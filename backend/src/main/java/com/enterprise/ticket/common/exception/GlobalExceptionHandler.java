package com.enterprise.ticket.common.exception;

import com.enterprise.ticket.common.alert.ExceptionRecorder;
import com.enterprise.ticket.common.api.ApiResponse;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.trace.TraceContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 全局异常处理：统一转换为 的响应格式，并同步设置真实 HTTP 状态码
 * （401 触发前端重新登录、403 表示越权、429 表示限流）。
 *
 * <p>原则：
 * <ol>
 *   <li>业务异常保留原始错误码与 HTTP 状态；</li>
 *   <li>越权统一返回 403 FORBIDDEN（：后端 API 必须再次校验）；</li>
 *   <li>参数校验错误返回 400 PARAM_INVALID，并拼接字段级提示，便于前端就近展示；</li>
 *   <li>限流（429）额外下发 {@code Retry-After} 响应头与 {@code retryAfterSeconds} 字段，
 *       让客户端知道「等多久」而不是盲目重试；</li>
 *   <li>未知异常统一 500 INTERNAL_ERROR，完整堆栈只进日志不外泄。</li>
 * </ol>
 */
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 未预期异常的采集器。
     *
     * <p><b>只挂在兜底分支上</b>：{@code BusinessException} 有专用处理器、根本不经过它 ⇒
     * 「业务异常（4xx）不告警」这条口径由**结构**保证，而不是靠这里写一个 if 判断
     * （那样日后新增一种 4xx 处理器时很容易漏掉判断，静默地把业务异常也告警了）。
     */
    private final ExceptionRecorder exceptionRecorder;

    /** 限流附加数据的字段名，与前端 request.ts 的解析口径保持一致 */
    private static final String FIELD_RETRY_AFTER = "retryAfterSeconds";

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Object>> handleBusiness(BusinessException e, HttpServletRequest request) {
        log.warn("业务异常 code={} uri={} traceId={} msg={}",
                e.getErrorCode().getCode(), request.getRequestURI(), TraceContext.getTraceId(), e.getMessage());
        HttpStatus status = resolveStatus(e.getErrorCode());
        Integer retryAfterSeconds = e.getRetryAfterSeconds();
        if (retryAfterSeconds != null) {
            return ResponseEntity.status(status)
                    // Retry-After 是 HTTP 标准语义，外层 Nginx / CDN / 浏览器都能识别；
                    // 响应体里的同名字段用于兜底（自定义头可能被中间层丢弃）
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds))
                    .body(ApiResponse.error(e.getErrorCode(), e.getMessage(),
                            Map.of(FIELD_RETRY_AFTER, retryAfterSeconds)));
        }
        return ResponseEntity.status(status).body(ApiResponse.error(e.getErrorCode(), e.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<ApiResponse<Void>> handleValidation(BindException e, HttpServletRequest request) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("；"));
        log.warn("参数校验失败 uri={} traceId={} msg={}", request.getRequestURI(), TraceContext.getTraceId(), message);
        return build(ErrorCode.PARAM_INVALID, message);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("；"));
        return build(ErrorCode.PARAM_INVALID, message);
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class})
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception e) {
        log.warn("请求参数解析失败 traceId={} msg={}", TraceContext.getTraceId(), e.getMessage());
        return build(ErrorCode.PARAM_INVALID, ErrorCode.PARAM_INVALID.getDefaultMessage());
    }

    /**
     * 上传文件超过 multipart 全局上限
     *
     * <p>{@code spring.servlet.multipart.max-file-size / max-request-size} 由容器在**进入业务代码之前**
     * 拦截，抛出的 {@link MaxUploadSizeExceededException} 是 {@link MultipartException} 的子类 ——
     * 若只按父类处理，用户会收到「请求格式不正确」这种与事实不符的提示（真实原因是文件过大），
     * 排查方向会被误导。故单独映射为「上传文件过大」。Spring 会优先匹配最具体的异常类型。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSize(MaxUploadSizeExceededException e,
                                                                HttpServletRequest request) {
        log.warn("上传文件超过服务端大小上限 uri={} traceId={} msg={}",
                request.getRequestURI(), TraceContext.getTraceId(), e.getMessage());
        return build(ErrorCode.UPLOAD_FILE_TOO_LARGE,
                "上传文件超过服务端允许的大小上限，请拆分后重新上传");
    }

    /**
     * 文件上传类请求错误
     *
     * <p>{@link MultipartException} 覆盖「请求体不是 multipart / 上传流读取失败」等情况，
     * 属于**客户端请求格式问题**，归为 400 PARAM_INVALID；若落到兜底 500 会让前端看到
     * 「系统内部错误」，误导排查方向。注意：**「缺少 file 部件」不在此列** ——
     * 那会抛 {@link MissingServletRequestPartException}，由下面的专用处理器接住。
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiResponse<Void>> handleMultipart(MultipartException e, HttpServletRequest request) {
        log.warn("文件上传请求解析失败 uri={} traceId={} msg={}",
                request.getRequestURI(), TraceContext.getTraceId(), e.getMessage());
        return build(ErrorCode.PARAM_INVALID, "文件上传请求格式不正确（需以 multipart/form-data 提交 file 字段）");
    }

    /**
     * 缺少 multipart 文件部件（例如上传导入文件时未带 {@code file} 字段）
     *
     * <p>{@link MissingServletRequestPartException} 继承 {@code ServletException}，
     * 既不是 {@link MultipartException} 也不是 {@link MissingServletRequestParameterException} 的子类 ——
     * 若不显式处理，会落到兜底 500，把「客户端少传了个文件」误报成「系统内部错误」。
     * 这类请求属于客户端格式问题，应返回 400 并给出可操作的提示。
     */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingPart(MissingServletRequestPartException e,
                                                               HttpServletRequest request) {
        log.warn("缺少请求文件部件 uri={} part={} traceId={}",
                request.getRequestURI(), e.getRequestPartName(), TraceContext.getTraceId());
        return build(ErrorCode.PARAM_INVALID, "缺少必要的文件参数：" + e.getRequestPartName());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException e, HttpServletRequest request) {
        log.warn("越权访问被拒绝 uri={} traceId={}", request.getRequestURI(), TraceContext.getTraceId());
        return build(ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.getDefaultMessage());
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthentication(AuthenticationException e) {
        return build(ErrorCode.UNAUTHORIZED, ErrorCode.UNAUTHORIZED.getDefaultMessage());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return build(ErrorCode.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED.getDefaultMessage());
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ApiResponse<Void>> handleNotFound(Exception e, HttpServletRequest request) {
        return build(ErrorCode.NOT_FOUND, "请求的资源不存在：" + request.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnknown(Exception e, HttpServletRequest request) {
        log.error("系统异常 uri={} traceId={}", request.getRequestURI(), TraceContext.getTraceId(), e);
        // 落库 + 排队告警。record 自身绝不抛异常，且刻意**不同步发邮件** ——
        // 同步发送会把请求线程挂在 SMTP 握手上，为了通知一个故障反而拖垮用户的请求。
        exceptionRecorder.record(e, request, "GLOBAL");
        return build(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getDefaultMessage());
    }

    private ResponseEntity<ApiResponse<Void>> build(ErrorCode errorCode, String message) {
        return ResponseEntity.status(resolveStatus(errorCode)).body(ApiResponse.error(errorCode, message));
    }

    /** 错误码 → HTTP 状态；映射缺失时按 500 处理（绝不静默返回 200） */
    private HttpStatus resolveStatus(ErrorCode errorCode) {
        HttpStatus status = HttpStatus.resolve(errorCode.getHttpStatus());
        return status == null ? HttpStatus.INTERNAL_SERVER_ERROR : status;
    }

    private String formatFieldError(FieldError fieldError) {
        return fieldError.getField() + ": " + fieldError.getDefaultMessage();
    }
}
