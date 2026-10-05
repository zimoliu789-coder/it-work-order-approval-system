package com.enterprise.ticket.module.device.job;

import com.enterprise.ticket.common.job.JobResult;
import com.enterprise.ticket.module.device.service.DeviceReconcileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 设备工单对账调度器（需求方三波·第二波·）
 *
 * <p>每天 02:30 执行。时刻选择的原因：
 * <ul>
 *   <li>避开 00:30 / 01:00 / 01:30 的三个借用任务与 02:00 的附件清理 —— 对账要读的是
 *       「这些任务跑完之后」的状态，跑在它们前面会把刚刚被顺延、被超时告警的单误判为异常；</li>
 *   <li>又必须在上班前完成，否则管理员到岗时看到的仍可能是昨天的告警。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceOrderReconcileJob {

    private final DeviceReconcileService deviceReconcileService;

    /** 每天 02:30 执行 */
    @Scheduled(cron = "0 30 2 * * ?")
    public void reconcile() {
        try {
            JobResult result = deviceReconcileService.reconcile();
            log.info("定时任务[设备工单对账]完成：affected={} notified={} detail={}",
                    result.affected(), result.notified(), result.detail());
        } catch (Exception e) {
            log.error("定时任务[设备工单对账]执行失败，已隔离异常", e);
        }
    }
}
