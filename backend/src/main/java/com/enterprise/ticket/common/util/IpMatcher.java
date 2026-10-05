package com.enterprise.ticket.common.util;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * IP / CIDR 网段匹配器（可信反向代理白名单、限流白名单共用）
 *
 * <h2>为什么需要它</h2>
 * <p>生产环境后端永远不在公网直连，而是「外层反向代理（群晖 DSM 反代 / Linux Nginx / Caddy）
 * → 容器内边缘 Nginx → 应用」。此时 {@code X-Forwarded-For} 是判断真实客户端 IP 的<b>唯一</b>来源，
 * 但该请求头的<b>左侧部分完全由客户端可控</b>：攻击者只要伪造一串随机 IP，就能让
 * 「账号 + IP」维度的登录限流每次落在不同的键上，等于把限流彻底绕过。
 *
 * <p>正确做法是「可信代理链」判定：只承认由<b>可信网段</b>的对端追加的转发记录。
 * 本类是这一判定的纯函数实现，不依赖 Spring，可直接单测。
 *
 * <h2>支持的写法</h2>
 * <ul>
 *   <li>精确 IP：{@code 192.168.1.10}、{@code ::1}、{@code 2001:db8::1}</li>
 *   <li>IPv4 CIDR：{@code 10.0.0.0/8}、{@code 172.16.0.0/12}、{@code 127.0.0.1/32}</li>
 *   <li>IPv6 CIDR：{@code fd00::/8}、{@code ::1/128}</li>
 * </ul>
 *
 * <p>IPv4-mapped IPv6（{@code ::ffff:192.168.1.1}，Docker / Lettuce 场景常见）
 * 会被归一化成 IPv4 再比较，否则「IPv4 规则 + mapped 候选」会误判为不匹配。
 */
public final class IpMatcher {

    /** 空匹配器：任何地址都不匹配。作为「未配置可信代理」时的安全默认值 —— 一律不采信转发头。 */
    private static final IpMatcher EMPTY = new IpMatcher(List.of());

    private final List<Rule> rules;

    private IpMatcher(List<Rule> rules) {
        this.rules = rules;
    }

    public static IpMatcher empty() {
        return EMPTY;
    }

