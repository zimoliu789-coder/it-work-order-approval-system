package com.enterprise.ticket.module.ad.service.impl;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.ad.ldap.AdAttributeMapping;
import com.enterprise.ticket.module.ad.ldap.AdConnection;
import com.enterprise.ticket.module.ad.ldap.AdDirectoryClient;
import com.enterprise.ticket.module.ad.ldap.AdDirectoryException;
import com.enterprise.ticket.module.ad.ldap.AdUser;
import com.enterprise.ticket.module.ad.service.AdAuthOutcome;
import com.enterprise.ticket.module.ad.service.AdAuthResult;
import com.enterprise.ticket.module.ad.service.AdConfigService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AD 域认证（Phase 13；需求一.3 登录流程）—— 单元测试
 *
 * <p>本类不抛异常，而是把「域控出了什么事」收敛成 {@link AdAuthOutcome}。
 * 之所以值得单测，是因为四个分类对应登录流程里<b>完全不同</b>的降级动作，
 * 合并任意两个都会产生产品缺陷：
 * <ul>
 *   <li>{@code USER_NOT_FOUND} vs {@code BAD_CREDENTIALS}：前者是本地账号（回退本地），
 *       后者是真口令错（<b>不回退</b>，否则可用旧本地口令绕过域控策略）；
 *   <li>{@code UNAVAILABLE}：域控不可用时本地账号必须仍能登录；
 *   <li>{@code ACCOUNT_DISABLED}：先判禁用再验口令，避免把「已禁用」误导成「密码错误」。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AD 域认证")
class AdAuthenticationServiceImplTest {

    @Mock
    private AdConfigService adConfigService;
    @Mock
    private AdDirectoryClient directoryClient;

    @InjectMocks
    private AdAuthenticationServiceImpl service;

    private final AdConnection connection = new AdConnection(
            List.of("dc1.company.com"), 389, false, true,
            "DC=company,DC=com", "CN=query,DC=company,DC=com", "pwd", 5,
            AdConnection.DEFAULT_FILTER,
            new AdAttributeMapping("sAMAccountName", "displayName", "mail", null, "department", "userAccountControl"));

    // ------------------------------------------------------------------
    // 入参守卫
    // ------------------------------------------------------------------

    @Test
    @DisplayName("空账号或空口令 → BAD_CREDENTIALS（拒绝匿名绑定后门，且不建连接）")
    void emptyCredentials() {
        AdAuthResult blankPassword = service.authenticate("zhangwei", "");
        assertEquals(AdAuthOutcome.BAD_CREDENTIALS, blankPassword.outcome());

        AdAuthResult blankAccount = service.authenticate("", "pwd");
        assertEquals(AdAuthOutcome.BAD_CREDENTIALS, blankAccount.outcome());

        verify(adConfigService, never()).activeConnection();
    }

    // ------------------------------------------------------------------
    // 分类：成功与三类失败
    // ------------------------------------------------------------------

    @Test
    @DisplayName("配置不可用（未启用 / 缺项）→ UNAVAILABLE，本地账号仍需能登录")
    void configUnavailable() {
        when(adConfigService.activeConnection())
                .thenThrow(new BusinessException(ErrorCode.AD_DISABLED, "AD 未启用"));

        AdAuthResult result = service.authenticate("zhangwei", "pwd");

        assertEquals(AdAuthOutcome.UNAVAILABLE, result.outcome());
        assertNull(result.user());
    }

    @Test
    @DisplayName("AD 中无此账号 → USER_NOT_FOUND（登录流程应回退本地）")
    void userNotFound() {
        when(adConfigService.activeConnection()).thenReturn(connection);
        when(directoryClient.findByAccount(connection, "zhangwei"))
                .thenThrow(new AdDirectoryException(AdDirectoryException.Kind.USER_NOT_FOUND, "无此用户"));

        assertEquals(AdAuthOutcome.USER_NOT_FOUND, service.authenticate("zhangwei", "pwd").outcome());
    }

    @Test
    @DisplayName("域口令错误 → BAD_CREDENTIALS（登录流程不得回退本地）")
    void badCredentials() {
        when(adConfigService.activeConnection()).thenReturn(connection);
        AdUser user = new AdUser("CN=zhangwei", "zhangwei", "张伟", null, null, null, false, null);
        when(directoryClient.findByAccount(connection, "zhangwei")).thenReturn(user);
        org.mockito.Mockito.doThrow(new AdDirectoryException(AdDirectoryException.Kind.BAD_CREDENTIALS, "拒绝"))
                .when(directoryClient).verifyUserCredentials(connection, user.dn(), "wrong");

        assertEquals(AdAuthOutcome.BAD_CREDENTIALS, service.authenticate("zhangwei", "wrong").outcome());
    }

    @Test
    @DisplayName("域账号已禁用 → ACCOUNT_DISABLED，且不再尝试校验口令")
    void accountDisabled() {
        when(adConfigService.activeConnection()).thenReturn(connection);
        AdUser disabled = new AdUser("CN=zhangwei", "zhangwei", "张伟", null, null, null, true, null);
        when(directoryClient.findByAccount(connection, "zhangwei")).thenReturn(disabled);

        AdAuthResult result = service.authenticate("zhangwei", "whatever");

        assertEquals(AdAuthOutcome.ACCOUNT_DISABLED, result.outcome());
        verify(directoryClient, never()).verifyUserCredentials(any(), any(), any());
    }

    @Test
    @DisplayName("域口令正确 → SUCCESS 并带回 AD 用户属性")
    void success() {
        when(adConfigService.activeConnection()).thenReturn(connection);
        AdUser user = new AdUser("CN=zhangwei", "zhangwei", "张伟", "z@company.com", null, "研发部", false, "guid1");
        when(directoryClient.findByAccount(connection, "zhangwei")).thenReturn(user);

        AdAuthResult result = service.authenticate("zhangwei", "correct");

        assertTrue(result.isSuccess());
        assertEquals(AdAuthOutcome.SUCCESS, result.outcome());
        assertEquals("zhangwei", result.user().account());
        verify(directoryClient).verifyUserCredentials(eq(connection), eq(user.dn()), eq("correct"));
    }

    @Test
    @DisplayName("配置错误（CONFIG）→ 按 UNAVAILABLE 处理（不把所有人锁在门外）")
    void configErrorKindDegrades() {
        when(adConfigService.activeConnection()).thenReturn(connection);
        when(directoryClient.findByAccount(connection, "zhangwei"))
                .thenThrow(new AdDirectoryException(AdDirectoryException.Kind.CONFIG, "缺 baseDn"));

        assertEquals(AdAuthOutcome.UNAVAILABLE, service.authenticate("zhangwei", "pwd").outcome());
    }

    @Test
    @DisplayName("未预期异常 → 兜底为 UNAVAILABLE（登录接口绝不 500）")
    void unexpectedExceptionDegrades() {
        when(adConfigService.activeConnection()).thenReturn(connection);
        when(directoryClient.findByAccount(connection, "zhangwei"))
                .thenThrow(new IllegalStateException("boom"));

        assertEquals(AdAuthOutcome.UNAVAILABLE, service.authenticate("zhangwei", "pwd").outcome());
    }
}
