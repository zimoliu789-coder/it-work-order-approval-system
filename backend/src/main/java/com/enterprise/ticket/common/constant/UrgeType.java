package com.enterprise.ticket.common.constant;

/**
 * 催办类型（ 新增；规范 V1.1 未覆盖，按需求方 2026-09-18 确认方案实现）
 *
 * <p>两类催办的发起人与被催办人完全不同，因此在数据层就用枚举区分，而不是靠
 * 「谁发起的」反推 —— 后者在超管代操作等场景下会失效。
 *
 * <p><b>催办不改变工单状态</b>（需求方明确）：它只做两件事 —— 发一条站内消息，
 * 以及在本表留痕供工单详情时间线展示。
 */
public enum UrgeType {

    /**
     * 审批催办：申请人 → 当前审批节点的审批人。
     *
     * <p>冷却键是「工单 + 当前节点」，节点推进后可对新节点再次催办（需求方  ）。
     */
    APPROVAL("审批催办"),

    /**
     * 归还催办：实际执行人 → 借用人（申请人）。
     *
     * <p>适用条件为「工单使用中且已到期或已超时」（需求方  ），
     * 没有节点的概念，故冷却键退化为「工单」。
     */
    RETURN("归还催办");

    private final String label;

    UrgeType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static UrgeType of(String value) {
        if (value == null) {
            return null;
        }
        for (UrgeType type : values()) {
            if (type.name().equals(value)) {
                return type;
            }
        }
        return null;
    }

    /** 中文名，非法值原样返回，避免展示层出现 null */
    public static String labelOf(String value) {
        UrgeType type = of(value);
        return type == null ? value : type.getLabel();
    }
}
