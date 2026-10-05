package com.enterprise.ticket.module.ad.ldap;

import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 一次 AD 访问所需的全部连接参数
 *
 * <p>由 {@code AdConfig} + 解密后的绑定密码组装而成，是「配置」与「协议操作」之间的边界对象：
 * {@link AdDirectoryClient} 只认本记录，不认识 {@code AdConfig} 实体 ——
 * 于是「用假配置跑同步逻辑」的单测不需要碰数据库。
 *
 * @param hosts            服务器地址列表（已按逗号拆开、去空白、去重；保持配置顺序即主备顺序）
 * @param port             端口（LDAP 389 / LDAPS 636）
 * @param ssl              是否 LDAPS
 * @param strictCert       是否严格校验证书（false = 跳过，有中间人风险）
 * @param baseDn           搜索基础 DN
 * @param bindDn           查询用服务账号 DN；为空表示匿名查询（部分目录允许）
 * @param bindPassword     绑定密码明文（仅存在于内存与本记录的生命周期内，绝不落库 / 落日志）
 * @param timeoutSeconds   连接与读取超时（秒）
 * @param filterTemplate   用户过滤器模板，含 {@code {0}} 占位符
 * @param mapping          属性映射
 */
public record AdConnection(List<String> hosts,
                           int port,
                           boolean ssl,
                           boolean strictCert,
                           String baseDn,
                           String bindDn,
                           String bindPassword,
                           int timeoutSeconds,
                           String filterTemplate,
                           AdAttributeMapping mapping) {

    /** 默认过滤器（与 ad_config 的列默认值保持一致） */
    public static final String DEFAULT_FILTER = "(&(objectClass=user)(sAMAccountName={0}))";

    /** 过滤器中的账号占位符 */
    public static final String ACCOUNT_PLACEHOLDER = "{0}";

    public int timeoutMillis() {
        return Math.max(timeoutSeconds, 1) * 1000;
    }

    /** 主服务器（用于错误提示） */
    public String primaryHost() {
        return hosts.isEmpty() ? "(未配置)" : hosts.get(0);
    }

    /**
     * 按登录名生成搜索过滤器
     *
     * <p><b>必须转义</b>：登录名来自用户输入。若不转义，形如
     * {@code *)(objectClass=*)} 的输入会把过滤器改写成
     * {@code (sAMAccountName=*)(objectClass=*))} —— 这是一次典型的
     * LDAP 注入，轻则越权查到不该看到的条目，重则让服务账号去绑定任意对象。
     */
    public String accountFilter(String account) {
        return applyFilter(escapeFilterValue(account));
    }

    /**
     * 全量同步用的过滤器：把占位符替换为 {@code *}。
     *
     * <p>用同一份模板而不是另配一个「同步过滤器」，是为了保证
     * 「登录时能匹配到的账号」与「同步时拉回来的账号」是<b>同一批人</b>。
     * 两份过滤器一旦不一致，就会出现「同步建了账号却登不进来」
     * 或「能登录却不在列表里」的诡异现象。
     */
    public String allFilter() {
        return applyFilter("*");
    }

    private String applyFilter(String value) {
        String template = StringUtils.hasText(filterTemplate) ? filterTemplate : DEFAULT_FILTER;
        return template.replace(ACCOUNT_PLACEHOLDER, value);
    }

    /**
     * RFC 4515 转义（{@code \ * ( ) NUL} 五个字符必须转义）
     *
     * <p>纯函数，便于单测 —— 转义写错属于「平时看不出、被攻击时才发现」的那类缺陷，
     * 只能靠用例钉死。
     */
    public static String escapeFilterValue(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length() + 8);
        for (char c : raw.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\5c");
                case '*' -> sb.append("\\2a");
                case '(' -> sb.append("\\28");
                case ')' -> sb.append("\\29");
                case '\0' -> sb.append("\\00");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 把「多服务器」配置串解析成地址列表。
     *
     * <p>容错点：管理员可能填 {@code dc1.company.com}、也可能填
     * {@code ldap://dc1.company.com} 甚至带端口 {@code ldap://dc1:389}。
     * 这里统一去掉协议前缀与端口后缀，只留主机名（端口由 {@code serverPort} 单独控制）——
     * 否则会出现「填了 636 又被 URL 里的 389 覆盖」这种配置打架。
     */
    public static List<String> parseHosts(String rawServerUrls) {
        if (!StringUtils.hasText(rawServerUrls)) {
            return List.of();
        }
        Set<String> hosts = new LinkedHashSet<>();
        for (String part : rawServerUrls.split("[,;\\s]+")) {
            String host = part.trim();
            if (host.isEmpty()) {
                continue;
            }
            int schemeIdx = host.indexOf("://");
            if (schemeIdx > 0) {
                host = host.substring(schemeIdx + 3);
            }
            // 去掉可能出现的路径部分（ldap://host/DC=company,DC=com）
            int slash = host.indexOf('/');
            if (slash > 0) {
                host = host.substring(0, slash);
            }
            // IPv6 字面量形如 [::1]:389，需保留方括号内的冒号
            if (host.startsWith("[")) {
                int close = host.indexOf(']');
                if (close > 0) {
                    host = host.substring(0, close + 1);
                }
            } else {
                int colon = host.indexOf(':');
                if (colon > 0) {
                    host = host.substring(0, colon);
                }
            }
            host = host.trim();
            if (!host.isEmpty()) {
                hosts.add(host);
            }
        }
        return List.copyOf(hosts);
    }
}
