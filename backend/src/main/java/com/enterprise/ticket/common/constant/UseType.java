package com.enterprise.ticket.common.constant;

/**
 * 借用类型（需求方  新增， 借用申请的两种业务形态）
 *
 * <p>背景：企业内部设备的使用方式天然分两类，用同一套字段表达会导致大量「日期为空」
 * 的脏数据与无意义的到期告警，因此显式建模：
 * <ul>
 *   <li><b>SHORT_TERM 短期借用</b>：有明确归还日期（{@code expected_return_date} 必填），
 *       到期参与预警、超时告警与自动顺延；</li>
 *   <li><b>LONG_TERM 长期领用</b>：无固定归还日期，系统<b>不主动催还</b>，
 *       由员工离职或设备故障事件触发归还。</li>
 * </ul>
 *
 * <p>注意：两种类型对应的<b>设备状态一律是「使用中」（IN_USE）</b>，
 * 设备侧不区分借用与领用（需求方明确要求）；使用类型只挂在工单上。
 */
public enum UseType {

    /** 短期借用：期望归还日期必填 */
    SHORT_TERM("短期借用", true),

    /** 长期领用：无固定归还日期，不催还 */
    LONG_TERM("长期领用", false);

    private final String label;
    private final boolean expectedReturnDateRequired;

    UseType(String label, boolean expectedReturnDateRequired) {
        this.label = label;
        this.expectedReturnDateRequired = expectedReturnDateRequired;
    }

    public String getLabel() {
        return label;
    }

    /** 是否必须填写期望归还日期 */
    public boolean isExpectedReturnDateRequired() {
        return expectedReturnDateRequired;
    }

    public static boolean isValid(String value) {
        return of(value) != null;
    }

    /** 按名称解析；非法值返回 {@code null} 或传空返回默认值由调用方决定 */
    public static UseType of(String value) {
        if (value == null) {
            return null;
        }
        for (UseType type : values()) {
            if (type.name().equals(value)) {
                return type;
            }
        }
        return null;
    }

    /** 中文名，非法值原样返回，避免展示层出现 null */
    public static String labelOf(String value) {
        UseType type = of(value);
        return type == null ? value : type.getLabel();
    }
}
