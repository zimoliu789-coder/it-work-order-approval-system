package com.enterprise.ticket.common.constant;

/**
 * 转单申请状态（V55 / D5.7 三态）。
 *
 * <h2>为什么是三个状态，而不是四个</h2>
 * <p>跨组转单的「接收」与「分配成员」被刻意合并为**一次动作**：
 * <ul>
 *   <li>若拆成「已接收（待分配）」+「已分配」两态，工单会多出一个<b>没人负责</b>的中间态 ——
 *       原组已经转出、新组还没指定人，超时告警/催还全部找不到执行人；</li>
 *   <li>需求定稿的审计要素里也只有三态：待接收 / 已接收分配 / 拒绝退回。</li>
 * </ul>
 * 因此「接收」接口强制同时给出目标执行人，接收即生效。
 *
 * <h2>为什么保留 CANCELLED</h2>
 * <p>工单在申请挂起期间可能被归还 / 撤回 / 强制终止，此时申请单不该永远挂在
 * 「待接收」里 —— 系统会把它置为作废，让待办列表自然清空，同时保留记录以备回溯。
 */
public enum TransferRequestStatus {

    /** 待接收（同组：待本组管理员审批；跨组：待目标组管理员接收） */
    PENDING("待接收"),

    /** 已接收分配：转单已生效（执行人已变更，跨组时小组也已变更） */
    ACCEPTED("已接收分配"),

    /** 拒绝退回：工单留在原组原执行人手里 */
    REJECTED("拒绝退回"),

    /** 已作废：工单在挂起期间进入终态 / 已被其他方式转走，本条申请不再有效 */
    CANCELLED("已作废");

    private final String label;

    TransferRequestStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static TransferRequestStatus of(String value) {
        if (value == null) {
            return null;
        }
        for (TransferRequestStatus status : values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        return null;
    }

    /** 中文名，非法值原样返回（避免展示层出现 null） */
    public static String labelOf(String value) {
        TransferRequestStatus status = of(value);
        return status == null ? value : status.getLabel();
    }
}
