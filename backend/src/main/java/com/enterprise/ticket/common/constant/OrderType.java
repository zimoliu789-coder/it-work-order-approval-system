package com.enterprise.ticket.common.constant;

/**
 * 工单类型（ 新增， 工单主表 +  /  /  的多种单据形态）
 *
 * <p>背景： 起，「归还」「维修」「换货」都可能以工单形式落地，需要在同一张
 * {@code borrow_order} 表里区分单据性质，避免用 {@code status} 兼职表达类型
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
 * <p>数据库列 {@code borrow_order.order_type} 带 {@code DEFAULT 'BORROW'}，
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
     *   <li><b>无设备</b>：{@code borrow_order.device_id} 为空。设备的锁定 / 交付 / 归还 /
     *       超时顺延这一整套流程对它无意义，相关分支按此类型跳过；</li>
     *   <li><b>形态由数据决定</b>：填什么字段取决于 {@code borrow_order.apply_type_id}
     *       指向的申请类型所绑定的模板版本，因此类型枚举无法穷举它的「样子」，
     *       它本身就是「可扩展」这一能力的载体。</li>
     * </ul>
     */
    CUSTOM("自定义申请"),

    /**
     * 出差申请（V53 新增的系统内置申请类型）
     *
     * <p>由 {@code BuiltInApplyCatalog} 播种、{@code OrderServiceImpl#submitCustomOrder}
     * 按类型编码分流产生：表单是播种出来的真实模板版本（起止日期 + 目标一级分部三个系统内置字段），
     * 审批走分组模式（与借用单同款）。单独成枚举值的理由与 CUSTOM 的注释互补：
     * 「同一员工只能有一份生效出差」的重叠校验、以及将来的「当前所在分部」预填，
     * 都要**按类型捞历史单** —— 有独立的 type 值，{@code WHERE order_type = 'TRIP'}
     * 就不必再经 apply_type 反查，也天然与采购 / 请假等 CUSTOM 单区分开。
     */
    TRIP("出差申请");

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
     * 是否为「表单驱动」的单据：{@link #CUSTOM} 与 {@link #TRIP}。
     *
     * <h2>为什么必须有这个谓词，而不是各处写 {@code "CUSTOM".equals(...)}</h2>
     * 有一类判定问的不是「这是什么单」，而是「**这张单走哪条生命周期**」：
     * 无设备 ⇒ 不交付、不归还、不延期、不超时顺延，审批全部通过即 {@code COMPLETED}。
     * 符合这条的，在 V53 之前只有 CUSTOM，于是全仓有四处直接写了 {@code CUSTOM} 字面量
     * （工单详情取表单数据、审批终态分流、流程条件求值的输入与 schema）。
     *
     * <p>新增 TRIP 时，这四处若继续只认 CUSTOM，会出现**最坏的一类缺陷**：
     * 出差单审批通过后落进借用单分支 ⇒ 状态被置成 PENDING_DELIVERY（待交付）、
     * 分配「最终处理人」，而交付动作要求 {@code device_id} 非空 ⇒ 必然抛 DEVICE_NOT_FOUND。
     * 工单从此<b>永久卡死</b>，且没有任何正向操作能结束它（只能撤回）。
     * 更隐蔽的连带后果：TRIP 永远到不了 COMPLETED ⇒ 出差重叠校验查不到任何历史单 ⇒
     * 闸门形同虚设，同一人可以提交无限条时间重叠的出差。
     *
     * <p>因此把这条语义收敛成一句谓词：以后再加「有表单、无设备」的单据类型，
     * 只需在 {@link #isFormDriven} 里登记一次，四处判定同源生效。
     */
    public static boolean isFormDriven(String value) {
        OrderType type = of(value);
        return type == CUSTOM || type == TRIP;
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
