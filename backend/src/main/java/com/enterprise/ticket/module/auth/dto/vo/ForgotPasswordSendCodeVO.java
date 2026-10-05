package com.enterprise.ticket.module.auth.dto.vo;

import lombok.Data;

/**
 * 找回密码 · 验证码发送结果（第二步）。
 */
@Data
public class ForgotPasswordSendCodeVO {

    /** 实际使用的渠道：{@code SMS} / {@code EMAIL} */
    private String contactType;

    /** 打码后的接收目标（138****8888 / a***@qq.com），供界面提示「已发送至 …」 */
    private String maskedTarget;

    /** 有效期（分钟），供倒计时与提示文案使用 */
    private int expireMinutes;

    /**
     * 开发环境回传的验证码；生产环境恒为 {@code null}。
     *
     * <h2>为什么要把验证码回传（而不是只打日志）</h2>
     * <p>本系统当前<b>没有接入真实的短信 / 邮件网关</b>（ 明确「预留短信/邮件
     * 发送接口，现在先用日志」）。若验证码只进日志，那么在任何非本机环境上
     * 都无法走通这条流程 —— 验收、联调、自动化回归全都要先翻服务器日志，
     * 而日志里混着成百上千行其它内容。
     *
     * <h2>为什么生产环境必须为 null</h2>
     * <p>回传验证码等价于「跳过验证」——任何调用方都能拿到它并直接重置密码。
     * 因此这里用<b>运行 profile</b> 而不是配置开关来控制：profile 是部署时确定的，
     * 不会被一次误操作（在参数页把某开关打开）意外打开。
     * 判断见 {@code ForgotPasswordService#devMode}。
     */
    private String devCode;
}
