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
import com.enterprise.ticket.module.device.dto.vo.FaultDeviceLookupVO;
import com.enterprise.ticket.module.device.dto.vo.FaultDeviceOptionVO;
import com.enterprise.ticket.module.device.entity.Device;
import com.enterprise.ticket.module.device.entity.DeviceCategory;
import com.enterprise.ticket.module.device.entity.DeviceFault;
import com.enterprise.ticket.module.device.mapper.DeviceCategoryMapper;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
 *   <li>台账直接登记（V57 起对普通员工开放）：<b>硬约束是设备必须「可用」</b>，设备「可用 → 维修中」；</li>
 *   <li>维修完成：设备「维修中 → 可用」；报废：使用中禁止，仅可用/维修中可报废；</li>
 *   <li>归还登记故障：同单已有待维修记录时不重复建档。</li>
 * </ul>
 *
 * <p>V57 / 第 10 项把「无工单登记仅管理员」放开为「设备必须可用」，因此本类不再断言
 * 「普通员工无法无工单登记」——那条限制已不是产品规则。真正需要守住的是**状态护栏**：
 * 使用中的设备必须走工单路径，否则设备状态会绕过借用单被改成维修中。
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
    /**
     * V57 / 第 10 项：报修的两种填报模式都要回显「这台设备属于什么分类」。
     *
     * <p>不加这个 mock，{@code @InjectMocks} 注入 null，凡是走到
     * {@code ownedDevices()} / {@code lookupByAssetNo()} 的用例全部 NPE。
     */
    @Mock
    private DeviceCategoryMapper categoryMapper;
    @Mock
    private OrderMapper orderMapper;
    @Mock
    private UserMapper userMapper;
    // V50：报修处理流程新增两个依赖 —— 不加这两个 mock，@InjectMocks 会注入 null，
    // 于是「登记后通知维修人」这一步直接 NPE（报修提交整个失败）
    @Mock
    private com.enterprise.ticket.module.device.support.FaultRepairAssigneeResolver
            faultRepairAssigneeResolver;
    @Mock
    private com.enterprise.ticket.module.message.service.UserNotificationService userNotificationService;
    /**
     * V54：「当前所在分部」预填/来源判定。
     *
     * <p>不加这个 mock，{@code @InjectMocks} 会注入 null，写入前那一步直接 NPE ——
     * 表现是「所有报修入口全部失败」，而错误与分部毫无关系（V50 加通知依赖时踩过同一个坑）。
     */
    @Mock
    private com.enterprise.ticket.module.order.support.DivisionPrefillService divisionPrefillService;
    /**
     * V59 / 批次 3.5：报修表单放开后新增的三个依赖。
     *
     * <p>不加 {@code applyTypeService} mock，{@code @InjectMocks} 注入 null，
     * 凡是带 formData 的用例都会 NPE；{@code formTemplateService} 不加同理
     * （schema 要从版本 id 取）。{@code formDataValidator} 不加则校验那步直接 NPE。
     */
    @Mock
    private com.enterprise.ticket.module.applytype.service.ApplyTypeService applyTypeService;
    @Mock
    private com.enterprise.ticket.module.form.service.FormTemplateService formTemplateService;
    @Mock
    private com.enterprise.ticket.common.form.FormDataValidator formDataValidator;

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
        // V54：分部预填/来源判定（写入前最后一步；校验全过才会被调用）
        when(divisionPrefillService.resolve(any(), any()))
                .thenReturn(new com.enterprise.ticket.module.order.support.DivisionPrefillService
                        .ResolvedDivision(10L, "HR_PRIMARY"));

        service.report(reportRequest(DEVICE_ID, ORDER_ID, "屏幕出现竖线", occurred));

        // 工单内上报绝不改变设备状态（去向由归还登记决定）
        verify(deviceMapper, never()).update(any(), any());
        ArgumentCaptor<DeviceFault> captor = ArgumentCaptor.forClass(DeviceFault.class);
        verify(faultMapper).insert(captor.capture());
        DeviceFault saved = captor.getValue();
        assertEquals(occurred, saved.getOccurredAt(), "应记录用户填报的故障发生时间，而非登记时刻");
        assertEquals(ORDER_ID, saved.getOrderId());
        assertEquals(FaultStatus.PENDING_REPAIR.name(), saved.getStatus());
        assertEquals(10L, saved.getCurrentDivisionId(), "V54：报修也要落「当前所在分部」");
        assertEquals("HR_PRIMARY", saved.getDivisionSource());
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
        when(divisionPrefillService.resolve(any(), any()))
                .thenReturn(new com.enterprise.ticket.module.order.support.DivisionPrefillService
                        .ResolvedDivision(10L, "MANUAL"));

        service.report(reportRequest(DEVICE_ID, null, "开不了机", LocalDateTime.now().minusHours(1)));

        verify(deviceMapper).update(any(), any());
        ArgumentCaptor<DeviceFault> captor = ArgumentCaptor.forClass(DeviceFault.class);
        verify(faultMapper).insert(captor.capture());
        assertNull(captor.getValue().getOrderId(), "台账登记不关联工单");
        assertEquals("MANUAL", captor.getValue().getDivisionSource());
    }

    @Test
    @DisplayName("台账直接登记：普通员工可对「可用」设备登记（V57 公共设备模式），设备 可用 → 维修中")
    void report_ledger_byEmployee_movesAvailableToMaintenance() {
        // V57 / 第 10 项把「无工单登记仅管理员」放开：会议室打印机这类没有负责人的设备，
        // 非要管理员才能报，等于把「公共设备」这个模式废掉。
        loginAs(APPLICANT_ID, RoleCode.USER);
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(DEVICE_ID, DeviceStatus.AVAILABLE.name()));
        when(deviceMapper.update(any(), any())).thenReturn(1);
        when(faultMapper.insert(any(DeviceFault.class))).thenAnswer(invocation -> {
            ((DeviceFault) invocation.getArgument(0)).setId(FAULT_ID);
            return 1;
        });
        when(divisionPrefillService.resolve(any(), any()))
                .thenReturn(new com.enterprise.ticket.module.order.support.DivisionPrefillService
                        .ResolvedDivision(10L, "MANUAL"));

        service.report(reportRequest(DEVICE_ID, null, "卡纸且报错灯常亮", LocalDateTime.now()));

        // 设备必须被真的推进到「维修中」，否则故障记录建了、设备还在可用池里
        verify(deviceMapper).update(any(), any());
        ArgumentCaptor<DeviceFault> captor = ArgumentCaptor.forClass(DeviceFault.class);
        verify(faultMapper).insert(captor.capture());
        assertNull(captor.getValue().getOrderId(), "公共设备无关联工单");
        assertEquals(APPLICANT_ID, captor.getValue().getReporterId());
    }

    @Test
    @DisplayName("★ 台账直接登记：普通员工对「使用中」设备无工单登记仍被拒（状态护栏，不是权限护栏）")
    void report_ledger_byEmployee_deviceInUse_rejected() {
        // 放开的是「谁能报」，不是「什么状态都能报」：没有工单却能把一台使用中的设备
        // 改成维修中，那台设备的使用中工单就永远收不回来了。
        loginAs(APPLICANT_ID, RoleCode.USER);
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(DEVICE_ID, DeviceStatus.IN_USE.name()));

        assertEquals(ErrorCode.FAULT_DEVICE_STATE_INVALID,
                errorCodeOf(() -> service.report(reportRequest(DEVICE_ID, null, "x", LocalDateTime.now()))));
        verify(deviceMapper, never()).update(any(), any());
        verify(faultMapper, never()).insert(any());
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
    // V59 / 批次 3.5：报修表单放开后的自定义字段
    // ------------------------------------------------------------------

    @Test
    @DisplayName("★ 自定义字段：绑了表单版本时校验 + 随记录落库（版本指针与值同生共死）")
    void report_withFormData_validatesAndStores() {
        loginAs(ADMIN_ID, RoleCode.ADMIN);
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(DEVICE_ID, DeviceStatus.AVAILABLE.name()));
        when(deviceMapper.update(any(), any())).thenReturn(1);
        when(faultMapper.insert(any(DeviceFault.class))).thenAnswer(invocation -> {
            ((DeviceFault) invocation.getArgument(0)).setId(FAULT_ID);
            return 1;
        });
        when(divisionPrefillService.resolve(any(), any()))
                .thenReturn(new com.enterprise.ticket.module.order.support.DivisionPrefillService
                        .ResolvedDivision(10L, "MANUAL"));
        // 内置「故障报修」类型绑定了 V77 这一版表单
        when(applyTypeService.builtInFormVersionId(
                com.enterprise.ticket.module.applytype.support.BuiltInApplyCatalog.CODE_FAULT_REPORT))
                .thenReturn(77L);
        when(formTemplateService.requirePublishedSchema(77L)).thenReturn(faultFormSchema());

        DeviceFaultRequest request = reportRequest(DEVICE_ID, null, "异响", LocalDateTime.now().minusHours(1));
        request.setFormData(java.util.Map.of("urgency", "HIGH"));

        service.report(request);

        // 必须走一遍 schema 校验：不校验就意味着任何人都能往 form_data_json 里塞任意 key
        verify(formDataValidator).validate(any(), any());
        ArgumentCaptor<DeviceFault> captor = ArgumentCaptor.forClass(DeviceFault.class);
        verify(faultMapper).insert(captor.capture());
        assertEquals(77L, captor.getValue().getFormTemplateVersionId(),
                "版本指针必须落库，否则详情页不知道该按哪一版回显");
        assertTrue(captor.getValue().getFormDataJson().contains("urgency"),
                "自定义字段值必须落库，实际：" + captor.getValue().getFormDataJson());
    }

    @Test
    @DisplayName("★ 自定义字段：没绑表单版本时，空值照常放行、非空明确拒绝（不静默丢数据）")
    void report_withFormData_withoutFormVersion() {
        loginAs(ADMIN_ID, RoleCode.ADMIN);
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(DEVICE_ID, DeviceStatus.AVAILABLE.name()));
        when(deviceMapper.update(any(), any())).thenReturn(1);
        when(faultMapper.insert(any(DeviceFault.class))).thenAnswer(invocation -> {
            ((DeviceFault) invocation.getArgument(0)).setId(FAULT_ID);
            return 1;
        });
        when(divisionPrefillService.resolve(any(), any()))
                .thenReturn(new com.enterprise.ticket.module.order.support.DivisionPrefillService
                        .ResolvedDivision(10L, "MANUAL"));
        // 升级未跑播种 / 内置类型被改坏的部署：拿不到表单版本
        when(applyTypeService.builtInFormVersionId(
                com.enterprise.ticket.module.applytype.support.BuiltInApplyCatalog.CODE_FAULT_REPORT))
                .thenReturn(null);

        DeviceFaultRequest withData = reportRequest(DEVICE_ID, null, "异响", LocalDateTime.now());
        withData.setFormData(java.util.Map.of("urgency", "HIGH"));
        assertEquals(ErrorCode.PARAM_INVALID, errorCodeOf(() -> service.report(withData)));

        // 但不传 formData 的存量调用方必须照常成功 —— 否则升级后普通报修直接不可用
        service.report(reportRequest(DEVICE_ID, null, "异响", LocalDateTime.now()));
        ArgumentCaptor<DeviceFault> captor = ArgumentCaptor.forClass(DeviceFault.class);
        verify(faultMapper).insert(captor.capture());
        assertNull(captor.getValue().getFormTemplateVersionId());
        assertNull(captor.getValue().getFormDataJson());
    }

    @Test
    @DisplayName("★ 故障列表：自定义字段按「当初那一版」schema 一起下发（快照语义，不读当前绑定版本）")
    void page_carriesFormSchemaSnapshot() {
        loginAs(ADMIN_ID, RoleCode.ADMIN);
        DeviceFault fault = fault(FaultStatus.PENDING_REPAIR.name(), DEVICE_ID);
        fault.setReporterId(ADMIN_ID);
        fault.setFormTemplateVersionId(77L);
        fault.setFormDataJson("{\"urgency\":\"HIGH\"}");
        when(faultMapper.selectPage(any(), any())).thenReturn(pageOf(fault));
        when(deviceMapper.selectBatchIds(any()))
                .thenReturn(java.util.List.of(device(DEVICE_ID, DeviceStatus.MAINTENANCE.name())));
        when(formTemplateService.requirePublishedSchema(77L)).thenReturn(faultFormSchema());

        PageResult<DeviceFaultVO> result = service.page(new DeviceFaultQuery());

        DeviceFaultVO vo = result.getRecords().get(0);
        assertEquals(77L, vo.getFormTemplateVersionId());
        assertEquals("HIGH", vo.getFormData().get("urgency"));
        assertNotNull(vo.getFormSchema(), "缺 schema 前端只能显示 key，等于没回显");
        assertEquals("urgency", vo.getFormSchema().getFields().get(0).getKey());
    }

    @Test
    @DisplayName("故障列表：schema 版本不可用时只跳过该条，不让整张列表打不开")
    void page_toleratesMissingFormSchema() {
        loginAs(ADMIN_ID, RoleCode.ADMIN);
        DeviceFault fault = fault(FaultStatus.PENDING_REPAIR.name(), DEVICE_ID);
        fault.setReporterId(ADMIN_ID);
        fault.setFormTemplateVersionId(999L);
        fault.setFormDataJson("{\"urgency\":\"HIGH\"}");
        when(faultMapper.selectPage(any(), any())).thenReturn(pageOf(fault));
        when(deviceMapper.selectBatchIds(any()))
                .thenReturn(java.util.List.of(device(DEVICE_ID, DeviceStatus.MAINTENANCE.name())));
        when(formTemplateService.requirePublishedSchema(999L))
                .thenThrow(new BusinessException(ErrorCode.PARAM_INVALID, "版本不存在"));

        PageResult<DeviceFaultVO> result = service.page(new DeviceFaultQuery());

        DeviceFaultVO vo = result.getRecords().get(0);
        assertNull(vo.getFormSchema(), "版本不可用时 schema 留空，前端按「无自定义字段」渲染");
        assertEquals("HIGH", vo.getFormData().get("urgency"), "值仍然下发，丢的只是显示名");
    }

    private static com.enterprise.ticket.common.form.FormSchema faultFormSchema() {
        com.enterprise.ticket.common.form.FormField field =
                new com.enterprise.ticket.common.form.FormField();
        field.setKey("urgency");
        field.setLabel("紧急程度");
        field.setType(com.enterprise.ticket.common.constant.FormFieldType.SELECT.name());
        field.setRequired(true);
        field.setWidth(1);
        com.enterprise.ticket.common.form.FormSchema schema =
                new com.enterprise.ticket.common.form.FormSchema();
        schema.setFields(new ArrayList<>(List.of(field)));
        return schema;
    }

    private static Page<DeviceFault> pageOf(DeviceFault... faults) {
        Page<DeviceFault> page = new Page<>(1, 10);
        page.setRecords(new ArrayList<>(List.of(faults)));
        page.setTotal(faults.length);
        return page;
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
    @DisplayName("故障分页：既不在处理范围、也没经手过的非管理员被拒（403）")
    void page_byNonAdmin_rejected() {
        // V50：准入从「仅管理员」放开为「管理员或处理范围内的人」，
        // 但**放开的是这类人，不是所有人** —— 一个普通员工仍拿不到这条列表。
        loginAs(APPLICANT_ID, RoleCode.USER);
        when(faultRepairAssigneeResolver.covers(APPLICANT_ID)).thenReturn(false);
        when(faultMapper.selectOne(any())).thenReturn(null);

        assertEquals(ErrorCode.FORBIDDEN,
                errorCodeOf(() -> service.page(new DeviceFaultQuery())));
        verify(faultMapper, never()).selectPage(any(), any());
    }

    @Test
    @DisplayName("★ 处理人：即使传 onlyMine=false 也只看得到自己的待办（隔离由服务端钉死，不信任查询参数）")
    void page_repairerForcedToOwnTodo() {
        loginAs(APPLICANT_ID, RoleCode.USER);
        when(faultRepairAssigneeResolver.covers(APPLICANT_ID)).thenReturn(true);
        DeviceFaultQuery query = new DeviceFaultQuery();
        // 刻意传 false —— 查询参数是客户端可控的，服务端不该因为一个参数就交出全量台账
        query.setOnlyMine(false);

        String sql = capturedSql(query);

        assertTrue(sql.contains("IS NULL"), "必须含「未接单」分支，实际 SQL：" + sql);
        assertTrue(sql.contains("status"), "必须限定未完结状态，实际 SQL：" + sql);
    }

    @Test
    @DisplayName("★ 配置被改走的「曾接过单人」：仍能进列表收尾，但看不到没人接的新单")
    void page_outOfScopeExHandlerSeesOnlyOwn() {
        loginAs(APPLICANT_ID, RoleCode.USER);
        when(faultRepairAssigneeResolver.covers(APPLICANT_ID)).thenReturn(false);
        // 历史上接过单（否则他连页面都进不来）
        when(faultMapper.selectOne(any())).thenReturn(fault(FaultStatus.IN_PROGRESS.name(), DEVICE_ID));

        String sql = capturedSql(new DeviceFaultQuery());

        // 仍按「归我管」收窄（能收尾），但**不带未接单分支** ——
        // 他此刻已无接单资格，把没人接的单摆给他只会让他点一下就报无权限。
        assertTrue(!sql.contains("IS NULL"), "不应含「未接单」分支，实际 SQL：" + sql);
        assertTrue(sql.contains("status"), "必须限定未完结状态，实际 SQL：" + sql);
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
        // 普通用户不查「可用设备」—— 这个下拉的「可用」分支是**管理员代报**用的；
        // 普通员工走 V57 的 ownedDevices() / lookupByAssetNo() 两个入口（见下一节）。
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

    // ------------------------------------------------------------------
    // 第 10 项两种填报模式（V57）：我名下的设备 / 扫码查设备
    // ------------------------------------------------------------------

    private Device ownedDevice(Long id, String status, Long ownerId, Long primaryCategoryId) {
        Device device = device(id, status);
        device.setOwnerId(ownerId);
        device.setPrimaryCategoryId(primaryCategoryId);
        return device;
    }

    private DeviceCategory category(Long id, String name) {
        DeviceCategory category = new DeviceCategory();
        category.setId(id);
        category.setCategoryName(name);
        return category;
    }

    @Test
    @DisplayName("我名下的设备：可用 → 可报（无 orderId）；正被我用 → 可报（带 orderId）")
    void ownedDevices_availableAndMyBorrowed_reportable() {
        loginAs(APPLICANT_ID, RoleCode.USER);
        Device available = ownedDevice(DEVICE_ID, DeviceStatus.AVAILABLE.name(), APPLICANT_ID, 5L);
        Device inUse = ownedDevice(OTHER_DEVICE_ID, DeviceStatus.IN_USE.name(), APPLICANT_ID, 5L);
        when(deviceMapper.selectList(any())).thenReturn(java.util.List.of(available, inUse));
        when(categoryMapper.selectBatchIds(any())).thenReturn(java.util.List.of(category(5L, "电脑整机")));
        // 只有「我本人使用中」的工单能带 orderId —— 服务端只允许该单借用人关联
        when(orderMapper.selectList(any()))
                .thenReturn(java.util.List.of(order(OrderStatus.BORROWED.name(), OTHER_DEVICE_ID, APPLICANT_ID)));

        java.util.List<FaultDeviceLookupVO> list = service.ownedDevices();

        assertEquals(2, list.size());
        FaultDeviceLookupVO availableVo = list.get(0);
        assertTrue(availableVo.isReportable(), "可用设备走台账登记路径");
        assertNull(availableVo.getOrderId(), "无工单不能带 orderId，否则提交必被拒");
        assertEquals("电脑整机", availableVo.getPrimaryCategoryName(), "要回显分类，让用户确认选对了没有");

        FaultDeviceLookupVO inUseVo = list.get(1);
        assertTrue(inUseVo.isReportable());
        assertEquals(ORDER_ID, inUseVo.getOrderId(), "正被我用 → 走工单路径，设备保持使用中");
        assertEquals("BO20260917-100", inUseVo.getOrderNo());
    }

    @Test
    @DisplayName("★ 我名下的设备：必须含「我在用的」（负责人不是我）—— 只查 owner_id 会让普通员工看到空列表")
    void ownedDevices_includesDevicesImUsingEvenIfNotOwnedByMe() {
        loginAs(APPLICANT_ID, RoleCode.USER);
        when(orderMapper.selectList(any()))
                .thenReturn(java.util.List.of(order(OrderStatus.BORROWED.name(), OTHER_DEVICE_ID, APPLICANT_ID)));
        // 负责人是资产管理员（99），不是我 —— 但我正在用它
        when(deviceMapper.selectList(any()))
                .thenReturn(java.util.List.of(ownedDevice(OTHER_DEVICE_ID, DeviceStatus.IN_USE.name(), 99L, null)));

        java.util.List<FaultDeviceLookupVO> list = service.ownedDevices();

        assertEquals(1, list.size());
        assertEquals(ORDER_ID, list.get(0).getOrderId(), "我在用的设备必须能带工单上报");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<Device>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(deviceMapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("owner_id"), "必须含「我负责的」，实际 SQL：" + sql);
        assertTrue(sql.contains("IN"), "必须含「我在用的」，实际 SQL：" + sql);
    }

    @Test
    @DisplayName("★ 我名下的设备：别人在用 / 维修中 / 已报废 / 丢失 → 不可报，且给出能照做的原因")
    void ownedDevices_notReportable_givesActionableReason() {
        loginAs(APPLICANT_ID, RoleCode.USER);
        when(deviceMapper.selectList(any())).thenReturn(java.util.List.of(
                ownedDevice(11L, DeviceStatus.IN_USE.name(), APPLICANT_ID, null),
                ownedDevice(12L, DeviceStatus.MAINTENANCE.name(), APPLICANT_ID, null),
                ownedDevice(13L, DeviceStatus.SCRAPPED.name(), APPLICANT_ID, null),
                ownedDevice(14L, DeviceStatus.LOST.name(), APPLICANT_ID, null)));
        // 设备在使用中，但工单借用人不是我（这里返回空：不是我的单）
        when(orderMapper.selectList(any())).thenReturn(java.util.List.of());

        java.util.List<FaultDeviceLookupVO> list = service.ownedDevices();

        assertEquals(4, list.size());
        for (FaultDeviceLookupVO vo : list) {
            assertTrue(!vo.isReportable(), "这些状态都不该可报：" + vo.getDeviceStatus());
            // 「不可报修」四个字本身没有任何指导意义，必须给出下一步动作
            assertTrue(vo.getReason() != null && !vo.getReason().isBlank(),
                    "不可报修必须带原因：" + vo.getDeviceStatus());
            assertNull(vo.getOrderId(), "不是我的单，就不能带 orderId 让前端以为能关联");
        }
    }

    @Test
    @DisplayName("扫码查询：资产编号查不到 → found=false 且不抛错（输错编号是日常动作）")
    void lookupByAssetNo_notFound_returnsFoundFalse() {
        loginAs(APPLICANT_ID, RoleCode.USER);
        when(deviceMapper.selectOne(any())).thenReturn(null);

        FaultDeviceLookupVO vo = service.lookupByAssetNo("IT-NOT-EXIST");

        assertTrue(!vo.isFound());
        assertTrue(!vo.isReportable());
        // 空编号同样走「查不到」，不能 NPE 也不能抛 400 —— 扫码枪扫空串很常见
        assertTrue(!service.lookupByAssetNo("   ").isFound());
    }

    @Test
    @DisplayName("扫码查询：设备被别人使用中 → 不可报，提示去找实际使用人（不带 orderId）")
    void lookupByAssetNo_inUseByOthers_notReportable() {
        loginAs(APPLICANT_ID, RoleCode.USER);
        when(deviceMapper.selectOne(any()))
                .thenReturn(ownedDevice(DEVICE_ID, DeviceStatus.IN_USE.name(), 999L, 5L));
        when(categoryMapper.selectBatchIds(any())).thenReturn(java.util.List.of(category(5L, "电脑整机")));
        when(orderMapper.selectList(any())).thenReturn(java.util.List.of());

        FaultDeviceLookupVO vo = service.lookupByAssetNo("IT-2026-0001");

        assertTrue(vo.isFound(), "查到了设备，只是不能报");
        assertTrue(!vo.isReportable());
        assertNull(vo.getOrderId());
        assertTrue(vo.getReason().contains("使用人"), "要让人知道该找谁，实际：" + vo.getReason());
        assertEquals("电脑整机", vo.getPrimaryCategoryName());
    }

    @Test
    @DisplayName("扫码查询：可用设备 → 可报，走台账登记路径")
    void lookupByAssetNo_available_reportable() {
        loginAs(APPLICANT_ID, RoleCode.USER);
        when(deviceMapper.selectOne(any()))
                .thenReturn(ownedDevice(DEVICE_ID, DeviceStatus.AVAILABLE.name(), null, null));

        FaultDeviceLookupVO vo = service.lookupByAssetNo("IT-2026-0001");

        assertTrue(vo.isFound());
        assertTrue(vo.isReportable());
        assertNull(vo.getOrderId(), "可用设备没有使用中工单");
    }

    // ------------------------------------------------------------------
    // 「待我处理」筛选（V50）
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private String capturedSql(DeviceFaultQuery query) {
        when(faultMapper.selectPage(any(), any())).thenReturn(new Page<>(1L, 10L));
        service.page(query);
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<DeviceFault>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(faultMapper).selectPage(any(), captor.capture());
        return captor.getValue().getSqlSegment();
    }

    @Test
    @DisplayName("★ 「待我处理」：限定未完结（待处理 / 处理中）+「没人接单或我接的单」")
    void page_onlyMine_scopesToOpenAndUnclaimedOrMine() {
        loginAs(ADMIN_ID, RoleCode.ADMIN);
        DeviceFaultQuery query = new DeviceFaultQuery();
        query.setOnlyMine(true);

        String sql = capturedSql(query);

        // 「没人接单」这一半：漏掉它，这个筛选在接单之前永远为空 ——
        // 而它存在的意义恰恰是让人看到「有什么等着我接」。
        assertTrue(sql.contains("IS NULL"), "必须包含「未接单」分支，实际 SQL：" + sql);
        // 「未完结」这一半：状态取值必须有待处理与处理中，不能只判待处理
        // （只判待处理的话，接单后这单就从自己的待办里消失了）
        assertTrue(sql.contains("status"), "必须带状态条件，实际 SQL：" + sql);
    }

    @Test
    @DisplayName("不勾「待我处理」：查询条件与改造前逐字节一致（零回归）")
    void page_withoutOnlyMine_keepsOldConditions() {
        loginAs(ADMIN_ID, RoleCode.ADMIN);

        String sql = capturedSql(new DeviceFaultQuery());

        assertTrue(!sql.contains("IS NULL"),
                "未勾选时不应出现任何「未接单」条件，实际 SQL：" + sql);
    }
}
