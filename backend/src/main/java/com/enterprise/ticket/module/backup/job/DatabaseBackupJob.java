package com.enterprise.ticket.module.backup.job;

import com.enterprise.ticket.module.backup.dto.vo.BackupRecordVO;
import com.enterprise.ticket.module.backup.service.BackupService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 数据库自动备份定时任务（P0）
 *
 * <h2>为什么是「每 10 分钟唤醒 + 当天没跑过就补跑」，而不是「每小时比对小时数」</h2>
 * 最直觉的写法是「每小时整点检查当前小时是否等于配置的备份时刻」。它有一个
 * <b>无法察觉的失效方式</b>：若配置的是 02:00，而应用在那一刻正在重启 / 宕机，
 * 那么当天<b>一次都不会备份</b>，而且没有任何提示 ——
 * 页面上显示开关是开的、配置是对的，唯独没有新记录。
 * 换成「补跑」语义后，应用恢复运行后的第一个 10 分钟窗口就会补上，且因为幂等位
 * 落在库里（当天已有 SCHEDULED 记录），一天仍然只跑一次。
 *
 * <h2>为什么唤醒间隔是 10 分钟而不是 1 分钟</h2>
 * 补跑语义下精度只需要「当天之内」，10 分钟足够且把空转的查询降到 144 次/天。
 * 每次唤醒只做两条极轻的 COUNT（是否存在 RUNNING、当天是否已有 SCHEDULED），
 * 且开关关闭时连库都不查（先判内存里的配置读取）。
 *
 * <h2>启动时先做僵尸回收</h2>
 * 进程被杀会让记录停在 RUNNING，而那条记录会让「是否存在 RUNNING」恒为真，
 * <b>把之后所有备份全部挡掉</b>（含定时）。因此在启动阶段先清理一次，
 * 而不是等到第一次备份被拒时才发现。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseBackupJob {

    private final BackupService backupService;

    /**
     * 启动对账：回收上一次进程残留的「备份中」记录。
     *
     * <p>{@code @PostConstruct} 而不是定时执行：这个清理只有在<b>进程刚起来</b>
     * 的时刻才有意义 —— 我的进程刚启动，那么任何 RUNNING 记录必然是「别人留下的」
     * （上个进程被杀时来不及落终态）。
     */
    @PostConstruct
    public void recycleOnStartup() {
        try {
            backupService.recycleStaleRunning();
        } catch (Exception e) {
            // 启动阶段的清理失败绝不能影响应用启动：真出问题时，第一次备份
            // 会以「已有备份正在执行」被拒，管理员在页面上仍能看出端倪。
            log.error("启动时回收残留备份记录失败（不影响启动）", e);
        }
    }

    @Scheduled(cron = "0 */10 * * * *")
    public void scheduledBackup() {
        try {
            LocalDateTime now = LocalDateTime.now();
            BackupRecordVO record = backupService.runScheduled();
            if (record != null) {
                log.info("定时备份已执行（{} 触发，时刻 {}）：{}",
                        record.getTriggerLabel(), now, record.getStatusLabel());
            }
        } catch (Exception e) {
            // 定时任务的调度线程是共享的：一次未捕获异常虽不会终止后续调度，
            // 但会在日志里留下难看的堆栈，且可能把同批其它任务带偏。
            // 失败原因本身已由服务层落库并告警，这里只需避免异常外溢。
            log.error("定时备份任务异常", e);
        }
    }
}
