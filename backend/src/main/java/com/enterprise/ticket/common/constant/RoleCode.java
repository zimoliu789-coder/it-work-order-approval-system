package com.enterprise.ticket.common.constant;

import java.util.List;
import java.util.Set;

/**
 * 角色编码（ 角色权限； 扩到 6 个）。
 *
 * <h2> 的变化</h2>
 * <p>改造前只有 3 个角色（super_admin / admin / user），因为「审批人」这件事完全由
 * 部门绑定的审批人表回答，角色只负责「能进哪些页面」。改造后组织与人员成为
 * 第一公民：审批人由**关系**（直属主管 / 部门主管）与**角色**（IT主管 / IT执行人）共同决定，
 * 于是「IT 主管」「IT 执行人」「部门经理/组长」必须成为一等角色。
 *
 * <h2>为什么「普通员工」也保留 order:approval 权限（与《需求说明书》表 9 略有出入）</h2>
 * <p>说明书表 9 把「待我审批」归给「部门经理/组长」，这在**静态角色**模型里成立，
 * 但在本系统里不成立：谁是某人的直属主管是**运行时**由组织关系推导出来的 ——
 * 张伟的角色是「普通员工」，却可能是李娜的直属主管。
 * 若普通员工拿不到 {@code order:approval}，他打开审批待办会直接 403，
 * 于是「审批自动报给一组小组长」这条验收标准根本走不通。
 * <p>因此本类刻意让**所有角色**都持有 {@code order:approval} / {@code order:pending}：
 * 列表本身只返回「与我有关」的单据，看到空列表与无权进入是两种体验，
 * 前者才是正确的「你暂时没有待办」。
 *
 * <h2>与 {@code sys_role} 表的关系</h2>
 * <p>这里只是**编码常量**；角色行与默认授权由 {@code RolePermissionInitializer}
 * 按 {@code PermissionCatalog} 写入数据库（代码即事实源）。新增一个内置角色时，
 * 三处必须同时改：本类、{@code PermissionCatalog.DEFAULT_PERMISSIONS}、
 * {@code RolePermissionInitializer} 的角色元数据表。
 */
public final class RoleCode {

    /** 超级管理员：全部权限（由守卫短路放行，不依赖数据库授权行） */
    public static final String SUPER_ADMIN = "super_admin";

    /** 管理员：全部业务功能 + 系统设置（不含高级设置） */
    public static final String ADMIN = "admin";

    /** IT主管：审批借用、管理资产、批准时指定执行人（ 新增） */
    public static final String IT_MANAGER = "it_manager";

    /** IT执行人：收到通知发设备、点「已发放」（ 新增） */
    public static final String IT_EXECUTOR = "it_executor";

    /** 部门经理/组长：审批本部门/本组的借用申请（ 新增） */
    public static final String DEPT_MANAGER = "dept_manager";

    /** 普通员工 */
    public static final String USER = "user";

    /** 「IT执行人」候选角色集合 —— 审批人规则 {@code IT_EXECUTOR} 用它找可派单的人 */
    public static final Set<String> IT_EXECUTOR_ROLES = Set.of(IT_EXECUTOR);

    /** 全部内置角色编码（顺序即角色列表的默认排序） */
    public static final List<String> BUILTIN = List.of(
            SUPER_ADMIN, ADMIN, IT_MANAGER, IT_EXECUTOR, DEPT_MANAGER, USER);

    private RoleCode() {
    }

    public static boolean isSuperAdmin(String role) {
        return SUPER_ADMIN.equals(role);
    }

    /**
     * 是否是「管理员及以上」。
     *
     * <p>只认 super_admin 与 admin —— **刻意不含 IT主管**：
     * 这个方法在改造前被用来判断「能看全部工单 / 能管设备台账」，
     * 而 IT主管的能力集与 admin 并不相同（没有员工管理、没有系统设置）。
     * 把 IT主管塞进来会在一夜之间给 IT主管多出一批它本不该有的入口。
     */
    public static boolean isAdminOrAbove(String role) {
        return SUPER_ADMIN.equals(role) || ADMIN.equals(role);
    }

    /**
     * 是否是「IT 侧角色」（IT主管 / IT执行人）。
     *
     * <p>供「待我处理」的兜底与消息路由使用：这两个角色是「设备流转」的执行方。
     */
    public static boolean isItSide(String role) {
        return IT_MANAGER.equals(role) || IT_EXECUTOR.equals(role);
    }

    /** 编码是否属于内置角色 */
    public static boolean isBuiltin(String role) {
        return BUILTIN.contains(role);
    }
}
