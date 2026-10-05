package com.enterprise.ticket.module.device.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.api.PageResult;
import com.enterprise.ticket.common.constant.DeviceStatus;
import com.enterprise.ticket.common.constant.FaultStatus;
import com.enterprise.ticket.common.constant.OrderStatus;
import com.enterprise.ticket.common.constant.RoleCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.device.dto.DeviceFaultHandleRequest;
import com.enterprise.ticket.module.device.dto.DeviceFaultQuery;
import com.enterprise.ticket.module.device.dto.DeviceFaultRequest;
import com.enterprise.ticket.module.device.dto.vo.DeviceFaultVO;
import com.enterprise.ticket.module.device.dto.vo.FaultDeviceOptionVO;
import com.enterprise.ticket.module.device.entity.Device;
import com.enterprise.ticket.module.device.entity.DeviceFault;
import com.enterprise.ticket.module.device.mapper.DeviceFaultMapper;
import com.enterprise.ticket.module.device.mapper.DeviceMapper;
import com.enterprise.ticket.module.order.entity.Order;
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

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 设备故障上报与处理单元测试（规范 §18，需求方 Phase 6）
 *
 * <p>重点覆盖三条上报路径的权限与状态边界，以及「设备状态与故障记录成对推进」：
 * <ul>
 *   <li>工单内上报：只有该单借用人或管理员可报，<b>设备保持「使用中」不变</b>；</li>
 *   <li>台账直接登记：仅管理员，设备「可用 → 维修中」；</li>
 *   <li>维修完成：设备「维修中 → 可用」；报废：使用中禁止，仅可用/维修中可报废；</li>
 *   <li>归还登记故障：同单已有待维修记录时不重复建档。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class DeviceFaultServiceImplTest {

    private static final Long DEVICE_ID = 11L;
    private static final Long OTHER_DEVICE_ID = 12L;
    private static final Long ORDER_ID = 100L;
    private static final Long FAULT_ID = 700L;
    private static final Long APPLICANT_ID = 2L;
    private static final Long ADMIN_ID = 3L;

    @BeforeAll
    static void initMyBatisLambdaCache() {
        MyBatisLambdaCache.init(DeviceFault.class, Device.class, Order.class, User.class);
    }

    @Mock
    private DeviceFaultMapper faultMapper;
    @Mock
    private DeviceMapper deviceMapper;
    @Mock
    private OrderMapper orderMapper;
    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private DeviceFaultServiceImpl service;

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

    private Device device(Long id, String status) {
        Device device = new Device();
        device.setId(id);
        device.setDeviceName("ThinkPad X1");
        device.setAssetNo("IT-2026-0001");
        device.setStatus(status);
        return device;
    }

    private Order order(String status, Long deviceId, Long applicantId) {
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setOrderNo("BO20260917-100");
        order.setDeviceId(deviceId);
        order.setApplicantId(applicantId);
        order.setStatus(status);
        return order;
    }

    private DeviceFault fault(String status, Long deviceId) {
        DeviceFault fault = new DeviceFault();
        fault.setId(FAULT_ID);
        fault.setDeviceId(deviceId);
        fault.setOrderId(ORDER_ID);
        fault.setStatus(status);
        return fault;
    }

    private DeviceFaultRequest reportRequest(Long deviceId, Long orderId, String desc, LocalDateTime occurredAt) {
        DeviceFaultRequest request = new DeviceFaultRequest();
        request.setDeviceId(deviceId);
        request.setOrderId(orderId);
        request.setFaultDescription(desc);
        request.setOccurredAt(occurredAt);
        return request;
    }

    private DeviceFaultHandleRequest handleRequest(String remark) {
        DeviceFaultHandleRequest request = new DeviceFaultHandleRequest();
        request.setRemark(remark);
        return request;
    }

    private static ErrorCode errorCodeOf(Runnable runnable) {
        return assertThrows(BusinessException.class, runnable::run).getErrorCode();
    }

    // ------------------------------------------------------------------
    // 工单内上报
    // ------------------------------------------------------------------

    @Test
    @DisplayName("工单内上报：借用人上报成功，设备保持「使用中」不被改动，且记录用户填报的发生时间")
    void report_withOrder_byApplicant_keepsDeviceInUseAndRecordsOccurredAt() {
        loginAs(APPLICANT_ID, RoleCode.USER);
        LocalDateTime occurred = LocalDateTime.now().minusHours(2).withNano(0);
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(DEVICE_ID, DeviceStatus.IN_USE.name()));
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), DEVICE_ID, APPLICANT_ID));
        when(faultMapper.insert(any(DeviceFault.class))).thenAnswer(invocation -> {
            ((DeviceFault) invocation.getArgument(0)).setId(FAULT_ID);
            return 1;
        });

        service.report(reportRequest(DEVICE_ID, ORDER_ID, "屏幕出现竖线", occurred));

        // 工单内上报绝不改变设备状态（去向由归还登记决定）
        verify(deviceMapper, never()).update(any(), any());
        ArgumentCaptor<DeviceFault> captor = ArgumentCaptor.forClass(DeviceFault.class);
        verify(faultMapper).insert(captor.capture());
        DeviceFault saved = captor.getValue();
        assertEquals(occurred, saved.getOccurredAt(), "应记录用户填报的故障发生时间，而非登记时刻");
        assertEquals(ORDER_ID, saved.getOrderId());
        assertEquals(FaultStatus.PENDING_REPAIR.name(), saved.getStatus());
    }

    @Test
    @DisplayName("工单内上报：非借用人（普通员工）被拒绝")
    void report_withOrder_byUnrelatedEmployee_rejected() {
        loginAs(999L, RoleCode.USER);
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(DEVICE_ID, DeviceStatus.IN_USE.name()));
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), DEVICE_ID, APPLICANT_ID));

        assertEquals(ErrorCode.FAULT_REPORTER_NOT_ALLOWED,
                errorCodeOf(() -> service.report(reportRequest(DEVICE_ID, ORDER_ID, "x", LocalDateTime.now()))));
        verify(faultMapper, never()).insert(any());
    }

    @Test
    @DisplayName("工单内上报：设备与工单不一致被拒绝")
    void report_withOrder_deviceMismatch_rejected() {
        loginAs(APPLICANT_ID, RoleCode.USER);
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(DEVICE_ID, DeviceStatus.IN_USE.name()));
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), OTHER_DEVICE_ID, APPLICANT_ID));

        assertEquals(ErrorCode.FAULT_ORDER_MISMATCH,
                errorCodeOf(() -> service.report(reportRequest(DEVICE_ID, ORDER_ID, "x", LocalDateTime.now()))));
    }

    @Test
    @DisplayName("工单内上报：工单非「使用中」被拒绝")
    void report_withOrder_statusNotBorrowed_rejected() {
        loginAs(APPLICANT_ID, RoleCode.USER);
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(DEVICE_ID, DeviceStatus.IN_USE.name()));
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.PENDING_RETURN.name(), DEVICE_ID, APPLICANT_ID));

        assertEquals(ErrorCode.FAULT_ORDER_MISMATCH,
                errorCodeOf(() -> service.report(reportRequest(DEVICE_ID, ORDER_ID, "x", LocalDateTime.now()))));
    }

    @Test
    @DisplayName("工单内上报：设备不处于「使用中」被拒绝（状态不一致）")
    void report_withOrder_deviceNotInUse_rejected() {
        loginAs(APPLICANT_ID, RoleCode.USER);
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(DEVICE_ID, DeviceStatus.AVAILABLE.name()));
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.BORROWED.name(), DEVICE_ID, APPLICANT_ID));

        assertEquals(ErrorCode.FAULT_DEVICE_STATE_INVALID,
                errorCodeOf(() -> service.report(reportRequest(DEVICE_ID, ORDER_ID, "x", LocalDateTime.now()))));
    }

    // ------------------------------------------------------------------
    // 台账直接登记
    // ------------------------------------------------------------------

    @Test
    @DisplayName("台账直接登记：管理员登记后设备「可用 → 维修中」，故障记录不关联工单")
    void report_ledger_byAdmin_movesAvailableToMaintenance() {
        loginAs(ADMIN_ID, RoleCode.ADMIN);
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(DEVICE_ID, DeviceStatus.AVAILABLE.name()));
        when(deviceMapper.update(any(), any())).thenReturn(1);
        when(faultMapper.insert(any(DeviceFault.class))).thenAnswer(invocation -> {
            ((DeviceFault) invocation.getArgument(0)).setId(FAULT_ID);
            return 1;
        });

        service.report(reportRequest(DEVICE_ID, null, "开不了机", LocalDateTime.now().minusHours(1)));

        verify(deviceMapper).update(any(), any());
        ArgumentCaptor<DeviceFault> captor = ArgumentCaptor.forClass(DeviceFault.class);
        verify(faultMapper).insert(captor.capture());
        assertNull(captor.getValue().getOrderId(), "台账登记不关联工单");
    }

    @Test
    @DisplayName("台账直接登记：普通员工被拒绝（无工单登记仅管理员）")
    void report_ledger_byEmployee_rejected() {
        loginAs(APPLICANT_ID, RoleCode.USER);
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(DEVICE_ID, DeviceStatus.AVAILABLE.name()));

        assertEquals(ErrorCode.FAULT_REPORTER_NOT_ALLOWED,
                errorCodeOf(() -> service.report(reportRequest(DEVICE_ID, null, "x", LocalDateTime.now()))));
        verify(deviceMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("台账直接登记：设备非「可用」被拒绝（使用中请从工单上报）")
    void report_ledger_deviceNotAvailable_rejected() {
        loginAs(ADMIN_ID, RoleCode.ADMIN);
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(DEVICE_ID, DeviceStatus.IN_USE.name()));

        assertEquals(ErrorCode.FAULT_DEVICE_STATE_INVALID,
                errorCodeOf(() -> service.report(reportRequest(DEVICE_ID, null, "x", LocalDateTime.now()))));
    }

    @Test
    @DisplayName("上报：故障发生时间晚于当前时间被拒绝")
    void report_occurredAtInFuture_rejected() {
        loginAs(ADMIN_ID, RoleCode.ADMIN);
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(DEVICE_ID, DeviceStatus.AVAILABLE.name()));

        assertEquals(ErrorCode.FAULT_OCCURRED_TIME_INVALID,
                errorCodeOf(() -> service.report(
                        reportRequest(DEVICE_ID, null, "x", LocalDateTime.now().plusDays(1)))));
    }

    // ------------------------------------------------------------------
    // 维修完成 / 报废
    // ------------------------------------------------------------------

    @Test
    @DisplayName("维修完成：设备「维修中 → 可用」并回写故障记录处理人")
    void markRepaired_movesMaintenanceToAvailable() {
        loginAs(ADMIN_ID, RoleCode.ADMIN);
        when(faultMapper.selectById(FAULT_ID)).thenReturn(fault(FaultStatus.PENDING_REPAIR.name(), DEVICE_ID));
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(DEVICE_ID, DeviceStatus.MAINTENANCE.name()));
        when(deviceMapper.update(any(), any())).thenReturn(1);

        service.markRepaired(FAULT_ID, handleRequest("更换屏幕后恢复正常"));

        verify(deviceMapper).update(any(), any());
        verify(faultMapper).update(any(), any());
    }

    @Test
    @DisplayName("维修完成：设备不处于「维修中」被拒绝")
    void markRepaired_deviceNotMaintenance_rejected() {
        loginAs(ADMIN_ID, RoleCode.ADMIN);
        when(faultMapper.selectById(FAULT_ID)).thenReturn(fault(FaultStatus.PENDING_REPAIR.name(), DEVICE_ID));
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(DEVICE_ID, DeviceStatus.AVAILABLE.name()));

        assertEquals(ErrorCode.FAULT_DEVICE_STATE_INVALID,
                errorCodeOf(() -> service.markRepaired(FAULT_ID, handleRequest(null))));
        verify(deviceMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("维修完成：故障记录已处理过被拒绝（不可重复处理）")
    void markRepaired_alreadyHandled_rejected() {
        loginAs(ADMIN_ID, RoleCode.ADMIN);
        when(faultMapper.selectById(FAULT_ID)).thenReturn(fault(FaultStatus.REPAIRED.name(), DEVICE_ID));

        assertEquals(ErrorCode.FAULT_ALREADY_HANDLED,
                errorCodeOf(() -> service.markRepaired(FAULT_ID, handleRequest(null))));
    }

    @Test
    @DisplayName("维修完成：非管理员被拒绝")
    void markRepaired_byNonAdmin_rejected() {
        loginAs(APPLICANT_ID, RoleCode.USER);

        assertEquals(ErrorCode.FORBIDDEN,
                errorCodeOf(() -> service.markRepaired(FAULT_ID, handleRequest(null))));
    }

    @Test
    @DisplayName("报废：设备使用中禁止报废（规范 §9）")
    void scrap_inUseDevice_rejected() {
        loginAs(ADMIN_ID, RoleCode.ADMIN);
        when(faultMapper.selectById(FAULT_ID)).thenReturn(fault(FaultStatus.PENDING_REPAIR.name(), DEVICE_ID));
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(DEVICE_ID, DeviceStatus.IN_USE.name()));

        assertEquals(ErrorCode.DEVICE_IN_BORROWED,
                errorCodeOf(() -> service.scrap(FAULT_ID, handleRequest("报废"))));
        verify(deviceMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("报废：维修中的设备可报废")
    void scrap_fromMaintenance_ok() {
        loginAs(ADMIN_ID, RoleCode.ADMIN);
        when(faultMapper.selectById(FAULT_ID)).thenReturn(fault(FaultStatus.PENDING_REPAIR.name(), DEVICE_ID));
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(DEVICE_ID, DeviceStatus.MAINTENANCE.name()));
        when(deviceMapper.update(any(), any())).thenReturn(1);

        service.scrap(FAULT_ID, handleRequest("主板损坏"));

        verify(deviceMapper).update(any(), any());
        verify(faultMapper).update(any(), any());
    }

    @Test
    @DisplayName("报废：已报废设备不可再次报废（状态机拒绝）")
    void scrap_alreadyScrappedDevice_rejected() {
        loginAs(ADMIN_ID, RoleCode.ADMIN);
        when(faultMapper.selectById(FAULT_ID)).thenReturn(fault(FaultStatus.PENDING_REPAIR.name(), DEVICE_ID));
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(DEVICE_ID, DeviceStatus.SCRAPPED.name()));

        assertEquals(ErrorCode.FAULT_DEVICE_STATE_INVALID,
                errorCodeOf(() -> service.scrap(FAULT_ID, handleRequest(null))));
    }

    // ------------------------------------------------------------------
    // 归还登记故障自动建档
    // ------------------------------------------------------------------

    @Test
    @DisplayName("归还登记故障：同单已有待维修记录时不重复建档")
    void recordReturnFault_dedupesExisting() {
        when(faultMapper.selectCount(any())).thenReturn(1L);

        Long id = service.recordReturnFault(DEVICE_ID, ORDER_ID, APPLICANT_ID, "屏幕碎裂", LocalDateTime.now());

        assertNull(id);
        verify(faultMapper, never()).insert(any());
    }

    @Test
    @DisplayName("归还登记故障：无待维修记录时自动建档")
    void recordReturnFault_createsWhenNone() {
        when(faultMapper.selectCount(any())).thenReturn(0L);
        when(faultMapper.insert(any(DeviceFault.class))).thenAnswer(invocation -> {
            ((DeviceFault) invocation.getArgument(0)).setId(FAULT_ID);
            return 1;
        });

        Long id = service.recordReturnFault(DEVICE_ID, ORDER_ID, APPLICANT_ID, "屏幕碎裂", LocalDateTime.now());

        assertEquals(FAULT_ID, id);
        ArgumentCaptor<DeviceFault> captor = ArgumentCaptor.forClass(DeviceFault.class);
        verify(faultMapper).insert(captor.capture());
        assertEquals(FaultStatus.PENDING_REPAIR.name(), captor.getValue().getStatus());
    }

    // ------------------------------------------------------------------
    // 查询权限
    // ------------------------------------------------------------------

    @Test
    @DisplayName("故障分页：非管理员被拒绝")
    void page_byNonAdmin_rejected() {
        loginAs(APPLICANT_ID, RoleCode.USER);

        assertEquals(ErrorCode.FORBIDDEN,
                errorCodeOf(() -> service.page(new DeviceFaultQuery())));
    }

    // ------------------------------------------------------------------
    // 查询视图装配（回归：待维修记录的 handledBy / orderId 可能为空，装配视图不得 NPE）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("故障分页：待维修记录（handledBy/orderId 为空）可正常装配视图，不抛 NPE")
    void page_pendingRepairRecord_nullableFields_assemblesVo() {
        loginAs(ADMIN_ID, RoleCode.ADMIN);
        // 复现线上 500：待维修记录既无处理人（handledBy=null），又无关联工单（orderId=null）
        DeviceFault pending = new DeviceFault();
        pending.setId(FAULT_ID);
        pending.setDeviceId(DEVICE_ID);
        pending.setOrderId(null);
        pending.setReporterId(ADMIN_ID);
        pending.setHandledBy(null);
        pending.setStatus(FaultStatus.PENDING_REPAIR.name());
        pending.setFaultDescription("开不了机");

        Page<DeviceFault> page = new Page<>(1, 10);
        page.setRecords(java.util.List.of(pending));
        page.setTotal(1);
        when(faultMapper.selectPage(any(), any())).thenReturn(page);
        when(deviceMapper.selectBatchIds(any()))
                .thenReturn(java.util.List.of(device(DEVICE_ID, DeviceStatus.MAINTENANCE.name())));

        PageResult<DeviceFaultVO> result = service.page(new DeviceFaultQuery());

        assertEquals(1, result.getRecords().size());
        DeviceFaultVO vo = result.getRecords().get(0);
        assertEquals(FAULT_ID, vo.getId());
        assertNull(vo.getOrderId(), "台账登记无关联工单");
        assertNull(vo.getHandledByName(), "未处理记录没有处理人姓名");
        assertEquals(FaultStatus.labelOf(FaultStatus.PENDING_REPAIR.name()), vo.getStatusLabel());
    }

    @Test
    @DisplayName("设备故障历史：无关联工单的待维修记录可正常装配视图，不抛 NPE")
    void listByDevice_nullableFields_assemblesVo() {
        loginAs(ADMIN_ID, RoleCode.ADMIN);
        DeviceFault pending = new DeviceFault();
        pending.setId(FAULT_ID);
        pending.setDeviceId(DEVICE_ID);
        pending.setOrderId(null);
        pending.setReporterId(ADMIN_ID);
        pending.setHandledBy(null);
        pending.setStatus(FaultStatus.PENDING_REPAIR.name());

        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(DEVICE_ID, DeviceStatus.MAINTENANCE.name()));
        when(faultMapper.selectList(any())).thenReturn(java.util.List.of(pending));

        java.util.List<DeviceFaultVO> list = service.listByDevice(DEVICE_ID);

        assertEquals(1, list.size());
        assertEquals(FAULT_ID, list.get(0).getId());
        assertNull(list.get(0).getOrderId());
        assertNull(list.get(0).getHandledByName());
    }

    @Test
    @DisplayName("设备故障历史：带关联工单的记录正确回填工单号（回归：曾传空表导致 orderNo 恒为空）")
    void listByDevice_withOrder_backfillsOrderNo() {
        loginAs(ADMIN_ID, RoleCode.ADMIN);
        DeviceFault fault = new DeviceFault();
        fault.setId(FAULT_ID);
        fault.setDeviceId(DEVICE_ID);
        fault.setOrderId(ORDER_ID);
        fault.setReporterId(APPLICANT_ID);
        fault.setHandledBy(null);
        fault.setStatus(FaultStatus.PENDING_REPAIR.name());

        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(DEVICE_ID, DeviceStatus.MAINTENANCE.name()));
        when(deviceMapper.selectBatchIds(any()))
                .thenReturn(java.util.List.of(device(DEVICE_ID, DeviceStatus.MAINTENANCE.name())));
        when(faultMapper.selectList(any())).thenReturn(java.util.List.of(fault));
        when(orderMapper.selectBatchIds(any()))
                .thenReturn(java.util.List.of(order(OrderStatus.BORROWED.name(), DEVICE_ID, APPLICANT_ID)));

        java.util.List<DeviceFaultVO> list = service.listByDevice(DEVICE_ID);

        assertEquals(1, list.size());
        assertEquals(ORDER_ID, list.get(0).getOrderId());
        assertEquals("BO20260917-100", list.get(0).getOrderNo(), "应回填关联工单号");
    }

    // ------------------------------------------------------------------
    // 故障上报「可选设备」（需求二：故障报修表单设备选择）
    // ------------------------------------------------------------------

    private User user(Long id) {
        User user = new User();
        user.setId(id);
        user.setUsername("u" + id);
        user.setDisplayName("用户" + id);
        user.setEnabled(true);
        user.setDimission(false);
        return user;
    }

    @Test
    @DisplayName("可选设备：普通用户只返回自己使用中工单的设备，且带 orderId（工单内上报路径）")
    void selectableDevices_employee_returnsOwnBorrowedDevices() {
        loginAs(APPLICANT_ID, RoleCode.USER);
        when(orderMapper.selectList(any()))
                .thenReturn(java.util.List.of(order(OrderStatus.BORROWED.name(), DEVICE_ID, APPLICANT_ID)));
        when(deviceMapper.selectBatchIds(any()))
                .thenReturn(java.util.List.of(device(DEVICE_ID, DeviceStatus.IN_USE.name())));
        when(userMapper.selectBatchIds(any()))
                .thenReturn(java.util.List.of(user(APPLICANT_ID)));

        java.util.List<FaultDeviceOptionVO> options = service.selectableDevices();

        assertEquals(1, options.size());
        FaultDeviceOptionVO vo = options.get(0);
        assertEquals(DEVICE_ID, vo.getDeviceId());
        assertEquals(ORDER_ID, vo.getOrderId(), "工单路径必须带 orderId");
        assertEquals("BO20260917-100", vo.getOrderNo());
        // 普通用户不查「可用设备」（无工单直登路径仅管理员可用）
        verify(deviceMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("可选设备：普通用户没有使用中工单时返回空列表")
    void selectableDevices_employee_noOrders_empty() {
        loginAs(APPLICANT_ID, RoleCode.USER);
        when(orderMapper.selectList(any())).thenReturn(java.util.List.of());

        assertEquals(0, service.selectableDevices().size());
    }

    @Test
    @DisplayName("可选设备：管理员返回「使用中工单设备（带工单）+ 可用设备（无工单）」两类")
    void selectableDevices_admin_includesAvailableAndBorrowed() {
        loginAs(ADMIN_ID, RoleCode.ADMIN);
        when(orderMapper.selectList(any()))
                .thenReturn(java.util.List.of(order(OrderStatus.BORROWED.name(), DEVICE_ID, APPLICANT_ID)));
        when(deviceMapper.selectBatchIds(any()))
                .thenReturn(java.util.List.of(device(DEVICE_ID, DeviceStatus.IN_USE.name())));
        when(deviceMapper.selectList(any()))
                .thenReturn(java.util.List.of(device(OTHER_DEVICE_ID, DeviceStatus.AVAILABLE.name())));
        when(userMapper.selectBatchIds(any()))
                .thenReturn(java.util.List.of(user(APPLICANT_ID)));

        java.util.List<FaultDeviceOptionVO> options = service.selectableDevices();

        assertEquals(2, options.size());
        assertEquals(ORDER_ID, options.get(0).getOrderId(), "第一类来自使用中工单");
        assertNull(options.get(1).getOrderId(), "可用设备走台账直接登记，无 orderId");
        assertEquals(OTHER_DEVICE_ID, options.get(1).getDeviceId());
    }
}
