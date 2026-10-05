package com.enterprise.ticket.module.system.job;

import com.enterprise.ticket.common.alert.AlertLevel;
import com.enterprise.ticket.common.alert.AlertService;
import com.enterprise.ticket.common.alert.ExceptionClassifier;
import com.enterprise.ticket.common.alert.ExceptionRecorder;
import com.enterprise.ticket.common.constant.MessageType;
import com.enterprise.ticket.common.job.JobLockService;
import com.enterprise.ticket.module.system.dto.vo.ExceptionDigestRow;
import com.enterprise.ticket.module.system.mapper.ExceptionLogMapper;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 异常告警的发送端—— 三个节奏，对应拍板的三级分级。
 *
 * <table>
 *   <caption>节奏与分级</caption>
 *   <tr><th>方法</th><th>触发</th><th>发什么</th></tr>
 *   <tr><td>{@link #sendImmediate()}</td><td>每 1 分钟</td><td>P0 / P1（数据库、网络、未预期）</td></tr>
 *   <tr><td>{@link #sendHourlySummary()}</td><td>整点</td><td>P2（第三方通道等）—— 静默时段内不发</td></tr>
 *   <tr><td>{@link #sendDailyReport()}</td><td>每天 09:00</td><td>夜间攒下的 P2 + 当日统计</td></tr>
 * </table>
 *
 * <h2>⚠️ 「P0 立即发」的实际语义：下一轮扫描（默认 ≤1 分钟）</h2>
 * <p>P0 <b>刻意不做同步发送</b>。若在 {@code ExceptionRecorder} 里直接发邮件，
 * 请求线程会被挂在 SMTP 握手上（网络不通时最长几十秒），
 * 结果是「为了通知一个故障，把用户的请求也拖垮」。
 * 因此改成「落库 + 下一轮扫描立即发出」，把延迟压到 1 分钟以内换请求线程的确定性。
 * 这是一个**有意识的取舍**，不是遗漏。
 *
 * <h2>为什么要分布式锁</h2>
 * <p>主备两节点都会跑同一份调度。没有锁的话同一个异常会被两台机器各发一封邮件 ——
 * 收件人看到重复告警，第一反应是「系统不稳定」，而不是「有两台机器」。
 *
 * <h2>为什么静默期在「发送端」判而不是「记录端」</h2>
 * <p>记录端必须记录**每一次**发生（次数是排查的关键证据，也是摘要里最有信息量的数字）；
 * 静默期约束的是「同一问题多久内不重复<em>告警</em>」。
 * 两者职责不同，混在一起会导致「静默期内发生的异常在日志里查不到」。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExceptionAlertJob {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 单封邮件最多列多少类异常（再多也不会有价值，只会被邮件客户端折叠） */
    private static final int MAX_DIGESTS_PER_MAIL = 30;

    /** 聚合查询的取数上限 */
    private static final int MAX_SCAN_ROWS = 200;

    private final ExceptionLogMapper exceptionLogMapper;
    private final AlertService alertService;
    private final SystemConfigService systemConfigService;
    private final JobLockService jobLockService;

    // ------------------------------------------------------------------
    // 节奏一：P0 / P1 即时
    // ------------------------------------------------------------------

    /**
     * 扫描并发送 P0 / P1 告警。
     *
     * <p>间隔由 {@code app.alert.exception-scan-interval-ms} 控制（默认 60 秒）——
     * 它必须 ≤「P1 5 分钟内」的承诺。用 {@code fixedDelay}（上一轮结束再等）
     * 天然避免重入，与 {@code HaHeartbeatMonitorJob} 同一取舍。
     */
    @Scheduled(fixedDelayString = "${app.alert.exception-scan-interval-ms:60000}")
    public void sendImmediate() {
        runLocked("exception-alert-immediate", Duration.ofMinutes(2),
                () -> send(EnumSet.of(AlertLevel.P0, AlertLevel.P1), false));
    }

    // ------------------------------------------------------------------
    // 节奏二：P2 整点汇总
    // ------------------------------------------------------------------

    /**
     * 整点汇总 P2 级异常。
     *
     * <p>静默时段（默认 22:00~08:00）内整体跳过：P2 是「提示」，半夜叫醒管理员
     * 与它的价值不成比例；这些行仍是 PENDING，由 09:00 的日报一起发出。
     */
    @Scheduled(cron = "0 0 * * * *")
    public void sendHourlySummary() {
        if (inQuietHours()) {
            log.debug("[异常告警] 处于静默时段，P2 整点汇总跳过（攒入 09:00 日报）");
            return;
        }
        runLocked("exception-alert-hourly", Duration.ofMinutes(5),
                () -> send(EnumSet.of(AlertLevel.P2), false));
    }

    // ------------------------------------------------------------------
    // 节奏三：每日日报
    // ------------------------------------------------------------------

    /**
     * 每日 09:00 日报：把夜间攒下的 P2 一次性发出，并附当日统计。
     *
     * <p>时间点固定在 09:00（而不是「静默时段一结束就发」）：上班时间收到的日报
     * 才会被真正阅读，08:00 整点发的那封会被埋进早上的收件箱。
     */
    @Scheduled(cron = "0 0 9 * * *")
    public void sendDailyReport() {
        runLocked("exception-alert-daily", Duration.ofMinutes(10), () -> {
            int sent = send(EnumSet.allOf(AlertLevel.class), true);
            if (sent > 0) {
                log.info("[异常告警] 日报已发出，覆盖 {} 类异常", sent);
            }
            return sent;
        });
    }

    // ------------------------------------------------------------------
    // 保留期清理
    // ------------------------------------------------------------------

    /**
     * 每天 03:30 清理超期异常日志。
     *
     * <p>时间点选在凌晨低峰：清理是**批量删除**，白天跑会与正常请求抢锁。
     * 保留天数由 {@code exception_log_retention_days} 控制（默认 90 天）——
     * 取证数据不该长期堆积，但太短会让「上周的事故」查不到。
     *
     * <p>分批删除（每次 {@code LIMIT}）而不是一次删光：一次性删几十万行会长时间持锁。
     * 每批 5000 行、最多 200 批（= 100 万行/天），到上限就停 ——
     * 剩下的明天继续，绝不为了「删干净」把数据库拖住。
     */
    @Scheduled(cron = "0 30 3 * * *")
    public void cleanupRetention() {
        runLocked("exception-log-cleanup", Duration.ofMinutes(20), () -> {
            int retentionDays = readInt(() -> systemConfigService.exceptionLogRetentionDays(), 90);
            LocalDateTime before = LocalDateTime.now().minusDays(Math.max(1, retentionDays));
            int deleted = 0;
            for (int round = 0; round < 200; round++) {
                int rows = exceptionLogMapper.deleteBefore(before, 5000);
                deleted += rows;
                if (rows < 5000) {
                    break;
                }
            }
            if (deleted > 0) {
                log.info("[异常日志] 已清理 {} 条超期记录（早于 {}）", deleted, before);
            }
            return deleted;
        });
    }

    // ------------------------------------------------------------------
    // 公共实现
    // ------------------------------------------------------------------

    /**
     * 发送指定级别的待告警异常。
     *
     * @param levels        本轮要处理的级别
     * @param dailyReport   是否为日报（决定标题与是否附统计）
     * @return 本轮实际告警的「类数」（0 表示无待告警项）
     */
    private int send(Set<AlertLevel> levels, boolean dailyReport) {
        try {
            List<ExceptionDigestRow> pending = exceptionLogMapper.selectPendingDigests(MAX_SCAN_ROWS);
            List<ExceptionDigestRow> due = pending.stream()
                    .filter(row -> levels.contains(AlertLevel.fromCode(row.getSeverity())))
                    .filter(this::passesRateLimit)
                    .sorted(Comparator.comparingLong((ExceptionDigestRow row) ->
                            row.getTotal() == null ? 0L : row.getTotal()).reversed())
                    .toList();
            if (due.isEmpty()) {
                return 0;
            }

            AlertLevel topLevel = due.stream()
                    .map(row -> AlertLevel.fromCode(row.getSeverity()))
                    .min(Comparator.comparingInt(Enum::ordinal))
                    .orElse(AlertLevel.P1);

            String title = buildTitle(dailyReport, topLevel, due.size());
            String body = buildBody(dailyReport, due);
            alertService.alert(topLevel, MessageType.EXCEPTION_ALERT, title, body,
                    "EXCEPTION_ALERT", "异常告警 | level=" + topLevel.code()
                            + " | digests=" + due.size() + " | daily=" + dailyReport);

            LocalDateTime now = LocalDateTime.now();
            int marked = 0;
            for (ExceptionDigestRow row : due) {
                marked += exceptionLogMapper.markDigestAlerted(row.getDigest(), ExceptionRecorder.STATE_SENT, now);
            }
            log.warn("[异常告警] 已发出（{}，{} 类 / {} 条）：{}",
                    topLevel.code(), due.size(), marked, title);
            return due.size();
        } catch (Exception e) {
            // 定时任务异常不得影响调度线程（与其它 job 同一约定）
            log.error("[异常告警] 发送失败", e);
            return 0;
        }
    }

    /**
     * 限流：同一类异常在「汇总周期」内只告警一次。
     *
     * <p>取 {@code max(汇总周期, 静默期)} 作为最小重复告警间隔 —— 两个参数都在系统参数里可调，
     * 管理员改任意一个都能收紧或放宽节奏，不会出现「改了没效果」的参数。
     *
     * <p>返回 {@code false} 时该行**仍是 PENDING**（次数继续累计），
     * 下一轮窗口过期后会被正常发出 —— 不是丢弃。
     */
    private boolean passesRateLimit(ExceptionDigestRow row) {
        LocalDateTime last = exceptionLogMapper.lastAlertedAt(row.getDigest());
        if (last == null) {
            return true;
        }
        return last.isBefore(LocalDateTime.now().minusMinutes(minReAlertMinutes()));
    }

    private long minReAlertMinutes() {
        int summary = readInt(() -> systemConfigService.exceptionAlertSummaryMinutes(), 10);
        int silence = readInt(() -> systemConfigService.exceptionAlertSilenceMinutes(), 5);
        return Math.max(1, Math.max(summary, silence));
    }

    private boolean inQuietHours() {
        String spec = readString(() -> systemConfigService.exceptionAlertQuietHours());
        return ExceptionClassifier.inQuietHours(LocalDateTime.now().toLocalTime(), spec);
    }

    private String buildTitle(boolean dailyReport, AlertLevel level, int digestCount) {
        if (dailyReport) {
            return "异常日报（" + digestCount + " 类待处理）";
        }
        return "系统异常告警：" + digestCount + " 类" + (level == AlertLevel.P0 ? "（含紧急）" : "");
    }

    /**
     * 组装邮件正文。
     *
     * <p>结构刻意是「先总后分」：管理员在手机通知栏只能看到前几行，
     * 因此第一行必须是「几类、多少条、最高级别是什么」。
     */
    private String buildBody(boolean dailyReport, List<ExceptionDigestRow> due) {
        long totalCount = due.stream().mapToLong(row -> row.getTotal() == null ? 0L : row.getTotal()).sum();
        StringBuilder sb = new StringBuilder();
        sb.append(dailyReport ? "以下为最近 24 小时内的异常汇总。" : "以下异常需要关注。").append('\n');
        sb.append("涉及 ").append(due.size()).append(" 类，共 ").append(totalCount).append(" 条。\n");

        long p0 = due.stream().filter(r -> "P0".equals(r.getSeverity())).count();
        long p1 = due.stream().filter(r -> "P1".equals(r.getSeverity())).count();
        long p2 = due.stream().filter(r -> "P2".equals(r.getSeverity())).count();
        sb.append("分级：P0 ").append(p0).append(" 类 / P1 ").append(p1).append(" 类 / P2 ").append(p2).append(" 类\n");
        sb.append("统计时间：").append(LocalDateTime.now().format(FORMATTER)).append("\n\n");

        int shown = 0;
        for (ExceptionDigestRow row : due) {
            if (shown >= MAX_DIGESTS_PER_MAIL) {
                sb.append("… 其余 ").append(due.size() - shown).append(" 类请到「系统设置 → 异常日志」查看。\n");
                break;
            }
            shown++;
            sb.append("【").append(shown).append("】").append(AlertLevel.fromCode(row.getSeverity()).label())
                    .append(" · ").append(categoryLabel(row.getCategory()))
                    .append(" · ").append(row.getTotal() == null ? 0 : row.getTotal()).append(" 次\n");
            sb.append("    异常：").append(nullToDash(row.getExceptionClass())).append('\n');
            if (row.getMessage() != null && !row.getMessage().isBlank()) {
                sb.append("    消息：").append(ExceptionClassifier.truncate(row.getMessage(), 200)).append('\n');
            }
            if (row.getRequestUri() != null && !row.getRequestUri().isBlank()) {
                sb.append("    接口：").append(row.getRequestUri()).append('\n');
            }
            sb.append("    首次：").append(format(row.getFirstAt()))
                    .append("　最近：").append(format(row.getLastAt())).append('\n');
            sb.append("    处理：到「系统设置 → 异常日志」按分类与时间检索完整堆栈。\n\n");
        }
        return sb.toString();
    }

    private String categoryLabel(String categoryName) {
        return com.enterprise.ticket.common.alert.ExceptionCategory.fromName(categoryName).label();
    }

    private String format(LocalDateTime time) {
        return time == null ? "-" : time.format(FORMATTER);
    }

    private String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    /** 在分布式锁保护下执行；未抢到锁的实例直接跳过（返回 0） */
    private void runLocked(String jobName, Duration ttl, Supplier<Integer> action) {
        jobLockService.runLocked(jobName, ttl, action, () -> 0);
    }

    private int readInt(Supplier<Integer> supplier, int fallback) {
        try {
            Integer value = supplier.get();
            return value == null ? fallback : value;
        } catch (Exception e) {
            log.warn("[异常告警] 读取配置失败，用缺省值 {}：{}", fallback, e.getMessage());
            return fallback;
        }
    }

    private String readString(Supplier<String> supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            log.warn("[异常告警] 读取配置失败：{}", e.getMessage());
            return null;
        }
    }
}
