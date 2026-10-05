package com.enterprise.ticket.module.order.support;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ticket.common.api.PageResult;
import com.enterprise.ticket.common.constant.ApprovalNodeStatus;
import com.enterprise.ticket.common.constant.OrderStatus;
import com.enterprise.ticket.common.constant.OrderType;
import com.enterprise.ticket.common.constant.ReturnCondition;
import com.enterprise.ticket.common.constant.ReturnTrigger;
import com.enterprise.ticket.common.constant.RoleCode;
import com.enterprise.ticket.common.constant.SignType;
import com.enterprise.ticket.common.constant.UseType;
import com.enterprise.ticket.common.flow.ApproverRule;
import com.enterprise.ticket.common.flow.ApproverRuleType;
import com.enterprise.ticket.common.flow.FlowDefinitionCodec;
import com.enterprise.ticket.common.flow.FlowNode;
import com.enterprise.ticket.common.flow.FlowNodeType;
import com.enterprise.ticket.module.device.entity.Device;
import com.enterprise.ticket.module.order.dto.vo.ApprovalNodeView;
import com.enterprise.ticket.module.order.dto.vo.OrderDetailVO;
import com.enterprise.ticket.module.order.dto.vo.OrderVO;
import com.enterprise.ticket.module.order.entity.Order;
import com.enterprise.ticket.module.order.entity.OrderApprovalNode;
import com.enterprise.ticket.module.order.mapper.OrderApprovalNodeMapper;
import com.enterprise.ticket.module.order.service.OrderExtendService;
import com.enterprise.ticket.module.order.service.OrderForceOperationService;
import com.enterprise.ticket.module.order.service.OrderTransferService;
import com.enterprise.ticket.module.order.service.OrderUrgeService;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工单视图装配器（ ·  · W4-A2，自 {@code OrderServiceImpl} 逐字搬出）。
 *
 * <h2>职责边界</h2>
 * <p>把「工单实体 + 审批节点快照 + 各类统计」装配成前端要看的
 * {@link OrderVO}（列表）与 {@link OrderDetailVO}（详情），包括：
 * <ul>
 *   <li>字典回填（设备 / 申请人 / 部门 / 处理小组 / 申请类型）；</li>
 *   <li><b>操作入口判定</b>：{@code canRequestReturn} / {@code canConfirmReturn} /
 *       {@code canTransfer} / {@code canUrgeApproval} / {@code canUrgeReturn} /
 *       {@code canForceOperate} / {@code canRequestExtend} —— 一律服务端算好、前端只渲染；</li>
 *   <li> 节点视图：时限与超时、待指派、由上一节点指定（及其指定者姓名）；</li>
 *   <li>延期 / 转交 / 催办的状态与冷却倒计时。</li>
 * </ul>
 *
 * <h2>它不做什么（这三条是刻意的）</h2>
 * <ol>
 *   <li><b>不读安全上下文</b>：{@code currentRole} 由调用方传入。原实现直接调
 *       {@code OrderServiceImpl#currentRole()}，导致装配逻辑无法脱离 Spring Security 测试；
 *       改成参数后，本类可被纯单测直接构造；</li>
 *   <li><b>不判定可见性</b>：{@code assertCanView} 留在服务层 —— 权限判定属于入口职责，
 *       混进装配器会让「谁有权看」散落到两个类；</li>
 *   <li><b>不做权限放行</b>：{@code canXxx} 只是<b>呈现建议</b>，真正的拦截在写接口里
 *       （两处保持同一套判定的意图，见各 {@code applyXxx} 的说明）。</li>
 * </ol>
 *
 * <h2>搬运时逐字保留的三处细节</h2>
 * <ul>
 *   <li>列表装配的全部字典与统计都<b>批量取</b>（extendStats / transferCounts / urgeStats 各一条 SQL），
 *       这是避免 N+1 的关键，不要在逐行 fill 里加查询；</li>
 *   <li>字典取用一律走 {@link OrderReferenceNames#mapGet}（null key 安全）——
 *       自定义工单外键可为 NULL，直接 {@code .get()} 会因 {@code Map.of()} 抛 NPE；</li>
 *   <li>「当前步骤」走 {@link OrderApprovalNodeSupport#currentStepOrder}，与任务侧同源。</li>
 * </ul>
 */
@Slf4j
@Component
public class OrderViewAssembler {

    private final OrderApprovalNodeMapper nodeMapper;
    private final OrderExtendService orderExtendService;
    private final OrderTransferService orderTransferService;
    private final OrderUrgeService orderUrgeService;
    private final OrderForceOperationService orderForceOperationService;
    private final SystemConfigService systemConfigService;
    private final OrderReferenceNames referenceNames;

    public OrderViewAssembler(OrderApprovalNodeMapper nodeMapper,
                              OrderExtendService orderExtendService,
                              OrderTransferService orderTransferService,
                              OrderUrgeService orderUrgeService,
                              OrderForceOperationService orderForceOperationService,
                              SystemConfigService systemConfigService,
                              OrderReferenceNames referenceNames) {
        this.nodeMapper = nodeMapper;
        this.orderExtendService = orderExtendService;
        this.orderTransferService = orderTransferService;
        this.orderUrgeService = orderUrgeService;
        this.orderForceOperationService = orderForceOperationService;
        this.systemConfigService = systemConfigService;
        this.referenceNames = referenceNames;
    }

    // ------------------------------------------------------------------
    // 列表
    // ------------------------------------------------------------------

    public PageResult<OrderVO> toPageResult(IPage<Order> resultPage, Long currentUserId, String currentRole) {
        List<Order> orders = resultPage.getRecords();
        // 字典与快照一次性批量加载，避免逐行 fill 造成 N+1
        Map<Long, Device> devices = referenceNames.devicesOf(orders);
        Map<Long, List<OrderApprovalNode>> nodeMap = nodesOf(orders);
        // 审批人姓名也要用于「当前审批节点」列，故除申请人/执行人外一并纳入姓名映射
        Set<Long> userIds = new LinkedHashSet<>(applicantAndHandlerIds(orders));
        nodeMap.values().forEach(nodes -> nodes.forEach(node -> {
            if (node.getApproverId() != null) {
                userIds.add(node.getApproverId());
            }
        }));
        Map<Long, String> userNames = referenceNames.userNameMap(userIds);
        Map<Long, String> departmentNames = referenceNames.departmentNames();
        Map<Long, String> applyTypeNames = referenceNames.applyTypeNames(orders);
        // ：延期统计同样批量取，避免逐行查询延期表
        List<Long> orderIds = orders.stream().map(Order::getId).toList();
        Map<Long, OrderExtendService.ExtendStat> extendStats = orderExtendService.statByOrders(orderIds);
        int extendMaxCount = systemConfigService.extendMaxCount();
        // ：转交次数与催办状态同样各一条 SQL 批量取，避免逐行回查
        Map<Long, Integer> transferCounts = orderTransferService.transferCountByOrders(orderIds);
        Map<Long, OrderUrgeService.UrgeStat> urgeStats = orderUrgeService.statByOrders(orderIds);
        int urgeCooldownMinutes = orderUrgeService.cooldownMinutes();
        LocalDateTime now = LocalDateTime.now();
        return PageResult.of(resultPage, order -> {
            OrderVO vo = new OrderVO();
            List<OrderApprovalNode> nodes = nodeMap.get(order.getId());
            fill(vo, order, devices, userNames, departmentNames, applyTypeNames,
                    currentUserId, currentRole, nodes);
            applyExtendInfo(vo, order, currentUserId, extendMaxCount,
                    extendStats.getOrDefault(order.getId(), new OrderExtendService.ExtendStat(0, false)));
            applyTransferUrgeInfo(vo, order, currentUserId, currentRole, nodes,
                    transferCounts.getOrDefault(order.getId(), 0),
                    urgeStats.getOrDefault(order.getId(), OrderUrgeService.UrgeStat.EMPTY),
                    urgeCooldownMinutes, now);
            return vo;
        });
    }

    // ------------------------------------------------------------------
    // 详情
    // ------------------------------------------------------------------

    public OrderDetailVO toDetail(Order order, Long currentUserId, String currentRole,
                                  List<OrderApprovalNode> nodes) {
        Set<Long> userIds = new LinkedHashSet<>(applicantAndHandlerIds(List.of(order)));
        nodes.forEach(node -> {
            if (node.getApproverId() != null) {
                userIds.add(node.getApproverId());
            }
        });

        Map<Long, String> userNames = referenceNames.userNameMap(userIds);
        OrderDetailVO vo = new OrderDetailVO();
        fill(vo, order, referenceNames.devicesOf(List.of(order)), userNames,
                referenceNames.departmentNames(),
                referenceNames.applyTypeNames(List.of(order)),
                currentUserId, currentRole, nodes);
        applyExtendInfo(vo, order, currentUserId, systemConfigService.extendMaxCount(),
                orderExtendService.statOfOrder(order.getId()));
        // ：转交与催办（详情页只有一笔工单，无需批量）
        applyTransferUrgeInfo(vo, order, currentUserId, currentRole, nodes,
                orderTransferService.transferCountByOrders(List.of(order.getId()))
                        .getOrDefault(order.getId(), 0),
                orderUrgeService.statOfOrder(order.getId()),
                orderUrgeService.cooldownMinutes(), LocalDateTime.now());
        // ：「由上一节点指定」的标记从冻结流程快照反查（详见 prevAssignRules）
        Map<String, ApproverRule> prevAssignRules = prevAssignRules(order);
        LocalDateTime nodeViewNow = LocalDateTime.now();
        vo.setNodes(nodes.stream()
                .map(node -> toNodeView(node, userNames, nodes, prevAssignRules, nodeViewNow))
                .toList());
        vo.setApplicantSelf(Objects.equals(order.getApplicantId(), currentUserId));
        vo.setHandlerSelf(Objects.equals(order.getActualFinalHandlerId(), currentUserId));
        // ：抄送人标记（只读查看）。只看 CC 行，避免「我是审批人」被误标成抄送人
        vo.setCcSelf(nodes.stream().anyMatch(node ->
                FlowNodeType.CC.name().equals(node.getNodeType())
                        && Objects.equals(node.getApproverId(), currentUserId)));
        // 时间线数据：转交历史 + 催办记录（需求方   / ）
        vo.setTransfers(orderTransferService.listByOrder(order.getId()));
        vo.setUrges(orderUrgeService.listByOrder(order.getId()));
        // 强制干预时间线（）；非终态工单可强制操作由 canForceOperate 承载
        vo.setForceOperations(orderForceOperationService.listByOrder(order.getId()));
        return vo;
    }

    // ------------------------------------------------------------------
    // 字段填充
    // ------------------------------------------------------------------

    /**
     * 填充  延期相关字段
     *
     * <p>「能否申请延期」由服务端一次性判定，前端只渲染按钮 —— 与  的
     * {@code canRequestReturn} 保持同一套「权限判定不下放前端」的思路。
     */
    private void applyExtendInfo(OrderVO vo, Order order, Long currentUserId, int extendMaxCount,
                                 OrderExtendService.ExtendStat stat) {
        vo.setExtendUsedCount(stat.used());
        vo.setExtendMaxCount(extendMaxCount);
        vo.setExtendPending(stat.pending());
        vo.setCanRequestExtend(Objects.equals(order.getApplicantId(), currentUserId)
                && OrderStatus.BORROWED.name().equals(order.getStatus())
                && order.getPlannedEndTime() != null
                && !stat.pending()
                && stat.used() < extendMaxCount);
    }

    /**
     * 填充  转交与催办相关字段（ + 需求方 ）
     *
     * <p>「能不能转交 / 能不能催办」与写接口的校验保持同一套判定（此处偏保守），
     * 前端只负责渲染按钮与倒计时，权限规则不下放 —— 与 /6 的
     * {@code canRequestReturn} / {@code canRequestExtend} 保持同一套思路。
     *
     * <p>冷却倒计时刻意在这里算而不在催办服务里算：只有本方法能同时看到
     * 「当前审批节点」与「各节点的历史催办时间」，而冷却键正是「工单 + 节点」。
     */
    private void applyTransferUrgeInfo(OrderVO vo, Order order, Long currentUserId, String currentRole,
                                       List<OrderApprovalNode> nodes, int transferCount,
                                       OrderUrgeService.UrgeStat urgeStat, int urgeCooldownMinutes,
                                       LocalDateTime now) {
        boolean handlerSelf = Objects.equals(order.getActualFinalHandlerId(), currentUserId);
        boolean applicantSelf = Objects.equals(order.getApplicantId(), currentUserId);
        boolean superAdmin = RoleCode.isSuperAdmin(currentRole);
        OrderStatus status = OrderStatus.of(order.getStatus());

        // ---------------- 转交 ----------------
        vo.setTransferCount(transferCount);
        vo.setTransferred(transferCount > 0);
        vo.setCanTransfer(status != null && status.isTransferable()
                && order.getActualFinalHandlerId() != null
                && (handlerSelf || superAdmin));

        // ---------------- 催办审批（需求方  ） ----------------
        List<Long> currentNodeIds = currentPendingNodeIds(nodes);
        long approvalCooldown = pendingUrgeCooldownSeconds(currentNodeIds,
                urgeStat.approvalLastAtByNode(), urgeCooldownMinutes, now);
        vo.setApprovalUrgeCooldownSeconds(approvalCooldown);
        vo.setCanUrgeApproval(applicantSelf
                && status == OrderStatus.PENDING_APPROVAL
                && !currentNodeIds.isEmpty()
                && approvalCooldown == 0L);

        // ---------------- 催还（需求方  ） ----------------
        long returnCooldown = remainingCooldownSeconds(urgeStat.returnLastAt(), urgeCooldownMinutes, now);
        vo.setReturnUrgeCooldownSeconds(returnCooldown);
        vo.setCanUrgeReturn((handlerSelf || superAdmin)
                && status == OrderStatus.BORROWED
                && dueOrTimeout(order, now)
                && returnCooldown == 0L);
    }

    /** 当前应处理步骤上仍为 PENDING 的节点 id（催办冷却键的成分之一） */
    private List<Long> currentPendingNodeIds(List<OrderApprovalNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }
        Integer currentStep = OrderApprovalNodeSupport.currentStepOrder(nodes);
        if (currentStep == null) {
            return List.of();
        }
        return nodes.stream()
                .filter(node -> Objects.equals(node.getStepOrder(), currentStep))
                .filter(node -> ApprovalNodeStatus.PENDING.name().equals(node.getStatus()))
                .map(OrderApprovalNode::getId)
                .toList();
    }

    /** 取「当前节点集合」中最晚一次催办对应的冷却剩余秒数；无催办记录返回 0 */
    private long pendingUrgeCooldownSeconds(List<Long> nodeIds, Map<Long, LocalDateTime> lastAtByNode,
                                            int cooldownMinutes, LocalDateTime now) {
        if (nodeIds.isEmpty() || lastAtByNode == null || lastAtByNode.isEmpty()) {
            return 0L;
        }
        LocalDateTime latest = null;
        for (Long nodeId : nodeIds) {
            LocalDateTime at = lastAtByNode.get(nodeId);
            if (at != null && (latest == null || at.isAfter(latest))) {
                latest = at;
            }
        }
        return remainingCooldownSeconds(latest, cooldownMinutes, now);
    }

    /**
     * 冷却剩余秒数；0 表示可立即催办。
     *
     * <p>向上取整（+1 秒）：剩余不足 1 秒时服务端仍会拒绝，倒计时不该先于服务端归零，
     * 否则会出现「按钮亮了但点下去报冷却中」的抖动。
     */
    private long remainingCooldownSeconds(LocalDateTime lastAt, int cooldownMinutes, LocalDateTime now) {
        if (lastAt == null) {
            return 0L;
        }
        LocalDateTime until = lastAt.plusMinutes(cooldownMinutes);
        if (!until.isAfter(now)) {
            return 0L;
        }
        return Duration.between(now, until).getSeconds() + 1;
    }

    /** 是否已到期或已超时（催还的适用条件，与 {@code OrderUrgeServiceImpl} 同判定） */
    private boolean dueOrTimeout(Order order, LocalDateTime now) {
        if (Boolean.TRUE.equals(order.getBorrowTimeout())) {
            return true;
        }
        return order.getPlannedEndTime() != null && !order.getPlannedEndTime().isAfter(now);
    }

    private void fill(OrderVO vo, Order order, Map<Long, Device> devices, Map<Long, String> userNames,
                      Map<Long, String> departmentNames,
                      Map<Long, String> applyTypeNames,
                      Long currentUserId, String currentRole, List<OrderApprovalNode> nodes) {
        vo.setId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setDeviceId(order.getDeviceId());
        // ：自定义工单无设备 / 无部门 / 无处理小组，其外键为 NULL。
        // 一律走 mapGet（对 null key 返回 null）—— 这些字典映射在「无对应数据」时是 Map.of()，
        // 而 Map.of() 的 get(null) 会抛 NPE（不可变空 Map 的已知行为），故不能直接 .get()。
        Device device = OrderReferenceNames.mapGet(devices, order.getDeviceId());
        vo.setDeviceName(device == null ? null
                : device.getDeviceName() + "（" + device.getAssetNo() + "）");
        vo.setApplicantId(order.getApplicantId());
        vo.setApplicantName(OrderReferenceNames.mapGet(userNames, order.getApplicantId()));
        vo.setDepartmentName(OrderReferenceNames.mapGet(departmentNames, order.getDepartmentId()));
        vo.setUseType(order.getUseType());
        vo.setUseTypeLabel(UseType.labelOf(order.getUseType()));
        vo.setReason(order.getReason());
        vo.setExpectedReturnDate(order.getExpectedReturnDate());
        vo.setStatus(order.getStatus());
        vo.setStatusLabel(OrderStatus.labelOf(order.getStatus()));
        vo.setHandlerGroupName(OrderReferenceNames.mapGet(departmentNames, order.getHandlerDepartmentId()));
        vo.setActualFinalHandlerId(order.getActualFinalHandlerId());
        vo.setActualFinalHandlerName(OrderReferenceNames.mapGet(userNames, order.getActualFinalHandlerId()));
        vo.setPlannedEndTime(order.getPlannedEndTime());
        vo.setDeliveredAt(order.getDeliveredAt());
        vo.setCreatedAt(order.getCreatedAt());
        vo.setUpdatedAt(order.getUpdatedAt());

        // ---------------- ：归还与顺延 ----------------
        vo.setOrderType(OrderType.ofOrDefault(order.getOrderType()).name());
        vo.setOrderTypeLabel(OrderType.labelOf(order.getOrderType()));
        // ：自定义申请的申请类型（非自定义为空 → 前端据此显示「借用申请」）
        vo.setApplyTypeId(order.getApplyTypeId());
        vo.setApplyTypeName(OrderReferenceNames.mapGet(applyTypeNames, order.getApplyTypeId()));
        vo.setReturnTrigger(order.getReturnTrigger());
        vo.setReturnTriggerLabel(ReturnTrigger.labelOf(order.getReturnTrigger()));
        vo.setReturnNote(order.getReturnNote());
        vo.setReturnCondition(order.getReturnCondition());
        vo.setReturnConditionLabel(order.getReturnCondition() == null
                ? null : ReturnCondition.labelOf(order.getReturnCondition()));
        vo.setReturnRemark(order.getReturnRemark());
        vo.setReturnedById(order.getReturnedBy());
        vo.setReturnedByName(OrderReferenceNames.mapGet(userNames, order.getReturnedBy()));
        vo.setActualEndTime(order.getActualEndTime());
        vo.setBorrowTimeout(Boolean.TRUE.equals(order.getBorrowTimeout()));
        vo.setAutoExtendCount(order.getAutoExtendCount() == null ? 0 : order.getAutoExtendCount());
        vo.setLastTimeoutAlertAt(order.getLastTimeoutAlertAt());

        boolean applicantSelf = Objects.equals(order.getApplicantId(), currentUserId);
        boolean handlerSelf = Objects.equals(order.getActualFinalHandlerId(), currentUserId);
        // 操作入口由服务端判定，前端只负责渲染 —— 避免权限规则散落到多个页面后漂移
        vo.setCanRequestReturn(applicantSelf && OrderStatus.BORROWED.name().equals(order.getStatus()));
        vo.setCanConfirmReturn(confirmable(order, handlerSelf, currentRole));

        // ：超管对「非终态」工单可强制操作（具体可执行哪几项由状态决定，前端按状态渲染）
        OrderStatus orderStatus = OrderStatus.of(order.getStatus());
        vo.setCanForceOperate(RoleCode.isSuperAdmin(currentRole)
                && orderStatus != null && !orderStatus.isTerminal());

        if (nodes != null) {
            Integer currentStep = OrderApprovalNodeSupport.currentStepOrder(nodes);
            vo.setCurrentStepOrder(currentStep);
            vo.setCurrentApproverName(currentApproverNames(nodes, currentStep, userNames));
            boolean actionable = currentStep != null && nodes.stream()
                    .anyMatch(node -> Objects.equals(node.getStepOrder(), currentStep)
                            && Objects.equals(node.getApproverId(), currentUserId)
                            && ApprovalNodeStatus.PENDING.name().equals(node.getStatus()));
            vo.setActionable(actionable);
            // ：当前待审批步骤是否已超过约定审批时限（列表与详情的红色「已超时」标记）。
            // 取该步骤所有 PENDING 行中最早的 deadline —— 会签/或签下多人共享同一截止时间，
            // 取 min 保证「只要有一行为超时，整步就算超时」，与提醒任务的口径一致。
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime deadline = currentStep == null ? null : nodes.stream()
                    .filter(node -> Objects.equals(node.getStepOrder(), currentStep))
                    .filter(node -> ApprovalNodeStatus.PENDING.name().equals(node.getStatus()))
                    .map(OrderApprovalNode::getDeadlineAt)
                    .filter(Objects::nonNull)
                    .min(Comparator.naturalOrder())
                    .orElse(null);
            boolean approvalOverdue = deadline != null && now.isAfter(deadline);
            vo.setApprovalOverdue(approvalOverdue);
            vo.setApprovalOverdueHours(
                    approvalOverdue ? Duration.between(deadline, now).toHours() : null);
        }
    }

    /**
     * 是否可确认收回（ +  + ）
     *
     * <p>与 {@code confirmReturn} 的校验条件保持同一套判定，避免「按钮可点但接口拒绝」的错位：
     * <ul>
     *   <li>工单处于「待收回」→ 实际执行人可收回；</li>
     *   <li>工单「使用中」且已超时 → 实际执行人可直接收回（无需申请人先发起归还）；</li>
     *   <li>super_admin 在以上两种情形下同样可代为收回（执行人离职时的兜底）。</li>
     * </ul>
     */
    private boolean confirmable(Order order, boolean handlerSelf, String currentRole) {
        if (!handlerSelf && !RoleCode.isSuperAdmin(currentRole)) {
            return false;
        }
        OrderStatus status = OrderStatus.of(order.getStatus());
        if (status == OrderStatus.PENDING_RETURN) {
            return true;
        }
        return status == OrderStatus.BORROWED && Boolean.TRUE.equals(order.getBorrowTimeout());
    }

    /** 当前待办步骤（若有）的审批人姓名，多审批人以「、」连接；无待办返回 null */
    private String currentApproverNames(List<OrderApprovalNode> nodes, Integer currentStep,
                                        Map<Long, String> userNames) {
        if (currentStep == null) {
            return null;
        }
        String names = nodes.stream()
                .filter(node -> Objects.equals(node.getStepOrder(), currentStep))
                .filter(node -> ApprovalNodeStatus.PENDING.name().equals(node.getStatus()))
                .map(node -> userNames.get(node.getApproverId()))
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.joining("、"));
        return names.isEmpty() ? null : names;
    }

    /**
     * 审批节点视图装配。
     *
     * <p> 新增三类字段，均由<b>服务端</b>算好后下发：
     * <ul>
     *   <li><b>时限</b>：{@code deadlineAt} + {@code overdue}/{@code overdueHours} ——
     *       「是否超时」是业务判定，不能让前端拿本地时钟与 {@code deadlineAt} 自行比较
     *       （客户端时钟可能不准，且判定口径会分裂成两套）；</li>
     *   <li><b>待指派</b>：{@code pendingAssign} = PENDING 且无审批人（PREV_ASSIGN 占位行）；</li>
     *   <li><b>由上一节点指定</b>：{@code assignedByPrev}/{@code assignedByName} ——
     *       靠冻结流程快照里该节点是否用了 PREV_ASSIGN 规则来反查，指定者取「严格前一个已通过步骤」的审批人。</li>
     * </ul>
     */
    private ApprovalNodeView toNodeView(OrderApprovalNode node, Map<Long, String> userNames,
                                        List<OrderApprovalNode> allNodes,
                                        Map<String, ApproverRule> prevAssignRules,
                                        LocalDateTime now) {
        ApprovalNodeView view = new ApprovalNodeView();
        view.setId(node.getId());
        view.setStepOrder(node.getStepOrder());
        view.setApproverId(node.getApproverId());
        // 走空值安全的 mapGet：FLOW 模式下「条件未命中」的节点 approver_id 为 NULL，
        // 而 userNames 在「这批工单没有任何审批人」时是 Map.of() → 直接 .get(null) 会抛 NPE
        // （ 已经在 fill() 里栽过一次的坑，这里复用同一个 helper 而不是再写一遍判空）
        view.setApproverName(OrderReferenceNames.mapGet(userNames, node.getApproverId()));
        view.setNodeKey(node.getNodeKey());
        view.setNodeName(node.getNodeName());
        view.setNodeType(node.getNodeType());
        view.setNodeTypeLabel(nodeTypeLabel(node.getNodeType()));
        view.setConditionDesc(node.getConditionDesc());
        view.setSignType(node.getSignType());
        view.setSignTypeLabel(SignType.ALL_SIGN.equals(node.getSignType()) ? "会签" : "或签");
        view.setStatus(node.getStatus());
        view.setStatusLabel(ApprovalNodeStatus.labelOf(node.getStatus()));
        view.setActionTime(node.getActionTime());
        view.setActionComment(node.getActionComment());
        view.setSuperBackup(Boolean.TRUE.equals(node.getSuperBackup()));
        view.setFallback(Boolean.TRUE.equals(node.getFallback()));

        // ----  ----
        view.setDeadlineAt(node.getDeadlineAt());
        boolean pending = ApprovalNodeStatus.PENDING.name().equals(node.getStatus());
        boolean overdue = pending && node.getDeadlineAt() != null && now.isAfter(node.getDeadlineAt());
        view.setOverdue(overdue);
        view.setOverdueHours(overdue ? Duration.between(node.getDeadlineAt(), now).toHours() : null);
        boolean pendingAssign = pending && node.getApproverId() == null;
        view.setPendingAssign(pendingAssign);
        boolean assignedByPrev = !pendingAssign && node.getApproverId() != null
                && node.getNodeKey() != null && prevAssignRules.containsKey(node.getNodeKey());
        view.setAssignedByPrev(assignedByPrev);
        view.setAssignedByName(assignedByPrev ? assignerNamesFor(node, allNodes, userNames) : null);
        // ：待指派节点的「可选范围」下发给前端，让选择器的候选与服务端肯收的人一致
        ApproverRule pendingRule = pendingAssign && node.getNodeKey() != null
                ? prevAssignRules.get(node.getNodeKey()) : null;
        if (pendingRule != null) {
            ApproverRuleType.AssignScope scope = ApproverRuleType.AssignScope.of(pendingRule.getAssignScope());
            view.setAssignScope(scope == null ? null : scope.name());
            view.setAssignScopeLabel(scope == null ? null : scope.getLabel());
        }

        // ---- ：激活史 ----
        // 注意 activatedAt 在「提交即物化」的节点上同样有值，故它只是时间线，不是"未激活过"的判据；
        // 前端据 runtimeReason / status 判定展示（INACTIVE 默认隐藏，管理角色可展开看完整骨架）。
        view.setActivatedAt(node.getActivatedAt());
        view.setRuntimeReason(node.getRuntimeReason());
        return view;
    }

    /** 节点类型展示名；借用单 / GROUP 自定义单的 {@code node_type} 为 NULL → 无标签 */
    private String nodeTypeLabel(String nodeType) {
        FlowNodeType type = FlowNodeType.of(nodeType);
        return type == null ? null : type.getLabel();
    }

    /**
     * 冻结流程快照里使用 {@code PREV_ASSIGN} 的节点 key 集合。
     *
     * <p>详情页要显示「下一节点审批人由上一节点 XXX 指定」，但库里只存了被指定人
     * （{@code approver_id}），并不记录「这个人是被指定的」。与其为此加一列（会多一个
     * 与快照可能不一致的事实源），不如从**已冻结的流程 JSON**反查 —— 快照本身就是
     * 「这张单当时按什么流程走」的唯一事实源。
     *
     * <p>快照损坏时<b>降级不报错</b>：详情页不能因为一个展示性标记而整体 500。
     */
    private Map<String, ApproverRule> prevAssignRules(Order order) {
        String json = order.getApprovalFlowJson();
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            Map<String, ApproverRule> rules = new LinkedHashMap<>();
            for (FlowNode node : FlowDefinitionCodec.read(json).getNodes()) {
                if (node == null || node.getKey() == null) {
                    continue;
                }
                for (ApproverRule rule : node.getApproverRules()) {
                    if (rule != null
                            && ApproverRuleType.PREV_ASSIGN == ApproverRuleType.of(rule.getType())) {
                        rules.put(node.getKey(), rule);
                        break;
                    }
                }
            }
            return rules;
        } catch (RuntimeException e) {
            log.warn("工单 {} 的流程快照解析失败，详情中「由上一节点指定」标记将缺失：{}",
                    order.getOrderNo(), e.getMessage());
            return Map.of();
        }
    }

    /**
     * 某节点的「指定者」展示名：取<b>严格早于</b>该节点、且已通过的最大步骤上的审批人。
     *
     * <p>用「最大已通过步骤」而非「上一行」来定位：PREV_ASSIGN 与真正的指派者之间
     * 可能夹着抄送节点，直接按数组相邻取会取到抄送人（他甚至不是审批人）。
     */
    private String assignerNamesFor(OrderApprovalNode node, List<OrderApprovalNode> allNodes,
                                    Map<Long, String> userNames) {
        Integer target = node.getStepOrder();
        if (target == null || allNodes == null) {
            return null;
        }
        int prevStep = allNodes.stream()
                .filter(item -> ApprovalNodeStatus.APPROVED.name().equals(item.getStatus()))
                .filter(item -> item.getStepOrder() != null && item.getStepOrder() < target)
                .mapToInt(OrderApprovalNode::getStepOrder)
                .max()
                .orElse(-1);
        if (prevStep < 0) {
            return null;
        }
        String names = allNodes.stream()
                .filter(item -> Objects.equals(item.getStepOrder(), prevStep))
                .filter(item -> ApprovalNodeStatus.APPROVED.name().equals(item.getStatus()))
                .map(item -> OrderReferenceNames.mapGet(userNames, item.getApproverId()))
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.joining("、"));
        return names.isEmpty() ? null : names;
    }

    /** 一次查出这一页工单的全部快照节点，按 orderId 分组 */
    private Map<Long, List<OrderApprovalNode>> nodesOf(Collection<Order> orders) {
        Set<Long> orderIds = orders.stream().map(Order::getId).filter(Objects::nonNull).collect(Collectors.toSet());
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        return nodeMapper.selectList(Wrappers.<OrderApprovalNode>lambdaQuery()
                        .in(OrderApprovalNode::getOrderId, orderIds)
                        .orderByAsc(OrderApprovalNode::getStepOrder)
                        .orderByAsc(OrderApprovalNode::getId))
                .stream()
                .collect(Collectors.groupingBy(OrderApprovalNode::getOrderId,
                        LinkedHashMap::new, Collectors.toList()));
    }

    /** 工单涉及的申请人 + 实际执行人 + 收回人 user_id（三者都要显示姓名） */
    private Set<Long> applicantAndHandlerIds(Collection<Order> orders) {
        Set<Long> ids = new LinkedHashSet<>();
        for (Order order : orders) {
            if (order.getApplicantId() != null) {
                ids.add(order.getApplicantId());
            }
            if (order.getActualFinalHandlerId() != null) {
                ids.add(order.getActualFinalHandlerId());
            }
            if (order.getReturnedBy() != null) {
                ids.add(order.getReturnedBy());
            }
        }
        return ids;
    }
}
