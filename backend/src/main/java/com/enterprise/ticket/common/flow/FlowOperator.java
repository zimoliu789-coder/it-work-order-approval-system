package com.enterprise.ticket.common.flow;

/**
 * 条件分支的比较运算符。
 *
 * <p>运算符集合与需求方确认的一致：等于 / 不等于 / 大于 / 大于等于 / 小于 / 小于等于 /
 * 包含 / 不包含 / 为空 / 不为空。
 *
 * <p>「是否需要一个比较值」与「是否做数值大小比较」这两件事由枚举自己回答，
 * 而不是散落在校验器与求值器里各写一遍——否则两边一旦不一致，
 * 就会出现"校验放过了、求值时不认"这类只有线上才暴露的裂缝。
 */
public enum FlowOperator {

    EQ("等于", true, false),
    NE("不等于", true, false),
    GT("大于", true, true),
    GTE("大于等于", true, true),
    LT("小于", true, true),
    LTE("小于等于", true, true),
    CONTAINS("包含", true, false),
    NOT_CONTAINS("不包含", true, false),
    IS_EMPTY("为空", false, false),
    NOT_EMPTY("不为空", false, false);

    private final String label;
    private final boolean requiresValue;
    private final boolean numericComparison;

    FlowOperator(String label, boolean requiresValue, boolean numericComparison) {
        this.label = label;
        this.requiresValue = requiresValue;
        this.numericComparison = numericComparison;
    }

    public String getLabel() {
        return label;
    }

    /** 是否需要比较值（为空/不为空不需要） */
    public boolean isRequiresValue() {
        return requiresValue;
    }

    /** 是否要求操作数为数值（大于/大于等于/小于/小于等于） */
    public boolean isNumericComparison() {
        return numericComparison;
    }

    public static FlowOperator of(String value) {
        if (value == null) {
            return null;
        }
        for (FlowOperator operator : values()) {
            if (operator.name().equals(value)) {
                return operator;
            }
        }
        return null;
    }

    public static String labelOf(String value) {
        FlowOperator operator = of(value);
        return operator == null ? value : operator.getLabel();
    }
}
