package com.enterprise.ticket.module.order.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.ApprovalNodeStatus;
import com.enterprise.ticket.common.constant.DeviceStatus;
import com.enterprise.ticket.common.constant.ForceOperationType;
import com.enterprise.ticket.common.constant.MessageType;
import com.enterprise.ticket.common.constant.OrderStatus;
import com.enterprise.ticket.common.constant.RoleCode;
import com.enterprise.ticket.common.constant.TransferType;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.util.SecurityUtils;
import com.enterprise.ticket.module.device.entity.Device;
import com.enterprise.ticket.module.device.mapper.DeviceMapper;
import com.enterprise.ticket.module.message.entity.Message;
import com.enterprise.ticket.module.message.mapper.MessageMapper;
import com.enterprise.ticket.module.message.service.MessageService;
import com.enterprise.ticket.module.order.dto.OrderForceRequest;
import com.enterprise.ticket.module.order.dto.vo.OrderForceOperationVO;
import com.enterprise.ticket.module.order.entity.Order;
import com.enterprise.ticket.module.order.entity.OrderApprovalNode;
import com.enterprise.ticket.module.order.entity.OrderForceOperation;
import com.enterprise.ticket.module.order.entity.OrderHandlerTransfer;
import com.enterprise.ticket.module.order.mapper.OrderApprovalNodeMapper;
import com.enterprise.ticket.module.order.mapper.OrderForceOperationMapper;
import com.enterprise.ticket.module.order.mapper.OrderHandlerTransferMapper;
import com.enterprise.ticket.module.order.mapper.OrderMapper;
import com.enterprise.ticket.module.order.service.FlowActivationService;
import com.enterprise.ticket.module.order.service.OrderForceOperationService;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 超管强制干预实现（）
 *
 * <p><b>并发与一致性</b>：每一次状态推进都带前置条件 ——
 * 工单更新带「原状态」，执行人改派带「原执行人」，审批改派带「节点仍 PENDING」。
 * 命中 0 行即抛错让整笔事务回滚，绝不留下「强制记录写了、工单却没动」的错乱。
 *
 * <p><b>设备释放口径</b>（ / ）：
 * <pre>
 *   强制驳回   —— 审批中工单，设备处于 LOCKED / IN_APPROVAL  → AVAILABLE
 *   强制终止   —— 任何非终态，设备可能处于 LOCKED / IN_APPROVAL / IN_USE → AVAILABLE
 * </pre>
 * 统一走 {@link #releaseDevice} 的「三态命中式」条件 UPDATE，不区分具体来源状态。
 *
 * <p><b>跨模块依赖</b>：按项目约定只依赖其它模块的 Mapper（{@code DeviceMapper} /
 * {@code MessageMapper}）与消息模块的 {@code MessageService}（它不反向依赖工单服务，无环）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderForceOperationServiceImpl implements OrderForceOperationService {

    /** 原执行人待办消息标记前缀（与 {@code OrderTransferServiceImpl} 保持一致） */
    private static final String TRANSFERRED_MARK = "【工单已转交】";

    /** 转交历史里标注「本次为强制转交」的分隔前缀 */
    private static final String FORCE_COMMENT_PREFIX = "[强制干预] ";

    private final OrderMapper orderMapper;
    private final OrderApprovalNodeMapper nodeMapper;
    private final OrderForceOperationMapper forceMapper;
    private final OrderHandlerTransferMapper transferMapper;
    private final DeviceMapper deviceMapper;
    private final UserMapper userMapper;
    private final MessageMapper messageMapper;
    private final MessageService messageService;

    /**
     * 流程节点激活服务：仅用于「作废未完成节点」这一个动作。
     *
     * <p>方向单向（{@code FlowActivationService} 只依赖 order 的 Mapper 与配置服务，
     * 不依赖本服务），不构成循环依赖。
     */
    private final FlowActivationService flowActivationService;

    // ------------------------------------------------------------------
    // 入口
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void force(Long orderId, OrderForceRequest request) {
        Long operatorId = requireCurrentUserId();
        if (!RoleCode.isSuperAdmin(currentRole())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅超级管理员可执行强制操作");
        }
        ForceOperationType type = ForceOperationType.of(request == null ? null : request.getOperationType());
        if (type == null) {
            throw new BusinessException(ErrorCode.FORCE_OPERATION_INVALID);
        }
        String reason = trimToNull(request.getReason());
        if (reason == null) {
            throw new BusinessException(ErrorCode.FORCE_REASON_REQUIRED);
        }
        Order order = requireOrder(orderId);
        OrderStatus status = OrderStatus.of(order.getStatus());
        if (!type.supports(status)) {
            throw new BusinessException(ErrorCode.FORCE_OPERATION_NOT_SUPPORTED,
                    "「" + type.getLabel() + "」不支持当前状态「" + OrderStatus.labelOf(order.getStatus()) + "」");
        }

        LocalDateTime now = LocalDateTime.now();
        switch (type) {
            case FORCE_REJECT ->
                    forceReject(order, operatorId, reason, now);
            case FORCE_TERMINATE ->
                    forceTerminate(order, operatorId, reason, now);
            case FORCE_TRANSFER_APPROVAL ->
                    forceTransferApproval(order, operatorId, reason, request.getTargetApproverId(), now);
            case FORCE_TRANSFER_HANDLER ->
                    forceTransferHandler(order, operatorId, reason, request.getTargetHandlerId(), now);
        }
    }

    // ------------------------------------------------------------------
    // 强制驳回
    // ------------------------------------------------------------------

    private void forceReject(Order order, Long operatorId, String reason, LocalDateTime now) {
        cancelPendingNodes(order.getId());
        int updated = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .eq(Order::getId, order.getId())
                .eq(Order::getStatus, OrderStatus.PENDING_APPROVAL.name())
                .set(Order::getStatus, OrderStatus.REJECTED.name())
                .set(Order::getActualFinalHandlerId, null));
        if (updated == 0) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_TRANSITION_INVALID, "工单状态已变化，请刷新后重试");
        }
        releaseDevice(order.getDeviceId());

        writeRecord(order.getId(), ForceOperationType.FORCE_REJECT, operatorId, reason,
                OrderStatus.PENDING_APPROVAL, OrderStatus.REJECTED,
                null, null, null, null, null, now);
        messageService.send(order.getApplicantId(), MessageType.FORCE_REJECT, "工单被强制驳回",
                "你的工单 %s 已被超级管理员强制驳回，设备已释放。原因：%s"
                        .formatted(order.getOrderNo(), reason), order.getId());
        log.warn("【强制干预】工单 {} 被超管 {} 强制驳回，设备 {} 已释放。原因：{}",
                order.getOrderNo(), operatorId, order.getDeviceId(), reason);
    }

    // ------------------------------------------------------------------
    // 强制终止
    // ------------------------------------------------------------------

    private void forceTerminate(Order order, Long operatorId, String reason, LocalDateTime now) {
        OrderStatus from = OrderStatus.of(order.getStatus());
        cancelPendingNodes(order.getId());
        int updated = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .eq(Order::getId, order.getId())
                .eq(Order::getStatus, order.getStatus())
                .set(Order::getStatus, OrderStatus.TERMINATED.name())
                .set(Order::getBorrowTimeout, false)
                .set(Order::getActualFinalHandlerId, null));
        if (updated == 0) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_TRANSITION_INVALID, "工单状态已变化，请刷新后重试");
        }
        releaseDevice(order.getDeviceId());

        writeRecord(order.getId(), ForceOperationType.FORCE_TERMINATE, operatorId, reason,
                from, OrderStatus.TERMINATED, null, null, null, null, null, now);

        String deviceLabel = deviceLabelOf(order.getDeviceId());
        messageService.send(order.getApplicantId(), MessageType.FORCE_TERMINATE, "工单被强制终止",
                "你的工单 %s 已被超级管理员强制终止，设备「%s」已释放。原因：%s"
                        .formatted(order.getOrderNo(), deviceLabel, reason), order.getId());
        Long handlerId = order.getActualFinalHandlerId();
        if (handlerId != null && !Objects.equals(handlerId, order.getApplicantId())) {
            messageService.send(handlerId, MessageType.FORCE_TERMINATE, "工单被强制终止",
                    "你名下的工单 %s 已被超级管理员强制终止，无需再处理。原因：%s"
                            .formatted(order.getOrderNo(), reason), order.getId());
        }
        log.warn("【强制干预】工单 {}（{}）被超管 {} 强制终止，设备 {} 已释放。原因：{}",
                order.getOrderNo(), OrderStatus.labelOf(from.name()), operatorId, order.getDeviceId(), reason);
    }

    // ------------------------------------------------------------------
    // 强制转交审批（改派当前待办节点的审批人）
    // ------------------------------------------------------------------

    private void forceTransferApproval(Order order, Long operatorId, String reason,
                                       Long targetApproverId, LocalDateTime now) {
        if (targetApproverId == null) {
            throw new BusinessException(ErrorCode.FORCE_TARGET_APPROVER_REQUIRED);
        }
        requireActiveUser(targetApproverId, ErrorCode.FORCE_TARGET_APPROVER_INVALID);
        if (Objects.equals(targetApproverId, order.getApplicantId())) {
            throw new BusinessException(ErrorCode.FORCE_TARGET_APPROVER_INVALID, "不能指定申请人本人为审批人");
        }

        List<OrderApprovalNode> nodes = listNodes(order.getId());
        Integer currentStep = currentStepOrder(nodes);
        if (currentStep == null) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_TRANSITION_INVALID, "该工单没有待处理的审批节点");
        }
        List<OrderApprovalNode> currentNodes = nodes.stream()
                .filter(node -> Objects.equals(node.getStepOrder(), currentStep))
                .filter(node -> ApprovalNodeStatus.PENDING.name().equals(node.getStatus()))
                .toList();
        Long oldApproverId = currentNodes.get(0).getApproverId();
        if (Objects.equals(oldApproverId, targetApproverId)) {
            throw new BusinessException(ErrorCode.FORCE_TARGET_APPROVER_INVALID,
                    "当前待办节点已由所选员工审批，无需改派");
        }

        // 写库收敛到 FlowActivationService：审核中的「改派」与「运行期重算」共用同一入口，
        // 避免第二条改节点的 SQL 与 recompute 的状态判定打架（例如漏带 INACTIVE 条件）。
        int updated = flowActivationService.reassignStepApprover(order.getId(), currentStep, targetApproverId);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_TRANSITION_INVALID, "审批节点已变化，请刷新后重试");
        }

        writeRecord(order.getId(), ForceOperationType.FORCE_TRANSFER_APPROVAL, operatorId, reason,
                OrderStatus.PENDING_APPROVAL, OrderStatus.PENDING_APPROVAL,
                currentNodes.get(0).getId(), oldApproverId, targetApproverId, null, null, now);

        String newName = displayNameOf(targetApproverId);
        String oldName = displayNameOf(oldApproverId);
        messageService.send(targetApproverId, MessageType.FORCE_TRANSFER_APPROVAL, "审批待办被强制改派",
                "工单 %s 的审批待办已由超级管理员改派给你，请及时处理。原因：%s"
                        .formatted(order.getOrderNo(), reason), order.getId());
        if (oldApproverId != null && !Objects.equals(oldApproverId, targetApproverId)) {
            messageService.send(oldApproverId, MessageType.FORCE_TRANSFER_APPROVAL, "审批待办已被改派",
                    "工单 %s 的审批待办已被超级管理员改派给 %s，你无需再处理。原因：%s"
                            .formatted(order.getOrderNo(), newName, reason), order.getId());
        }
        messageService.send(order.getApplicantId(), MessageType.FORCE_TRANSFER_APPROVAL, "工单审批人变更",
                "你的工单 %s 的当前审批人已由 %s 变更为 %s。原因：%s"
                        .formatted(order.getOrderNo(), oldName, newName, reason), order.getId());
        log.warn("【强制干预】工单 {} 第 {} 步审批人由 {} 改派为 {}，操作人 {}. 原因：{}",
                order.getOrderNo(), currentStep, oldApproverId, targetApproverId, operatorId, reason);
    }

    // ------------------------------------------------------------------
    // 强制转交执行人（可跨小组）
    // ------------------------------------------------------------------

    private void forceTransferHandler(Order order, Long operatorId, String reason,
                                      Long targetHandlerId, LocalDateTime now) {
        if (targetHandlerId == null) {
            throw new BusinessException(ErrorCode.FORCE_TARGET_HANDLER_REQUIRED);
        }
        requireActiveUser(targetHandlerId, ErrorCode.FORCE_TARGET_HANDLER_INVALID);
        Long oldHandlerId = order.getActualFinalHandlerId();
        if (Objects.equals(targetHandlerId, oldHandlerId)) {
            throw new BusinessException(ErrorCode.FORCE_TARGET_HANDLER_INVALID,
                    "该工单当前已由所选员工执行，无需转交");
        }
        if (Objects.equals(targetHandlerId, order.getApplicantId())) {
            throw new BusinessException(ErrorCode.FORCE_TARGET_HANDLER_INVALID, "不能把工单转交给申请人本人");
        }

        // 条件 UPDATE：带「原状态 + 原执行人」（原执行人为空时用 IS NULL 约束）保证原子性
        int updated = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .eq(Order::getId, order.getId())
                .eq(Order::getStatus, order.getStatus())
                .eq(oldHandlerId != null, Order::getActualFinalHandlerId, oldHandlerId)
                .isNull(oldHandlerId == null, Order::getActualFinalHandlerId)
                .set(Order::getActualFinalHandlerId, targetHandlerId));
        if (updated == 0) {
            throw new BusinessException(ErrorCode.TRANSFER_CONFLICT);
        }

        // 转交历史（与人工转交同表，来源标 FORCE_ADMIN）
        writeTransferRecord(order.getId(), oldHandlerId, targetHandlerId, operatorId,
                FORCE_COMMENT_PREFIX + reason, TransferType.FORCE_ADMIN, now);
        markOldHandlerTodo(order.getId(), oldHandlerId, now);

        writeRecord(order.getId(), ForceOperationType.FORCE_TRANSFER_HANDLER, operatorId, reason,
                OrderStatus.of(order.getStatus()), OrderStatus.of(order.getStatus()),
                null, null, null, oldHandlerId, targetHandlerId, now);

        String newName = displayNameOf(targetHandlerId);
        messageService.send(targetHandlerId, MessageType.FORCE_TRANSFER_HANDLER, "工单被强制转交",
                "工单 %s 已被超级管理员强制转交给你，请及时处理。原因：%s"
                        .formatted(order.getOrderNo(), reason), order.getId());
        if (oldHandlerId != null) {
            messageService.send(oldHandlerId, MessageType.FORCE_TRANSFER_HANDLER, "工单已被强制转交",
                    "你名下的工单 %s 已被超级管理员强制转交给 %s，该工单的后续待办已移交。原因：%s"
                            .formatted(order.getOrderNo(), newName, reason), order.getId());
        }
        messageService.send(order.getApplicantId(), MessageType.FORCE_TRANSFER_HANDLER, "工单执行人变更",
                "你的工单 %s 的执行人已变更。原因：%s".formatted(order.getOrderNo(), reason), order.getId());
        log.warn("【强制干预】工单 {} 执行人由 {} 强制转交给 {}，操作人 {}. 原因：{}",
                order.getOrderNo(), oldHandlerId, targetHandlerId, operatorId, reason);
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    @Override
    public List<OrderForceOperationVO> listByOrder(Long orderId) {
        Long currentUserId = requireCurrentUserId();
        Order order = requireOrder(orderId);
        assertCanView(order, currentUserId);
        List<OrderForceOperation> rows = forceMapper.selectList(Wrappers.<OrderForceOperation>lambdaQuery()
                .eq(OrderForceOperation::getOrderId, orderId)
                .orderByAsc(OrderForceOperation::getId));
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<Long> userIds = new LinkedHashSet<>();
        rows.forEach(row -> {
            userIds.add(row.getOperatorId());
            userIds.add(row.getOldApproverId());
            userIds.add(row.getNewApproverId());
            userIds.add(row.getOldHandlerId());
            userIds.add(row.getNewHandlerId());
        });
        Map<Long, String> names = userNameMap(userIds);
        return rows.stream().map(row -> toVO(row, names)).toList();
    }

    // ------------------------------------------------------------------
    // 内部方法
    // ------------------------------------------------------------------

    /**
     * 作废全部仍待处理的审批节点（强制驳回 / 强制终止时调用，）。
     *
     * <p>M2 起委托给 {@link FlowActivationService#cancelOpenNodes}：它是节点状态变更的
     * <b>单一写库入口</b>，会把 {@code INACTIVE}（运行期"尚未判定"）一并作废。
     * 超管干预必须走同一个入口 —— 否则会出现"超管把节点作废了，但运行期重算仍基于
     * 半旧的状态做决策"这类不一致，而这类不一致只在运行期流程上暴露，
     * 排查时极难联想到"两条改节点的代码路径"。开关关闭时两条 SQL 等价。
     */
    private void cancelPendingNodes(Long orderId) {
        flowActivationService.cancelOpenNodes(orderId, null);
    }

    /**
     * 释放设备：把处于「锁定 / 审批中 / 使用中」的设备退回可用并清空临时锁三件套。
     *
     * <p>三态一次命中：强制终止可能发生在审批中（LOCKED/IN_APPROVAL）或使用中（IN_USE），
     * 用 {@code IN (...)} 覆盖，调用方无需按工单状态分支。
     */
    private void releaseDevice(Long deviceId) {
        int updated = deviceMapper.update(null, Wrappers.<Device>lambdaUpdate()
                .eq(Device::getId, deviceId)
                .in(Device::getStatus, List.of(DeviceStatus.LOCKED.name(),
                        DeviceStatus.IN_APPROVAL.name(), DeviceStatus.IN_USE.name()))
                .set(Device::getStatus, DeviceStatus.AVAILABLE.name())
                .set(Device::getLockedBy, null)
                .set(Device::getLockedAt, null)
                .set(Device::getLockToken, null));
        if (updated == 0) {
            // 设备可能已被其它流程处理（如已报废/维修中）：不阻断强制干预，仅告警
            log.warn("【强制干预】释放设备 {} 时未命中可释放状态（可能已被其它流程处理）", deviceId);
        }
    }

    private void writeRecord(Long orderId, ForceOperationType type, Long operatorId, String reason,
                             OrderStatus oldStatus, OrderStatus newStatus,
                             Long nodeId, Long oldApproverId, Long newApproverId,
                             Long oldHandlerId, Long newHandlerId, LocalDateTime now) {
        OrderForceOperation record = new OrderForceOperation();
        record.setOrderId(orderId);
        record.setOperationType(type.name());
        record.setOperatorId(operatorId);
        record.setReason(reason);
        record.setOldStatus(oldStatus == null ? null : oldStatus.name());
        record.setNewStatus(newStatus == null ? null : newStatus.name());
        record.setNodeId(nodeId);
        record.setOldApproverId(oldApproverId);
        record.setNewApproverId(newApproverId);
        record.setOldHandlerId(oldHandlerId);
        record.setNewHandlerId(newHandlerId);
        record.setCreatedAt(now);
        forceMapper.insert(record);
    }

    private void writeTransferRecord(Long orderId, Long oldHandlerId, Long newHandlerId,
                                     Long operatorId, String comment, TransferType type, LocalDateTime now) {
        OrderHandlerTransfer record = new OrderHandlerTransfer();
        record.setOrderId(orderId);
        record.setOldHandlerId(oldHandlerId);
        record.setNewHandlerId(newHandlerId);
        record.setTransferOperatorId(operatorId);
        record.setTransferComment(comment);
        record.setTransferType(type.name());
        record.setCreatedAt(now);
        transferMapper.insert(record);
    }

    /** 原执行人的该工单待办消息：置为已读并打上「工单已转交」前缀 */
    private void markOldHandlerTodo(Long orderId, Long oldHandlerId, LocalDateTime now) {
        if (oldHandlerId == null) {
            return;
        }
        messageMapper.update(null, Wrappers.<Message>lambdaUpdate()
                .eq(Message::getUserId, oldHandlerId)
                .eq(Message::getOrderId, orderId)
                .eq(Message::getIsRead, false)
                .notLikeRight(Message::getTitle, TRANSFERRED_MARK)
                .set(Message::getIsRead, true)
                .set(Message::getReadAt, now)
                .setSql("title = CONCAT('" + TRANSFERRED_MARK + "', title)"));
    }

    private OrderForceOperationVO toVO(OrderForceOperation row, Map<Long, String> names) {
        OrderForceOperationVO vo = new OrderForceOperationVO();
        vo.setId(row.getId());
        vo.setOrderId(row.getOrderId());
        vo.setOperationType(row.getOperationType());
        vo.setOperationTypeLabel(ForceOperationType.labelOf(row.getOperationType()));
        vo.setOperatorId(row.getOperatorId());
        vo.setOperatorName(names.get(row.getOperatorId()));
        vo.setReason(row.getReason());
        vo.setOldStatus(row.getOldStatus());
        vo.setOldStatusLabel(OrderStatus.labelOf(row.getOldStatus()));
        vo.setNewStatus(row.getNewStatus());
        vo.setNewStatusLabel(OrderStatus.labelOf(row.getNewStatus()));
        vo.setOldApproverId(row.getOldApproverId());
        vo.setOldApproverName(names.get(row.getOldApproverId()));
        vo.setNewApproverId(row.getNewApproverId());
        vo.setNewApproverName(names.get(row.getNewApproverId()));
        vo.setOldHandlerId(row.getOldHandlerId());
        vo.setOldHandlerName(names.get(row.getOldHandlerId()));
        vo.setNewHandlerId(row.getNewHandlerId());
        vo.setNewHandlerName(names.get(row.getNewHandlerId()));
        vo.setCreatedAt(row.getCreatedAt());
        return vo;
    }

    private List<OrderApprovalNode> listNodes(Long orderId) {
        return nodeMapper.selectList(Wrappers.<OrderApprovalNode>lambdaQuery()
                .eq(OrderApprovalNode::getOrderId, orderId)
                .orderByAsc(OrderApprovalNode::getStepOrder)
                .orderByAsc(OrderApprovalNode::getId));
    }

    /** 当前待办步骤 = 最小的「仍存在 PENDING 节点」的 stepOrder；无待办返回 null */
    private Integer currentStepOrder(List<OrderApprovalNode> nodes) {
        return nodes.stream()
                .filter(node -> ApprovalNodeStatus.PENDING.name().equals(node.getStatus()))
                .map(OrderApprovalNode::getStepOrder)
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    /**
     * 工单可见性复核：申请人 / 当前执行人 / 审批人 / admin 以上可见。
     *
     * <p>与 {@code OrderServiceImpl#assertCanView} 同口径；此处独立实现是为了让
     * 「时间线」接口在脱离详情页被调用时也保持安全边界。
     */
    private void assertCanView(Order order, Long currentUserId) {
        if (RoleCode.isAdminOrAbove(currentRole())
                || Objects.equals(order.getApplicantId(), currentUserId)
                || Objects.equals(order.getActualFinalHandlerId(), currentUserId)) {
            return;
        }
        boolean isApprover = nodeMapper.selectCount(Wrappers.<OrderApprovalNode>lambdaQuery()
                .eq(OrderApprovalNode::getOrderId, order.getId())
                .eq(OrderApprovalNode::getApproverId, currentUserId)) > 0;
        if (!isApprover) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权查看该工单的强制操作记录");
        }
    }

    private User requireActiveUser(Long userId, ErrorCode errorCode) {
        User user = userId == null ? null : userMapper.selectById(userId);
        if (user == null || !Boolean.TRUE.equals(user.getEnabled()) || Boolean.TRUE.equals(user.getDimission())) {
            throw new BusinessException(errorCode);
        }
        return user;
    }

    private Order requireOrder(Long orderId) {
        Order order = orderId == null ? null : orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }
        return order;
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

    private String displayNameOf(Long userId) {
        if (userId == null) {
            return "（无）";
        }
        return userNameMap(List.of(userId)).getOrDefault(userId, "用户#" + userId);
    }

    private String deviceLabelOf(Long deviceId) {
        Device device = deviceId == null ? null : deviceMapper.selectById(deviceId);
        if (device == null) {
            return "设备#" + deviceId;
        }
        return device.getDeviceName() + "（" + device.getAssetNo() + "）";
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

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
