package com.enterprise.ticket.module.auth.dto;

import com.enterprise.ticket.module.user.entity.User;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 当前登录用户信息（前端用于渲染菜单、头像区、强制改密拦截）
 *
 * <p>（角色与权限管理）追加两个字段：
 * <ul>
 *   <li>{@code permissions} —— 该角色被授予的权限码集合。前端据此<b>动态渲染菜单与按钮</b>，
 *       不再依赖硬编码的角色判断；</li>
 *   <li>{@code dataScope} —— 数据权限范围（ALL / GROUP / SELF），供前端决定是否展示
 *       「全部工单」这类全局视图入口。</li>
 * </ul>
 */
@Data
public class LoginUserInfo {

    private Long id;
    private String username;
    private String displayName;
    private String role;
    private String authType;
    private Long departmentId;
    private String departmentName;
    private boolean forceChangePassword;
    private boolean superAdmin;
    private boolean adminOrAbove;

    /**
     * 是否为「内置超级管理员」（登录名 = {@code app.super-admin.username}，默认 administrator）。
     *
     * <p><b>与 {@link #superAdmin} 的区别</b>：后者只表示「角色是 super_admin」，
     * 本字段表示「且登录名等于配置的内置超管」——「其他超管」（如演示账号张伟）
     * {@code superAdmin=true} 而 {@code builtInAdmin=false}。
     *
     * <p>前端必须能区分这两者，否则会多出本不该有的入口：
     * 员工管理页「内置超管可重置其他超管口令」、系统参数页「站点品牌仅内置超管可改」，
     * 都要求「其他超管」看到的是只读/隐藏，而不是可操作。
     * 判定逻辑与后端同源（{@code BuiltinAdmin}），避免前后端算出不同答案。
     */
    private boolean builtInAdmin;

    /**
     * 手机号（V26）。{@code null} = 未绑定。
     *
     * <p>登录响应里带上它，是为了让前端在「首次绑定引导」与「个人中心」两处
     * 直接拿到当前值做回填 —— 否则前端还得为此多发一次请求，
     * 而且会出现「引导页显示未绑定、个人中心显示已绑定」的不一致窗口。
     */
    private String phone;

    /** 邮箱（V26）。语义同 {@link #phone} */
    private String email;

    /**
     * 是否需要强制绑定联系方式（）。
     *
     * <p>为 {@code true} 表示：手机号与邮箱<b>都为空</b>，且系统至少启用了一个验证渠道。
     * 此时前端必须弹出绑定引导页，不绑定不能进入系统。
     *
     * <p>为什么要带上「且至少启用一个渠道」这个条件（）：
     * 两个开关都关掉时，用户<b>根本无法完成绑定</b>（没有渠道可以验证号码归属）——
     * 此时若仍然 require=true，用户就会卡在一个永远点不过去的引导页上，
     * 既进不了系统、也联系不到管理员。所以这种情况直接放行，
     * 由管理员在后台补录联系方式。
     */
    private boolean requireContactBinding;

    /** 权限码集合（）。未加载时为空列表，前端按「无权限」渲染，不会误放开入口 */
    private List<String> permissions = new ArrayList<>();

    /** 数据权限范围：ALL / GROUP / SELF（） */
    private String dataScope;

    /**
     * 是否为故障报修「处理人」（V50）。
     *
     * <p>即：落在「申请类型与流程 → 故障报修 → 处理流程」配置的通知范围内。
     * 这类人**没有** {@code device:fault:view} 权限码（那个码只发给管理员），
     * 但「设备故障记录」页正是他们的待办 —— 接单、修完登记结果都在那里。
     * 因此单独下发一个标识，让前端能把那个菜单放进他们的侧边栏。
     *
     * <p><b>这不是权限码，也不该当成权限码用</b>：它只影响「菜单可见性」，
     * 真正的准入与数据隔离在服务端 —— 非管理员调列表接口只会拿到
     * 「未完结 且（没人接单 或 他接的）」，拿不到全量台账。
     * 之所以由后端算而不是让前端自己判断：处理范围是可配的（部门 / 角色 / 指定人），
     * 前端无从得知；若让前端「按角色猜」，就会与配置脱节。
     */
    private boolean faultRepairer;

    public static LoginUserInfo from(User user) {
        LoginUserInfo info = new LoginUserInfo();
        info.setId(user.getId());
        info.setUsername(user.getUsername());
        info.setDisplayName(user.getDisplayName());
        info.setRole(user.getRole());
        info.setAuthType(user.getAuthType());
        info.setDepartmentId(user.getDepartmentId());
        info.setForceChangePassword(Boolean.TRUE.equals(user.getForceChangePassword()));
        info.setPhone(user.getPhone());
        info.setEmail(user.getEmail());
        boolean superAdmin = "super_admin".equals(user.getRole());
        info.setSuperAdmin(superAdmin);
        info.setAdminOrAbove(superAdmin || "admin".equals(user.getRole()));
        return info;
    }
}
