package com.enterprise.ticket.common.constant;

/**
 * 盘点核对结果（P2）
 *
 * <p>「尚未核对」用 {@code null} 表达，**不设 PENDING 值**：
 * null 的确切语义是「还没盘到」，与「盘了、结果是在库」天然可分；
 * 而用一个 PENDING 字符串则要求所有统计都记得排除它 —— 漏一处就把它算成一种真实结果。
 */
public enum InventoryCheckResult {

    /** 在库：现场找到了，且位置与台账一致 */
    IN_PLACE("在库"),

    /** 缺失：现场没找到（盘亏） */
    MISSING("缺失"),

    /** 位置不符：找到了，但不在台账登记的位置 */
    WRONG_LOCATION("位置不符");

    private final String label;

    InventoryCheckResult(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 中文名（静态安全版）：null 返回 null（供「尚未核对」判定），非法值原样返回 */
    public static String labelOf(String value) {
        InventoryCheckResult result = of(value);
        return result == null ? value : result.getLabel();
    }

    public static InventoryCheckResult of(String value) {
        if (value == null) {
            return null;
        }
        for (InventoryCheckResult result : values()) {
            if (result.name().equals(value)) {
                return result;
            }
        }
        return null;
    }
}
