package com.enterprise.ticket.common.api;

import com.enterprise.ticket.common.trace.TraceContext;

import java.io.Serializable;

/**
 * 统一响应体
 *
 * <pre>
 * {
 *   "code": "SUCCESS",
 *   "message": "操作成功",
 *   "data": {},
 *   "traceId": "xxx"
 * }
 * </pre>
 */
public class ApiResponse<T> implements Serializable {

    private String code;
    private String message;
    private T data;
    private String traceId;

    public ApiResponse() {
    }

    public ApiResponse(String code, String message, T data, String traceId) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.traceId = traceId;
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getDefaultMessage(),
                data, TraceContext.getTraceId());
    }

    public static ApiResponse<Void> success() {
        return success(null);
    }

    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(ErrorCode.SUCCESS.getCode(), message, data, TraceContext.getTraceId());
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode) {
        return new ApiResponse<>(errorCode.getCode(), errorCode.getDefaultMessage(), null, TraceContext.getTraceId());
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode, String message) {
        return new ApiResponse<>(errorCode.getCode(), message, null, TraceContext.getTraceId());
    }

    /**
     * 带附加数据的错误响应。
     *
     * <p>用途：限流拒绝时把 {@code retryAfterSeconds} 一并下发，让前端无需解析
     * 响应头也能给出「请 X 秒后重试」的准确提示（响应头是补充，不是唯一来源 ——
     * 中间代理可能吃掉自定义头，而响应体一定会到达业务代码）。
     */
    public static <T> ApiResponse<T> error(ErrorCode errorCode, String message, T data) {
        return new ApiResponse<>(errorCode.getCode(), message, data, TraceContext.getTraceId());
    }

    public static <T> ApiResponse<T> error(String code, String message) {
        return new ApiResponse<>(code, message, null, TraceContext.getTraceId());
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }
}
