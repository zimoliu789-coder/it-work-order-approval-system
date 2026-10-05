package com.enterprise.ticket.security;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.web.ResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 未认证入口：返回统一的 401 UNAUTHORIZED 响应体
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        ResponseWriter.write(response, ErrorCode.UNAUTHORIZED);
    }
}
