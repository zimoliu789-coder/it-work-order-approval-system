package com.enterprise.ticket.common.alert;

/**
 * 告警分级（ 拍板口径）。
 *
 * <p>分级同时决定「什么时候发」与「发给谁」—— 这两件事都是说明明确要求的：
 * <ul>
 *   <li>{@code P0} 紧急：<b>立即发</b>；收件人 = 全部超管 + 额外收件人（运维）；</li>
 *   <li>{@code P1} 重要：<b>汇总周期内发</b>（默认 10 分钟一封）；收件人 = 全部超管 + 额外收件人；</li>
 *   <li>{@code P2} 提示：<b>攒入日报</b>（静默时段 22:00~08:00 内不即时发）；收件人 = 只发超管。</li>
 * </ul>
 *
 * <h2>为什么分级由「异常种类」推导，而不是让每个异常自己声明</h2>
 * <p>让异常自带等级会得到一个必然的结局：要么没人填、要么所有人填 P0。
 * 等级是**运维视角**的判断（「半夜该不该把我叫起来」），应由系统按异常的性质统一推导，
 * 并允许管理员在系统参数里按分类整体忽略（{@code exception_alert_ignore_categories}）。
 * 推导规则集中在 {@link ExceptionClassifier}，可单测。
 */
public enum AlertLevel {

    /** 紧急：数据层故障等，必须立刻知情。 */
    P0("P0", "紧急", "立即发送"),

    /** 重要：网络抖动、未预期异常，汇总周期内发一封。 */
    P1("P1", "重要", "汇总周期内发送"),

    /** 提示：第三方通道（邮件 / 短信）失败等，攒日报，不打扰休息。 */
    P2("P2", "提示", "攒入日报");

    private final String code;
    private final String label;
    private final String delivery;

    AlertLevel(String code, String label, String delivery) {
        this.code = code;
        this.label = label;
        this.delivery = delivery;
    }

    /** 落库 / 前端展示用的短码（{@code P0} / {@code P1} / {@code P2}） */
    public String code() {
        return code;
    }

    /** 中文标签（页面展示） */
    public String label() {
        return label;
    }

    /** 投递节奏的一句话说明（写进告警正文，让收件人知道为什么是这时候收到的） */
    public String delivery() {
        return delivery;
    }

    /**
     * 短码 → 枚举。
     *
     * <p>未知值一律回落 {@link #P2}（最保守：不会因为库里一个脏值就把半夜的告警升级成 P0，
     * 也不会因为返回 null 让调用方 NPE）。
     */
    public static AlertLevel fromCode(String code) {
        if (code == null) {
            return P2;
        }
        for (AlertLevel level : values()) {
            if (level.code.equalsIgnoreCase(code.trim())) {
                return level;
            }
        }
        return P2;
    }
}
