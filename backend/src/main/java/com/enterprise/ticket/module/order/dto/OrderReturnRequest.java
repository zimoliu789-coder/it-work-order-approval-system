package com.enterprise.ticket.module.order.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发起归还请求（ 第一步）
 *
 * <p>申请人只能填一段<span>可选</span>的归还说明（设备当前状态、有无损坏等），
 * 设备状态由之后的「确认收回」环节登记 —— 归还流程刻意分两步，就是为了避免申请人
 * 单方面决定设备去向（「申请人本人不能执行收回确认」）。
 */
@Data
public class OrderReturnRequest {

    /** 归还说明（可选）；长度上限与 orders.return_note 列宽一致 */
    @Size(max = 500, message = "归还说明不能超过 500 个字符")
    private String returnNote;
}
