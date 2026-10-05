package com.enterprise.ticket.module.role.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 角色定义（需求方三波·第一波·）
 *
 * <p>{@code roleCode} 与 {@code users.role} 对应，但<b>刻意不建外键</b>：
 * 删角色时若级联会顺手抹掉员工的角色值（安全风险），
 * 「角色是否被员工使用」属于业务规则，由服务层校验。
 *
 * <p>{@code builtin=true} 的三个内置角色（super_admin / admin / user）
 * 只允许改名称与备注，不允许改编码、不允许删除、不允许停用。
 */
@Data
@TableName("sys_role")
public class SysRole {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 角色编码（与 users.role 对应，全局唯一） */
    private String roleCode;

    /** 角色名称（界面展示） */
    private String roleName;

    /**
     * 数据权限范围：{@code ALL} 全部 / {@code GROUP} 本部门 / {@code SELF} 仅本人。
     *
     * <p>它与「权限码集合」是两个正交的维度：权限码回答「能做什么」，
     * 数据权限回答「能看到谁的数据」。本迭代真正落地的判据是
     * 「{@code ALL/GROUP} 才可访问全局视图」——由 {@code PermissionGuard} 在
     * 校验 {@code order:all:view} 时一并检查，避免它沦为纯装饰字段。
     */
    private String dataScope;

    /** 备注 */
    private String remark;

    /** 是否内置角色：内置角色不可删除、不可改编码、不可停用 */
    private Boolean builtin;

    /** 是否启用：停用后不可再分配给员工 */
    private Boolean enabled;

    /** 排序号（升序） */
    private Integer sortNo;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
