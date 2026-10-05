package com.enterprise.ticket.module.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改密码请求
 */
@Data
public class ChangePasswordRequest {

    @NotBlank(message = "请输入当前密码")
    @Size(max = 64, message = "密码长度不能超过 64 个字符")
    private String oldPassword;

    @NotBlank(message = "请输入新密码")
    @Size(max = 64, message = "密码长度不能超过 64 个字符")
    private String newPassword;

    @NotBlank(message = "请再次输入新密码")
    @Size(max = 64, message = "密码长度不能超过 64 个字符")
    private String confirmPassword;
}
