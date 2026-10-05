package com.enterprise.ticket.module.order.service.impl;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.ApprovalNodeStatus;
import com.enterprise.ticket.common.constant.MessageType;
import com.enterprise.ticket.common.constant.OrderStatus;
import com.enterprise.ticket.common.constant.RoleCode;
import com.enterprise.ticket.common.constant.UrgeType;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.message.service.MessageService;
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
import com.enterprise.ticket.security.LoginUser;
import com.enterprise.ticket.support.MyBatisLambdaCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工单催办单元测试（Phase 7；规范 V1.1 未覆盖，按需求方确认方案实现）
 *
 * <p>催办是「只发消息、不改状态」的轻操作，风险集中在两点：
 * <ul>
 *   <li><b>谁有权催谁</b>：审批催办只有申请人能发起；归还催办只有执行人（或 super_admin）能发起；</li>
 *   <li><b>什么时候能催</b>：审批需「审批中 + 有 PENDING 节点」，归还需「使用中 + 已到期/超时」，
 *       且都受冷却时间约束 —— 冷却键审批按「工单+节点」、归还按「工单」。</li>
 * </ul>
 * <p>会签（同一步骤多个 PENDING 节点）必须给全部审批人各发一条消息。
 */
@ExtendWith(MockitoExtension.class)
class OrderUrgeServiceImplTest {

    private static final Long ORDER_ID = 100L;
    private static final Long DEVICE_ID = 11L;
    private static final Long APPLICANT_ID = 2L;
    private static final Long HANDLER_ID = 4L;
    private static final Long APPROVER_ID = 6L;
    private static final Long SUPER_ADMIN_ID = 1L;

    @BeforeAll
    static void initMyBatisLambdaCache() {
        MyBatisLambdaCache.init(Order.class, OrderApprovalNode.class, OrderUrge.class, User.class);
    }

    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderApprovalNodeMapper orderNodeMapper;
    @Mock
    private OrderUrgeMapper urgeMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private MessageService messageService;
    @Mock
    private SystemConfigService systemConfigService;

    @InjectMocks
    private OrderUrgeServiceImpl service;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------
    // 辅助构造
    // ------------------------------------------------------------------

