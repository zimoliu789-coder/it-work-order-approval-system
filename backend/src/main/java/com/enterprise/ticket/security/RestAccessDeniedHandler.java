package com.enterprise.ticket.security;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.web.ResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 越权处理器：返回统一的 403 FORBIDDEN 响应体（：越权 API 必须返回 403）
 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        ResponseWriter.write(response, ErrorCode.FORBIDDEN);
    }
}
