package com.enterprise.ticket.module.applytype.service.impl;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.applytype.dto.BuiltInConfigRequest;
import com.enterprise.ticket.module.applytype.dto.vo.BuiltInConfigVO;
import com.enterprise.ticket.module.applytype.entity.ApplyType;
import com.enterprise.ticket.module.applytype.mapper.ApplyTypeMapper;
import com.enterprise.ticket.module.applytype.support.BuiltInApplyCatalog;
import com.enterprise.ticket.module.approvalflow.mapper.ApprovalFlowMapper;
import com.enterprise.ticket.module.approvalflow.mapper.ApprovalFlowVersionMapper;
import com.enterprise.ticket.module.approvalflow.service.ApprovalFlowService;
import com.enterprise.ticket.module.department.mapper.DepartmentMapper;
import com.enterprise.ticket.module.form.mapper.FormTemplateMapper;
import com.enterprise.ticket.module.form.mapper.FormTemplateVersionMapper;
import com.enterprise.ticket.module.form.service.FormTemplateService;
import com.enterprise.ticket.module.order.mapper.OrderMapper;
import com.enterprise.ticket.module.permission.service.PermissionApplyPolicyService;
import com.enterprise.ticket.module.role.mapper.SysRoleMapper;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import com.enterprise.ticket.support.MyBatisLambdaCache;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 系统内置申请类型（V48）的行为固化。
 *
 * <h2>为什么这些用例重要</h2>
 * 内置类型是**唯一带「不可删除」语义**的申请类型，而它的删除保护、整体覆盖保护
 * 都是「少一行 if 就失效」的那种防护 —— 失效后界面上完全看不出来：
 * 卡片照常显示、接口照常返回 200，只是某一天「借用设备」被删了。
 * 因此每条守卫都固化成断言。
 *
 * <p>纯 Mockito：只注册 {@code ApplyType} 的 Lambda 元数据（被测路径只对它构造 Wrapper）。
 */
@ExtendWith(MockitoExtension.class)
class ApplyTypeServiceImplBuiltInTest {

    private static final Long BUILT_IN_ID = 900L;
    private static final Long CUSTOM_ID = 901L;
    private static final Long FLOW_VERSION_ID = 77L;

    @BeforeAll
    static void initMyBatisLambdaCache() {
        MyBatisLambdaCache.init(ApplyType.class);
    }

    @Mock
    private ApplyTypeMapper applyTypeMapper;
    @Mock
    private FormTemplateMapper templateMapper;
    @Mock
    private FormTemplateVersionMapper versionMapper;
    @Mock
    private FormTemplateService formTemplateService;
    @Mock
    private ApprovalFlowVersionMapper flowVersionMapper;
    @Mock
    private ApprovalFlowMapper flowMapper;
    @Mock
    private ApprovalFlowService approvalFlowService;
    @Mock
    private SysRoleMapper roleMapper;
    @Mock
    private DepartmentMapper departmentMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private OrderMapper orderMapper;
    @Mock
    private PermissionApplyPolicyService permissionApplyPolicyService;
    @Mock
    private SystemConfigService systemConfigService;

    @InjectMocks
    private ApplyTypeServiceImpl service;

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private ApplyType builtIn(String typeCode) {
        ApplyType type = new ApplyType();
        type.setId(BUILT_IN_ID);
        type.setTypeCode(typeCode);
        type.setTypeName(BuiltInApplyCatalog.of(typeCode).typeName());
        type.setStatus("ENABLED");
        type.setBuiltIn(true);
        type.setApprovalMode(BuiltInApplyCatalog.of(typeCode).approvalMode().name());
        type.setSubmitPermissionType("ALL");
        type.setSubmitPermissionValue("[]");
        return type;
    }

    private ApplyType custom() {
        ApplyType type = new ApplyType();
        type.setId(CUSTOM_ID);
        type.setTypeCode("PURCHASE");
        type.setTypeName("采购申请");
        type.setStatus("ENABLED");
        type.setBuiltIn(false);
        type.setApprovalMode("GROUP");
        type.setFormTemplateVersionId(9L);
        return type;
    }

