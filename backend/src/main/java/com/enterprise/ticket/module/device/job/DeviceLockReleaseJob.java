package com.enterprise.ticket.module.device.job;

import com.enterprise.ticket.module.device.service.DeviceService;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 临时锁超时释放（ / 「临时锁超时释放，每 5 分钟」）
 *
 * <p>用户在申请页选定设备后设备进入 LOCKED，若中途关页面 / 断网 / 直接离开，锁会一直占着设备，
 * 其他人无法申请。本任务按 {@code system_config.lock_timeout_minutes}（默认 5 分钟，可配 1–60）
 * 扫描超时锁并放回 AVAILABLE。
 *
 * <p>幂等性（「所有定时任务必须幂等」）：释放条件为
 * {@code status = LOCKED AND locked_at < now - timeout}，重复执行只会命中同一批尚未释放的行，
 * 已释放的行因状态不再是 LOCKED 而不会再被处理。
 *
 * <p>注意：本任务只是「兜底」而非唯一保障 —— 加锁接口本身也会把「已超时的锁」视为可接管，
 * 因此即使定时任务未及时运行，用户也不会被一把过期锁长期挡住。
 *
 * <p>「定时任务」还将补充审批超时提醒、到期预警、自动顺延、超时告警与设备工单对账，
 * 并统一记录任务执行日志。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceLockReleaseJob {

    private final DeviceService deviceService;
    private final SystemConfigService systemConfigService;

    /** 每 5 分钟执行一次 */
    @Scheduled(cron = "0 */5 * * * ?")
    public void releaseExpiredLocks() {
        try {
            int removed = deviceService.releaseExpiredLocks();
            if (removed > 0) {
                log.info("临时锁超时释放完成：释放 {} 台设备（超时阈值 {} 分钟）",
                        removed, systemConfigService.lockTimeoutMinutes());
            }
        } catch (Exception e) {
            // 定时任务异常不得影响调度线程
            log.error("临时锁超时释放失败", e);
        }
    }
}
