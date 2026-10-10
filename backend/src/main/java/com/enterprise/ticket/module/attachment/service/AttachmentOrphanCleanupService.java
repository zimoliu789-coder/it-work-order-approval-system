package com.enterprise.ticket.module.attachment.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ticket.common.job.JobLockService;
import com.enterprise.ticket.module.attachment.entity.Attachment;
import com.enterprise.ticket.module.attachment.mapper.AttachmentMapper;
import com.enterprise.ticket.module.attachment.support.AttachmentStorage;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/**
 * 附件孤儿文件清理（需求方三波·第三波·）
 *
 * <h2>什么是「孤儿文件」</h2>
 * <p>磁盘上有文件、但 {@code attachment} 表里没有任何一行引用它。常见成因：
 * <ul>
 *   <li>「先落盘再插库」两步之间进程崩溃 / 数据库连接中断 —— 文件已写入，记录没建成；</li>
 *   <li>运维手工在存储目录里放文件、或从备份恢复时多带出来的残留；</li>
 *   <li>逻辑删除（软删）时磁盘删除失败，之后记录不再可见，文件却留在盘上。</li>
 * </ul>
 * 这些文件不会再被任何业务访问，却持续占用 NAS 空间。
 *
 * <h2>为什么不删「软删记录对应的文件」以外的所有孤儿</h2>
 * <p>判定标准只有一条：<b>当前可见的（未软删）附件记录里是否引用了这个相对路径</b>。
 * 软删记录指向的文件在删除当下已被物理删除；若仍有残留，说明删除失败，
 * 属于孤儿，理应回收。于是「引用集合 = 未软删记录的 stored_path」正好覆盖这两种情形。
 *
 * <h2>为什么必须有「宽限期」</h2>
 * <p>落盘与插库之间存在时间窗。若任务恰好在窗口内运行，会把一个<b>即将被引用</b>的文件
 * 当成孤儿删掉 —— 用户上传成功却下载 404。因此只清理「最后修改时间早于
 * {@code now - grace_hours}」的文件，宽限期默认 24 小时（配置项
 * {@code attachment_orphan_grace_hours}），远大于任何一次上传的处理耗时。
 *
 * <h2>幂等与安全</h2>
 * <ul>
 *   <li>分布式锁（{@link JobLockService}）避免多实例同时扫描；</li>
 *   <li>单次删除数量封顶，避免首次上线把历史残留一次性删爆磁盘 IO；</li>
 *   <li>删除前再次确认路径在存储根之内（{@link AttachmentStorage#resolve} 的越界断言），
 *       即便相对路径被人工改库污染也不会越界删到系统文件。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentOrphanCleanupService {

    /** 宽限期配置键（小时） */
    private static final String GRACE_KEY = "attachment_orphan_grace_hours";

    /** 默认宽限期：24 小时 */
    private static final int DEFAULT_GRACE_HOURS = 24;

    /** 单次最多删除的文件数：留出余量，避免一次性大删拖垮磁盘 */
    private static final int MAX_DELETE_PER_RUN = 500;

    private static final String JOB_NAME = "attachmentOrphanCleanup";
    private static final Duration LOCK_TTL = Duration.ofMinutes(50);

    private final AttachmentMapper attachmentMapper;
    private final AttachmentStorage storage;
    private final JobLockService jobLockService;
    private final SystemConfigService systemConfigService;

    /**
     * 在分布式锁保护下清理孤儿文件
     *
     * @return 本次实际删除的文件数；未获取到锁时返回 0
     */
    public int cleanup() {
        return jobLockService.runLocked(JOB_NAME, LOCK_TTL, this::doCleanup, () -> 0);
    }

    /** 任务体（供单测直接调用，绕过分布式锁） */
    int doCleanup() {
        int graceHours = Math.max(systemConfigService.getInt(GRACE_KEY, DEFAULT_GRACE_HOURS), 1);
        Set<String> referenced = referencedPaths();
        long cutoffMillis = System.currentTimeMillis() - graceHours * 3600_000L;

        int deleted = 0;
        int capped = 0;
        for (Path file : storage.listFiles()) {
            String relative = storage.relativeOf(file);
            if (referenced.contains(relative)) {
                continue;
            }
            if (!isOlderThan(file, cutoffMillis)) {
                continue;
            }
            if (deleted >= MAX_DELETE_PER_RUN) {
                capped++;
                continue;
            }
            try {
                // 再解析一次以复用越界断言：path 来自 walk，理论上安全，此处为纵深防御
                Files.deleteIfExists(storage.resolve(relative));
                deleted++;
                log.warn("附件孤儿文件已清理：{}", relative);
            } catch (IOException e) {
                log.warn("附件孤儿文件删除失败：relative={} err={}", relative, e.getMessage());
            }
        }

        if (deleted > 0 || capped > 0) {
            log.info("附件孤儿清理完成：删除 {} 个，因单次上限跳过 {} 个，宽限期 {} 小时",
                    deleted, capped, graceHours);
        }
        return deleted;
    }

    /** 引用集合：当前未软删的全部附件相对路径（逻辑删除由 MyBatis-Plus 自动过滤） */
    private Set<String> referencedPaths() {
        Set<String> paths = new HashSet<>();
        for (Attachment attachment : attachmentMapper.selectList(
                Wrappers.<Attachment>lambdaQuery().select(Attachment::getStoredPath))) {
            if (attachment.getStoredPath() != null) {
                paths.add(attachment.getStoredPath());
            }
        }
        return paths;
    }

    /** 文件最后修改时间是否早于截止时刻；取不到时间时保守返回 false（不删） */
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
