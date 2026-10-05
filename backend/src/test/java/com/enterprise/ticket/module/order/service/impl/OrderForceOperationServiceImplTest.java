package com.enterprise.ticket.module.order.service.impl;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.ApprovalNodeStatus;
import com.enterprise.ticket.common.constant.DeviceStatus;
import com.enterprise.ticket.common.constant.ForceOperationType;
import com.enterprise.ticket.common.constant.MessageType;
import com.enterprise.ticket.common.constant.OrderStatus;
import com.enterprise.ticket.common.constant.RoleCode;
import com.enterprise.ticket.common.constant.TransferType;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.device.entity.Device;
import com.enterprise.ticket.module.device.mapper.DeviceMapper;
import com.enterprise.ticket.module.message.entity.Message;
import com.enterprise.ticket.module.message.mapper.MessageMapper;
import com.enterprise.ticket.module.message.service.MessageService;
import com.enterprise.ticket.module.order.dto.OrderForceRequest;
import com.enterprise.ticket.module.order.entity.Order;
import com.enterprise.ticket.module.order.entity.OrderApprovalNode;
import com.enterprise.ticket.module.order.entity.OrderForceOperation;
import com.enterprise.ticket.module.order.entity.OrderHandlerTransfer;
import com.enterprise.ticket.module.order.mapper.OrderApprovalNodeMapper;
import com.enterprise.ticket.module.order.mapper.OrderForceOperationMapper;
import com.enterprise.ticket.module.order.mapper.OrderHandlerTransferMapper;
import com.enterprise.ticket.module.order.mapper.OrderMapper;
import com.enterprise.ticket.module.order.service.FlowActivationService;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import com.enterprise.ticket.security.LoginUser;
import com.enterprise.ticket.support.MyBatisLambdaCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 超管强制干预单元测试（需求三）
 *
 * <p>强制干预是本迭代破坏性最强的写操作（可单方面结束工单、改派审批人 / 执行人），
 * 因此测试锁定四条边界：
 * <ul>
 *   <li><b>权限</b>：非 super_admin 一律 FORBIDDEN（admin 也不例外）；</li>
 *   <li><b>参数</b>：类型非法 / 原因缺失 / 目标人缺失，分别返回明确错误码；</li>
 *   <li><b>状态</b>：每种操作只在其适用状态下可执行，否则 FORCE_OPERATION_NOT_SUPPORTED；</li>
 *   <li><b>原子性</b>：条件 UPDATE 命中 0 行 → 抛错，绝不写「强制记录与实际不符」的痕迹。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class OrderForceOperationServiceImplTest {

    private static final Long ORDER_ID = 100L;
    private static final Long DEVICE_ID = 11L;
    private static final Long APPLICANT_ID = 2L;
    private static final Long HANDLER_ID = 4L;
    private static final Long NEW_HANDLER_ID = 5L;
    private static final Long OLD_APPROVER_ID = 3L;
    private static final Long NEW_APPROVER_ID = 6L;
    private static final Long SUPER_ADMIN_ID = 1L;
    private static final Long NODE_ID = 50L;

    @BeforeAll
    static void initMyBatisLambdaCache() {
        MyBatisLambdaCache.init(Order.class, OrderApprovalNode.class, OrderForceOperation.class,
                OrderHandlerTransfer.class, Device.class, Message.class, User.class);
    }

    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderApprovalNodeMapper nodeMapper;
    @Mock
    private OrderForceOperationMapper forceMapper;
    @Mock
    private OrderHandlerTransferMapper transferMapper;
    @Mock
    private DeviceMapper deviceMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private MessageMapper messageMapper;
    @Mock
    private MessageService messageService;

    /**
     * Phase 16 Wave 2 · M2：节点状态的写库已收敛到 {@code FlowActivationService}
     * （作废未完成节点 / 改派当前待审节点），因此本测试改为验证「服务被正确调用」，
     * 而不是直接验证 service 内部发出的那条 SQL —— SQL 本身的正确性由其自身单测覆盖。
     */
    @Mock
    private FlowActivationService flowActivationService;

    @InjectMocks
    private OrderForceOperationServiceImpl service;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------
    // 辅助构造
    // ------------------------------------------------------------------

    private void loginAsSuperAdmin() {
        login(SUPER_ADMIN_ID, RoleCode.SUPER_ADMIN);
    }

    private void login(Long userId, String role) {
        User user = new User();
        user.setId(userId);
        user.setUsername("u" + userId);
        user.setDisplayName("用户" + userId);
        user.setRole(role);
        user.setEnabled(true);
        user.setDimission(false);
        LoginUser loginUser = new LoginUser(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
    }

    private Order order(String status, Long handlerId) {
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setOrderNo("BO-100");
        order.setDeviceId(DEVICE_ID);
        order.setApplicantId(APPLICANT_ID);
        order.setActualFinalHandlerId(handlerId);
        order.setStatus(status);
        return order;
    }

    private OrderApprovalNode node(Long id, int step, Long approverId, String status) {
        OrderApprovalNode node = new OrderApprovalNode();
        node.setId(id);
        node.setOrderId(ORDER_ID);
        node.setStepOrder(step);
        node.setApproverId(approverId);
        node.setStatus(status);
        return node;
    }

    private User user(Long id, boolean enabled, boolean dimission) {
        User user = new User();
        user.setId(id);
        user.setUsername("u" + id);
        user.setDisplayName("用户" + id);
        user.setEnabled(enabled);
        user.setDimission(dimission);
        return user;
    }

    private OrderForceRequest request(String type, String reason) {
        OrderForceRequest request = new OrderForceRequest();
        request.setOperationType(type);
        request.setReason(reason);
        return request;
    }

    private static ErrorCode errorCodeOf(Runnable runnable) {
        return assertThrows(BusinessException.class, runnable::run).getErrorCode();
    }

    private OrderForceOperation capturedForceRecord() {
        ArgumentCaptor<OrderForceOperation> captor = ArgumentCaptor.forClass(OrderForceOperation.class);
        verify(forceMapper).insert(captor.capture());
        return captor.getValue();
    }

    // ------------------------------------------------------------------
    // 权限 / 参数
    // ------------------------------------------------------------------

    @Test
    @DisplayName("权限：admin 执行强制操作 → FORBIDDEN（admin 只读，规范 §6）")
    void force_adminForbidden() {
        login(9L, RoleCode.ADMIN);
        assertEquals(ErrorCode.FORBIDDEN,
                errorCodeOf(() -> service.force(ORDER_ID, request(ForceOperationType.FORCE_TERMINATE.name(), "x"))));
        verify(forceMapper, never()).insert(any());
    }

    @Test
    @DisplayName("参数：操作类型非法 → FORCE_OPERATION_INVALID")
    void force_invalidType() {
        loginAsSuperAdmin();
        assertEquals(ErrorCode.FORCE_OPERATION_INVALID,
                errorCodeOf(() -> service.force(ORDER_ID, request("NOT_A_TYPE", "x"))));
    }

    @Test
    @DisplayName("参数：原因缺失（空白）→ FORCE_REASON_REQUIRED")
    void force_reasonRequired() {
        loginAsSuperAdmin();
        assertEquals(ErrorCode.FORCE_REASON_REQUIRED,
                errorCodeOf(() -> service.force(ORDER_ID, request(ForceOperationType.FORCE_TERMINATE.name(), "   "))));
    }

    @Test
    @DisplayName("状态：强制驳回用在「使用中」工单 → FORCE_OPERATION_NOT_SUPPORTED")
    void forceReject_wrongStatus_rejected() {
        loginAsSuperAdmin();
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), HANDLER_ID));

        assertEquals(ErrorCode.FORCE_OPERATION_NOT_SUPPORTED,
                errorCodeOf(() -> service.force(ORDER_ID, request(ForceOperationType.FORCE_REJECT.name(), "材料不实"))));
        verify(forceMapper, never()).insert(any());
    }

    // ------------------------------------------------------------------
    // 强制驳回
    // ------------------------------------------------------------------

    @Test
    @DisplayName("强制驳回：作废节点 + 工单→已驳回 + 释放设备 + 写记录 + 通知申请人")
    void forceReject_success() {
        loginAsSuperAdmin();
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.PENDING_APPROVAL.name(), null));
        when(orderMapper.update(any(), any())).thenReturn(1);
        when(deviceMapper.update(any(), any())).thenReturn(1);

        service.force(ORDER_ID, request(ForceOperationType.FORCE_REJECT.name(), "材料不实"));

        OrderForceOperation record = capturedForceRecord();
        assertEquals(ForceOperationType.FORCE_REJECT.name(), record.getOperationType());
        assertEquals(OrderStatus.PENDING_APPROVAL.name(), record.getOldStatus());
        assertEquals(OrderStatus.REJECTED.name(), record.getNewStatus());
        assertEquals("材料不实", record.getReason());
        // 作废未完成节点（含 INACTIVE）已收敛到 FlowActivationService
        verify(flowActivationService).cancelOpenNodes(ORDER_ID, null);
        verify(deviceMapper).update(any(), any());
        verify(messageService).send(eq(APPLICANT_ID), eq(MessageType.FORCE_REJECT), any(), any(), eq(ORDER_ID));
    }

    @Test
    @DisplayName("强制驳回：条件 UPDATE 命中 0 行（并发已推进）→ ORDER_STATUS_TRANSITION_INVALID，不写记录")
    void forceReject_conflict_rejected() {
        loginAsSuperAdmin();
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.PENDING_APPROVAL.name(), null));
        when(orderMapper.update(any(), any())).thenReturn(0);

        assertEquals(ErrorCode.ORDER_STATUS_TRANSITION_INVALID,
                errorCodeOf(() -> service.force(ORDER_ID, request(ForceOperationType.FORCE_REJECT.name(), "材料不实"))));
        verify(forceMapper, never()).insert(any());
    }

    // ------------------------------------------------------------------
    // 强制终止
    // ------------------------------------------------------------------

    @Test
    @DisplayName("强制终止：使用中工单 → 已终止 + 释放设备（IN_USE→AVAILABLE）+ 通知申请人与执行人")
    void forceTerminate_fromBorrowed_success() {
        loginAsSuperAdmin();
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), HANDLER_ID));
        when(orderMapper.update(any(), any())).thenReturn(1);
        when(deviceMapper.update(any(), any())).thenReturn(1);

        service.force(ORDER_ID, request(ForceOperationType.FORCE_TERMINATE.name(), "设备无法找回"));

        OrderForceOperation record = capturedForceRecord();
        assertEquals(ForceOperationType.FORCE_TERMINATE.name(), record.getOperationType());
        assertEquals(OrderStatus.BORROWED.name(), record.getOldStatus());
        assertEquals(OrderStatus.TERMINATED.name(), record.getNewStatus());
        verify(deviceMapper).update(any(), any());
        verify(messageService).send(eq(APPLICANT_ID), eq(MessageType.FORCE_TERMINATE), any(), any(), eq(ORDER_ID));
        verify(messageService).send(eq(HANDLER_ID), eq(MessageType.FORCE_TERMINATE), any(), any(), eq(ORDER_ID));
    }

    @Test
    @DisplayName("强制终止：终态（已归还）→ FORCE_OPERATION_NOT_SUPPORTED")
    void forceTerminate_terminal_rejected() {
        loginAsSuperAdmin();
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.RETURNED.name(), HANDLER_ID));

        assertEquals(ErrorCode.FORCE_OPERATION_NOT_SUPPORTED,
                errorCodeOf(() -> service.force(ORDER_ID, request(ForceOperationType.FORCE_TERMINATE.name(), "x"))));
        verify(forceMapper, never()).insert(any());
    }

    // ------------------------------------------------------------------
    // 强制转交审批
    // ------------------------------------------------------------------

    @Test
    @DisplayName("强制转交审批：改派当前待办节点审批人 + 记录 node/新旧审批人 + 三方通知")
    void forceTransferApproval_success() {
        loginAsSuperAdmin();
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.PENDING_APPROVAL.name(), null));
        when(userMapper.selectById(NEW_APPROVER_ID)).thenReturn(user(NEW_APPROVER_ID, true, false));
        when(nodeMapper.selectList(any())).thenReturn(List.of(
                node(NODE_ID, 1, OLD_APPROVER_ID, ApprovalNodeStatus.PENDING.name())));
        when(flowActivationService.reassignStepApprover(ORDER_ID, 1, NEW_APPROVER_ID)).thenReturn(1);
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(
                user(OLD_APPROVER_ID, true, false), user(NEW_APPROVER_ID, true, false)));

        OrderForceRequest req = request(ForceOperationType.FORCE_TRANSFER_APPROVAL.name(), "原审批人长期休假");
        req.setTargetApproverId(NEW_APPROVER_ID);
        service.force(ORDER_ID, req);

        OrderForceOperation record = capturedForceRecord();
        assertEquals(ForceOperationType.FORCE_TRANSFER_APPROVAL.name(), record.getOperationType());
        assertEquals(NODE_ID, record.getNodeId());
        assertEquals(OLD_APPROVER_ID, record.getOldApproverId());
        assertEquals(NEW_APPROVER_ID, record.getNewApproverId());
        // 新旧状态相同（转交不改状态）
        assertEquals(OrderStatus.PENDING_APPROVAL.name(), record.getOldStatus());
        assertEquals(OrderStatus.PENDING_APPROVAL.name(), record.getNewStatus());
        verify(flowActivationService).reassignStepApprover(ORDER_ID, 1, NEW_APPROVER_ID);
        verify(messageService).send(eq(NEW_APPROVER_ID), eq(MessageType.FORCE_TRANSFER_APPROVAL), any(), any(), eq(ORDER_ID));
    }

    @Test
    @DisplayName("强制转交审批：未指定目标审批人 → FORCE_TARGET_APPROVER_REQUIRED")
    void forceTransferApproval_missingTarget_rejected() {
        loginAsSuperAdmin();
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.PENDING_APPROVAL.name(), null));

        assertEquals(ErrorCode.FORCE_TARGET_APPROVER_REQUIRED,
                errorCodeOf(() -> service.force(ORDER_ID, request(ForceOperationType.FORCE_TRANSFER_APPROVAL.name(), "x"))));
        verify(nodeMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("强制转交审批：目标为申请人本人 → FORCE_TARGET_APPROVER_INVALID（规范 §14 自审回避）")
    void forceTransferApproval_targetIsApplicant_rejected() {
        loginAsSuperAdmin();
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.PENDING_APPROVAL.name(), null));
        when(userMapper.selectById(APPLICANT_ID)).thenReturn(user(APPLICANT_ID, true, false));

        OrderForceRequest req = request(ForceOperationType.FORCE_TRANSFER_APPROVAL.name(), "x");
        req.setTargetApproverId(APPLICANT_ID);
        assertEquals(ErrorCode.FORCE_TARGET_APPROVER_INVALID, errorCodeOf(() -> service.force(ORDER_ID, req)));
    }

    @Test
    @DisplayName("强制转交审批：目标就是当前审批人 → FORCE_TARGET_APPROVER_INVALID")
    void forceTransferApproval_sameApprover_rejected() {
        loginAsSuperAdmin();
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.PENDING_APPROVAL.name(), null));
        when(userMapper.selectById(OLD_APPROVER_ID)).thenReturn(user(OLD_APPROVER_ID, true, false));
        when(nodeMapper.selectList(any())).thenReturn(List.of(
                node(NODE_ID, 1, OLD_APPROVER_ID, ApprovalNodeStatus.PENDING.name())));

        OrderForceRequest req = request(ForceOperationType.FORCE_TRANSFER_APPROVAL.name(), "x");
        req.setTargetApproverId(OLD_APPROVER_ID);
        assertEquals(ErrorCode.FORCE_TARGET_APPROVER_INVALID, errorCodeOf(() -> service.force(ORDER_ID, req)));
    }

    // ------------------------------------------------------------------
    // 强制转交执行人
    // ------------------------------------------------------------------

    @Test
    @DisplayName("强制转交执行人：跨组改派 + 写 FORCE_ADMIN 转交历史 + 写强制记录 + 通知")
    void forceTransferHandler_success() {
        loginAsSuperAdmin();
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), HANDLER_ID));
        when(userMapper.selectById(NEW_HANDLER_ID)).thenReturn(user(NEW_HANDLER_ID, true, false));
        when(orderMapper.update(any(), any())).thenReturn(1);
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(
                user(HANDLER_ID, true, false), user(NEW_HANDLER_ID, true, false)));

        OrderForceRequest req = request(ForceOperationType.FORCE_TRANSFER_HANDLER.name(), "原执行人休假");
        req.setTargetHandlerId(NEW_HANDLER_ID);
        service.force(ORDER_ID, req);

        ArgumentCaptor<OrderHandlerTransfer> captor = ArgumentCaptor.forClass(OrderHandlerTransfer.class);
        verify(transferMapper).insert(captor.capture());
        assertEquals(HANDLER_ID, captor.getValue().getOldHandlerId());
        assertEquals(NEW_HANDLER_ID, captor.getValue().getNewHandlerId());
        assertEquals(TransferType.FORCE_ADMIN.name(), captor.getValue().getTransferType());

        OrderForceOperation record = capturedForceRecord();
        assertEquals(ForceOperationType.FORCE_TRANSFER_HANDLER.name(), record.getOperationType());
        assertEquals(HANDLER_ID, record.getOldHandlerId());
        assertEquals(NEW_HANDLER_ID, record.getNewHandlerId());

        verify(messageService).send(eq(NEW_HANDLER_ID), eq(MessageType.FORCE_TRANSFER_HANDLER), any(), any(), eq(ORDER_ID));
        verify(messageService).send(eq(HANDLER_ID), eq(MessageType.FORCE_TRANSFER_HANDLER), any(), any(), eq(ORDER_ID));
    }

    @Test
    @DisplayName("强制转交执行人：未指定目标 → FORCE_TARGET_HANDLER_REQUIRED")
    void forceTransferHandler_missingTarget_rejected() {
        loginAsSuperAdmin();
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), HANDLER_ID));

        assertEquals(ErrorCode.FORCE_TARGET_HANDLER_REQUIRED,
                errorCodeOf(() -> service.force(ORDER_ID, request(ForceOperationType.FORCE_TRANSFER_HANDLER.name(), "x"))));
    }

    @Test
    @DisplayName("强制转交执行人：目标就是当前执行人 → FORCE_TARGET_HANDLER_INVALID")
    void forceTransferHandler_sameHandler_rejected() {
        loginAsSuperAdmin();
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), HANDLER_ID));
        when(userMapper.selectById(HANDLER_ID)).thenReturn(user(HANDLER_ID, true, false));

        OrderForceRequest req = request(ForceOperationType.FORCE_TRANSFER_HANDLER.name(), "x");
        req.setTargetHandlerId(HANDLER_ID);
        assertEquals(ErrorCode.FORCE_TARGET_HANDLER_INVALID, errorCodeOf(() -> service.force(ORDER_ID, req)));
    }

    @Test
    @DisplayName("强制转交执行人：目标已停用 → FORCE_TARGET_HANDLER_INVALID")
    void forceTransferHandler_disabledTarget_rejected() {
        loginAsSuperAdmin();
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), HANDLER_ID));
        when(userMapper.selectById(NEW_HANDLER_ID)).thenReturn(user(NEW_HANDLER_ID, false, false));

        OrderForceRequest req = request(ForceOperationType.FORCE_TRANSFER_HANDLER.name(), "x");
        req.setTargetHandlerId(NEW_HANDLER_ID);
        assertEquals(ErrorCode.FORCE_TARGET_HANDLER_INVALID, errorCodeOf(() -> service.force(ORDER_ID, req)));
    }

    @Test
    @DisplayName("强制转交执行人：条件 UPDATE 命中 0 行 → TRANSFER_CONFLICT，不写历史/记录")
    void forceTransferHandler_conflict_rejected() {
        loginAsSuperAdmin();
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), HANDLER_ID));
        when(userMapper.selectById(NEW_HANDLER_ID)).thenReturn(user(NEW_HANDLER_ID, true, false));
        when(orderMapper.update(any(), any())).thenReturn(0);

        OrderForceRequest req = request(ForceOperationType.FORCE_TRANSFER_HANDLER.name(), "原执行人休假");
        req.setTargetHandlerId(NEW_HANDLER_ID);
        assertEquals(ErrorCode.TRANSFER_CONFLICT, errorCodeOf(() -> service.force(ORDER_ID, req)));
        verify(transferMapper, never()).insert(any());
        verify(forceMapper, never()).insert(any());
    }

    @Test
    @DisplayName("ForceOperationType 适用范围：支持状态与枚举声明一致（驳回/转交审批仅审批中）")
    void forceOperationType_allowedStatuses() {
        assertEquals(List.of(OrderStatus.PENDING_APPROVAL),
                ForceOperationType.FORCE_REJECT.getAllowedStatuses());
        assertEquals(List.of(OrderStatus.PENDING_APPROVAL),
                ForceOperationType.FORCE_TRANSFER_APPROVAL.getAllowedStatuses());
        assertEquals(OrderStatus.transferableNames().size(),
                ForceOperationType.FORCE_TRANSFER_HANDLER.getAllowedStatuses().size());
    }
}
