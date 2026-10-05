package com.enterprise.ticket.module.ad.job;

import com.enterprise.ticket.module.ad.dto.AdSyncResultVO;
import com.enterprise.ticket.module.ad.entity.AdConfig;
import com.enterprise.ticket.module.ad.service.AdConfigService;
import com.enterprise.ticket.module.ad.service.AdUserSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * AD 用户定时同步（；；开关与时刻归位 ）
 *
 * <h2>为什么是「每小时醒一次 + 自己判断到点没」，而不是直接写 cron 2 点</h2>
 * <p>需求要求执行时刻<b>可配置</b>（原 {@code system_config.ad_sync_hour}，
 *  起为 {@code ad_config.sync_hour}）。
 * {@code @Scheduled} 的 cron 表达式在启动时固定，无法随参数热更新 ——
 * 要支持可配置时刻，只有三条路：重建调度线程池（复杂度高、风险大）、
 * 引入 Quartz（为一个任务引入整条调度链路），或者「高频唤醒 + 条件判断」。
 * 这里选第三条：每小时的第 0 分钟醒一次，判断「当前小时 == 配置时刻」才真正执行。
 * 代价是每小时一次空转（成本可忽略），换来的是「改配置保存即生效」。
 *
 * <h2>开关与时刻为什么读 {@code AdConfigService} 而不是 {@code SystemConfigService}</h2>
 * <p> 把它们从 {@code system_config} 搬进了 {@code ad_config}（迁移 V36），
 * 目的是让 AD 的「连接 + 同步」在一个页面里维护完（）。
 * 本类因此不再依赖 {@code SystemConfigService} —— <b>依赖方向与配置的存储位置保持一致</b>：
 * 若这里仍去读 system_config，迁移删掉那两个键之后，定时同步会静默地永远不触发
 * （读到默认值 false），而且没有任何报错。
 *
 * <h2>幂等性（「所有定时任务必须幂等」）</h2>
 * <p>「每小时醒一次」意味着<b>同一个小时内可能被唤醒多次</b>（例如应用重启、
 * 或手动触发了同步）。若不设防，重启窗口期内可能同步两三遍。
 * 因此执行前检查 {@code ad_config.last_sync_at}：若它落在当前这一小时内，直接跳过。
 * 这个判据的好处是<b>持久化</b>——重启后依然有效，不依赖内存状态。
 *
 * <h2>与手动同步的关系</h2>
 * <p>定时入口只做「开关 + 时间 + 幂等」三件事，真正的同步逻辑一律走
 * {@link AdUserSyncService#sync()}。两条入口共用同一实现，避免
 * 「手动同步正常、自动同步建出脏数据」这种最难解释的分歧。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdUserSyncJob {

    private final AdUserSyncService adUserSyncService;
    private final AdConfigService adConfigService;

    /** 每小时第 0 分钟醒来检查一次；是否真的执行由「开关 + 小时 + 幂等」三项决定 */
    @Scheduled(cron = "0 0 * * * ?")
    public void scheduledSync() {
        try {
            if (!adConfigService.syncEnabled()) {
                return;
            }
            if (!adConfigService.isEnabled()) {
                // 同步开关开着但 AD 本身没启用：属于配置不一致，提示一次即可，不反复告警
                log.info("AD 定时同步已开启，但 AD 认证未启用，跳过本次同步");
                return;
            }
            int configuredHour = adConfigService.syncHour();
            LocalDateTime now = LocalDateTime.now();
            if (now.getHour() != configuredHour) {
                return;
            }
            if (alreadySyncedThisHour(now)) {
                log.debug("本小时（{} 时）已完成过 AD 同步，跳过", configuredHour);
                return;
            }
            log.info("AD 定时同步开始（配置时刻 {} 时）", configuredHour);
            AdSyncResultVO result = adUserSyncService.sync();
            log.info("AD 定时同步结束：{}", result.getMessage());
        } catch (Exception e) {
            // 定时任务异常必须隔离：抛出去会污染调度线程池，影响其它任务的后续触发
            log.error("AD 定时同步执行失败，已隔离异常避免影响其它调度任务", e);
        }
    }

    /** 当前这一小时内是否已经同步过（幂等判据，见类注释） */
    private boolean alreadySyncedThisHour(LocalDateTime now) {
        AdConfig config = adConfigService.current();
        LocalDateTime lastSyncAt = config.getLastSyncAt();
        if (lastSyncAt == null) {
            return false;
        }
        LocalDate today = now.toLocalDate();
        return lastSyncAt.toLocalDate().equals(today) && lastSyncAt.getHour() == now.getHour();
    }
}
