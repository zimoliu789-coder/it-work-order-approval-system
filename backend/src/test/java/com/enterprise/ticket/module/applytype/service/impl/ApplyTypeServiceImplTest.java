package com.enterprise.ticket.module.applytype.service.impl;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.ApplyTypeStatus;
import com.enterprise.ticket.common.constant.ApprovalMode;
import com.enterprise.ticket.common.constant.RoleCode;
import com.enterprise.ticket.common.constant.SubmitPermissionType;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.form.FormSchemaCodec;
import com.enterprise.ticket.common.util.SecurityUtils;
import com.enterprise.ticket.module.applytype.dto.ApplyTypeSaveRequest;
import com.enterprise.ticket.module.applytype.dto.vo.ApplyTypeOptionVO;
import com.enterprise.ticket.module.applytype.entity.ApplyType;
import com.enterprise.ticket.module.approvalflow.entity.ApprovalFlowVersion;
import com.enterprise.ticket.module.approvalflow.mapper.ApprovalFlowVersionMapper;
import com.enterprise.ticket.module.approvalflow.service.ApprovalFlowService;
import com.enterprise.ticket.module.applytype.mapper.ApplyTypeMapper;
import com.enterprise.ticket.module.department.entity.Department;
import com.enterprise.ticket.module.department.mapper.DepartmentMapper;
import com.enterprise.ticket.module.form.entity.FormTemplate;
import com.enterprise.ticket.module.form.entity.FormTemplateVersion;
import com.enterprise.ticket.module.form.mapper.FormTemplateMapper;
import com.enterprise.ticket.module.form.mapper.FormTemplateVersionMapper;
import com.enterprise.ticket.module.form.service.FormTemplateService;
import com.enterprise.ticket.module.order.entity.Order;
import com.enterprise.ticket.module.order.mapper.OrderMapper;
import com.enterprise.ticket.module.role.entity.SysRole;
import com.enterprise.ticket.module.role.mapper.SysRoleMapper;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import com.enterprise.ticket.security.LoginUser;
import com.enterprise.ticket.support.MyBatisLambdaCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 申请类型服务单元测试（Phase 14）
 *
 * <p>纯 Mockito；{@code SecurityUtils} 为静态工具，用 {@link MockedStatic} 打桩。
 *
 * <p>为什么这些用例重要：申请类型是自定义申请能力的「总开关」—— 它决定<em>谁能提交</em>
 * （提交权限）、<em>提交后走不走进审批</em>（审批方式）、<em>填什么</em>（关联表单版本）。
 * 其中「提交权限」的判定直接决定越权与否：一旦判定反了（把该拦的放行），
 * 用户就能提交本不属于他的申请类型。故这里把每条权限分支都固化为断言。
 */
@ExtendWith(MockitoExtension.class)
class ApplyTypeServiceImplTest {

    private static final Long TYPE_ID = 100L;
    private static final Long VERSION_ID = 9L;
    private static final Long USER_ID = 1L;

    @BeforeAll
    static void initMyBatisLambdaCache() {
        MyBatisLambdaCache.init(ApplyType.class, FormTemplate.class, FormTemplateVersion.class,
                SysRole.class, Department.class, Order.class, User.class);
    }

    @Mock
    private ApplyTypeMapper applyTypeMapper;
    @Mock
    private FormTemplateMapper templateMapper;
    @Mock
    private FormTemplateVersionMapper versionMapper;
    @Mock
    private FormTemplateService formTemplateService;
    // P4-B：删除申请类型时要做「连带清理专属模板/流程」的引用查询，这两处依赖必须有 mock，
    // 否则 @InjectMocks 注入 null —— 只要被测类型带了流程版本 id 就会 NPE。
    @Mock
    private ApprovalFlowVersionMapper flowVersionMapper;
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
    // P1 安全修复：含 permissionCodes 字段的表单详情要把选项按策略动态生成，需要这个依赖；
    // 不含该字段的表单不会调用它（见 toVO 的 hasPermissionField 短路）。
    @Mock
    private com.enterprise.ticket.module.permission.service.PermissionApplyPolicyService permissionApplyPolicyService;

    @InjectMocks
    private ApplyTypeServiceImpl service;

    private MockedStatic<SecurityUtils> securityUtils;

