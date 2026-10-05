package com.enterprise.ticket.security;

import com.enterprise.ticket.common.constant.RoleCode;
import com.enterprise.ticket.module.user.entity.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.List;

/**
 * 登录用户主体：持有 user_id、角色、部门、强制改密标记、联系方式绑定状态。
 *
 * <p>：所有业务表外键必须使用 user_id；因此这里同时保留 id 与 username，
 * 业务代码统一通过 {@link #getId()} 取用户主键。
 *
 * <p>说明：此处手写 getter 而不使用 Lombok，避免与 {@link UserDetails} 接口方法
 * （getUsername / isEnabled）产生重复定义。
 */
public class LoginUser implements UserDetails {

    private final Long id;
    private final String username;
    private final String displayName;
    private final String passwordHash;
    private final String role;
    private final String authType;
    private final Long departmentId;
    private final boolean forceChangePassword;
    private final boolean enabled;
    private final boolean dimission;

    /**
     * 令牌版本号（需求方三波·第一波·）。
     *
     * <p>放在登录主体里而不是每次单独查库：过滤器本来就要加载用户，
     * 顺带拿到版本号即可完成校验，不额外增加一次数据库往返。
     * 空值按 0 处理，兼容 V12 之前的存量行（迁移已给默认值 0，此处是二道保险）。
     */
    private final int tokenVersion;

    /**
     * 库里是否已填写手机号或邮箱（ / R1：强制绑定联系方式的服务端闸门读它）。
     *
     * <p><b>只表达「库里有没有值」，不含「渠道开关是否打开」</b> —— 后者是运行时配置，
     * 由过滤器另行判断（渠道全关时视为无需绑定，见）。把配置判断塞进来会让
     * LoginUser 依赖 Spring 容器，而它是纯 POJO；过滤器每请求都会加载用户，
     * 顺带把布尔值算好，也不额外增加查询。
     */
    private final boolean hasContact;

    public LoginUser(User user) {
        this.id = user.getId();
        this.username = user.getUsername();
        this.displayName = user.getDisplayName();
        this.passwordHash = user.getPasswordHash();
        this.role = user.getRole();
        this.authType = user.getAuthType();
        this.departmentId = user.getDepartmentId();
        this.forceChangePassword = Boolean.TRUE.equals(user.getForceChangePassword());
        this.enabled = Boolean.TRUE.equals(user.getEnabled());
        this.dimission = Boolean.TRUE.equals(user.getDimission());
        this.tokenVersion = user.getTokenVersion() == null ? 0 : user.getTokenVersion();
        this.hasContact = StringUtils.hasText(user.getPhone()) || StringUtils.hasText(user.getEmail());
    }

    public Long getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getRole() {
        return role;
    }

    public String getAuthType() {
        return authType;
    }

    public Long getDepartmentId() {
        return departmentId;
    }

    public boolean isForceChangePassword() {
        return forceChangePassword;
    }

    public boolean isDimission() {
        return dimission;
    }

    /** 库中是否已填写手机号或邮箱（不含渠道开关判断，见字段注释） */
    public boolean hasContact() {
        return hasContact;
    }

    /** 令牌版本号；过滤器据此判定 JWT 是否已被服务端主动作废 */
    public int getTokenVersion() {
        return tokenVersion;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled && !dimission;
    }

    public boolean isSuperAdmin() {
        return RoleCode.isSuperAdmin(role);
    }
}
