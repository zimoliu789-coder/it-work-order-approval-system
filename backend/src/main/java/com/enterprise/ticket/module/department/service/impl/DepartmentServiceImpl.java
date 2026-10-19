package com.enterprise.ticket.module.department.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ticket.common.alert.AlertLevel;
import com.enterprise.ticket.common.alert.AlertService;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.MessageType;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.approvalflow.entity.ApprovalFlowVersion;
import com.enterprise.ticket.module.approvalflow.mapper.ApprovalFlowVersionMapper;
import com.enterprise.ticket.module.department.dto.DepartmentSaveRequest;
import com.enterprise.ticket.module.department.dto.vo.DepartmentDeleteResultVO;
import com.enterprise.ticket.module.department.dto.vo.DepartmentNodeVO;
import com.enterprise.ticket.module.department.dto.vo.DepartmentOptionVO;
import com.enterprise.ticket.module.department.dto.vo.DivisionHandlerConfigVO;
import com.enterprise.ticket.module.department.entity.Department;
import com.enterprise.ticket.module.department.entity.DepartmentManager;
import com.enterprise.ticket.module.department.entity.DivisionHandlerGroup;
import com.enterprise.ticket.module.department.entity.UserDepartment;
import com.enterprise.ticket.module.department.mapper.DepartmentManagerMapper;
import com.enterprise.ticket.module.department.mapper.DepartmentMapper;
import com.enterprise.ticket.module.department.mapper.DivisionHandlerGroupMapper;
import com.enterprise.ticket.module.department.mapper.UserDepartmentMapper;
import com.enterprise.ticket.module.department.service.DepartmentService;
import com.enterprise.ticket.module.user.dto.UserOptionVO;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 组织与人员 —— 部门侧实现（，需求文档 二）。
 *
 * <h2>本类维护的两条不变量</h2>
 * <ol>
 *   <li><b>path / depth 与 parent_id 恒一致</b>：任何改动父子关系的操作（{@link #create} /
 *       {@link #move}）都必须整体重写受影响子树的 path。只改 parent_id 会让
 *       「取子树」的 {@code path LIKE} 查询静默给出错误结果 —— 不报错、只是少人或多人。</li>
 *   <li><b>「直属主管」只有一个出处</b>：{@link #primaryManagerOf(Long)}。它被
 *       {@code ApproverRuleResolver}（审批走到谁）与成员管理的「默认直属主管」共同使用。
 *       任何一处另写一份「取主管」的逻辑，都会造成「界面显示的上级」与「实际审批人」不一致。</li>
 * </ol>
 *
 * <h2>成员归属怎么算（本类最容易踩的一点）</h2>
 * <p>有两张表回答「谁属于这个部门」：
 * <ul>
 *   <li>{@code employee.department_id} —— <b>主部门</b>，唯一，决定审批上级；</li>
 *   <li>{@code user_department} —— <b>额外兼职归属</b>（如研发部的人同时挂在 IT运维组做执行人）。</li>
 * </ul>
 * 「部门人数」与「部门成员列表」取**两者的并集**。若只取主部门，
 * 「IT运维组」会永远显示 0 人（因为没人把它当主部门），而它恰恰是该页面最需要看到成员的部门。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DepartmentServiceImpl implements DepartmentService {

    /** 物化路径在「先插入、后回填」期间的占位后缀：一次事务内不可能被外部读到 */
    private static final String PATH_PENDING = "PENDING/";

    private final DepartmentMapper departmentMapper;
    private final DepartmentManagerMapper departmentManagerMapper;
    private final UserDepartmentMapper userDepartmentMapper;
    private final UserMapper userMapper;
    /** 分部 ↔ 处理小组绑定（V53）：逐级匹配的事实源 */
    private final DivisionHandlerGroupMapper divisionHandlerGroupMapper;
    /**
     * 运维告警出口（V53）：仅在「分部没绑处理小组、用默认组兜底」时使用。
     *
     * <p>依赖是单向的（{@code AlertService} 只依赖 message / log / mail / 系统配置），
     * 不会与本模块形成环。
     */
    private final AlertService alertService;

    /**
     * 兜底告警去重：分部 id → 上次告警的毫秒时间戳。
     *
     * <p>为什么必须去重：这条告警挂在<b>每一次提交</b>的路径上。一个漏配的分部
     * 一天可能有几十张单子，不去重会把超管的站内消息与邮箱刷成一片 ——
     * 告警疲劳之后真故障会被直接忽略。30 分钟内同一分部只提醒一次，足够推动配置修复。
     * 进程内 Map 即可：重启后重发一次无害（且那时往往正好有人去看配置）。
     */
    private final Map<Long, Long> fallbackAlertedAt = new java.util.concurrent.ConcurrentHashMap<>();

    /** 兜底告警的最小间隔 */
    private static final long FALLBACK_ALERT_INTERVAL_MS = 30L * 60L * 1000L;

    /**
     * 流程版本出口（ 全量回归补丁）：仅用于**校验**部门绑定的版本确实存在且已发布。
     *
     * <p>刻意注入 Mapper 而不是 {@code ApprovalFlowService}：本类只需要「这一版能不能被绑定」
     * 这一个事实，走服务层会连带引入它的其它依赖（申请类型 / 系统配置），
     * 而这里既不需要发布校验也不需要读定义。同模块的 {@code ApplyTypeServiceImpl} 也是这么取版本的。
     */
    private final ApprovalFlowVersionMapper approvalFlowVersionMapper;

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    @Override
    public List<DepartmentNodeVO> tree() {
        List<Department> all = departmentMapper.selectList(Wrappers.<Department>lambdaQuery()
                .orderByAsc(Department::getDepth)
                .orderByAsc(Department::getSortOrder)
                .orderByAsc(Department::getId));
        if (all.isEmpty()) {
            return List.of();
        }

        Map<Long, Long> primaryCounts = primaryMemberCounts();
        Map<Long, Long> extraCounts = extraMemberCounts();
        Map<Long, List<UserOptionVO>> managerMap = managersByDepartment(all);

        Map<Long, DepartmentNodeVO> index = new LinkedHashMap<>();
        for (Department dept : all) {
            DepartmentNodeVO vo = toNode(dept);
            long primary = primaryCounts.getOrDefault(dept.getId(), 0L);
            long extra = extraCounts.getOrDefault(dept.getId(), 0L);
            // 直接成员数 = 主部门人数 + 仅兼职在本部门的人数（并集，不重复计）
            vo.setMemberCount(primary + extra);
            vo.setManagers(managerMap.getOrDefault(dept.getId(), new ArrayList<>()));
            index.put(dept.getId(), vo);
        }

        // 组装树 + 回填「含下级的合计人数」（自底向上：先按 depth 降序累加）
        List<DepartmentNodeVO> roots = new ArrayList<>();
        for (DepartmentNodeVO node : index.values()) {
            DepartmentNodeVO parent = node.getParentId() == null ? null : index.get(node.getParentId());
            if (parent == null) {
                roots.add(node);
            } else {
                parent.getChildren().add(node);
            }
        }
        List<DepartmentNodeVO> byDepthDesc = new ArrayList<>(index.values());
        byDepthDesc.sort((a, b) -> Integer.compare(b.getDepth(), a.getDepth()));
        for (DepartmentNodeVO node : byDepthDesc) {
            long total = node.getMemberCount() == null ? 0L : node.getMemberCount();
            for (DepartmentNodeVO child : node.getChildren()) {
                total += child.getTotalMemberCount() == null ? 0L : child.getTotalMemberCount();
            }
            node.setTotalMemberCount(total);
        }
        return roots;
    }

    @Override
    public List<DepartmentOptionVO> options() {
        List<Department> all = departmentMapper.selectList(Wrappers.<Department>lambdaQuery()
                .orderByAsc(Department::getDepth)
                .orderByAsc(Department::getSortOrder)
                .orderByAsc(Department::getId));
        Map<Long, Department> index = all.stream()
                .collect(Collectors.toMap(Department::getId, d -> d, (a, b) -> a, LinkedHashMap::new));
        List<DepartmentOptionVO> options = new ArrayList<>();
        for (Department dept : all) {
            options.add(DepartmentOptionVO.of(dept, displayPathOf(dept, index)));
        }
        return options;
    }

    @Override
    public Long primaryManagerOf(Long departmentId) {
        if (departmentId == null) {
            return null;
        }
        List<Long> managers = managerIdsOf(departmentId);
        return managers.isEmpty() ? null : managers.get(0);
    }

    @Override
    public List<Long> managerIdsOf(Long departmentId) {
        if (departmentId == null) {
            return List.of();
        }
        return departmentManagerMapper.selectList(Wrappers.<DepartmentManager>lambdaQuery()
                        .eq(DepartmentManager::getDepartmentId, departmentId)
                        .orderByAsc(DepartmentManager::getUserId)).stream()
                .map(DepartmentManager::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    @Override
    public Long handlerDepartmentId() {
        // V53 起「全局唯一组」这个概念已被「分部绑定 + 全局默认」取代。
        // 本方法保留给尚未改造的调用方，语义收敛为「默认处理小组」—— 迁移会把
        // V53 之前那个唯一的组标成 default，因此升级后行为与升级前逐字一致。
        return defaultHandlerDepartmentId();
    }

    @Override
    public Long defaultHandlerDepartmentId() {
        Department marked = departmentMapper.selectOne(Wrappers.<Department>lambdaQuery()
                .eq(Department::getHandlerDefault, true)
                .orderByAsc(Department::getId)
                .last("LIMIT 1"));
        if (marked != null) {
            return marked.getId();
        }
        // 兜底：标记被手工清掉 / V53 迁移未跑到时，退回「id 最小的处理小组」，
        // 与 V53 之前完全一致 —— 不让一次配置事故把整条分配链断成「无处理部门」
        Department oldest = departmentMapper.selectOne(Wrappers.<Department>lambdaQuery()
                .eq(Department::getHandlerGroup, true)
                .orderByAsc(Department::getId)
                .last("LIMIT 1"));
        if (oldest != null) {
            log.warn("未找到标记为全局默认的处理小组，已回退为 id 最小的处理小组 id={}（"
                    + "请到组织与人员页把某个处理小组设为默认）", oldest.getId());
        }
        return oldest == null ? null : oldest.getId();
    }

    @Override
    public Long handlerDepartmentOf(Long startDepartmentId) {
        Long current = startDepartmentId;
        // 环保护：正常部门树不会有环（move 里禁止移到自己的子树），但脏数据/并发改动
        // 一旦造出环，这个 while 会变成死循环 —— 在请求线程里死循环比报错严重得多。
        Set<Long> visited = new LinkedHashSet<>();
        while (current != null && visited.add(current)) {
            Long bound = boundHandlerDepartmentOf(current);
            if (bound != null) {
                return bound;
            }
            Department dept = departmentMapper.selectById(current);
            if (dept == null) {
                return null;
            }
            current = dept.getParentId();
        }
        if (current != null) {
            log.error("部门树向上遍历出现环，逐级匹配处理小组提前终止：start={} 再次遇到 {}",
                    startDepartmentId, current);
        }
        return null;
    }

    @Override
    public Long rootDivisionIdOf(Long departmentId) {
        Department dept = departmentId == null ? null : departmentMapper.selectById(departmentId);
        if (dept == null) {
            return null;
        }
        List<Long> chain = idChainOf(dept);
        // path 形如 "/1/10/20/"：第 1 段是根（公司），第 2 段才是一级分部。
        // 深度 1 的部门 chain 长度恰为 2，get(1) 就是它自己 —— 调用方不必区分「传的是分部还是子部门」。
        return chain.size() >= 2 ? chain.get(1) : null;
    }

    @Override
    public Long boundHandlerDepartmentOf(Long divisionId) {
        if (divisionId == null) {
            return null;
        }
        DivisionHandlerGroup binding = divisionHandlerGroupMapper.selectOne(
                Wrappers.<DivisionHandlerGroup>lambdaQuery()
                        .eq(DivisionHandlerGroup::getDivisionId, divisionId)
                        .last("LIMIT 1"));
        return binding == null ? null : binding.getHandlerDepartmentId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void bindHandlerGroup(Long divisionId, Long handlerDepartmentId) {
        Department division = divisionId == null ? null : departmentMapper.selectById(divisionId);
        if (division == null) {
            throw new BusinessException(ErrorCode.DEPARTMENT_NOT_FOUND);
        }
        if (!Objects.equals(division.getDepth(), 1)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "处理小组只能绑定在一级分部上，「" + division.getDeptName() + "」不是一级分部");
        }
        if (handlerDepartmentId == null) {
            divisionHandlerGroupMapper.delete(Wrappers.<DivisionHandlerGroup>lambdaQuery()
                    .eq(DivisionHandlerGroup::getDivisionId, divisionId));
            log.info("已解除一级分部 {} 的处理小组绑定", divisionId);
            return;
        }
        if (Objects.equals(divisionId, handlerDepartmentId)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "不能把分部绑定到它自己");
        }
        Department handler = departmentMapper.selectById(handlerDepartmentId);
        if (handler == null) {
            throw new BusinessException(ErrorCode.DEPARTMENT_NOT_FOUND);
        }
        if (!Boolean.TRUE.equals(handler.getHandlerGroup())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "「" + handler.getDeptName() + "」不是处理小组，请先在部门标记中把它设为处理小组");
        }
        if (!Boolean.TRUE.equals(handler.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "处理小组「" + handler.getDeptName() + "」已停用，不能绑定");
        }

        DivisionHandlerGroup existing = divisionHandlerGroupMapper.selectOne(
                Wrappers.<DivisionHandlerGroup>lambdaQuery()
                        .eq(DivisionHandlerGroup::getDivisionId, divisionId)
                        .last("LIMIT 1"));
        if (existing == null) {
            DivisionHandlerGroup created = new DivisionHandlerGroup();
            created.setDivisionId(divisionId);
            created.setHandlerDepartmentId(handlerDepartmentId);
            divisionHandlerGroupMapper.insert(created);
        } else {
            divisionHandlerGroupMapper.update(null, Wrappers.<DivisionHandlerGroup>lambdaUpdate()
                    .eq(DivisionHandlerGroup::getId, existing.getId())
                    .set(DivisionHandlerGroup::getHandlerDepartmentId, handlerDepartmentId));
        }
        // 成员为空只告警不拦：配置可以先于人员到位（新分部刚成立时常见），
        // 拦死会让管理员无法先把绑定关系建起来
        if (activeMemberIdsOf(handlerDepartmentId).isEmpty()) {
            log.warn("分部 {} 绑定的处理小组 {} 暂无在职成员，该分部的工单将在审批通过后分配失败",
                    divisionId, handlerDepartmentId);
        }
        log.info("一级分部 {} 已绑定处理小组 {}", divisionId, handlerDepartmentId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setDefaultHandlerDepartment(Long handlerDepartmentId) {
        // ① 先清掉全部标记。必须显式 set(false)：全局 update-strategy=not_null 会跳过
        //    非 null 之外的一切，而 Boolean.FALSE 不是 null —— 但 updateById 的字段策略
        //    对 false 的写入依赖具体实现，显式 lambdaUpdate().set(...) 最稳妥。
        //    where 加 isNotNull(id) 只为避开「无任何条件」的全表更新写法（部分 MP 版本会拦）。
        departmentMapper.update(null, Wrappers.<Department>lambdaUpdate()
                .isNotNull(Department::getId)
                .set(Department::getHandlerDefault, false));
        if (handlerDepartmentId == null) {
            log.info("已清除全局默认处理小组标记，解析时将回退为 id 最小的处理小组");
            return;
        }
        Department handler = departmentMapper.selectById(handlerDepartmentId);
        if (handler == null) {
            throw new BusinessException(ErrorCode.DEPARTMENT_NOT_FOUND);
        }
        if (!Boolean.TRUE.equals(handler.getHandlerGroup())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "「" + handler.getDeptName() + "」不是处理小组，不能设为全局默认");
        }
        if (!Boolean.TRUE.equals(handler.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "处理小组「" + handler.getDeptName() + "」已停用，不能设为全局默认");
        }
        departmentMapper.update(null, Wrappers.<Department>lambdaUpdate()
                .eq(Department::getId, handlerDepartmentId)
                .set(Department::getHandlerDefault, true));
        log.info("全局默认处理小组已设为 {}（{}）", handlerDepartmentId, handler.getDeptName());
    }

    @Override
    public Map<Long, Long> handlerGroupBindings() {
        Map<Long, Long> result = new LinkedHashMap<>();
        for (DivisionHandlerGroup binding : divisionHandlerGroupMapper.selectList(null)) {
            if (binding.getDivisionId() != null && binding.getHandlerDepartmentId() != null) {
                result.put(binding.getDivisionId(), binding.getHandlerDepartmentId());
            }
        }
        return result;
    }

    @Override
    public List<DepartmentOptionVO> handlerGroupOptions() {
        List<Department> all = departmentMapper.selectList(Wrappers.<Department>lambdaQuery()
                .eq(Department::getHandlerGroup, true)
                .orderByAsc(Department::getSortOrder)
                .orderByAsc(Department::getId));
        Map<Long, Department> index = all.stream()
                .collect(Collectors.toMap(Department::getId, d -> d, (a, b) -> a, LinkedHashMap::new));
        List<DepartmentOptionVO> options = new ArrayList<>();
        for (Department dept : all) {
            options.add(DepartmentOptionVO.of(dept, displayPathOf(dept, index)));
        }
        return options;
    }

    @Override
    public DivisionHandlerConfigVO handlerGroupConfig() {
        List<Department> all = departmentMapper.selectList(Wrappers.<Department>lambdaQuery()
                .orderByAsc(Department::getDepth)
                .orderByAsc(Department::getSortOrder)
                .orderByAsc(Department::getId));
        Map<Long, Department> index = all.stream()
                .collect(Collectors.toMap(Department::getId, d -> d, (a, b) -> a, LinkedHashMap::new));

        DivisionHandlerConfigVO vo = new DivisionHandlerConfigVO();
        Long defaultId = defaultHandlerDepartmentId();
        vo.setDefaultHandlerDepartmentId(defaultId);
        Department defaultDept = defaultId == null ? null : index.get(defaultId);
        vo.setDefaultHandlerDepartmentName(defaultDept == null ? null : defaultDept.getDeptName());

        Map<Long, Long> bindings = handlerGroupBindings();
        for (Department dept : all) {
            // 「分部」= 部门树 depth = 1 的节点（用户裁决 D6=A），根节点（公司）不参与绑定
            if (!Objects.equals(dept.getDepth(), 1)) {
                continue;
            }
            DivisionHandlerConfigVO.DivisionBinding binding = new DivisionHandlerConfigVO.DivisionBinding();
            binding.setDivisionId(dept.getId());
            binding.setDivisionName(dept.getDeptName());
            Long handlerId = bindings.get(dept.getId());
            binding.setHandlerDepartmentId(handlerId);
            Department handler = handlerId == null ? null : index.get(handlerId);
            binding.setHandlerDepartmentName(handler == null ? null : handler.getDeptName());
            vo.getDivisions().add(binding);
        }
        return vo;
    }

    @Override
    public Long resolveHandlerDepartment(Long startDepartmentId) {
        Long matched = handlerDepartmentOf(startDepartmentId);
        if (matched != null) {
            return matched;
        }
        Long fallback = defaultHandlerDepartmentId();
        if (fallback == null) {
            // 连默认组都没有：这不是「漏配一个分部」，而是系统未初始化处理小组，
            // 由调用方抛 HANDLER_DEPARTMENT_NOT_CONFIGURED（与 V53 之前一致）
            return null;
        }
        notifyHandlerFallbackOnce(startDepartmentId, fallback);
        return fallback;
    }

    /**
     * 「分部漏配、用默认组兜底」的告警（30 分钟内同一分部只发一次）。
     *
     * <p>告警失败绝不影响主流程：{@link AlertService#alert} 自身已约定不抛，
     * 这里再加一层兜底是因为它挂在<b>提交工单</b>的链路上 —— 一个通知问题
     * 不该让用户提交不了单。
     */
    private void notifyHandlerFallbackOnce(Long startDepartmentId, Long fallbackId) {
        Long divisionId = rootDivisionIdOf(startDepartmentId);
        Long key = divisionId == null ? 0L : divisionId;
        long now = System.currentTimeMillis();
        Long last = fallbackAlertedAt.get(key);
        if (last != null && now - last < FALLBACK_ALERT_INTERVAL_MS) {
            return;
        }
        fallbackAlertedAt.put(key, now);

        Department division = divisionId == null ? null : departmentMapper.selectById(divisionId);
        Department fallback = departmentMapper.selectById(fallbackId);
        String divisionName = division == null ? "（申请人未分配部门）" : division.getDeptName();
        String fallbackName = fallback == null ? ("#" + fallbackId) : fallback.getDeptName();
        log.warn("分部「{}」(id={}) 未绑定处理小组，本次用默认组「{}」(id={}) 兜底 —— "
                        + "工单仍可正常流转，但该分部的单子会全部落到默认组，请到组织与人员页补配置",
                divisionName, divisionId, fallbackName, fallbackId);
        try {
            alertService.alert(AlertLevel.P1, MessageType.HANDLER_GROUP_FALLBACK,
                    "分部「" + divisionName + "」未绑定处理小组",
                    "该分部的工单已自动转给默认处理小组「" + fallbackName + "」处理。\n"
                            + "若这不是预期结果，请到「组织与人员 → 部门树」把该分部绑定到它自己的 IT 处理小组。\n"
                            + "同一分部 30 分钟内只提醒一次。",
                    "HANDLER_GROUP_FALLBACK",
                    "division=" + divisionName + "(id=" + divisionId + ")"
                            + " | fallback=" + fallbackName + "(id=" + fallbackId + ")");
        } catch (RuntimeException e) {
            log.warn("处理小组兜底告警发送失败（不影响工单提交）：{}", e.getMessage());
        }
    }

    /**
     * 某部门从根到自身的 id 链（含首尾）。
     *
     * <p>优先解析物化路径 {@code path}（一次查询即可）；path 缺失/脏数据时退回
     * {@code parent_id} 逐级向上 —— 后者每次一跳一次查询，但正常数据不会走到这条分支。
     */
    private List<Long> idChainOf(Department dept) {
        String path = dept.getPath();
        if (StringUtils.hasText(path)) {
            List<Long> ids = new ArrayList<>();
            try {
                for (String raw : path.split("/")) {
                    if (!raw.isBlank()) {
                        ids.add(Long.parseLong(raw.trim()));
                    }
                }
                if (!ids.isEmpty()) {
                    return ids;
                }
            } catch (NumberFormatException e) {
                log.warn("部门 {} 的物化路径不合法（{}），回退为逐级向上解析", dept.getId(), path);
            }
        }
        List<Long> ids = new ArrayList<>();
        Set<Long> guard = new LinkedHashSet<>();
        Long cursor = dept.getId();
        while (cursor != null && guard.add(cursor)) {
            ids.add(cursor);
            Department node = departmentMapper.selectById(cursor);
            cursor = node == null ? null : node.getParentId();
        }
        java.util.Collections.reverse(ids);
        return ids;
    }

    @Override
    public Long parentIdOf(Long departmentId) {
        if (departmentId == null) {
            return null;
        }
        Department department = departmentMapper.selectById(departmentId);
        return department == null ? null : department.getParentId();
    }

    @Override
    public List<Long> handlerDepartmentMemberIds() {
        Long handlerId = handlerDepartmentId();
        if (handlerId == null) {
            return List.of();
        }
        return activeMemberIdsOf(handlerId);
    }

    /**
     * 某部门的**在职**成员 user_id（主部门 ∪ 兼职，再过滤在职可用）。
     *
     * <p>这是「谁能当执行人 / 谁能接单」的**唯一实现**。原先
     * {@code OrderServiceImpl} / {@code OrderTransferServiceImpl} /
     * {@code ApproverRuleResolver} / {@code FaultRepairAssigneeResolver}
     * 各自写了一份「只查 {@code user_department}」的版本 —— 那份在真实部署上恒为空，
     * 因为运行期没有任何代码往 {@code user_department} 写兼职关系。
     * 成员口径收在这里之后，将来改判定规则（比如排除试用期）只需要动这一个方法。
     */
    @Override
    public List<Long> activeMemberIdsOf(Long departmentId) {
        return activeMemberIds(memberIdsOf(departmentId));
    }

    @Override
    public List<Long> subtreeIds(Long departmentId) {
        if (departmentId == null) {
            return List.of();
        }
        Department dept = departmentMapper.selectById(departmentId);
        if (dept == null || !StringUtils.hasText(dept.getPath())) {
            return List.of();
        }
        String prefix = dept.getPath();
        return departmentMapper.selectList(Wrappers.<Department>lambdaQuery()
                        .likeRight(Department::getPath, prefix)).stream()
                .map(Department::getId)
                .toList();
    }

    /**
     * 某部门的**全部**成员 user_id（主部门 ∪ 兼职），顺序稳定。
     *
     * <p><b>刻意保持 private</b>：它返回的是「未过滤在职状态」的原始集合，不是对外口径。
     * 外部一律走 {@link #activeMemberIdsOf(Long)}（在职可用）。若把它放成 public、
     * 又不进接口，就容易被误当成「对外成员口径」直接调用 —— 这正是本次
     * 「第 5 份口径」病根的温床。仅本类内部 {@code activeMemberIdsOf / membersOf / delete 校验} 三处使用。
     */
    private List<Long> memberIdsOf(Long departmentId) {
        if (departmentId == null) {
            return List.of();
        }
        Set<Long> ids = new LinkedHashSet<>();
        userMapper.selectList(Wrappers.<User>lambdaQuery()
                        .select(User::getId)
                        .eq(User::getDepartmentId, departmentId)).stream()
                .map(User::getId)
                .forEach(ids::add);
        userDepartmentMapper.selectList(Wrappers.<UserDepartment>lambdaQuery()
                        .eq(UserDepartment::getDepartmentId, departmentId)).stream()
                .map(UserDepartment::getUserId)
                .forEach(ids::add);
        return new ArrayList<>(ids);
    }

    /**
     * 从候选 id 里筛出**在职可用**的（{@code enabled = 1 且 is_dimission = 0}），保持入参顺序。
     *
     * <p>订单侧原先各自写的实现里有的是逐个 {@code selectById}（N+1），这里统一成一次批量查。
     */
    private List<Long> activeMemberIds(List<Long> candidateIds) {
        if (candidateIds == null || candidateIds.isEmpty()) {
            return List.of();
        }
        Map<Long, User> users = usersByIds(candidateIds);
        return candidateIds.stream()
                .map(users::get)
                .filter(Objects::nonNull)
                .filter(user -> Boolean.TRUE.equals(user.getEnabled()) && !Boolean.TRUE.equals(user.getDimission()))
                .map(User::getId)
                .distinct()
                .toList();
    }

    /** 批量取用户，按 id 建索引；查不到的人不会出现在结果里 */
    private Map<Long, User> usersByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(ids).stream()
                .filter(Objects::nonNull)
                .filter(user -> user.getId() != null)
                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a, LinkedHashMap::new));
    }

    @Override
    public List<UserOptionVO> membersOf(Long departmentId) {
        List<Long> ids = memberIdsOf(departmentId);
        if (ids.isEmpty()) {
            return List.of();
        }
        // 复用同一批用户，避免第二次查询与 activeMemberIds 的口径漂移
        Map<Long, User> users = usersByIds(ids);
        Map<Long, String> deptNames = departmentMapper.selectList(Wrappers.<Department>lambdaQuery()
                        .select(Department::getId, Department::getDeptName)).stream()
                .collect(Collectors.toMap(Department::getId, Department::getDeptName, (a, b) -> a));
        return new ArrayList<>(users.values()).stream()
                .sorted((a, b) -> Long.compare(a.getId() == null ? 0 : a.getId(),
                        b.getId() == null ? 0 : b.getId()))
                .map(user -> UserOptionVO.of(user, deptNames.get(user.getDepartmentId())))
                .toList();
    }

    // ------------------------------------------------------------------
    // 增删改
    // ------------------------------------------------------------------

    /**
     * 校验「绑定的审批流程版本」入参 —— {@code null} 表示解绑，是合法操作。
     *
     * <h2>为什么这道校验必须存在（ 全量回归补回的既有行为）</h2>
     * <p>{@code department.approval_flow_version_id} 是**活配置**：员工提交借用单时，
     * {@code OrderServiceImpl#create} 会拿它去取已发布流程定义。若库里存着一个不存在的版本 id，
     * 报错点会漂移到「员工点提交」的那一刻 —— 管理员在部门页看到的是一次「保存成功」，
     * 缺陷却要等到某个员工提交工单才暴露，且报的是流程错误，排查方向完全被带偏。
     *
     * <p>改造前的 {@code BizGroupServiceImpl} 有这道校验（  回归 「不存在的版本 id 被拒」
     * 就是守着它）， 把 biz_group 重写成 departments 时漏带，故在此补回。
     * 「未发布（草稿）也不能绑」是同一件事的另一半：绑定只认冻结版本，
     * 否则草稿被改一次，已绑部门的审批路径就跟着变，与「已提交工单走提交时快照」的审计语义相冲。
     */
    private Long resolveFlowVersion(Long versionId) {
        if (versionId == null) {
            return null;
        }
        ApprovalFlowVersion version = approvalFlowVersionMapper.selectById(versionId);
        if (version == null) {
            throw new BusinessException(ErrorCode.FLOW_VERSION_NOT_FOUND,
                    "所选审批流程版本不存在（id=" + versionId + "），请重新选择");
        }
        if (version.isDraft()) {
            throw new BusinessException(ErrorCode.FLOW_NO_PUBLISHED_VERSION,
                    "该审批流程版本尚未发布，不能绑定到部门");
        }
        return versionId;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(DepartmentSaveRequest request) {
        String name = trimToNull(request.getDeptName());
        if (name == null) {
            throw new BusinessException(ErrorCode.DEPARTMENT_NAME_EXISTS, "部门名称不能为空");
        }
        Long parentId = resolveParent(request.getParentId());
        assertNameAvailable(parentId, name, null);
        // 校验放在建行之前：绑定一个不存在的版本不该留下半个部门
        Long flowVersionId = resolveFlowVersion(request.getApprovalFlowVersionId());

        Department parent = departmentMapper.selectById(parentId);
        String parentPath = parent == null || !StringUtils.hasText(parent.getPath())
                ? "/" + parentId + "/"
                : parent.getPath();

        Department dept = new Department();
        dept.setDeptName(name);
        dept.setParentId(parentId);
        dept.setDepth(parent == null || parent.getDepth() == null ? 0 : parent.getDepth() + 1);
        dept.setSortOrder(request.getSortOrder() == null ? 0 : request.getSortOrder());
        dept.setApprovalFlowVersionId(flowVersionId);
        dept.setHandlerGroup(false);
        dept.setStatus(true);
        dept.setRemark(trimToNull(request.getRemark()));
        // path 依赖自身 id → 先落占位行再回填（一次事务，外部读不到占位值）
        dept.setPath(parentPath + PATH_PENDING);
        departmentMapper.insert(dept);
        dept.setPath(parentPath + dept.getId() + "/");
        departmentMapper.updateById(dept);
        return dept.getId();
    }

    /**
     * 编辑部门：名称 / 排序 / 绑定流程 / 备注。**不含父子关系** —— 改层级必须走 {@link #move}。
     *
     * <h2>为什么这里用显式 UPDATE 而不是 {@code updateById}</h2>
     * <p>全局配置 {@code mybatis-plus.global-config.db-config.update-strategy: not_null}
     * 会让 {@code updateById} **跳过所有 null 字段**。而本接口是 PUT（整体替换）语义，
     * 「解绑流程」「清空备注」恰恰要求把列**写成 NULL**：走 {@code updateById} 时这两个操作
     * 会返回「部门已保存」却一个字段都没改 —— 静默无效，管理员以为解绑了、其实还绑着，
     * 直到员工提交工单走了旧流程才发现。
     *
     * <p>所以这里显式 {@code .set(...)}：{@code set} 不经过字段策略，null 也会进 SET 子句。
     * 这是 全量回归发现的第二个既有缺陷（改造前 {@code BizGroupServiceImpl} 用的是
     * 显式 UPDATE，所以   回归 / 的「解绑 → 回读为 null」是 PASS 的；
     * A+B 改成 {@code updateById} 后这两条翻红）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, DepartmentSaveRequest request) {
        Department dept = requireDepartment(id);
        String name = trimToNull(request.getDeptName());
        if (name != null && !name.equals(dept.getDeptName())) {
            assertNameAvailable(dept.getParentId(), name, id);
            dept.setDeptName(name);
        }
        if (request.getSortOrder() != null) {
            dept.setSortOrder(request.getSortOrder());
        }
        // 先校验再写库：不存在的版本直接拒，不留下一次「看似成功」的脏配置
        Long flowVersionId = resolveFlowVersion(request.getApprovalFlowVersionId());

        departmentMapper.update(null, Wrappers.<Department>lambdaUpdate()
                .eq(Department::getId, id)
                // 可空字段一律显式 set：解绑流程 / 清空备注要真的写成 NULL
                .set(Department::getDeptName, dept.getDeptName())
                .set(Department::getSortOrder, dept.getSortOrder())
                .set(Department::getApprovalFlowVersionId, flowVersionId)
                .set(Department::getRemark, trimToNull(request.getRemark())));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void move(Long id, Long newParentId) {
        Department dept = requireDepartment(id);
        if (dept.getParentId() == null) {
            throw new BusinessException(ErrorCode.DEPARTMENT_ROOT_PROTECTED);
        }
        Long parentId = resolveParent(newParentId);
        if (Objects.equals(parentId, id)) {
            throw new BusinessException(ErrorCode.DEPARTMENT_MOVE_INVALID, "不能把部门移动到它自己下面");
        }
        Department newParent = requireDepartment(parentId);
        // 禁止移动到自己的子孙下：否则会形成环，path 拼接会无限增长且树渲染直接栈溢出
        if (StringUtils.hasText(dept.getPath()) && StringUtils.hasText(newParent.getPath())
                && newParent.getPath().startsWith(dept.getPath())) {
            throw new BusinessException(ErrorCode.DEPARTMENT_MOVE_INVALID, "不能把部门移动到它的下级部门里");
        }
        assertNameAvailable(parentId, dept.getDeptName(), id);

        String oldPath = dept.getPath();
        String newPath = newParent.getPath() + id + "/";
        int depthShift = (newParent.getDepth() == null ? 0 : newParent.getDepth() + 1)
                - (dept.getDepth() == null ? 0 : dept.getDepth());

        dept.setParentId(parentId);
        dept.setPath(newPath);
        dept.setDepth(newParent.getDepth() == null ? 0 : newParent.getDepth() + 1);
        departmentMapper.updateById(dept);

        // 整体平移子树：一次 UPDATE 用 REPLACE 换前缀，避免按层递归
        if (StringUtils.hasText(oldPath)) {
            List<Department> descendants = departmentMapper.selectList(Wrappers.<Department>lambdaQuery()
                    .likeRight(Department::getPath, oldPath)
                    .ne(Department::getId, id));
            for (Department child : descendants) {
                child.setPath(newPath + child.getPath().substring(oldPath.length()));
                child.setDepth((child.getDepth() == null ? 0 : child.getDepth()) + depthShift);
                departmentMapper.updateById(child);
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DepartmentDeleteResultVO delete(Long id) {
        Department dept = requireDepartment(id);
        if (dept.getParentId() == null) {
            throw new BusinessException(ErrorCode.DEPARTMENT_ROOT_PROTECTED);
        }
        Long children = departmentMapper.selectCount(Wrappers.<Department>lambdaQuery()
                .eq(Department::getParentId, id));
        if (children != null && children > 0) {
            throw new BusinessException(ErrorCode.DEPARTMENT_HAS_CHILDREN,
                    "「" + dept.getDeptName() + "」下还有 " + children + " 个子部门，请先处理子部门");
        }
        if (Boolean.TRUE.equals(dept.getHandlerGroup())) {
            throw new BusinessException(ErrorCode.DEPARTMENT_ROOT_PROTECTED,
                    "「" + dept.getDeptName() + "」是最终处理部门（IT运维组），不可删除");
        }

        // ① 有成员的部门禁止删除（P1 安全修复）。
        //    删除会连带清除该部门的 department_manager 行 ⇒ 原本走「部门主管」审批的工单
        //    静默改道为超管兜底 —— 一次误点即改写一批人的审批路径。故只允许删除空部门。
        List<Long> memberIds = memberIdsOf(id);
        if (!memberIds.isEmpty()) {
            throw new BusinessException(ErrorCode.DEPARTMENT_HAS_MEMBERS,
                    "「" + dept.getDeptName() + "」下还有 " + memberIds.size()
                            + " 名成员，请先把成员调到其他部门再删除");
        }

        Long parentId = dept.getParentId();
        Department parent = departmentMapper.selectById(parentId);
        // ② 主管关系一并清除（部门都没了，留下悬空主管行会让后续解析莫名多出一人）
        departmentManagerMapper.delete(Wrappers.<DepartmentManager>lambdaQuery()
                .eq(DepartmentManager::getDepartmentId, id));
        // ③ 删行
        departmentMapper.deleteById(id);

        DepartmentDeleteResultVO result = new DepartmentDeleteResultVO();
        result.setDeptName(dept.getDeptName());
        // 只有空部门可删 ⇒ 恒定 0；字段保留是为了不动前端响应契约
        result.setMovedMemberCount(0);
        result.setMovedToDepartmentId(parentId);
        result.setMovedToDepartmentName(parent == null ? null : parent.getDeptName());
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setManagers(Long departmentId, List<Long> userIds) {
        Department dept = requireDepartment(departmentId);
        List<Long> target = userIds == null ? List.of()
                : userIds.stream().filter(Objects::nonNull).distinct().toList();

        departmentManagerMapper.delete(Wrappers.<DepartmentManager>lambdaQuery()
                .eq(DepartmentManager::getDepartmentId, departmentId));
        for (Long userId : target) {
            User user = userMapper.selectById(userId);
            if (user == null) {
                throw new BusinessException(ErrorCode.USER_NOT_FOUND, "部门主管用户不存在：" + userId);
            }
            DepartmentManager manager = new DepartmentManager();
            manager.setDepartmentId(departmentId);
            manager.setUserId(userId);
            departmentManagerMapper.insert(manager);
        }
        log.info("部门「{}」主管更新为 {} 人", dept.getDeptName(), target.size());
        // 主管变更 → 未手工覆盖的成员直属主管自动跟随
        syncMembersLeader(departmentId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void syncMembersLeader(Long departmentId) {
        Long leaderId = primaryManagerOf(departmentId);
        if (leaderId == null || departmentId == null) {
            return;
        }
        // 只改「未手工覆盖」的人：leader_override = 1 是管理员手工指定的，覆盖掉等于吃掉手工配置
        int updated = userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .eq(User::getDepartmentId, departmentId)
                .and(w -> w.isNull(User::getLeaderOverride).or().eq(User::getLeaderOverride, false))
                .set(User::getLeaderId, leaderId));
        if (updated > 0) {
            log.info("部门 {} 的 {} 名成员直属主管已同步为 {}", departmentId, updated, leaderId);
        }
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private Department requireDepartment(Long id) {
        if (id == null) {
            throw new BusinessException(ErrorCode.DEPARTMENT_NOT_FOUND);
        }
        Department dept = departmentMapper.selectById(id);
        if (dept == null) {
            throw new BusinessException(ErrorCode.DEPARTMENT_NOT_FOUND);
        }
        return dept;
    }

    /** 未指定上级时挂到根节点（公司）下 —— 避免出现第二棵树 */
    private Long resolveParent(Long parentId) {
        if (parentId != null) {
            return parentId;
        }
        Department root = departmentMapper.selectOne(Wrappers.<Department>lambdaQuery()
                .isNull(Department::getParentId)
                .orderByAsc(Department::getId)
                .last("LIMIT 1"));
        if (root == null) {
            throw new BusinessException(ErrorCode.DEPARTMENT_NOT_FOUND, "组织根节点（公司）不存在");
        }
        return root.getId();
    }

    private void assertNameAvailable(Long parentId, String name, Long excludeId) {
        Long exists = departmentMapper.selectCount(Wrappers.<Department>lambdaQuery()
                .eq(Department::getDeptName, name)
                .eq(parentId == null, Department::getParentId, null)
                .eq(parentId != null, Department::getParentId, parentId)
                .ne(excludeId != null, Department::getId, excludeId));
        if (exists != null && exists > 0) {
            throw new BusinessException(ErrorCode.DEPARTMENT_NAME_EXISTS,
                    "「" + name + "」在同级下已存在，请换一个名称");
        }
    }

    private DepartmentNodeVO toNode(Department dept) {
        DepartmentNodeVO vo = new DepartmentNodeVO();
        vo.setId(dept.getId());
        vo.setDeptName(dept.getDeptName());
        vo.setParentId(dept.getParentId());
        vo.setPath(dept.getPath());
        vo.setDepth(dept.getDepth());
        vo.setSortOrder(dept.getSortOrder());
        vo.setHandlerGroup(Boolean.TRUE.equals(dept.getHandlerGroup()));
        vo.setApprovalFlowVersionId(dept.getApprovalFlowVersionId());
        vo.setStatus(dept.getStatus());
        vo.setRemark(dept.getRemark());
        return vo;
    }

    private String displayPathOf(Department dept, Map<Long, Department> index) {
        List<String> names = new ArrayList<>();
        Department cursor = dept;
        int guard = 0;
        while (cursor != null && guard++ < 32) {
            names.add(0, cursor.getDeptName());
            cursor = cursor.getParentId() == null ? null : index.get(cursor.getParentId());
        }
        return String.join(" / ", names);
    }

    /** 主部门人数：users.department_id 分组计数 */
    private Map<Long, Long> primaryMemberCounts() {
        Map<Long, Long> counts = new HashMap<>();
        userMapper.selectList(Wrappers.<User>lambdaQuery()
                        .select(User::getId, User::getDepartmentId)
                        .isNotNull(User::getDepartmentId)).stream()
                .map(User::getDepartmentId)
                .forEach(id -> counts.merge(id, 1L, Long::sum));
        return counts;
    }

    /** **仅**兼职在本部门的人数（主部门已是本部门的排除掉，避免与 primaryMemberCounts 重复计） */
    private Map<Long, Long> extraMemberCounts() {
        List<UserDepartment> all = userDepartmentMapper.selectList(null);
        if (all.isEmpty()) {
            return Map.of();
        }
        Set<Long> userIds = all.stream().map(UserDepartment::getUserId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, Long> primaryOf = userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u.getDepartmentId() == null ? -1L : u.getDepartmentId(),
                        (a, b) -> a));
        Map<Long, Long> extra = new HashMap<>();
        for (UserDepartment membership : all) {
            Long primary = primaryOf.get(membership.getUserId());
            if (Objects.equals(primary, membership.getDepartmentId())) {
                continue;
            }
            extra.merge(membership.getDepartmentId(), 1L, Long::sum);
        }
        return extra;
    }

    private Map<Long, List<UserOptionVO>> managersByDepartment(List<Department> departments) {
        List<DepartmentManager> managers = departmentManagerMapper.selectList(null);
        if (managers.isEmpty() || departments.isEmpty()) {
            return Map.of();
        }
        Set<Long> userIds = managers.stream().map(DepartmentManager::getUserId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, User> users = userIds.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(userIds).stream()
                        .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
        Map<Long, String> deptNames = departments.stream()
                .collect(Collectors.toMap(Department::getId, Department::getDeptName, (a, b) -> a));

        Map<Long, List<UserOptionVO>> result = new LinkedHashMap<>();
        for (DepartmentManager manager : managers) {
            User user = users.get(manager.getUserId());
            if (user == null) {
                continue;
            }
            UserOptionVO vo = UserOptionVO.of(user, deptNames.get(user.getDepartmentId()));
            result.computeIfAbsent(manager.getDepartmentId(), k -> new ArrayList<>()).add(vo);
        }
        return result;
    }

    private static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