    private void loginAs(Long userId, String role) {
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

    /** 审批中的工单（申请人 = APPLICANT_ID），用于审批催办 */
    private Order approvalOrder() {
        return order(OrderStatus.PENDING_APPROVAL.name(), HANDLER_ID);
    }

    private OrderApprovalNode node(Long id, int stepOrder, ApprovalNodeStatus status, Long approverId) {
        OrderApprovalNode node = new OrderApprovalNode();
        node.setId(id);
        node.setOrderId(ORDER_ID);
        node.setStepOrder(stepOrder);
        node.setStatus(status.name());
        node.setApproverId(approverId);
        return node;
    }

    private OrderUrge urgeRow(Long orderId, UrgeType type, Long nodeId, LocalDateTime createdAt) {
        OrderUrge urge = new OrderUrge();
        urge.setOrderId(orderId);
        urge.setUrgeType(type.name());
        urge.setNodeId(nodeId);
        urge.setCreatedAt(createdAt);
        return urge;
    }

    private static ErrorCode errorCodeOf(Runnable runnable) {
        return assertThrows(BusinessException.class, runnable::run).getErrorCode();
    }

    // ------------------------------------------------------------------
    // 审批催办
    // ------------------------------------------------------------------

    @Test
    @DisplayName("审批催办：申请人催当前 PENDING 节点 → 写记录 + 通知审批人")
    void urgeApproval_success() {
        loginAs(APPLICANT_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(approvalOrder());
        when(orderNodeMapper.selectList(any()))
                .thenReturn(List.of(node(1L, 1, ApprovalNodeStatus.PENDING, APPROVER_ID)));
        when(urgeMapper.selectList(any())).thenReturn(List.of());

        service.urgeApproval(ORDER_ID);

        verify(urgeMapper).insert(any());
        verify(messageService).send(eq(List.of(APPROVER_ID)), eq(MessageType.URGE_APPROVAL), any(), any(), eq(ORDER_ID));
    }

    @Test
    @DisplayName("审批催办拒绝：非申请人 → FORBIDDEN")
    void urgeApproval_notApplicant_forbidden() {
        loginAs(HANDLER_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(approvalOrder());

        assertEquals(ErrorCode.FORBIDDEN, errorCodeOf(() -> service.urgeApproval(ORDER_ID)));
        verify(urgeMapper, never()).insert(any());
    }

    @Test
    @DisplayName("审批催办拒绝：工单非「审批中」→ URGE_NOT_ALLOWED")
    void urgeApproval_wrongStatus_notAllowed() {
        loginAs(APPLICANT_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), HANDLER_ID));

        assertEquals(ErrorCode.URGE_NOT_ALLOWED, errorCodeOf(() -> service.urgeApproval(ORDER_ID)));
    }

    @Test
    @DisplayName("审批催办拒绝：当前无 PENDING 节点 → URGE_TARGET_MISSING")
    void urgeApproval_noPendingNode_targetMissing() {
        loginAs(APPLICANT_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(approvalOrder());
        when(orderNodeMapper.selectList(any()))
                .thenReturn(List.of(node(1L, 1, ApprovalNodeStatus.APPROVED, APPROVER_ID)));

        assertEquals(ErrorCode.URGE_TARGET_MISSING, errorCodeOf(() -> service.urgeApproval(ORDER_ID)));
    }

    @Test
    @DisplayName("审批催办拒绝：同工单同节点冷却期内 → URGE_COOLDOWN")
    void urgeApproval_cooldown_rejected() {
        loginAs(APPLICANT_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(approvalOrder());
        when(orderNodeMapper.selectList(any()))
                .thenReturn(List.of(node(1L, 1, ApprovalNodeStatus.PENDING, APPROVER_ID)));
        when(systemConfigService.urgeCooldownMinutes()).thenReturn(60);
        when(urgeMapper.selectList(any()))
                .thenReturn(List.of(urgeRow(ORDER_ID, UrgeType.APPROVAL, 1L, LocalDateTime.now().minusMinutes(10))));

        assertEquals(ErrorCode.URGE_COOLDOWN, errorCodeOf(() -> service.urgeApproval(ORDER_ID)));
        verify(urgeMapper, never()).insert(any());
    }

    @Test
    @DisplayName("审批催办：会签（同步骤多 PENDING 节点）→ 逐一写记录 + 通知全部审批人")
    void urgeApproval_multiApprover_notifiesAll() {
        loginAs(APPLICANT_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(approvalOrder());
        when(orderNodeMapper.selectList(any())).thenReturn(List.of(
                node(1L, 1, ApprovalNodeStatus.PENDING, 6L),
                node(2L, 1, ApprovalNodeStatus.PENDING, 9L)));
        when(urgeMapper.selectList(any())).thenReturn(List.of());

        service.urgeApproval(ORDER_ID);

        verify(urgeMapper, times(2)).insert(any());
        verify(messageService).send(eq(List.of(6L, 9L)), eq(MessageType.URGE_APPROVAL), any(), any(), eq(ORDER_ID));
    }

    // ------------------------------------------------------------------
    // 归还催办
    // ------------------------------------------------------------------

    @Test
    @DisplayName("归还催办：执行人催已到期工单 → 写记录 + 通知借用人")
    void urgeReturn_success() {
        loginAs(HANDLER_ID, RoleCode.USER);
        Order order = order(OrderStatus.BORROWED.name(), HANDLER_ID);
        order.setPlannedEndTime(LocalDateTime.now().minusDays(1));
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order);
        when(urgeMapper.selectList(any())).thenReturn(List.of());

        service.urgeReturn(ORDER_ID);

        verify(urgeMapper).insert(any());
        verify(messageService).send(eq(APPLICANT_ID), eq(MessageType.URGE_RETURN), any(), any(), eq(ORDER_ID));
    }

    @Test
    @DisplayName("归还催办：超时标记为真（无应还时间）也可催")
    void urgeReturn_timeoutFlag_success() {
        loginAs(HANDLER_ID, RoleCode.USER);
        Order order = order(OrderStatus.BORROWED.name(), HANDLER_ID);
        order.setBorrowTimeout(true);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order);
        when(urgeMapper.selectList(any())).thenReturn(List.of());

        service.urgeReturn(ORDER_ID);

        verify(urgeMapper).insert(any());
    }

    @Test
    @DisplayName("归还催办拒绝：非执行人的普通组员 → FORBIDDEN")
    void urgeReturn_notHandler_forbidden() {
        loginAs(APPLICANT_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), HANDLER_ID));

        assertEquals(ErrorCode.FORBIDDEN, errorCodeOf(() -> service.urgeReturn(ORDER_ID)));
    }