    @BeforeEach
    void stubCurrentUser() {
        securityUtils = Mockito.mockStatic(SecurityUtils.class);
        securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
    }

    @AfterEach
    void closeStatic() {
        securityUtils.close();
    }

    // ------------------------------------------------------------------
    // 辅助构造
    // ------------------------------------------------------------------

    private ApplyTypeSaveRequest request(String typeCode, String approvalMode, String permissionType) {
        ApplyTypeSaveRequest request = new ApplyTypeSaveRequest();
        request.setTypeCode(typeCode);
        request.setTypeName("采购申请");
        request.setFormTemplateVersionId(VERSION_ID);
        request.setApprovalMode(approvalMode);
        request.setSubmitPermissionType(permissionType);
        return request;
    }

    private ApplyType type(String status, String permissionType, List<String> values) {
        ApplyType type = new ApplyType();
        type.setId(TYPE_ID);
        type.setTypeCode("purchase");
        type.setTypeName("采购申请");
        type.setStatus(status);
        type.setFormTemplateVersionId(VERSION_ID);
        type.setApprovalMode(ApprovalMode.GROUP.name());
        type.setSubmitPermissionType(permissionType);
        type.setSubmitPermissionValue(FormSchemaCodec.write(values));
        return type;
    }

    private User user(String role, Long departmentId) {
        User user = new User();
        user.setId(USER_ID);
        user.setRole(role);
        user.setDepartmentId(departmentId);
        user.setEnabled(true);
        user.setDimission(false);
        return user;
    }

    /** 构造登录主体（详情可见性判定读的是 LoginUser.role） */
    private LoginUser loginUser(String role, Long departmentId) {
        return new LoginUser(user(role, departmentId));
    }

    private static ErrorCode codeOf(ThrowingRunnable runnable) {
        return assertThrows(BusinessException.class, runnable::run).getErrorCode();
    }

    /** 详情成功路径会加载上下文（版本/模板/角色/部门/工单计数），把这几处读库打桩为空集合 */
    private void stubDetailContext() {
        when(versionMapper.selectBatchIds(any())).thenReturn(List.of());
        when(roleMapper.selectList(any())).thenReturn(List.of());
        when(departmentMapper.selectList(any())).thenReturn(List.of());
        when(orderMapper.selectList(any())).thenReturn(List.of());
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }

    // ------------------------------------------------------------------
    // create：编码 / 版本 / 前缀 校验
    // ------------------------------------------------------------------

    @Test
    @DisplayName("新增：类型编码非法 → APPLY_TYPE_CODE_INVALID，不写库")
    void create_rejectsInvalidCode() {
        assertEquals(ErrorCode.APPLY_TYPE_CODE_INVALID,
                codeOf(() -> service.create(request("1bad", ApprovalMode.NONE.name(), SubmitPermissionType.ALL.name()))));
        verify(applyTypeMapper, never()).insert(any(ApplyType.class));
    }

    @Test
    @DisplayName("新增：类型编码重复 → APPLY_TYPE_CODE_EXISTS，不写库")
    void create_rejectsDuplicateCode() {
        when(applyTypeMapper.selectCount(any())).thenReturn(1L);

        assertEquals(ErrorCode.APPLY_TYPE_CODE_EXISTS,
                codeOf(() -> service.create(request("purchase", ApprovalMode.NONE.name(), SubmitPermissionType.ALL.name()))));
        verify(applyTypeMapper, never()).insert(any(ApplyType.class));
    }

    @Test
    @DisplayName("新增：关联版本未发布（isPublishedVersion=false）→ 拒绝，不写库")
    void create_rejectsUnpublishedVersion() {
        when(applyTypeMapper.selectCount(any())).thenReturn(0L);
        when(formTemplateService.isPublishedVersion(VERSION_ID)).thenReturn(false);

        assertEquals(ErrorCode.FORM_TEMPLATE_NO_PUBLISHED_VERSION,
                codeOf(() -> service.create(request("purchase", ApprovalMode.NONE.name(), SubmitPermissionType.ALL.name()))));
        verify(applyTypeMapper, never()).insert(any(ApplyType.class));
    }

