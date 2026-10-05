package com.enterprise.ticket.module.auth.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 管理员重置他人密码请求（ 密码管理、 高风险操作）
 *
 * <p>2026-09-20 ：<b>移除了 {@code newPassword} 字段</b>。
 * 临时口令改由服务端生成（{@code RandomPasswordGenerator}）并通过响应体回传，
 * 不再由调用方指定 —— 这样既能避免管理员随手设定弱口令，
 * 也保证口令必然满足密码策略的字符类型要求。
 */
@Data
public class ResetPasswordRequest {

    @NotNull(message = "请选择要重置密码的员工")
    private Long userId;
}
