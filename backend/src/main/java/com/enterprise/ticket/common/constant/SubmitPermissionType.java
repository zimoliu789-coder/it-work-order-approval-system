package com.enterprise.ticket.common.constant;

/**
 * 申请类型的提交权限类型
 *
 * <p>控制「谁能提交这种申请」。取值与配置值分开表达：
 * <ul>
 *   <li>{@link #ALL} —— 提交权限值恒为空数组，不做二次判定；</li>
 *   <li>{@link #ROLE} —— 提交权限值是<b>角色编码数组</b>（如 {@code ["admin","user"]}）；</li>
 *   <li>{@link #GROUP} —— 提交权限值是<b>部门 id 数组</b>。</li>
 * </ul>
 *
 * <p><b>为什么用角色而不是具体人</b>：按人配置会在人员调岗/离职后迅速腐化
 * （「离职的人还在授权名单里，新人加不进去」）。角色与部门都是系统里已经
 * 存在、且有专人维护的组织结构，复用它能让「谁能提交」自动跟随组织变化。
 */
public enum SubmitPermissionType {

    /** 全部登录用户均可提交 */
    ALL("全部"),

    /** 仅指定角色可提交 */
    ROLE("指定角色"),

    /** 仅指定部门可提交 */
    GROUP("指定分组");

    private final String label;

    SubmitPermissionType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static SubmitPermissionType of(String value) {
        if (value == null) {
            return null;
        }
        for (SubmitPermissionType type : values()) {
            if (type.name().equals(value)) {
                return type;
            }
        }
        return null;
    }

    /** 中文名，非法值原样返回，避免展示层出现 null */
    public static String labelOf(String value) {
        SubmitPermissionType type = of(value);
        return type == null ? value : type.getLabel();
    }
}
