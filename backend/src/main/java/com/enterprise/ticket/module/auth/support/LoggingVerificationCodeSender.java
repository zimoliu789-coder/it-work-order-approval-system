package com.enterprise.ticket.module.auth.support;

import com.enterprise.ticket.common.util.AccountFormats;
import com.enterprise.ticket.module.auth.service.VerificationCodeSender;
import com.enterprise.ticket.module.system.support.ContactRecovery;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 验证码发送通道的<b>日志实现</b>（当前生效；真实网关接入后由新实现取代）。
 *
 * <h2>它做了什么</h2>
 * <p>把「该发给谁、发什么」完整写进应用日志，格式固定为：
 * <pre>
 *   [FORGOT-CODE] 渠道=SMS 目标=138****8888 验证码=483920 有效期=5分钟
 * </pre>
 * 这样在没有网关的环境里，运维 / 测试可以用一条 grep 直接把验证码捞出来：
 * <pre>grep 'FORGOT-CODE' logs/app.log | tail -1</pre>
 *
 * <h2>为什么目标要打码、验证码却打明文</h2>
 * <p>这条日志的<b>存在意义</b>就是「让人能读到验证码」—— 把验证码也打码就等于
 * 什么都没做。而手机号 / 邮箱打码是因为日志的留存期远长于验证码（5 分钟），
 * 且日志会被备份、被归档、被更多人看到：一份「姓名 ↔ 手机号」的完整清单
 * 落在日志里，就是一份现成的钓鱼名单。
 *
 * <h2>换成真实网关时要注意的事</h2>
 * <p>新实现里<b>不要</b>沿用本类的「打明文验证码」做法 ——
 * 这句话在网关实现里就是一条「把用户验证码写进日志」的缺陷。
 * 到时候验证码应当只出现在发给用户的短信 / 邮件正文里。
 */
@Slf4j
@Component
public class LoggingVerificationCodeSender implements VerificationCodeSender {

    @Override
    public void send(String contactType, String target, String code, int expireMinutes) {
        log.info("[FORGOT-CODE] 渠道={} 目标={} 验证码={} 有效期={}分钟",
                ContactRecovery.label(contactType), AccountFormats.maskContact(target), code, expireMinutes);
    }

    // 打码统一走 AccountFormats.maskContact（ ：全库唯一实现）
}
