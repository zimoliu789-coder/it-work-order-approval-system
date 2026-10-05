package com.enterprise.ticket.module.security.support;

/**
 * 安全防护的配置键与默认值。
 *
 * <p>与 {@code ExceptionAlertSettings} 同一姿势：键名是**跨三处**的契约 ——
 * 本类、{@code SystemConfigCatalog}（页面渲染 + 恢复默认）、Flyway 迁移的初值必须逐字一致。
 *
 * <p>⚠️ {@code login_fail_max_count} / {@code login_lock_minutes} 是**既有**键（V12 起），
 * 不属于本类 —— 账号锁定的实现早就在 {@code LoginProtectionService} 里了（见 V44 的说明）。
 * 其中 {@code login_lock_minutes} 的默认值已在 P2 安全修复中由 15 收紧为 30（见 V46 迁移）。
 */
public final class SecuritySettings {

    private SecuritySettings() {
    }

    /** IP 封禁总开关。默认开启：这是「装好就该生效」的防护。 */
    public static final String KEY_IP_BLOCK_ENABLED = "security_ip_block_enabled";
    public static final boolean DEFAULT_IP_BLOCK_ENABLED = true;

    /**
     * 一级封禁阈值：同一 IP 累计失败多少次后**短时封禁**。
     * 默认比账号阈值宽 —— 一个 IP 后面往往是整个办公室。
     */
    public static final String KEY_IP_BLOCK_MAX_COUNT = "security_ip_block_max_count";
    public static final int DEFAULT_IP_BLOCK_MAX_COUNT = 10;

    /** 一级封禁时长（分钟），到期自动解封。 */
    public static final String KEY_IP_BLOCK_MINUTES = "security_ip_block_minutes";
    public static final int DEFAULT_IP_BLOCK_MINUTES = 30;

    /**
     * 二级（长封）阈值：同一 IP 累计失败达到该值时**长时封禁**（P2 安全修复）。
     *
     * <p>一级只封 30 分钟，对「持续撞库」压制不足（攻击者等 30 分钟即可续打）。
     * 因此对同一窗口内累计失败更多的来源追加一档 24 小时长封。
     */
    public static final String KEY_IP_BLOCK_LONG_MAX_COUNT = "security_ip_block_long_max_count";
    public static final int DEFAULT_IP_BLOCK_LONG_MAX_COUNT = 20;

    /** 二级（长封）封禁时长（分钟），默认 24 小时 = 1440。 */
    public static final String KEY_IP_BLOCK_LONG_MINUTES = "security_ip_block_long_minutes";
    public static final int DEFAULT_IP_BLOCK_LONG_MINUTES = 1440;

    /**
     * IP 白名单（逗号分隔，支持网段）。白名单内永不封禁。
     *
     * <p><b>默认预置内网网段</b>（P2 安全修复）：办公网出口 IP 常被整层楼共用，
     * 一人输错几次口令就会把整片人挡在门外。默认放行回环与 RFC1918 私网段，
     * 管理员仍可在参数页改。
     */
    public static final String KEY_IP_WHITELIST = "security_ip_whitelist";
    public static final String DEFAULT_IP_WHITELIST =
            "127.0.0.0/8,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16";

    /** 安全事件保留天数。 */
    public static final String KEY_EVENT_RETENTION_DAYS = "security_event_retention_days";
    public static final int DEFAULT_EVENT_RETENTION_DAYS = 90;

    /**
     * 异常登录检测总开关（P2 安全修复）。
     *
     * <p>开启后，登录成功时若命中「凌晨 0–6 点登录 / 新设备登录 / 非常用 IP 登录」，
     * 会记录一条 {@code LOGIN_ANOMALY} 安全事件并通知用户本人（站内 + 邮件）；
     * 管理员账号凌晨登录会额外告警超管。
     */
    public static final String KEY_LOGIN_ANOMALY_ENABLED = "security_login_anomaly_enabled";
    public static final boolean DEFAULT_LOGIN_ANOMALY_ENABLED = true;

    /**
     * IP 失败计数的窗口（分钟）。
     *
     * <p>取「封禁时长的 2 倍、但不小于 10 分钟、不大于 60 分钟」：
     * 窗口必须**明显大于**封禁时长，否则封禁期内累计的失败会立刻触发下一轮封禁，
     * 让「30 分钟自动解封」变成一个永远不到期的承诺。
     *
     * <p>二级长封（默认 1440 分钟）也复用本窗口来累计失败计数 ——
     * 因此「24 小时内累计 20 次失败」实际是按**最近 1 小时窗口**内的累计来判定的：
     * 这比「跨 24 小时慢慢凑够 20 次」更严格（更早长封），对持续攻击的压制更强。
     */
    public static int ipFailWindowMinutes(int blockMinutes) {
        int safe = Math.max(1, blockMinutes);
        return Math.min(60, Math.max(10, safe * 2));
    }
}