    @Test
    @DisplayName("新增：审批方式取值非法 → PARAM_INVALID")
    void create_rejectsInvalidApprovalMode() {
        when(applyTypeMapper.selectCount(any())).thenReturn(0L);
        when(formTemplateService.isPublishedVersion(VERSION_ID)).thenReturn(true);

        assertEquals(ErrorCode.PARAM_INVALID,
                codeOf(() -> service.create(request("purchase", "NOT_A_MODE", SubmitPermissionType.ALL.name()))));
    }

    @Test
    @DisplayName("新增：工单前缀非法 → APPLY_TYPE_PREFIX_INVALID；合法小写前缀归一化为大写")
    void create_normalizesPrefix() {
        when(applyTypeMapper.selectCount(any())).thenReturn(0L);
        when(formTemplateService.isPublishedVersion(VERSION_ID)).thenReturn(true);

        ApplyTypeSaveRequest bad = request("purchase", ApprovalMode.NONE.name(), SubmitPermissionType.ALL.name());
        bad.setOrderPrefix("P_R");
        assertEquals(ErrorCode.APPLY_TYPE_PREFIX_INVALID, codeOf(() -> service.create(bad)));

        ApplyTypeSaveRequest ok = request("purchase", ApprovalMode.NONE.name(), SubmitPermissionType.ALL.name());
        ok.setOrderPrefix("prch");
        when(applyTypeMapper.insert(any(ApplyType.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, ApplyType.class).setId(TYPE_ID);
            return 1;
        });

        service.create(ok);

        ArgumentCaptor<ApplyType> captor = ArgumentCaptor.forClass(ApplyType.class);
        verify(applyTypeMapper).insert(captor.capture());
        assertEquals("PRCH", captor.getValue().getOrderPrefix());
        assertEquals(ApplyTypeStatus.ENABLED.name(), captor.getValue().getStatus());
    }

    // ------------------------------------------------------------------
    // create：提交权限
    // ------------------------------------------------------------------

    @Test
    @DisplayName("新增：ALL 模式 → 提交权限值归一化为空数组")
    void create_allModeWritesEmptyArray() {
        when(applyTypeMapper.selectCount(any())).thenReturn(0L);
        when(formTemplateService.isPublishedVersion(VERSION_ID)).thenReturn(true);
        when(applyTypeMapper.insert(any(ApplyType.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, ApplyType.class).setId(TYPE_ID);
            return 1;
        });

        service.create(request("purchase", ApprovalMode.NONE.name(), SubmitPermissionType.ALL.name()));

        ArgumentCaptor<ApplyType> captor = ArgumentCaptor.forClass(ApplyType.class);
        verify(applyTypeMapper).insert(captor.capture());
        assertEquals(List.of(), FormSchemaCodec.readStringList(captor.getValue().getSubmitPermissionValue()));
    }

    @Test
    @DisplayName("新增：ROLE 模式但值为空 → APPLY_TYPE_PERMISSION_INVALID，不写库")
    void create_roleModeRequiresValue() {
        when(applyTypeMapper.selectCount(any())).thenReturn(0L);
        when(formTemplateService.isPublishedVersion(VERSION_ID)).thenReturn(true);

        ApplyTypeSaveRequest request = request("purchase", ApprovalMode.GROUP.name(), SubmitPermissionType.ROLE.name());
        request.setSubmitPermissionValues(List.of());

        assertEquals(ErrorCode.APPLY_TYPE_PERMISSION_INVALID, codeOf(() -> service.create(request)));
        verify(applyTypeMapper, never()).insert(any(ApplyType.class));
    }

    @Test
    @DisplayName("新增：ROLE 模式引用了不存在/已停用的角色 → 拒绝（防止「保存成功但谁也提不了」）")
    void create_roleModeRejectsUnknownRole() {
        when(applyTypeMapper.selectCount(any())).thenReturn(0L);
        when(formTemplateService.isPublishedVersion(VERSION_ID)).thenReturn(true);

        SysRole disabled = new SysRole();
        disabled.setRoleCode("user");
        disabled.setEnabled(false);
        when(roleMapper.selectList(any())).thenReturn(List.of(disabled));

        ApplyTypeSaveRequest request = request("purchase", ApprovalMode.GROUP.name(), SubmitPermissionType.ROLE.name());
        request.setSubmitPermissionValues(List.of("user"));

        assertEquals(ErrorCode.APPLY_TYPE_PERMISSION_INVALID, codeOf(() -> service.create(request)));
        verify(applyTypeMapper, never()).insert(any(ApplyType.class));
    }

