package com.enterprise.ticket.module.order.service.impl;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.MessageType;
import com.enterprise.ticket.common.constant.OrderStatus;
import com.enterprise.ticket.common.constant.RoleCode;
import com.enterprise.ticket.common.constant.TransferType;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.department.entity.UserDepartment;
import com.enterprise.ticket.module.department.mapper.UserDepartmentMapper;
import com.enterprise.ticket.module.message.entity.Message;
import com.enterprise.ticket.module.message.mapper.MessageMapper;
import com.enterprise.ticket.module.message.service.MessageService;
import com.enterprise.ticket.module.order.dto.OrderTransferRequest;
import com.enterprise.ticket.module.order.dto.vo.TransferCandidateVO;
import com.enterprise.ticket.module.order.entity.Order;
import com.enterprise.ticket.module.order.entity.OrderApprovalNode;
import com.enterprise.ticket.module.order.entity.OrderHandlerTransfer;
import com.enterprise.ticket.module.order.mapper.OrderApprovalNodeMapper;
import com.enterprise.ticket.module.order.mapper.OrderHandlerTransferMapper;
import com.enterprise.ticket.module.order.mapper.OrderMapper;
import com.enterprise.ticket.module.order.service.OrderTransferService;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工单转交单元测试（规范 §16.4 + 需求方 Phase 7 需求一）
 *
 * <p>转交是本阶段风险最高的写操作：它改的是「谁对这台设备负责」，一旦越权或改错人，
 * 责任归属就乱了。因此测试锁定四条边界：
 * <ul>
 *   <li><b>权限边界</b>：只有当前执行人（或 super_admin）能转；不能转给申请人自己 / 转给自己；</li>
 *   <li><b>状态边界</b>：只允许 PENDING_DELIVERY / BORROWED / PENDING_RETURN，终态拒绝；</li>
 *   <li><b>小组边界</b>：普通组员限同组在职成员，super_admin 跨组；</li>
 *   <li><b>并发边界</b>：条件 UPDATE 命中 0 行抛 TRANSFER_CONFLICT，绝不写入「历史与实际不符」的记录。</li>
 * </ul>
 * <p>另覆盖离职自动转交的「负载最少优先」策略（含 0 单候选人必须被优先选中的回归）。
 */
@ExtendWith(MockitoExtension.class)
class OrderTransferServiceImplTest {

    private static final Long ORDER_ID = 100L;
    private static final Long DEVICE_ID = 11L;
    private static final Long APPLICANT_ID = 2L;
    private static final Long HANDLER_ID = 4L;
    private static final Long NEW_HANDLER_ID = 5L;
    private static final Long SUPER_ADMIN_ID = 1L;
    private static final Long GROUP_ID = 7L;

    @BeforeAll
    static void initMyBatisLambdaCache() {
        MyBatisLambdaCache.init(Order.class, OrderHandlerTransfer.class, OrderApprovalNode.class,
                UserDepartment.class, Message.class, User.class);
    }

    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderHandlerTransferMapper transferMapper;
    @Mock
    private OrderApprovalNodeMapper orderNodeMapper;
    @Mock
    private UserDepartmentMapper userDepartmentMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private MessageMapper messageMapper;
    @Mock
    private MessageService messageService;