    private ErrorCode codeOf(Runnable runnable) {
        return assertThrows(BusinessException.class, runnable::run).getErrorCode();
    }

    // ------------------------------------------------------------------
    // 删除守卫
    // ------------------------------------------------------------------

    @Test
    @DisplayName("删除内置类型：一律拒绝，且不发生任何 deleteById")
    void deleteBuiltInRejected() {
        when(applyTypeMapper.selectById(BUILT_IN_ID)).thenReturn(builtIn(BuiltInApplyCatalog.CODE_BORROW));

        assertEquals(ErrorCode.APPLY_TYPE_BUILT_IN, codeOf(() -> service.delete(BUILT_IN_ID)));
        // 显式限定 Serializable：BaseMapper 同时有 deleteById(Serializable) 与 deleteById(T)，裸 any() 会有歧义
        verify(applyTypeMapper, never()).deleteById(any(java.io.Serializable.class));
    }

    @Test
    @DisplayName("内置类型走整体覆盖（update）：拒绝 —— 它没有表单模板，覆盖会写坏记录")
    void updateBuiltInRejected() {
        when(applyTypeMapper.selectById(BUILT_IN_ID)).thenReturn(builtIn(BuiltInApplyCatalog.CODE_BORROW));

        assertEquals(ErrorCode.PARAM_INVALID,
                codeOf(() -> service.update(BUILT_IN_ID, new com.enterprise.ticket.module.applytype.dto.ApplyTypeSaveRequest())));
        verify(applyTypeMapper, never()).update(any(), any());
    }

    // ------------------------------------------------------------------
    // 配置面板读数
    // ------------------------------------------------------------------

    @Test
    @DisplayName("借用设备未绑流程：给的是**默认流程**（不是空画布），且带上借用域字段目录")
    void borrowBuiltInShowsDefaultFlow() {
        when(applyTypeMapper.selectById(BUILT_IN_ID)).thenReturn(builtIn(BuiltInApplyCatalog.CODE_BORROW));
        when(systemConfigService.approvalDeviceAmountThreshold()).thenReturn(new BigDecimal("5000"));

        BuiltInConfigVO vo = service.builtInConfig(BUILT_IN_ID);

        assertTrue(vo.isBuiltIn());
        assertTrue(vo.isFlowEditable());
        assertFalse(vo.isFlowBound());
        assertNull(vo.getApprovalFlowVersionId());
        assertNotNull(vo.getFlowDefinition(), "未绑定时必须下发默认流程，否则管理员会以为「当前没有流程」");
        assertFalse(vo.getFlowDefinition().getNodes().isEmpty());
        assertNotNull(vo.getFieldSchema(), "条件分支要引用借用域字段，缺了它配出来的条件提交时会报字段不存在");
    }

    @Test
    @DisplayName("故障报修：流程不可编辑、没有默认流程、没有字段目录 —— 如实回答「无审批环节」")
    void faultReportHasNoFlow() {
        when(applyTypeMapper.selectById(BUILT_IN_ID)).thenReturn(builtIn(BuiltInApplyCatalog.CODE_FAULT_REPORT));

        BuiltInConfigVO vo = service.builtInConfig(BUILT_IN_ID);

        assertFalse(vo.isFlowEditable());
        assertFalse(vo.isFlowBound());
        assertNull(vo.getFlowDefinition());
        assertNull(vo.getFieldSchema());
        assertNotNull(vo.getFormNote(), "必须告诉管理员「为什么这里动不了」");
    }

    @Test
    @DisplayName("非内置类型调内置配置接口：按不存在处理（不暴露「这不是内置类型」这一可枚举口径）")
    void customTypeTreatedAsNotFound() {
        when(applyTypeMapper.selectById(CUSTOM_ID)).thenReturn(custom());

        assertEquals(ErrorCode.APPLY_TYPE_NOT_FOUND, codeOf(() -> service.builtInConfig(CUSTOM_ID)));
        assertEquals(ErrorCode.APPLY_TYPE_NOT_FOUND,
                codeOf(() -> service.updateBuiltInConfig(CUSTOM_ID, new BuiltInConfigRequest())));
    }

