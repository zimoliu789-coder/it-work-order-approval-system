package com.enterprise.ticket.security;

import com.enterprise.ticket.common.permission.PermissionCatalog;
import com.enterprise.ticket.common.util.SecurityUtils;
import com.enterprise.ticket.module.permission.service.UserPermissionService;
import com.enterprise.ticket.module.role.service.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 权限判定守卫（需求方三波·第一波·）
 *
 * <p>在 {@code @PreAuthorize} 中以 Bean 引用的方式使用：
 * <pre>
 *   {@code @PreAuthorize("@perm.has('device:ledger:manage')")}
 * </pre>
 *
 * <h2>为什么用「权限码」替换掉散落各处的 hasRole</h2>
 * <p>改造前每个端点都写死 {@code hasRole('SUPER_ADMIN')} / {@code hasAnyRole(...)}，
 * 这意味着<b>角色集合是编译期常量</b> —— 想新增一个「只能看设备台账、不能改」的角色，
 * 只能改代码。改成权限码后，「能做什么」由数据决定，新角色无需发版即可生效。
 *
 * <h2>三条不变式</h2>
 * <ol>
 *   <li><b>super_admin 恒放行</b>：不查库、不看授权行，任何情况下都通过。
 *       这是系统最后的救火通道 —— 即便授权数据被误删或写坏，超管也能进去修好；</li>
 *   <li><b>未登录恒拒绝</b>：守卫不做「未知即放行」的兜底；</li>
 *   <li><b>异常一律拒绝</b>：实现里不吞异常，查库失败时 Spring Security 会把它
 *       当作授权失败处理（403）而不是放行 —— 拒绝服务比越权可接受得多。</li>
 * </ol>
 *
 * <h2>数据权限如何参与判定</h2>
 * <p>{@code data_scope} 不是装饰字段：访问<b>全局视图</b>（如「全部工单」）时，
 * 除权限码之外还要求角色的数据范围不是 {@code SELF}。
 * 否则「勾了全部工单权限 + 数据范围仅本人」这一自相矛盾的配置会直接把全量数据放出去。
 */
@Component("perm")
@RequiredArgsConstructor
public class PermissionGuard {

    private final RoleService roleService;
    private final UserPermissionService userPermissionService;

    /**
     * 当前登录用户是否拥有某权限码
     *
     * <p>判定来源是**并集**：角色权限 **∪** 用户级授权。
     * 用户级授权是「审批通过后自动开通」的落点 —— 只给某个人开一项权限，
     * 不能靠改角色（角色是共享的，改它会波及该角色下的所有人）。
     *
     * <p>查询顺序是刻意的：**先查角色**（走缓存、便宜），角色没有才去查用户级授权。
     * 反过来会让每一次权限判定都多一次数据库往返，而绝大多数请求靠角色就能通过。
     *
     * @param permCode 权限码，取值见 {@link PermissionCatalog}
     */
    public boolean has(String permCode) {
        LoginUser user = SecurityUtils.getCurrentUser();
        if (user == null) {
            return false;
        }
        if (user.isSuperAdmin()) {
            return true;
        }
        String role = user.getRole();
        if (!roleService.hasPermission(role, permCode)) {
            // 角色没有 ⇒ 再看有没有用户级授权（审批通过自动开通的那条腿）
            if (!userPermissionService.effectiveCodes(user.getId()).contains(permCode)) {
                return false;
            }
        }
        // 全局视图额外要求数据范围：SELF 范围的角色即便拿到权限码，也不允许看全量数据
        if (PermissionCatalog.ORDER_ALL_VIEW.equals(permCode)
                && RoleService.SCOPE_SELF.equals(roleService.dataScopeOf(role))) {
            return false;
        }
        return true;
    }

    /** 任一权限码满足即通过（用于同一端点多入口的场景） */
    public boolean hasAny(String... permCodes) {
        if (permCodes == null) {
            return false;
        }
        for (String code : permCodes) {
            if (has(code)) {
                return true;
            }
        }
        return false;
    }

    /** 供前端渲染按钮：当前登录用户是否拥有该权限（不抛异常，未登录返回 false） */
    public boolean currentHas(String permCode) {
        return has(permCode);
    }
}
