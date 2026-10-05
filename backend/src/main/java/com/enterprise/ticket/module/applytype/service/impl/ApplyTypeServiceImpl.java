package com.enterprise.ticket.module.applytype.service.impl;

import com.enterprise.ticket.module.applytype.dto.ApplyConfigCreateRequest;
import com.enterprise.ticket.module.approvalflow.dto.ApprovalFlowSaveRequest;
import com.enterprise.ticket.module.form.dto.FormTemplateSaveRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Locale;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.api.PageResult;
import com.enterprise.ticket.common.constant.ApplyTypeStatus;
import com.enterprise.ticket.common.constant.ApprovalMode;
import com.enterprise.ticket.common.constant.RoleCode;
import com.enterprise.ticket.common.constant.SignType;
import com.enterprise.ticket.common.constant.SubmitPermissionType;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.form.FormSchema;
import com.enterprise.ticket.common.form.FormSchemaCodec;
import com.enterprise.ticket.common.form.FormSchemaValidator;
import com.enterprise.ticket.common.flow.ApproverRule;
import com.enterprise.ticket.common.flow.ApproverRuleResolver;
import com.enterprise.ticket.common.flow.ApproverRuleType;
import com.enterprise.ticket.common.flow.FlowDefinition;
import com.enterprise.ticket.common.flow.FlowNode;
import com.enterprise.ticket.common.flow.FlowPathResolver;
import com.enterprise.ticket.common.util.SecurityUtils;
import com.enterprise.ticket.module.approvalflow.entity.ApprovalFlow;
import com.enterprise.ticket.module.approvalflow.entity.ApprovalFlowVersion;
import com.enterprise.ticket.module.approvalflow.mapper.ApprovalFlowMapper;
import com.enterprise.ticket.module.approvalflow.mapper.ApprovalFlowVersionMapper;
import com.enterprise.ticket.module.approvalflow.service.ApprovalFlowService;
import com.enterprise.ticket.module.applytype.dto.ApplyTypeSaveRequest;
import com.enterprise.ticket.module.applytype.dto.vo.ApplyTypeOptionVO;
import com.enterprise.ticket.module.applytype.dto.vo.ApplyTypeVO;
import com.enterprise.ticket.module.applytype.dto.vo.FlowPreviewVO;
import com.enterprise.ticket.module.applytype.entity.ApplyType;
import com.enterprise.ticket.module.applytype.mapper.ApplyTypeMapper;
import com.enterprise.ticket.module.applytype.service.ApplyTypeService;
import com.enterprise.ticket.module.permission.service.PermissionApplyPolicyService;
import com.enterprise.ticket.module.permission.support.PermissionApplySchemaSupport;
import com.enterprise.ticket.module.department.entity.Department;
import com.enterprise.ticket.module.department.mapper.DepartmentMapper;
import com.enterprise.ticket.module.form.entity.FormTemplate;
import com.enterprise.ticket.module.form.entity.FormTemplateVersion;
import com.enterprise.ticket.module.form.mapper.FormTemplateMapper;
import com.enterprise.ticket.module.form.mapper.FormTemplateVersionMapper;
import com.enterprise.ticket.module.form.service.FormTemplateService;
import com.enterprise.ticket.module.order.entity.Order;
import com.enterprise.ticket.module.order.mapper.OrderMapper;
import com.enterprise.ticket.module.role.entity.SysRole;
import com.enterprise.ticket.module.role.mapper.SysRoleMapper;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 申请类型服务实现
 *
 * <h2>三条必须在写库前完成的完整性校验</h2>
 * <ol>
 *   <li><b>关联版本必须已发布</b> —— 引用一份草稿等于引用一份随时会被覆盖的配置。
 *       用户按草稿填完提交，回头想核对时表单已经不是当初那版了；</li>
 *   <li><b>提交权限值必须真实存在</b> —— ROLE 模式查角色表、GROUP 模式查部门表。
 *       不校验的话，管理员手滑写错一个角色码，界面上一切正常（配置保存成功），
 *       但谁也提交不了，而且不会有任何报错提示指出原因；</li>
 *   <li><b>类型编码唯一</b> —— 预检 + 唯一索引双层兜底（与项目既有做法一致，
 *       并发下靠 {@code DuplicateKeyException} 兜住）。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApplyTypeServiceImpl implements ApplyTypeService {

    /**
     * 预览响应里「申请人自选」候选人的**首屏条数上限**（ · W4-D）。
     *
     * <p>取 50 的理由：按角色 / 分组限定的候选人远少于 50，能一次装下（与改造前行为一致）；
     * 只有「范围 = 全部员工」才会真正触发截断，而那种场景用户本来就是靠输入姓名找人，
     * 首屏给再多也很难用。
     *
     * <p>与 {@link #MAX_CANDIDATE_PAGE_SIZE} 的分工：前者是「预览里预装多少条」，
     * 后者是「搜索接口单页硬上限」（防御前端传 size=100000 拖库）。
     */
    private static final int CANDIDATE_PREVIEW_LIMIT = 50;

    /** 候选人搜索接口的单页硬上限（与其它列表页同一口径：钳住 size，防大页查询） */
    private static final long MAX_CANDIDATE_PAGE_SIZE = 100L;

    private final ApplyTypeMapper applyTypeMapper;
    private final FormTemplateMapper templateMapper;
    private final FormTemplateVersionMapper versionMapper;
    private final FormTemplateService formTemplateService;
    /** 「流程版本必须已发布」「条件字段必须存在于所绑表单」两条校验走流程模块的公开契约 */
    private final ApprovalFlowService approvalFlowService;
    /** 仅用于列表装配流程名（批量取，避免逐行两条查询） */
    private final ApprovalFlowVersionMapper flowVersionMapper;
    private final ApprovalFlowMapper flowMapper;
    /** 预览「申请人自选」的可选人员池（与提交时的解析共用同一份判定，避免两处口径漂移） */
    private final ApproverRuleResolver approverRuleResolver;
    private final SysRoleMapper roleMapper;
    private final DepartmentMapper departmentMapper;
    private final UserMapper userMapper;
    /** 仅用于「是否已被工单使用」的统计（删除保护与列表提示），不做任何业务调用 */
    private final OrderMapper orderMapper;
    /**
     * 可申请权限策略（P1 安全修复）：用于把「系统权限申请」表单的 {@code permissionCodes} 选项
     * 按当前策略**动态下发**，与角色页的「可申请权限配置」保持同源。
     */
    private final PermissionApplyPolicyService permissionApplyPolicyService;

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    @Override
    public List<ApplyTypeVO> listAll() {
        List<ApplyType> types = applyTypeMapper.selectList(Wrappers.<ApplyType>lambdaQuery()
                .orderByAsc(ApplyType::getSortOrder)
                .orderByAsc(ApplyType::getId));
        if (types.isEmpty()) {
            return List.of();
        }
        Context context = loadContext(types);
        return types.stream().map(type -> toVO(type, context, false)).toList();
    }

    @Override
    public ApplyTypeVO getDetail(Long id) {
        ApplyType type = requireType(id);
        // 详情会带上 schema（提交页据此渲染动态表单）。普通员工只能看「自己可提交」的类型 ——
        // 否则任何登录用户都能按 id 遍历出全部申请类型的表单定义与配置（越权枚举），
        // 这与本模块对 /enabled 的处理口径必须一致：前端过滤等于没过滤，服务端必须判。
        // admin 及以上用于管理端查看，不受提交范围限制。
        if (!RoleCode.isAdminOrAbove(currentRole())) {
            // 非管理角色：只有「已启用且自己可提交」的类型才返回详情，其余一律按不存在处理。
            // 之所以不区分「已停用 / 无提交权限」，是因为那种区分会给出一个可枚举的口径
            // （逐个大 id 试探即可推断某类型的启停与授权范围），与项目「越权统一 404」的既有约定一致。
            boolean submittable = ApplyTypeStatus.ENABLED.name().equals(type.getStatus())
                    && canSubmit(type, requireCurrentUser());
            if (!submittable) {
                throw new BusinessException(ErrorCode.APPLY_TYPE_NOT_FOUND);
            }
        }
        Context context = loadContext(List.of(type));
        // 详情才带 schema：提交页要用它渲染动态表单，而普通员工没有 form_template:view 权限
        return toVO(type, context, true);
    }

    @Override
    public List<ApplyTypeOptionVO> listEnabledForCurrentUser() {
        User currentUser = requireCurrentUser();
        List<ApplyType> types = applyTypeMapper.selectList(Wrappers.<ApplyType>lambdaQuery()
                .eq(ApplyType::getStatus, ApplyTypeStatus.ENABLED.name())
                .orderByAsc(ApplyType::getSortOrder)
                .orderByAsc(ApplyType::getId));
        // 权限过滤放在服务端：前端过滤等于没过滤（直调接口即可绕过）
        return types.stream()
                .filter(type -> canSubmit(type, currentUser))
                .map(this::toOptionVO)
                .toList();
    }

    // ------------------------------------------------------------------
    // 审批流程预览
    // ------------------------------------------------------------------

    @Override
    public FlowPreviewVO flowPreview(Long id, Map<String, Object> formData) {
        ApplyType type = requireSubmittable(id);
        ApprovalMode mode = ApprovalMode.of(type.getApprovalMode());
        if (mode != ApprovalMode.FLOW || type.getApprovalFlowVersionId() == null) {
            // NONE / GROUP 两条路径不存在「申请人自选审批人」这回事。
            // 返回 flowUsed=false 让前端整块隐藏，而不是丢一个需要前端再判空的空结构。
            FlowPreviewVO empty = new FlowPreviewVO();
            empty.setFlowUsed(false);
            empty.setNodes(List.of());
            empty.setChooseRequirements(List.of());
            return empty;
        }
        FlowDefinition definition = approvalFlowService.requirePublishedDefinition(type.getApprovalFlowVersionId());
        FormSchema schema = formTemplateService.requirePublishedSchema(type.getFormTemplateVersionId());
        Map<String, Object> data = formData == null ? Map.of() : formData;

        // 预览刻意不跑 FormDataValidator：用户正在填表，此刻本就字段不全，
        // 拿「必填未填」打断预览毫无意义。条件求值对缺失值按「空」处理，正是想要的行为。
        FlowPathResolver.Result path = FlowPathResolver.resolve(definition, schema, data);

        FlowPreviewVO vo = new FlowPreviewVO();
        vo.setFlowUsed(true);
        vo.setApprovalFlowName(approvalFlowService.describeVersion(type.getApprovalFlowVersionId()));
        vo.setNodes(path.nodes().stream().map(ApplyTypeServiceImpl::toPreviewNode).toList());
        vo.setChooseRequirements(chooseRequirements(path));
        return vo;
    }

    /**
     * 「申请人自选」候选人的**分页搜索**（ · W4-D）。
     *
     * <h2>为什么必须有这条通路</h2>
     * <p>预览里的候选池被截断成首屏若干条后，若没有搜索通路，「全部员工」这类大池子里
     * 排在后面的同事就**真的选不到** —— 那不是"少加载一点"的优化，而是功能缺失。
     * 因此截断与搜索必须同时到位。
     *
     * <h2>它与提交页的关系</h2>
     * <p>权限口径与 {@link #flowPreview} 完全一致（都走 {@code requireSubmittable}）：
     * 能预览 = 能搜，不可提交的用户连候选人都拿不到，避免它变成枚举在职员工的旁路。
     * 人员名册本身不是敏感数据（提交页本来就要选人），但把"谁能读"与"谁能提交"对齐
     * 可以避免权限面出现第二条口径。
     *
     * <h2>nodeKey 找不到时返回空页而不是报错</h2>
     * <p>nodeKey 由前端从预览结果里带过来。若作者在此期间改了流程并重新发布，
     * 该节点可能已不存在或已不是「申请人自选」—— 此时正确行为是"现在不需要你选人"，
     * 而不是给提交页弹一个报错。真正的安全边界在提交时的重算校验，这里只是 UI 取数。
     *
     * @param nodeKey 预览结果里的节点标识（必填）
     * @param keyword 姓名 / 登录名模糊匹配，可为空
     */
    @Override
    public PageResult<FlowPreviewVO.Candidate> chooseCandidates(Long id, String nodeKey, String keyword,
                                                               long page, long size) {
        ApplyType type = requireSubmittable(id);
        long safePage = Math.max(page, 1L);
        long safeSize = Math.min(Math.max(size, 1L), MAX_CANDIDATE_PAGE_SIZE);
        if (!StringUtils.hasText(nodeKey)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "缺少节点标识 nodeKey");
        }
        ApproverRule chooseRule = findChooseRule(type, nodeKey.trim());
        if (chooseRule == null) {
            return PageResult.empty(safePage, safeSize);
        }
        IPage<User> result = approverRuleResolver.searchChoosable(chooseRule, keyword, safePage, safeSize);
        return PageResult.of(result, ApplyTypeServiceImpl::toCandidate);
    }

    /**
     * 在申请类型所绑的流程定义里定位某个节点上的「申请人自选」规则；找不到返回 {@code null}。
     *
     * <p><b>刻意不重跑路径求值</b>：nodeKey 来自预览结果，能走到这里就说明它在命中路径上。
     * 重跑一次求值需要表单数据（提交页正在填，随时在变），代价与不确定性都不划算，
     * 而且结论也不会比"按 key 找节点"更可靠 —— 真正的范围与人数校验在提交时完成。
     */
    private ApproverRule findChooseRule(ApplyType type, String nodeKey) {
        if (type.getApprovalFlowVersionId() == null
                || ApprovalMode.of(type.getApprovalMode()) != ApprovalMode.FLOW) {
            return null;
        }
        FlowDefinition definition = approvalFlowService.requirePublishedDefinition(type.getApprovalFlowVersionId());
        for (FlowNode node : definition.getNodes()) {
            if (node == null || !nodeKey.equals(node.getKey())) {
                continue;
            }
            for (ApproverRule rule : node.getApproverRules()) {
                if (rule != null
                        && ApproverRuleType.of(rule.getType()) == ApproverRuleType.APPLICANT_CHOOSE) {
                    return rule;
                }
            }
        }
        return null;
    }

    private static FlowPreviewVO.Node toPreviewNode(FlowPathResolver.ResolvedNode resolved) {
        FlowPreviewVO.Node node = new FlowPreviewVO.Node();
        node.setNodeKey(resolved.nodeKey());
        node.setNodeName(resolved.nodeName());
        node.setSignType(resolved.signType());
        node.setSignTypeLabel(SignType.ALL_SIGN.equals(resolved.signType()) ? "会签" : "或签");
        node.setStepOrder(resolved.stepOrder());
        node.setOnPath(resolved.onPath());
        node.setConditionDesc(resolved.conditionDesc());
        return node;
    }

    /**
     * 命中路径上的「申请人自选」节点约束。
     *
     * <p>只遍历 {@code onPathNodes()} —— 这正是需求「被跳过的节点不需要选」的落地点：
     * 被条件分支绕开的节点根本不会出现在这里，前端也就不会为它渲染选择器。
     *
     * <p>发布校验已保证一个节点最多一条自选规则，因此这里一条规则对应一个选择器，
     * 提交端按 {@code nodeKey} 回传所选人时不会有归属歧义。
     */
    private List<FlowPreviewVO.ChooseRequirement> chooseRequirements(FlowPathResolver.Result path) {
        List<FlowPreviewVO.ChooseRequirement> result = new ArrayList<>();
        for (FlowPathResolver.ResolvedNode node : path.onPathNodes()) {
            for (ApproverRule rule : node.approverRules()) {
                if (rule == null
                        || ApproverRuleType.of(rule.getType()) != ApproverRuleType.APPLICANT_CHOOSE) {
                    continue;
                }
                FlowPreviewVO.ChooseRequirement requirement = new FlowPreviewVO.ChooseRequirement();
                requirement.setNodeKey(node.nodeKey());
                requirement.setNodeName(node.nodeName());
                requirement.setSignType(node.signType());
                requirement.setSignTypeLabel(SignType.ALL_SIGN.equals(node.signType()) ? "会签" : "或签");
                requirement.setStepOrder(node.stepOrder());
                requirement.setScope(rule.getScope());
                requirement.setScopeValue(rule.getScopeValue());
                requirement.setScopeLabel(scopeLabel(rule.getScope(), rule.getScopeValue()));
                requirement.setMinCount(rule.getMinCount());
                requirement.setMaxCount(rule.getMaxCount());
                // W4-D：不再把整个池子塞进响应（「全部员工」域 = 全员，响应体随组织规模线性膨胀）。
                // 只给首屏 N 条 + 总数 + 是否截断；完整列表由 /choose-candidates 分页搜索提供。
                // 一次 selectPage 同时拿到总数（MP 自动执行 count），不需要额外一次 COUNT 查询。
                IPage<User> firstScreen = approverRuleResolver.searchChoosable(
                        rule, null, 1, CANDIDATE_PREVIEW_LIMIT);
                requirement.setCandidates(firstScreen.getRecords().stream()
                        .map(ApplyTypeServiceImpl::toCandidate)
                        .toList());
                requirement.setCandidateTotal((int) firstScreen.getTotal());
                requirement.setCandidatesTruncated(firstScreen.getTotal() > firstScreen.getRecords().size());
                result.add(requirement);
            }
        }
        return result;
    }

    /** 用户 → 候选人员。预览首屏与分页搜索共用，避免两处显示名口径漂移 */
    private static FlowPreviewVO.Candidate toCandidate(User user) {
        FlowPreviewVO.Candidate candidate = new FlowPreviewVO.Candidate();
        candidate.setId(user.getId());
        candidate.setName(displayName(user));
        return candidate;
    }

    private static String displayName(User user) {
        if (StringUtils.hasText(user.getDisplayName())) {
            return user.getDisplayName();
        }
        if (StringUtils.hasText(user.getRealName())) {
            return user.getRealName();
        }
        return user.getUsername();
    }

    /** 可选范围的可读文案（管理端配置的是角色码 / 分组 id，提交页要显示成人看得懂的名字） */
    private String scopeLabel(String scope, String scopeValue) {
        ApproverRuleType.ChooseScope parsed = ApproverRuleType.ChooseScope.of(scope);
        if (parsed == null || !StringUtils.hasText(scopeValue)) {
            return "全部员工";
        }
        if (parsed == ApproverRuleType.ChooseScope.ROLE) {
            String name = roleMapper.selectList(Wrappers.<SysRole>lambdaQuery()
                            .eq(SysRole::getRoleCode, scopeValue.trim())).stream()
                    .map(SysRole::getRoleName).findFirst().orElse(null);
            return "角色：" + (name == null ? scopeValue : name);
        }
        if (parsed == ApproverRuleType.ChooseScope.GROUP) {
            Department group = groupById(scopeValue);
            return "部门：" + (group == null ? scopeValue : group.getDeptName());
        }
        return "全部员工";
    }

    private Department groupById(String raw) {
        try {
            return departmentMapper.selectById(Long.valueOf(raw.trim()));
        } catch (NumberFormatException e) {
            // 发布校验已保证范围分组是合法 id；此处是纵深防御，不合法就当查不到
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 写入
    // ------------------------------------------------------------------

    /**
     * 一步创建申请类型。
     *
     * <h2>为什么把三段合成一个事务</h2>
     * <p>改造前管理员要手工走：建表单模板 → 发布版本 → 建审批流程 → 发布版本 → 回类型页挑两个版本。
     * 少了任何一步都会留下垃圾（比如「建了表单但没建类型」的模板，页面上看不出它是废的）。
     * 合成一个事务后，中途失败整体回滚，不留半成品。
     *
     * <h2>为什么审批方式恒为 FLOW</h2>
     * <p>一步创建的定义就是「这条类型绑这条流程」。GROUP 模式（按业务分组配审批人）
     * 属于借用类等内置流程的机制，不在本入口的语义里。
     *
     * <h2>为什么类型编码允许留空</h2>
     * <p>需求要求「编码、版本号这些技术细节藏起来」，管理员不该被一个他看不见的字段卡住。
     * 留空时由服务端生成；只有预置播种会显式指定稳定的编码（便于幂等判断）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createFull(ApplyConfigCreateRequest request) {
        String typeCode = StringUtils.hasText(request.getTypeCode())
                ? request.getTypeCode().trim()
                : generateTechCode("AT");
        FormSchemaValidator.validateTypeCode(typeCode);
        assertTypeCodeAvailable(typeCode, null);

        // ① 表单模板 + 首发版本（发布后拿到 versionId，类型要绑的是版本而不是模板）
        FormTemplateSaveRequest formRequest = new FormTemplateSaveRequest();
        formRequest.setTemplateName(request.getTypeName() + " · 表单");
        formRequest.setDescription("随申请类型「" + request.getTypeName() + "」一并创建");
        formRequest.setSchema(request.getFormSchema());
        Long templateId = formTemplateService.createTemplate(formRequest);
        Long formVersionId = formTemplateService.publish(templateId);

        // ② 审批流程 + 首发版本
        ApprovalFlowSaveRequest flowRequest = new ApprovalFlowSaveRequest();
        flowRequest.setFlowCode(generateTechCode("AF"));
        flowRequest.setFlowName(request.getTypeName() + " · 审批流程");
        flowRequest.setDescription("随申请类型「" + request.getTypeName() + "」一并创建");
        flowRequest.setDefinition(request.getFlow());
        Long flowId = approvalFlowService.createFlow(flowRequest);
        Long flowVersionId = approvalFlowService.publish(flowId);

        // ③ 类型本身 —— 复用既有的 create()，因此表单/流程版本是否已发布、
        //    流程与表单字段是否相容这些校验，与「手工建类型」走的是同一套，不会两处口径漂移
        ApplyTypeSaveRequest typeRequest = new ApplyTypeSaveRequest();
        typeRequest.setTypeCode(typeCode);
        typeRequest.setTypeName(request.getTypeName());
        typeRequest.setIcon(request.getIcon());
        typeRequest.setDescription(request.getDescription());
        typeRequest.setSortOrder(request.getSortOrder());
        typeRequest.setOrderPrefix(request.getOrderPrefix());
        typeRequest.setFormTemplateVersionId(formVersionId);
        typeRequest.setApprovalMode(ApprovalMode.FLOW.name());
        typeRequest.setApprovalFlowVersionId(flowVersionId);
        typeRequest.setSubmitPermissionType(StringUtils.hasText(request.getSubmitPermissionType())
                ? request.getSubmitPermissionType()
                : SubmitPermissionType.ALL.name());
        typeRequest.setSubmitPermissionValues(request.getSubmitPermissionValues());

        Long id = create(typeRequest);
        log.info("申请类型一步创建完成 id={} code={} name={} 表单版本={} 流程版本={}",
                id, typeCode, request.getTypeName(), formVersionId, flowVersionId);
        return id;
    }

    /**
     * 生成技术编码（类型编码 / 流程编码）。
     *
     * <p>用「前缀 + nanoTime 的 36 进制」而**不是** {@code MAX(id)+1}：后者有两个问题 ——
     * 并发创建时会撞唯一键；被删除的编号会被复用，而编码一旦被历史工单引用过，
     * 复用会让两批互不相干的工单看起来同源。
     */
    private String generateTechCode(String prefix) {
        return prefix + Long.toString(System.nanoTime(), 36).toUpperCase(Locale.ROOT);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(ApplyTypeSaveRequest request) {
        String typeCode = request.getTypeCode().trim();
        FormSchemaValidator.validateTypeCode(typeCode);
        assertTypeCodeAvailable(typeCode, null);

        ApplyType type = new ApplyType();
        applyRequest(type, request);
        type.setTypeCode(typeCode);
        type.setStatus(ApplyTypeStatus.ENABLED.name());
        type.setCreatedBy(SecurityUtils.getCurrentUserId());
        applyTypeMapper.insert(type);
        log.info("申请类型已创建 id={} code={} name={} 审批方式={}",
                type.getId(), typeCode, type.getTypeName(), type.getApprovalMode());
        return type.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, ApplyTypeSaveRequest request) {
        ApplyType existing = requireType(id);
        String typeCode = request.getTypeCode().trim();
        FormSchemaValidator.validateTypeCode(typeCode);
        assertTypeCodeAvailable(typeCode, id);

        Resolved resolved = resolve(request);
        applyTypeMapper.update(null, Wrappers.<ApplyType>lambdaUpdate()
                .eq(ApplyType::getId, id)
                .set(ApplyType::getTypeCode, typeCode)
                .set(ApplyType::getTypeName, request.getTypeName().trim())
                .set(ApplyType::getIcon, trimToNull(request.getIcon()))
                .set(ApplyType::getDescription, trimToNull(request.getDescription()))
                .set(ApplyType::getSortOrder, request.getSortOrder() == null ? 100 : request.getSortOrder())
                .set(ApplyType::getFormTemplateVersionId, resolved.formVersionId())
                .set(ApplyType::getOrderPrefix, normalizePrefix(request.getOrderPrefix()))
                .set(ApplyType::getApprovalMode, resolved.mode().name())
                // 切走 FLOW 时这里为 null：lambdaUpdate 的 set 对 null 同样生成 SET 语句，
                // 正是「清空旧绑定」所需的行为（不能靠 if 判空跳过，否则幽灵值永远留在库里）
                .set(ApplyType::getApprovalFlowVersionId, resolved.flowVersionId())
                .set(ApplyType::getSubmitPermissionType, resolved.permissionType().name())
                .set(ApplyType::getSubmitPermissionValue, resolved.permissionValueJson()));
        log.info("申请类型 {} 已更新（code {} → {}）", id, existing.getTypeCode(), typeCode);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long id, String status) {
        ApplyType type = requireType(id);
        ApplyTypeStatus target = ApplyTypeStatus.of(status);
        if (target == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "申请类型状态取值不合法：" + status);
        }
        if (target.name().equals(type.getStatus())) {
            // 幂等：重复点击不应报错，但也没必要写库（避免无意义的 updated_at 抖动）
            return;
        }
        applyTypeMapper.update(null, Wrappers.<ApplyType>lambdaUpdate()
                .eq(ApplyType::getId, id)
                .set(ApplyType::getStatus, target.name()));
        log.info("申请类型 {}（{}）状态 {} → {}", id, type.getTypeName(), type.getStatus(), target.name());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        ApplyType type = requireType(id);
        long used = orderCount(id);
        if (used > 0) {
            throw new BusinessException(ErrorCode.APPLY_TYPE_HAS_ORDER,
                    "该申请类型已被 " + used + " 笔工单使用，只能停用不能删除");
        }
        // ：先记下它引用的表单模板 / 审批流程，删掉类型之后再按「是否仍被别的类型引用」
        // 决定要不要连带清理。顺序很重要 —— 先删类型，「还查得到引用」才等价于「被别的类型引用」。
        Long templateId = templateIdOfVersion(type.getFormTemplateVersionId());
        Long flowId = flowIdOfVersion(type.getApprovalFlowVersionId());

        applyTypeMapper.deleteById(id);
        cleanupOwnedTemplate(templateId);
        cleanupOwnedFlow(flowId);
        log.info("申请类型 {}（{}）已删除", id, type.getTypeName());
    }

    /**
     * 连带清理申请类型**专属**的表单模板。
     *
     * <h2>为什么必须有这一步</h2>
     * <p>「申请类型与审批流程」合并页把表单模板与审批流程收成了申请类型的**实现细节**
     * （说明：「编码、版本号这些技术细节藏起来，管理员看不到」）——
     * 界面上没有它们的入口。若删类型时把模板留下，管理员再建一个同名类型就会撞
     * 「表单模板名称已存在」，而那个名字他**既看不到也删不掉**，报错完全无法自解。
     * （这是实测出来的：第二遍用同名做浏览器取证时被这条挡住。）
     *
     * <h2>为什么只删「没人再引用」的</h2>
     * <p>流程模板按设计可被多个申请类型复用（见 {@code ApplyType} 的流程绑定）。
     * 共用的东西不是残留 —— 删掉它会连带毁掉别的申请类型，因此先查引用，仍被引用就原样保留。
     *
     * <h2>为什么清理失败不回滚</h2>
     * <p>类型本身已经删掉了。为「清理副作用」失败而把删除回滚，只会让管理员陷入
     * 「点了删除却还在」这种更糟的状态。因此这里只记日志，保留残留（最多占一个名字）。
     */
    private void cleanupOwnedTemplate(Long templateId) {
        if (templateId == null || templateReferencedByAnyType(templateId)) {
            return;
        }
        try {
            formTemplateService.deleteTemplate(templateId);
            log.info("已连带清理申请类型专属的表单模板 {}", templateId);
        } catch (BusinessException ex) {
            log.warn("表单模板 {} 未能连带清理（{}），保留原样", templateId, ex.getMessage());
        }
    }

    /**
     * 连带清理申请类型**专属**的审批流程。规则与 {@link #cleanupOwnedTemplate} 一致：
     * 只删没人再引用的，失败只记日志。
     */
    private void cleanupOwnedFlow(Long flowId) {
        if (flowId == null || flowReferencedByAnyType(flowId)) {
            return;
        }
        try {
            approvalFlowService.deleteFlow(flowId);
            log.info("已连带清理申请类型专属的审批流程 {}", flowId);
        } catch (BusinessException ex) {
            log.warn("审批流程 {} 未能连带清理（{}），保留原样", flowId, ex.getMessage());
        }
    }

    /** 由表单版本 id 反查模板 id；版本行已不存在时返回 null（当作没有可清理的东西） */
    private Long templateIdOfVersion(Long versionId) {
        if (versionId == null) {
            return null;
        }
        FormTemplateVersion version = versionMapper.selectById(versionId);
        return version == null ? null : version.getTemplateId();
    }

    /** 由流程版本 id 反查流程 id；版本行已不存在时返回 null */
    private Long flowIdOfVersion(Long versionId) {
        if (versionId == null) {
            return null;
        }
        ApprovalFlowVersion version = flowVersionMapper.selectById(versionId);
        return version == null ? null : version.getFlowId();
    }

    /**
     * 该表单模板是否仍被**任何**申请类型引用。
     *
     * <p>调用时机是「本类型已删除之后」，因此「还查得到」就等价于「被别的类型引用」——
     * 这正是要保留的情形。反过来说，若在删除之前调用，本类型自己会被算进去，
     * 判断将永远为真、清理永不发生。
     */
    private boolean templateReferencedByAnyType(Long templateId) {
        List<Long> versionIds = versionMapper.selectList(Wrappers.<FormTemplateVersion>lambdaQuery()
                        .eq(FormTemplateVersion::getTemplateId, templateId))
                .stream()
                .map(FormTemplateVersion::getId)
                .toList();
        if (versionIds.isEmpty()) {
            return false;
        }
        return applyTypeMapper.selectCount(Wrappers.<ApplyType>lambdaQuery()
                .in(ApplyType::getFormTemplateVersionId, versionIds)) > 0;
    }

    /** 该审批流程是否仍被**任何**申请类型引用（口径同 {@link #templateReferencedByAnyType}） */
    private boolean flowReferencedByAnyType(Long flowId) {
        List<Long> versionIds = flowVersionMapper.selectList(Wrappers.<ApprovalFlowVersion>lambdaQuery()
                        .eq(ApprovalFlowVersion::getFlowId, flowId))
                .stream()
                .map(ApprovalFlowVersion::getId)
                .toList();
        if (versionIds.isEmpty()) {
            return false;
        }
        return applyTypeMapper.selectCount(Wrappers.<ApplyType>lambdaQuery()
                .in(ApplyType::getApprovalFlowVersionId, versionIds)) > 0;
    }

    // ------------------------------------------------------------------
    // 跨模块契约
    // ------------------------------------------------------------------

    @Override
    public ApplyType requireSubmittable(Long applyTypeId) {
        ApplyType type = requireType(applyTypeId);
        if (!ApplyTypeStatus.ENABLED.name().equals(type.getStatus())) {
            throw new BusinessException(ErrorCode.APPLY_TYPE_DISABLED,
                    "「" + type.getTypeName() + "」已停用，无法提交");
        }
        if (!canSubmit(type, requireCurrentUser())) {
            throw new BusinessException(ErrorCode.APPLY_TYPE_SUBMIT_FORBIDDEN,
                    "你不在「" + type.getTypeName() + "」的可提交范围内");
        }
        return type;
    }

    // ------------------------------------------------------------------
    // 校验与装配
    // ------------------------------------------------------------------

    /** 请求 → 实体（不含 typeCode / status / createdBy，这三者由调用方决定） */
    private void applyRequest(ApplyType type, ApplyTypeSaveRequest request) {
        Resolved resolved = resolve(request);
        type.setTypeName(request.getTypeName().trim());
        type.setIcon(trimToNull(request.getIcon()));
        type.setDescription(trimToNull(request.getDescription()));
        type.setSortOrder(request.getSortOrder() == null ? 100 : request.getSortOrder());
        type.setFormTemplateVersionId(resolved.formVersionId());
        type.setOrderPrefix(normalizePrefix(request.getOrderPrefix()));
        type.setApprovalMode(resolved.mode().name());
        type.setApprovalFlowVersionId(resolved.flowVersionId());
        type.setSubmitPermissionType(resolved.permissionType().name());
        type.setSubmitPermissionValue(resolved.permissionValueJson());
    }

    /**
     * 把请求里「需要查库才能确认合法」的字段一次性解析出来。
     *
     * <p>创建与更新共用同一份解析 —— 这不是为了少写几行，而是要堵住一个真实缺口：
     * 更新路径原先直接把 {@code request.getFormTemplateVersionId()} 写库，
     * 绕过了「必须已发布」的校验，于是可以把一个类型改指向草稿版本。
     */
    private Resolved resolve(ApplyTypeSaveRequest request) {
        Long formVersionId = requirePublishedVersion(request.getFormTemplateVersionId());
        ApprovalMode mode = resolveApprovalMode(request);
        Long flowVersionId = resolveFlowVersion(request.getApprovalFlowVersionId(), mode, formVersionId);
        SubmitPermissionType permissionType = resolvePermissionType(request);
        List<String> permissionValues = resolvePermissionValues(permissionType,
                request.getSubmitPermissionValues());
        return new Resolved(formVersionId, mode, flowVersionId, permissionType,
                FormSchemaCodec.write(permissionValues));
    }

    /**
     * 解析绑定的审批流程版本。
     *
     * <p>三条规则：
     * <ol>
     *   <li>审批方式不是 FLOW 时恒返回 {@code null} —— 切换审批方式必须清掉旧流程绑定，
     *       否则「先绑流程、再改成不用审批」会留下一个仍指向流程的幽灵字段，
     *       后续任何按 {@code approval_flow_version_id IS NOT NULL} 做的判断都会误判；</li>
     *   <li>FLOW 时必须选且必须是<b>已发布</b>版本（与表单版本同一条口径）；</li>
     *   <li>流程模板独立于表单发布，发布流程时无从知晓会被哪个表单引用，
     *       所以「条件分支只能引用所绑表单里的字段」这条<b>只能在绑定这一刻</b>校验 ——
     *       它落在这里，与一期的「发布时校验 schema 自洽」形成互补。</li>
     * </ol>
     */
    private Long resolveFlowVersion(Long flowVersionId, ApprovalMode mode, Long formVersionId) {
        if (mode != ApprovalMode.FLOW) {
            return null;
        }
        if (flowVersionId == null) {
            throw new BusinessException(ErrorCode.FLOW_NO_PUBLISHED_VERSION,
                    "审批方式为「" + mode.getLabel() + "」时必须选择一个已发布的审批流程");
        }
        if (!approvalFlowService.isPublishedVersion(flowVersionId)) {
            throw new BusinessException(ErrorCode.FLOW_NO_PUBLISHED_VERSION,
                    "关联的审批流程版本不存在或尚未发布，请先在审批流程模板中发布");
        }
        approvalFlowService.validateAgainstForm(flowVersionId,
                formTemplateService.requirePublishedSchema(formVersionId));
        return flowVersionId;
    }

    private Long requirePublishedVersion(Long versionId) {
        if (versionId == null) {
            throw new BusinessException(ErrorCode.FORM_TEMPLATE_NO_PUBLISHED_VERSION, "请选择关联的表单模板版本");
        }
        if (!formTemplateService.isPublishedVersion(versionId)) {
            // 不存在与「存在但未发布」含义不同：前者是选错了，后者是还没发布，
            // 但对外都用同一码更安全（不通过错误差异探测内部数据是否存在）。
            throw new BusinessException(ErrorCode.FORM_TEMPLATE_NO_PUBLISHED_VERSION,
                    "关联的表单版本不存在或尚未发布，请先在表单设计中发布");
        }
        return versionId;
    }

    private ApprovalMode resolveApprovalMode(ApplyTypeSaveRequest request) {
        ApprovalMode mode = ApprovalMode.of(request.getApprovalMode());
        if (mode == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "审批方式取值不合法：" + request.getApprovalMode());
        }
        return mode;
    }

    private SubmitPermissionType resolvePermissionType(ApplyTypeSaveRequest request) {
        SubmitPermissionType type = SubmitPermissionType.of(request.getSubmitPermissionType());
        if (type == null) {
            throw new BusinessException(ErrorCode.APPLY_TYPE_PERMISSION_INVALID,
                    "提交权限类型取值不合法：" + request.getSubmitPermissionType());
        }
        return type;
    }

    /**
     * 归一化提交权限值。
     *
     * <p>ALL 模式恒写空数组而不是 null：null 与「空数组」在读取端是两种写法却同一种含义，
     * 统一成空数组后，判定逻辑只需要处理一种情况（见 {@link #canSubmit}）。
     */
    private List<String> resolvePermissionValues(SubmitPermissionType type, List<String> raw) {
        if (type == SubmitPermissionType.ALL) {
            return List.of();
        }
        List<String> values = raw == null ? List.of()
                : raw.stream().filter(StringUtils::hasText).map(String::trim).distinct().toList();
        if (values.isEmpty()) {
            throw new BusinessException(ErrorCode.APPLY_TYPE_PERMISSION_INVALID,
                    "提交权限为「" + type.getLabel() + "」时至少需要选择一项");
        }
        if (type == SubmitPermissionType.ROLE) {
            assertRolesExist(values);
        } else {
            assertGroupsExist(values);
        }
        return values;
    }

    private void assertRolesExist(List<String> roleCodes) {
        Set<String> existing = roleMapper.selectList(Wrappers.<SysRole>lambdaQuery()
                        .in(SysRole::getRoleCode, roleCodes))
                .stream()
                .filter(role -> Boolean.TRUE.equals(role.getEnabled()))
                .map(SysRole::getRoleCode)
                .collect(Collectors.toSet());
        List<String> missing = roleCodes.stream().filter(code -> !existing.contains(code)).toList();
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.APPLY_TYPE_PERMISSION_INVALID,
                    "以下角色不存在或已停用：" + String.join("、", missing));
        }
    }

    private void assertGroupsExist(List<String> groupIds) {
        List<Long> ids = new ArrayList<>();
        for (String raw : groupIds) {
            try {
                ids.add(Long.valueOf(raw));
            } catch (NumberFormatException e) {
                throw new BusinessException(ErrorCode.APPLY_TYPE_PERMISSION_INVALID,
                        "部门 id 不合法：" + raw);
            }
        }
        long found = departmentMapper.selectCount(Wrappers.<Department>lambdaQuery().in(Department::getId, ids));
        if (found != ids.size()) {
            throw new BusinessException(ErrorCode.APPLY_TYPE_PERMISSION_INVALID,
                    "存在无效的部门，请刷新页面后重新选择");
        }
    }

    private String normalizePrefix(String prefix) {
        String value = trimToNull(prefix);
        FormSchemaValidator.validateOrderPrefix(value);
        return value == null ? null : value.toUpperCase();
    }

    private void assertTypeCodeAvailable(String typeCode, Long excludeId) {
        long count = applyTypeMapper.selectCount(Wrappers.<ApplyType>lambdaQuery()
                .eq(ApplyType::getTypeCode, typeCode)
                .ne(excludeId != null, ApplyType::getId, excludeId));
        if (count > 0) {
            throw new BusinessException(ErrorCode.APPLY_TYPE_CODE_EXISTS);
        }
    }

    /**
     * 当前用户能否提交该类型。
     *
     * <p>super_admin 恒定放行：与 {@code PermissionGuard} 对超管的短路策略保持一致 ——
     * 超管是最后的救火通道，若连他自己都被配置挡在门外，出问题时就没有人能修了。
     */
    private boolean canSubmit(ApplyType type, User user) {
        if (RoleCode.isSuperAdmin(user.getRole())) {
            return true;
        }
        SubmitPermissionType permissionType = SubmitPermissionType.of(type.getSubmitPermissionType());
        if (permissionType == null || permissionType == SubmitPermissionType.ALL) {
            return true;
        }
        List<String> values = FormSchemaCodec.readStringList(type.getSubmitPermissionValue());
        if (values.isEmpty()) {
            // 配置损坏（值解析不出来）：按「无人可提交」处理，宁可少放行不可错放行
            return false;
        }
        if (permissionType == SubmitPermissionType.ROLE) {
            return values.contains(user.getRole());
        }
        return user.getDepartmentId() != null && values.contains(String.valueOf(user.getDepartmentId()));
    }

    // ------------------------------------------------------------------
    // 装配
    // ------------------------------------------------------------------

    /** 批量加载装配 VO 所需的关联数据，避免逐行查库 */
    private Context loadContext(List<ApplyType> types) {
        Set<Long> versionIds = types.stream()
                .map(ApplyType::getFormTemplateVersionId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, FormTemplateVersion> versions = versionIds.isEmpty() ? Map.of()
                : versionMapper.selectBatchIds(versionIds).stream()
                        .collect(Collectors.toMap(FormTemplateVersion::getId, v -> v, (a, b) -> a));
        Set<Long> templateIds = versions.values().stream()
                .map(FormTemplateVersion::getTemplateId)
                .collect(Collectors.toSet());
        Map<Long, String> templateNames = templateIds.isEmpty() ? Map.of()
                : templateMapper.selectBatchIds(templateIds).stream()
                        .collect(Collectors.toMap(FormTemplate::getId, FormTemplate::getTemplateName, (a, b) -> a));
        Map<String, String> roleNames = roleMapper.selectList(null).stream()
                .collect(Collectors.toMap(SysRole::getRoleCode, SysRole::getRoleName, (a, b) -> a, LinkedHashMap::new));
        Map<String, String> groupNames = departmentMapper.selectList(null).stream()
                .collect(Collectors.toMap(group -> String.valueOf(group.getId()),
                        Department::getDeptName, (a, b) -> a, LinkedHashMap::new));
        Map<Long, Long> orderCounts = orderCountsByType(types);
        return new Context(versions, templateNames, roleNames, groupNames, flowNames(types), orderCounts);
    }

    /**
     * 流程版本 id → 「流程名 v版本号」（两条批量查询，避免逐行 N+1）。
     *
     * <p>只查一次不缓存：管理列表最多几十行，为它引入缓存反而多一处失效面。
     */
    private Map<Long, String> flowNames(List<ApplyType> types) {
        Set<Long> versionIds = types.stream()
                .map(ApplyType::getApprovalFlowVersionId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (versionIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, ApprovalFlowVersion> flowVersions = flowVersionMapper.selectBatchIds(versionIds).stream()
                .collect(Collectors.toMap(ApprovalFlowVersion::getId, v -> v, (a, b) -> a));
        Set<Long> flowIds = flowVersions.values().stream()
                .map(ApprovalFlowVersion::getFlowId)
                .collect(Collectors.toSet());
        Map<Long, String> names = flowIds.isEmpty() ? Map.of()
                : flowMapper.selectBatchIds(flowIds).stream()
                        .collect(Collectors.toMap(ApprovalFlow::getId, ApprovalFlow::getFlowName, (a, b) -> a));
        Map<Long, String> result = new LinkedHashMap<>();
        flowVersions.forEach((id, version) -> result.put(id,
                (names.get(version.getFlowId()) == null ? "流程" : names.get(version.getFlowId()))
                        + " v" + version.getVersionNo()));
        return result;
    }

    /** 各类型的工单使用量（一条 group by 查询，避免逐类型 count） */
    private Map<Long, Long> orderCountsByType(List<ApplyType> types) {
        List<Long> ids = types.stream().map(ApplyType::getId).filter(Objects::nonNull).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<Order> orders = orderMapper.selectList(Wrappers.<Order>lambdaQuery()
                .select(Order::getApplyTypeId)
                .in(Order::getApplyTypeId, ids));
        return orders.stream()
                .filter(order -> order.getApplyTypeId() != null)
                .collect(Collectors.groupingBy(Order::getApplyTypeId, Collectors.counting()));
    }

    private ApplyTypeVO toVO(ApplyType type, Context context, boolean withSchema) {
        ApplyTypeVO vo = new ApplyTypeVO();
        vo.setId(type.getId());
        vo.setTypeCode(type.getTypeCode());
        vo.setTypeName(type.getTypeName());
        vo.setIcon(type.getIcon());
        vo.setDescription(type.getDescription());
        vo.setSortOrder(type.getSortOrder());
        vo.setStatus(type.getStatus());
        vo.setStatusLabel(ApplyTypeStatus.labelOf(type.getStatus()));
        vo.setFormTemplateVersionId(type.getFormTemplateVersionId());
        vo.setOrderPrefix(type.getOrderPrefix());
        vo.setApprovalMode(type.getApprovalMode());
        vo.setApprovalModeLabel(ApprovalMode.labelOf(type.getApprovalMode()));
        vo.setApprovalFlowVersionId(type.getApprovalFlowVersionId());
        // 先判空再查表：flowNames 在「本轮没有任何类型绑流程」时是 Map.of()，
        // 而 Map.of().get(null) 会抛 NPE（ 已栽过一次的坑）
        vo.setApprovalFlowName(type.getApprovalFlowVersionId() == null ? null
                : context.flowNames().get(type.getApprovalFlowVersionId()));
        vo.setSubmitPermissionType(type.getSubmitPermissionType());
        vo.setSubmitPermissionTypeLabel(SubmitPermissionType.labelOf(type.getSubmitPermissionType()));

        FormTemplateVersion version = context.versions().get(type.getFormTemplateVersionId());
        if (version != null) {
            vo.setFormTemplateVersionNo(version.getVersionNo());
            vo.setFormTemplateName(context.templateNames().get(version.getTemplateId()));
        }

        List<String> values = FormSchemaCodec.readStringList(type.getSubmitPermissionValue());
        vo.setSubmitPermissionValues(values);
        vo.setSubmitPermissionText(permissionText(type.getSubmitPermissionType(), values, context));

        long used = context.orderCounts().getOrDefault(type.getId(), 0L);
        vo.setUsedByOrder(used > 0);
        vo.setOrderCount(used);

        vo.setCreatedAt(type.getCreatedAt());
        vo.setUpdatedAt(type.getUpdatedAt());
        if (withSchema) {
            // 走表单服务取「已发布版本」的定义：这里再校验一次发布态，
            // 防止「版本发布后又被人工改成草稿」这类脏数据把非法 schema 放进来
            FormSchema schema = formTemplateService.requirePublishedSchema(type.getFormTemplateVersionId());
            // P1 安全修复：权限申请表单的权限选项按当前策略**动态生成**，与角色页的
            // 「可申请权限配置」同源 —— 避免「策略标不可申请、表单却可勾选」的静默提权口子。
            // 仅当表单确实含 permissionCodes 字段时才查策略（普通申请无需这次查询）。
            if (PermissionApplySchemaSupport.hasPermissionField(schema)) {
                schema = PermissionApplySchemaSupport.withApplicableOptions(
                        schema, permissionApplyPolicyService.applicableCodes());
            }
            vo.setSchema(schema);
        }
        return vo;
    }

    private String permissionText(String permissionType, List<String> values, Context context) {
        SubmitPermissionType type = SubmitPermissionType.of(permissionType);
        if (type == null || type == SubmitPermissionType.ALL) {
            return "全部登录用户";
        }
        if (type == SubmitPermissionType.ROLE) {
            String text = values.stream()
                    .map(code -> context.roleNames().getOrDefault(code, code))
                    .collect(Collectors.joining("、"));
            return "角色：" + text;
        }
        String text = values.stream()
                .map(id -> context.groupNames().getOrDefault(id, "分组#" + id))
                .collect(Collectors.joining("、"));
        return "分组：" + text;
    }

    private ApplyTypeOptionVO toOptionVO(ApplyType type) {
        ApplyTypeOptionVO vo = new ApplyTypeOptionVO();
        vo.setId(type.getId());
        vo.setTypeCode(type.getTypeCode());
        vo.setTypeName(type.getTypeName());
        vo.setIcon(type.getIcon());
        vo.setDescription(type.getDescription());
        vo.setApprovalMode(type.getApprovalMode());
        vo.setApprovalModeLabel(ApprovalMode.labelOf(type.getApprovalMode()));
        return vo;
    }

    private ApplyType requireType(Long id) {
        if (id == null) {
            throw new BusinessException(ErrorCode.APPLY_TYPE_NOT_FOUND);
        }
        ApplyType type = applyTypeMapper.selectById(id);
        if (type == null) {
            throw new BusinessException(ErrorCode.APPLY_TYPE_NOT_FOUND);
        }
        return type;
    }

    private User requireCurrentUser() {
        User user = userMapper.selectById(SecurityUtils.getCurrentUserId());
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND, "当前登录账号不存在，请重新登录");
        }
        return user;
    }

    /** 当前登录用户角色（详情可见性判定用；未登录返回 null） */
    private String currentRole() {
        var loginUser = SecurityUtils.getCurrentUser();
        return loginUser == null ? null : loginUser.getRole();
    }

    private long orderCount(Long applyTypeId) {
        return orderMapper.selectCount(Wrappers.<Order>lambdaQuery()
                .eq(Order::getApplyTypeId, applyTypeId));
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    /** 装配 VO 所需的关联数据集合（一次查询，多处复用） */
    private record Context(Map<Long, FormTemplateVersion> versions,
                           Map<Long, String> templateNames,
                           Map<String, String> roleNames,
                           Map<String, String> groupNames,
                           Map<Long, String> flowNames,
                           Map<Long, Long> orderCounts) {
    }

    /** 请求中「查库校验后」的落地值（创建与更新共用，避免两条路径的校验口径漂移） */
    private record Resolved(Long formVersionId,
                            ApprovalMode mode,
                            Long flowVersionId,
                            SubmitPermissionType permissionType,
                            String permissionValueJson) {
    }

    /** 供测试断言排序稳定性（sortOrder 升序、同序号按 id 升序） */
    static Comparator<ApplyType> bySortOrder() {
        return Comparator.comparing(ApplyType::getSortOrder, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ApplyType::getId);
    }
}
