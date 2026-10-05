package com.enterprise.ticket.module.ad.service.impl;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.RoleCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.ad.ldap.AdUser;
import com.enterprise.ticket.module.ad.service.AdConfigService;
import com.enterprise.ticket.module.ad.service.AdUserProvisioningService.ProvisionResult;
import com.enterprise.ticket.module.role.service.RoleService;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import com.enterprise.ticket.support.MyBatisLambdaCache;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AD 账号本地化（Phase 13；需求一.2）—— 单元测试
 *
 * <p>本服务是「同步」与「首次登录自动建号」两个入口<b>共用</b>的唯一实现，
 * 它的守卫一旦失效，后果都很重，因此逐条钉死：
 * <ul>
 *   <li><b>超管不参与</b>：AD 里同名账号不得改名 / 禁用超管；</li>
 *   <li><b>已转本地的账号不再被域控掌管</b>（第五道守卫）：否则一次定时同步会静默推翻管理员的「本地接管」决定；</li>
 *   <li><b>占位口令不可用</b>：AD 账号的本地 password_hash 是随机占位，不是可用凭据；</li>
 *   <li><b>单向禁用</b>：AD 禁用 → 本地禁用并作废会话；AD 启用不反向放开本地手工禁用。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AD 账号本地化")
class AdUserProvisioningServiceImplTest {

    @BeforeAll
    static void initLambdaCache() {
        MyBatisLambdaCache.init(User.class);
    }

    @Mock
    private UserMapper userMapper;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private AdConfigService adConfigService;
    @Mock
    private RoleService roleService;

    @InjectMocks
    private AdUserProvisioningServiceImpl service;

    private AdUser adUser(String account, String name, boolean disabled) {
        return new AdUser("CN=" + account + ",OU=x,DC=company,DC=com", account, name,
                account + "@company.com", null, "研发部", disabled, "guid-" + account);
    }

    private AdUser adUser(String account, String name, boolean disabled, String phone) {
        return new AdUser("CN=" + account + ",OU=x,DC=company,DC=com", account, name,
                account + "@company.com", phone, "研发部", disabled, "guid-" + account);
    }

    /**
     * 与 {@code adUser(account, name, false, phone)} 逐字段对齐的本地账号。
     *
     * <p>构造它的目的只有一个：让 {@code changed} 的判据里<b>只剩手机号一项</b>在变，
     * 于是「手机号规则是否生效」不会被姓名 / 邮箱 / 部门的噪音掩盖。
     */
    private User localMirroring(String account, String name, String phone) {
        User local = new User();
        local.setId(9L);
        local.setUsername(account);
        local.setRealName(name);
        local.setDisplayName(name);
        local.setEmail(account + "@company.com");
        local.setPhone(phone);
        local.setDepartment("研发部");
        local.setAdObjectGuid("guid-" + account);
        local.setLdapDn("CN=" + account + ",OU=x,DC=company,DC=com");
        local.setRole(RoleCode.USER);
        local.setAuthType("LDAP");
        local.setEnabled(true);
        return local;
    }

    private User localUser(Long id, String role, String authType, boolean enabled) {
        User user = new User();
        user.setId(id);
        user.setUsername("zhangwei");
        user.setRealName("旧姓名");
        user.setDisplayName("旧姓名");
        user.setRole(role);
        user.setAuthType(authType);
        user.setEnabled(enabled);
        user.setDimission(false);
        return user;
    }

    // ------------------------------------------------------------------
    // 入参守卫
    // ------------------------------------------------------------------

    @Test
    @DisplayName("adUser 为 null → PARAM_INVALID")
    void nullAdUser() {
        BusinessException ex = assertThrows(BusinessException.class, () -> service.provision(null));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
    }

