package com.enterprise.ticket.common.constant;

/**
 * 自定义申请类型的审批方式（ 引入， 扩展）
 *
 * <p>一期只有 {@code NONE} 与 {@code GROUP} 两个取值，并在注释里明确写下了
 * "不放 {@code CUSTOM_FLOW} 之类的占位值——一个永远不会被实现的取值，
 * 只会在管理界面的下拉里多出一个选了不生效的选项"。
 * 二期真正实现了独立流程模板，于是这里补上 {@code FLOW} —— 兑现那个承诺的方式
 * 是先有实现、再加取值，而不是反过来先占位。
 *
 * <p>三种取值并存是刻意的：{@code GROUP} 并没有被 FLOW 取代。
 * 既有的分组审批配置继续可用（它简单、够用，且已被验收过），
 * 新建类型可以按需选择"走分组的线性审批"还是"用独立流程模板"。
 */
public enum ApprovalMode {

    /**
     * 无审批：提交即完成（状态直接落 {@code COMPLETED}）。
     *
     * <p>适用「登记 / 备案」类申请：如外出登记、备件领用登记 —— 这些动作的价值在于
     * 「留痕」而不是「等谁同意」，强行加审批只会让人绕过系统。
     */
    NONE("无审批"),

    /** 走分组审批流：复用申请人所属部门的审批节点，全部通过后工单完成 */
    GROUP("分组审批"),

    /**
     * 使用独立审批流程模板。
     *
     * <p>与 {@link #GROUP} 的区别：GROUP 的审批人与顺序来自**部门**的固定线性配置；
     * FLOW 则来自申请类型绑定的**流程版本**，支持条件分支与多种审批人来源
     * （指定人员 / 角色 / 分组审批人 / 处理小组 / 表单人员字段 / 申请人自选）。
     *
     * <p>选择 FLOW 时必须绑定一个**已发布**的流程版本，由服务层校验（与一期
     * "必须绑定已发布的表单版本"同构）。
     */
    FLOW("自定义流程");

    private final String label;

    ApprovalMode(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static ApprovalMode of(String value) {
        if (value == null) {
            return null;
        }
        for (ApprovalMode mode : values()) {
            if (mode.name().equals(value)) {
                return mode;
            }
        }
        return null;
    }

    /** 中文名，非法值原样返回，避免展示层出现 null */
    public static String labelOf(String value) {
        ApprovalMode mode = of(value);
        return mode == null ? value : mode.getLabel();
    }
}
