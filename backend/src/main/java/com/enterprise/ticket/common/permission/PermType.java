package com.enterprise.ticket.common.permission;

/**
 * 权限类型（需求方三波·第一波·「角色与权限管理」）
 *
 * <p>区分两类权限，是因为它们在校验时机与失败表现上完全不同：
 * <ul>
 *   <li>{@link #MENU} —— 控制「看不看得见入口」。前端据此渲染侧边栏；
 *       后端仅在个别「全局视图」端点上顺带校验（如全部工单），
 *       因为菜单本身不产生数据访问。</li>
 *   <li>{@link #ACTION} —— 控制「能不能做这个动作」。每个写端点都必须校验，
 *       缺权限返回 403。这类权限才是真正的安全边界。</li>
 * </ul>
 * 前端按钮显隐只是体验，接口必须自己拦（：前端隐藏不是权限）。
 */
public enum PermType {

    /** 菜单权限：控制入口是否可见 */
    MENU("菜单"),

    /** 操作权限：控制具体动作是否可执行（后端强制校验） */
    ACTION("操作");

    private final String label;

    PermType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
