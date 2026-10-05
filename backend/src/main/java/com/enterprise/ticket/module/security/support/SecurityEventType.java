package com.enterprise.ticket.module.security.support;

/**
 * 安全事件类型。枚举名**落库**（{@code security_event.event_type}）并出现在
 * 页面筛选里，因此重命名等同于改数据契约。
 */
public enum SecurityEventType {

    /** 登录失败（口令错误 / 账号不存在）。这是最频繁的一类，也是攻击的主要信号。 */
    LOGIN_FAIL("登录失败"),

    /** 账号因连续失败被锁定（由既有 {@code LoginProtectionService} 触发）。 */
    ACCOUNT_LOCKED("账号锁定"),

    /** IP 被自动封禁（同一 IP 累计失败达阈值）。 */
    IP_BLOCKED("IP 封禁"),

    /** IP 封禁被解除（自动过期或人工解封）。 */
    IP_UNBLOCKED("IP 解封"),

    /** 越权 / 提权尝试（访问了无权访问的资源）。 */
    PERM_ESCALATION_ATTEMPT("越权尝试"),

    /**
     * 异常登录（P2 安全修复）。
     *
     * <p>登录<b>成功</b>但命中以下任一情形时记录：凌晨 0–6 点登录、新设备（首次出现的
     * User-Agent）、非常用 IP（该账号首次出现的来源 IP）。它不是「失败」，而是
     * 「账号可能已被他人使用」的信号 —— 因此既落库也通知（本人邮件 + 管理员账号告警超管）。
     */
    LOGIN_ANOMALY("异常登录");

    private final String label;

    SecurityEventType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 枚举名 → 类型；未知值回落 {@link #LOGIN_FAIL}（不返回 null，避免调用方 NPE） */
    public static SecurityEventType fromName(String name) {
        if (name == null) {
            return LOGIN_FAIL;
        }
        for (SecurityEventType type : values()) {
            if (type.name().equalsIgnoreCase(name.trim())) {
                return type;
            }
        }
        return LOGIN_FAIL;
    }
}
