package com.enterprise.ticket.common.trace;

import org.slf4j.MDC;

/**
 * 链路追踪上下文（规范：日志需带 trace_id，见  operation_logs.trace_id）
 */
public final class TraceContext {

    public static final String TRACE_ID = "traceId";
    public static final String HEADER = "X-Trace-Id";

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private TraceContext() {
    }

    public static void setTraceId(String traceId) {
        HOLDER.set(traceId);
        MDC.put(TRACE_ID, traceId);
    }

    public static String getTraceId() {
        String traceId = HOLDER.get();
        return traceId == null ? "" : traceId;
    }

    public static void clear() {
        HOLDER.remove();
        MDC.remove(TRACE_ID);
    }
}