    @Test
    @DisplayName("新增：GROUP 模式 id 数量与库中不一致 → 拒绝")
    void create_groupModeRejectsUnknownGroup() {
        when(applyTypeMapper.selectCount(any())).thenReturn(0L);
        when(formTemplateService.isPublishedVersion(VERSION_ID)).thenReturn(true);
        when(departmentMapper.selectCount(any())).thenReturn(1L);

        ApplyTypeSaveRequest request = request("purchase", ApprovalMode.GROUP.name(), SubmitPermissionType.GROUP.name());
        request.setSubmitPermissionValues(List.of("3", "7"));

        assertEquals(ErrorCode.APPLY_TYPE_PERMISSION_INVALID, codeOf(() -> service.create(request)));
    }

    @Test
    @DisplayName("新增：GROUP 模式 id 非数字 → 拒绝（而不是抛 NumberFormatException）")
    void create_groupModeRejectsNonNumeric() {
        when(applyTypeMapper.selectCount(any())).thenReturn(0L);
        when(formTemplateService.isPublishedVersion(VERSION_ID)).thenReturn(true);

        ApplyTypeSaveRequest request = request("purchase", ApprovalMode.GROUP.name(), SubmitPermissionType.GROUP.name());
        request.setSubmitPermissionValues(List.of("abc"));

        assertEquals(ErrorCode.APPLY_TYPE_PERMISSION_INVALID, codeOf(() -> service.create(request)));
    }

    // ------------------------------------------------------------------
    // requireSubmittable：谁能提交
    // ------------------------------------------------------------------

    @Test
    @DisplayName("提交校验：类型不存在 → APPLY_TYPE_NOT_FOUND")
    void requireSubmittable_missingType() {
        when(applyTypeMapper.selectById(TYPE_ID)).thenReturn(null);

        assertEquals(ErrorCode.APPLY_TYPE_NOT_FOUND, codeOf(() -> service.requireSubmittable(TYPE_ID)));
    }

    @Test
    @DisplayName("提交校验：类型已停用 → APPLY_TYPE_DISABLED")
    void requireSubmittable_disabled() {
        when(applyTypeMapper.selectById(TYPE_ID))
                .thenReturn(type(ApplyTypeStatus.DISABLED.name(), SubmitPermissionType.ALL.name(), List.of()));

        assertEquals(ErrorCode.APPLY_TYPE_DISABLED, codeOf(() -> service.requireSubmittable(TYPE_ID)));
    }

    @Test
    @DisplayName("提交校验：ALL 模式 → 普通用户放行")
    void requireSubmittable_allModeAllowsNormalUser() {
        when(applyTypeMapper.selectById(TYPE_ID))
                .thenReturn(type(ApplyTypeStatus.ENABLED.name(), SubmitPermissionType.ALL.name(), List.of()));
        when(userMapper.selectById(USER_ID)).thenReturn(user(RoleCode.USER, null));

        assertEquals(TYPE_ID, service.requireSubmittable(TYPE_ID).getId());
    }

    @Test
    @DisplayName("提交校验：ROLE 模式且当前用户角色命中 → 放行；未命中 → APPLY_TYPE_SUBMIT_FORBIDDEN")
    void requireSubmittable_roleMode() {
        ApplyType type = type(ApplyTypeStatus.ENABLED.name(), SubmitPermissionType.ROLE.name(), List.of("admin"));

        when(applyTypeMapper.selectById(TYPE_ID)).thenReturn(type);
        when(userMapper.selectById(USER_ID)).thenReturn(user(RoleCode.ADMIN, null));
        assertEquals(TYPE_ID, service.requireSubmittable(TYPE_ID).getId());

        when(userMapper.selectById(USER_ID)).thenReturn(user(RoleCode.USER, null));
        assertEquals(ErrorCode.APPLY_TYPE_SUBMIT_FORBIDDEN, codeOf(() -> service.requireSubmittable(TYPE_ID)));
    }

