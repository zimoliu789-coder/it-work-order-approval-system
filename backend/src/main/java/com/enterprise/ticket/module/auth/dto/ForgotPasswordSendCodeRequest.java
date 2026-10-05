package com.enterprise.ticket.module.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 找回密码 · 第二步：指定接收验证码的渠道并发送验证码。
 *
 * <p>{@code contactType} 只有两个合法取值：{@code SMS} / {@code EMAIL}（）。
 * 用 {@code @Pattern} 在入口处先挡一道，避免非法取值一路流到服务层再判 ——
 * 入口校验的报错更精确（能直接指向字段），服务层只需处理「合法但当前被禁用」的情况。
 */
@Data
public class ForgotPasswordSendCodeRequest {

    @NotBlank(message = "请输入登录名（数字账号）、姓名、手机号或邮箱")
    private String account;

    @NotBlank(message = "请选择验证方式")
    @Pattern(regexp = "SMS|EMAIL", message = "验证方式取值不合法（仅支持 SMS / EMAIL）")
    private String contactType;
}
