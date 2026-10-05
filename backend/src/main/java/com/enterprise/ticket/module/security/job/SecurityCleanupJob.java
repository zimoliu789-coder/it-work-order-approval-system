package com.enterprise.ticket.module.security.job;

import com.enterprise.ticket.common.job.JobLockService;
import com.enterprise.ticket.module.security.mapper.IpBlockMapper;
import com.enterprise.ticket.module.security.mapper.SecurityEventMapper;
import com.enterprise.ticket.module.security.service.IpBlockService;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 安全数据的每日维护。
 *
 * <h2>⚠️ 这个任务**不是**解封的必要条件</h2>
 * 「IP 封禁到期自动解封」由 {@code IpBlockService#isBlocked} 的**懒判定**完成
 * （每次判定比一次时间）。本任务只做两件清理工作：
 * <ol>
 *   <li>把已过期的封禁行标为失效 —— 纯视图整理，让页面上的「历史」视图干净；</li>
 *   <li>按保留期删除超期的安全事件与已失效的封禁记录。</li>
 * </ol>
 * 这样设计的原因是：**定时任务停摆不能改变安全策略的语义**。
 * 如果解封依赖本任务，那么任务一挂，说好的「30 分钟」就变成永久封禁 ——
 * 而没有人会想到去查「为什么今天所有被封的 IP 都没解封」。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SecurityCleanupJob {

    /** 单批删除行数（一次删几十万行会长时间持锁） */
    private static final int BATCH_SIZE = 5000;

    /** 单次最多跑多少批（= 每天最多清理 100 万行，剩下的明天继续） */
    private static final int MAX_BATCHES = 200;

    private final SecurityEventMapper securityEventMapper;
    private final IpBlockMapper ipBlockMapper;
    private final IpBlockService ipBlockService;
    private final SystemConfigService systemConfigService;
    private final JobLockService jobLockService;

    /**
     * 每天 03:40 清理（与异常日志的 03:30 错开 10 分钟，避免两个大删除撞在一起抢锁）。
     */
    @Scheduled(cron = "0 40 3 * * *")
    public void cleanup() {
        jobLockService.runLocked("security-cleanup", Duration.ofMinutes(20), () -> {
            try {
                int expired = ipBlockService.cleanupExpired();
                int retentionDays = readRetentionDays();
                LocalDateTime before = LocalDateTime.now().minusDays(retentionDays);

                int events = deleteInBatches(() -> securityEventMapper.deleteBefore(before, BATCH_SIZE));
                int blocks = deleteInBatches(() -> ipBlockMapper.deleteUnblockedBefore(before, BATCH_SIZE));

                if (expired > 0 || events > 0 || blocks > 0) {
                    log.info("[安全清理] 标记过期封禁 {} 条；删除安全事件 {} 条、失效封禁 {} 条（保留 {} 天）",
                            expired, events, blocks, retentionDays);
                }
            } catch (Exception e) {
                // 定时任务异常不得影响调度线程（与其它 job 同一约定）
                log.error("[安全清理] 失败", e);
            }
            return 0;
        }, () -> 0);
    }

    private int deleteInBatches(java.util.function.IntSupplier deleteOnce) {
        int total = 0;
        for (int round = 0; round < MAX_BATCHES; round++) {
            int rows = deleteOnce.getAsInt();
            total += rows;
            if (rows < BATCH_SIZE) {
                break;
            }
        }
        return total;
    }

    private int readRetentionDays() {
        try {
            return systemConfigService.securityEventRetentionDays();
        } catch (Exception e) {
            log.warn("[安全清理] 读取保留天数失败，用缺省值 90：{}", e.getMessage());
            return 90;
        }
    }
}
