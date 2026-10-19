package com.enterprise.ticket.module.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.OrderStatus;
import com.enterprise.ticket.common.constant.RoleCode;
import com.enterprise.ticket.common.constant.TransferRequestStatus;
import com.enterprise.ticket.common.constant.TransferScope;
import com.enterprise.ticket.common.constant.TransferType;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.department.entity.Department;
import com.enterprise.ticket.module.department.mapper.DepartmentMapper;
import com.enterprise.ticket.module.department.service.DepartmentService;
import com.enterprise.ticket.module.message.entity.Message;
import com.enterprise.ticket.module.message.mapper.MessageMapper;
import com.enterprise.ticket.module.message.service.MessageService;
import com.enterprise.ticket.module.order.dto.TransferRequestCreateRequest;
import com.enterprise.ticket.module.order.dto.TransferRequestDecideRequest;
import com.enterprise.ticket.module.order.entity.Order;
import com.enterprise.ticket.module.order.entity.OrderApprovalNode;
import com.enterprise.ticket.module.order.entity.OrderHandlerTransfer;
import com.enterprise.ticket.module.order.entity.OrderHandlerTransferRequest;
import com.enterprise.ticket.module.order.mapper.OrderApprovalNodeMapper;
import com.enterprise.ticket.module.order.mapper.OrderHandlerTransferMapper;
import com.enterprise.ticket.module.order.mapper.OrderHandlerTransferRequestMapper;
import com.enterprise.ticket.module.order.mapper.OrderMapper;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 转单申请（同组 / 跨组，V55 / D5.7）单元测试。
 *
 * <h2>这段逻辑出错会怎样</h2>
 * <p>它既改「谁负责」，也改「哪个组负责」—— 越权发起或错误生效，
 * 都是**静默的责任错位**：没人报错，只是设备一直没人送、催还催到了不相干的人头上。
 * 因此这里把四类边界钉死：
 * <ol>
 *   <li><b>谁能发起</b>：同组=当前执行人；跨组=本组管理员（小组管理员是「部门主管」，
 *       不是系统角色 —— 用例里管理员用的是普通 {@code user} 角色）；</li>
 *   <li><b>谁能处理</b>：只有 {@code to_admin_id} 本人（或 super_admin）；</li>
 *   <li><b>生效范围</b>：同组只换人、跨组连 {@code handler_department_id} 一起换；</li>
 *   <li><b>并发与幂等</b>：同一工单只允许一条待处理申请；条件 UPDATE 命中 0 行必须整体失败。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class OrderTransferRequestServiceImplTest {

    private static final Long ORDER_ID = 100L;
    private static final Long APPLICANT_ID = 2L;
    private static final Long HANDLER_ID = 4L;
    private static final Long TARGET_ID = 5L;
    private static final Long GROUP_ID = 7L;
    private static final Long ADMIN_ID = 8L;
    private static final Long TARGET_GROUP_ID = 12L;
    private static final Long TARGET_GROUP_ADMIN_ID = 20L;
    private static final Long REQUEST_ID = 33L;

    @BeforeAll
    static void initMyBatisLambdaCache() {
        MyBatisLambdaCache.init(Order.class, OrderHandlerTransfer.class, OrderHandlerTransferRequest.class,
                OrderApprovalNode.class, Message.class, User.class, Department.class);
    }

    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderHandlerTransferMapper transferMapper;
    @Mock
    private OrderHandlerTransferRequestMapper requestMapper;
    @Mock
    private OrderApprovalNodeMapper orderNodeMapper;
    @Mock
    private DepartmentMapper departmentMapper;
    @Mock
    private DepartmentService departmentService;
    @Mock
    private UserMapper userMapper;
    @Mock
    private MessageMapper messageMapper;
    @Mock
    private MessageService messageService;

    @InjectMocks
    private OrderTransferRequestServiceImpl service;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------
    // 夹具
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

    /** 已登录用户的可显示姓名（通知文案与 VO 都会用到） */
    private void givenUsers(Long... ids) {
        for (Long id : ids) {
            User user = new User();
            user.setId(id);
            user.setUsername("u" + id);
            user.setDisplayName("用户" + id);
            user.setEnabled(true);
            user.setDimission(false);
            when(userMapper.selectById(id)).thenReturn(user);
        }
    }

    private Order order(String status, Long handlerId) {
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setOrderNo("BO-100");
        order.setApplicantId(APPLICANT_ID);
        order.setActualFinalHandlerId(handlerId);
        order.setHandlerDepartmentId(GROUP_ID);
        order.setStatus(status);
        return order;
    }

    private Department department(Long id, String name, boolean handlerGroup, boolean enabled) {
        Department d = new Department();
        d.setId(id);
        d.setDeptName(name);
        d.setHandlerGroup(handlerGroup);
        d.setStatus(enabled);
        return d;
    }

    private OrderHandlerTransferRequest pendingRow(String scope, Long toDepartmentId, Long toHandlerId,
                                                   Long toAdminId) {
        OrderHandlerTransferRequest row = new OrderHandlerTransferRequest();
        row.setId(REQUEST_ID);
        row.setOrderId(ORDER_ID);
        row.setTransferScope(scope);
        row.setFromDepartmentId(GROUP_ID);
        row.setToDepartmentId(toDepartmentId);
        row.setFromHandlerId(HANDLER_ID);
        row.setToHandlerId(toHandlerId);
        row.setToAdminId(toAdminId);
        row.setStatus(TransferRequestStatus.PENDING.name());
        row.setReason("岗位调整");
        row.setOperatorId(HANDLER_ID);
        return row;
    }

    private TransferRequestCreateRequest sameGroupRequest(Long targetId) {
        TransferRequestCreateRequest request = new TransferRequestCreateRequest();
        request.setScope(TransferScope.SAME_GROUP.name());
        request.setTargetHandlerId(targetId);
        request.setReason("出差交接");
        return request;
    }

    private TransferRequestCreateRequest crossGroupRequest(Long targetDepartmentId) {
        TransferRequestCreateRequest request = new TransferRequestCreateRequest();
        request.setScope(TransferScope.CROSS_GROUP.name());
        request.setTargetDepartmentId(targetDepartmentId);
        request.setReason("该组更熟悉此设备");
        return request;
    }

    private TransferRequestDecideRequest decideRequest(boolean accepted, Long targetHandlerId, String comment) {
        TransferRequestDecideRequest request = new TransferRequestDecideRequest();
        request.setAccepted(accepted);
        request.setTargetHandlerId(targetHandlerId);
        request.setComment(comment);
        return request;
    }

    private static ErrorCode errorCodeOf(Runnable action) {
        return assertThrows(BusinessException.class, action::run).getErrorCode();
    }

    private OrderHandlerTransferRequest capturedInsert() {
        ArgumentCaptor<OrderHandlerTransferRequest> captor =
                ArgumentCaptor.forClass(OrderHandlerTransferRequest.class);
        verify(requestMapper).insert(captor.capture());
        return captor.getValue();
    }

    // ------------------------------------------------------------------
    // 同组转单
    // ------------------------------------------------------------------

    @Test
    @DisplayName("同组：组员发起 → 挂起待本组管理员审批，工单不变")
    void sameGroupByMemberGoesPending() {
        loginAs(HANDLER_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), HANDLER_ID));
        when(requestMapper.selectCount(any())).thenReturn(0L);
        when(departmentService.activeMemberIdsOf(GROUP_ID)).thenReturn(List.of(HANDLER_ID, TARGET_ID));
        when(departmentService.primaryManagerOf(GROUP_ID)).thenReturn(ADMIN_ID);
        givenUsers(HANDLER_ID, TARGET_ID);

        service.create(ORDER_ID, sameGroupRequest(TARGET_ID));

        OrderHandlerTransferRequest row = capturedInsert();
        assertEquals(TransferScope.SAME_GROUP.name(), row.getTransferScope());
        assertEquals(TransferRequestStatus.PENDING.name(), row.getStatus());
        assertEquals(TARGET_ID, row.getToHandlerId());
        assertEquals(ADMIN_ID, row.getToAdminId());
        assertEquals(GROUP_ID, row.getToDepartmentId());
        // 挂起阶段绝不动工单
        verify(orderMapper, never()).update(any(), any());
        verify(messageService).send(eq(ADMIN_ID), any(), any(), any(), eq(ORDER_ID));
    }

    @Test
    @DisplayName("同组：发起人本人就是管理员 → 免审批当场生效（不产生自己的待审批）")
    void sameGroupByAdminTakesEffectImmediately() {
        loginAs(ADMIN_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), ADMIN_ID));
        when(requestMapper.selectCount(any())).thenReturn(0L);
        when(departmentService.activeMemberIdsOf(GROUP_ID)).thenReturn(List.of(ADMIN_ID, TARGET_ID));
        when(departmentService.primaryManagerOf(GROUP_ID)).thenReturn(ADMIN_ID);
        when(orderMapper.update(any(), any())).thenReturn(1);
        givenUsers(TARGET_ID);

        service.create(ORDER_ID, sameGroupRequest(TARGET_ID));

        OrderHandlerTransferRequest row = capturedInsert();
        assertEquals(TransferRequestStatus.ACCEPTED.name(), row.getStatus());
        assertEquals(ADMIN_ID, row.getDecideBy());
        ArgumentCaptor<OrderHandlerTransfer> history = ArgumentCaptor.forClass(OrderHandlerTransfer.class);
        verify(transferMapper).insert(history.capture());
        assertEquals(TransferType.MANUAL.name(), history.getValue().getTransferType());
        assertEquals(TARGET_ID, history.getValue().getNewHandlerId());
        verify(orderMapper).update(any(), any());
    }

    @Test
    @DisplayName("同组：非当前执行人发起 → TRANSFER_NOT_HANDLER")
    void sameGroupRejectsNonHandler() {
        loginAs(TARGET_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), HANDLER_ID));
        when(requestMapper.selectCount(any())).thenReturn(0L);

        assertEquals(ErrorCode.TRANSFER_NOT_HANDLER,
                errorCodeOf(() -> service.create(ORDER_ID, sameGroupRequest(TARGET_ID))));
    }

    @Test
    @DisplayName("同组：目标不在本组 → TRANSFER_TARGET_NOT_IN_GROUP")
    void sameGroupRejectsTargetOutsideGroup() {
        loginAs(HANDLER_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), HANDLER_ID));
        when(requestMapper.selectCount(any())).thenReturn(0L);
        when(userMapper.selectById(TARGET_ID)).thenReturn(displayUser(TARGET_ID));
        when(departmentService.activeMemberIdsOf(GROUP_ID)).thenReturn(List.of(HANDLER_ID));

        assertEquals(ErrorCode.TRANSFER_TARGET_NOT_IN_GROUP,
                errorCodeOf(() -> service.create(ORDER_ID, sameGroupRequest(TARGET_ID))));
    }

    @Test
    @DisplayName("同组：本组没配管理员 → 明确报错，而不是造一条永远没人批的申请")
    void sameGroupWithoutAdminRejected() {
        loginAs(HANDLER_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), HANDLER_ID));
        when(requestMapper.selectCount(any())).thenReturn(0L);
        when(userMapper.selectById(TARGET_ID)).thenReturn(displayUser(TARGET_ID));
        when(departmentService.activeMemberIdsOf(GROUP_ID)).thenReturn(List.of(HANDLER_ID, TARGET_ID));
        when(departmentService.primaryManagerOf(GROUP_ID)).thenReturn(null);

        assertEquals(ErrorCode.PARAM_INVALID,
                errorCodeOf(() -> service.create(ORDER_ID, sameGroupRequest(TARGET_ID))));
    }

    @Test
    @DisplayName("同一工单已有待处理申请 → 拒绝重复发起")
    void createRejectsWhenPendingExists() {
        loginAs(HANDLER_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), HANDLER_ID));
        when(requestMapper.selectCount(any())).thenReturn(1L);

        assertEquals(ErrorCode.PARAM_INVALID,
                errorCodeOf(() -> service.create(ORDER_ID, sameGroupRequest(TARGET_ID))));
    }

    // ------------------------------------------------------------------
    // 跨组转单
    // ------------------------------------------------------------------

    @Test
    @DisplayName("跨组：普通组员发起 → 403（跨组只能由本组管理员发起）")
    void crossGroupRequiresGroupAdmin() {
        loginAs(HANDLER_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), HANDLER_ID));
        when(requestMapper.selectCount(any())).thenReturn(0L);
        when(departmentService.managerIdsOf(GROUP_ID)).thenReturn(List.of(ADMIN_ID));

        assertEquals(ErrorCode.FORBIDDEN,
                errorCodeOf(() -> service.create(ORDER_ID, crossGroupRequest(TARGET_GROUP_ID))));
    }

    @Test
    @DisplayName("跨组：目标小组就是本组 → 参数错误（组内换人应走同组转单）")
    void crossGroupRejectsSameDepartment() {
        loginAs(ADMIN_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), HANDLER_ID));
        when(requestMapper.selectCount(any())).thenReturn(0L);
        when(departmentService.managerIdsOf(GROUP_ID)).thenReturn(List.of(ADMIN_ID));

        assertEquals(ErrorCode.PARAM_INVALID,
                errorCodeOf(() -> service.create(ORDER_ID, crossGroupRequest(GROUP_ID))));
    }

    @Test
    @DisplayName("跨组：目标小组没配管理员 → 目标无效（没人能接收）")
    void crossGroupRejectsGroupWithoutAdmin() {
        loginAs(ADMIN_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), HANDLER_ID));
        when(requestMapper.selectCount(any())).thenReturn(0L);
        when(departmentService.managerIdsOf(GROUP_ID)).thenReturn(List.of(ADMIN_ID));
        when(departmentMapper.selectById(TARGET_GROUP_ID))
                .thenReturn(department(TARGET_GROUP_ID, "上海处理组", true, true));
        when(departmentService.primaryManagerOf(TARGET_GROUP_ID)).thenReturn(null);

        assertEquals(ErrorCode.TRANSFER_TARGET_INVALID,
                errorCodeOf(() -> service.create(ORDER_ID, crossGroupRequest(TARGET_GROUP_ID))));
    }

    @Test
    @DisplayName("跨组：管理员发起 → 待目标组管理员接收，目标执行人留空")
    void crossGroupCreatesPendingForTargetAdmin() {
        loginAs(ADMIN_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), HANDLER_ID));
        when(requestMapper.selectCount(any())).thenReturn(0L);
        when(departmentService.managerIdsOf(GROUP_ID)).thenReturn(List.of(ADMIN_ID));
        when(departmentMapper.selectById(TARGET_GROUP_ID))
                .thenReturn(department(TARGET_GROUP_ID, "上海处理组", true, true));
        when(departmentService.primaryManagerOf(TARGET_GROUP_ID)).thenReturn(TARGET_GROUP_ADMIN_ID);
        givenUsers(ADMIN_ID);

        service.create(ORDER_ID, crossGroupRequest(TARGET_GROUP_ID));

        OrderHandlerTransferRequest row = capturedInsert();
        assertEquals(TransferScope.CROSS_GROUP.name(), row.getTransferScope());
        assertEquals(TransferRequestStatus.PENDING.name(), row.getStatus());
        assertEquals(TARGET_GROUP_ID, row.getToDepartmentId());
        assertEquals(TARGET_GROUP_ADMIN_ID, row.getToAdminId());
        // 派谁由对方组长决定，发起时不能填
        assertNull(row.getToHandlerId());
        verify(messageService).send(eq(TARGET_GROUP_ADMIN_ID), any(), any(), any(), eq(ORDER_ID));
    }

    // ------------------------------------------------------------------
    // 处理申请
    // ------------------------------------------------------------------

    @Test
    @DisplayName("处理：不是待处理人 → 403")
    void decideRequiresTargetAdmin() {
        loginAs(HANDLER_ID, RoleCode.USER);
        when(requestMapper.selectById(REQUEST_ID))
                .thenReturn(pendingRow(TransferScope.SAME_GROUP.name(), GROUP_ID, TARGET_ID, ADMIN_ID));

        assertEquals(ErrorCode.FORBIDDEN,
                errorCodeOf(() -> service.decide(REQUEST_ID, decideRequest(true, null, null))));
    }

    @Test
    @DisplayName("处理：申请已处理过 → 参数错误（不能重复生效）")
    void decideRejectsNonPending() {
        loginAs(ADMIN_ID, RoleCode.USER);
        OrderHandlerTransferRequest row = pendingRow(TransferScope.SAME_GROUP.name(), GROUP_ID, TARGET_ID, ADMIN_ID);
        row.setStatus(TransferRequestStatus.ACCEPTED.name());
        when(requestMapper.selectById(REQUEST_ID)).thenReturn(row);

        assertEquals(ErrorCode.PARAM_INVALID,
                errorCodeOf(() -> service.decide(REQUEST_ID, decideRequest(true, null, null))));
    }

    @Test
    @DisplayName("处理：同组审批通过 → 换执行人 + 写人工转交历史")
    void decideSameGroupApproved() {
        loginAs(ADMIN_ID, RoleCode.USER);
        when(requestMapper.selectById(REQUEST_ID))
                .thenReturn(pendingRow(TransferScope.SAME_GROUP.name(), GROUP_ID, TARGET_ID, ADMIN_ID));
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), HANDLER_ID));
        when(orderMapper.update(any(), any())).thenReturn(1);
        givenUsers(TARGET_ID);

        service.decide(REQUEST_ID, decideRequest(true, null, "同意"));

        ArgumentCaptor<OrderHandlerTransferRequest> rowCaptor =
                ArgumentCaptor.forClass(OrderHandlerTransferRequest.class);
        verify(requestMapper).updateById(rowCaptor.capture());
        assertEquals(TransferRequestStatus.ACCEPTED.name(), rowCaptor.getValue().getStatus());
        assertEquals(TARGET_ID, rowCaptor.getValue().getToHandlerId());

        ArgumentCaptor<OrderHandlerTransfer> history = ArgumentCaptor.forClass(OrderHandlerTransfer.class);
        verify(transferMapper).insert(history.capture());
        assertEquals(TransferType.MANUAL.name(), history.getValue().getTransferType());
        assertEquals(HANDLER_ID, history.getValue().getOldHandlerId());
        assertEquals(TARGET_ID, history.getValue().getNewHandlerId());
        assertEquals(ADMIN_ID, history.getValue().getTransferOperatorId());
    }

    @Test
    @DisplayName("处理：跨组接收未指定本组执行人 → 拒绝（否则会出现无人负责的中间态）")
    void decideCrossGroupRequiresTargetHandler() {
        loginAs(TARGET_GROUP_ADMIN_ID, RoleCode.USER);
        when(requestMapper.selectById(REQUEST_ID)).thenReturn(
                pendingRow(TransferScope.CROSS_GROUP.name(), TARGET_GROUP_ID, null, TARGET_GROUP_ADMIN_ID));
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), HANDLER_ID));

        assertEquals(ErrorCode.TRANSFER_TARGET_INVALID,
                errorCodeOf(() -> service.decide(REQUEST_ID, decideRequest(true, null, "已收到"))));
    }

    @Test
    @DisplayName("处理：跨组接收 → 执行人与处理小组一起换，历史记为跨组转单")
    void decideCrossGroupAcceptMovesGroup() {
        loginAs(TARGET_GROUP_ADMIN_ID, RoleCode.USER);
        when(requestMapper.selectById(REQUEST_ID)).thenReturn(
                pendingRow(TransferScope.CROSS_GROUP.name(), TARGET_GROUP_ID, null, TARGET_GROUP_ADMIN_ID));
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), HANDLER_ID));
        when(departmentService.activeMemberIdsOf(TARGET_GROUP_ID)).thenReturn(List.of(TARGET_ID));
        when(orderMapper.update(any(), any())).thenReturn(1);
        when(departmentMapper.selectById(TARGET_GROUP_ID))
                .thenReturn(department(TARGET_GROUP_ID, "上海处理组", true, true));
        givenUsers(TARGET_ID, TARGET_GROUP_ADMIN_ID);

        service.decide(REQUEST_ID, decideRequest(true, TARGET_ID, "已安排张三跟进"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<Order>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(orderMapper).update(any(), wrapperCaptor.capture());
        String setSql = ((LambdaUpdateWrapper<?>) wrapperCaptor.getValue()).getSqlSet();
        // 关键：跨组必须同时改小组，否则"人换了组没换"，后续派工/统计仍算在原组
        assertTrue(setSql.contains("handler_department_id"), "跨组生效必须同时更新处理小组：" + setSql);

        ArgumentCaptor<OrderHandlerTransfer> history = ArgumentCaptor.forClass(OrderHandlerTransfer.class);
        verify(transferMapper).insert(history.capture());
        assertEquals(TransferType.CROSS_GROUP.name(), history.getValue().getTransferType());
    }

    @Test
    @DisplayName("处理：拒绝 → 状态拒绝退回，工单一个字段都不动（回流是天然的）")
    void decideRejectKeepsOrderUntouched() {
        loginAs(TARGET_GROUP_ADMIN_ID, RoleCode.USER);
        when(requestMapper.selectById(REQUEST_ID)).thenReturn(
                pendingRow(TransferScope.CROSS_GROUP.name(), TARGET_GROUP_ID, null, TARGET_GROUP_ADMIN_ID));
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), HANDLER_ID));
        givenUsers(TARGET_GROUP_ADMIN_ID);

        service.decide(REQUEST_ID, decideRequest(false, null, "该设备不在本组职责范围"));

        ArgumentCaptor<OrderHandlerTransferRequest> rowCaptor =
                ArgumentCaptor.forClass(OrderHandlerTransferRequest.class);
        verify(requestMapper).updateById(rowCaptor.capture());
        assertEquals(TransferRequestStatus.REJECTED.name(), rowCaptor.getValue().getStatus());
        assertEquals("该设备不在本组职责范围", rowCaptor.getValue().getDecideComment());
        verify(orderMapper, never()).update(any(), any());
        verify(transferMapper, never()).insert(any(OrderHandlerTransfer.class));
        // 发起人（原组管理员）必须收到"被拒绝"的通知，否则他会一直等
        verify(messageService).send(eq(HANDLER_ID), any(), any(), any(), eq(ORDER_ID));
    }

    @Test
    @DisplayName("处理：工单在挂起期间已变化 → TRANSFER_CONFLICT 整体失败")
    void decideConflictsWhenOrderChanged() {
        loginAs(ADMIN_ID, RoleCode.USER);
        when(requestMapper.selectById(REQUEST_ID))
                .thenReturn(pendingRow(TransferScope.SAME_GROUP.name(), GROUP_ID, TARGET_ID, ADMIN_ID));
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), HANDLER_ID));
        // 条件 UPDATE 命中 0 行：工单已被归还 / 撤回 / 他人转交
        when(orderMapper.update(any(), any())).thenReturn(0);

        assertEquals(ErrorCode.TRANSFER_CONFLICT,
                errorCodeOf(() -> service.decide(REQUEST_ID, decideRequest(true, null, "同意"))));
        // 冲突时不能写历史，否则时间线会凭空多出一条没发生过的转交
        verify(transferMapper, never()).insert(any(OrderHandlerTransfer.class));
    }

    private User displayUser(Long id) {
        User user = new User();
        user.setId(id);
        user.setUsername("u" + id);
        user.setDisplayName("用户" + id);
        user.setEnabled(true);
        user.setDimission(false);
        return user;
    }
}
