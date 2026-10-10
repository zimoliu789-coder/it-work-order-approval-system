package com.enterprise.ticket.module.role.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 角色-权限关联（需求方三波·第一波·）
 *
 * <p>一行 = 某角色被授予某个权限码。{@code permCode} 的合法性由
 * {@code PermissionCatalog.exists()} 校验，库里不做约束 —— 见目录类注释。
 */
@Data
@TableName("role_permission")
public class SysRolePermission {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 角色编码 */
    private String roleCode;

    /** 权限码（菜单码或操作码） */
    private String permCode;

    private LocalDateTime createdAt;
}