    @Test
    @DisplayName("提交校验：GROUP 模式命中分组放行；无分组或未命中 → 拒绝")
    void requireSubmittable_groupMode() {
        ApplyType type = type(ApplyTypeStatus.ENABLED.name(), SubmitPermissionType.GROUP.name(), List.of("7"));

        when(applyTypeMapper.selectById(TYPE_ID)).thenReturn(type);
        when(userMapper.selectById(USER_ID)).thenReturn(user(RoleCode.USER, 7L));
        assertEquals(TYPE_ID, service.requireSubmittable(TYPE_ID).getId());

        when(userMapper.selectById(USER_ID)).thenReturn(user(RoleCode.USER, 8L));
        assertEquals(ErrorCode.APPLY_TYPE_SUBMIT_FORBIDDEN, codeOf(() -> service.requireSubmittable(TYPE_ID)));

        when(userMapper.selectById(USER_ID)).thenReturn(user(RoleCode.USER, null));
        assertEquals(ErrorCode.APPLY_TYPE_SUBMIT_FORBIDDEN, codeOf(() -> service.requireSubmittable(TYPE_ID)));
    }

    @Test
    @DisplayName("提交校验：super_admin 恒定放行（即使不在 ROLE 名单内）")
    void requireSubmittable_superAdminAlwaysAllowed() {
        when(applyTypeMapper.selectById(TYPE_ID))
                .thenReturn(type(ApplyTypeStatus.ENABLED.name(), SubmitPermissionType.ROLE.name(), List.of("user")));
        when(userMapper.selectById(USER_ID)).thenReturn(user(RoleCode.SUPER_ADMIN, null));

        assertEquals(TYPE_ID, service.requireSubmittable(TYPE_ID).getId());
    }

    @Test
    @DisplayName("提交校验：权限值损坏（解析为空）→ 视为无人可提交（少放行不错放行）")
    void requireSubmittable_brokenPermissionValueDeniesAll() {
        ApplyType type = type(ApplyTypeStatus.ENABLED.name(), SubmitPermissionType.ROLE.name(), List.of());
        type.setSubmitPermissionValue("not-json");
        when(applyTypeMapper.selectById(TYPE_ID)).thenReturn(type);
        when(userMapper.selectById(USER_ID)).thenReturn(user(RoleCode.ADMIN, null));

        assertEquals(ErrorCode.APPLY_TYPE_SUBMIT_FORBIDDEN, codeOf(() -> service.requireSubmittable(TYPE_ID)));
    }

    // ------------------------------------------------------------------
    // getDetail：详情可见性（防越权枚举）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("详情可见性：普通用户读已停用类型 → 统一 404（不泄露存在性，且不加载 schema）")
    void getDetail_normalUserGets404ForDisabledType() {
        when(applyTypeMapper.selectById(TYPE_ID))
                .thenReturn(type(ApplyTypeStatus.DISABLED.name(), SubmitPermissionType.ALL.name(), List.of()));
        securityUtils.when(SecurityUtils::getCurrentUser).thenReturn(loginUser(RoleCode.USER, null));

        assertEquals(ErrorCode.APPLY_TYPE_NOT_FOUND, codeOf(() -> service.getDetail(TYPE_ID)));
        verify(formTemplateService, never()).requirePublishedSchema(any());
    }

    @Test
    @DisplayName("详情可见性：普通用户读「启用但不在其提交范围」的类型 → 统一 404")
    void getDetail_normalUserGets404WhenNotInSubmitScope() {
        when(applyTypeMapper.selectById(TYPE_ID))
                .thenReturn(type(ApplyTypeStatus.ENABLED.name(), SubmitPermissionType.ROLE.name(), List.of("admin")));
        securityUtils.when(SecurityUtils::getCurrentUser).thenReturn(loginUser(RoleCode.USER, null));
        when(userMapper.selectById(USER_ID)).thenReturn(user(RoleCode.USER, null));

        assertEquals(ErrorCode.APPLY_TYPE_NOT_FOUND, codeOf(() -> service.getDetail(TYPE_ID)));
        verify(formTemplateService, never()).requirePublishedSchema(any());
    }

