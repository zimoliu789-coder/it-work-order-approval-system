package com.enterprise.ticket.module.applytype.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 申请类型启用 / 停用请求
 *
 * <p>停用与删除是两回事：停用只是「不再出现在提交页」，已提交的工单照常流转、
 * 照常按原表单渲染；删除则要求该类型从未被任何工单使用过。因此启用/停用
 * 单独一个端点，而不是复用「修改」——避免管理员为了停用一个类型，
 * 先把整份配置（名称、权限、表单关联）提交一遍。
 */
@Data
public class ApplyTypeStatusRequest {

    /** ENABLED / DISABLED */
    @NotBlank(message = "状态不能为空")
    private String status;
}
