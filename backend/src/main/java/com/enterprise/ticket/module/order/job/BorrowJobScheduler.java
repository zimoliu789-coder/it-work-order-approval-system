package com.enterprise.ticket.module.order.job;

import com.enterprise.ticket.common.job.JobResult;
import com.enterprise.ticket.module.order.service.BorrowJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 借用到期 / 顺延 / 超时 定时任务调度器
 *
 * <p>本类只负责「什么时候跑」与「异常隔离」，「跑什么」全部在
 * {@link BorrowJobService} 里 —— 这样同一套逻辑既能被调度器按天触发，
 * 也能被运维通过 {@code POST /api/admin/jobs/run} 手动触发（验收与排障必需），
 * 且单测可以绕过调度直接调用服务方法。
 *
 * <p><b>触发时刻刻意错开</b>（每日一次，）：
 * <pre>
 * 00:30  到期预警   —— 必须在顺延之前，否则「到期当天提醒」会被顺延推走的到期日吞掉
 * 01:00  自动顺延   —— 把已逾期的计划结束时间后移，最多 2 次
 * 01:30  超时告警   —— 读顺延后的 auto_extend_count 判定「2 次顺延后仍未归还」
 * </pre>
 * 三个任务的幂等性由数据层幂等位保证，因此即便因为重启补跑、或手动触发导致同一天跑多次，
 * 也不会重复通知（详见 {@link BorrowJobService} 注释）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BorrowJobScheduler {

    private final BorrowJobService borrowJobService;

    /** 到期预警：每天 00:30（「到期预警检查，每天 1 次」） */
    @Scheduled(cron = "0 30 0 * * ?")
    public void warnExpiringBorrows() {
        safeRun("到期预警", borrowJobService::warnExpiringBorrows);
    }

    /** 自动顺延：每天 01:00（「自动顺延检查，每天 1 次」） */
    @Scheduled(cron = "0 0 1 * * ?")
    public void autoExtendOverdueBorrows() {
        safeRun("自动顺延", borrowJobService::autoExtendOverdueBorrows);
    }

    /** 超时告警：每天 01:30（「超时告警检查，每天 1 次」，重复推送间隔可配） */
    @Scheduled(cron = "0 30 1 * * ?")
    public void alertTimeoutBorrows() {
        safeRun("超时告警", borrowJobService::alertTimeoutBorrows);
    }

    /**
     * 统一的异常隔离：定时任务抛异常会污染调度线程池的后续任务，
     * 因此这里必须兜住所有异常（与既有 {@code DeviceLockReleaseJob} 的处理方式一致）。
     */
    private void safeRun(String label, Supplier<JobResult> action) {
        try {
            JobResult result = action.get();
            log.info("定时任务[{}]完成：affected={} notified={} detail={}",
                    label, result.affected(), result.notified(), result.detail());
        } catch (Exception e) {
            log.error("定时任务[{}]执行失败，已隔离异常避免影响其它调度任务", label, e);
        }
    }
}
