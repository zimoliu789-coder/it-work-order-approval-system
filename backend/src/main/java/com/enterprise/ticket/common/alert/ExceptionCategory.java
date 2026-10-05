package com.enterprise.ticket.common.alert;

/**
 * 异常分类。
 *
 * <p>分类的用途有三个，都必须稳定：
 * <ol>
 *   <li><b>推导告警等级</b>（见 {@link ExceptionClassifier}）—— 数据库故障与邮件发不出去
 *       显然不该用同一个节奏打扰管理员；</li>
 *   <li><b>界面筛选</b>——「异常日志」页按分类过滤是排查的第一步；</li>
 *   <li><b>整体忽略</b>——管理员可以用 {@code exception_alert_ignore_categories}
 *       把某一类整体静音（例如测试环境的 THIRD_PARTY）。</li>
 * </ol>
 *
 * <p>枚举名会**落库**（{@code exception_log.category}）并出现在系统参数里，
 * 因此重命名枚举常量等同于改数据契约 —— 迁移脚本里必须同步。
 */
public enum ExceptionCategory {

    /** 数据层：SQL 异常、连接池耗尽、唯一键冲突、数据完整性。 */
    DATABASE("数据库"),

    /** 网络：连接被拒、超时、DNS 解析失败。 */
    NETWORK("网络"),

    /** 第三方通道：邮件（SMTP）、短信、外部接口。 */
    THIRD_PARTY("第三方"),

    /** 参数 / 请求格式：本应被校验拦下却漏到兜底处理器的那些。 */
    PARAM("参数"),

    /** 业务规则：{@code BusinessException} 的兜底归类（正常路径由专用处理器接走，不落库）。 */
    BUSINESS("业务"),

    /** 未知：无法归类的一切 —— 也是最值得看的那些。 */
    UNKNOWN("未知");

    private final String label;

    ExceptionCategory(String label) {
        this.label = label;
    }

    /** 中文标签（页面展示；落库存的是枚举名，展示时才翻译） */
    public String label() {
        return label;
    }

    /** 枚举名 → 分类；未知值回落 {@link #UNKNOWN}（不返回 null，避免调用方 NPE） */
    public static ExceptionCategory fromName(String name) {
        if (name == null) {
            return UNKNOWN;
        }
        for (ExceptionCategory category : values()) {
            if (category.name().equalsIgnoreCase(name.trim())) {
                return category;
            }
        }
        return UNKNOWN;
    }
}
