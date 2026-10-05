package com.enterprise.ticket.module.system.controller;

import com.enterprise.ticket.common.api.ApiResponse;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.job.JobResult;
import com.enterprise.ticket.common.log.AuditLog;
import com.enterprise.ticket.module.attachment.service.AttachmentRetentionCleanupService;
import com.enterprise.ticket.module.backup.dto.vo.BackupRecordVO;
import com.enterprise.ticket.module.backup.service.BackupService;
import com.enterprise.ticket.module.device.service.DeviceReconcileService;
import com.enterprise.ticket.module.device.service.DeviceService;
import com.enterprise.ticket.module.order.service.ApprovalTimeoutJobService;
import com.enterprise.ticket.module.order.service.BorrowJobService;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 定时任务手动触发（运维与验收用，）
 *
 * <p><b>为什么需要这个接口</b>：各任务的调度频率都是「每天 1 次」或「每小时 1 次」，
 * 若只保留 cron，验收与排障时只能等到下一个调度点才能确认效果，也无法在出问题时
 * 立即补跑。因此额外开放一个 <b>仅 super_admin</b> 的手动触发入口。
 *
 * <p>安全性：任务本身全部幂等（数据层幂等位 + 条件 UPDATE + 分布式锁），因此重复触发不会造成
 * 重复通知、重复顺延或重复告警 —— 这也是敢于开放手动触发的前提。
 *
 * <p>测试路径：{@code POST http://localhost:8080/api/admin/jobs/run?job=all}
 * <br>可选值：{@code all}（默认）/ {@code expire-warning} / {@code auto-extend} /
 * {@code timeout-alert} / {@code approval-timeout-remind} / {@code device-reconcile} /
 * {@code lock-release} / {@code attachment-retention}
 *
 * <p>说明：三波补做·第二波新增了「审批超时提醒」与「设备工单对账」两个定时任务
 * （ / ），它们此前只有 cron 入口、无法人工补跑；此处一并纳入，
 * 使这两个任务与既有借用任务一样可被验收与运维触发。
 *  收尾优化· 又把「临时锁超时释放」（{@code lock-release}）纳入同一白名单。
 *
 * <p><b>为什么不把 {@code attachment-retention} 并进 {@code all}</b>：
 * {@code all} 的语义是「把日常可安全重跑的任务跑一遍」，运维在排障时习惯直接点它；
 * 而附件保留期清理会<b>物理删除磁盘文件与数据库行</b>，一旦误配了保留天数（例如被改成 1 天），
 * 「顺手跑个 all」就会连带执行一次不可逆删除。因此它只提供独立 job 名，
 * 必须由知道自己在做什么的人显式指定。
 */
@RestController
@RequestMapping("/api/admin/jobs")
@RequiredArgsConstructor
public class JobAdminController {

    private static final String JOB_ALL = "all";
    private static final String JOB_EXPIRE_WARNING = "expire-warning";
    private static final String JOB_AUTO_EXTEND = "auto-extend";
    private static final String JOB_TIMEOUT_ALERT = "timeout-alert";
    private static final String JOB_APPROVAL_TIMEOUT_REMIND = "approval-timeout-remind";
    private static final String JOB_DEVICE_RECONCILE = "device-reconcile";
    /**
     * 临时锁超时释放（ 收尾优化·）
     *
     * <p>此前该任务<b>只有 cron 入口</b>（每 5 分钟），运维遇到「用户反馈设备被锁住无人能申请」
     * 时只能等下一次调度，验收时也无法立刻复现效果。纳入白名单后与其他任务口径一致。
     *
     * <p>jobName 与请求参数同名（见类注释「与分布式锁 key、手动触发接口的 job 参数一致」）：
     * 这个任务本身用条件 UPDATE（{@code status = LOCKED AND locked_at < 阈值}）保证幂等，
     * 不依赖分布式锁 —— 重复触发只会命中同一批尚未释放的行，不会重复处理。
     */
    private static final String JOB_LOCK_RELEASE = "lock-release";

    /**
     * 附件保留期清理（·）
     *
     * <p>jobName 与 {@code AttachmentRetentionCleanupService} 的分布式锁 key 一致。
     * 刻意<b>不并入 {@code all}</b>，理由见类注释末段。
     */
    private static final String JOB_ATTACHMENT_RETENTION = "attachment-retention";

    /**
     * 数据库自动备份（P0）
     *
     * <p>走的是<b>定时语义</b>（{@code runScheduled()}）：未启用或今天已完成过定时备份时会
     * 明确回一句「未执行」及原因，而不是静默成功。
     * 要「立刻强制备份一份」请用「系统设置 → 备份记录」页的「立即备份」按钮 ——
     * 那条路径不受「今天是否跑过」与开关限制（{@code runManually()}）。
     * 两个入口共用同一个服务实现，差别只在准入条件与返回结构。
     */
    private static final String JOB_DATABASE_BACKUP = "database-backup";

    private static final String JOB_HINT = "（可选 all / expire-warning / auto-extend / timeout-alert "
            + "/ approval-timeout-remind / device-reconcile / lock-release / attachment-retention "
            + "/ database-backup）";

    private final BorrowJobService borrowJobService;
    private final ApprovalTimeoutJobService approvalTimeoutJobService;
    private final DeviceReconcileService deviceReconcileService;
    private final DeviceService deviceService;
    /** 读取锁超时阈值，用于把「释放了几台」写得可解释（阈值本身由参数页维护） */
    private final SystemConfigService systemConfigService;
    /** 附件保留期清理：会物理删文件，故只走独立 job 名 */
    private final AttachmentRetentionCleanupService attachmentRetentionCleanupService;
    /** 数据库自动备份（P0）：同样只走独立 job 名 —— 它会做一次全库导出，开销不小 */
    private final BackupService backupService;

    @PostMapping("/run")
    @PreAuthorize("@perm.has('job:run')")
    @AuditLog(module = "JOB", action = "JOB_MANUAL_RUN", description = "手动触发定时任务")
    public ApiResponse<List<JobResult>> run(@RequestParam(defaultValue = JOB_ALL) String job) {
        String normalized = job == null ? JOB_ALL : job.trim().toLowerCase(Locale.ROOT);
        List<JobResult> results = switch (normalized) {
            case JOB_ALL -> {
                List<JobResult> all = new ArrayList<>(borrowJobService.runAll());
                all.add(approvalTimeoutJobService.remindOverdueApprovals());
                all.add(deviceReconcileService.reconcile());
                all.add(runLockRelease());
                yield all;
            }
            case JOB_EXPIRE_WARNING -> List.of(borrowJobService.warnExpiringBorrows());
            case JOB_AUTO_EXTEND -> List.of(borrowJobService.autoExtendOverdueBorrows());
            case JOB_TIMEOUT_ALERT -> List.of(borrowJobService.alertTimeoutBorrows());
            case JOB_APPROVAL_TIMEOUT_REMIND -> List.of(approvalTimeoutJobService.remindOverdueApprovals());
            case JOB_DEVICE_RECONCILE -> List.of(deviceReconcileService.reconcile());
            case JOB_LOCK_RELEASE -> List.of(runLockRelease());
            case JOB_ATTACHMENT_RETENTION -> List.of(runAttachmentRetention());
            case JOB_DATABASE_BACKUP -> List.of(runDatabaseBackup());
            default -> throw new BusinessException(ErrorCode.JOB_NOT_FOUND,
                    "不支持的定时任务：" + job + JOB_HINT);
        };
        return ApiResponse.success("定时任务执行完成", results);
    }

    /**
     * 执行一次临时锁超时释放，并把「释放了几台」包装成 {@link JobResult}。
     *
     * <p>包一层而不是直接调 {@code deviceService}：手动触发接口对外的返回结构是统一的
     * {@code List<JobResult>}，若这里单独回一个裸数字，前端就要为「这一个任务」写特殊分支。
     */
    private JobResult runLockRelease() {
        int released = deviceService.releaseExpiredLocks();
        return JobResult.of(JOB_LOCK_RELEASE, released, 0,
                released == 0 ? "没有超时的临时锁需要释放"
                        : "已释放 " + released + " 台超时锁定的设备（阈值 "
                                + systemConfigService.lockTimeoutMinutes() + " 分钟）");
    }

    /**
     * 执行一次附件保留期清理，并把「清理了几个」包装成 {@link JobResult}。
     *
     * <p>detail 里带上保留天数：「清理 0 个」有两种完全不同的原因 ——
     * 「本来就没有超期附件」与「保留天数被配成了 5 年」，只看数字分不出来。
     */
    private JobResult runAttachmentRetention() {
        int deleted = attachmentRetentionCleanupService.cleanup();
        int retentionDays = systemConfigService.attachmentRetentionDays();
        return JobResult.of(JOB_ATTACHMENT_RETENTION, deleted, 0,
                deleted == 0 ? "没有超过保留期（" + retentionDays + " 天）的附件需要清理"
                        : "已清理 " + deleted + " 个超过保留期（" + retentionDays + " 天）的附件");
    }

    /**
     * 执行一次数据库备份，并把结果包成 {@link JobResult}。
     *
     * <p>「未执行」与「执行失败」必须<b>分开表达</b>：前者是配置与时间安排的结果
     * （未启用 / 今天已跑过），后者是环境出了问题（目录不可写 / 磁盘满）。
     * 若都笼统回一句「失败」，运维会去查一个根本不存在的故障。
     */
    private JobResult runDatabaseBackup() {
        BackupRecordVO record = backupService.runScheduled();
        if (record == null) {
            return JobResult.of(JOB_DATABASE_BACKUP, 0, 0,
                    "未执行：自动备份未启用，或今天已完成过定时备份（每天只自动备份一次）");
        }
        boolean ok = "SUCCESS".equals(record.getStatus());
        return JobResult.of(JOB_DATABASE_BACKUP, ok ? 1 : 0, ok ? 0 : 1,
                ok ? "备份成功：" + record.getFileName() + "（" + record.getSizeText() + "）"
                        : "备份失败：" + record.getErrorMessage());
    }
}
