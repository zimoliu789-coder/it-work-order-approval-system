package com.enterprise.ticket.module.device.service;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.department.dto.vo.DepartmentOptionVO;
import com.enterprise.ticket.module.department.service.DepartmentService;
import com.enterprise.ticket.module.device.dto.vo.FaultRepairHandlerConfigVO;
import com.enterprise.ticket.module.device.support.FaultRepairAssigneeResolver;
import com.enterprise.ticket.module.device.support.FaultRepairSettings;
import com.enterprise.ticket.module.role.dto.vo.RoleVO;
import com.enterprise.ticket.module.role.entity.SysRole;
import com.enterprise.ticket.module.role.service.RoleService;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 故障报修「处理流程」配置的读写（V50）
 *
 * <h2>这些用例在防什么</h2>
 * 「通知给谁」配错有两种形态，而**两种都不会报错**：
 * <ol>
 *   <li>配额合法但命中 0 人（角色没人持有 / 部门没有在职成员 / 指定的人已离职）——
 *       表现为「报修提交成功，但没有任何人收到通知」；</li>
 *   <li>写入侧对非法取值**静默纠正**（比如把拼错的类型回落成「按部门」）——
 *       管理员以为配的是「指定人员张三」，系统却按部门通知了另一批人。</li>
 * </ol>
 * 第 2 类是本轮刻意做严的地方：读取侧用容错解析（库被手工改坏也不该让报修挂掉），
 * 写入侧用严格校验（保存是管理员主动动作，静默纠正才是灾难）。
 * 每条断言都对应其中一个失败形态。
 */
@ExtendWith(MockitoExtension.class)
class FaultRepairHandlerConfigServiceTest {

    @Mock
    private SystemConfigService systemConfigService;
    @Mock
    private FaultRepairAssigneeResolver assigneeResolver;
    @Mock
    private DepartmentService departmentService;
    @Mock
    private RoleService roleService;
    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private FaultRepairHandlerConfigService service;

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private ErrorCode codeOf(Runnable runnable) {
        return assertThrows(BusinessException.class, runnable::run).getErrorCode();
    }

    private DepartmentOptionVO deptOption(long id, String path) {
        DepartmentOptionVO option = new DepartmentOptionVO();
        option.setId(id);
        option.setDeptName(path);
        option.setDisplayPath(path);
        return option;
    }

    private RoleVO roleOption(String code, String name) {
        RoleVO role = new RoleVO();
        role.setRoleCode(code);
        role.setRoleName(name);
        return role;
    }

    private User user(long id, boolean enabled, boolean dimission) {
        User user = new User();
        user.setId(id);
        user.setUsername("u" + id);
        user.setDisplayName("用户" + id);
        user.setEnabled(enabled);
        user.setDimission(dimission);
        return user;
    }

    // ------------------------------------------------------------------
    // 读：配置面板
    // ------------------------------------------------------------------

    @Test
    @DisplayName("按部门且未指定：标记「用默认」，生效部门 = 最终处理部门，命中人数取自同一个解析器")
    void describeDepartmentUsingDefault() {
        when(assigneeResolver.currentType()).thenReturn(FaultRepairSettings.AssigneeType.DEPARTMENT);
        when(assigneeResolver.currentValue()).thenReturn("");
        when(assigneeResolver.effectiveValue()).thenReturn("5");
        when(departmentService.options()).thenReturn(List.of(deptOption(5L, "公司 / IT运维组")));
        when(assigneeResolver.resolve()).thenReturn(List.of(1L, 2L));

        FaultRepairHandlerConfigVO vo = service.describe();

        assertTrue(vo.isUsingDefault(), "空值不是「忘了填」，而是一条会跟随组织架构变动的规则，页面必须标出来");
        assertEquals("DEPARTMENT", vo.getAssigneeType());
        assertEquals("", vo.getAssigneeValue(), "回显的必须是**配置值**（空），而不是生效值 —— 否则一保存就把默认变成写死");
        assertEquals("5", vo.getEffectiveValue());
        assertTrue(vo.getEffectiveLabel().contains("IT运维组"));
        assertTrue(vo.getEffectiveLabel().contains("默认使用最终处理部门"));
        assertEquals(2, vo.getResolvedCount());
    }

    @Test
    @DisplayName("命中 0 人：如实返回 0（页面据此警示；这是不会报错的静默故障）")
    void describeReportsZeroWhenNobodyMatches() {
        when(assigneeResolver.currentType()).thenReturn(FaultRepairSettings.AssigneeType.ROLE);
        when(assigneeResolver.currentValue()).thenReturn("admin");
        when(assigneeResolver.effectiveValue()).thenReturn("admin");
        when(assigneeResolver.resolve()).thenReturn(List.of());

        FaultRepairHandlerConfigVO vo = service.describe();

        assertEquals(0, vo.getResolvedCount());
        assertFalse(vo.isUsingDefault());
    }

    @Test
    @DisplayName("回落默认值：目标为空时给一句可读提示，而不是留空（空串看起来像页面渲染坏了）")
    void describeFallsBackToReadablePlaceholder() {
        when(assigneeResolver.currentType()).thenReturn(FaultRepairSettings.AssigneeType.DEPARTMENT);
        when(assigneeResolver.currentValue()).thenReturn("");
        when(assigneeResolver.effectiveValue()).thenReturn("");

        FaultRepairHandlerConfigVO vo = service.describe();

        assertTrue(vo.getEffectiveLabel().contains("未配置"));
        assertEquals(0, vo.getResolvedCount());
    }

    // ------------------------------------------------------------------
    // 写：类型校验
    // ------------------------------------------------------------------

