package com.enterprise.ticket.common.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * traceId 过滤器：生成 / 透传链路 ID，写入 MDC 与响应头。
 *
 * <p>优先级最高，保证后续所有日志、审计记录、异常响应都能拿到同一个 traceId。
 *
 * <p>注意：必须使用 {@link Ordered#HIGHEST_PRECEDENCE}（Integer.MIN_VALUE）。
 * Spring Security 过滤链的 order 为 -100，若此过滤器 order 高于它（例如 {@code @Order(0)}），
 * 401 / 403 / CSRF 拒绝等由 Security 直接写出的响应将拿不到 traceId，恰好是最需要排查的场景。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    private static final int MAX_TRACE_ID_LENGTH = 64;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = request.getHeader(TraceContext.HEADER);
        if (!StringUtils.hasText(traceId) || traceId.length() > MAX_TRACE_ID_LENGTH) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        TraceContext.setTraceId(traceId);
        response.setHeader(TraceContext.HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            TraceContext.clear();
        }
    }
}
