package com.enterprise.ticket.module.device.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 批量变更设备状态（P3 批量操作）
 *
 * <p>每条都走与单条变更**完全相同**的校验（设备存在 / 状态取值合法 /
 * 「使用中禁止直接报废」/ 手工流转白名单），本类只负责「选了哪些设备」与「改成什么」。
 *
 * <p>条目数上限不在这里用 {@code @Size} 校验，而是由服务层抛 {@code BATCH_SIZE_EXCEEDED}：
 * 这样前端拿到的是一个可识别的错误码，而不是通用的参数校验失败。
 */
@Data
public class DeviceBatchStatusRequest {

    @NotEmpty(message = "请选择要变更的设备")
    private List<Long> ids;

    @NotBlank(message = "目标状态不能为空")
    @Size(max = 32, message = "目标状态取值过长")
    private String targetStatus;

    @Size(max = 255, message = "操作理由长度不能超过 255 个字符")
    private String reason;
}
