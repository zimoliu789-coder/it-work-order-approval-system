package com.enterprise.ticket.common.constant;

/**
 * 盘点范围类型（P2）
 *
 * <p>范围必须可复现，所以它是**结构化字段**而不是任务名里的一句话：
 * 「这次盘了哪些设备」决定报告的可信度 —— 漏盘一台和真丢一台在报告里长得一模一样，
 * 只有范围写得清楚，盘亏才有意义。
 */
public enum InventoryScopeType {

    /** 全量：所有未删除设备 */
    ALL("全部设备"),

    /** 按设备分类（一级或二级分类） */
    CATEGORY("按分类"),

    /** 按存放位置 */
    LOCATION("按存放位置");

    private final String label;

    InventoryScopeType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 中文名（静态安全版）：非法/空值原样返回 */
    public static String labelOf(String value) {
        InventoryScopeType type = of(value);
        return type == null ? value : type.getLabel();
    }

    public static InventoryScopeType of(String value) {
        if (value == null) {
            return null;
        }
        for (InventoryScopeType type : values()) {
            if (type.name().equals(value)) {
                return type;
            }
        }
        return null;
    }
}
