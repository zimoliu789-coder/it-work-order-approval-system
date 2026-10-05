package com.enterprise.ticket.module.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 登录请求（：登录账号为员工姓名）
 */
@Data
public class LoginRequest {

    @NotBlank(message = "请输入姓名")
    @Size(max = 64, message = "姓名长度不能超过 64 个字符")
    private String username;

    @NotBlank(message = "请输入密码")
    @Size(max = 64, message = "密码长度不能超过 64 个字符")
    private String password;
}
