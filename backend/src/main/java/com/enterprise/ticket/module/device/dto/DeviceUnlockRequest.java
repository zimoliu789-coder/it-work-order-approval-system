package com.enterprise.ticket.module.device.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 临时锁释放请求
 *
 * <p>{@code lockToken} 必须回传且与库中一致才允许释放 —— 这是 明确要求的
 * 「防止旧页面释放后来属于其他用户的新锁」。
 */
@Data
public class DeviceUnlockRequest {

    @NotBlank(message = "临时锁令牌不能为空")
    @Size(max = 64, message = "临时锁令牌长度不合法")
    private String lockToken;
}