    @Test
    @DisplayName("详情可见性：普通用户读「启用且可提交」的类型 → 正常返回详情（提交页据此渲染表单）")
    void getDetail_normalUserCanReadSubmittableType() {
        when(applyTypeMapper.selectById(TYPE_ID))
                .thenReturn(type(ApplyTypeStatus.ENABLED.name(), SubmitPermissionType.ALL.name(), List.of()));
        securityUtils.when(SecurityUtils::getCurrentUser).thenReturn(loginUser(RoleCode.USER, null));
        when(userMapper.selectById(USER_ID)).thenReturn(user(RoleCode.USER, null));
        stubDetailContext();

        assertEquals(TYPE_ID, service.getDetail(TYPE_ID).getId());
    }

    @Test
    @DisplayName("详情可见性：admin 及以上读已停用类型 → 放行（管理端查看/编辑需要，不受提交范围约束）")
    void getDetail_adminCanReadDisabledType() {
        when(applyTypeMapper.selectById(TYPE_ID))
                .thenReturn(type(ApplyTypeStatus.DISABLED.name(), SubmitPermissionType.ALL.name(), List.of()));
        securityUtils.when(SecurityUtils::getCurrentUser).thenReturn(loginUser(RoleCode.ADMIN, null));
        stubDetailContext();

        assertEquals(TYPE_ID, service.getDetail(TYPE_ID).getId());
        verify(userMapper, never()).selectById(any());
    }

    // ------------------------------------------------------------------
    // listEnabledForCurrentUser
    // ------------------------------------------------------------------

    @Test
    @DisplayName("可提交列表：只返回启用且当前用户有权的类型")
    void listEnabledFiltersByPermission() {
        ApplyType allowed = type(ApplyTypeStatus.ENABLED.name(), SubmitPermissionType.ALL.name(), List.of());
        allowed.setTypeCode("all_type");
        ApplyType roleRestricted = type(ApplyTypeStatus.ENABLED.name(), SubmitPermissionType.ROLE.name(), List.of("super_admin"));
        roleRestricted.setTypeCode("role_type");

        when(applyTypeMapper.selectList(any())).thenReturn(List.of(allowed, roleRestricted));
        when(userMapper.selectById(USER_ID)).thenReturn(user(RoleCode.USER, null));

        List<ApplyTypeOptionVO> options = service.listEnabledForCurrentUser();

        assertEquals(1, options.size());
        assertEquals("all_type", options.get(0).getTypeCode());
    }

    // ------------------------------------------------------------------
    // delete / updateStatus
    // ------------------------------------------------------------------

    @Test
    @DisplayName("删除：已被工单使用 → APPLY_TYPE_HAS_ORDER，不删除")
    void delete_rejectsUsedType() {
        when(applyTypeMapper.selectById(TYPE_ID))
                .thenReturn(type(ApplyTypeStatus.ENABLED.name(), SubmitPermissionType.ALL.name(), List.of()));
        when(orderMapper.selectCount(any())).thenReturn(3L);

        assertEquals(ErrorCode.APPLY_TYPE_HAS_ORDER, codeOf(() -> service.delete(TYPE_ID)));
        verify(applyTypeMapper, never()).deleteById(any(Long.class));
    }

    @Test
    @DisplayName("删除：无工单使用时物理删除")
    void delete_removesUnusedType() {
        when(applyTypeMapper.selectById(TYPE_ID))
                .thenReturn(type(ApplyTypeStatus.ENABLED.name(), SubmitPermissionType.ALL.name(), List.of()));
        when(orderMapper.selectCount(any())).thenReturn(0L);

        service.delete(TYPE_ID);

        verify(applyTypeMapper).deleteById(TYPE_ID);
    }

