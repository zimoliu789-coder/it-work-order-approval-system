package com.enterprise.ticket.module.approvalflow.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.ApprovalFlowStatus;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.form.FormSchema;
import com.enterprise.ticket.common.flow.ApproverRuleType;
import com.enterprise.ticket.common.flow.FlowCondition;
import com.enterprise.ticket.common.flow.FlowDefinition;
import com.enterprise.ticket.common.flow.FlowDefinitionCodec;
import com.enterprise.ticket.common.flow.FlowDefinitionValidator;
import com.enterprise.ticket.common.flow.FlowScope;
import com.enterprise.ticket.common.util.SecurityUtils;
import com.enterprise.ticket.module.approvalflow.dto.ApprovalFlowDuplicateRequest;
import com.enterprise.ticket.module.approvalflow.dto.ApprovalFlowSaveRequest;
import com.enterprise.ticket.module.approvalflow.dto.vo.ApprovalFlowDetailVO;
import com.enterprise.ticket.module.approvalflow.dto.vo.ApprovalFlowVO;
import com.enterprise.ticket.module.approvalflow.dto.vo.ApprovalFlowVersionVO;
import com.enterprise.ticket.module.approvalflow.dto.vo.FlowDesignMetaVO;
import com.enterprise.ticket.module.approvalflow.entity.ApprovalFlow;
import com.enterprise.ticket.module.approvalflow.entity.ApprovalFlowVersion;
import com.enterprise.ticket.module.approvalflow.mapper.ApprovalFlowMapper;
import com.enterprise.ticket.module.approvalflow.mapper.ApprovalFlowVersionMapper;
import com.enterprise.ticket.module.approvalflow.service.ApprovalFlowService;
import com.enterprise.ticket.module.applytype.entity.ApplyType;
import com.enterprise.ticket.module.applytype.mapper.ApplyTypeMapper;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 审批流程模板服务实现。
 *
 * <h2>与一期的三条同构语义</h2>
 * <ol>
 *   <li><b>草稿宽松、发布严格</b>：保存草稿只要求能序列化；发布是唯一严格校验点
 *       （结构 / 可达 / 无环 / 穷尽分支 / 审批人规则完备）；</li>
 *   <li><b>发布即冻结</b>：已发布版本的定义不可修改；要改只能再存草稿、再发布新版本；</li>
 *   <li><b>被引用不可删</b>：流程被申请类型引用后只能停用，否则历史工单的流程快照会指向悬空。</li>
 * </ol>
 *
 * <h2>一个刻意的分工</h2>
 * 发布时**不**校验"条件字段是否真的存在于表单里"——流程模板独立于表单，
 * 发布时根本不知道将来被谁引用。这一层校验放在申请类型绑定表单与流程的那一刻
 * （{@link #validateAgainstForm}），以及提交时的再次求值兜底。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalFlowServiceImpl implements ApprovalFlowService {

    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{1,31}$");

    /** {@code flow_code} 库列长度上限（{@code VARCHAR(32)}）：派生复制编码时用于截断。 */
    private static final int CODE_MAX_LENGTH = 32;

    /**
     * 发布闸门文案（M2）。
     *
     * <p>提成常量而不是内联在方法里，是因为它是 M4a「publish 首条 message == validate 端点首项」
     * 这条契约的**字面载体** —— 两处引用同一个常量，契约就不会因为某次文案微调而悄悄失效；
     * 包级可见性让单测能直接比对，而不必靠"包含某个关键词"这种脆弱断言。
     */
    static final String RUNTIME_GATE_MESSAGE =
            "该流程包含运行期条件（驳回改道 / 超时加签 / 引用上一节点结果等），"
                    + "需先在「系统配置」中开启「运行时条件引擎」总开关后再发布";

    private final ApprovalFlowMapper flowMapper;
    private final ApprovalFlowVersionMapper versionMapper;
    private final ApplyTypeMapper applyTypeMapper;

    /**
     * 系统配置出口：仅用于读取运行时条件引擎总开关。
     *
     * <p>发布侧闸门放在服务层而不是校验器里，是因为校验器是一个<b>无 Spring 依赖的纯函数静态类</b>
     * （这样它才能被单测穷尽覆盖、也能被前端金样例共享），而"开关是否开启"是运行期环境状态。
     * 把环境状态塞进纯校验器会让它失去可穷尽测试的性质。
     */
    private final SystemConfigService systemConfigService;

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    @Override
    public List<ApprovalFlowVO> listFlows() {
        List<ApprovalFlow> flows = flowMapper.selectList(Wrappers.<ApprovalFlow>lambdaQuery()
                .orderByAsc(ApprovalFlow::getId));
        if (flows.isEmpty()) {
            return List.of();
        }
        Map<Long, List<ApprovalFlowVersion>> versionsByFlow = versionMapper.selectList(
                        Wrappers.<ApprovalFlowVersion>lambdaQuery().orderByDesc(ApprovalFlowVersion::getVersionNo))
                .stream()
                .collect(Collectors.groupingBy(ApprovalFlowVersion::getFlowId, LinkedHashMap::new,
                        Collectors.toList()));
        Map<Long, Long> usageByVersion = usageByVersion(versionsByFlow.values().stream()
                .flatMap(List::stream).map(ApprovalFlowVersion::getId).collect(Collectors.toSet()));

        List<ApprovalFlowVO> result = new ArrayList<>();
        for (ApprovalFlow flow : flows) {
            List<ApprovalFlowVersion> versions = versionsByFlow.getOrDefault(flow.getId(), List.of());
            ApprovalFlowVersion published = versions.stream()
                    .filter(version -> !version.isDraft())
                    .max(Comparator.comparing(ApprovalFlowVersion::getVersionNo))
                    .orElse(null);
            boolean hasDraft = versions.stream().anyMatch(ApprovalFlowVersion::isDraft);
            long used = versions.stream()
                    .mapToLong(version -> usageByVersion.getOrDefault(version.getId(), 0L))
                    .sum();

            ApprovalFlowVO vo = new ApprovalFlowVO();
            vo.setId(flow.getId());
            vo.setFlowCode(flow.getFlowCode());
            vo.setFlowName(flow.getFlowName());
            vo.setDescription(flow.getDescription());
            vo.setStatus(flow.getStatus());
            vo.setStatusLabel(ApprovalFlowStatus.labelOf(flow.getStatus()));
            if (published != null) {
                vo.setLatestPublishedVersionNo(published.getVersionNo());
                vo.setLatestPublishedVersionId(published.getId());
                vo.setNodeCount(published.getNodeCount());
            }
            vo.setHasDraft(hasDraft);
            vo.setUsedByTypeCount(used);
            vo.setCreatedAt(flow.getCreatedAt());
            vo.setUpdatedAt(flow.getUpdatedAt());
            result.add(vo);
        }
        return result;
    }

    @Override
    public ApprovalFlowDetailVO getDetail(Long flowId) {
        ApprovalFlow flow = requireFlow(flowId);
        List<ApprovalFlowVersion> versions = listVersionEntities(flowId);

        ApprovalFlowVersion draft = versions.stream().filter(ApprovalFlowVersion::isDraft).findFirst().orElse(null);
        ApprovalFlowVersion published = versions.stream()
                .filter(version -> !version.isDraft())
                .max(Comparator.comparing(ApprovalFlowVersion::getVersionNo))
                .orElse(null);

        ApprovalFlowVersion effective = draft != null ? draft : published;

        ApprovalFlowDetailVO vo = new ApprovalFlowDetailVO();
        vo.setId(flow.getId());
        vo.setFlowCode(flow.getFlowCode());
        vo.setFlowName(flow.getFlowName());
        vo.setDescription(flow.getDescription());
        vo.setStatus(flow.getStatus());
        vo.setStatusLabel(ApprovalFlowStatus.labelOf(flow.getStatus()));
        if (draft != null) {
            vo.setDraftVersionId(draft.getId());
        }
        if (published != null) {
            vo.setPublishedVersionNo(published.getVersionNo());
        }
        vo.setDefinition(effective == null ? new FlowDefinition() : readDefinition(effective));
        vo.setVersions(versions.stream().map(version -> toVersionVO(version, false)).toList());
        vo.setCreatedAt(flow.getCreatedAt());
        vo.setUpdatedAt(flow.getUpdatedAt());
        return vo;
    }

    @Override
    public List<ApprovalFlowVersionVO> listVersions(Long flowId) {
        requireFlow(flowId);
        return listVersionEntities(flowId).stream().map(version -> toVersionVO(version, false)).toList();
    }

    @Override
    public ApprovalFlowVersionVO getVersion(Long versionId) {
        return toVersionVO(requireVersion(versionId), true);
    }

    // ------------------------------------------------------------------
    // 写入
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createFlow(ApprovalFlowSaveRequest request) {
        String flowCode = request.getFlowCode().trim();
        assertCodeFormat(flowCode);
        assertCodeAvailable(flowCode, null);

        ApprovalFlow flow = new ApprovalFlow();
        flow.setFlowCode(flowCode);
        flow.setFlowName(request.getFlowName().trim());
        flow.setDescription(trimToNull(request.getDescription()));
        flow.setStatus(ApprovalFlowStatus.DRAFT.name());
        flow.setCreatedBy(SecurityUtils.getCurrentUserId());
        flowMapper.insert(flow);

        ApprovalFlowVersion version = new ApprovalFlowVersion();
        version.setFlowId(flow.getId());
        version.setVersionNo(1);
        version.setDefinitionJson(FlowDefinitionCodec.write(
                request.getDefinition() == null ? new FlowDefinition() : request.getDefinition()));
        version.setNodeCount(0);
        versionMapper.insert(version);

        log.info("审批流程已创建 id={} code={} name={}，已生成 v1 草稿", flow.getId(), flowCode, flow.getFlowName());
        return flow.getId();
    }

    /**
     * 「另存为 / 复制」流程（ · W4-B）。
     *
     * <h2>为什么"复制的内容"要与 getDetail 同源</h2>
     * <p>源定义取「草稿优先，无草稿取最新已发布版本」，与 {@link #getDetail} 的选择口径**同一份逻辑**。
     * 若两者不一致（比如复制强行只取已发布版本），用户就会遇到"设计器里看到的是草稿、
     * 复制出来的却是上一版"这种无法解释的差异 —— 而"另存为"这个词的全部含义就是"所见即所存"。
     *
     * <h2>为什么复制出来一律 DRAFT</h2>
     * <p>复制出来的是一份**没人审过**的新模板。若把源模板的 PUBLISHED 状态一并带过来，
     * 它立刻就能被申请类型绑定 —— 即"复制即生效"。DRAFT 让复制产物必须先经过一次发布校验，
     * 与"新建流程"落在同一个起点上。
     *
     * <h2>为什么不复制申请类型 / 部门的绑定</h2>
     * <p>绑定是"谁在用这个模板"的事实，主体是被引用方（申请类型 / 部门），
     * 复制方无权代它新增一条指向新模板的绑定。这与设计文档「引用不复制」一致。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long duplicateFlow(Long sourceFlowId, ApprovalFlowDuplicateRequest request) {
        ApprovalFlow source = requireFlow(sourceFlowId);
        String flowCode = resolveDuplicateCode(source, request.getFlowCode());

        // 已按 versionNo 倒序；草稿优先（那是用户正在改的那一份），无草稿退到最新已发布版本。
        List<ApprovalFlowVersion> versions = listVersionEntities(sourceFlowId);
        ApprovalFlowVersion origin = versions.stream()
                .filter(ApprovalFlowVersion::isDraft)
                .findFirst()
                .orElseGet(() -> versions.stream()
                        .filter(version -> !version.isDraft())
                        .max(Comparator.comparing(ApprovalFlowVersion::getVersionNo))
                        .orElse(null));
        if (origin == null) {
            // createFlow 必定同时写一份 v1 草稿，正常数据走不到这里；
            // 只有"模板行还在、版本行被人工删光"的脏数据会。报"版本不存在"比静默复制出一个空流程诚实。
            throw new BusinessException(ErrorCode.FLOW_VERSION_NOT_FOUND,
                    "源流程没有任何版本，无法复制");
        }
        // 编解码往返 = 深拷贝：复制产出的定义与源模板不共享任何对象；
        // 同时把"源 JSON 读不出来"这件事提前到复制这一刻暴露，而不是等用户下次打开设计器才发现。
        String definitionJson = FlowDefinitionCodec.write(readDefinition(origin));

        ApprovalFlow flow = new ApprovalFlow();
        flow.setFlowCode(flowCode);
        flow.setFlowName(request.getFlowName().trim());
        flow.setDescription(duplicateDescription(request.getDescription(), source));
        flow.setStatus(ApprovalFlowStatus.DRAFT.name());
        flow.setCreatedBy(SecurityUtils.getCurrentUserId());
        try {
            flowMapper.insert(flow);
        } catch (DuplicateKeyException e) {
            // 派生编码天然有竞态：两个人同时复制同一份流程，会派生出同一个 XXX_COPY。
            // 预检（codeExists）只能缩小窗口、不能消除，因此这里必须把唯一索引的兜底
            // 转成与新建流程**同一个**错误码与文案，否则用户会看到一句看不懂的 500。
            throw new BusinessException(ErrorCode.FLOW_CODE_EXISTS);
        }

        ApprovalFlowVersion version = new ApprovalFlowVersion();
        version.setFlowId(flow.getId());
        version.setVersionNo(1);
        version.setDefinitionJson(definitionJson);
        // 与 createFlow 一致：node_count 是**发布**校验的产物，草稿阶段固定记 0
        version.setNodeCount(0);
        versionMapper.insert(version);

        log.info("审批流程 {}（{}）已复制为 {}（{}）id={}，来源版本 v{}（{}）",
                sourceFlowId, source.getFlowCode(), flowCode, flow.getFlowName(), flow.getId(),
                origin.getVersionNo(), origin.isDraft() ? "草稿" : "已发布");
        return flow.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateFlow(Long flowId, ApprovalFlowSaveRequest request) {
        ApprovalFlow flow = requireFlow(flowId);
        String flowCode = request.getFlowCode().trim();
        assertCodeFormat(flowCode);
        assertCodeAvailable(flowCode, flowId);

        flowMapper.update(null, Wrappers.<ApprovalFlow>lambdaUpdate()
                .eq(ApprovalFlow::getId, flowId)
                .set(ApprovalFlow::getFlowCode, flowCode)
                .set(ApprovalFlow::getFlowName, request.getFlowName().trim())
                .set(ApprovalFlow::getDescription, trimToNull(request.getDescription())));

        ApprovalFlowVersion draft = listVersionEntities(flowId).stream()
                .filter(ApprovalFlowVersion::isDraft)
                .findFirst()
                .orElse(null);
        if (draft == null) {
            // 「编辑已发布流程自动另开一版草稿」：绝不改动已发布版本
            draft = new ApprovalFlowVersion();
            draft.setFlowId(flowId);
            draft.setVersionNo(listVersionEntities(flowId).stream()
                    .mapToInt(ApprovalFlowVersion::getVersionNo).max().orElse(0) + 1);
            draft.setNodeCount(0);
            draft.setDefinitionJson(FlowDefinitionCodec.write(
                    request.getDefinition() == null ? new FlowDefinition() : request.getDefinition()));
            versionMapper.insert(draft);
            log.info("流程 {} 已发布版本保持冻结，另开草稿 v{}", flowId, draft.getVersionNo());
            return;
        }
        versionMapper.update(null, Wrappers.<ApprovalFlowVersion>lambdaUpdate()
                .eq(ApprovalFlowVersion::getId, draft.getId())
                .set(ApprovalFlowVersion::getDefinitionJson, FlowDefinitionCodec.write(
                        request.getDefinition() == null ? new FlowDefinition() : request.getDefinition())));
        log.info("流程 {} 草稿 v{} 已保存（来源 {} → {}）", flowId, draft.getVersionNo(),
                flow.getFlowCode(), flowCode);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long publish(Long flowId) {
        ApprovalFlow flow = requireFlow(flowId);
        ApprovalFlowVersion draft = listVersionEntities(flowId).stream()
                .filter(ApprovalFlowVersion::isDraft)
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.FLOW_VERSION_NOT_FOUND,
                        "没有可发布的草稿版本，请先保存草稿"));

        FlowDefinition definition = readDefinition(draft);
        // 发布闸门（M2）：含运行期特性的定义在总开关关闭时**拒绝发布**。
        // 放在结构校验之前，使「publish 抛出的首条 message」与「validate 端点返回的首项」
        // 恒为同一条 —— 这是 M4a 建立的契约，前端据此做预检提示。
        String runtimeProblem = runtimeFeatureProblem(definition);
        if (runtimeProblem != null) {
            throw new BusinessException(ErrorCode.FLOW_RUNTIME_CONDITION_DISABLED, runtimeProblem);
        }
        // 发布是唯一严格校验点：结构 / 可达 / 无环 / 穷尽分支 / 审批人规则完备。
        // 此处 schema 传 null —— 流程独立于表单，条件字段的存在性要等申请类型绑定时才判定。
        FlowDefinitionValidator.validate(definition, null);
        int nodeCount = FlowDefinitionValidator.countApprovalNodes(definition);

        versionMapper.update(null, Wrappers.<ApprovalFlowVersion>lambdaUpdate()
                .eq(ApprovalFlowVersion::getId, draft.getId())
                .set(ApprovalFlowVersion::getPublishedAt, LocalDateTime.now())
                .set(ApprovalFlowVersion::getPublishedBy, SecurityUtils.getCurrentUserId())
                .set(ApprovalFlowVersion::getNodeCount, nodeCount));

        if (!ApprovalFlowStatus.PUBLISHED.name().equals(flow.getStatus())) {
            flowMapper.update(null, Wrappers.<ApprovalFlow>lambdaUpdate()
                    .eq(ApprovalFlow::getId, flowId)
                    .set(ApprovalFlow::getStatus, ApprovalFlowStatus.PUBLISHED.name()));
        }
        log.info("审批流程 {}（{}）已发布 v{}，审批节点 {} 个", flowId, flow.getFlowCode(),
                draft.getVersionNo(), nodeCount);
        return draft.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long flowId, String status) {
        ApprovalFlow flow = requireFlow(flowId);
        ApprovalFlowStatus target = ApprovalFlowStatus.of(status);
        if (target == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "流程状态取值不合法：" + status);
        }
        if (target.name().equals(flow.getStatus())) {
            return;
        }
        flowMapper.update(null, Wrappers.<ApprovalFlow>lambdaUpdate()
                .eq(ApprovalFlow::getId, flowId)
                .set(ApprovalFlow::getStatus, target.name()));
        log.info("审批流程 {}（{}）状态 {} → {}", flowId, flow.getFlowName(), flow.getStatus(), target.name());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteFlow(Long flowId) {
        ApprovalFlow flow = requireFlow(flowId);
        long used = usageCount(flowId);
        if (used > 0) {
            throw new BusinessException(ErrorCode.FLOW_IN_USE,
                    "该审批流程已被 " + used + " 个申请类型引用，只能停用不能删除");
        }
        versionMapper.delete(Wrappers.<ApprovalFlowVersion>lambdaQuery()
                .eq(ApprovalFlowVersion::getFlowId, flowId));
        flowMapper.deleteById(flowId);
        log.info("审批流程 {}（{}）已删除（含全部版本）", flowId, flow.getFlowName());
    }

    // ------------------------------------------------------------------
    // 跨模块契约
    // ------------------------------------------------------------------

    @Override
    public FlowDefinition requirePublishedDefinition(Long versionId) {
        ApprovalFlowVersion version = requireVersion(versionId);
        if (version.isDraft()) {
            throw new BusinessException(ErrorCode.FLOW_NO_PUBLISHED_VERSION,
                    "该审批流程版本尚未发布，不能用于提交");
        }
        return readDefinition(version);
    }

    @Override
    public boolean isPublishedVersion(Long versionId) {
        if (versionId == null) {
            return false;
        }
        ApprovalFlowVersion version = versionMapper.selectById(versionId);
        return version != null && !version.isDraft();
    }

    @Override
    public String describeVersion(Long versionId) {
        if (versionId == null) {
            return null;
        }
        ApprovalFlowVersion version = versionMapper.selectById(versionId);
        if (version == null) {
            return null;
        }
        ApprovalFlow flow = flowMapper.selectById(version.getFlowId());
        String name = flow == null ? "流程" : flow.getFlowName();
        return name + " v" + version.getVersionNo();
    }

    @Override
    public String flowNameOfVersion(Long versionId) {
        if (versionId == null) {
            return null;
        }
        ApprovalFlowVersion version = versionMapper.selectById(versionId);
        if (version == null) {
            return null;
        }
        ApprovalFlow flow = flowMapper.selectById(version.getFlowId());
        // 模板行比版本行更容易被删（删模板会连着版本一起删，但人工改库只删 flow 也可能），
        // 因此这里不抛异常、不做兜底名字：拿不到就返回 null，
        // 由调用方（工单提交）决定"没有名字时到底写不写"——静默编一个假名字会更糟。
        return flow == null ? null : flow.getFlowName();
    }

    @Override
    public void validateAgainstForm(Long versionId, FormSchema schema) {
        ApprovalFlowVersion version = requireVersion(versionId);
        FlowDefinition definition = readDefinition(version);
        // 结构 + 条件字段（此时 schema 已知，条件字段必须存在于表单且类型可比）
        FlowDefinitionValidator.validate(definition, schema);
    }

    @Override
    public List<String> validateDefinition(FlowDefinition definition, FormSchema schema) {
        return validateDefinition(definition, schema, null);
    }

    /**
     * 校验一份流程定义（M4a）并返回**全部**问题；首项与 {@code publish()} 抛出的 message 同源。
     *
     * <h2>为什么运行期闸门必须排在校验器问题之前（M2 修正）</h2>
     * <p>发布闸门住在 {@code publish()} 里（它要读总开关，属于环境状态，校验器是无 Spring 依赖的纯函数）。
     * 但"同源同序"这条契约要求的是**端到端**一致：前端在发布前会先调本端点做预检，
     * 若这里不把闸门算进去，就会出现 M1 已经踩过的那个坑 ——
     * <b>预检全绿 → 用户点发布 → 被拒</b>，而拒绝理由在预检结果里根本没出现过。
     * 用户只会认为系统自相矛盾。
     *
     * <p>因此这里把闸门结果**前置**为列表首项，与 {@code publish()} 的抛错顺序严格对齐；
     * 开关开启或定义不含运行期特性时它返回 {@code null}，列表内容与改造前逐项一致（零回归）。
     */
    @Override
    public List<String> validateDefinition(FlowDefinition definition, FormSchema schema, FlowScope scope) {
        List<String> problems = new ArrayList<>();
        // 与 publish() 的第一步同源（同一个私有方法、同一条文案），保证"首项 == 首条 message"
        String runtimeProblem = runtimeFeatureProblem(definition);
        if (runtimeProblem != null) {
            problems.add(runtimeProblem);
        }
        problems.addAll(FlowDefinitionValidator.collectProblems(definition, schema, scope));
        return problems;
    }

    /**
     * 设计器元数据（ · W4-D / C8）：把「深度上限 + 各域禁用集 + 各来源参数槽位」
     * 从后端枚举原样下发。
     *
     * <h2>为什么不是硬编码一份响应</h2>
     * <p>如果这里手写字面量（{@code setConditionMaxDepth(3)}、{@code List.of("FORM_USER_FIELD", …)}），
     * 就只是把"前端那份副本"搬到了后端 —— 依旧存在第二份事实源，漂移照旧。
     * 因此三个数组全部**遍历枚举**生成，枚举改动自动体现在响应里。
     *
     * <h2>顺序为什么重要</h2>
     * <p>遍历 {@code values()} 得到的是**声明顺序**，稳定且可断言；前端按同一顺序渲染下拉，
     * 就不会出现"同一条规则这次排第三、下次排第一"的抖动。
     */
    @Override
    public FlowDesignMetaVO designMeta() {
        FlowDesignMetaVO meta = new FlowDesignMetaVO();
        meta.setConditionMaxDepth(FlowCondition.MAX_DEPTH);

        List<FlowDesignMetaVO.Scope> scopes = new ArrayList<>();
        for (FlowScope scope : FlowScope.values()) {
            FlowDesignMetaVO.Scope item = new FlowDesignMetaVO.Scope();
            item.setCode(scope.name());
            item.setLabel(scope.getLabel());
            item.setForbiddenRuleTypes(scope.forbiddenRuleTypes().stream()
                    .map(ApproverRuleType::name)
                    .toList());
            scopes.add(item);
        }
        meta.setScopes(scopes);

        List<FlowDesignMetaVO.RuleType> ruleTypes = new ArrayList<>();
        for (ApproverRuleType type : ApproverRuleType.values()) {
            FlowDesignMetaVO.RuleType item = new FlowDesignMetaVO.RuleType();
            item.setCode(type.name());
            item.setLabel(type.getLabel());
            item.setParams(type.params());
            ruleTypes.add(item);
        }
        meta.setRuleTypes(ruleTypes);
        return meta;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private ApprovalFlow requireFlow(Long flowId) {
        ApprovalFlow flow = flowMapper.selectById(flowId);
        if (flow == null) {
            throw new BusinessException(ErrorCode.FLOW_NOT_FOUND);
        }
        return flow;
    }

    private ApprovalFlowVersion requireVersion(Long versionId) {
        ApprovalFlowVersion version = versionMapper.selectById(versionId);
        if (version == null) {
            throw new BusinessException(ErrorCode.FLOW_VERSION_NOT_FOUND);
        }
        return version;
    }

    private List<ApprovalFlowVersion> listVersionEntities(Long flowId) {
        return versionMapper.selectList(Wrappers.<ApprovalFlowVersion>lambdaQuery()
                .eq(ApprovalFlowVersion::getFlowId, flowId)
                .orderByDesc(ApprovalFlowVersion::getVersionNo));
    }

    private FlowDefinition readDefinition(ApprovalFlowVersion version) {
        if (!StringUtils.hasText(version.getDefinitionJson())) {
            return new FlowDefinition();
        }
        return FlowDefinitionCodec.read(version.getDefinitionJson());
    }

    /**
     * 发布闸门：定义含运行期特性且总开关关闭时返回拒绝理由，否则返回 {@code null}。
     *
     * <p>为什么闸门在<b>发布</b>而不是提交：运行期条件（驳回改道 / 超时加签 / 引用 {@code process.*}）
     * 一旦被发布，就会让所有后来提交的工单走 INACTIVE 骨架 —— 单单靠"提交时短路"是拦不住的，
     * 因为提交侧短路只能保证"不写 INACTIVE 行"，却无法阻止定义本身被启用。把闸门放在唯一的严格校验点，
     * 使「开关关闭 ⇒ 系统中不存在在途的运行期定义」，这是回滚干净的前提。
     *
     * <p>与校验器分层：这里是<b>环境状态</b>判断（读开关），校验器是<b>无 Spring 依赖的纯函数</b>，
     * 二者职责不混。放在结构校验之前，使 publish 抛出的首条 message 与 validate 端点首项恒同源（M4a 契约）。
     */
    private String runtimeFeatureProblem(FlowDefinition definition) {
        if (definition != null && definition.hasRuntimeFeature()
                && !systemConfigService.flowRuntimeConditionEnabled()) {
            return RUNTIME_GATE_MESSAGE;
        }
        return null;
    }

    private ApprovalFlowVersionVO toVersionVO(ApprovalFlowVersion version, boolean withDefinition) {
        ApprovalFlowVersionVO vo = new ApprovalFlowVersionVO();
        vo.setId(version.getId());
        vo.setFlowId(version.getFlowId());
        vo.setVersionNo(version.getVersionNo());
        vo.setDraft(version.isDraft());
        vo.setNodeCount(version.getNodeCount());
        vo.setPublishedAt(version.getPublishedAt());
        if (withDefinition) {
            vo.setDefinition(readDefinition(version));
        }
        return vo;
    }

    /**
     * 解析复制产物的流程编码：调用方指定了就沿用（格式 / 唯一性校验与新建流程**完全同源**），
     * 没指定就按源编码派生一个可用值。
     *
     * <p>为什么要允许派生：{@code flow_code} 有唯一索引，且前端在设计器里把它置为只读，
     * 创建后没有任何修改入口。程序化调用方（回归脚本 / 集成测试）若被迫自己规避唯一约束，
     * 就得在脚本里再实现一遍"加后缀 + 查重"——那正是这个方法要做的事，
     * 没必要让每个调用方各写一份。
     */
    private String resolveDuplicateCode(ApprovalFlow source, String requested) {
        if (StringUtils.hasText(requested)) {
            String code = requested.trim();
            assertCodeFormat(code);
            assertCodeAvailable(code, null);
            return code;
        }
        String base = source.getFlowCode();
        String candidate = copyCode(base, "");
        int seq = 2;
        while (codeExists(candidate, null)) {
            candidate = copyCode(base, String.valueOf(seq));
            seq++;
        }
        return candidate;
    }

    /**
     * 拼一个 {@code {源编码}_COPY[序号]} 形式的候选编码，并按库列上限截断。
     *
     * <p>截断是必须的：源编码最长 32（见 {@link #CODE_PATTERN}），加后缀必然溢出
     * {@code VARCHAR(32)}。而截断会让"两个不同的源编码"可能派生出同一个候选值，
     * 因此调用方**必须循环查重**（见 {@link #resolveDuplicateCode}），只试一次会漏。
     *
     * @param seq 序号；<b>空串表示第一轮</b>（后缀即 {@code _COPY}，而不是 {@code _COPY1}）
     */
    private static String copyCode(String baseCode, String seq) {
        String suffix = "_COPY" + seq;
        int headLength = CODE_MAX_LENGTH - suffix.length();
        String head = baseCode.length() > headLength ? baseCode.substring(0, headLength) : baseCode;
        return head + suffix;
    }

    /** 复制产物的说明：调用方给了就用给的，没给就沿用源流程的（说明属于模板内容，随内容一起走）。 */
    private static String duplicateDescription(String requested, ApprovalFlow source) {
        return StringUtils.hasText(requested) ? trimToNull(requested) : source.getDescription();
    }

    private void assertCodeFormat(String flowCode) {
        if (!CODE_PATTERN.matcher(flowCode).matches()) {
            throw new BusinessException(ErrorCode.FLOW_CODE_INVALID);
        }
    }

    private void assertCodeAvailable(String flowCode, Long excludeId) {
        if (codeExists(flowCode, excludeId)) {
            throw new BusinessException(ErrorCode.FLOW_CODE_EXISTS);
        }
    }

    /** 编码是否已被占用（{@code excludeId} 非空时排除自身）；派生复制编码时需要问「这个可用吗」。 */
    private boolean codeExists(String flowCode, Long excludeId) {
        Long count = flowMapper.selectCount(Wrappers.<ApprovalFlow>lambdaQuery()
                .eq(ApprovalFlow::getFlowCode, flowCode)
                .ne(excludeId != null, ApprovalFlow::getId, excludeId));
        return count != null && count > 0;
    }

    /** 某流程被多少个申请类型引用（按该流程的所有版本统计） */
    private long usageCount(Long flowId) {
        Set<Long> versionIds = versionMapper.selectList(Wrappers.<ApprovalFlowVersion>lambdaQuery()
                        .eq(ApprovalFlowVersion::getFlowId, flowId)).stream()
                .map(ApprovalFlowVersion::getId)
                .collect(Collectors.toSet());
        if (versionIds.isEmpty()) {
            return 0L;
        }
        return usageByVersion(versionIds).values().stream().mapToLong(Long::longValue).sum();
    }

    private Map<Long, Long> usageByVersion(Set<Long> versionIds) {
        if (versionIds.isEmpty()) {
            return Map.of();
        }
        List<ApplyType> types = applyTypeMapper.selectList(Wrappers.<ApplyType>lambdaQuery()
                .in(ApplyType::getApprovalFlowVersionId, versionIds));
        Map<Long, Long> counts = new LinkedHashMap<>();
        for (ApplyType type : types) {
            if (type.getApprovalFlowVersionId() != null) {
                counts.merge(type.getApprovalFlowVersionId(), 1L, Long::sum);
            }
        }
        return counts;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
