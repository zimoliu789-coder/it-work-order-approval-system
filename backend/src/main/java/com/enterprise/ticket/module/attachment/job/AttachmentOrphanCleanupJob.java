package com.enterprise.ticket.module.attachment.job;

import com.enterprise.ticket.module.attachment.service.AttachmentOrphanCleanupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 附件孤儿文件清理定时任务（需求方三波·第三波·）
 *
 * <p>每天 04:00 执行（排在 03:30 的操作日志清理之后，避免同一时刻并发重 IO）。
 * 具体的判定、宽限期与幂等策略见 {@link AttachmentOrphanCleanupService}。
 *
 * <p>异常在任务体内部被 try/catch 收敛：定时任务的调度线程是共享的，
 * 一次未捕获异常不会终止后续调度，但会在日志里留下难看的堆栈；更重要的是
 * 「清理失败」绝不应影响其它任务（ 定时任务异常隔离）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AttachmentOrphanCleanupJob {

    private final AttachmentOrphanCleanupService cleanupService;

    @Scheduled(cron = "0 0 4 * * ?")
    public void cleanOrphans() {
        try {
            int deleted = cleanupService.cleanup();
            if (deleted > 0) {
                log.info("附件孤儿清理任务完成：删除 {} 个文件", deleted);
            }
        } catch (Exception e) {
            log.error("附件孤儿清理任务失败", e);
        }
    }
}
