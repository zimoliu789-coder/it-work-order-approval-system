package com.enterprise.ticket.common.constant;

/**
 * 归还触发来源（ 新增， 两步归还 +  超时直接收回 +  离职管控）
 *
 * <p>记录「这一次归还到底是谁/什么触发的」。同一笔工单的归还结果相同，但来源不同，
 * 事后追溯与统计的口径完全不同（例如「有多少设备是因员工离职而被动回收的」），
 * 因此单独落列而不是从操作日志反推。
 *
 * <p>{@code orders.return_trigger} 在归还流程发起时写入，未被归还的工单该列为 NULL。
 */
public enum ReturnTrigger {

    /** 申请人主动在「我的工单」点击「归还设备」（ 第一步） */
    USER_INITIATED("申请人主动归还"),

    /** 管理员标记员工离职，系统自动把其在办工单推进为待收回（ +  ） */
    DIMISSION("员工离职自动回收"),

    /**
     * 管理员强制收回。
     *
     * <p>典型场景：实际执行人本人已离职/禁用，无人可确认收回（ 的兜底思路），
     * 由 super_admin 代为收回。此时 {@code returned_by} 记录的是管理员而非原执行人。
     */
    ADMIN_FORCE("管理员强制收回");

    private final String label;

    ReturnTrigger(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static ReturnTrigger of(String value) {
        if (value == null) {
            return null;
        }
        for (ReturnTrigger trigger : values()) {
            if (trigger.name().equals(value)) {
                return trigger;
            }
        }
        return null;
    }

    /** 中文名；未归还（NULL）时返回 null，由展示层决定显示为「-」还是留空 */
    public static String labelOf(String value) {
        ReturnTrigger trigger = of(value);
        return trigger == null ? null : trigger.getLabel();
    }
}
