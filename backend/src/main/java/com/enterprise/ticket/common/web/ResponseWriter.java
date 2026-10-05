package com.enterprise.ticket.common.web;

import com.enterprise.ticket.common.api.ApiResponse;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.trace.TraceContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;

import java.io.IOException;

/**
 * 过滤器 / 安全链中直接输出统一响应体的工具类。
 *
 * <p>Servlet Filter 抛出的异常不会经过 {@code @RestControllerAdvice}，
 * 因此安全链内的拒绝响应必须在此手写，保证响应格式与业务接口完全一致。
 */
public final class ResponseWriter {

    private static final Logger log = LoggerFactory.getLogger(ResponseWriter.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ResponseWriter() {
    }

    public static void write(HttpServletResponse response, ErrorCode errorCode) {
        write(response, errorCode.getHttpStatus(), ApiResponse.error(errorCode));
    }

    public static void write(HttpServletResponse response, ErrorCode errorCode, String message) {
        write(response, errorCode.getHttpStatus(), ApiResponse.error(errorCode, message));
    }

    public static void write(HttpServletResponse response, int httpStatus, ApiResponse<?> body) {
        if (response.isCommitted()) {
            log.warn("响应已提交，跳过写入统一响应体: status={}", httpStatus);
            return;
        }
        response.setStatus(httpStatus);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        if (!response.containsHeader(TraceContext.HEADER)) {
            response.setHeader(TraceContext.HEADER, TraceContext.getTraceId());
        }
        try {
            response.getWriter().write(MAPPER.writeValueAsString(body));
            response.getWriter().flush();
        } catch (IOException e) {
            log.error("写入统一响应体失败", e);
        }
    }
}
