package com.enterprise.ticket.module.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.ApprovalNodeStatus;
import com.enterprise.ticket.common.constant.ExtendStatus;
import com.enterprise.ticket.common.constant.MessageType;
import com.enterprise.ticket.common.constant.OrderStatus;
import com.enterprise.ticket.common.constant.RoleCode;
import com.enterprise.ticket.common.constant.SignType;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.flow.FlowNodeType;
import com.enterprise.ticket.module.message.service.MessageService;
import com.enterprise.ticket.module.order.dto.OrderExtendApproveRequest;
import com.enterprise.ticket.module.order.dto.OrderExtendRequest;
import com.enterprise.ticket.module.order.entity.Order;
import com.enterprise.ticket.module.order.entity.OrderApprovalNode;
import com.enterprise.ticket.module.order.entity.OrderExtend;
import com.enterprise.ticket.module.order.entity.OrderExtendApprovalNode;
import com.enterprise.ticket.module.order.mapper.OrderApprovalNodeMapper;
import com.enterprise.ticket.module.order.mapper.OrderExtendApprovalNodeMapper;
import com.enterprise.ticket.module.order.mapper.OrderExtendMapper;
import com.enterprise.ticket.module.order.mapper.OrderMapper;
import com.enterprise.ticket.module.order.service.OrderExtendService;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 借用延期子工单单元测试（规范 §17，需求方 Phase 6）
 *
 * <p>重点覆盖「谁能发起 / 什么状态能发起」「审批链路快照复制的正确性」与
 * 「审批通过后只回写主单三个字段」——这三处是本阶段唯一可能造成业务错误的地方：
 * <ul>
 *   <li>只有主单申请人可发起，且仅「使用中」的短期借用可延期（长期领用无固定归还日）；</li>
 *   <li>延期次数用尽 / 已有审批中的延期 / 新时间不晚于当前时间，均明确拒绝；</li>
 *   <li>审批通过后只改 planned_end_time / borrow_timeout / auto_extend_count，
 *       <b>不动工单状态、不动设备、不动最终执行人</b>；驳回则主单完全不变；</li>
 *   <li>主单状态已变化时靠条件 UPDATE 的 0 行结果兜底，不得静默生效。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class OrderExtendServiceImplTest {

    private static final Long ORDER_ID = 100L;
    private static final Long EXTEND_ID = 500L;
    private static final Long DEVICE_ID = 11L;
    private static final Long APPLICANT_ID = 2L;
    private static final Long HANDLER_ID = 4L;
    private static final Long OTHER_ID = 9L;

    @BeforeAll
    static void initMyBatisLambdaCache() {
        MyBatisLambdaCache.init(Order.class, User.class, OrderExtend.class,
                OrderExtendApprovalNode.class, OrderApprovalNode.class);
    }

    @Mock
    private OrderExtendMapper extendMapper;
    @Mock
    private OrderExtendApprovalNodeMapper extendNodeMapper;
    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderApprovalNodeMapper orderNodeMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private MessageService messageService;
    @Mock
    private SystemConfigService systemConfigService;

    @InjectMocks
    private OrderExtendServiceImpl service;

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

    private User activeUser(Long id) {
        User user = new User();
        user.setId(id);
        user.setUsername("u" + id);
        user.setDisplayName("用户" + id);
        user.setEnabled(true);
        user.setDimission(false);
        return user;
    }

    private Order order(String status, LocalDateTime plannedEnd) {
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setOrderNo("BO20260917-100");
        order.setDeviceId(DEVICE_ID);
        order.setApplicantId(APPLICANT_ID);
        order.setActualFinalHandlerId(HANDLER_ID);
        order.setStatus(status);
        order.setPlannedEndTime(plannedEnd);
        order.setBorrowTimeout(false);
        order.setAutoExtendCount(0);
        return order;
    }

    private OrderApprovalNode mainNode(int step, Long approverId, String signType) {
        OrderApprovalNode node = new OrderApprovalNode();
        node.setOrderId(ORDER_ID);
        node.setStepOrder(step);
        node.setApproverId(approverId);
        node.setSignType(signType);
        node.setStatus(ApprovalNodeStatus.PENDING.name());
        node.setSuperBackup(false);
        return node;
    }

    private OrderExtend extend(String status, LocalDateTime newEnd) {
        OrderExtend extend = new OrderExtend();
        extend.setId(EXTEND_ID);
        extend.setOrderId(ORDER_ID);
        extend.setApplicantId(APPLICANT_ID);
        extend.setOriginalEndTime(LocalDateTime.now().plusDays(1));
        extend.setNewEndTime(newEnd == null ? LocalDateTime.now().plusDays(3) : newEnd);
        extend.setReason("项目尚未结束");
        extend.setStatus(status);
        return extend;
    }

    private OrderExtendApprovalNode extendNode(Long id, int step, Long approverId, String signType, String status) {
        OrderExtendApprovalNode node = new OrderExtendApprovalNode();
        node.setId(id);
        node.setExtendId(EXTEND_ID);
        node.setStepOrder(step);
        node.setApproverId(approverId);
        node.setSignType(signType);
        node.setStatus(status);
        node.setSuperBackup(false);
        node.setFallback(false);
        return node;
    }

    private OrderExtendRequest request(LocalDateTime newEnd, String reason) {
        OrderExtendRequest request = new OrderExtendRequest();
        request.setNewEndTime(newEnd);
        request.setReason(reason);
        return request;
    }

    private OrderExtendApproveRequest approveRequest(boolean approved, String comment) {
        OrderExtendApproveRequest request = new OrderExtendApproveRequest();
        request.setApproved(approved);
        request.setComment(comment);
        return request;
    }

    private static ErrorCode errorCodeOf(Runnable runnable) {
        return assertThrows(BusinessException.class, runnable::run).getErrorCode();
    }

    // ------------------------------------------------------------------
    // 发起延期的边界
    // ------------------------------------------------------------------

    @Test
    @DisplayName("发起延期：非主单申请人被拒绝（规范 §17 申请人主动发起）")
    void request_byNonApplicant_rejected() {
        loginAs(OTHER_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), LocalDateTime.now().plusDays(1)));

        assertEquals(ErrorCode.ORDER_NOT_APPLICANT,
                errorCodeOf(() -> service.request(ORDER_ID, request(LocalDateTime.now().plusDays(3), "x"))));
        verify(extendMapper, never()).insert(any());
    }

    @Test
    @DisplayName("发起延期：主工单非「使用中」被拒绝（仅使用中可延期）")
    void request_wrongStatus_rejected() {
        loginAs(APPLICANT_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.PENDING_DELIVERY.name(), LocalDateTime.now().plusDays(1)));

        assertEquals(ErrorCode.EXTEND_NOT_ALLOWED,
                errorCodeOf(() -> service.request(ORDER_ID, request(LocalDateTime.now().plusDays(3), "x"))));
    }

    @Test
    @DisplayName("发起延期：长期领用（无计划结束时间）被拒绝（无「延期」语义）")
    void request_longTermNoPlannedEnd_rejected() {
        loginAs(APPLICANT_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), null));

        assertEquals(ErrorCode.EXTEND_LONG_TERM_NOT_SUPPORTED,
                errorCodeOf(() -> service.request(ORDER_ID, request(LocalDateTime.now().plusDays(3), "x"))));
    }

    @Test
    @DisplayName("发起延期：新结束时间不晚于当前时间被拒绝（规范 §17）")
    void request_newEndTimeNotFuture_rejected() {
        loginAs(APPLICANT_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), LocalDateTime.now().plusDays(1)));

        assertEquals(ErrorCode.EXTEND_TIME_INVALID,
                errorCodeOf(() -> service.request(ORDER_ID, request(LocalDateTime.now().minusHours(1), "x"))));
    }

    @Test
    @DisplayName("发起延期：已有审批中的延期被拒绝（防重复提交）")
    void request_pendingExists_rejected() {
        loginAs(APPLICANT_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), LocalDateTime.now().plusDays(1)));
        when(systemConfigService.extendMaxCount()).thenReturn(2);
        when(extendMapper.selectList(any())).thenReturn(List.of(extend(ExtendStatus.PENDING_APPROVAL.name(), null)));

        assertEquals(ErrorCode.EXTEND_PENDING_EXISTS,
                errorCodeOf(() -> service.request(ORDER_ID, request(LocalDateTime.now().plusDays(3), "x"))));
    }

    @Test
    @DisplayName("发起延期：次数用尽被拒绝（默认上限 2，已驳回的不计数）")
    void request_limitReached_rejected() {
        loginAs(APPLICANT_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), LocalDateTime.now().plusDays(1)));
        when(systemConfigService.extendMaxCount()).thenReturn(2);
        when(extendMapper.selectList(any())).thenReturn(List.of(
                extend(ExtendStatus.APPROVED.name(), null),
                extend(ExtendStatus.APPROVED.name(), null)));

        assertEquals(ErrorCode.EXTEND_LIMIT_EXCEEDED,
                errorCodeOf(() -> service.request(ORDER_ID, request(LocalDateTime.now().plusDays(3), "x"))));
    }

    // ------------------------------------------------------------------
    // 发起延期：正常路径
    // ------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("发起延期：按主单快照复制审批节点并推送延期待办")
    void request_happyPath_copiesSnapshotAndNotifies() {
        loginAs(APPLICANT_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), LocalDateTime.now().plusDays(1)));
        when(systemConfigService.extendMaxCount()).thenReturn(2);
        when(extendMapper.selectList(any())).thenReturn(List.of());
        when(extendMapper.insert(any(OrderExtend.class))).thenAnswer(invocation -> {
            ((OrderExtend) invocation.getArgument(0)).setId(EXTEND_ID);
            return 1;
        });
        when(orderNodeMapper.selectList(any())).thenReturn(List.of(mainNode(1, HANDLER_ID, SignType.ALL_SIGN)));
        when(userMapper.selectById(HANDLER_ID)).thenReturn(activeUser(HANDLER_ID));

        service.request(ORDER_ID, request(LocalDateTime.now().plusDays(3), "项目延期"));

        verify(extendMapper).insert(any(OrderExtend.class));
        ArgumentCaptor<OrderExtendApprovalNode> nodeCaptor = ArgumentCaptor.forClass(OrderExtendApprovalNode.class);
        verify(extendNodeMapper).insert(nodeCaptor.capture());
        assertEquals(HANDLER_ID, nodeCaptor.getValue().getApproverId());
        assertEquals(1, nodeCaptor.getValue().getStepOrder());
        assertEquals(ApprovalNodeStatus.PENDING.name(), nodeCaptor.getValue().getStatus());

        ArgumentCaptor<Collection<Long>> sendCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(messageService).send(sendCaptor.capture(), eq(MessageType.APPROVAL_TODO), anyString(), anyString(), eq(ORDER_ID));
        assertTrue(sendCaptor.getValue().contains(HANDLER_ID), "应把延期待办推给当前步骤审批人");
        // 发起阶段绝不回写主单
        verify(orderMapper, never()).update(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("发起延期 · C10：只复制审批行，查询排除抄送(CC)与未激活(INACTIVE)行")
    void request_excludesCcAndInactiveRows() {
        loginAs(APPLICANT_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID))
                .thenReturn(order(OrderStatus.BORROWED.name(), LocalDateTime.now().plusDays(1)));
        when(systemConfigService.extendMaxCount()).thenReturn(2);
        when(extendMapper.selectList(any())).thenReturn(List.of());
        when(extendMapper.insert(any(OrderExtend.class))).thenAnswer(invocation -> {
            ((OrderExtend) invocation.getArgument(0)).setId(EXTEND_ID);
            return 1;
        });
        when(orderNodeMapper.selectList(any())).thenReturn(List.of(mainNode(1, HANDLER_ID, SignType.ALL_SIGN)));
        when(userMapper.selectById(HANDLER_ID)).thenReturn(activeUser(HANDLER_ID));

        service.request(ORDER_ID, request(LocalDateTime.now().plusDays(3), "项目延期"));

        ArgumentCaptor<LambdaQueryWrapper<OrderApprovalNode>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(orderNodeMapper).selectList(captor.capture());
        LambdaQueryWrapper<OrderApprovalNode> wrapper = captor.getValue();
        // MyBatis-Plus 的条件参数是惰性写入的：先取一次 SQL 片段把参数落桶，否则断言看到的是空桶
        wrapper.getSqlSegment();
        assertTrue(wrapper.getParamNameValuePairs().containsValue(FlowNodeType.CC.name()),
                "必须排除抄送行 —— 否则抄送对象的 approver_id 被复制并强置 PENDING，抄送人凭空变成审批人");
        assertTrue(wrapper.getParamNameValuePairs().containsValue(ApprovalNodeStatus.INACTIVE.name()),
                "必须排除未激活行 —— 否则「还没轮到的人」会被变成「现在必须审的人」");
    }

    @Test
    @DisplayName("发起延期：审批人=申请人且存在其他审批人时跳过该节点（规范 §14）")
    void request_approverIsApplicant_skipped() {
        loginAs(APPLICANT_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), LocalDateTime.now().plusDays(1)));
        when(systemConfigService.extendMaxCount()).thenReturn(2);
        when(extendMapper.selectList(any())).thenReturn(List.of());
        when(extendMapper.insert(any(OrderExtend.class))).thenAnswer(invocation -> {
            ((OrderExtend) invocation.getArgument(0)).setId(EXTEND_ID);
            return 1;
        });
        when(orderNodeMapper.selectList(any())).thenReturn(List.of(
                mainNode(1, APPLICANT_ID, SignType.ALL_SIGN),
                mainNode(2, HANDLER_ID, SignType.ALL_SIGN)));
        when(userMapper.selectById(APPLICANT_ID)).thenReturn(activeUser(APPLICANT_ID));
        when(userMapper.selectById(HANDLER_ID)).thenReturn(activeUser(HANDLER_ID));

        service.request(ORDER_ID, request(LocalDateTime.now().plusDays(3), "项目延期"));

        ArgumentCaptor<OrderExtendApprovalNode> nodeCaptor = ArgumentCaptor.forClass(OrderExtendApprovalNode.class);
        verify(extendNodeMapper, org.mockito.Mockito.times(2)).insert(nodeCaptor.capture());
        List<OrderExtendApprovalNode> inserted = nodeCaptor.getAllValues();
        OrderExtendApprovalNode applicantNode = inserted.stream()
                .filter(n -> n.getApproverId().equals(APPLICANT_ID)).findFirst().orElseThrow();
        assertEquals(ApprovalNodeStatus.SKIPPED.name(), applicantNode.getStatus(),
                "申请人本人作为审批人的节点应被跳过");
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("发起延期：主单无审批链路时延期免审批直接生效（规范 §15 同语义）")
    void request_mainOrderNoNodes_appliesDirectly() {
        loginAs(APPLICANT_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), LocalDateTime.now().plusDays(1)));
        when(systemConfigService.extendMaxCount()).thenReturn(2);
        when(extendMapper.selectList(any())).thenReturn(List.of());
        when(extendMapper.insert(any(OrderExtend.class))).thenAnswer(invocation -> {
            ((OrderExtend) invocation.getArgument(0)).setId(EXTEND_ID);
            return 1;
        });
        when(orderNodeMapper.selectList(any())).thenReturn(List.of());
        when(orderMapper.update(any(), any())).thenReturn(1);

        service.request(ORDER_ID, request(LocalDateTime.now().plusDays(3), "项目延期"));

        verify(extendNodeMapper, never()).insert(any(OrderExtendApprovalNode.class));
        verify(orderMapper).update(any(), any());
        verify(messageService).send(eq(APPLICANT_ID), eq(MessageType.EXTEND_RESULT), anyString(), anyString(), eq(ORDER_ID));
    }

    // ------------------------------------------------------------------
    // 延期审批
    // ------------------------------------------------------------------

    @Test
    @DisplayName("延期审批：非当前节点审批人被拒绝")
    void approve_notCurrentApprover_rejected() {
        loginAs(OTHER_ID, RoleCode.USER);
        when(extendMapper.selectById(EXTEND_ID)).thenReturn(extend(ExtendStatus.PENDING_APPROVAL.name(), null));
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), LocalDateTime.now().plusDays(1)));
        when(extendNodeMapper.selectList(any()))
                .thenReturn(List.of(extendNode(1L, 1, HANDLER_ID, SignType.ALL_SIGN, ApprovalNodeStatus.PENDING.name())));

        assertEquals(ErrorCode.APPROVER_NOT_CURRENT_NODE,
                errorCodeOf(() -> service.approve(EXTEND_ID, approveRequest(true, null))));
    }

    @Test
    @DisplayName("延期审批：本节点已被处理过被拒绝（不可重复提交）")
    void approve_nodeAlreadyHandled_rejected() {
        loginAs(HANDLER_ID, RoleCode.USER);
        when(extendMapper.selectById(EXTEND_ID)).thenReturn(extend(ExtendStatus.PENDING_APPROVAL.name(), null));
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), LocalDateTime.now().plusDays(1)));
        when(extendNodeMapper.selectList(any())).thenReturn(List.of(
                extendNode(1L, 1, OTHER_ID, SignType.ALL_SIGN, ApprovalNodeStatus.PENDING.name()),
                extendNode(2L, 1, HANDLER_ID, SignType.ALL_SIGN, ApprovalNodeStatus.APPROVED.name())));

        assertEquals(ErrorCode.APPROVAL_NODE_HANDLED,
                errorCodeOf(() -> service.approve(EXTEND_ID, approveRequest(true, null))));
    }

    @Test
    @DisplayName("延期审批：驳回未填原因被拒绝（规范 §13.1 驳回必填意见）")
    void approve_rejectWithoutComment_rejected() {
        loginAs(HANDLER_ID, RoleCode.USER);
        when(extendMapper.selectById(EXTEND_ID)).thenReturn(extend(ExtendStatus.PENDING_APPROVAL.name(), null));
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), LocalDateTime.now().plusDays(1)));
        when(extendNodeMapper.selectList(any()))
                .thenReturn(List.of(extendNode(1L, 1, HANDLER_ID, SignType.ALL_SIGN, ApprovalNodeStatus.PENDING.name())));

        assertEquals(ErrorCode.REJECT_COMMENT_REQUIRED,
                errorCodeOf(() -> service.approve(EXTEND_ID, approveRequest(false, "  "))));
    }

    @Test
    @DisplayName("延期审批：驳回后不触碰主单借用时间（规范 §17 驳回不影响原时间）")
    void approve_reject_leavesMainOrderUntouched() {
        loginAs(HANDLER_ID, RoleCode.USER);
        when(extendMapper.selectById(EXTEND_ID)).thenReturn(extend(ExtendStatus.PENDING_APPROVAL.name(), null));
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), LocalDateTime.now().plusDays(1)));
        when(extendNodeMapper.selectList(any()))
                .thenReturn(List.of(extendNode(1L, 1, HANDLER_ID, SignType.ALL_SIGN, ApprovalNodeStatus.PENDING.name())));

        service.approve(EXTEND_ID, approveRequest(false, "时间仍充裕，无需延期"));

        verify(orderMapper, never()).update(any(), any());
        verify(messageService).send(eq(APPLICANT_ID), eq(MessageType.EXTEND_RESULT), anyString(), anyString(), eq(ORDER_ID));
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("延期审批：末节点通过后回写主单计划结束时间并重置顺延计数")
    void approve_finalApproval_writesBackMainOrder() {
        loginAs(HANDLER_ID, RoleCode.USER);
        LocalDateTime newEnd = LocalDateTime.now().plusDays(5).withNano(0);
        when(extendMapper.selectById(EXTEND_ID)).thenReturn(extend(ExtendStatus.PENDING_APPROVAL.name(), newEnd));
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), LocalDateTime.now().plusDays(1)));
        // 第一次读取节点：待审批；动作后再读：已通过（无 PENDING → 判定链路走完）
        when(extendNodeMapper.selectList(any())).thenReturn(
                List.of(extendNode(1L, 1, HANDLER_ID, SignType.ALL_SIGN, ApprovalNodeStatus.PENDING.name())),
                List.of(extendNode(1L, 1, HANDLER_ID, SignType.ALL_SIGN, ApprovalNodeStatus.APPROVED.name())));
        when(orderMapper.update(any(), any())).thenReturn(1);

        service.approve(EXTEND_ID, approveRequest(true, "同意延期"));

        ArgumentCaptor<Wrapper<Order>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(orderMapper).update(any(), captor.capture());
        String sqlSet = ((LambdaUpdateWrapper<Order>) captor.getValue()).getSqlSet();
        assertTrue(sqlSet != null && sqlSet.contains("planned_end_time"),
                "应回写主单计划结束时间，实际 SQL：" + sqlSet);
        assertTrue(sqlSet.contains("auto_extend_count"), "应重置自动顺延计数，实际 SQL：" + sqlSet);
        verify(messageService).send(eq(APPLICANT_ID), eq(MessageType.EXTEND_RESULT), anyString(), anyString(), eq(ORDER_ID));
    }

    @Test
    @DisplayName("延期审批：主单状态已变化时条件更新 0 行 → 明确报错（不得静默生效）")
    void approve_mainOrderChanged_reportsConflict() {
        loginAs(HANDLER_ID, RoleCode.USER);
        when(extendMapper.selectById(EXTEND_ID)).thenReturn(extend(ExtendStatus.PENDING_APPROVAL.name(), null));
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), LocalDateTime.now().plusDays(1)));
        when(extendNodeMapper.selectList(any())).thenReturn(
                List.of(extendNode(1L, 1, HANDLER_ID, SignType.ALL_SIGN, ApprovalNodeStatus.PENDING.name())),
                List.of(extendNode(1L, 1, HANDLER_ID, SignType.ALL_SIGN, ApprovalNodeStatus.APPROVED.name())));
        when(orderMapper.update(any(), any())).thenReturn(0);

        assertEquals(ErrorCode.ORDER_STATUS_TRANSITION_INVALID,
                errorCodeOf(() -> service.approve(EXTEND_ID, approveRequest(true, "同意"))));
    }

    @Test
    @DisplayName("延期审批：延期单已处理完毕被拒绝（终态不可重复审批）")
    void approve_extendAlreadyFinal_rejected() {
        loginAs(HANDLER_ID, RoleCode.USER);
        when(extendMapper.selectById(EXTEND_ID)).thenReturn(extend(ExtendStatus.APPROVED.name(), null));

        assertEquals(ErrorCode.ORDER_STATUS_TRANSITION_INVALID,
                errorCodeOf(() -> service.approve(EXTEND_ID, approveRequest(true, null))));
    }

    // ------------------------------------------------------------------
    // 统计
    // ------------------------------------------------------------------

    @Test
    @DisplayName("延期统计：非驳回记录计入已用次数，其中审批中的同时标记 pending")
    void statOfOrder_countsNonRejected() {
        // 查询层已在 SQL 中排除 REJECTED，这里模拟「库中只剩通过 + 审批中」的结果
        when(extendMapper.selectList(any())).thenReturn(List.of(
                extend(ExtendStatus.APPROVED.name(), null),
                extend(ExtendStatus.PENDING_APPROVAL.name(), null)));

        OrderExtendService.ExtendStat stat = service.statOfOrder(ORDER_ID);

        assertEquals(2, stat.used());
        assertTrue(stat.pending());
    }

    @Test
    @DisplayName("延期统计：从无延期记录时返回 0 / 无审批中")
    void statOfOrder_whenNone_returnsZero() {
        when(extendMapper.selectList(any())).thenReturn(List.of());

        OrderExtendService.ExtendStat stat = service.statOfOrder(ORDER_ID);

        assertEquals(0, stat.used());
        assertFalse(stat.pending());
    }
}
