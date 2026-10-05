package com.enterprise.ticket.module.export.job;

import com.enterprise.ticket.module.export.service.ExportMaintenanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 导出文件过期清理 + 僵尸任务回收定时任务（需求方三波·第三波·）
 *
 * <p>每天 04:30 执行。具体的过期判定、僵尸阈值与宽限期见
 * {@link ExportMaintenanceService}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExportMaintenanceJob {

    private final ExportMaintenanceService maintenanceService;

    @Scheduled(cron = "0 30 4 * * ?")
    public void run() {
        try {
            int handled = maintenanceService.cleanup();
            if (handled > 0) {
                log.info("导出维护任务完成：处理 {} 项", handled);
            }
        } catch (Exception e) {
            // 定时任务异常隔离：一次失败不影响其它任务
            log.error("导出维护任务失败", e);
        }
    }
}
