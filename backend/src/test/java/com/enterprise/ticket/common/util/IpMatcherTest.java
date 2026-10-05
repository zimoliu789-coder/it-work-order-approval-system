package com.enterprise.ticket.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IP / CIDR 匹配器单测（部署与限流加固）
 *
 * <p>{@link IpMatcher} 是「可信代理链」判定的地基：它错判一个地址，
 * 要么把伪造的 {@code X-Forwarded-For} 当成真实客户端（限流被绕过、审计 IP 被污染），
 * 要么把合法代理排除在外（所有用户被合并成同一个 IP，正常登录被误伤）。
 * 因此本类覆盖四类高风险点：
 * <ol>
 *   <li>CIDR 边界（网段首/尾地址必须命中，网段外相邻地址必须不命中）；</li>
 *   <li>IPv4-mapped IPv6 归一化（Docker 场景实际会传 {@code ::ffff:10.0.0.1}）；</li>
 *   <li>非法条目与非法候选的被丢弃行为；</li>
 *   <li>{@code empty()} 的「安全默认值」语义（一律不匹配）。</li>
 * </ol>
 */
class IpMatcherTest {

    @Nested
    @DisplayName("IPv4 精确匹配")
    class Ipv4Exact {

        @Test
        @DisplayName("精确地址命中，邻近地址不命中")
        void exactMatch() {
            IpMatcher matcher = IpMatcher.of(List.of("192.168.1.10"));
            assertTrue(matcher.matches("192.168.1.10"));
            assertFalse(matcher.matches("192.168.1.11"));
            assertFalse(matcher.matches("192.168.1.1"));
        }

        @Test
        @DisplayName("/32 等价于精确匹配")
        void thirtyTwoPrefix() {
            IpMatcher matcher = IpMatcher.of(List.of("127.0.0.1/32"));
            assertTrue(matcher.matches("127.0.0.1"));
            assertFalse(matcher.matches("127.0.0.2"));
        }
    }

    @Nested
    @DisplayName("IPv4 CIDR 网段匹配（含边界）")
    class Ipv4Cidr {

        @Test
        @DisplayName("10/8 命中首尾与中间，不命中相邻网段")
        void classA() {
            IpMatcher matcher = IpMatcher.of(List.of("10.0.0.0/8"));
            assertTrue(matcher.matches("10.0.0.0"));
            assertTrue(matcher.matches("10.255.255.255"));
            assertTrue(matcher.matches("10.1.2.3"));
            assertFalse(matcher.matches("11.0.0.1"));
            assertFalse(matcher.matches("9.255.255.255"));
        }

        @Test
        @DisplayName("172.16/12 覆盖 Docker 网段边界（172.16~172.31）")
        void dockerRange() {
            IpMatcher matcher = IpMatcher.of(List.of("172.16.0.0/12"));
            assertTrue(matcher.matches("172.16.0.1"));
            assertTrue(matcher.matches("172.31.255.255"));
            // 172.32 已越出 /12 边界
            assertFalse(matcher.matches("172.32.0.1"));
            assertFalse(matcher.matches("172.15.255.255"));
        }

        @Test
        @DisplayName("/0 命中任意 IPv4")
        void zeroPrefix() {
            IpMatcher matcher = IpMatcher.of(List.of("0.0.0.0/0"));
            assertTrue(matcher.matches("1.2.3.4"));
            assertTrue(matcher.matches("255.255.255.255"));
        }
    }

    @Nested
    @DisplayName("IPv6 与 IPv4-mapped")
    class Ipv6 {

        @Test
        @DisplayName("IPv6 CIDR fd00::/8 命中，邻近网段不命中")
        void ipv6Cidr() {
            IpMatcher matcher = IpMatcher.of(List.of("fd00::/8"));
            assertTrue(matcher.matches("fd00::1"));
            assertTrue(matcher.matches("fdff:ffff::1"));
            assertFalse(matcher.matches("fe00::1"));
        }

        @Test
        @DisplayName("::1/128 支持带方括号的写法（HTTP 头常见）")
        void bracketForm() {
            IpMatcher matcher = IpMatcher.of(List.of("::1/128"));
            assertTrue(matcher.matches("::1"));
            assertTrue(matcher.matches("[::1]"));
        }

        @Test
        @DisplayName("IPv4-mapped IPv6 归一化后与 IPv4 规则匹配（Docker 场景）")
        void ipv4Mapped() {
            IpMatcher matcher = IpMatcher.of(List.of("10.0.0.0/8"));
            // 若不归一化，::ffff:10.0.0.1 会被当成 IPv6 而与 IPv4 规则永不匹配
            assertTrue(matcher.matches("::ffff:10.0.0.1"));
            assertFalse(matcher.matches("::ffff:11.0.0.1"));
        }

        @Test
        @DisplayName("IPv6 规则不匹配 IPv4 候选（版本隔离）")
        void versionIsolation() {
            IpMatcher matcher = IpMatcher.of(List.of("fd00::/8"));
            assertFalse(matcher.matches("10.0.0.1"));
        }
    }

    @Nested
    @DisplayName("健壮性：非法输入与安全默认值")
    class Robustness {

        @Test
        @DisplayName("非法条目被跳过，合法条目仍然生效")
        void skipInvalidPatterns() {
            IpMatcher matcher = IpMatcher.of(Arrays.asList("garbage", "  ", null, "10.0.0.0/8", "999.1.1.1"));
            assertEquals(1, matcher.size());
            assertTrue(matcher.matches("10.0.0.5"));
        }

        @Test
        @DisplayName("空配置得到 empty()：任何地址都不匹配（未配置可信代理时的安全默认值）")
        void emptyMatchesNothing() {
            assertTrue(IpMatcher.empty().isEmpty());
            assertFalse(IpMatcher.empty().matches("127.0.0.1"));
            assertTrue(IpMatcher.of(null).isEmpty());
            assertTrue(IpMatcher.of(List.of()).isEmpty());
        }

        @Test
        @DisplayName("候选为 null / 空 / 非法一律不匹配，不抛异常")
        void invalidCandidates() {
            IpMatcher matcher = IpMatcher.of(List.of("10.0.0.0/8"));
            assertFalse(matcher.matches(null));
            assertFalse(matcher.matches(""));
            assertFalse(matcher.matches("10.0.0.1; DROP TABLE"));
            assertFalse(matcher.matches("10.0.0.256"));
        }
    }

    @Nested
    @DisplayName("isValidIp：供丢弃伪造转发记录使用")
    class ValidIp {

        @Test
        @DisplayName("合法 IPv4 / IPv6 返回 true")
        void valid() {
            assertTrue(IpMatcher.isValidIp("1.2.3.4"));
            assertTrue(IpMatcher.isValidIp("255.255.255.255"));
            assertTrue(IpMatcher.isValidIp("::1"));
            assertTrue(IpMatcher.isValidIp("2001:db8::1"));
            assertTrue(IpMatcher.isValidIp("::ffff:192.168.1.1"));
        }

        @Test
        @DisplayName("前导零、越界、段数不足、非数字一律 false（严格十进制）")
        void invalid() {
            assertFalse(IpMatcher.isValidIp("01.2.3.4"));
            assertFalse(IpMatcher.isValidIp("256.1.1.1"));
            assertFalse(IpMatcher.isValidIp("1.2.3"));
            assertFalse(IpMatcher.isValidIp("1.2.3.4.5"));
            assertFalse(IpMatcher.isValidIp("abc"));
            assertFalse(IpMatcher.isValidIp(null));
            assertFalse(IpMatcher.isValidIp(""));
        }
    }
}
