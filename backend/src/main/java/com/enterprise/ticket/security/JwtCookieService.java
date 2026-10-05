package com.enterprise.ticket.security;

import com.enterprise.ticket.config.AppProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Arrays;

/**
 * JWT Cookie 读写（：优先使用 HttpOnly、Secure、SameSite 安全 Cookie）
 *
 * <p>安全设计：
 * <ul>
 *   <li>HttpOnly —— JavaScript 无法读取 token，XSS 拿不到凭证；</li>
 *   <li>Secure   —— 生产 HTTPS 下仅加密传输（由 COOKIE_SECURE 控制）；</li>
 *   <li>SameSite=Lax —— CSRF 第一层防护；第二层由 {@code CsrfHeaderFilter} 提供。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class JwtCookieService {

    private final AppProperties appProperties;

    /**
     * 写入登录态 Cookie
     */
    public void writeToken(HttpServletResponse response, String token, int expireMinutes) {
        ResponseCookie cookie = baseBuilder(token)
                .maxAge(Duration.ofMinutes(expireMinutes))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    /**
     * 清除登录态 Cookie（退出登录 / 会话失效）
     */
    public void clearToken(HttpServletResponse response) {
        ResponseCookie cookie = baseBuilder("")
                .maxAge(Duration.ZERO)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    /**
     * 从请求中读取 token
     */
    public String readToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        String name = appProperties.getJwt().getCookieName();
        return Arrays.stream(cookies)
                .filter(c -> name.equals(c.getName()))
                .map(Cookie::getValue)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);
    }

    private ResponseCookie.ResponseCookieBuilder baseBuilder(String value) {
        AppProperties.Jwt jwt = appProperties.getJwt();
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(jwt.getCookieName(), value)
                .httpOnly(true)
                .secure(jwt.isCookieSecure())
                .path(StringUtils.hasText(jwt.getCookiePath()) ? jwt.getCookiePath() : "/");
        if (StringUtils.hasText(jwt.getCookieSameSite())) {
            builder.sameSite(jwt.getCookieSameSite());
        }
        return builder;
    }
}
