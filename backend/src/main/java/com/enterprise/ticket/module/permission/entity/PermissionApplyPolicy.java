package com.enterprise.ticket.module.permission.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 权限申请策略。
 *
 * <p>「没有行」= 用代码里的默认值（{@code PermissionCatalog#applicableByDefault} /
 * {@code riskLevelOf}）。这样新增权限码时不必同步插一行，而管理员改过的行会覆盖默认值。
 *
 * <p>与「代码即事实源」不冲突：代码说的是「这个码是什么」（码 / 中文名 / 默认等级），
 * 本表说的是「这个码能不能申请」—— 两者职责不同。
 */
@Data
@TableName("permission_apply_policy")
public class PermissionApplyPolicy {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String permCode;

    /** 是否开放申请；false = 不开放（提权类） */
    private Boolean applicable;

    /** NORMAL 一级审批 / HIGH 需超管多走一级 */
    private String riskLevel;

    private LocalDateTime updatedAt;
}
