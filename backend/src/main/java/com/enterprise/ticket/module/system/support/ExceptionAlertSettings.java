package com.enterprise.ticket.module.system.support;

/**
 * 异常告警的配置键与默认值。
 *
 * <p>与 {@code ContactRecovery} / {@code MailSettings} 同一姿势：键名是**跨三处**的契约 ——
 * 本类、{@code SystemConfigCatalog}（页面渲染 + 恢复默认）、Flyway 迁移脚本的初值
 * 必须逐字一致。三处任一写错都不会编译报错，只会让「恢复默认」回填一个不存在的键，
 * 或让页面显示一个与库里不同的默认值。
 *
 * <p>因此默认值只在**本类**定义一份，目录与迁移都引用它（迁移是 SQL、无法引用，
 * 因此迁移里的字面量必须与这里逐字对齐 —— 交付文档里列了对照表）。
 */
public final class ExceptionAlertSettings {

    private ExceptionAlertSettings() {
    }

    /** 总开关。默认**开启**：异常告警是「装好就该生效」的基础设施能力。 */
    public static final String KEY_ENABLED = "exception_alert_enabled";
    public static final boolean DEFAULT_ENABLED = true;

    /** 同类异常静默期（分钟）：期内重复出现只计数、不重复告警。 */
    public static final String KEY_SILENCE_MINUTES = "exception_alert_silence_minutes";
    public static final int DEFAULT_SILENCE_MINUTES = 5;

    /** 汇总周期（分钟）：待告警异常合并成一封的节奏。 */
    public static final String KEY_SUMMARY_MINUTES = "exception_alert_summary_minutes";
    public static final int DEFAULT_SUMMARY_MINUTES = 10;

    /** 额外收件人（邮箱，英文逗号分隔）。默认只发全部超管。 */
    public static final String KEY_EXTRA_RECIPIENTS = "exception_alert_extra_recipients";
    public static final String DEFAULT_EXTRA_RECIPIENTS = "";

    /** 不告警的分类（逗号分隔的 {@code ExceptionCategory} 枚举名）。 */
    public static final String KEY_IGNORE_CATEGORIES = "exception_alert_ignore_categories";
    public static final String DEFAULT_IGNORE_CATEGORIES = "";

    /** 静默时段（{@code HH:mm-HH:mm}）。P2 在该时段内不即时发、攒日报。 */
    public static final String KEY_QUIET_HOURS = "exception_alert_quiet_hours";
    public static final String DEFAULT_QUIET_HOURS = "22:00-08:00";

    /** 异常日志保留天数（超期由每日清理任务删除）。 */
    public static final String KEY_RETENTION_DAYS = "exception_log_retention_days";
    public static final int DEFAULT_RETENTION_DAYS = 90;

    /**
     * 汇总任务的扫描间隔（毫秒）。
     *
     * <p>取「汇总周期的一半、但不小于 1 分钟」，让用户改汇总周期时不必再改一个隐藏的调度值；
     * 上限 5 分钟 —— 再长就会让「P0 立即发」变成「最多等 5 分钟才发」，
     * 与分级的承诺不符。调度本身用 {@code fixedDelay}（上一轮结束再等），
     * 天然避免重入。
     */
    public static long scanIntervalMs(int summaryMinutes) {
        int safe = Math.max(1, summaryMinutes);
        long half = (long) safe * 60_000L / 2L;
        return Math.min(300_000L, Math.max(60_000L, half));
    }
}
