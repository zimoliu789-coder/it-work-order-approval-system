package com.enterprise.ticket.module.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 找回密码 · 第三步：校验验证码并设置新密码。
 *
 * <p>刻意<b>不含</b> {@code confirmPassword}：两次输入是否一致是纯粹的
 * 前端表单问题（前后端都校验一遍并不能提高安全性，密码强度才是服务端必须管的）。
 * 让服务端少一个字段，就少一处「前端传了、后端没读」的语义歧义。
 */
@Data
public class ForgotPasswordResetRequest {

    @NotBlank(message = "请输入登录名（数字账号）、姓名、手机号或邮箱")
    private String account;

    @NotBlank(message = "请输入验证码")
    @Size(max = 16, message = "验证码长度不合法")
    private String code;

    @NotBlank(message = "请输入新密码")
    @Size(max = 64, message = "密码长度不能超过 64 个字符")
    private String newPassword;
}
