package com.enterprise.ticket.common.constant;

/**
 * 工单转交来源（ 新增， + 需求方  ）
 *
 * <p> 只定义了「谁转给谁、为什么转」，没有区分转交的<b>发起方</b>。
 * 需求方在  明确要求「实际执行人离职时自动转交，并记录为系统自动转交」，
 * 因此必须把「人工转交」与「离职自动转交」在数据层分开 —— 否则事后无法回答
 * 「这笔转交是本人主动交接的，还是系统代办的」，而这直接影响责任归属。
 *
 * <p>存库取值即 {@link #name()}，与项目其它状态枚举（{@code ExtendStatus} / {@code FaultStatus}）一致。
 */
public enum TransferType {

    /** 人工转交：当前实际执行人本人发起（ 主路径） */
    MANUAL("人工转交"),

    /** 离职自动转交：员工被标记离职时，系统代其把在办工单转给同组其他在职成员（） */
    AUTO_DIMISSION("离职自动转交"),

    /**
     * 强制转交执行人（；规范 V1.1 未覆盖）
     *
     * <p>由 super_admin 在「全部工单」发起：不受小组限制，可跨组指定执行人。
     * 与 {@link #MANUAL} 区分，是为了让转交历史能回答「这笔是执行人自己交接的，
     * 还是超管强制指派的」——责任归属不同。
     */
    FORCE_ADMIN("强制转交");

    private final String label;

    TransferType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static TransferType of(String value) {
        if (value == null) {
            return null;
        }
        for (TransferType type : values()) {
            if (type.name().equals(value)) {
                return type;
            }
        }
        return null;
    }

    /** 中文名，非法值原样返回，避免展示层出现 null */
    public static String labelOf(String value) {
        TransferType type = of(value);
        return type == null ? value : type.getLabel();
    }
}
