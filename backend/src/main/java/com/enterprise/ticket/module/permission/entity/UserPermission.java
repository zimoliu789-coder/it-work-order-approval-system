package com.enterprise.ticket.module.permission.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户级授权。
 *
 * <p>为什么必须有这张表：系统原为**纯角色制**（{@code sys_role_permission}），
 * 「只给某个人开一项权限」不能靠改角色 —— 角色是共享的，改它会波及该角色下的所有人。
 *
 * <p>撤销用 {@code revoked_at} 标记而不是删行：授权历史是审计证据
 * （「这个人的设备台账权限是什么时候、因为哪张工单开的」必须查得到）。
 * 「同一时刻只有一条未撤销记录」由 {@code UserPermissionService} 在事务内保证
 * （见 V45 里为什么没有唯一键的说明）。
 */
@Data
@TableName("user_permission")
public class UserPermission {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String permCode;

    /** APPLY 审批通过自动授予 / MANUAL 管理员手工授予 */
    private String source;

    /** 来源工单 id（APPLY 时有值，便于回溯「谁批的」） */
    private Long orderId;

    private Long grantedBy;

    private LocalDateTime grantedAt;

    /** null = 当前有效 */
    private LocalDateTime revokedAt;

    private String revokeReason;

    private LocalDateTime createdAt;
}
