package com.enterprise.ticket.security;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.web.ResponseWriter;
import com.enterprise.ticket.config.AppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * CSRF 第二层防护（ 会话机制确认方案）
 *
 * <p>第一层：Cookie 设置 {@code SameSite=Lax}；
 * 第二层：所有状态变更请求（非 GET/HEAD/OPTIONS）必须携带自定义请求头
 * {@code X-Requested-With: XMLHttpRequest}。
 *
 * <p>原理：跨站表单/图片/脚本无法携带自定义请求头；跨域携带自定义头会触发 CORS 预检，
 * 而服务端只放行受信任来源，因此该方案可以在不使用 CSRF Token 的前提下有效阻断 CSRF。
 */
@Component
@RequiredArgsConstructor
public class CsrfHeaderFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final AppProperties appProperties;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (SAFE_METHODS.contains(request.getMethod().toUpperCase())) {
            chain.doFilter(request, response);
            return;
        }

        String expectedHeader = appProperties.getSecurity().getCsrfHeaderName();
        String expectedValue = appProperties.getSecurity().getCsrfHeaderValue();
        String actualValue = request.getHeader(expectedHeader);

        if (!StringUtils.hasText(actualValue) || !expectedValue.equalsIgnoreCase(actualValue.trim())) {
            ResponseWriter.write(response, ErrorCode.CSRF_HEADER_MISSING);
            return;
        }
        chain.doFilter(request, response);
    }
}
