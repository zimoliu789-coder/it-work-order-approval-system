package com.enterprise.ticket.module.device.dto.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 设备临时锁视图
 *
 * <p>前端拿到 {@code lockToken} 与 {@code expiresAt} 后：展示倒计时；
 * 提交申请时把 {@code lockToken} 原样回传；超时/取消/切换设备时用同一令牌释放。
 * 令牌必须在释放时比对，否则旧页面会把「后来属于其他用户的新锁」错误释放（ 明确要求）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeviceLockVO {

    private Long deviceId;

    private String deviceName;

    private String lockToken;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lockedAt;

    /** 锁到期时间 = lockedAt + 超时时长 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime expiresAt;

    /** 本次锁的有效分钟数（来自 system_config.lock_timeout_minutes， 可配置 1–60） */
    private int timeoutMinutes;
}