    // ------------------------------------------------------------------
    // 配置写入
    // ------------------------------------------------------------------

    @Test
    @DisplayName("给故障报修绑流程：拒绝 —— 它不产生工单，绑了也不会有人审批（宁可报错也不给无效开关）")
    void faultReportCannotBindFlow() {
        when(applyTypeMapper.selectById(BUILT_IN_ID)).thenReturn(builtIn(BuiltInApplyCatalog.CODE_FAULT_REPORT));
        BuiltInConfigRequest request = new BuiltInConfigRequest();
        request.setApprovalFlowVersionId(FLOW_VERSION_ID);

        assertEquals(ErrorCode.APPLY_TYPE_BUILT_IN_FLOW_FIXED,
                codeOf(() -> service.updateBuiltInConfig(BUILT_IN_ID, request)));
        verify(applyTypeMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("只改说明（故障报修）：允许，且不触碰流程绑定")
    void faultReportDescriptionEditable() {
        when(applyTypeMapper.selectById(BUILT_IN_ID)).thenReturn(builtIn(BuiltInApplyCatalog.CODE_FAULT_REPORT));
        BuiltInConfigRequest request = new BuiltInConfigRequest();
        request.setDescription("报修前请先确认电源已接通");

        service.updateBuiltInConfig(BUILT_IN_ID, request);

        verify(applyTypeMapper).update(any(), any());
    }

    @Test
    @DisplayName("借用设备绑未发布流程：异常透传，且不落库")
    void borrowBuiltInRejectsUnpublishedFlow() {
        when(applyTypeMapper.selectById(BUILT_IN_ID)).thenReturn(builtIn(BuiltInApplyCatalog.CODE_BORROW));
        when(approvalFlowService.requirePublishedDefinition(FLOW_VERSION_ID))
                .thenThrow(new BusinessException(ErrorCode.PARAM_INVALID, "流程版本未发布"));
        BuiltInConfigRequest request = new BuiltInConfigRequest();
        request.setApprovalFlowVersionId(FLOW_VERSION_ID);

        assertThrows(BusinessException.class, () -> service.updateBuiltInConfig(BUILT_IN_ID, request));
        verify(applyTypeMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("借用设备绑已发布流程：落库（这是「改了审批流程要真正生效」的写入侧）")
    void borrowBuiltInBindsPublishedFlow() {
        when(applyTypeMapper.selectById(BUILT_IN_ID)).thenReturn(builtIn(BuiltInApplyCatalog.CODE_BORROW));
        when(approvalFlowService.requirePublishedDefinition(FLOW_VERSION_ID)).thenReturn(null);
        BuiltInConfigRequest request = new BuiltInConfigRequest();
        request.setApprovalFlowVersionId(FLOW_VERSION_ID);
        request.setDescription("两级审批：主管 → IT");

        service.updateBuiltInConfig(BUILT_IN_ID, request);

        verify(applyTypeMapper).update(any(), any());
    }

    // ------------------------------------------------------------------
    // 跨模块契约
    // ------------------------------------------------------------------

    @Test
    @DisplayName("borrowBuiltInFlowVersionId：读出内置借用类型绑定的流程版本（OrderServiceImpl 依赖它）")
    void borrowFlowVersionReadable() {
        ApplyType borrow = builtIn(BuiltInApplyCatalog.CODE_BORROW);
        borrow.setApprovalFlowVersionId(FLOW_VERSION_ID);
        when(applyTypeMapper.selectOne(any())).thenReturn(borrow);

        assertEquals(FLOW_VERSION_ID, service.borrowBuiltInFlowVersionId());
    }

    @Test
    @DisplayName("borrowBuiltInFlowVersionId：未绑定时返回 null（提交侧据此退回部门 / 预置流程）")
    void borrowFlowVersionNullWhenUnbound() {
        when(applyTypeMapper.selectOne(any())).thenReturn(null);

        assertNull(service.borrowBuiltInFlowVersionId());
    }
}