    @Test
    @DisplayName("状态切换：非法取值 → PARAM_INVALID；与现状相同 → 幂等不写库")
    void updateStatus_guards() {
        when(applyTypeMapper.selectById(TYPE_ID))
                .thenReturn(type(ApplyTypeStatus.ENABLED.name(), SubmitPermissionType.ALL.name(), List.of()));

        assertEquals(ErrorCode.PARAM_INVALID, codeOf(() -> service.updateStatus(TYPE_ID, "NOT_A_STATUS")));

        service.updateStatus(TYPE_ID, ApplyTypeStatus.ENABLED.name());
        verify(applyTypeMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("状态切换：合法变更写入新状态")
    void updateStatus_writesChange() {
        when(applyTypeMapper.selectById(TYPE_ID))
                .thenReturn(type(ApplyTypeStatus.ENABLED.name(), SubmitPermissionType.ALL.name(), List.of()));

        service.updateStatus(TYPE_ID, ApplyTypeStatus.DISABLED.name());

        verify(applyTypeMapper).update(any(), any());
    }

    @Test
    @DisplayName("删除：连带清理专属的表单模板与审批流程（P4-B）")
    void delete_cascadesOwnedTemplateAndFlow() {
        ApplyType owned = new ApplyType();
        owned.setId(TYPE_ID);
        owned.setFormTemplateVersionId(11L);
        owned.setApprovalFlowVersionId(22L);
        when(applyTypeMapper.selectById(TYPE_ID)).thenReturn(owned);
        when(orderMapper.selectCount(any())).thenReturn(0L);

        FormTemplateVersion templateVersion = new FormTemplateVersion();
        templateVersion.setId(11L);
        templateVersion.setTemplateId(101L);
        when(versionMapper.selectById(11L)).thenReturn(templateVersion);

        ApprovalFlowVersion flowVersion = new ApprovalFlowVersion();
        flowVersion.setId(22L);
        flowVersion.setFlowId(202L);
        when(flowVersionMapper.selectById(22L)).thenReturn(flowVersion);

        // 模板 / 流程都在，但没有任何**别的**申请类型引用它们 —— 这就是「专属」。
        when(versionMapper.selectList(any())).thenReturn(List.of(templateVersion));
        when(flowVersionMapper.selectList(any())).thenReturn(List.of(flowVersion));
        when(applyTypeMapper.selectCount(any())).thenReturn(0L);

        service.delete(TYPE_ID);

        verify(applyTypeMapper).deleteById(TYPE_ID);
        verify(formTemplateService).deleteTemplate(101L);
        verify(approvalFlowService).deleteFlow(202L);
    }

    @Test
    @DisplayName("删除：模板/流程仍被别的申请类型引用时保留（共用不是残留）（P4-B）")
    void delete_keepsSharedTemplateAndFlow() {
        ApplyType owned = new ApplyType();
        owned.setId(TYPE_ID);
        owned.setFormTemplateVersionId(11L);
        owned.setApprovalFlowVersionId(22L);
        when(applyTypeMapper.selectById(TYPE_ID)).thenReturn(owned);
        when(orderMapper.selectCount(any())).thenReturn(0L);

        FormTemplateVersion templateVersion = new FormTemplateVersion();
        templateVersion.setId(11L);
        templateVersion.setTemplateId(101L);
        when(versionMapper.selectById(11L)).thenReturn(templateVersion);

        ApprovalFlowVersion flowVersion = new ApprovalFlowVersion();
        flowVersion.setId(22L);
        flowVersion.setFlowId(202L);
        when(flowVersionMapper.selectById(22L)).thenReturn(flowVersion);

        when(versionMapper.selectList(any())).thenReturn(List.of(templateVersion));
        when(flowVersionMapper.selectList(any())).thenReturn(List.of(flowVersion));
        // 还有别的类型引用着 —— 删掉会连带毁掉它，必须保留
        when(applyTypeMapper.selectCount(any())).thenReturn(1L);

        service.delete(TYPE_ID);

        verify(applyTypeMapper).deleteById(TYPE_ID);
        verify(formTemplateService, never()).deleteTemplate(any(Long.class));
        verify(approvalFlowService, never()).deleteFlow(any(Long.class));
    }

    @Test
    @DisplayName("排序辅助：sortOrder 升序、同序按 id 升序（列表稳定性）")
    void bySortOrderIsStable() {
        ApplyType a = new ApplyType();
        a.setId(3L);
        a.setSortOrder(100);
        ApplyType b = new ApplyType();
        b.setId(1L);
        b.setSortOrder(100);
        ApplyType c = new ApplyType();
        c.setId(2L);
        c.setSortOrder(10);

        List<ApplyType> sorted = List.of(a, b, c).stream()
                .sorted(ApplyTypeServiceImpl.bySortOrder())
                .toList();

        assertEquals(List.of(2L, 1L, 3L), sorted.stream().map(ApplyType::getId).toList());
        assertFalse(sorted.isEmpty());
    }
}
