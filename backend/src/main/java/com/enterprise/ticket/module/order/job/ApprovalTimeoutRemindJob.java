package com.enterprise.ticket.module.order.job;

import com.enterprise.ticket.common.job.JobResult;
import com.enterprise.ticket.module.order.service.ApprovalTimeoutJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 审批超时提醒调度器（需求方三波·第二波·； 改为每天凌晨）
 *
 * <h2>触发频率变更说明</h2>
 * <p>改造前按「审批超时提醒检查，每小时 1 次」执行，触发在每小时第 5 分钟。
 *  需求方明确要求改为<b>每天凌晨一次</b> —— 理由是审批提醒不必小时级轰炸，
 * 而每节点「每天最多一条」也更符合「约定审批时限」的语义。
 * 因此 cron 改为每天 02:30（与其它凌晨任务错峰，避免同一时刻集中打库）。
 *
 * <p>任务内部对有 {@code deadline_at} 的节点按 24 小时去重、对无 deadline 的节点沿用
 * 原阈值窗口 —— 频率下调不会让「借用单 / GROUP 单」的既有提醒行为发生变化。
 *
 * <p>与 {@code BorrowJobScheduler} 同样的分工：本类只管「什么时候跑」与「异常隔离」，
 * 业务逻辑全部在 {@link ApprovalTimeoutJobService} 里，从而同一套逻辑既能被调度触发，
 * 也能被运维通过 {@code POST /api/admin/jobs/run} 手动触发。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApprovalTimeoutRemindJob {

    private final ApprovalTimeoutJobService approvalTimeoutJobService;

    /** 每天 02:30 执行（：由「每小时」改为「每天凌晨一次」） */
    @Scheduled(cron = "0 30 2 * * ?")
    public void remind() {
        try {
            JobResult result = approvalTimeoutJobService.remindOverdueApprovals();
            log.info("定时任务[审批超时提醒]完成：affected={} notified={} detail={}",
                    result.affected(), result.notified(), result.detail());
        } catch (Exception e) {
            // 定时任务抛异常会污染调度线程池，必须兜住
            log.error("定时任务[审批超时提醒]执行失败，已隔离异常", e);
        }
    }
}
