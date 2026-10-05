package com.enterprise.ticket.common.util;

import com.enterprise.ticket.security.LoginUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collection;

/**
 * 安全上下文与请求信息工具
 *
 * <h2>真实客户端 IP 的可信代理链解析</h2>
 * <p>生产链路是「客户端 → 外层反代（群晖 DSM 反代 / Linux Nginx / Caddy）→ 容器内边缘 Nginx → 应用」，
 * 应用看到的 TCP 对端永远是上一层代理。判断真实客户端 IP 只能依赖 {@code X-Forwarded-For}，
 * 但该头的<b>左侧部分由客户端完全可控</b>。
 *
 * <p>因此采用两段式判定：
 * <ol>
 *   <li><b>先判对端是否可信</b>：{@code remoteAddr} 不在可信网段 → 一律以 TCP 源地址为准，
 *       忽略所有转发头。这堵住了「后端端口被直接暴露时伪造 XFF 绕过限流 / 污染审计 IP」的路径；</li>
 *   <li><b>再沿链右→左回溯</b>：从最右侧（最近一跳）开始跳过可信代理，
 *       第一个<b>不可信</b>地址即真实客户端 —— 这正是需求方要求的
 *       「从 X-Forwarded-For 取最右可信 IP 之后的第一个」。</li>
 * </ol>
 *
 * <p>可信网段由 {@code app.security.trusted-proxies} 配置（支持多个 IP / CIDR），
 * 由 {@code TrustedProxyInitializer} 在启动时注入。未配置时<b>不采信任何转发头</b>（安全默认值）。
 */
public final class SecurityUtils {

    /** 最多解析 X-Forwarded-For 的层数，避免超长请求头拖慢解析 */
    private static final int MAX_FORWARD_DEPTH = 8;

    /** IP 字段最大长度，与 operation_logs.ip VARCHAR(64) 对齐：超长请求头会导致写库失败 */
    private static final int MAX_IP_LENGTH = 64;

    /** 可信反向代理网段；启动时由 Spring 注入，之后只读 */
    private static volatile IpMatcher trustedProxies = IpMatcher.empty();

    private SecurityUtils() {
    }

    /**
     * 注入可信代理网段（应用启动时调用一次）。
     *
     * <p>采用「静态工具 + 启动期注入」而非把本类改成 Spring Bean：调用点遍布过滤器、
     * 审计切面与业务代码（均为静态调用），改成注入会带来大范围签名改动，
     * 而该配置在运行期不会变化，静态持有更简单且无并发风险（volatile 一次写、多次读）。
     */
    public static void configureTrustedProxies(Collection<String> patterns) {
        trustedProxies = IpMatcher.of(patterns);
    }

    /** 当前配置的可信代理条目数（0 表示不采信任何转发头） */
    public static int trustedProxyCount() {
        return trustedProxies.size();
    }

    /** TCP 对端地址是否属于可信代理网段 */
    public static boolean isTrustedProxyAddress(String ip) {
        return trustedProxies.matches(ip);
    }

