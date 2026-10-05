package com.enterprise.ticket.common.constant;

/**
 * 借用延期子工单状态（ 新增，）
 *
 * <p>延期是**子工单**：申请人主动发起 → 走与主单相同的审批链路 → 通过后只回写主单的
 * 借用时间，不改动主单的申请记录与状态。
 *
 * <p>状态机为单向：{@code PENDING_APPROVAL → APPROVED | REJECTED}，不设撤回 ——
 * 延期只是「申请延长归还时间」，驳回对主单无任何副作用（「延期审批驳回：不影响原有借用时间」），
 * 因此无需撤回语义。
 */
public enum ExtendStatus {

    /** 审批中：已生成延期审批快照，等待审批人处理 */
    PENDING_APPROVAL("审批中"),

    /** 已通过：主单 planned_end_time 已延长、borrow_timeout 已重算、auto_extend_count 已归零 */
    APPROVED("已通过"),

    /** 已驳回：不影响主单原有借用时间 */
    REJECTED("已驳回");

    private final String label;

    ExtendStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static ExtendStatus of(String value) {
        if (value == null) {
            return null;
        }
        for (ExtendStatus status : values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        return null;
    }

    /** 中文名，用于列表与详情展示 */
    public static String labelOf(String value) {
        ExtendStatus status = of(value);
        return status == null ? null : status.getLabel();
    }
}