    @InjectMocks
    private OrderTransferServiceImpl service;

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
        order.setHandlerDepartmentId(GROUP_ID);
        order.setStatus(status);
        return order;
    }

    /** 仅用于「负载统计」查询结果的行（只关心 actualFinalHandlerId） */
    private Order loadOwner(Long handlerId) {
        Order order = new Order();
        order.setActualFinalHandlerId(handlerId);
        return order;
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

    private UserDepartment member(Long departmentId, Long userId) {
        UserDepartment member = new UserDepartment();
        member.setId(userId);
        member.setDepartmentId(departmentId);
        member.setUserId(userId);
        return member;
    }

    private OrderHandlerTransfer transferRecord(Long orderId) {
        OrderHandlerTransfer record = new OrderHandlerTransfer();
        record.setOrderId(orderId);
        return record;
    }

    private OrderTransferRequest transferRequest(Long newHandlerId) {
        OrderTransferRequest request = new OrderTransferRequest();
        request.setNewHandlerId(newHandlerId);
        request.setComment("岗位调整，后续由你跟进");
        return request;
    }

    private static ErrorCode errorCodeOf(Runnable runnable) {
        return assertThrows(BusinessException.class, runnable::run).getErrorCode();
    }

    /** 让「转交成功」的公共前置条件就位（校验全过、条件 UPDATE 命中 1 行） */
    private void givenSuccessfulTransferPreconditions(String role, Long actorId) {
        loginAs(actorId, role);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), HANDLER_ID));
        when(userMapper.selectById(NEW_HANDLER_ID)).thenReturn(user(NEW_HANDLER_ID, true, false));
        when(orderMapper.update(any(), any())).thenReturn(1);
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(
                user(HANDLER_ID, true, false), user(NEW_HANDLER_ID, true, false)));
        if (RoleCode.USER.equals(role)) {
            when(userDepartmentMapper.selectList(any())).thenReturn(List.of(
                    member(GROUP_ID, HANDLER_ID), member(GROUP_ID, NEW_HANDLER_ID)));
        }
    }

    // ------------------------------------------------------------------
    // 人工转交：成功路径
    // ------------------------------------------------------------------

    @Test
    @DisplayName("转交成功：更新执行人 + 写历史（人工转交）+ 通知新旧执行人 + 标记原待办")
    void transfer_success() {
        givenSuccessfulTransferPreconditions(RoleCode.USER, HANDLER_ID);

        service.transfer(ORDER_ID, transferRequest(NEW_HANDLER_ID));

        ArgumentCaptor<OrderHandlerTransfer> captor = ArgumentCaptor.forClass(OrderHandlerTransfer.class);
        verify(transferMapper).insert(captor.capture());
        OrderHandlerTransfer record = captor.getValue();
        assertEquals(ORDER_ID, record.getOrderId());
        assertEquals(HANDLER_ID, record.getOldHandlerId());
        assertEquals(NEW_HANDLER_ID, record.getNewHandlerId());
        assertEquals(HANDLER_ID, record.getTransferOperatorId());
        assertEquals("岗位调整，后续由你跟进", record.getTransferComment());
        assertEquals(TransferType.MANUAL.name(), record.getTransferType());

        verify(orderMapper).update(any(), any());
        verify(messageMapper).update(any(), any());
        verify(messageService).send(eq(NEW_HANDLER_ID), eq(MessageType.ORDER_TRANSFERRED), any(), any(), eq(ORDER_ID));
        verify(messageService).send(eq(HANDLER_ID), eq(MessageType.ORDER_TRANSFERRED), any(), any(), eq(ORDER_ID));
    }

    // ------------------------------------------------------------------
    // 人工转交：权限 / 状态 / 目标校验
    // ------------------------------------------------------------------

    @Test
    @DisplayName("转交拒绝：非当前执行人的普通组员 → TRANSFER_NOT_HANDLER")
    void transfer_notHandler_rejected() {
        loginAs(APPLICANT_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), HANDLER_ID));

        assertEquals(ErrorCode.TRANSFER_NOT_HANDLER,
                errorCodeOf(() -> service.transfer(ORDER_ID, transferRequest(NEW_HANDLER_ID))));
        verify(orderMapper, never()).update(any(), any());
        verify(transferMapper, never()).insert(any());
    }

    @Test
    @DisplayName("转交拒绝：工单为终态（已归还）→ TRANSFER_ORDER_STATUS_INVALID")
    void transfer_terminalStatus_rejected() {
        loginAs(HANDLER_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.RETURNED.name(), HANDLER_ID));

        assertEquals(ErrorCode.TRANSFER_ORDER_STATUS_INVALID,
                errorCodeOf(() -> service.transfer(ORDER_ID, transferRequest(NEW_HANDLER_ID))));
        verify(transferMapper, never()).insert(any());
    }

    @Test
    @DisplayName("转交拒绝：目标不在本工单最终处理部门 → TRANSFER_TARGET_NOT_IN_GROUP")
    void transfer_targetNotInGroup_rejected() {
        loginAs(HANDLER_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), HANDLER_ID));
        when(userMapper.selectById(NEW_HANDLER_ID)).thenReturn(user(NEW_HANDLER_ID, true, false));
        when(userDepartmentMapper.selectList(any())).thenReturn(List.of(member(GROUP_ID, HANDLER_ID)));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user(HANDLER_ID, true, false)));

        assertEquals(ErrorCode.TRANSFER_TARGET_NOT_IN_GROUP,
                errorCodeOf(() -> service.transfer(ORDER_ID, transferRequest(NEW_HANDLER_ID))));
        verify(transferMapper, never()).insert(any());
    }

    @Test
    @DisplayName("转交拒绝：目标为申请人本人 → TRANSFER_TARGET_INVALID（不能把活转回给需求方）")
    void transfer_targetIsApplicant_rejected() {
        loginAs(HANDLER_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), HANDLER_ID));
        when(userMapper.selectById(APPLICANT_ID)).thenReturn(user(APPLICANT_ID, true, false));

        assertEquals(ErrorCode.TRANSFER_TARGET_INVALID,
                errorCodeOf(() -> service.transfer(ORDER_ID, transferRequest(APPLICANT_ID))));
    }

    @Test
    @DisplayName("转交拒绝：目标就是当前执行人自己 → TRANSFER_TARGET_INVALID")
    void transfer_targetIsSelf_rejected() {
        loginAs(HANDLER_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), HANDLER_ID));
        when(userMapper.selectById(HANDLER_ID)).thenReturn(user(HANDLER_ID, true, false));

        assertEquals(ErrorCode.TRANSFER_TARGET_INVALID,
                errorCodeOf(() -> service.transfer(ORDER_ID, transferRequest(HANDLER_ID))));
    }

    @Test
    @DisplayName("转交拒绝：目标已停用/离职 → TRANSFER_TARGET_INVALID")
    void transfer_targetDisabled_rejected() {
        loginAs(HANDLER_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), HANDLER_ID));
        when(userMapper.selectById(NEW_HANDLER_ID)).thenReturn(user(NEW_HANDLER_ID, false, false));

        assertEquals(ErrorCode.TRANSFER_TARGET_INVALID,
                errorCodeOf(() -> service.transfer(ORDER_ID, transferRequest(NEW_HANDLER_ID))));
    }

    @Test
    @DisplayName("转交冲突：条件 UPDATE 命中 0 行（并发已被推进）→ TRANSFER_CONFLICT，不写历史")
    void transfer_concurrentConflict_rejected() {
        loginAs(HANDLER_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), HANDLER_ID));
        when(userMapper.selectById(NEW_HANDLER_ID)).thenReturn(user(NEW_HANDLER_ID, true, false));
        when(userDepartmentMapper.selectList(any())).thenReturn(List.of(
                member(GROUP_ID, HANDLER_ID), member(GROUP_ID, NEW_HANDLER_ID)));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(
                user(HANDLER_ID, true, false), user(NEW_HANDLER_ID, true, false)));
        when(orderMapper.update(any(), any())).thenReturn(0);

        assertEquals(ErrorCode.TRANSFER_CONFLICT,
                errorCodeOf(() -> service.transfer(ORDER_ID, transferRequest(NEW_HANDLER_ID))));
        verify(transferMapper, never()).insert(any());
        verify(messageService, never()).send(any(Long.class), any(), any(), any(), any());
    }

    @Test
    @DisplayName("super_admin 转交：不受小组限制（跨组可转），且不查询成员小组")
    void transfer_superAdmin_crossGroupAllowed() {
        givenSuccessfulTransferPreconditions(RoleCode.SUPER_ADMIN, SUPER_ADMIN_ID);
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(
                user(SUPER_ADMIN_ID, true, false), user(HANDLER_ID, true, false), user(NEW_HANDLER_ID, true, false)));

        service.transfer(ORDER_ID, transferRequest(NEW_HANDLER_ID));

        verify(transferMapper).insert(any());
        // 跨组可行性由「根本不查小组」证明
        verify(userDepartmentMapper, never()).selectList(any());
    }

    // ------------------------------------------------------------------
    // 转交候选
    // ------------------------------------------------------------------

    @Test
    @DisplayName("候选列表：排除当前执行人与申请人本人")
    void candidates_excludesSelfAndApplicant() {
        loginAs(HANDLER_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), HANDLER_ID));
        when(userDepartmentMapper.selectList(any())).thenReturn(List.of(
                member(GROUP_ID, HANDLER_ID), member(GROUP_ID, NEW_HANDLER_ID), member(GROUP_ID, APPLICANT_ID)));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(
                user(HANDLER_ID, true, false), user(NEW_HANDLER_ID, true, false), user(APPLICANT_ID, true, false)));
        when(orderMapper.selectList(any())).thenReturn(List.of());

        List<TransferCandidateVO> candidates = service.candidates(ORDER_ID);

        assertEquals(1, candidates.size());
        assertEquals(NEW_HANDLER_ID, candidates.get(0).getUserId());
        assertEquals(0, candidates.get(0).getInFlightCount());
    }

    // ------------------------------------------------------------------
    // 离职自动转交
    // ------------------------------------------------------------------

    @Test
    @DisplayName("离职自动转交：负载最少优先 —— 0 单候选人被选中（回归：不能选到有单的人）")
    void transferOnDimission_picksIdleCandidate() {
        Long leavingId = 8L;
        Long idleId = 5L;
        Long busyId = 6L;
        // 第 1 次 selectList = 离职者名下在办工单；第 2 次 = 候选人负载（只有 busyId 有单）
        when(orderMapper.selectList(any()))
                .thenReturn(List.of(order(OrderStatus.BORROWED.name(), leavingId)))
                .thenReturn(List.of(loadOwner(busyId)));
        when(userDepartmentMapper.selectList(any())).thenReturn(List.of(
                member(GROUP_ID, leavingId), member(GROUP_ID, idleId), member(GROUP_ID, busyId)));
        // 离职账号此时已被禁用，故 activeHandlerIds 只留 idleId / busyId
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(
                user(leavingId, false, false), user(idleId, true, false), user(busyId, true, false)));
        when(orderMapper.update(any(), any())).thenReturn(1);

        OrderTransferService.AutoTransferResult result = service.transferOnDimission(leavingId, "张伟");

        assertEquals(1, result.transferred());
        assertEquals(0, result.skipped());
        ArgumentCaptor<OrderHandlerTransfer> captor = ArgumentCaptor.forClass(OrderHandlerTransfer.class);
        verify(transferMapper).insert(captor.capture());
        assertEquals(leavingId, captor.getValue().getOldHandlerId());
        assertEquals(idleId, captor.getValue().getNewHandlerId());
        assertEquals(TransferType.AUTO_DIMISSION.name(), captor.getValue().getTransferType());
        verify(messageService).send(eq(idleId), eq(MessageType.ORDER_TRANSFERRED), any(), any(), eq(ORDER_ID));
    }

    @Test
    @DisplayName("离职自动转交：小组内无其他在职成员 → 不转交、不报错，计入 skipped")
    void transferOnDimission_noInGroupMember_skips() {
        Long leavingId = 8L;
        when(orderMapper.selectList(any()))
                .thenReturn(List.of(order(OrderStatus.BORROWED.name(), leavingId)));
        when(userDepartmentMapper.selectList(any())).thenReturn(List.of(member(GROUP_ID, leavingId)));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user(leavingId, false, false)));

        OrderTransferService.AutoTransferResult result = service.transferOnDimission(leavingId, "张伟");

        assertEquals(0, result.transferred());
        assertEquals(1, result.skipped());
        verify(orderMapper, never()).update(any(), any());
        verify(transferMapper, never()).insert(any());
    }

    @Test
    @DisplayName("离职自动转交：userId 为空 → 直接返回 (0,0)，不查库")
    void transferOnDimission_nullUser_noop() {
        OrderTransferService.AutoTransferResult result = service.transferOnDimission(null, "张伟");
        assertEquals(0, result.transferred());
        assertEquals(0, result.skipped());
        verify(orderMapper, never()).selectList(any());
    }

    // ------------------------------------------------------------------
    // 转交次数统计
    // ------------------------------------------------------------------

    @Test
    @DisplayName("转交次数统计：按工单聚合，一次查完")
    void transferCountByOrders_aggregates() {
        when(transferMapper.selectList(any())).thenReturn(List.of(
                transferRecord(ORDER_ID), transferRecord(ORDER_ID), transferRecord(200L)));

        Map<Long, Integer> counts = service.transferCountByOrders(List.of(ORDER_ID, 200L));

        assertEquals(2, counts.get(ORDER_ID));
        assertEquals(1, counts.get(200L));
        assertEquals(Map.of(), service.transferCountByOrders(List.of()));
    }
}
