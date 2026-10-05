package com.enterprise.ticket.common.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * MDC 跨线程传递单测（需求方三波·第三波·需求 15）
 *
 * <p>异步审计（{@code @AuditLog}）把落库交给线程池后，日志里的 {@code traceId} 一度为空，
 * 无法把一次异步操作与其所属请求关联起来。本类固化 {@link MdcTaskDecorator} 的两条契约：
 * <ol>
 *   <li><b>传递</b>：提交线程的 MDC 必须出现在工作线程内；</li>
 *   <li><b>还原</b>：任务结束必须还原工作线程<b>原有</b> MDC（而非清空）——
 *       线程池会复用线程，若不清干净，上一个任务的 traceId 会串到下一个任务上，
 *       制造比「没有 traceId」更危险的假关联。</li>
 * </ol>
 */
class MdcTaskDecoratorTest {

    private static final String KEY = "traceId";

    private final MdcTaskDecorator decorator = new MdcTaskDecorator();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("提交线程的 traceId 会传递到执行线程")
    void propagatesSubmitThreadTraceId() {
        MDC.put(KEY, "T-submit");
        AtomicReference<String> seen = new AtomicReference<>();
        Runnable task = decorator.decorate(() -> seen.set(MDC.get(KEY)));

        // 模拟「任务跑在另一个线程」：清空当前线程 MDC，装饰后的任务仍应看得到快照
        MDC.clear();
        task.run();

        assertEquals("T-submit", seen.get());
    }

    @Test
    @DisplayName("任务结束还原工作线程原有 MDC，避免线程复用串号")
    void restoresWorkerContextAfterRun() {
        MDC.put(KEY, "T-submit");
        Runnable task = decorator.decorate(() -> {
            // 任务内应看到提交线程的 traceId
            assertEquals("T-submit", MDC.get(KEY));
        });

        // 工作线程进入任务前，已经带着「上一个任务」的上下文
        MDC.clear();
        MDC.put(KEY, "T-previous");
        MDC.put("operatorId", "9");
        task.run();

        assertEquals("T-previous", MDC.get(KEY), "任务结束必须还原工作线程原有 traceId");
        assertEquals("9", MDC.get("operatorId"));
    }

    @Test
    @DisplayName("提交线程无 MDC 时，任务内读不到工作线程残留的 traceId")
    void clearsStaleContextWhenSubmitContextMissing() {
        MDC.clear();
        AtomicReference<String> seen = new AtomicReference<>("unset");
        Runnable task = decorator.decorate(() -> seen.set(MDC.get(KEY)));

        MDC.put(KEY, "T-stale");
        task.run();

        assertNull(seen.get(), "提交线程无上下文时，任务内不应继承工作线程的残留值");
    }
}
