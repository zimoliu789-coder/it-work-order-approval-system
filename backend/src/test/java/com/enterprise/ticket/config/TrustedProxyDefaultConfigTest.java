package com.enterprise.ticket.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可信代理**默认配置**的 fail-closed 断言（P0 安全修复回归）。
 *
 * <h2>为什么单独钉住「默认值」</h2>
 * <p>{@link com.enterprise.ticket.common.util.SecurityUtilsTrustedProxyTest} 测的是
 * 「给定一组可信网段后，解析链路是否正确」；它每个用例都自行
 * {@code configureTrustedProxies(...)}，因此<b>永远不会发现默认值被改宽</b>。
 *
 * <p>而本次修复的正是一个只在默认值上体现的缺陷：默认把 {@code 10.0.0.0/8} /
 * {@code 172.16.0.0/12} / {@code 192.168.0.0/16} 当作可信代理，导致<b>任何内网客户端</b>
 * 都能自选 {@code X-Forwarded-For} 伪造身份、绕过 IP 限流。修复口径是
 * 「默认不信任任何转发头，确有反向代理时必须显式配置」——
 * 这条口径只能靠断言「默认集合为空」来守。
 */
class TrustedProxyDefaultConfigTest {

    @Test
    @DisplayName("默认可信代理为空：不允许任何内网客户端自选 X-Forwarded-For")
    void defaultTrustedProxiesAreEmpty() {
        AppProperties properties = new AppProperties();
        assertTrue(properties.getSecurity().getTrustedProxies().isEmpty(),
                "app.security.trusted-proxies 默认必须为空（fail-closed）；"
                        + "若默认信任内网网段，任何内网客户端都能伪造 XFF 绕过 IP 限流");
    }
}