    /**
     * 获取当前登录用户；未登录返回 null
     */
    public static LoginUser getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        return principal instanceof LoginUser loginUser ? loginUser : null;
    }

    /**
     * 获取当前登录用户 ID；未登录返回 null
     */
    public static Long getCurrentUserId() {
        LoginUser loginUser = getCurrentUser();
        return loginUser == null ? null : loginUser.getId();
    }

    /**
     * 获取当前登录用户姓名；未登录返回 null
     */
    public static String getCurrentUsername() {
        LoginUser loginUser = getCurrentUser();
        return loginUser == null ? null : loginUser.getUsername();
    }

    /**
     * 获取当前登录用户的角色编码；未登录返回 null
     *
     * <p>角色已在 JWT 主体里，因此这里不查库。用途：区分「内置超管」与「其他超管」
     * （见 {@link com.enterprise.ticket.common.permission.BuiltinAdmin}）——
     * 只靠 {@link #isSuperAdmin()} 无法区分这两者。
     */
    public static String getCurrentUserRole() {
        LoginUser loginUser = getCurrentUser();
        return loginUser == null ? null : loginUser.getRole();
    }

    /**
     * 是否超级管理员
     */
    public static boolean isSuperAdmin() {
        LoginUser loginUser = getCurrentUser();
        return loginUser != null && loginUser.isSuperAdmin();
    }

    /**
     * 获取当前请求对象；非 Web 线程返回 null
     */
    public static HttpServletRequest getRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }

    /**
     * 解析客户端真实 IP（兼容 Nginx / 群晖反向代理）
     */
    public static String getClientIp() {
        HttpServletRequest request = getRequest();
        return request == null ? null : getClientIp(request);
    }

    public static String getClientIp(HttpServletRequest request) {
        String direct = truncate(request.getRemoteAddr());

        // 第一段：TCP 对端不可信 → 转发头一律忽略。
        // 少了这道判定，攻击者只要在请求里塞一个 X-Forwarded-For 就能自选身份，
        // 「账号 + IP」限流会被逐个虚构 IP 打散而完全失效，审计日志里的 IP 也不再可信。
        if (!trustedProxies.matches(direct)) {
            return direct;
        }

        // 第二段：可信代理链回溯。自右向左跳过可信地址，取第一个不可信地址。
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            String[] parts = forwarded.split(",");
            int floor = Math.max(0, parts.length - MAX_FORWARD_DEPTH);
            for (int i = parts.length - 1; i >= floor; i--) {
                String candidate = sanitizeIp(parts[i]);
                if (candidate == null) {
                    continue;
                }
                if (!trustedProxies.matches(candidate)) {
                    return candidate;
                }
            }
            // 整条链都在可信网段（内网客户端 + 内网代理）→ 取最左的有效地址。
            // 此时左端是第一跳追加的，即真实客户端；若返回最右会退化成「代理自己的 IP」，
            // 让所有内网用户在限流维度上被合并成同一个人。
            for (int i = floor; i < parts.length; i++) {
                String candidate = sanitizeIp(parts[i]);
                if (candidate != null) {
                    return candidate;
                }
            }
        }

        String realIp = sanitizeIp(request.getHeader("X-Real-IP"));
        if (realIp != null) {
            return realIp;
        }
        return direct;
    }

    /**
     * 解析请求的<b>原始协议</b>（外层反代经 {@code X-Forwarded-Proto} 告知）。
     *
     * <p>用于「Cookie 是否带 Secure」这类必须知道用户实际是否走 HTTPS 的判定。
     * 仅在 TCP 对端可信时才采信该头 —— 否则直连 http 的请求可以自称 https，
     * 让浏览器拿到一个 Secure Cookie 而永远发不回来（表现为「登录成功但立刻未登录」）。
     *
     * @return {@code "https"} / {@code "http"}；无法判定时回落到容器看到的 scheme
     */
    public static String getOriginalScheme(HttpServletRequest request) {
        String direct = truncate(request.getRemoteAddr());
        if (trustedProxies.matches(direct)) {
            String forwardedProto = request.getHeader("X-Forwarded-Proto");
            if (StringUtils.hasText(forwardedProto)) {
                // 多级代理时取最左（最初一跳）的值，形如 "https, http"
                String first = forwardedProto.split(",")[0].trim().toLowerCase();
                if ("https".equals(first) || "http".equals(first)) {
                    return first;
                }
            }
        }
        return request.getScheme();
    }

    /** 请求是否经外层反代以 HTTPS 抵达（TLS 在外层终止） */
    public static boolean isOriginalRequestSecure(HttpServletRequest request) {
        return request != null && "https".equals(getOriginalScheme(request));
    }

    /**
     * 去空白、剔除 unknown、并限制长度；非法返回 null。
     *
     * <p>额外做<b>语法校验</b>：非 IP 形态的文本（例如客户端伪造的 {@code 1.2.3.4;<script>}）
     * 一律丢弃。这类值一旦进入限流键或审计表，既能让限流形同虚设，也会污染日志。
     */
    private static String sanitizeIp(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (!StringUtils.hasText(value) || "unknown".equalsIgnoreCase(value)) {
            return null;
        }
        String truncated = truncate(value);
        return IpMatcher.isValidIp(truncated) ? truncated : null;
    }

    private static String truncate(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        return value.length() > MAX_IP_LENGTH ? value.substring(0, MAX_IP_LENGTH) : value;
    }
}