    @Test
    @DisplayName("归还催办拒绝：工单非「使用中」→ URGE_NOT_ALLOWED")
    void urgeReturn_wrongStatus_notAllowed() {
        loginAs(HANDLER_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.PENDING_RETURN.name(), HANDLER_ID));

        assertEquals(ErrorCode.URGE_NOT_ALLOWED, errorCodeOf(() -> service.urgeReturn(ORDER_ID)));
    }

    @Test
    @DisplayName("归还催办拒绝：尚未到期 → URGE_NOT_ALLOWED")
    void urgeReturn_notDue_notAllowed() {
        loginAs(HANDLER_ID, RoleCode.USER);
        Order order = order(OrderStatus.BORROWED.name(), HANDLER_ID);
        order.setPlannedEndTime(LocalDateTime.now().plusDays(3));
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order);

        assertEquals(ErrorCode.URGE_NOT_ALLOWED, errorCodeOf(() -> service.urgeReturn(ORDER_ID)));
        verify(urgeMapper, never()).insert(any());
    }

    @Test
    @DisplayName("归还催办：super_admin 非执行人也可发起")
    void urgeReturn_superAdmin_allowed() {
        loginAs(SUPER_ADMIN_ID, RoleCode.SUPER_ADMIN);
        Order order = order(OrderStatus.BORROWED.name(), HANDLER_ID);
        order.setPlannedEndTime(LocalDateTime.now().minusHours(2));
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order);
        when(urgeMapper.selectList(any())).thenReturn(List.of());

        service.urgeReturn(ORDER_ID);

        verify(messageService).send(eq(APPLICANT_ID), eq(MessageType.URGE_RETURN), any(), any(), eq(ORDER_ID));
    }

    @Test
    @DisplayName("归还催办拒绝：冷却期内 → URGE_COOLDOWN")
    void urgeReturn_cooldown_rejected() {
        loginAs(HANDLER_ID, RoleCode.USER);
        Order order = order(OrderStatus.BORROWED.name(), HANDLER_ID);
        order.setPlannedEndTime(LocalDateTime.now().minusDays(1));
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order);
        when(systemConfigService.urgeCooldownMinutes()).thenReturn(60);
        when(urgeMapper.selectList(any()))
                .thenReturn(List.of(urgeRow(ORDER_ID, UrgeType.RETURN, null, LocalDateTime.now().minusMinutes(5))));

        assertEquals(ErrorCode.URGE_COOLDOWN, errorCodeOf(() -> service.urgeReturn(ORDER_ID)));
        verify(urgeMapper, never()).insert(any());
    }

    // ------------------------------------------------------------------
    // 冷却配置 & 统计
    // ------------------------------------------------------------------

    @Test
    @DisplayName("冷却时长：透传 system_config.urge_cooldown_minutes")
    void cooldownMinutes_delegatesToConfig() {
        when(systemConfigService.urgeCooldownMinutes()).thenReturn(30);
        assertEquals(30, service.cooldownMinutes());
    }

    @Test
    @DisplayName("催办统计：按工单聚合，审批取节点最新一次、归还取最新一次")
    void statByOrders_aggregates() {
        LocalDateTime oldApproval = LocalDateTime.now().minusMinutes(30);
        LocalDateTime newApproval = LocalDateTime.now().minusMinutes(5);
        LocalDateTime returnAt = LocalDateTime.now().minusMinutes(2);
        when(urgeMapper.selectList(any())).thenReturn(List.of(
                urgeRow(ORDER_ID, UrgeType.APPROVAL, 1L, oldApproval),
                urgeRow(ORDER_ID, UrgeType.APPROVAL, 1L, newApproval),
                urgeRow(ORDER_ID, UrgeType.RETURN, null, returnAt),
                urgeRow(200L, UrgeType.APPROVAL, 3L, LocalDateTime.now().minusMinutes(1))));

        Map<Long, OrderUrgeService.UrgeStat> stats = service.statByOrders(List.of(ORDER_ID, 200L));

        OrderUrgeService.UrgeStat stat = stats.get(ORDER_ID);
        assertEquals(newApproval, stat.approvalLastAtByNode().get(1L));
        assertEquals(returnAt, stat.returnLastAt());
        assertNotNull(stats.get(200L).approvalLastAtByNode().get(3L));
        assertNull(stats.get(200L).returnLastAt());

        // 单工单查询命中
        assertEquals(stat, service.statOfOrder(ORDER_ID));
    }
}
