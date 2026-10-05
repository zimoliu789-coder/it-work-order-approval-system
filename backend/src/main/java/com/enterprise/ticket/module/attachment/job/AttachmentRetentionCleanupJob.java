package com.enterprise.ticket.module.attachment.job;

import com.enterprise.ticket.module.attachment.service.AttachmentRetentionCleanupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 附件保留期清理定时任务（；「已删工单附件保留」）
 *
 * <h2>为什么排在 03:00</h2>
 * <p>与既有任务错峰、避免同一时刻并发重 IO：
 * <pre>
 *   02:30  设备工单对账 / 审批超时提醒
 *   03:00  附件保留期清理（本任务）
 *   03:30  操作日志清理
 *   04:00  附件孤儿清理
 *   04:30  导出维护
 * </pre>
 * 其中与「附件孤儿清理」的关系值得一提：两者都在删附件文件，但判据<b>不同且互补</b> ——
 * 孤儿清理按「磁盘上有没有、记录里引用没有」，本任务按「记录已删 / 主体不在，且超过保留期」。
 * 前者捞起「记录都删没了但文件还在」的残留，后者清理「记录还在（软删）但已过保留期」的行。
 *
 * <p>具体判定、审计与幂等策略见 {@link AttachmentRetentionCleanupService}。
 *
 * <p>异常在任务体内部被 try/catch 收敛（ 定时任务异常隔离）：
 * 定时任务的调度线程是共享的，一次未捕获异常虽不会终止后续调度，但会在日志里留下难看的堆栈；
 * 更重要的是「清理失败」绝不应影响其它任务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AttachmentRetentionCleanupJob {

    private final AttachmentRetentionCleanupService cleanupService;

    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanExpiredAttachments() {
        try {
            int deleted = cleanupService.cleanup();
            if (deleted > 0) {
                log.info("附件保留期清理任务完成：清理 {} 个", deleted);
            }
        } catch (Exception e) {
            log.error("附件保留期清理任务失败", e);
        }
    }
}
