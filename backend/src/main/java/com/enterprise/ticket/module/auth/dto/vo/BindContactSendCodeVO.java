package com.enterprise.ticket.module.auth.dto.vo;

import lombok.Data;

/**
 * 绑定 / 换绑联系方式 · 验证码发送结果（，）。
 */
@Data
public class BindContactSendCodeVO {

    /** 实际使用的渠道：{@code SMS} / {@code EMAIL} */
    private String contactType;

    /** 打码后的接收目标（138****8888 / a***@qq.com），供界面提示「已发送至 …」 */
    private String maskedTarget;

    /** 有效期（分钟），供倒计时与提示文案使用 */
    private int expireMinutes;

    /**
     * 开发环境回传的验证码；生产环境恒为 {@code null}。
     *
     * <p>与找回密码同一取舍（见 {@code ForgotPasswordSendCodeVO} 的说明）：
     * 当前未接入真实网关，若验证码只进日志，任何非本机环境都无法走通绑定流程。
     * 用<b>运行 profile</b> 而非配置开关控制，避免「一次误操作把全站变成免验证」。
     */
    private String devCode;
}
