package com.enterprise.ticket.module.order.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 发起借用延期申请
 *
 * <p>延期是申请人主动发起的子工单：延长借用时间，需要走审批。
 *
 * <p>时间校验放在服务层（{@code EXTEND_TIME_INVALID}）而非 {@code @Future}：
 * 与设备故障上报（{@code FAULT_OCCURRED_TIME_INVALID}）保持一致的错误码语义 ——
 * 时间非法属于**业务校验**，应返回可被前端识别的具体业务错误码，而不是笼统的
 * 参数校验错误（{@code PARAM_INVALID}）。服务层同时覆盖 {@code newEndTime} 为空的情况。
 */
@Data
public class OrderExtendRequest {

    /** 申请延长到的新结束时间（必须晚于当前系统时间，由服务层校验） */
    @NotNull(message = "请填写延期后的结束时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime newEndTime;

    /** 延期原因（必填） */
    @NotBlank(message = "请填写延期原因")
    @Size(max = 500, message = "延期原因不能超过 500 个字符")
    private String reason;
}
