package com.enterprise.ticket.module.ad.service.impl;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.RoleCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.ad.dto.AdAccountConvertVO;
import com.enterprise.ticket.module.ad.ldap.AdAttributeMapping;
import com.enterprise.ticket.module.ad.ldap.AdConnection;
import com.enterprise.ticket.module.ad.ldap.AdDirectoryClient;
import com.enterprise.ticket.module.ad.ldap.AdDirectoryException;
import com.enterprise.ticket.module.ad.ldap.AdUser;
import com.enterprise.ticket.module.ad.service.AdConfigService;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 账号来源互转（Phase 13；需求一.4）—— 单元测试
 *
 * <p>两个方向都会让账号「只能通过另一种方式登录」，写错一步就是把账号弄成死号，
 * 因此测试重点是<b>前置校验必须全部排在写入之前</b>，以及几条不可越过的护栏：
 * <ul>
 *   <li>超管固定本地认证，不可转换；</li>
 *   <li>「本地 → AD」必须先在域控确认存在该账号（不存在则拒绝，且不做任何修改）；</li>
 *   <li>AD 未启用时不可转成域账号（转过去 = 既无本地口令、又不走域认证 = 永远登不进来）。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("账号来源互转")
class AdAccountConversionServiceImplTest {

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
    private AdDirectoryClient directoryClient;

    @InjectMocks
    private AdAccountConversionServiceImpl service;

    private final AdConnection connection = new AdConnection(
            List.of("dc1.company.com"), 389, false, true,
            "DC=company,DC=com", "CN=query,DC=company,DC=com", "pwd", 5,
            AdConnection.DEFAULT_FILTER,
            new AdAttributeMapping("sAMAccountName", "displayName", "mail", null, "department", "userAccountControl"));

    private User user(Long id, String role, String authType) {
        User user = new User();
        user.setId(id);
        user.setUsername("zhangwei");
        user.setRealName("张伟");
        user.setDisplayName("张伟");
        user.setRole(role);
        user.setAuthType(authType);
        user.setEnabled(true);
        return user;
    }

    // ==================================================================
    // AD → 本地
    // ==================================================================

    @Test
    @DisplayName("AD→本地：用户不存在 → USER_NOT_FOUND")
    void toLocalUserNotFound() {
        when(userMapper.selectById(9L)).thenReturn(null);
        assertEquals(ErrorCode.USER_NOT_FOUND,
                assertThrows(BusinessException.class, () -> service.convertToLocal(9L)).getErrorCode());
    }

