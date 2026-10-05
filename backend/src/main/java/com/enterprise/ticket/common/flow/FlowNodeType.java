package com.enterprise.ticket.common.flow;

/**
 * 流程节点类型（ + ）。
 *
 * <p> 只有审批 / 条件分支 / 结束三值； 增补 {@code CC}（抄送）。
 *
 * <p>抄送与审批**共用同一套审批人规则解析**（{@link ApproverRuleResolver}），但语义不同：
 * 抄送是「知会」而非「审批步骤」—— 提交时即解析提交人、即发消息、落库为终态
 * （{@code CC_NOTIFIED}），不参与「当前步骤」判定、不阻塞推进。
 * 因此它需要审批规则、需要 {@code next}，但不需要 {@code signType}，也不允许配分支。
 */
public enum FlowNodeType {

    /** 审批节点：由人（或规则解析出的人）进行通过/驳回 */
    APPROVAL("审批"),

    /** 抄送节点：知会对象，提交时即解析并发送，不阻塞、不参与推进 */
    CC("抄送"),

    /** 条件分支节点：按表单字段值选择一条出口，条件全不命中时走默认出口 */
    CONDITION("条件分支"),

    /** 结束节点：流程终点，无出口 */
    END("结束");

    private final String label;

    FlowNodeType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static FlowNodeType of(String value) {
        if (value == null) {
            return null;
        }
        for (FlowNodeType type : values()) {
            if (type.name().equals(value)) {
                return type;
            }
        }
        return null;
    }
}
