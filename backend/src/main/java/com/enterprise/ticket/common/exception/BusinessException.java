package com.enterprise.ticket.common.exception;

import com.enterprise.ticket.common.api.ErrorCode;

/**
 * 业务异常：携带 定义的错误码，由 GlobalExceptionHandler 统一转换为响应体。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    /**
     * 建议重试等待秒数（仅限流类异常使用）。
     *
     * <p>存在的意义：限流的拒绝响应如果只说「过于频繁」，用户与前端都无从判断该等多久，
     * 只能盲目重试 —— 而盲目重试又会继续撞在限流上。带上明确秒数后，
     * 响应可以写 {@code Retry-After} 头、前端可以提示「请 X 秒后重试」。
     */
    private Integer retryAfterSeconds;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public Integer getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public static BusinessException of(ErrorCode errorCode) {
        return new BusinessException(errorCode);
    }

    /**
     * 限流异常：携带建议等待秒数，供 GlobalExceptionHandler 写入 {@code Retry-After} 响应头。
     *
     * @param retryAfterSeconds 秒数不足 1 时按 1 处理，避免下发 {@code Retry-After: 0} 导致前端立即重试
     */
    public static BusinessException rateLimited(String message, long retryAfterSeconds) {
        BusinessException exception = new BusinessException(ErrorCode.RATE_LIMITED, message);
        exception.retryAfterSeconds = (int) Math.max(1L, retryAfterSeconds);
        return exception;
    }
}