    @Test
    @DisplayName("AD→本地：超管不可转换 → USER_SUPER_ADMIN_PROTECTED")
    void toLocalSuperAdminProtected() {
        when(userMapper.selectById(1L)).thenReturn(user(1L, RoleCode.SUPER_ADMIN, "LOCAL"));

        assertEquals(ErrorCode.USER_SUPER_ADMIN_PROTECTED,
                assertThrows(BusinessException.class, () -> service.convertToLocal(1L)).getErrorCode());
        verify(userMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("AD→本地：本来已是本地账号 → AD_ACCOUNT_ALREADY_LOCAL（不重复转换）")
    void toLocalAlreadyLocal() {
        when(userMapper.selectById(2L)).thenReturn(user(2L, RoleCode.USER, "LOCAL"));

        assertEquals(ErrorCode.AD_ACCOUNT_ALREADY_LOCAL,
                assertThrows(BusinessException.class, () -> service.convertToLocal(2L)).getErrorCode());
        verify(userMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("AD→本地：成功生成一次性临时口令，且只在此返回")
    void toLocalSuccess() {
        when(userMapper.selectById(3L)).thenReturn(user(3L, RoleCode.USER, "LDAP"));
        when(passwordEncoder.encode(any())).thenReturn("hashed-temp");

        AdAccountConvertVO vo = service.convertToLocal(3L);

        assertNotNull(vo.getTemporaryPassword(), "应返回一次性临时口令");
        assertEquals("LOCAL", vo.getAuthType());
        assertEquals("本地", vo.getAuthTypeLabel());
        assertEquals("zhangwei", vo.getUsername());
        assertTrue(vo.getMessage().contains("已转为本地账号"));
        verify(userMapper).update(eq(null), any());
    }

    // ==================================================================
    // 本地 → AD
    // ==================================================================

    @Test
    @DisplayName("本地→AD：用户不存在 → USER_NOT_FOUND")
    void toLdapUserNotFound() {
        when(userMapper.selectById(9L)).thenReturn(null);
        assertEquals(ErrorCode.USER_NOT_FOUND,
                assertThrows(BusinessException.class, () -> service.convertToLdap(9L)).getErrorCode());
    }

    @Test
    @DisplayName("本地→AD：超管不可转换 → USER_SUPER_ADMIN_PROTECTED")
    void toLdapSuperAdminProtected() {
        when(userMapper.selectById(1L)).thenReturn(user(1L, RoleCode.SUPER_ADMIN, "LOCAL"));

        assertEquals(ErrorCode.USER_SUPER_ADMIN_PROTECTED,
                assertThrows(BusinessException.class, () -> service.convertToLdap(1L)).getErrorCode());
        verify(userMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("本地→AD：已是域账号 → AD_ACCOUNT_ALREADY_LDAP")
    void toLdapAlreadyLdap() {
        when(userMapper.selectById(4L)).thenReturn(user(4L, RoleCode.USER, "LDAP"));

        assertEquals(ErrorCode.AD_ACCOUNT_ALREADY_LDAP,
                assertThrows(BusinessException.class, () -> service.convertToLdap(4L)).getErrorCode());
        verify(userMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("本地→AD：AD 未启用 → AD_DISABLED（转过去会变死号）")
    void toLdapDisabled() {
        when(userMapper.selectById(5L)).thenReturn(user(5L, RoleCode.USER, "LOCAL"));
        when(adConfigService.isEnabled()).thenReturn(false);

        assertEquals(ErrorCode.AD_DISABLED,
                assertThrows(BusinessException.class, () -> service.convertToLdap(5L)).getErrorCode());
        verify(userMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("本地→AD：域控中查无此人 → AD_ACCOUNT_NOT_IN_DIRECTORY，且不写库")
    void toLdapNotInDirectory() {
        when(userMapper.selectById(6L)).thenReturn(user(6L, RoleCode.USER, "LOCAL"));
        when(adConfigService.isEnabled()).thenReturn(true);
        when(adConfigService.activeConnection()).thenReturn(connection);
        when(directoryClient.findByAccount(connection, "zhangwei"))
                .thenThrow(new AdDirectoryException(AdDirectoryException.Kind.USER_NOT_FOUND, "无此用户"));

        assertEquals(ErrorCode.AD_ACCOUNT_NOT_IN_DIRECTORY,
                assertThrows(BusinessException.class, () -> service.convertToLdap(6L)).getErrorCode());
        verify(userMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("本地→AD：域控暂不可用 → AD_TEST_FAILED，且不写库（本次未做任何修改）")
    void toLdapDirectoryUnavailable() {
        when(userMapper.selectById(7L)).thenReturn(user(7L, RoleCode.USER, "LOCAL"));
        when(adConfigService.isEnabled()).thenReturn(true);
        when(adConfigService.activeConnection()).thenReturn(connection);
        when(directoryClient.findByAccount(connection, "zhangwei"))
                .thenThrow(new AdDirectoryException(AdDirectoryException.Kind.UNAVAILABLE, "连接超时"));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.convertToLdap(7L));
        assertEquals(ErrorCode.AD_TEST_FAILED, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("未做任何修改"));
        verify(userMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("本地→AD：成功，返回 AD 来源且无临时口令")
    void toLdapSuccess() {
        when(userMapper.selectById(8L)).thenReturn(user(8L, RoleCode.USER, "LOCAL"));
        when(adConfigService.isEnabled()).thenReturn(true);
        when(adConfigService.activeConnection()).thenReturn(connection);
        when(directoryClient.findByAccount(connection, "zhangwei")).thenReturn(
                new AdUser("CN=zhangwei,DC=company,DC=com", "zhangwei", "张伟",
                        "zhangwei@company.com", null, "研发部", false, "guid1"));
        when(passwordEncoder.encode(any())).thenReturn("hashed-random");

        AdAccountConvertVO vo = service.convertToLdap(8L);

        assertNull(vo.getTemporaryPassword(), "本地→AD 不该返回临时口令");
        assertEquals("LDAP", vo.getAuthType());
        assertEquals("AD", vo.getAuthTypeLabel());
        assertTrue(vo.getMessage().contains("已转为 AD 域账号"));
        verify(userMapper).update(eq(null), any());
    }

    @Test
    @DisplayName("本地→AD：域控侧已禁用 → 提示需先在域控启用（本地随之置禁用）")
    void toLdapDisabledInAd() {
        when(userMapper.selectById(10L)).thenReturn(user(10L, RoleCode.USER, "LOCAL"));
        when(adConfigService.isEnabled()).thenReturn(true);
        when(adConfigService.activeConnection()).thenReturn(connection);
        when(directoryClient.findByAccount(connection, "zhangwei")).thenReturn(
                new AdUser("CN=zhangwei,DC=company,DC=com", "zhangwei", "张伟",
                        null, null, null, true, null));
        when(passwordEncoder.encode(any())).thenReturn("hashed-random");

        AdAccountConvertVO vo = service.convertToLdap(10L);

        assertEquals("LDAP", vo.getAuthType());
        assertTrue(vo.getMessage().contains("禁用"), "应提示域控侧处于禁用状态");
        verify(userMapper).update(eq(null), any());
    }
}