    @Test
    @DisplayName("★ 非法类型：拒绝，而不是静默回落成「按部门」")
    void saveRejectsUnknownTypeInsteadOfCoercing() {
        // AssigneeType.of() 会把非法值一律回落 DEPARTMENT —— 那是读侧的正确行为，
        // 用在写侧却会让「管理员选错」变成「按部门通知了另一批人」，且页面刷新后看着像保存成功了。
        assertEquals(ErrorCode.PARAM_INVALID,
                codeOf(() -> service.save("NOT_A_TYPE", "1")));
        verify(systemConfigService, never()).updateValue(any(), any());
    }

    @Test
    @DisplayName("类型缺失：拒绝（不猜一个默认值存下去）")
    void saveRejectsBlankType() {
        assertEquals(ErrorCode.PARAM_INVALID, codeOf(() -> service.save("  ", "1")));
        verify(systemConfigService, never()).updateValue(any(), any());
    }

    // ------------------------------------------------------------------
    // 写：取值校验
    // ------------------------------------------------------------------

    @Test
    @DisplayName("按部门留空：合法 —— 表示「跟随最终处理部门」，这是默认值也是唯一会自动跟随的选项")
    void saveDepartmentBlankIsAllowed() {
        service.save("DEPARTMENT", "");

        verify(systemConfigService).updateValue(FaultRepairSettings.KEY_ASSIGNEE_TYPE, "DEPARTMENT");
        verify(systemConfigService).updateValue(FaultRepairSettings.KEY_ASSIGNEE_VALUE, "");
    }

    @Test
    @DisplayName("按部门指定了不存在的部门：拒绝（写下去等于谁也通知不到，且不会报错）")
    void saveDepartmentUnknownIsRejected() {
        when(departmentService.options()).thenReturn(List.of(deptOption(5L, "公司 / IT运维组")));

        assertEquals(ErrorCode.PARAM_INVALID, codeOf(() -> service.save("DEPARTMENT", "999")));
        verify(systemConfigService, never()).updateValue(any(), any());
    }

    @Test
    @DisplayName("按部门填了非数字（如人名）：拒绝 —— 否则会静默存成一个解析时必然失败的值")
    void saveDepartmentNonNumericIsRejected() {
        assertEquals(ErrorCode.PARAM_INVALID, codeOf(() -> service.save("DEPARTMENT", "张三")));
        verify(systemConfigService, never()).updateValue(any(), any());
    }

    @Test
    @DisplayName("按角色留空：拒绝（留空 = 一条通知也发不出去，属于「保存成功但永远没人处理」）")
    void saveRoleBlankIsRejected() {
        assertEquals(ErrorCode.PARAM_INVALID, codeOf(() -> service.save("ROLE", " ")));
        verify(systemConfigService, never()).updateValue(any(), any());
    }

    @Test
    @DisplayName("按角色指定了不存在的角色：拒绝")
    void saveRoleUnknownIsRejected() {
        when(roleService.getByCode("ghost")).thenReturn(null);

        assertEquals(ErrorCode.PARAM_INVALID, codeOf(() -> service.save("ROLE", "ghost")));
        verify(systemConfigService, never()).updateValue(any(), any());
    }

    @Test
    @DisplayName("按角色合法：写入角色码")
    void saveRolePersists() {
        when(roleService.getByCode("admin")).thenReturn(new SysRole());

        service.save("ROLE", "admin");

        verify(systemConfigService).updateValue(FaultRepairSettings.KEY_ASSIGNEE_TYPE, "ROLE");
        verify(systemConfigService).updateValue(FaultRepairSettings.KEY_ASSIGNEE_VALUE, "admin");
    }

    @Test
    @DisplayName("指定人员留空：拒绝")
    void saveUserBlankIsRejected() {
        assertEquals(ErrorCode.PARAM_INVALID, codeOf(() -> service.save("USER", "")));
        verify(systemConfigService, never()).updateValue(any(), any());
    }

    @Test
    @DisplayName("★ 指定人员已离职 / 已停用：拒绝 —— 解析器会静默把他过滤掉，那是一条永远没人处理的通知")
    void saveDimissionUserIsRejected() {
        when(userMapper.selectById(7L)).thenReturn(user(7L, true, true));

        assertEquals(ErrorCode.PARAM_INVALID, codeOf(() -> service.save("USER", "7")));
        verify(systemConfigService, never()).updateValue(any(), any());
    }

    @Test
    @DisplayName("指定人员合法：写入用户 id")
    void saveUserPersists() {
        when(userMapper.selectById(7L)).thenReturn(user(7L, true, false));

        service.save("USER", "7");

        verify(systemConfigService).updateValue(FaultRepairSettings.KEY_ASSIGNEE_TYPE, "USER");
        verify(systemConfigService).updateValue(FaultRepairSettings.KEY_ASSIGNEE_VALUE, "7");
    }

    @Test
    @DisplayName("类型与值必须同批写入（只写成功一半会得到「类型=指定人员、值=部门 id」这种必然无人命中的组合）")
    void saveWritesBothKeys() {
        service.save("DEPARTMENT", "");

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(systemConfigService, times(2)).updateValue(key.capture(), anyString());
        assertEquals(
                List.of(FaultRepairSettings.KEY_ASSIGNEE_TYPE, FaultRepairSettings.KEY_ASSIGNEE_VALUE),
                key.getAllValues());
    }

    @Test
    @DisplayName("命中 0 人时**仍然保存成功**（「角色建好了但还没给人挂上」是配置中的正常中间态）")
    void saveSucceedsEvenWhenNobodyMatchesYet() {
        when(roleService.getByCode("admin")).thenReturn(new SysRole());
        when(assigneeResolver.resolve()).thenReturn(List.of());

        service.save("ROLE", "admin");

        verify(systemConfigService).updateValue(FaultRepairSettings.KEY_ASSIGNEE_TYPE, "ROLE");
    }
}
