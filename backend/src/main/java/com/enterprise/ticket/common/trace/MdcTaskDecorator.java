package com.enterprise.ticket.common.trace;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * MDC 上下文跨线程传递（需求方三波·第三波·「异步审计 traceId 补 MDC」）
 *
 * <h2>问题</h2>
 * <p>{@code traceId} 存在两处：{@link TraceContext} 的 {@code ThreadLocal}（业务代码读取）
 * 与 SLF4J {@code MDC}（日志格式 {@code %X{traceId}} 渲染）。二者都是<b>线程本地</b>的：
 * 请求线程写入后，交给线程池执行的任务在<b>另一个线程</b>上运行，读不到任何值 ——
 * 于是异步审计日志里的 {@code traceId} 是空的，把一个异步操作与它所属的请求链断开了。
 *
 * <h2>方案</h2>
 * <p>在任务<b>提交时</b>（调用线程，此时 MDC 还是完整的）抓一份快照，
 * 在任务<b>执行时</b>（工作线程）装上，执行完再还原工作线程原有的 MDC（而不是清空）——
 * 线程池里的线程会被复用，若不还原，上一个任务残留的 traceId 会「串」到下一个任务上，
 * 制造出比「没有 traceId」更危险的假关联。
 *
 * <p>只搬 MDC 不搬 {@code TraceContext.HOLDER}：落库用的 {@code traceId} 在
 * {@code OperationLog} 实体构建时（提交线程内）就已写入字段，不依赖工作线程读取；
 * 这里要解决的是<b>日志输出</b>的 traceId 缺失。
 */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        // 提交线程的上下文快照（可为 null：调用方未设置任何 MDC）
        Map<String, String> submitContext = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> workerPrevious = MDC.getCopyOfContextMap();
            if (submitContext != null) {
                MDC.setContextMap(submitContext);
            } else {
                MDC.clear();
            }
            try {
                runnable.run();
            } finally {
                // 还原工作线程原有上下文，避免线程复用造成 traceId 串号
                if (workerPrevious != null) {
                    MDC.setContextMap(workerPrevious);
                } else {
                    MDC.clear();
                }
            }
        };
    }
}