    /**
     * 由配置串构造。非法条目被<b>跳过而非抛异常</b>：
     * 一条写错的网段不应该让应用起不来，但必须能被发现（调用方负责记录告警日志）。
     *
     * @param patterns IP 或 CIDR 列表，允许为 null / 含空白
     */
    public static IpMatcher of(Collection<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return EMPTY;
        }
        List<Rule> parsed = new ArrayList<>();
        for (String pattern : patterns) {
            Rule rule = Rule.parse(pattern);
            if (rule != null) {
                parsed.add(rule);
            }
        }
        return parsed.isEmpty() ? EMPTY : new IpMatcher(List.copyOf(parsed));
    }

    /** 配置中是否有任何有效条目 */
    public boolean isEmpty() {
        return rules.isEmpty();
    }

    public int size() {
        return rules.size();
    }

    /**
     * @param ip 候选地址（IPv4 或 IPv6 文本形式），null / 空 / 非法一律返回 false
     */
    public boolean matches(String ip) {
        if (ip == null || ip.isEmpty() || rules.isEmpty()) {
            return false;
        }
        Parsed parsed = Parsed.of(ip);
        if (parsed == null) {
            return false;
        }
        for (Rule rule : rules) {
            if (rule.matches(parsed)) {
                return true;
            }
        }
        return false;
    }

    /** IP 文本是否语法合法（用于过滤伪造的、非 IP 形态的转发记录） */
    public static boolean isValidIp(String ip) {
        return ip != null && !ip.isEmpty() && Parsed.of(ip) != null;
    }

    // ------------------------------------------------------------------
    // 内部：规则与解析
    // ------------------------------------------------------------------

    /** 单条规则：精确地址或 CIDR 网段 */
    private record Rule(int version, BigInteger base, int prefix, boolean isCidr) {

        /** IPv4 网络号掩码常量：32 位全 1 */
        private static final long V4_FULL_MASK = 0xFFFFFFFFL;

        static Rule parse(String raw) {
            if (raw == null) {
                return null;
            }
            String text = raw.trim();
            if (text.isEmpty()) {
                return null;
            }
            int slash = text.indexOf('/');
            String addrPart = slash < 0 ? text : text.substring(0, slash);
            Parsed addr = Parsed.of(addrPart);
            if (addr == null) {
                // 容忍误写成 "10.0.0.0/8 " 或多余分隔符；不符合语法的条目直接丢弃
                return null;
            }
            int bits = addr.version == 4 ? 32 : 128;
            if (slash < 0) {
                return new Rule(addr.version, addr.value, bits, false);
            }
            int prefix;
            try {
                prefix = Integer.parseInt(text.substring(slash + 1).trim());
            } catch (NumberFormatException e) {
                return null;
            }
            if (prefix < 0 || prefix > bits) {
                return null;
            }
            return new Rule(addr.version, addr.value, prefix, true);
        }

        boolean matches(Parsed candidate) {
            if (candidate.version != version) {
                return false;
            }
            if (!isCidr) {
                return candidate.value.equals(base);
            }
            if (version == 4) {
                long mask = prefix == 0 ? 0L : (V4_FULL_MASK << (32 - prefix)) & V4_FULL_MASK;
                long a = candidate.value.longValue() & mask;
                long b = base.longValue() & mask;
                return a == b;
            }
            int shift = 128 - prefix;
            return candidate.value.shiftRight(shift).equals(base.shiftRight(shift));
        }
    }

    /** 归一化后的地址：version = 4 或 6，value 为无符号整数 */
    private record Parsed(int version, BigInteger value) {

        static Parsed of(String raw) {
            if (raw == null) {
                return null;
            }
            String text = raw.trim();
            if (text.isEmpty()) {
                return null;
            }
            // 去掉 [] 包裹（HTTP 头里 IPv6 常写成 [::1]）
            if (text.startsWith("[") && text.endsWith("]")) {
                text = text.substring(1, text.length() - 1);
            }
            if (text.indexOf(':') < 0) {
                long v4 = parseIpv4(text);
                return v4 < 0 ? null : new Parsed(4, BigInteger.valueOf(v4));
            }
            BigInteger v6 = parseIpv6(text);
            if (v6 == null) {
                return null;
            }
            // IPv4-mapped（::ffff:a.b.c.d）：归一化为 IPv4，否则与 IPv4 规则永不匹配
            BigInteger mappedBase = BigInteger.valueOf(0xFFFFL);
            if (v6.shiftRight(32).equals(mappedBase)) {
                return new Parsed(4, v6.and(BigInteger.valueOf(0xFFFFFFFFL)));
            }
            return new Parsed(6, v6);
        }

        /** 严格十进制点分四段；任何越界 / 前导零 / 非数字都判非法 */
        private static long parseIpv4(String text) {
            String[] parts = text.split("\\.", -1);
            if (parts.length != 4) {
                return -1;
            }
            long value = 0;
            for (String part : parts) {
                if (part.isEmpty() || part.length() > 3) {
                    return -1;
                }
                if (part.length() > 1 && part.charAt(0) == '0') {
                    return -1;
                }
                int octet = 0;
                for (int i = 0; i < part.length(); i++) {
                    char c = part.charAt(i);
                    if (c < '0' || c > '9') {
                        return -1;
                    }
                    octet = octet * 10 + (c - '0');
                }
                if (octet > 255) {
                    return -1;
                }
                value = (value << 8) | octet;
            }
            return value;
        }

        /** 支持 :: 压缩与「尾部内嵌 IPv4」两种写法 */
        private static BigInteger parseIpv6(String raw) {
            String text = raw;
            int zoneIndex = text.indexOf('%');
            if (zoneIndex >= 0) {
                text = text.substring(0, zoneIndex);
            }
            if (text.isEmpty()) {
                return null;
            }
            // 尾部内嵌 IPv4（::ffff:192.168.1.1）先展开成两个十六进制组
            int lastColon = text.lastIndexOf(':');
            if (lastColon >= 0 && text.indexOf('.', lastColon) > 0) {
                String dotted = text.substring(lastColon + 1);
                long v4 = parseIpv4(dotted);
                if (v4 < 0) {
                    return null;
                }
                text = text.substring(0, lastColon + 1)
                        + Long.toHexString((v4 >> 16) & 0xFFFFL) + ":"
                        + Long.toHexString(v4 & 0xFFFFL);
            }

            String head = text;
            String tail = "";
            int compression = text.indexOf("::");
            if (compression >= 0) {
                if (text.indexOf("::", compression + 1) >= 0) {
                    return null;
                }
                head = text.substring(0, compression);
                tail = text.substring(compression + 2);
            }

            List<Integer> headGroups = splitGroups(head);
            List<Integer> tailGroups = splitGroups(tail);
            if (headGroups == null || tailGroups == null) {
                return null;
            }
            int present = headGroups.size() + tailGroups.size();
            if (compression < 0) {
                if (present != 8) {
                    return null;
                }
            } else if (present > 7) {
                // :: 至少要压缩掉一组
                return null;
            }

            BigInteger value = BigInteger.ZERO;
            for (Integer group : headGroups) {
                value = value.shiftLeft(16).or(BigInteger.valueOf(group));
            }
            for (int i = present; i < 8; i++) {
                value = value.shiftLeft(16);
            }
            for (Integer group : tailGroups) {
                value = value.shiftLeft(16).or(BigInteger.valueOf(group));
            }
            return value;
        }

        /** 空串返回空列表；含空组（如 "a::" 拆出的前后段）视为合法由调用方处理 */
        private static List<Integer> splitGroups(String segment) {
            if (segment.isEmpty()) {
                return List.of();
            }
            String[] parts = segment.split(":", -1);
            List<Integer> groups = new ArrayList<>(parts.length);
            for (String part : parts) {
                if (part.isEmpty() || part.length() > 4) {
                    return null;
                }
                int value = 0;
                for (int i = 0; i < part.length(); i++) {
                    int digit = Character.digit(part.charAt(i), 16);
                    if (digit < 0) {
                        return null;
                    }
                    value = (value << 4) | digit;
                }
                groups.add(value);
            }
            return groups;
        }
    }
}
