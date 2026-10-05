package com.enterprise.ticket.common.util;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 可信代理链解析单测（部署与限流加固）
 *
 * <p>这是本次加固中最关键的一段逻辑：它决定了「限流到底按谁计数、审计到底记谁」。
 * 历史上 {@code getClientIp} 无条件采信 {@code X-Forwarded-For}，导致攻击者只要
 * 伪造一串 IP，就能让「账号 + IP」登录限流每次落在不同键上而完全失效。
 * 本类把「先判对端是否可信 → 再沿链回溯」这两段行为逐条钉死。
 *
 * <p>注意：{@code trustedProxies} 是静态配置，测试间必须复原，否则会污染其它用例。
 */
class SecurityUtilsTrustedProxyTest {

    @BeforeEach
    void setUp() {
        // 每个用例从「可信网段 = 10/8」这一明确前提开始
        SecurityUtils.configureTrustedProxies(List.of("10.0.0.0/8"));
    }

    @AfterEach
    void tearDown() {
        // 复原为「未配置」：不采信任何转发头，避免影响其它测试
        SecurityUtils.configureTrustedProxies(List.of());
    }

    private HttpServletRequest request(String remoteAddr, String forwardedFor, String realIp, String scheme) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        lenient().when(request.getRemoteAddr()).thenReturn(remoteAddr);
        lenient().when(request.getHeader("X-Forwarded-For")).thenReturn(forwardedFor);
        lenient().when(request.getHeader("X-Real-IP")).thenReturn(realIp);
        lenient().when(request.getScheme()).thenReturn(scheme);
        return request;
    }

    @Test
    @DisplayName("对端不可信：忽略伪造的 XFF，以 TCP 源地址为准")
    void untrustedPeerIgnoresForwardedHeaders() {
        HttpServletRequest req = request("203.0.113.9", "1.2.3.4", "5.6.7.8", "http");
        // 对端 203.0.113.9 不在 10/8 内 → 整条 XFF 都不可信
        assertEquals("203.0.113.9", SecurityUtils.getClientIp(req));
    }

    @Test
    @DisplayName("对端可信 + 单跳代理：取 XFF 中的真实客户端")
    void trustedPeerSingleHop() {
        HttpServletRequest req = request("10.0.0.5", "198.51.100.7", null, "http");
        assertEquals("198.51.100.7", SecurityUtils.getClientIp(req));
    }

    @Test
    @DisplayName("多跳：从右向左跳过可信代理，取第一个不可信地址（最右可信 IP 之后第一个）")
    void walksRightToLeftPastTrustedProxies() {
        // 右端 10.0.0.7 是内层代理（可信）→ 继续左移 → 198.51.100.7 为真实客户端
        HttpServletRequest req = request("10.0.0.5", "198.51.100.7, 10.0.0.7", null, "http");
        assertEquals("198.51.100.7", SecurityUtils.getClientIp(req));
    }

    @Test
    @DisplayName("整条链均为可信网段：取最左有效地址，避免全体内网用户被合并成同一 IP")
    void allTrustedFallsBackToLeftmost() {
        HttpServletRequest req = request("10.0.0.5", "10.1.1.1, 10.2.2.2", null, "http");
        assertEquals("10.1.1.1", SecurityUtils.getClientIp(req));
    }

    @Test
    @DisplayName("XFF 中夹带非法 IP：丢弃该段，继续回溯")
    void skipsInvalidForwardedSegment() {
        HttpServletRequest req = request("10.0.0.5", "10.9.9.9, 198.51.100.7", null, "http");
        assertEquals("198.51.100.7", SecurityUtils.getClientIp(req));
    }

    @Test
    @DisplayName("无 XFF 时回落到 X-Real-IP")
    void fallsBackToRealIp() {
        HttpServletRequest req = request("10.0.0.5", null, "198.51.100.7", "http");
        assertEquals("198.51.100.7", SecurityUtils.getClientIp(req));
    }

    @Test
    @DisplayName("未配置可信网段：一律以 TCP 源地址为准")
    void noTrustedConfigUsesRemoteAddr() {
        SecurityUtils.configureTrustedProxies(List.of());
        HttpServletRequest req = request("203.0.113.9", "1.2.3.4", null, "http");
        assertEquals("203.0.113.9", SecurityUtils.getClientIp(req));
        assertEquals(0, SecurityUtils.trustedProxyCount());
    }

    @Test
    @DisplayName("isTrustedProxyAddress 按网段判定")
    void trustedProxyAddressCheck() {
        assertTrue(SecurityUtils.isTrustedProxyAddress("10.1.2.3"));
        assertFalse(SecurityUtils.isTrustedProxyAddress("203.0.113.9"));
        assertEquals(1, SecurityUtils.trustedProxyCount());
    }

    @Test
    @DisplayName("原始协议：仅可信对端采信 X-Forwarded-Proto")
    void originalSchemeOnlyTrustedPeer() {
        HttpServletRequest trusted = request("10.0.0.5", null, null, "http");
        when(trusted.getHeader("X-Forwarded-Proto")).thenReturn("https");
        assertEquals("https", SecurityUtils.getOriginalScheme(trusted));
        assertTrue(SecurityUtils.isOriginalRequestSecure(trusted));

        // 直连 http 的请求自称 https 必须被忽略，否则会下发一个永远发不回来的 Secure Cookie
        HttpServletRequest untrusted = request("203.0.113.9", null, null, "http");
        when(untrusted.getHeader("X-Forwarded-Proto")).thenReturn("https");
        assertEquals("http", SecurityUtils.getOriginalScheme(untrusted));
        assertFalse(SecurityUtils.isOriginalRequestSecure(untrusted));
    }

    @Test
    @DisplayName("原始协议：多级代理取最左（最初一跳）的值")
    void originalSchemeTakesLeftmost() {
        HttpServletRequest req = request("10.0.0.5", null, null, "http");
        when(req.getHeader("X-Forwarded-Proto")).thenReturn("https, http");
        assertEquals("https", SecurityUtils.getOriginalScheme(req));
    }
}
