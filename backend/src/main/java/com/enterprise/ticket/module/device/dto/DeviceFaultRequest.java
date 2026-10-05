package com.enterprise.ticket.module.device.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 设备故障上报请求
 *
 * <p>两条上报路径共用本 DTO：
 * <ul>
 *   <li><b>工单内上报</b> —— 借用人在「我的工单」对使用中的设备上报，携带 {@code orderId}；</li>
 *   <li><b>台账直接登记</b> —— 管理员 / 最终处理人在设备台账页面登记，{@code orderId} 留空。</li>
 * </ul>
 *
 * <p>图片附件不在本阶段交付（需求方确认随 统一实现）。
 */
@Data
public class DeviceFaultRequest {

    /** 故障设备 device.id */
    @NotNull(message = "请选择故障设备")
    private Long deviceId;

    /** 关联工单 orders.id；无工单直接登记时留空 */
    private Long orderId;

    /** 故障描述（必填） */
    @NotBlank(message = "请填写故障描述")
    @Size(max = 500, message = "故障描述不能超过 500 个字符")
    private String faultDescription;

    /** 故障发生时间（必填，不允许晚于当前时间） */
    @NotNull(message = "请填写故障发生时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime occurredAt;
}
