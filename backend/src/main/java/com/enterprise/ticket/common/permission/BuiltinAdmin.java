package com.enterprise.ticket.common.permission;

import com.enterprise.ticket.common.constant.RoleCode;
import com.enterprise.ticket.common.util.SecurityUtils;
import com.enterprise.ticket.config.AppProperties;
import com.enterprise.ticket.config.SuperAdminInitializer;

/**
 * 「内置超级管理员」判定 —— 全局唯一事实源。
 *
 * <h2>为什么要把它单列出来</h2>
 * <p>系统里存在<b>两类</b>超级管理员，权限并不相同：
 * <ol>
 *   <li><b>内置超管</b>（登录名 = {@code app.super-admin.username}，默认 {@code administrator}）：
 *       由 Flyway 之外的 {@code SuperAdminInitializer} 种子化，是「系统永远能被救回来」的最后入口。
 *       它的角色不可被降级、不可被禁用、不可被标记离职；</li>
 *   <li><b>其他超管</b>（如演示账号「张伟」）：拥有 {@code super_admin} 角色，
 *       但受上述护栏保护，属于「能被别人管理的超管」。</li>
 * </ol>
 *
 * <p>改造前这套判定只存在于 {@code UserServiceImpl} 的私有方法里（用于组装员工列表的
 * {@code roleLocked}）。当「系统名称 / logo 仅内置超管可改」「内置超管可重置其他超管口令」
 * 两条新规则落地后，判定点从 1 处变成 3 处 —— 再各写一份必然漂移
 * （典型后果：列表里 {@code roleLocked=true} 的行，却被判定为可改）。
 * 因此把「登录名解析 + 角色判定」收敛到本类，各处只调用它。
 *
 * <h2>为什么是静态工具类而非 Bean</h2>
 * <p>输入只有「配置 + 角色 + 登录名」三个值，没有可注入的协作方，
 * 也不需要 DB（内置超管的登录名来自配置而非查库）。做成纯函数即可被单测穷尽覆盖，
 * 与 {@code ConfigRules} / {@code OperationLogLabels} 同一取舍。
 */
public final class BuiltinAdmin {

    private BuiltinAdmin() {
    }

    /**
     * 内置超管的登录名：取 {@code app.super-admin.username}，空白时回落默认值。
     *
     * <p>刻意做空值兜底：单测常以 Mock 注入 {@link AppProperties}，
     * 此时 {@code getSuperAdmin()} 返回 {@code null}；若不兜底，会在与超管无关的路径上抛 NPE，
     * 把真正的断言目标遮蔽掉。
     */
    public static String username(AppProperties appProperties) {
        AppProperties.SuperAdmin superAdmin = appProperties == null ? null : appProperties.getSuperAdmin();
        String configured = superAdmin == null ? null : superAdmin.getUsername();
        return configured == null || configured.isBlank()
                ? SuperAdminInitializer.DEFAULT_USERNAME
                : configured.trim();
    }

    /**
     * 是否为「内置超级管理员」账号 = 角色为 {@code super_admin} 且登录名等于配置的超管登录名。
     *
     * @param appProperties 应用配置（可为 null，见 {@link #username(AppProperties)}）
     * @param role          账号角色
     * @param username      账号登录名
     */
    public static boolean isBuiltinAdmin(AppProperties appProperties, String role, String username) {
        return RoleCode.isSuperAdmin(role) && username(appProperties).equals(username);
    }

    /**
     * 当前登录用户是否为内置超级管理员。
     *
     * <p>直接读 JWT 主体（{@link SecurityUtils}），不查库 —— 登录名与角色都在令牌里，
     * 多一次查询既无必要，也会让「未登录」这条路径多一个分支。
     */
    public static boolean isCurrentUserBuiltinAdmin(AppProperties appProperties) {
        return isBuiltinAdmin(appProperties, SecurityUtils.getCurrentUserRole(), SecurityUtils.getCurrentUsername());
    }

    /** 当前登录用户是否为「其他超管」（有 super_admin 角色但不是内置超管） */
    public static boolean isCurrentUserOtherSuperAdmin(AppProperties appProperties) {
        return SecurityUtils.isSuperAdmin() && !isCurrentUserBuiltinAdmin(appProperties);
    }
}
