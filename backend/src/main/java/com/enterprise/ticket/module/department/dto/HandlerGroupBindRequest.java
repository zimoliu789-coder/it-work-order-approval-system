package com.enterprise.ticket.module.department.dto;

import lombok.Data;

/**
 * 把「一级分部」绑定到某个处理小组（V53）。
 *
 * <p>只有 {@code handlerDepartmentId} 一个字段，且<b>允许为 null</b>：
 * {@code null} 表示解除绑定。用「传 null = 解绑」而不是另做一个 DELETE 端点，
 * 是因为前端就是同一个下拉框 —— 选人时 PUT、清空选择时 PUT（值 null），
 * 两个动作落在同一个交互控件上，做成两个接口反而要在前端分叉。
 */
@Data
public class HandlerGroupBindRequest {

    /** 处理小组的部门 id（必须 {@code handler_group = 1} 且启用）；null = 解除绑定 */
    private Long handlerDepartmentId;
}
