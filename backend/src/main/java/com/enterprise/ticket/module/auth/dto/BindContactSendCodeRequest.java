package com.enterprise.ticket.module.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 绑定 / 换绑联系方式 · 发送验证码请求（，）。
 *
 * <p>刻意<b>不</b>接收 {@code userId}：本接口只服务当前登录者，
 * 越权在入参层面无从表达（与 {@code ProfileController} 的整体设计一致）。
 *
 * <p>{@code target} 是「用户<b>想绑定</b>的那个号码」，不是「已绑定的号码」——
 * 验证码发到新号码上，用于证明该号码确实在他手上。服务端会把发码时的目标值
 * 一并存进 Redis，提交绑定时逐字比对，防止「用发给自己的码绑定别人的号」。
 */
@Data
public class BindContactSendCodeRequest {

    /** 渠道：{@code SMS} / {@code EMAIL}（忽略大小写与首尾空白） */
    @NotBlank(message = "请选择验证方式")
    private String contactType;

    /** 接收目标（手机号 / 邮箱明文） */
    @NotBlank(message = "请填写手机号或邮箱")
    @Size(max = 128, message = "接收目标不能超过 128 个字符")
    private String target;
}
