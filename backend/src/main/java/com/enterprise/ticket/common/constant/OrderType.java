package com.enterprise.ticket.common.constant;

/**
 * 工单类型（ 新增， 工单主表 +  /  /  的多种单据形态）
 *
 * <p>背景： 起，「归还」「维修」「换货」都可能以工单形式落地，需要在同一张
 * {@code orders} 表里区分单据性质，避免用 {@code status} 兼职表达类型
 * （状态描述「走到哪一步」，类型描述「这是什么单」，两者正交）。
 *
 * <p><b>当前阶段的实际使用范围</b>：
 * <ul>
 *   <li>{@link #BORROW} —— 主借用流程，由申请人提交产生，承载审批快照与交付/归还全生命周期；</li>
 *   <li>{@link #RETURN} —— <b>预留</b>。用于「设备已在使用中、但查不到任何在办借用单」时的
 *       独立归还登记（脏数据或迁移数据场景）。需求方已确认：员工离职联动<b>复用原借用单</b>
 *       推进为待收回，不新建 RETURN 单，因此本阶段不产生该类型的行；</li>
 *   <li>{@link #REPAIR} / {@link #EXCHANGE} —— 预留给后续阶段的维修单与换货单。</li>
 * </ul>
 *
 * <p>数据库列 {@code orders.order_type} 带 {@code DEFAULT 'BORROW'}，
 *  的存量工单不需要回填即可正确解析。
 */
public enum OrderType {

    /** 借用申请单（主流程，含审批快照） */
    BORROW("借用申请"),

    /** 归还单（无在办借用单时的独立归还登记，当前阶段预留） */
    RETURN("归还单"),

    /** 维修单（预留给设备故障处理阶段） */
    REPAIR("维修单"),

    /** 换货单（预留给设备调换阶段） */
    EXCHANGE("换货单"),

    /**
     * 自定义申请
     *
     * <p>由管理员配置的「申请类型 + 动态表单」产生，承载在职员工的自定义事项申请
     * （采购申请、外出登记、备件领用……）。与 BORROW 的关键差异：
     * <ul>
     *   <li><b>无设备</b>：{@code orders.device_id} 为空。设备的锁定 / 交付 / 归还 /
     *       超时顺延这一整套流程对它无意义，相关分支按此类型跳过；</li>
     *   <li><b>形态由数据决定</b>：填什么字段取决于 {@code orders.apply_type_id}
     *       指向的申请类型所绑定的模板版本，因此类型枚举无法穷举它的「样子」，
     *       它本身就是「可扩展」这一能力的载体。</li>
     * </ul>
     */
    CUSTOM("自定义申请");

    private final String label;

    OrderType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static OrderType of(String value) {
        if (value == null) {
            return null;
        }
        for (OrderType type : values()) {
            if (type.name().equals(value)) {
                return type;
            }
        }
        return null;
    }

    /**
     * 解析类型，空值回落为 {@link #BORROW}。
     *
     * <p>用途：读取  遗留数据（列可能为空的历史行）时给出稳定的默认语义，
     * 避免展示层出现 {@code null} 类型。
     */
    public static OrderType ofOrDefault(String value) {
        OrderType type = of(value);
        return type == null ? BORROW : type;
    }

    /** 中文名，非法/空值回落为借用申请（历史数据兜底），避免展示层出现 null */
    public static String labelOf(String value) {
        return ofOrDefault(value).getLabel();
    }
}
