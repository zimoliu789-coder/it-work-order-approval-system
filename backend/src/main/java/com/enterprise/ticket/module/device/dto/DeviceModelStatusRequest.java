package com.enterprise.ticket.module.device.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 设备型号启停请求（R3） */
@Data
public class DeviceModelStatusRequest {

    /** ENABLED / DISABLED，取值见 DeviceModelStatus */
    @NotBlank(message = "请选择目标状态")
    private String status;
}