    @Test
    @DisplayName("adUser 缺登录名 → PARAM_INVALID（不建号）")
    void unusableAdUser() {
        AdUser noAccount = new AdUser("CN=x", null, "无名", null, null, null, false, null);
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BusinessException.class, () -> service.provision(noAccount)).getErrorCode());

        AdUser blank = new AdUser("CN=x", "  ", "无名", null, null, null, false, null);
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BusinessException.class, () -> service.provision(blank)).getErrorCode());
    }

    // ------------------------------------------------------------------
    // 新建
    // ------------------------------------------------------------------

    @Test
    @DisplayName("本地无此账号 → 新建，角色取 AD 默认角色，账号来源为 LDAP")
    void createWhenAbsent() {
        AdUser ad = adUser("zhangwei", "张伟", false);
        when(userMapper.selectByUsername("zhangwei")).thenReturn(null);
        when(adConfigService.defaultRole()).thenReturn("user");
        when(roleService.isAssignable("user")).thenReturn(true);
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(passwordEncoder.encode(any())).thenReturn("hashed");

        ProvisionResult result = service.provision(ad);

        assertTrue(result.created());
        assertFalse(result.changed());
        User created = result.user();
        assertEquals("zhangwei", created.getUsername());
        assertEquals("张伟", created.getRealName());
        assertEquals("user", created.getRole());
        assertEquals("LDAP", created.getAuthType());
        assertTrue(created.getEnabled());
        assertFalse(created.getForceChangePassword(), "域账号不该被强制改本地密码");
        verify(userMapper).insert(any(User.class));
    }

    @Test
    @DisplayName("AD 默认角色不存在 / 已停用 → ROLE_NOT_ASSIGNABLE（不建出无法登录的脏账号）")
    void createFailsWhenDefaultRoleInvalid() {
        AdUser ad = adUser("zhangwei", "张伟", false);
        when(userMapper.selectByUsername("zhangwei")).thenReturn(null);
        when(adConfigService.defaultRole()).thenReturn("ghost-role");
        when(roleService.isAssignable("ghost-role")).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.provision(ad));
        assertEquals(ErrorCode.ROLE_NOT_ASSIGNABLE, ex.getErrorCode());
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    @DisplayName("AD 默认角色不存在时不得写库（先校验后写入）")
    void createDoesNotWriteBeforeRoleCheck() {
        AdUser ad = adUser("zhangwei", "张伟", false);
        when(userMapper.selectByUsername("zhangwei")).thenReturn(null);
        when(adConfigService.defaultRole()).thenReturn("bad");
        when(roleService.isAssignable("bad")).thenReturn(false);

        assertThrows(BusinessException.class, () -> service.provision(ad));
        verify(userMapper, never()).insert(any(User.class));
    }

    // ------------------------------------------------------------------
    // 守卫：超管 / 已转本地
    // ------------------------------------------------------------------

    @Test
    @DisplayName("超管同名账号 → 跳过（不新建、不刷新、不禁用）")
    void skipSuperAdmin() {
        User superAdmin = localUser(1L, RoleCode.SUPER_ADMIN, "LOCAL", true);
        when(userMapper.selectByUsername("zhangwei")).thenReturn(superAdmin);

        ProvisionResult result = service.provision(adUser("zhangwei", "AD 里的名字", true));

        assertFalse(result.created());
        assertFalse(result.changed());
        assertSame(superAdmin, result.user());
        verify(userMapper, never()).update(any(), any());
        verify(userMapper, never()).disableForAdRemoval(any());
    }

    @Test
    @DisplayName("已转为本地（auth_type=LOCAL）的账号 → 跳过，域控不再掌管")
    void skipLocalAccount() {
        User local = localUser(2L, RoleCode.USER, "LOCAL", true);
        when(userMapper.selectByUsername("zhangwei")).thenReturn(local);

        ProvisionResult result = service.provision(adUser("zhangwei", "AD 里的名字", true));

        assertFalse(result.created());
        assertFalse(result.changed());
        assertSame(local, result.user());
        verify(userMapper, never()).update(any(), any());
    }

    // ------------------------------------------------------------------
    // 刷新既有域账号
    // ------------------------------------------------------------------

    @Test
    @DisplayName("既有域账号属性变化 → 刷新并计为 changed")
    void refreshChanged() {
        User local = localUser(3L, RoleCode.USER, "LDAP", true);
        User refreshed = localUser(3L, RoleCode.USER, "LDAP", true);
        refreshed.setRealName("新姓名");
        when(userMapper.selectByUsername("zhangwei")).thenReturn(local, refreshed);
        when(userMapper.selectCount(any())).thenReturn(0L);

        ProvisionResult result = service.provision(adUser("zhangwei", "新姓名", false));

        assertFalse(result.created());
        assertTrue(result.changed());
        assertSame(refreshed, result.user());
        verify(userMapper).update(eq(null), any());
        verify(userMapper, never()).disableForAdRemoval(any());
    }

    @Test
    @DisplayName("既有域账号无变化 → 仍写同步时间但计为 unchanged")
    void refreshUnchanged() {
        User local = new User();
        local.setId(4L);
        local.setUsername("zhangwei");
        local.setRealName("张伟");
        local.setDisplayName("张伟");
        local.setEmail("zhangwei@company.com");
        local.setDepartment("研发部");
        local.setAdObjectGuid("guid-zhangwei");
        local.setLdapDn("CN=zhangwei,OU=x,DC=company,DC=com");
        local.setRole(RoleCode.USER);
        local.setAuthType("LDAP");
        local.setEnabled(true);
        when(userMapper.selectByUsername("zhangwei")).thenReturn(local, local);
        when(userMapper.selectCount(any())).thenReturn(0L);

        ProvisionResult result = service.provision(adUser("zhangwei", "张伟", false));

        assertFalse(result.created());
        assertFalse(result.changed());
        verify(userMapper).update(eq(null), any());
    }

    @Test
    @DisplayName("AD 侧已禁用且本地仍启用 → 本地禁用并作废会话（单向）")
    void refreshDisablesWhenAdDisabled() {
        User local = localUser(5L, RoleCode.USER, "LDAP", true);
        when(userMapper.selectByUsername("zhangwei")).thenReturn(local, local);
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(userMapper.disableForAdRemoval(5L)).thenReturn(1);

        ProvisionResult result = service.provision(adUser("zhangwei", "旧姓名", true));

        assertTrue(result.changed());
        verify(userMapper).disableForAdRemoval(5L);
    }

    @Test
    @DisplayName("AD 侧启用不会反向放开本地手工禁用")
    void adEnabledDoesNotReEnableLocal() {
        User local = localUser(6L, RoleCode.USER, "LDAP", false);
        when(userMapper.selectByUsername("zhangwei")).thenReturn(local, local);
        when(userMapper.selectCount(any())).thenReturn(0L);

        ProvisionResult result = service.provision(adUser("zhangwei", "旧姓名", false));

        // 账号在本地下仍是禁用的：本次既未走 disableForAdRemoval，也没有任何「放开」的写入
        assertFalse(result.user().getEnabled());
        verify(userMapper, never()).disableForAdRemoval(any());
    }

    @Test
    @DisplayName("姓名与既有员工重复 → 回退用登录名作为本地姓名")
    void uniqueRealNameFallsBackToAccount() {
        AdUser ad = adUser("zhangwei", "张伟", false);
        when(userMapper.selectByUsername("zhangwei")).thenReturn(null);
        when(adConfigService.defaultRole()).thenReturn("user");
        when(roleService.isAssignable("user")).thenReturn(true);
        // 已有同名员工 → 冲突
        when(userMapper.selectCount(any())).thenReturn(1L);
        when(passwordEncoder.encode(any())).thenReturn("hashed");

        ProvisionResult result = service.provision(ad);

        assertEquals("zhangwei", result.user().getRealName(), "姓名冲突时应回退登录名");
    }

    // ------------------------------------------------------------------
    // 手机号（Phase 19 批次 E）：AD 权威，但受「唯一性 + 不覆盖本地已绑定值」两条约束
    // ------------------------------------------------------------------

    @Test
    @DisplayName("新建时写入 AD 手机号（省掉每个人手工补录）")
    void createWritesAdPhone() {
        when(userMapper.selectByUsername("zhangwei")).thenReturn(null);
        when(adConfigService.defaultRole()).thenReturn("user");
        when(roleService.isAssignable("user")).thenReturn(true);
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(passwordEncoder.encode(any())).thenReturn("hashed");

        ProvisionResult result = service.provision(adUser("zhangwei", "张伟", false, "13800001111"));

        assertTrue(result.created());
        assertEquals("13800001111", result.user().getPhone());
    }

    @Test
    @DisplayName("新建时 AD 手机号已被其他员工占用 → 只跳过手机号，建号仍成功")
    void createSkipsPhoneWhenTakenByOther() {
        when(userMapper.selectByUsername("zhangwei")).thenReturn(null);
        when(adConfigService.defaultRole()).thenReturn("user");
        when(roleService.isAssignable("user")).thenReturn(true);
        // 第 1 次 selectCount = 姓名唯一性预检（0 = 不冲突）；第 2 次 = 手机号占用检查（1 = 已被占用）
        when(userMapper.selectCount(any())).thenReturn(0L, 1L);
        when(passwordEncoder.encode(any())).thenReturn("hashed");

        ProvisionResult result = service.provision(adUser("zhangwei", "张伟", false, "13800001111"));

        assertTrue(result.created(), "手机号冲突绝不能把整条建号拖成失败");
        assertNull(result.user().getPhone(), "被占用时应放弃该字段而不是抛唯一键冲突");
    }

    @Test
    @DisplayName("AD 手机号有值 → 覆盖本地空值并计为 changed")
    void refreshAdoptsAdPhone() {
        User local = localMirroring("zhangwei", "张伟", null);
        when(userMapper.selectByUsername("zhangwei")).thenReturn(local, local);
        when(userMapper.selectCount(any())).thenReturn(0L);

        ProvisionResult result = service.provision(adUser("zhangwei", "张伟", false, "13800001111"));

        assertTrue(result.changed(), "手机号从无到有应计为 changed");
        verify(userMapper).update(eq(null), any());
    }

    @Test
    @DisplayName("AD 未提供手机号 → 保留本地已绑定号码（抹掉=那个人再也拿不回密码）")
    void refreshKeepsLocalPhoneWhenAdProvidesNone() {
        User local = localMirroring("zhangwei", "张伟", "13900000000");
        when(userMapper.selectByUsername("zhangwei")).thenReturn(local, local);
        when(userMapper.selectCount(any())).thenReturn(0L);

        ProvisionResult result = service.provision(adUser("zhangwei", "张伟", false, null));

        assertFalse(result.changed(), "AD 手机号为空时不得清空本地值");
    }

    @Test
    @DisplayName("AD 手机号已被他人占用 → 保留本地现值（不撞 users.phone 唯一索引）")
    void refreshKeepsLocalPhoneWhenAdPhoneTakenByOther() {
        User local = localMirroring("zhangwei", "张伟", "13900000000");
        when(userMapper.selectByUsername("zhangwei")).thenReturn(local, local);
        when(userMapper.selectCount(any())).thenReturn(0L, 1L);

        ProvisionResult result = service.provision(adUser("zhangwei", "张伟", false, "13800001111"));

        assertFalse(result.changed(), "AD 手机号被占用时应保留本地现值，其余字段该刷还是刷");
    }
}
