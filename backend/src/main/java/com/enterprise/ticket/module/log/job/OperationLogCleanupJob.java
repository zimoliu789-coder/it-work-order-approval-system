package com.enterprise.ticket.module.log.job;

import com.enterprise.ticket.module.log.service.OperationLogService;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 操作日志保留期清理（ / ）
 *
 * <p>保留天数由 {@code system_config.operation_log_retention_days} 控制（默认 90 天）。
 * 若无此定时任务，operation_logs 将无界增长，最终拖慢查询并放大写入开销。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OperationLogCleanupJob {

    private static final String RETENTION_KEY = "operation_log_retention_days";
    private static final int DEFAULT_RETENTION_DAYS = 90;

    private final OperationLogService operationLogService;
    private final SystemConfigService systemConfigService;

    /** 每天 03:30 清理超过保留期的日志 */
    @Scheduled(cron = "0 30 3 * * ?")
    public void cleanExpiredLogs() {
        try {
            int retentionDays = systemConfigService.getInt(RETENTION_KEY, DEFAULT_RETENTION_DAYS);
            int removed = operationLogService.cleanExpired(retentionDays);
            if (removed > 0) {
                log.info("操作日志清理完成：保留 {} 天，删除 {} 条", retentionDays, removed);
            }
        } catch (Exception e) {
            // 定时任务异常不得影响调度线程
            log.error("操作日志清理失败", e);
        }
    }
}
