package com.enterprise.ticket.module.user.dto;

import lombok.Data;

/**
 * 员工账号分页查询条件（ 轻量版员工管理）
 *
 * <p>本阶段只交付「列表 + 标记离职 / 恢复在职」，因此筛选条件也围绕这三件事：
 * 找人（keyword）、看在职状态（dimission）、按组织定位（departmentId），另补角色筛选便于快速找到管理员。
 */
@Data
public class UserPageQuery {

    private long page = 1L;

    private long size = 10L;

    /** 姓名 / 登录名 模糊匹配 */
    private String keyword;

    /**
     * 在职状态：{@code null} 全部、{@code false} 在职、{@code true} 离职。
     *
     * <p>用 {@code Boolean} 而非 {@code boolean} 是为了让「不限」与「在职」可区分。
     */
    private Boolean dimission;

    /** 部门 */
    private Long departmentId;

    /** 角色：super_admin / admin / user */
    private String role;

    /**
     * 账号来源筛选（ ）：{@code null} 全部、{@code LOCAL} 本地、{@code LDAP} AD 域账号。
     *
     * <p>用字符串而非枚举：筛选值来自前端下拉，非法值应被显式拒绝（返回 PARAM_INVALID）
     * 而不是被静默当成「全部」—— 否则运维筛「AD 账号」却看到一屏本地账号，会以为同步没生效。
     */
    private String authType;
}
