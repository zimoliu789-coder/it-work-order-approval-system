package com.enterprise.ticket.module.attachment.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ticket.common.constant.AttachmentBizType;
import com.enterprise.ticket.common.job.JobLockService;
import com.enterprise.ticket.common.log.RiskLevel;
import com.enterprise.ticket.module.attachment.entity.Attachment;
import com.enterprise.ticket.module.attachment.mapper.AttachmentMapper;
import com.enterprise.ticket.module.attachment.support.AttachmentStorage;
import com.enterprise.ticket.module.device.mapper.DeviceFaultMapper;
import com.enterprise.ticket.module.log.service.OperationLogService;
import com.enterprise.ticket.module.order.mapper.OrderMapper;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 附件保留期清理（；「已删工单附件保留」）
 *
 * <h2>清理两类「已删除的附件」</h2>
 * <ol>
 *   <li><b>记录已被删除</b>（{@code deleted = 1}）：用户或管理员把附件删掉了。
 *       删除当下磁盘文件已尽力删除，但 DB 行仍保留 —— 它是「误删恢复」的唯一线索
 *       （运维还能从 {@code stored_path} 找到恢复位置）。超过保留期后连行一起彻底清掉。</li>
 *   <li><b>主体已不存在</b>（记录未删，但它挂靠的工单 / 故障记录已不在业务表里）：
 *       这类附件永远不会有人再看到，却持续占用 NAS 空间。</li>
 * </ol>
 *
 * <h2>为什么必须记录「删除时刻」而不是用上传时间（本次加了 deleted_at）</h2>
 * <p>保留期的承诺是「<b>删除后</b>还能恢复 N 天」。若拿 {@code created_at}（上传时间）当基准，
 * 一个半年前上传、昨天才被删除的附件会在当天夜里被立刻物理删除 ——
 * 用户看到的是「我刚删的东西怎么一点回收机会都没有」。因此基准时刻取
 * {@link Attachment#getDeletedAt}，历史数据由 V29 用 {@code created_at} 兜底。
 *
 * <h2>先 dry-run 出清单、再落审计、最后才删</h2>
 * <p>物理删除磁盘文件<b>不可逆</b>。因此本服务把「删」拆成三步：
 * <ol>
 *   <li>先只<b>读</b>：算出完整清单（数量 + 路径前缀），不碰任何文件；</li>
 *   <li>把清单写进审计（{@link RiskLevel#HIGH} ⇒ <b>同步落库</b>）。审计写不成就抛异常中断，
 *       本轮一个文件都不删 —— 宁可这次不清理，也不要出现「文件没了、日志里也没有」的不可追溯删除；</li>
 *   <li>逐条「先删盘、再物理删行」。顺序刻意如此：若先删行后删盘，一旦删盘失败，
 *       文件就成了无人知晓的孤儿（记录已不存在，谁也定位不到它）。</li>
 * </ol>
 *
 * <h2>幂等与安全</h2>
 * <ul>
 *   <li>分布式锁（{@link JobLockService}）避免多实例同时扫描；</li>
 *   <li>单次删除数量封顶，避免首次上线一次性删爆磁盘 IO；</li>
 *   <li>删盘走 {@link AttachmentStorage#deleteQuietly} ⇒ 复用「路径必须在存储根之内」的越界断言，
 *       即便 {@code stored_path} 被人工改库污染也不会删到系统文件；</li>
 *   <li>未知 {@code biz_type} 一律<b>不删</b>：新增业务类型后，旧代码不该替它做删除决定。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentRetentionCleanupService {

    /** 单次最多清理的附件数：留出余量，避免一次性大删拖垮磁盘 */
    private static final int MAX_PER_RUN = 500;

    /** 审计详情上限（与 AuditLogAspect 的 2000 保持同一量级，宁短勿长） */
    private static final int MAX_DETAIL_LENGTH = 1000;

    /** 清单里最多列出的路径前缀段数，超出只报数量 */
    private static final int MAX_PREFIX_SAMPLES = 5;

    /** 定时任务名：与分布式锁 key、手动触发接口的 job 参数一致 */
    private static final String JOB_NAME = "attachmentRetention";

    private static final Duration LOCK_TTL = Duration.ofMinutes(30);

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AttachmentMapper attachmentMapper;
    private final AttachmentStorage storage;
    private final OrderMapper orderMapper;
    private final DeviceFaultMapper faultMapper;
    private final JobLockService jobLockService;
    private final SystemConfigService systemConfigService;
    private final OperationLogService operationLogService;

    /**
     * 在分布式锁保护下清理超期附件
     *
     * @return 本次实际清理的附件数；未获取到锁时返回 0
     */
    public int cleanup() {
        return jobLockService.runLocked(JOB_NAME, LOCK_TTL, this::doCleanup, () -> 0);
    }

    /** 任务体（供单测直接调用，绕过分布式锁） */
    int doCleanup() {
        int retentionDays = systemConfigService.attachmentRetentionDays();
        LocalDateTime deadline = LocalDateTime.now().minusDays(retentionDays);

        // 第一步：只读，算出清单（不碰任何文件）
        List<Attachment> softDeleted = attachmentMapper.selectSoftDeletedBefore(deadline, MAX_PER_RUN);
        List<Attachment> orphanBiz = findOrphanBizBefore(deadline, MAX_PER_RUN - softDeleted.size());
        List<Attachment> targets = new ArrayList<>(softDeleted);
        targets.addAll(orphanBiz);
        if (targets.isEmpty()) {
            return 0;
        }

        // 第二步：把清单写进审计（同步）。写不成则抛异常 ⇒ 本轮不删任何东西
        recordPlan(targets, softDeleted.size(), retentionDays, deadline);

        // 第三步：逐条「先删盘、再物理删行」
        int deleted = 0;
        int failed = 0;
        for (Attachment target : targets) {
            try {
                storage.deleteQuietly(target.getStoredPath());
                attachmentMapper.hardDeleteById(target.getId());
                deleted++;
            } catch (Exception e) {
                // 单条失败不影响其余：下一轮调度会重新把它算进清单（幂等）
                failed++;
                log.warn("附件保留期清理失败：id={} path={} err={}",
                        target.getId(), target.getStoredPath(), e.getMessage());
            }
        }
        log.info("附件保留期清理完成：保留期 {} 天，清理 {} 个（已删记录 {} / 主体不存在 {}），失败 {} 个",
                retentionDays, deleted, softDeleted.size(), orphanBiz.size(), failed);
        return deleted;
    }

    // ------------------------------------------------------------------
    // 清单收集
    // ------------------------------------------------------------------

    /**
     * 「主体已不存在」的候选（未软删、且早于保留期）
     *
     * <p>用 {@code created_at} 判定：附件记录本身没被删，所以基准时刻只能是它的创建时间 ——
     * 「这个附件挂靠的工单早就不在了」这件事没有单独的时间戳；用创建时间会偏保守
     * （只会更晚被清理，不会误删新数据）。
     */
    private List<Attachment> findOrphanBizBefore(LocalDateTime deadline, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<Attachment> candidates = attachmentMapper.selectList(Wrappers.<Attachment>lambdaQuery()
                .lt(Attachment::getCreatedAt, deadline)
                .orderByAsc(Attachment::getId)
                .last("LIMIT " + limit));

        List<Attachment> orphans = new ArrayList<>();
        for (Attachment candidate : candidates) {
            if (bizMissing(candidate)) {
                orphans.add(candidate);
            }
        }
        return orphans;
    }

    /** 附件挂靠的业务主体是否已不存在；无法判定时一律返回 false（保守不删） */
    private boolean bizMissing(Attachment attachment) {
        if (attachment.getBizId() == null) {
            return false;
        }
        AttachmentBizType type = AttachmentBizType.of(attachment.getBizType());
        if (type == null) {
            // 未知业务类型（未来新增但当前代码不认识）：不做删除决定，留给能识别它的版本
            return false;
        }
        return switch (type.getTarget()) {
            case ORDER -> orderMapper.selectById(attachment.getBizId()) == null;
            case FAULT -> faultMapper.selectById(attachment.getBizId()) == null;
        };
    }

    // ------------------------------------------------------------------
    // 审计
    // ------------------------------------------------------------------

    /**
     * 把清理清单写入审计（HIGH ⇒ 同步落库，失败即抛 ⇒ 调用方中止本轮删除）
     *
     * <p>操作人记为「系统」：本任务无人触发，{@code operator_id} 留空由前端渲染成系统账号。
     */
    private void recordPlan(List<Attachment> targets, int softDeletedCount,
                            int retentionDays, LocalDateTime deadline) {
        int orphanCount = targets.size() - softDeletedCount;
        String details = "保留期 " + retentionDays + " 天（截止 " + deadline.format(STAMP) + "）"
                + "；清理清单 " + targets.size() + " 个"
                + "（已删记录 " + softDeletedCount + " + 主体不存在 " + orphanCount + "）"
                + "；路径前缀 " + prefixSummary(targets);
        if (details.length() > MAX_DETAIL_LENGTH) {
            details = details.substring(0, MAX_DETAIL_LENGTH);
        }
        operationLogService.record(null, "系统（附件保留期清理）", "ATTACHMENT",
                "ATTACHMENT_RETENTION_CLEANUP", details, true, RiskLevel.HIGH);
    }

    /**
     * 清单里涉及的去重路径前缀（{@code yyyy/MM} 形式）
     *
     * <p>为什么记前缀而不是逐个文件：清单可能有几百条，逐条写会把审计撑爆；
     * 而「路径前缀」已经足够回答事后追溯的关键问题 ——「删掉的是哪一批、哪个目录下的」。
     */
    private String prefixSummary(List<Attachment> targets) {
        Set<String> prefixes = new LinkedHashSet<>();
        for (Attachment target : targets) {
            prefixes.add(prefixOf(target.getStoredPath()));
        }
        List<String> samples = prefixes.stream().limit(MAX_PREFIX_SAMPLES).toList();
        String joined = String.join("、", samples);
        return prefixes.size() > samples.size()
                ? joined + " 等 " + prefixes.size() + " 段"
                : joined;
    }

    /** 取相对路径的前两级目录（{@code 2026/09/<uuid>.png} → {@code 2026/09}） */
    private String prefixOf(String storedPath) {
        if (storedPath == null || storedPath.isBlank()) {
            return "(无路径)";
        }
        String[] parts = storedPath.split("/");
        if (parts.length <= 2) {
            return storedPath;
        }
        return parts[0] + "/" + parts[1];
    }
}
