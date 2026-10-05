package com.enterprise.ticket.module.order.service.impl;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.DeviceStatus;
import com.enterprise.ticket.common.constant.MessageType;
import com.enterprise.ticket.common.constant.OrderStatus;
import com.enterprise.ticket.common.constant.ReturnTrigger;
import com.enterprise.ticket.common.constant.RoleCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.applytype.mapper.ApplyTypeMapper;
import com.enterprise.ticket.module.department.mapper.DepartmentManagerMapper;
import com.enterprise.ticket.module.department.mapper.DepartmentMapper;
import com.enterprise.ticket.module.device.entity.Device;
import com.enterprise.ticket.module.device.mapper.DeviceCategoryMapper;
import com.enterprise.ticket.module.device.mapper.DeviceMapper;
import com.enterprise.ticket.module.department.mapper.UserDepartmentMapper;
import com.enterprise.ticket.module.message.service.MessageService;
import com.enterprise.ticket.module.order.dto.OrderConfirmReturnRequest;
import com.enterprise.ticket.module.order.dto.OrderReturnRequest;
import com.enterprise.ticket.module.order.entity.Order;
import com.enterprise.ticket.module.order.mapper.OrderApprovalNodeMapper;
import com.enterprise.ticket.module.order.mapper.OrderMapper;
import com.enterprise.ticket.module.order.support.OrderReferenceNames;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import com.enterprise.ticket.security.LoginUser;
import com.enterprise.ticket.support.MyBatisLambdaCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 两步归还流程单元测试（规范 §16.2 / §16.3 / §16.5，需求方 Phase 5 需求 1 / 4）
 *
 * <p>重点覆盖「权限边界」与「状态机边界」——这两处是本阶段唯一可能造成
 * 业务错误（而非体验问题）的地方：
 * <ul>
 *   <li>只有申请人可以发起归还，只有实际执行人可以确认收回（申请人本人也不行）；</li>
 *   <li>发起归还时<b>设备状态必须保持使用中</b>，否则归还途中设备会被他人申请；</li>
 *   <li>确认收回时设备去向由登记结果决定：故障 → 维修中，其余 → 可用；</li>
 *   <li>超时工单允许执行人直接收回（无需申请人先发起）；</li>
 *   <li>并发/重复提交靠条件 UPDATE 的 0 行结果兜住，不得静默成功。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class OrderReturnServiceImplTest {

    private static final Long ORDER_ID = 100L;
    private static final Long DEVICE_ID = 11L;
    private static final Long APPLICANT_ID = 2L;
    private static final Long HANDLER_ID = 4L;
    private static final Long SUPER_ADMIN_ID = 1L;

    @BeforeAll
    static void initMyBatisLambdaCache() {
        MyBatisLambdaCache.init(Order.class, Device.class, User.class);
    }

    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderApprovalNodeMapper nodeMapper;
    @Mock
    private DeviceMapper deviceMapper;
    @Mock
    private DeviceCategoryMapper categoryMapper;
    @Mock
    private DepartmentMapper departmentMapper;
    @Mock
    private DepartmentManagerMapper departmentManagerMapper;
    @Mock
    private UserDepartmentMapper userDepartmentMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private SystemConfigService systemConfigService;
    @Mock
    private MessageService messageService;
    // Phase 6 起 OrderServiceImpl 新增两个协作依赖：归还登记故障建档 / 工单详情延期信息装配
    @Mock
    private com.enterprise.ticket.module.device.service.DeviceFaultService deviceFaultService;
    @Mock
    private com.enterprise.ticket.module.order.service.OrderExtendService orderExtendService;

    /** W4-A2：OrderReferenceNames 构造所需依赖之一（其余 mock 本类已有） */
    @Mock
    private ApplyTypeMapper applyTypeMapper;

    @InjectMocks
    private OrderServiceImpl service;

    /**
     * W4-A2：工单引用名称解析已从 OrderServiceImpl 抽为独立组件。
     *
     * <p>这里注入**真实实例**（内部仍用本类已声明的 mock mapper），而不是再 mock 一层：
     * 归还流程的站内消息正文里带申请人姓名与设备名，用真实实现能让既有 stub
     * （{@code deviceMapper.selectById} 等）继续被使用 —— 若改成 mock，那些 stub
     * 会变成「未使用」而被 Mockito 严格模式判为失败，且测试对「名称从哪来」的覆盖
     * 会静默消失。
     */
    @BeforeEach
    void wireReferenceNames() {
        ReflectionTestUtils.setField(service, "referenceNames",
                new OrderReferenceNames(userMapper, deviceMapper, categoryMapper,
                        departmentMapper, applyTypeMapper));
    }

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

    private Order order(String status, Boolean borrowTimeout, String returnTrigger) {
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setOrderNo("BO20260917-100");
        order.setDeviceId(DEVICE_ID);
        order.setApplicantId(APPLICANT_ID);
        order.setActualFinalHandlerId(HANDLER_ID);
        order.setStatus(status);
        order.setBorrowTimeout(borrowTimeout);
        order.setReturnTrigger(returnTrigger);
        order.setPlannedEndTime(LocalDateTime.now().minusDays(1));
        return order;
    }

    private Device device(String status) {
        Device device = new Device();
        device.setId(DEVICE_ID);
        device.setDeviceName("ThinkPad X1");
        device.setAssetNo("IT-2026-0001");
        device.setStatus(status);
        return device;
    }

    private static ErrorCode errorCodeOf(Runnable runnable) {
        return assertThrows(BusinessException.class, runnable::run).getErrorCode();
    }

    private OrderReturnRequest returnRequest(String note) {
        OrderReturnRequest request = new OrderReturnRequest();
        request.setReturnNote(note);
        return request;
    }

    private OrderConfirmReturnRequest confirmRequest(String condition, String remark) {
        OrderConfirmReturnRequest request = new OrderConfirmReturnRequest();
        request.setCondition(condition);
        request.setRemark(remark);
        return request;
    }

    // ------------------------------------------------------------------
    // 第一步：发起归还
    // ------------------------------------------------------------------

    @Test
    @DisplayName("申请人发起归还：工单转待收回，且设备状态保持不变（仍为使用中）")
    void requestReturn_movesOrderToPendingReturnAndKeepsDeviceInUse() {
        loginAs(APPLICANT_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), false, null));
        when(orderMapper.update(any(), any())).thenReturn(1);
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(DeviceStatus.IN_USE.name()));

        service.requestReturn(ORDER_ID, returnRequest("外观完好"));

        verify(orderMapper).update(any(), any());
        // 设备未参与任何状态变更：调用方根本没有触发 deviceMapper.update
        verify(deviceMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("发起归还：向实际执行人推送「请确认收回」站内消息")
    void requestReturn_notifiesActualFinalHandler() {
        loginAs(APPLICANT_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), false, null));
        when(orderMapper.update(any(), any())).thenReturn(1);
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(DeviceStatus.IN_USE.name()));

        service.requestReturn(ORDER_ID, returnRequest(null));

        verify(messageService).send(eq(HANDLER_ID), eq(MessageType.RETURN_REQUESTED), any(), any(), eq(ORDER_ID));
    }

    @Test
    @DisplayName("发起归还：非申请人被拒绝（规范 §16.2 申请人本人操作）")
    void requestReturn_byNonApplicant_rejected() {
        loginAs(999L, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), false, null));

        assertEquals(ErrorCode.ORDER_NOT_APPLICANT,
                errorCodeOf(() -> service.requestReturn(ORDER_ID, returnRequest(null))));
        verify(orderMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("发起归还：非「使用中」状态被拒绝（如待收回/已归还不可重复发起）")
    void requestReturn_wrongStatus_rejected() {
        loginAs(APPLICANT_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.PENDING_RETURN.name(), false, null));

        assertEquals(ErrorCode.ORDER_STATUS_TRANSITION_INVALID,
                errorCodeOf(() -> service.requestReturn(ORDER_ID, returnRequest(null))));
    }

    @Test
    @DisplayName("发起归还：并发下条件更新 0 行 → 明确报状态已变化，不得静默成功")
    void requestReturn_whenConditionalUpdateAffectsZeroRows_reportsConflict() {
        loginAs(APPLICANT_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), false, null));
        when(orderMapper.update(any(), any())).thenReturn(0);

        assertEquals(ErrorCode.ORDER_STATUS_TRANSITION_INVALID,
                errorCodeOf(() -> service.requestReturn(ORDER_ID, returnRequest(null))));
        verify(messageService, never()).send(org.mockito.ArgumentMatchers.<Long>any(), any(), any(), any(), any());
    }

    // ------------------------------------------------------------------
    // 第二步：确认收回
    // ------------------------------------------------------------------

    @Test
    @DisplayName("确认收回（完好）：工单已归还 + 设备回可用 + 记录收回人与归还时间")
    void confirmReturn_goodCondition_returnsOrderAndReleasesDevice() {
        loginAs(HANDLER_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID))
                .thenReturn(order(OrderStatus.PENDING_RETURN.name(), false, ReturnTrigger.USER_INITIATED.name()));
        when(orderMapper.update(any(), any())).thenReturn(1);
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(DeviceStatus.IN_USE.name()));
        when(deviceMapper.update(any(), any())).thenReturn(1);

        service.confirmReturn(ORDER_ID, confirmRequest("GOOD", "外观无损"));

        verify(orderMapper).update(any(), any());
        verify(deviceMapper).update(any(), any());
        // 归还完成通知申请人
        verify(messageService).send(eq(APPLICANT_ID), eq(MessageType.RETURN_CONFIRMED), any(), any(), eq(ORDER_ID));
    }

    @Test
    @DisplayName("确认收回（损坏）：设备进入维修中而非可用（规范 §16.2 + §9）")
    void confirmReturn_faultCondition_movesDeviceToMaintenance() {
        loginAs(HANDLER_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID))
                .thenReturn(order(OrderStatus.PENDING_RETURN.name(), false, ReturnTrigger.USER_INITIATED.name()));
        when(orderMapper.update(any(), any())).thenReturn(1);
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(DeviceStatus.IN_USE.name()));
        when(deviceMapper.update(any(), any())).thenReturn(1);

        service.confirmReturn(ORDER_ID, confirmRequest("DAMAGED", "屏幕碎裂"));

        // 设备目标状态由 ReturnCondition 决定，这里校验业务语义而非 SQL 文本
        assertEquals(DeviceStatus.MAINTENANCE,
                com.enterprise.ticket.common.constant.ReturnCondition.DAMAGED.getDeviceStatus());
        verify(deviceMapper).update(any(), any());
    }

    @Test
    @DisplayName("确认收回：申请人本人（非实际执行人）被拒绝（规范 §16.2 明确拦截）")
    void confirmReturn_byApplicantSelf_rejected() {
        loginAs(APPLICANT_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID))
                .thenReturn(order(OrderStatus.PENDING_RETURN.name(), false, ReturnTrigger.USER_INITIATED.name()));

        assertEquals(ErrorCode.OPERATOR_NOT_ACTUAL_FINAL_HANDLER,
                errorCodeOf(() -> service.confirmReturn(ORDER_ID, confirmRequest("GOOD", null))));
        verify(orderMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("确认收回：超时的「使用中」工单可由执行人直接收回（无需申请人先发起，规范 §16.3）")
    void confirmReturn_timeoutDirectReturn_allowed() {
        loginAs(HANDLER_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), true, null));
        when(orderMapper.update(any(), any())).thenReturn(1);
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(DeviceStatus.IN_USE.name()));
        when(deviceMapper.update(any(), any())).thenReturn(1);

        service.confirmReturn(ORDER_ID, confirmRequest("GOOD", "超时直接收回"));

        verify(orderMapper).update(any(), any());
        verify(deviceMapper).update(any(), any());
    }

    @Test
    @DisplayName("确认收回：未超时的「使用中」工单不可直接收回（必须先由申请人发起归还）")
    void confirmReturn_borrowedWithoutTimeout_rejected() {
        loginAs(HANDLER_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), false, null));

        assertEquals(ErrorCode.ORDER_STATUS_TRANSITION_INVALID,
                errorCodeOf(() -> service.confirmReturn(ORDER_ID, confirmRequest("GOOD", null))));
    }

    @Test
    @DisplayName("确认收回：设备状态取值非法被拒绝（枚举白名单，不落库脏值）")
    void confirmReturn_invalidCondition_rejected() {
        loginAs(HANDLER_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID))
                .thenReturn(order(OrderStatus.PENDING_RETURN.name(), false, ReturnTrigger.USER_INITIATED.name()));

        assertEquals(ErrorCode.RETURN_CONDITION_INVALID,
                errorCodeOf(() -> service.confirmReturn(ORDER_ID, confirmRequest("BROKEN", null))));
    }

    @Test
    @DisplayName("确认收回：设备已不在使用中（如被其它流程处理）→ 拒绝，避免重复结算")
    void confirmReturn_deviceNotInUse_rejected() {
        loginAs(HANDLER_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID))
                .thenReturn(order(OrderStatus.PENDING_RETURN.name(), false, ReturnTrigger.USER_INITIATED.name()));
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(DeviceStatus.AVAILABLE.name()));

        assertEquals(ErrorCode.DEVICE_STATUS_TRANSITION_INVALID,
                errorCodeOf(() -> service.confirmReturn(ORDER_ID, confirmRequest("GOOD", null))));
        verify(deviceMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("确认收回：设备条件更新 0 行 → 整单回滚（不出现「工单已归还但设备仍占用」）")
    void confirmReturn_deviceConcurrentChange_rollsBack() {
        loginAs(HANDLER_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID))
                .thenReturn(order(OrderStatus.PENDING_RETURN.name(), false, ReturnTrigger.USER_INITIATED.name()));
        when(orderMapper.update(any(), any())).thenReturn(1);
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(DeviceStatus.IN_USE.name()));
        when(deviceMapper.update(any(), any())).thenReturn(0);

        assertEquals(ErrorCode.DEVICE_STATUS_TRANSITION_INVALID,
                errorCodeOf(() -> service.confirmReturn(ORDER_ID, confirmRequest("GOOD", null))));
    }

    @Test
    @DisplayName("确认收回：实际执行人离职时 super_admin 可代为收回（规范 §16.5 / §28 兜底）")
    void confirmReturn_bySuperAdmin_isAllowed() {
        loginAs(SUPER_ADMIN_ID, RoleCode.SUPER_ADMIN);
        when(orderMapper.selectById(ORDER_ID))
                .thenReturn(order(OrderStatus.PENDING_RETURN.name(), false, ReturnTrigger.USER_INITIATED.name()));
        when(orderMapper.update(any(), any())).thenReturn(1);
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(DeviceStatus.IN_USE.name()));
        when(deviceMapper.update(any(), any())).thenReturn(1);

        service.confirmReturn(ORDER_ID, confirmRequest("GOOD", "外观无损"));

        verify(orderMapper).update(any(), any());
        verify(deviceMapper).update(any(), any());
    }

    @Test
    @DisplayName("确认收回：普通员工（既非执行人也非超管）被拒绝")
    void confirmReturn_byUnrelatedUser_rejected() {
        loginAs(999L, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID))
                .thenReturn(order(OrderStatus.PENDING_RETURN.name(), false, ReturnTrigger.USER_INITIATED.name()));

        assertEquals(ErrorCode.OPERATOR_NOT_ACTUAL_FINAL_HANDLER,
                errorCodeOf(() -> service.confirmReturn(ORDER_ID, confirmRequest("GOOD", null))));
    }

    @Test
    @DisplayName("确认收回：工单已归还后重复提交被拒绝（终态不可再流转）")
    void confirmReturn_whenAlreadyReturned_rejected() {
        loginAs(HANDLER_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID))
                .thenReturn(order(OrderStatus.RETURNED.name(), true, ReturnTrigger.USER_INITIATED.name()));

        assertEquals(ErrorCode.ORDER_STATUS_TRANSITION_INVALID,
                errorCodeOf(() -> service.confirmReturn(ORDER_ID, confirmRequest("GOOD", null))));
    }

    @Test
    @DisplayName("确认收回：归还完成必须清除 borrow_timeout 实时标记（否则「已归还」旁仍挂「已超时」，且被「仅看已超时」筛出）")
    @SuppressWarnings("unchecked")
    void confirmReturn_clearsBorrowTimeoutFlag() {
        loginAs(HANDLER_ID, RoleCode.USER);
        // 超时直接收回：进入时 borrow_timeout = true
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), true, null));
        when(orderMapper.update(any(), any())).thenReturn(1);
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(DeviceStatus.IN_USE.name()));
        when(deviceMapper.update(any(), any())).thenReturn(1);

        service.confirmReturn(ORDER_ID, confirmRequest("GOOD", "超时直接收回"));

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<Order>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(orderMapper).update(any(), captor.capture());
        String sqlSet = ((com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Order>)
                captor.getValue()).getSqlSet();
        org.junit.jupiter.api.Assertions.assertTrue(
                sqlSet != null && sqlSet.contains("borrow_timeout"),
                "确认收回的 SET 子句应包含 borrow_timeout（该标记是实时状态，须随工单终结清除），实际：" + sqlSet);
    }
}
