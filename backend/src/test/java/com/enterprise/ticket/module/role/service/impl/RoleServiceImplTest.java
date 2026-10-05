package com.enterprise.ticket.module.role.service.impl;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.RoleCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.permission.PermissionCatalog;
import com.enterprise.ticket.module.role.dto.RoleSaveRequest;
import com.enterprise.ticket.module.role.dto.vo.RoleVO;
import com.enterprise.ticket.module.role.entity.SysRole;
import com.enterprise.ticket.module.role.entity.SysRolePermission;
import com.enterprise.ticket.module.role.mapper.SysRoleMapper;
import com.enterprise.ticket.module.role.mapper.SysRolePermissionMapper;
import com.enterprise.ticket.module.role.service.RoleService;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import com.enterprise.ticket.support.MyBatisLambdaCache;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 角色与权限服务单测（需求方三波·第一波·需求 4）
 *
 * <p>这是三波补做里<b>唯一触及「谁能做什么」的模块</b>，出错后果最重，故集中固化四类护栏：
 * <ol>
 *   <li><b>超管恒全量</b>：超管权限不来自库，代码硬保证，避免误删授权把系统锁死；</li>
 *   <li><b>可分配性</b>：只有「存在且启用」的角色能分配给员工（否则会出现「有角色值但无权限」的幽灵角色）；</li>
 *   <li><b>内置保护</b>：内置编码不可被自建角色冒名、内置角色不可删除；</li>
 *   <li><b>缓存正确性</b>：权限热数据首次查库后进缓存，并在授权变更时及时失效。</li>
 * </ol>
 *
 * <p>注入说明：{@code getByCode} 走 {@code ServiceImpl#getOne(queryWrapper, false)}，
 * 它在本项目使用的 MyBatis-Plus 版本里委托给 <b>两参</b> {@code baseMapper.selectOne(wrapper, throwEx)}
 * （而非 {@code selectList}，也不是单参 {@code selectOne}）——stub 时必须用
 * {@code selectOne(any(), anyBoolean())}，用错重载会触发严格模式下的参数不匹配报错。
 * {@code baseMapper} 是父类受保护字段，需显式反射注入。
 */
@ExtendWith(MockitoExtension.class)
class RoleServiceImplTest {

    @BeforeAll
    static void initMyBatisLambdaCache() {
        MyBatisLambdaCache.init(SysRole.class, SysRolePermission.class, User.class);
    }

    @Mock
    private SysRoleMapper roleMapper;
    @Mock
    private SysRolePermissionMapper rolePermissionMapper;
    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private RoleServiceImpl service;

    @BeforeEach
    void injectBaseMapper() {
        ReflectionTestUtils.setField(service, "baseMapper", roleMapper);
    }

    private static ErrorCode errorCodeOf(Runnable action) {
        return assertThrows(BusinessException.class, action::run).getErrorCode();
    }

    private static SysRole role(String code, boolean enabled, boolean builtin) {
        SysRole role = new SysRole();
        role.setId(1L);
        role.setRoleCode(code);
        role.setRoleName(code);
        role.setDataScope(RoleService.SCOPE_SELF);
        role.setEnabled(enabled);
        role.setBuiltin(builtin);
        role.setSortNo(100);
        return role;
    }

    // ------------------------------------------------------------------
    // 权限判定
    // ------------------------------------------------------------------

    @Test
    @DisplayName("超管权限恒为全量，且不查授权表")
    void superAdminHasAllCodes() {
        assertEquals(PermissionCatalog.allCodes(), service.permissionsOf(RoleCode.SUPER_ADMIN));
        assertTrue(service.hasPermission(RoleCode.SUPER_ADMIN, "any:unknown:perm"));
        verifyNoInteractions(rolePermissionMapper);
    }

    @Test
    @DisplayName("isAssignable：存在且启用的角色可分配")
    void isAssignableRequiresEnabledRole() {
        when(roleMapper.selectOne(any(), anyBoolean())).thenReturn(role("auditor", true, false));

        assertTrue(service.isAssignable("auditor"));
    }

    @Test
    @DisplayName("isAssignable：角色被停用则不可分配")
    void disabledRoleIsNotAssignable() {
        when(roleMapper.selectOne(any(), anyBoolean())).thenReturn(role("auditor", false, false));

        assertFalse(service.isAssignable("auditor"));
    }

    @Test
    @DisplayName("isAssignable：角色不存在则不可分配")
    void missingRoleIsNotAssignable() {
        when(roleMapper.selectOne(any(), anyBoolean())).thenReturn(null);

        assertFalse(service.isAssignable("ghost"));
    }

    @Test
    @DisplayName("permissionsOf：首次查库后进缓存，二次调用不再查库")
    void permissionsCachedAfterFirstLoad() {
        SysRolePermission row = new SysRolePermission();
        row.setRoleCode("auditor");
        row.setPermCode(PermissionCatalog.USAGE_VIEW);
        when(rolePermissionMapper.selectList(any())).thenReturn(List.of(row));

        assertEquals(Set.of(PermissionCatalog.USAGE_VIEW), service.permissionsOf("auditor"));
        assertEquals(Set.of(PermissionCatalog.USAGE_VIEW), service.permissionsOf("auditor"));
        verify(rolePermissionMapper, times(1)).selectList(any());
    }

    @Test
    @DisplayName("permissionsOf：过滤权限目录中已不存在的历史码")
    void permissionsFilterUnknownCodes() {
        SysRolePermission stale = new SysRolePermission();
        stale.setRoleCode("auditor");
        stale.setPermCode("legacy:gone:view");
        when(rolePermissionMapper.selectList(any())).thenReturn(List.of(stale));

        assertTrue(service.permissionsOf("auditor").isEmpty(), "目录里没有的权限码必须被丢弃");
    }

    // ------------------------------------------------------------------
    // 授权写入
    // ------------------------------------------------------------------

    @Test
    @DisplayName("setPermissions：拒绝修改 super_admin 权限且不产生任何写入")
    void setPermissionsRejectsSuperAdmin() {
        when(roleMapper.selectOne(any(), anyBoolean())).thenReturn(role(RoleCode.SUPER_ADMIN, true, true));

        assertEquals(ErrorCode.ROLE_SUPER_ADMIN_READONLY,
                errorCodeOf(() -> service.setPermissions(RoleCode.SUPER_ADMIN, List.of(PermissionCatalog.USAGE_VIEW))));
        verify(rolePermissionMapper, never()).delete(any());
    }

    @Test
    @DisplayName("setPermissions：未知权限码被拒绝，先校验后写入（不留半份授权）")
    void setPermissionsRejectsUnknownCode() {
        when(roleMapper.selectOne(any(), anyBoolean())).thenReturn(role("auditor", true, false));

        assertEquals(ErrorCode.ROLE_PERMISSION_INVALID,
                errorCodeOf(() -> service.setPermissions("auditor", List.of("nope:nope:nope"))));
        verify(rolePermissionMapper, never()).delete(any());
    }

    @Test
    @DisplayName("setPermissions：覆盖式写入（先删后插）并返回授权项数")
    void setPermissionsRewritesGrantedSet() {
        when(roleMapper.selectOne(any(), anyBoolean())).thenReturn(role("auditor", true, false));

        int written = service.setPermissions("auditor",
                List.of(PermissionCatalog.USAGE_VIEW, PermissionCatalog.STAFF_VIEW));

        assertEquals(2, written);
        verify(rolePermissionMapper).delete(any());
        verify(rolePermissionMapper, times(2)).insert(any(SysRolePermission.class));
    }

    // ------------------------------------------------------------------
    // 内置保护
    // ------------------------------------------------------------------

    @Test
    @DisplayName("createRole：内置编码被保留，禁止新建同名自建角色")
    void createRoleRejectsBuiltinCode() {
        when(roleMapper.selectOne(any(), anyBoolean())).thenReturn(null);

        RoleSaveRequest request = new RoleSaveRequest();
        request.setRoleCode(RoleCode.ADMIN);
        request.setRoleName("冒名管理员");
        request.setDataScope(RoleService.SCOPE_ALL);

        assertEquals(ErrorCode.ROLE_BUILTIN_PROTECTED, errorCodeOf(() -> service.createRole(request)));
        verify(roleMapper, never()).insert(any(SysRole.class));
    }

    @Test
    @DisplayName("deleteRole：内置角色不可删除")
    void deleteRoleRejectsBuiltin() {
        when(roleMapper.selectOne(any(), anyBoolean())).thenReturn(role(RoleCode.ADMIN, true, true));

        assertEquals(ErrorCode.ROLE_BUILTIN_PROTECTED, errorCodeOf(() -> service.deleteRole(RoleCode.ADMIN)));
        verify(roleMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("deleteRole：仍被员工使用时拒绝删除")
    void deleteRoleRejectsInUse() {
        when(roleMapper.selectOne(any(), anyBoolean())).thenReturn(role("auditor", true, false));
        when(userMapper.selectCount(any())).thenReturn(2L);

        assertEquals(ErrorCode.ROLE_IN_USE, errorCodeOf(() -> service.deleteRole("auditor")));
        verify(roleMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("deleteRole：无人在用时删除角色并清理授权行")
    void deleteRoleRemovesRoleAndGrants() {
        when(roleMapper.selectOne(any(), anyBoolean())).thenReturn(role("auditor", true, false));
        when(userMapper.selectCount(any())).thenReturn(0L);

        service.deleteRole("auditor");

        verify(rolePermissionMapper).delete(any());
        verify(roleMapper).deleteById(anyLong());
    }

    @Test
    @DisplayName("createRole：成功创建自建角色并写入授权")
    void createRoleWritesRoleAndGrants() {
        SysRole created = role("auditor", true, false);
        created.setRoleName("审计员");
        // 第 1 次 selectOne：exists() 判重返回 null；第 2 次：getRole() 返回新建角色
        when(roleMapper.selectOne(any(), anyBoolean())).thenReturn(null, created);
        when(roleMapper.insert(any(SysRole.class))).thenReturn(1);

        RoleSaveRequest request = new RoleSaveRequest();
        request.setRoleCode("auditor");
        request.setRoleName("审计员");
        request.setDataScope(RoleService.SCOPE_ALL);
        request.setPermissions(List.of(PermissionCatalog.LOG_VIEW));

        RoleVO vo = service.createRole(request);

        assertEquals("auditor", vo.getRoleCode());
        verify(rolePermissionMapper).insert(any(SysRolePermission.class));
    }
}
