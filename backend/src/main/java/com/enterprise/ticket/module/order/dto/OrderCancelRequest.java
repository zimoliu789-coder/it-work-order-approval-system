package com.enterprise.ticket.module.order.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 工单撤回请求（：PENDING_APPROVAL / PENDING_DELIVERY 状态申请人可撤回）
 *
 * <p>撤回原因为选填，仅用于审计留痕。
 */
@Data
public class OrderCancelRequest {

    @Size(max = 500, message = "撤回原因长度不能超过 500 个字符")
    private String reason;
}
