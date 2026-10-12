package com.enterprise.ticket.module.device.support;

/**
 * 故障报修「处理流程」的配置键与默认值（V50）
 *
 * <h2>它回答一个问题：报修提交后通知谁</h2>
 * <p>需求：「员工提交报修后，自动通知对应 IT 执行人（按部门或指定维修人）」，
 * 且「管理员能配置通知给谁（哪个角色 / 哪个人负责维修）」。
 *
 * <h2>为什么只有「一个目标」而不是一个审批流程</h2>
 * <p>报修**不产生工单**，也就没有审批节点这一环 —— V48 里我把它标成
 * {@code flowEditable=false} 并写明理由，正是因为这个。它需要的是**处理流程**：
 * 通知 → 接单 → 修完填结果。整条链路上唯一需要管理员配置的，就是「通知给谁」，
 * 所以这里只有两项配置，而不是一份流程图。
 *
 * <h2>为什么默认落到「最终处理部门（IT运维组）」</h2>
 * <p>系统里已经有一个全局唯一的最终处理部门（{@code department.handler_group = 1}，
 * 借用单的交付也走它）。把默认值指向它而不是留空，好处是**装好就能用**：
 * 管理员什么都不配，报修提交后也会通知到已经在维护设备的那批人。
 * 留空则表现为「提交成功但没人收到通知」——一条不会报错的静默故障。
 *
 * <h2>键名是跨三处的契约</h2>
 * <p>与 {@code UpgradeSettings} 同一约定：本类、Flyway 迁移 V50 的初值、
 * 读取它的服务层必须逐字一致。
 */
public final class FaultRepairSettings {

    private FaultRepairSettings() {
    }

    /** 配置分组编码（写入 system_config.config_group，便于归类与导出） */
    public static final String GROUP_CODE = "fault";

    /**
     * 通知目标类型：{@link AssigneeType} 的枚举名。
     *
     * <p>默认 {@code DEPARTMENT}（按部门）—— 它是三种里唯一「不需要管理员先做任何选择」
     * 就能命中的：角色要先有人挂、指定人要先去名册里找人，而部门是组织架构里现成的。
     */
    public static final String KEY_ASSIGNEE_TYPE = "fault_repair_assignee_type";
    public static final String DEFAULT_ASSIGNEE_TYPE = "DEPARTMENT";

    /**
     * 通知目标值。
     *
     * <p>语义随类型而变：{@code DEPARTMENT} = 部门 id；{@code ROLE} = 角色码；
     * {@code USER} = 用户 id。
     *
     * <p><b>留空即「用默认值」</b>：{@code DEPARTMENT} 类型的空值表示
     * 「用系统里那个最终处理部门（IT运维组）」，而不是「通知给所有人」——
     * 后者在 100 人的系统里等于没有通知。
     */
    public static final String KEY_ASSIGNEE_VALUE = "fault_repair_assignee_value";
    public static final String DEFAULT_ASSIGNEE_VALUE = "";

    /**
     * 通知目标类型。
     *
     * <p>三种类型的取舍：部门最稳（组织架构现成）、角色最灵活（不用维护成员名单）、
     * 指定人最精确（但人一离职就断）。都保留，让管理员按自己的运维习惯选。
     */
    public enum AssigneeType {

        /** 按部门：该部门的全部在职成员都会收到通知 */
        DEPARTMENT("按部门", "该部门的全部在职成员都会收到报修通知"),

        /** 按角色：所有拥有该角色码的在职员工都会收到通知 */
        ROLE("按角色", "所有拥有该角色的在职员工都会收到报修通知"),

        /** 指定人员：只有这一个人收到通知 */
        USER("指定人员", "只有这个人收到报修通知（他不在职时通知会落空，请留意）");

        private final String label;
        private final String description;

        AssigneeType(String label, String description) {
            this.label = label;
            this.description = description;
        }

        public String getLabel() {
            return label;
        }

        public String getDescription() {
            return description;
        }

        /** 容错解析：非法值一律回落到默认类型，绝不返回 null 让调用方去判空 */
        public static AssigneeType of(String value) {
            if (value == null || value.isBlank()) {
                return DEPARTMENT;
            }
            for (AssigneeType type : values()) {
                if (type.name().equalsIgnoreCase(value.trim())) {
                    return type;
                }
            }
            return DEPARTMENT;
        }
    }
}
