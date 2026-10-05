package com.enterprise.ticket.module.auth.service;

/**
 * 验证码发送通道（「预留短信 / 邮件发送接口，现在先用日志」）。
 *
 * <h2>为什么先抽接口、而不是直接在服务里写 log.info</h2>
 * <p>接入真实网关是**必然要发生的事**（说明就是「预留」）。若验证码的投递
 * 直接内联在找回密码服务里，将来接入短信网关就要去改业务逻辑 ——
 * 而业务逻辑里混着「发什么、发给谁、发不出去的失败语义」三类关注点，
 * 改起来最容易顺手改坏前两类。
 *
 * <p>抽出接口后，接入真实网关只需新增一个 {@code @Primary} 的实现，
 * 业务代码一行不动。当前实现见 {@code LoggingVerificationCodeSender}。
 *
 * <h2>失败语义</h2>
 * <p>实现应<b>抛出 {@code BusinessException}</b> 表示「这条验证码没送出去」——
 * 调用方（{@code ForgotPasswordService}）会因此中止流程并保持 Redis 中的验证码
 * 与本次通知内容一致。刻意不用返回值表示失败：静默失败会导致
 * 「界面提示已发送、实际没发」，而用户会一直等着一条永远不来的短信。
 */
public interface VerificationCodeSender {

    /**
     * 发送验证码。
     *
     * @param contactType 渠道：{@code SMS} / {@code EMAIL}
     * @param target      接收目标（手机号 / 邮箱明文；打码由实现方在日志里自行处理）
     * @param code        验证码
     * @param expireMinutes 有效期（分钟），供文案使用
     */
    void send(String contactType, String target, String code, int expireMinutes);
}
