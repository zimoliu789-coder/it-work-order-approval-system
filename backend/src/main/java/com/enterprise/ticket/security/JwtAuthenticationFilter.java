package com.enterprise.ticket.security;

import com.enterprise.ticket.config.AppProperties;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.web.ResponseWriter;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import com.enterprise.ticket.module.system.support.ContactRecovery;
import com.enterprise.ticket.module.user.service.UserService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 认证过滤器（； 追加令牌版本号与黑名单校验； 追加绑定联系方式闸门）
 *
 * <p>职责：
 * <ol>
 *   <li>从 HttpOnly Cookie 中读取 token 并解析；</li>
 *   <li>每次请求从数据库重新加载用户，保证「账号禁用 / 离职后立即失效」；</li>
 *   <li><b>令牌版本号比对</b>：Token 中的 {@code ver} 与库中 {@code users.token_version}
 *       不一致即视为已作废（改密 / 管理员重置 / 强制下线的结果）；</li>
 *   <li><b>黑名单比对</b>：Token 的 {@code jti} 在 Redis 中即视为已登出；</li>
 *   <li>强制改密拦截：{@code force_change_password=true} 时除白名单接口外一律返回
 *       {@code FORCE_CHANGE_PASSWORD}；</li>
 *   <li><b>强制绑定联系方式拦截</b>（ ）：手机与邮箱皆空、且至少一个验证渠道
 *       开着时，除白名单接口外一律返回 {@code CONTACT_BIND_REQUIRED}。</li>
 * </ol>
 *
 * <p><b>校验顺序的考量</b>：版本号（查库，本地）排在黑名单（查 Redis，远程）之前 ——
 * 大部分「已作废 Token」是被改密作废的，先做便宜的本地判定可以省掉一次网络往返。
 * 两者都是 O(1)，顺序只影响性能，不影响正确性。
 *
 * <h2>为什么「先强制改密、后强制绑定」</h2>
 * <p>首次登录时两个标记可能同时成立，而此时两者互为前置：要绑定联系方式，
 * 用户得先能进系统；改密则必须在绑定之前完成（临时密码本身就不该继续使用）。
 * 若顺序颠倒，用户会在「请改密码」与「请绑定联系方式」之间来回弹，页面无法收敛。
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider tokenProvider;
    private final JwtCookieService cookieService;
    private final UserService userService;
    private final AppProperties appProperties;
    private final TokenBlacklistService tokenBlacklistService;
    private final SystemConfigService systemConfigService;

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String token = cookieService.readToken(request);
        if (StringUtils.hasText(token)) {
            Claims claims = tokenProvider.parse(token);
            Long userId = claims == null ? null : tokenProvider.getUserId(claims);
            if (userId != null) {
                LoginUser loginUser = userService.loadLoginUser(userId);
                if (loginUser == null) {
                    // 用户被删除 / 禁用 / 离职：立即失效并清 Cookie
                    cookieService.clearToken(response);
                } else if (isTokenRevoked(claims, loginUser)) {
                    // 令牌已被服务端主动作废（改密 / 重置 / 登出）：清 Cookie 并给出明确 401，
                    // 而不是让它退化成一个没有身份的匿名请求 —— 后者只会让用户看到
                    // 「无权限」这类无法理解的提示，并可能在页面上留下半加载的状态。
                    cookieService.clearToken(response);
                    ResponseWriter.write(response, ErrorCode.TOKEN_REVOKED);
                    return;
                } else {
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities());
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);

                    if (loginUser.isForceChangePassword() && !isForceChangePasswordAllowed(request)) {
                        ResponseWriter.write(response, ErrorCode.FORCE_CHANGE_PASSWORD);
                        return;
                    }
                    if (needsContactBinding(loginUser) && !isContactBindAllowed(request)) {
                        ResponseWriter.write(response, ErrorCode.CONTACT_BIND_REQUIRED);
                        return;
                    }
                }
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * 令牌是否已被作废：版本号不匹配，或 jti 已进黑名单
     */
    private boolean isTokenRevoked(Claims claims, LoginUser loginUser) {
        if (tokenProvider.getTokenVersion(claims) != loginUser.getTokenVersion()) {
            return true;
        }
        return tokenBlacklistService.isRevoked(tokenProvider.getTokenId(claims));
    }

    /**
     * 是否处于「必须先绑定联系方式」的状态（ ）。
     *
     * <p>判据与登录响应下发的 {@code requireContactBinding} <b>同源</b>：库中手机与邮箱
     * 都为空，且至少一个验证渠道开着。渠道全关时返回 {@code false} —— 没有任何渠道
     * 能验证，强制绑定只会把用户永久堵在门外（ ）。
     *
     * <p>服务启动时若 SystemConfigService 尚未就绪，配置读取会回落内置默认值
     * （两个开关默认开），不会抛异常中断请求。
     */
    private boolean needsContactBinding(LoginUser loginUser) {
        if (loginUser.hasContact()) {
            return false;
        }
        boolean smsEnabled = systemConfigService.smsVerifyEnabled();
        boolean emailEnabled = systemConfigService.emailVerifyEnabled();
        return !ContactRecovery.allChannelsDisabled(smsEnabled, emailEnabled);
    }

    private boolean isForceChangePasswordAllowed(HttpServletRequest request) {
        // CORS 预检请求放行，否则前端拿不到真实错误码
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        return matchesAny(appProperties.getSecurity().getForceChangePasswordWhitelist(), requestPath(request));
    }

    private boolean isContactBindAllowed(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = requestPath(request);
        // 非 API 路径一律放行：浏览器对同源静态资源（JS / CSS / 图片）同样会带上 HttpOnly
        // Cookie，若把它们也拦成 403，用户看到的是「绑定页自己都加载不出来」——
        // 连「去绑定」这一步都做不到，闸门就从「强制绑定」退化成「锁死系统」。
        // 强制改密没有踩到这个坑，是因为它的白名单里含 /error 且页面在登录后就绪；
        // 绑定页是登录后立刻跳转的第一个页面，静态资源必然在闸门生效期间被请求。
        if (!path.startsWith("/api/")) {
            return true;
        }
        return matchesAny(appProperties.getSecurity().getContactBindWhitelist(), path);
    }

    /** 去掉 contextPath 的请求路径（部署到子路径时白名单仍能匹配） */
    private String requestPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (StringUtils.hasText(contextPath) && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return path;
    }

    private boolean matchesAny(List<String> patterns, String path) {
        if (patterns == null || patterns.isEmpty()) {
            return false;
        }
        for (String pattern : patterns) {
            if (PATH_MATCHER.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }
}
