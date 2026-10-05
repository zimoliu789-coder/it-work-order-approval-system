package com.enterprise.ticket.module.order.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.ApprovalNodeStatus;
import com.enterprise.ticket.common.constant.MessageType;
import com.enterprise.ticket.common.constant.OrderStatus;
import com.enterprise.ticket.common.constant.RoleCode;
import com.enterprise.ticket.common.constant.UrgeType;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.util.SecurityUtils;
import com.enterprise.ticket.module.message.service.MessageService;
import com.enterprise.ticket.module.order.dto.vo.OrderUrgeVO;
import com.enterprise.ticket.module.order.entity.Order;
import com.enterprise.ticket.module.order.entity.OrderApprovalNode;
import com.enterprise.ticket.module.order.entity.OrderUrge;
import com.enterprise.ticket.module.order.mapper.OrderApprovalNodeMapper;
import com.enterprise.ticket.module.order.mapper.OrderMapper;
import com.enterprise.ticket.module.order.mapper.OrderUrgeMapper;
import com.enterprise.ticket.module.order.service.OrderUrgeService;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工单催办服务实现
 *
 * <p><b>为什么把审批节点纳入冷却键</b>：需求是「同一工单同一审批节点 1 小时内只能催 1 次」。
 * 若只按工单冷却，多级审批时申请人催完第 1 步、审批人当天通过，第 2 步审批人却在冷却期内
 * 收不到任何提醒 —— 与「催当前审批人」的业务意图相悖。按节点冷却后，流程每推进一步
 * 申请人就重新获得一次催办机会。
 *
 * <p><b>会签场景</b>：当前步骤若是会签（多个 PENDING 节点），催办会给该步骤<b>全部</b>
 * 待办审批人各发一条消息、各写一行催办记录（时间线因此能显示「已催办 2 人」）。
 *
 * <p><b>事务边界</b>：催办是「写记录 + 发消息」，消息发送本身 best-effort（见
 * {@code MessageService}），因此整笔用 {@code REQUIRED} 事务即可；消息失败不会回滚业务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderUrgeServiceImpl implements OrderUrgeService {

    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final OrderMapper orderMapper;
    private final OrderApprovalNodeMapper orderNodeMapper;
    private final OrderUrgeMapper urgeMapper;
    private final UserMapper userMapper;
    private final MessageService messageService;
    private final SystemConfigService systemConfigService;

    // ------------------------------------------------------------------
    // 审批催办
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void urgeApproval(Long orderId) {
        Long currentUserId = requireCurrentUserId();
        Order order = requireOrder(orderId);

        // 需求方  ：申请人对「审批中」的工单催办
        if (!Objects.equals(order.getApplicantId(), currentUserId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有工单申请人可以催办审批");
        }
        if (!OrderStatus.PENDING_APPROVAL.name().equals(order.getStatus())) {
            throw new BusinessException(ErrorCode.URGE_NOT_ALLOWED,
                    "工单当前状态为「" + OrderStatus.labelOf(order.getStatus()) + "」，仅「审批中」可以催办审批");
        }

        List<OrderApprovalNode> pending = pendingNodesOfCurrentStep(orderId);
        if (pending.isEmpty()) {
            throw new BusinessException(ErrorCode.URGE_TARGET_MISSING, "当前没有待处理的审批节点，无法催办");
        }
        List<Long> nodeIds = pending.stream().map(OrderApprovalNode::getId).toList();
        List<Long> approverIds = pending.stream()
                .map(OrderApprovalNode::getApproverId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (approverIds.isEmpty()) {
            throw new BusinessException(ErrorCode.URGE_TARGET_MISSING, "当前审批步骤没有可催办的审批人");
        }

        LocalDateTime now = LocalDateTime.now();
        assertCooldownFree(orderId, UrgeType.APPROVAL, nodeIds, now);

        Integer stepOrder = pending.get(0).getStepOrder();
        for (OrderApprovalNode node : pending) {
            if (node.getApproverId() == null) {
                continue;
            }
            insertUrge(orderId, UrgeType.APPROVAL, node.getId(), node.getApproverId(), currentUserId, now);
        }
        String deviceLabel = deviceLabelOf(order.getDeviceId());
        messageService.send(approverIds, MessageType.URGE_APPROVAL, "审批催办提醒",
                "工单 %s（%s）已在您处等待审批，申请人提醒您尽快处理。"
                        .formatted(order.getOrderNo(), deviceLabel),
                order.getId());
        log.info("工单 {} 第 {} 步审批被申请人 {} 催办，目标审批人 {}",
                order.getOrderNo(), stepOrder, currentUserId, approverIds);
    }

    // ------------------------------------------------------------------
    // 归还催办
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void urgeReturn(Long orderId) {
        Long currentUserId = requireCurrentUserId();
        boolean superAdmin = RoleCode.isSuperAdmin(currentRole());
        Order order = requireOrder(orderId);

        // 需求方  ：实际执行人对「使用中」且已到期 / 超时的工单催还
        if (!Objects.equals(order.getActualFinalHandlerId(), currentUserId) && !superAdmin) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有该工单的实际执行人可以发起催还");
        }
        if (!OrderStatus.BORROWED.name().equals(order.getStatus())) {
            throw new BusinessException(ErrorCode.URGE_NOT_ALLOWED,
                    "工单当前状态为「" + OrderStatus.labelOf(order.getStatus()) + "」，仅「使用中」可以催还");
        }
        LocalDateTime now = LocalDateTime.now();
        if (!isDueOrTimeout(order, now)) {
            throw new BusinessException(ErrorCode.URGE_NOT_ALLOWED, "该工单尚未到期，无需催还");
        }
        Long borrowerId = order.getApplicantId();
        if (borrowerId == null) {
            throw new BusinessException(ErrorCode.URGE_TARGET_MISSING, "该工单没有借用人，无法催还");
        }

        assertCooldownFree(orderId, UrgeType.RETURN, List.of(), now);

        insertUrge(orderId, UrgeType.RETURN, null, borrowerId, currentUserId, now);
        String due = order.getPlannedEndTime() == null
                ? "已超期"
                : "应归还时间 " + order.getPlannedEndTime().format(DATETIME_FMT);
        messageService.send(borrowerId, MessageType.URGE_RETURN, "归还催办提醒",
                "工单 %s（%s）%s，实际执行人提醒您尽快办理归还。"
                        .formatted(order.getOrderNo(), deviceLabelOf(order.getDeviceId()), due),
                order.getId());
        log.info("工单 {} 被实际执行人 {} 催还，目标借用人 {}", order.getOrderNo(), currentUserId, borrowerId);
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    @Override
    public List<OrderUrgeVO> listByOrder(Long orderId) {
        Long currentUserId = requireCurrentUserId();
        Order order = requireOrder(orderId);
        List<OrderUrge> rows = urgeMapper.selectList(Wrappers.<OrderUrge>lambdaQuery()
                .eq(OrderUrge::getOrderId, orderId)
                .orderByAsc(OrderUrge::getId));
        assertCanViewUrge(order, currentUserId, rows);
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<Long> userIds = new LinkedHashSet<>();
        rows.forEach(row -> {
            userIds.add(row.getTargetUserId());
            userIds.add(row.getOperatorId());
        });
        Map<Long, String> userNames = userNameMap(userIds);
        Map<Long, Integer> stepByNodeId = stepOrderByNodeId(orderId);
        return rows.stream().map(row -> toVO(row, order, userNames, stepByNodeId)).toList();
    }

    @Override
    public UrgeStat statOfOrder(Long orderId) {
        return statByOrders(List.of(orderId)).getOrDefault(orderId, UrgeStat.EMPTY);
    }

    @Override
    public Map<Long, UrgeStat> statByOrders(Collection<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = orderIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<OrderUrge> rows = urgeMapper.selectList(Wrappers.<OrderUrge>lambdaQuery()
                .select(OrderUrge::getOrderId, OrderUrge::getUrgeType,
                        OrderUrge::getNodeId, OrderUrge::getCreatedAt)
                .in(OrderUrge::getOrderId, ids));

        Map<Long, Map<Long, LocalDateTime>> approvalLastAt = new HashMap<>();
        Map<Long, LocalDateTime> returnLastAt = new HashMap<>();
        for (OrderUrge row : rows) {
            if (row.getCreatedAt() == null) {
                continue;
            }
            if (UrgeType.APPROVAL.name().equals(row.getUrgeType())) {
                approvalLastAt.computeIfAbsent(row.getOrderId(), key -> new HashMap<>())
                        .merge(row.getNodeId(), row.getCreatedAt(), (a, b) -> b.isAfter(a) ? b : a);
            } else if (UrgeType.RETURN.name().equals(row.getUrgeType())) {
                returnLastAt.merge(row.getOrderId(), row.getCreatedAt(), (a, b) -> b.isAfter(a) ? b : a);
            }
        }
        Map<Long, UrgeStat> result = new HashMap<>();
        for (Long id : ids) {
            result.put(id, new UrgeStat(
                    approvalLastAt.getOrDefault(id, Map.of()),
                    returnLastAt.get(id)));
        }
        return result;
    }

    @Override
    public int cooldownMinutes() {
        return systemConfigService.urgeCooldownMinutes();
    }

    // ------------------------------------------------------------------
    // 内部方法
    // ------------------------------------------------------------------

    /**
     * 冷却校验：存在「同工单 + 同类型 + 同节点」且发生在冷却窗口内的催办记录则拒绝。
     *
     * <p>{@code nodeIds} 为空表示该类型不分节点（归还催办）。
     */
    private void assertCooldownFree(Long orderId, UrgeType type, Collection<Long> nodeIds, LocalDateTime now) {
        int cooldown = cooldownMinutes();
        LocalDateTime threshold = now.minusMinutes(cooldown);

        var wrapper = Wrappers.<OrderUrge>lambdaQuery()
                .eq(OrderUrge::getOrderId, orderId)
                .eq(OrderUrge::getUrgeType, type.name())
                .ge(OrderUrge::getCreatedAt, threshold)
                .orderByDesc(OrderUrge::getCreatedAt)
                .orderByDesc(OrderUrge::getId)
                .last("LIMIT 1");
        if (nodeIds != null && !nodeIds.isEmpty()) {
            wrapper.in(OrderUrge::getNodeId, nodeIds);
        }
        List<OrderUrge> recent = urgeMapper.selectList(wrapper);
        if (recent.isEmpty() || recent.get(0).getCreatedAt() == null) {
            return;
        }
        LocalDateTime until = recent.get(0).getCreatedAt().plusMinutes(cooldown);
        long remainingSeconds = Math.max(Duration.between(now, until).getSeconds(), 0L);
        long minutes = Math.max((remainingSeconds + 59) / 60, 1L);
        throw new BusinessException(ErrorCode.URGE_COOLDOWN,
                "催办过于频繁，请在约 " + minutes + " 分钟后重试");
    }

    private void insertUrge(Long orderId, UrgeType type, Long nodeId, Long targetUserId,
                            Long operatorId, LocalDateTime now) {
        OrderUrge urge = new OrderUrge();
        urge.setOrderId(orderId);
        urge.setUrgeType(type.name());
        urge.setNodeId(nodeId);
        urge.setTargetUserId(targetUserId);
        urge.setOperatorId(operatorId);
        urge.setCreatedAt(now);
        urgeMapper.insert(urge);
    }

    /** 当前应处理的步骤上仍为 PENDING 的节点（多审批人时可能有多条） */
    private List<OrderApprovalNode> pendingNodesOfCurrentStep(Long orderId) {
        List<OrderApprovalNode> nodes = listNodes(orderId);
        Integer currentStep = nodes.stream()
                .filter(node -> ApprovalNodeStatus.PENDING.name().equals(node.getStatus()))
                .map(OrderApprovalNode::getStepOrder)
                .min(Integer::compareTo)
                .orElse(null);
        if (currentStep == null) {
            return List.of();
        }
        return nodes.stream()
                .filter(node -> Objects.equals(node.getStepOrder(), currentStep))
                .filter(node -> ApprovalNodeStatus.PENDING.name().equals(node.getStatus()))
                .toList();
    }

    private List<OrderApprovalNode> listNodes(Long orderId) {
        return orderNodeMapper.selectList(Wrappers.<OrderApprovalNode>lambdaQuery()
                .eq(OrderApprovalNode::getOrderId, orderId)
                .orderByAsc(OrderApprovalNode::getStepOrder)
                .orderByAsc(OrderApprovalNode::getId));
    }

    private Map<Long, Integer> stepOrderByNodeId(Long orderId) {
        return listNodes(orderId).stream()
                .collect(Collectors.toMap(OrderApprovalNode::getId, OrderApprovalNode::getStepOrder, (a, b) -> a));
    }

    /** 是否已到期或已超时（催还的适用条件） */
    private boolean isDueOrTimeout(Order order, LocalDateTime now) {
        if (Boolean.TRUE.equals(order.getBorrowTimeout())) {
            return true;
        }
        return order.getPlannedEndTime() != null && !order.getPlannedEndTime().isAfter(now);
    }

    private OrderUrgeVO toVO(OrderUrge row, Order order, Map<Long, String> userNames,
                             Map<Long, Integer> stepByNodeId) {
        OrderUrgeVO vo = new OrderUrgeVO();
        vo.setId(row.getId());
        vo.setOrderId(row.getOrderId());
        vo.setOrderNo(order == null ? null : order.getOrderNo());
        vo.setUrgeType(row.getUrgeType());
        vo.setUrgeTypeLabel(UrgeType.labelOf(row.getUrgeType()));
        vo.setNodeId(row.getNodeId());
        vo.setNodeStepOrder(row.getNodeId() == null ? null : stepByNodeId.get(row.getNodeId()));
        vo.setTargetUserId(row.getTargetUserId());
        vo.setTargetUserName(userNames.get(row.getTargetUserId()));
        vo.setOperatorId(row.getOperatorId());
        vo.setOperatorName(userNames.get(row.getOperatorId()));
        vo.setCreatedAt(row.getCreatedAt());
        return vo;
    }

    /**
     * 催办记录可见性：能看主单的人（申请人 / 当前执行人 / 管理员），
     * 或被催办过的人（审批人需要能看到「我被催办了」），或发起过催办的人。
     */
    private void assertCanViewUrge(Order order, Long currentUserId, List<OrderUrge> rows) {
        if (RoleCode.isAdminOrAbove(currentRole())
                || Objects.equals(order.getApplicantId(), currentUserId)
                || Objects.equals(order.getActualFinalHandlerId(), currentUserId)) {
            return;
        }
        // 被催办过的人（当前/历史审批人）需要能看到「我被催办了」
        boolean related = rows.stream().anyMatch(row ->
                Objects.equals(row.getTargetUserId(), currentUserId)
                        || Objects.equals(row.getOperatorId(), currentUserId));
        if (related) {
            return;
        }
        // 与「工单详情可见性」（OrderServiceImpl#assertCanView）保持一致：本工单审批人同样可见，
        // 否则详情页装配时间线时会被 403 打断
        boolean isApprover = orderNodeMapper.selectCount(Wrappers.<OrderApprovalNode>lambdaQuery()
                .eq(OrderApprovalNode::getOrderId, order.getId())
                .eq(OrderApprovalNode::getApproverId, currentUserId)) > 0;
        if (!isApprover) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权查看该工单的催办记录");
        }
    }

    private Map<Long, String> userNameMap(Collection<Long> userIds) {
        Collection<Long> ids = userIds == null ? List.of() : userIds.stream().filter(Objects::nonNull).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(User::getId,
                        user -> user.getDisplayName() == null ? user.getUsername() : user.getDisplayName(),
                        (a, b) -> a));
    }

    private String deviceLabelOf(Long deviceId) {
        return deviceId == null ? "设备未知" : "设备#" + deviceId;
    }

    private Order requireOrder(Long orderId) {
        Order order = orderId == null ? null : orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }
        return order;
    }

    private Long requireCurrentUserId() {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }

    private String currentRole() {
        var loginUser = SecurityUtils.getCurrentUser();
        return loginUser == null ? null : loginUser.getRole();
    }
}
