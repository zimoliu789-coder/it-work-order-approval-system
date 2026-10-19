package com.enterprise.ticket.module.device.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 设备型号新增 / 编辑请求（R3）。
 *
 * <p>刻意<b>不含 status</b>：状态变更走独立端点 {@code PUT /api/device-models/{id}/status}。
 * 混在一起会让「改个备注」和「停用型号」共用一次请求 —— 而停用会立刻影响所有
 * 借用申请页的下拉，属于需要单独审计的动作。
 */
@Data
public class DeviceModelSaveRequest {

    @NotBlank(message = "型号名称不能为空")
    @Size(max = 128, message = "型号名称不能超过 128 个字符")
    private String modelName;

    @Size(max = 64, message = "品牌不能超过 64 个字符")
    private String brand;

    @Size(max = 255, message = "备注不能超过 255 个字符")
    private String remark;
}
