package com.enterprise.ticket.module.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 找回密码 · 第一步：提交账号标识，查询可用于接收验证码的渠道。
 *
 * <p>{@code account} 允许四种形态，由服务端自动识别（）：
 * 5 位以上纯数字（登录名）、11 位手机号、含 {@code @} 的邮箱、其余按姓名处理。
 *
 * <p>为什么单独有这一步、而不是直接进入「选渠道 + 发验证码」：
 * 用户根本不知道「自己绑了手机还是邮箱」。若不做这一步，界面只能把两个渠道
 * 都列出来让他猜，猜错时给一个「该账号未绑定该验证方式」——
 * 那是让用户去记忆系统里的数据。先查再选，界面就能只列<b>真实可用</b>的渠道，
 * 并回显打码后的号码 / 邮箱，用户一眼就能确认「发给我的确实是那个号」。
 */
@Data
public class ForgotPasswordAccountRequest {

    @NotBlank(message = "请输入登录名（数字账号）、姓名、手机号或邮箱")
    @Size(max = 128, message = "账号长度不能超过 128 个字符")
    private String account;
}
