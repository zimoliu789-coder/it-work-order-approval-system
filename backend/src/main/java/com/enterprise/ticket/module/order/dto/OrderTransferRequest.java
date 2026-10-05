package com.enterprise.ticket.module.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 工单转交请求
 *
 * <p>规范要求「必填转交备注」，故 {@code comment} 用 {@link NotBlank} 而非 {@code NotNull} ——
 * 只填空格同样应被拒绝。
 */
@Data
public class OrderTransferRequest {

    /** 新实际执行人 user_id：普通执行人限同组在职成员，super_admin 不受限 */
    @NotNull(message = "请选择转交对象")
    private Long newHandlerId;

    /** 转交原因 / 备注（ transfer_comment，必填） */
    @NotBlank(message = "请填写转交原因")
    @Size(max = 500, message = "转交原因不能超过 500 字")
    private String comment;
}
