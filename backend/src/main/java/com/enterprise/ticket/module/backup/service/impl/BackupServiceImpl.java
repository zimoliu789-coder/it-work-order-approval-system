package com.enterprise.ticket.module.backup.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.BackupStatus;
import com.enterprise.ticket.common.constant.BackupTrigger;
import com.enterprise.ticket.common.constant.MessageType;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.config.AppProperties;
import com.enterprise.ticket.module.backup.dto.vo.BackupOverviewVO;
import com.enterprise.ticket.module.backup.dto.vo.BackupRecordVO;
import com.enterprise.ticket.module.backup.entity.BackupRecord;
import com.enterprise.ticket.module.backup.mapper.BackupRecordMapper;
import com.enterprise.ticket.module.backup.service.BackupService;
import com.enterprise.ticket.module.backup.support.BackupException;
import com.enterprise.ticket.module.backup.support.DatabaseDumper;
import com.enterprise.ticket.module.message.service.MessageService;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * 数据库备份服务实现（P0）
 *
 * <h2>为什么整个流程<b>没有</b> {@code @Transactional}</h2>
 * 备份的核心动作是「跑 mysqldump」，耗时从数秒到数十分钟不等。
 * 若把它包在一个事务里，这段时间内会一直占着一条数据库连接与一个事务快照 ——
 * 既拖累正常业务，也让「备份失败」有概率连带回滚掉状态记录，
 * 于是页面上留不下任何失败痕迹（而失败恰恰是最需要留痕的情况）。
 * 因此这里改为<b>显式三段写</b>：先插 RUNNING → 执行外部命令 → 再条件 UPDATE 落终态。
 * 代价是「RUNNING 记录可能残留」，由 {@link #recycleStaleRunning()} 兜住。
 *
 * <h2>保留清理为什么放在<b>成功之后</b></h2>
 * 铁律，与 {@code deploy/backup/backup.sh} 一致：
 * 若「先清理、后校验」，某天备份失败 + 同时删掉旧归档，会叠加成
 * <b>一个能用的备份都不剩</b>。因此清理只在本次 SUCCESS 时触发。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BackupServiceImpl implements BackupService {

    private final BackupRecordMapper backupRecordMapper;
    private final DatabaseDumper dumper;
    private final SystemConfigService systemConfigService;
    private final AppProperties appProperties;
    private final MessageService messageService;
    private final UserMapper userMapper;

    // ------------------------------------------------------------------
    // 执行
    // ------------------------------------------------------------------

    @Override
    public BackupRecordVO runManually(Long operatorId) {
        // 并发保护：判据是「库里存在 RUNNING 行」而不是「本实例有线程在跑」——
        // 后者在多实例部署下形同虚设（A 实例在跑，B 实例照样发起第二条，
        // 两个 mysqldump 同时压同一个库）。
        if (backupRecordMapper.countRunning() > 0) {
            throw new BusinessException(ErrorCode.BACKUP_ALREADY_RUNNING);
        }
        return execute(BackupTrigger.MANUAL, operatorId);
    }

    @Override
    public BackupRecordVO runScheduled() {
        if (!systemConfigService.backupEnabled()) {
            return null;
        }
        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        if (backupRecordMapper.countScheduledSince(dayStart) > 0) {
            // 补跑语义的幂等位：当天已有定时备份 ⇒ 不再执行。
            // 判据落在**库里**而不是内存标志 —— 否则应用重启一次就会多备份一遍。
            return null;
        }
        if (backupRecordMapper.countRunning() > 0) {
            log.info("已有备份正在执行，本次定时备份跳过");
            return null;
        }
        return execute(BackupTrigger.SCHEDULED, null);
    }

    /**
     * 执行一次备份并落库。**不抛业务异常**：环境类失败（mysqldump 缺失 / 目录不可写 /
     * 磁盘满）一律落成 FAILED 记录 + 告警，让调用方与页面都能看到确切原因。
     */
    private BackupRecordVO execute(BackupTrigger trigger, Long operatorId) {
        LocalDateTime startedAt = LocalDateTime.now();
        Path dir = resolveDir();

        BackupRecord record = new BackupRecord();
        record.setFileName("");
        record.setFilePath("");
        record.setFileSize(0L);
        record.setStatus(BackupStatus.RUNNING.name());
        record.setTriggerType(trigger.name());
        record.setStartedAt(startedAt);
        record.setDurationMs(0L);
        record.setOperatorId(operatorId);
        record.setCreatedAt(startedAt);
        backupRecordMapper.insert(record);

        String fileName = DatabaseDumper.fileNameOf(startedAt);
        long beginMillis = System.currentTimeMillis();
        try {
            DatabaseDumper.Artifact artifact = dumper.dump(dir, fileName);
            long cost = System.currentTimeMillis() - beginMillis;

            BackupRecord update = new BackupRecord();
            update.setId(record.getId());
            update.setFileName(artifact.file().getFileName().toString());
            update.setFilePath(DatabaseDumper.normalize(artifact.file()));
            update.setFileSize(artifact.size());
            update.setStatus(BackupStatus.SUCCESS.name());
            update.setFinishedAt(LocalDateTime.now());
            update.setDurationMs(cost);
            backupRecordMapper.updateById(update);

            int removed = cleanupExpired();
            log.info("数据库备份成功：{}（{} 字节，耗时 {} ms，触发方式 {}，清理超期归档 {} 个）",
                    artifact.file().getFileName(), artifact.size(), cost, trigger.name(), removed);
            return resultOf(record, update);
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - beginMillis;
            String reason = reasonOf(e);
            log.error("数据库备份失败（触发方式 {}）：{}", trigger.name(), reason, e);

            BackupRecord update = new BackupRecord();
            update.setId(record.getId());
            update.setStatus(BackupStatus.FAILED.name());
            update.setFinishedAt(LocalDateTime.now());
            update.setDurationMs(cost);
            update.setErrorMessage(reason);
            backupRecordMapper.updateById(update);

            notifyFailure(trigger, reason);
            return resultOf(record, update);
        }
    }

    /**
     * 由「插入时的基线 + 终态更新」组装出对外返回的 VO。
     *
     * <h2>为什么要新建对象，而不是就地回写基线对象</h2>
     * 基线对象已经交给 {@code insert()} 过（MyBatis 还会回填自增 id），
     * 若随后在它身上改字段，任何持有同一引用的地方都会看到<b>被改过的状态</b> ——
     * 首当其冲的就是「插入时必须是 RUNNING」这个事实：它一旦被就地覆盖，
     * 「备份中」在页面上就永远不出现，僵尸回收也失去判据。
     * 返回一份新对象可以让「插入的」与「返回的」彻底解耦。
     */
    private BackupRecordVO resultOf(BackupRecord base, BackupRecord update) {
        BackupRecord merged = new BackupRecord();
        merged.setId(base.getId());
        merged.setTriggerType(base.getTriggerType());
        merged.setStartedAt(base.getStartedAt());
        merged.setOperatorId(base.getOperatorId());
        merged.setFileName(update.getFileName());
        merged.setFilePath(update.getFilePath());
        merged.setFileSize(update.getFileSize());
        merged.setStatus(update.getStatus());
        merged.setFinishedAt(update.getFinishedAt());
        merged.setDurationMs(update.getDurationMs());
        merged.setErrorMessage(update.getErrorMessage());
        return toVO(merged);
    }

    @Override
    public int recycleStaleRunning() {
        // 容忍窗口 = 备份超时 + 60s 余量：正常情况下 RUNNING 不可能超过超时上限，
        // 超过即说明写它的进程已经不在了（发布 / 崩溃 / 机器重启）。
        long timeout = appProperties.getBackup().getTimeoutSeconds() + 60;
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(timeout);
        int recycled = backupRecordMapper.failStaleRunning(cutoff,
                "进程重启，本次备份未完成（已自动回收）");
        if (recycled > 0) {
            log.warn("回收了 {} 条残留的备份中记录（进程重启导致）", recycled);
        }
        return recycled;
    }

    /**
     * 保留清理：删除超过保留期的**成功**记录及其归档文件。
     *
     * <p>先删文件、再删记录：反过来的话，一旦删文件失败（文件被占用 / NAS 抖动），
     * 记录已经没了，那个文件就永远成了无人知晓的孤儿。
     * 顺序相反时的最坏结果是「文件删了但记录还在」，下次清理会再试一次，可自愈。
     */
    private int cleanupExpired() {
        int days = systemConfigService.backupRetentionDays();
        LocalDateTime before = LocalDateTime.now().minusDays(days);
        List<BackupRecord> expired = backupRecordMapper.selectExpiredSuccess(before);
        int removed = 0;
        for (BackupRecord item : expired) {
            try {
                if (item.getFilePath() != null && !item.getFilePath().isBlank()) {
                    Files.deleteIfExists(Path.of(item.getFilePath()));
                }
                backupRecordMapper.deleteById(item.getId());
                removed++;
            } catch (IOException e) {
                // 单个文件删不掉不中断整轮清理，也不影响本次备份的成功判定 ——
                // 留一条 warn，下次清理会重试。
                log.warn("超期备份文件删除失败，下次清理将重试：{}", item.getFilePath(), e);
            }
        }
        return removed;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    @Override
    public IPage<BackupRecordVO> page(long page, long size) {
        Page<BackupRecord> query = new Page<>(Math.max(1, page), Math.min(Math.max(1, size), 100));
        IPage<BackupRecord> result = backupRecordMapper.selectPage(query,
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<BackupRecord>()
                        .orderByDesc(BackupRecord::getStartedAt));
        return result.convert(this::toVO);
    }

    @Override
    public BackupOverviewVO overview() {
        BackupOverviewVO vo = new BackupOverviewVO();
        vo.setEnabled(systemConfigService.backupEnabled());
        vo.setBackupHour(systemConfigService.backupHour());
        vo.setRetentionDays(systemConfigService.backupRetentionDays());

        Path dir = resolveDir();
        vo.setDir(DatabaseDumper.normalize(dir));
        vo.setDirWritable(dumper.checkWritable(dir) == null);

        BackupRecord lastSuccess = backupRecordMapper.selectLastSuccess();
        if (lastSuccess != null) {
            vo.setLastSuccessAt(lastSuccess.getStartedAt());
            vo.setLastSuccessFile(lastSuccess.getFileName());
            vo.setLastSuccessSize(lastSuccess.getFileSize());
            vo.setLastSuccessSizeText(humanSize(lastSuccess.getFileSize()));
        }

        // 只有「失败晚于最后一次成功」时才呈现失败 ——
        // 否则一次早已修好的历史失败会长期挂在页面上，让「当前是否正常」变得无法判断。
        BackupRecord lastFailure = backupRecordMapper.selectLastFailure();
        if (lastFailure != null
                && (lastSuccess == null || lastFailure.getStartedAt().isAfter(lastSuccess.getStartedAt()))) {
            vo.setLastFailureAt(lastFailure.getStartedAt());
            vo.setLastFailureReason(lastFailure.getErrorMessage());
        }

        vo.setRunning(backupRecordMapper.countRunning() > 0);
        vo.setScheduledToday(backupRecordMapper
                .countScheduledSince(LocalDate.now().atStartOfDay()) > 0);
        vo.setScheduleHint(scheduleHint(vo));
        return vo;
    }

    /** 把「今天还会不会跑」翻译成一句人话 —— 避免维护人员盯着开关猜 */
    private String scheduleHint(BackupOverviewVO vo) {
        if (!vo.isEnabled()) {
            return "自动备份未启用（Docker 部署请使用 deploy/backup 备份容器，两者不要同时开启）";
        }
        if (vo.isRunning()) {
            return "正在执行备份";
        }
        if (vo.isScheduledToday()) {
            return "今天已完成自动备份";
        }
        return "每天 " + String.format("%02d", vo.getBackupHour()) + ":00 自动备份；"
                + "若该时刻应用未运行，恢复后会自动补跑一次";
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 生效目录：系统参数 backup_dir 优先，其次应用配置 app.backup.dir */
    private Path resolveDir() {
        String configured = systemConfigService.backupDir();
        String dir = (configured == null || configured.isBlank())
                ? appProperties.getBackup().getDir() : configured;
        return Path.of(dir);
    }

    /**
     * 失败原因取「最内层」的异常消息。
     *
     * <p>{@link BackupException} 的 message 是给人看的（含路径与下一步动作），
     * 而它包裹的 cause 往往是 IOException 的技术描述。取最外层即可拿到人话，
     * 但若最外层是框架包装的通用异常（如 ExecutionException），
     * 往里找第一个非空 message 才有信息量。
     */
    private String reasonOf(Throwable e) {
        Throwable current = e;
        String message = null;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message = current.getMessage();
                break;
            }
            current = current.getCause();
        }
        if (message == null) {
            message = e.getClass().getSimpleName();
        }
        return message.length() > 500 ? message.substring(0, 497) + "..." : message;
    }

    /**
     * 失败告警发给**超管**（不是全部管理员）。
     *
     * <p>与既有 {@code UserMapper#selectSuperAdminIds()} 的约定一致：
     * 运维/基础设施级告警只发超管 —— 排查备份失败需要的基础设施权限
     * （改 .env、进 NAS、看容器日志）普通业务管理员并不具备，
     * 群发只会产生「收到但处理不了」的消息噪音。
     * 「备份记录」页本身也是超管专属，两者口径一致。
     */
    private void notifyFailure(BackupTrigger trigger, String reason) {
        List<Long> receivers = userMapper.selectSuperAdminIds();
        if (receivers.isEmpty()) {
            log.warn("数据库备份失败，但系统中没有可用的超管接收人，未发出告警：{}", reason);
            return;
        }
        messageService.send(receivers, MessageType.OPS_BACKUP_ALERT, "数据库备份失败",
                ("应用内数据库自动备份执行失败（触发方式：%s）。原因：%s。"
                        + "请前往「系统设置 → 备份记录」查看详情；"
                        + "本次未产出可用归档，历史归档因「先成功后清理」的规则未被删除。")
                        .formatted(trigger.getLabel(), reason),
                null);
    }

    private BackupRecordVO toVO(BackupRecord entity) {
        BackupRecordVO vo = new BackupRecordVO();
        vo.setId(entity.getId());
        vo.setFileName(entity.getFileName());
        vo.setFileSize(entity.getFileSize());
        vo.setSizeText(humanSize(entity.getFileSize()));
        vo.setStatus(entity.getStatus());
        vo.setStatusLabel(BackupStatus.labelOf(entity.getStatus()));
        vo.setTriggerType(entity.getTriggerType());
        vo.setTriggerLabel(BackupTrigger.labelOf(entity.getTriggerType()));
        vo.setStartedAt(entity.getStartedAt());
        vo.setFinishedAt(entity.getFinishedAt());
        vo.setDurationMs(entity.getDurationMs());
        vo.setDurationText(humanDuration(entity.getDurationMs()));
        vo.setErrorMessage(entity.getErrorMessage());
        vo.setOperatorName(operatorNameOf(entity.getOperatorId()));
        return vo;
    }

    private String operatorNameOf(Long operatorId) {
        if (operatorId == null) {
            return null;
        }
        User user = userMapper.selectById(operatorId);
        return user == null ? ("用户#" + operatorId) : user.getRealName();
    }

    /** 人类可读大小；0 表示本次未产出归档，展示为 "-" 而不是 "0 B" */
    private String humanSize(Long bytes) {
        if (bytes == null || bytes <= 0) {
            return "-";
        }
        double value = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return unit == 0 ? bytes + " B"
                : String.format(Locale.ROOT, "%.2f %s", value, units[unit]);
    }

    private String humanDuration(Long millis) {
        if (millis == null || millis <= 0) {
            return "-";
        }
        Duration duration = Duration.ofMillis(millis);
        if (millis < 1000) {
            return millis + " ms";
        }
        if (duration.toMinutes() < 1) {
            return String.format(Locale.ROOT, "%.1f s", millis / 1000.0);
        }
        return duration.toMinutes() + " 分 " + (duration.toSecondsPart()) + " 秒";
    }

}
