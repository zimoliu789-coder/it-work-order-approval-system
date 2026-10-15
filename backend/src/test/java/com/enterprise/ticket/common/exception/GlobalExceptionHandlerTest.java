package com.enterprise.ticket.common.exception;

import com.enterprise.ticket.common.alert.ExceptionRecorder;
import com.enterprise.ticket.common.api.ApiResponse;
import com.enterprise.ticket.common.api.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 全局异常处理：客户端「请求格式」类错误必须如实返回 4xx。
 *
 * <p>锁住的是**一条容易被忽视的边界**：这类错误一旦落到兜底分支，
 * 就会 `log.error` 打完整堆栈、并交给 {@link ExceptionRecorder} 落库 + 排队告警 ——
 * 于是「调用方 header 写错」被当成**服务端故障**上报，污染异常统计与 5xx 指标。
 * 因此本测试除了断言状态码，还断言**采集器完全没被碰过**。
 */
class GlobalExceptionHandlerTest {

    private final ExceptionRecorder recorder = mock(ExceptionRecorder.class);
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(recorder);

    private HttpServletRequest request(String uri) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn(uri);
        return req;
    }

    @Test
    @DisplayName("Content-Type 不受支持 → 415，且不进异常采集（客户端错误不得当服务端故障上报）")
    void mediaTypeNotSupportedReturns415() {
        // 典型场景：向只收 application/octet-stream 的升级包上传接口发 application/json
        HttpMediaTypeNotSupportedException e = new HttpMediaTypeNotSupportedException(
                MediaType.APPLICATION_JSON, List.of(MediaType.APPLICATION_OCTET_STREAM));

        ResponseEntity<ApiResponse<Void>> resp =
                handler.handleMediaTypeNotSupported(e, request("/api/system/upgrade/package"));

        assertEquals(415, resp.getStatusCode().value(), "HTTP 语义应为 415，而不是兜底 500");
        assertEquals(ErrorCode.UNSUPPORTED_MEDIA_TYPE.getCode(), resp.getBody().getCode());
        assertEquals(ErrorCode.UNSUPPORTED_MEDIA_TYPE.getDefaultMessage(), resp.getBody().getMessage());
        verifyNoInteractions(recorder);
    }

    @Test
    @DisplayName("Accept 无法满足 → 406，同样不进异常采集")
    void mediaTypeNotAcceptableReturns406() {
        HttpMediaTypeNotAcceptableException e =
                new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_XML));

        ResponseEntity<ApiResponse<Void>> resp =
                handler.handleMediaTypeNotAcceptable(e, request("/api/system/upgrade/overview"));

        assertEquals(406, resp.getStatusCode().value(), "HTTP 语义应为 406，而不是兜底 500");
        assertEquals(ErrorCode.NOT_ACCEPTABLE.getCode(), resp.getBody().getCode());
        verifyNoInteractions(recorder);
    }
}
