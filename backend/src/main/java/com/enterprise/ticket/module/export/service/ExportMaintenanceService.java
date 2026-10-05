package com.enterprise.ticket.module.export.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ticket.common.constant.ExportStatus;
import com.enterprise.ticket.common.job.JobLockService;
import com.enterprise.ticket.module.export.entity.ExportTask;
import com.enterprise.ticket.module.export.mapper.ExportTaskMapper;
import com.enterprise.ticket.module.export.support.ExportStorage;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 导出文件的过期清理与僵尸任务回收（需求方三波·第三波·）
 *
 * <h2>三件事</h2>
 * <ol>
 *   <li><b>过期文件清理</b>：{@code SUCCESS} 且 {@code expire_at < now} 的任务，
 *       删除磁盘文件并把 {@code stored_path} 置空。记录行保留（历史可查），
 *       下载接口在检查文件之前先检查过期时间，因此用户仍会得到明确的
 *       {@code EXPORT_EXPIRED}，而不是含混的「文件不存在」。</li>
 *   <li><b>僵尸任务回收</b>：长期停留在 {@code PENDING} / {@code RUNNING} 的任务置为
 *       {@code FAILED}（见 {@link ExportTaskMapper#failZombies}）。</li>
 *   <li><b>孤儿文件清理</b>：磁盘上有、导出记录里没有任何行引用的文件（例如记录已删但文件删除
 *       失败），带宽限期回收。</li>
 * </ol>
 *
 * <h2>为什么导出文件需要主动清理，而附件不需要按时间清</h2>
 * <p>导出文件是<b>系统生成的临时产物</b>（可随时按条件重新导出），有明确过期时间；
 * 附件是用户上传的原始资料，需要长期保留。二者的根目录也因此在配置上分开，
 * 避免「按过期时间清理」误伤附件。
 *
 * <h2>宽限期为什么同样必要（针对孤儿文件）</h2>
 * <p>导出生成的顺序是「先占位任务行 → 生成文件 → 回填 stored_path」。在文件已写、路径尚未回填的
 * 窗口内，该文件暂时「无引用」。若清理任务恰好在窗口内扫描，会把这个即将被引用的文件删掉 ——
 * 表现为「消息说导出完成，点下载却 404」。因此孤儿清理同样只处理「足够旧」的文件。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportMaintenanceService {

    /** 僵尸任务判定阈值（分钟）：PENDING / RUNNING 超过该时长未完成即回收 */
    private static final String ZOMBIE_KEY = "export_zombie_timeout_minutes";
    private static final int DEFAULT_ZOMBIE_MINUTES = 30;

    /** 孤儿文件宽限期（小时） */
    private static final String ORPHAN_GRACE_KEY = "export_orphan_grace_hours";
    private static final int DEFAULT_ORPHAN_GRACE_HOURS = 12;

    /** 每轮各类处理的条数上限，避免一次大删拖垮磁盘 / 长事务 */
    private static final int MAX_PER_RUN = 500;

    private static final String JOB_NAME = "exportMaintenance";
    private static final Duration LOCK_TTL = Duration.ofMinutes(50);

    private final ExportTaskMapper taskMapper;
    private final ExportStorage storage;
    private final JobLockService jobLockService;
    private final SystemConfigService systemConfigService;

    /**
     * 在分布式锁保护下执行清理与回收
     *
     * @return 本轮处理的总条数（过期文件 + 僵尸任务 + 孤儿文件）
     */
    public int cleanup() {
        return jobLockService.runLocked(JOB_NAME, LOCK_TTL, this::doCleanup, () -> 0);
    }

    /** 任务体（供单测直接调用） */
    int doCleanup() {
        int expired = cleanExpiredFiles();
        int zombies = reclaimZombies();
        int orphans = cleanOrphanFiles();
        int total = expired + zombies + orphans;
        if (total > 0) {
            log.info("导出维护完成：清理过期文件 {} 个，回收僵尸任务 {} 个，清理孤儿文件 {} 个",
                    expired, zombies, orphans);
        }
        return total;
    }

    // ------------------------------------------------------------------
    // 一、过期文件清理
    // ------------------------------------------------------------------

    private int cleanExpiredFiles() {
        List<ExportTask> expired = taskMapper.selectList(Wrappers.<ExportTask>lambdaQuery()
                .eq(ExportTask::getStatus, ExportStatus.SUCCESS.name())
                .isNotNull(ExportTask::getStoredPath)
                .isNotNull(ExportTask::getExpireAt)
                .lt(ExportTask::getExpireAt, LocalDateTime.now())
                .last("LIMIT " + MAX_PER_RUN));

        int removed = 0;
        for (ExportTask task : expired) {
            storage.deleteQuietly(task.getStoredPath());
            // 置空 stored_path：如实反映「文件已不在」，并让孤儿清理不再重复处理它
            taskMapper.update(null, Wrappers.<ExportTask>lambdaUpdate()
                    .eq(ExportTask::getId, task.getId())
                    .set(ExportTask::getStoredPath, null));
            removed++;
            log.debug("导出过期文件已清理：taskId={} path={}", task.getId(), task.getStoredPath());
        }
        return removed;
    }

    // ------------------------------------------------------------------
    // 二、僵尸任务回收
    // ------------------------------------------------------------------

    private int reclaimZombies() {
        int minutes = Math.max(systemConfigService.getInt(ZOMBIE_KEY, DEFAULT_ZOMBIE_MINUTES), 5);
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(minutes);
        int affected = taskMapper.failZombies(cutoff, "后台生成超时（进程可能已重启），请重新发起导出");
        if (affected > 0) {
            log.warn("回收僵尸导出任务 {} 个（阈值 {} 分钟）", affected, minutes);
        }
        return affected;
    }

    // ------------------------------------------------------------------
    // 三、孤儿文件清理
    // ------------------------------------------------------------------

    private int cleanOrphanFiles() {
        Set<String> referenced = referencedPaths();
        int graceHours = Math.max(systemConfigService.getInt(ORPHAN_GRACE_KEY, DEFAULT_ORPHAN_GRACE_HOURS), 1);
        long cutoffMillis = System.currentTimeMillis() - graceHours * 3600_000L;

        int deleted = 0;
        for (Path file : storage.listFiles()) {
            String relative = storage.relativeOf(file);
            if (referenced.contains(relative)) {
                continue;
            }
            if (!isOlderThan(file, cutoffMillis)) {
                continue;
            }
            if (deleted >= MAX_PER_RUN) {
                break;
            }
            try {
                Files.deleteIfExists(storage.resolve(relative));
                deleted++;
                log.warn("导出孤儿文件已清理：{}", relative);
            } catch (IOException e) {
                log.warn("导出孤儿文件删除失败：relative={} err={}", relative, e.getMessage());
            }
        }
        return deleted;
    }

    /** 引用集合：导出任务表里全部非空 stored_path（导出任务表无逻辑删除，全量即全部） */
    private Set<String> referencedPaths() {
        Set<String> paths = new HashSet<>();
        for (ExportTask task : taskMapper.selectList(
                Wrappers.<ExportTask>lambdaQuery().select(ExportTask::getStoredPath))) {
            if (task.getStoredPath() != null) {
                paths.add(task.getStoredPath());
            }
        }
        return paths;
    }

    private boolean isOlderThan(Path file, long cutoffMillis) {
        try {
            FileTime lastModified = Files.getLastModifiedTime(file);
            return lastModified.toMillis() < cutoffMillis;
        } catch (IOException e) {
            log.warn("读取文件修改时间失败，跳过：file={} err={}", file, e.getMessage());
            return false;
        }
    }
}
