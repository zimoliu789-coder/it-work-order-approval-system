package com.enterprise.ticket.module.auth.support;

import com.enterprise.ticket.module.auth.service.VerificationCodeSender;
import com.enterprise.ticket.module.system.service.SystemConfigMailService;
import com.enterprise.ticket.module.system.support.ContactRecovery;
import com.enterprise.ticket.module.system.support.MailSettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 验证码投递<b>路由</b>：邮箱走 SMTP（配好时），其余一律走日志。
 *
 * <h2>为什么需要一层路由，而不是「配好就把日志实现换掉」</h2>
 * <p>SMTP 是<b>运行时可变的配置</b>：管理员可能今天配好、明天为了排障清空，
 * 也可能只在生产配、测试环境不配。若把「用哪个实现」固定在启动期的 Bean 选择上，
 * 就会出现两种都不对的局面：
 * <ul>
 *   <li>固定用邮件实现 + SMTP 没配 → 每次发码都失败，用户既收不到码、
 *       运维也没法从日志里捞（因为日志实现已经不在链路上了）；</li>
 *   <li>固定用日志实现 + SMTP 已配好 → 用户永远收不到真邮件，功能形同没做。</li>
 * </ul>
 * 因此「用哪条通道」必须是<b>每次发送时按当前配置判定</b>，而不是装配时的静态选择。
 *
 * <h2>降级方向是刻意的</h2>
 * <p>SMTP 不完整时<b>不抛异常</b>，而是回落到日志输出。理由：
 * 邮件通道没配好属于「部署尚未完成」，不是「本次操作错误」。
 * 若抛异常，找回密码会在 SMTP 未配置的环境里<b>完全不可用</b> ——
 * 而这类环境恰恰是开发 / 测试 / 演示环境，是功能最需要可走通的地方。
 * 回落日志后，验证码仍能被运维用一条 grep 捞出来（见 {@code LoggingVerificationCodeSender}）。
 *
 * <h2>为什么标 {@code @Primary}</h2>
 * <p>本类与 {@code LoggingVerificationCodeSender} 都实现 {@link VerificationCodeSender}，
 * 业务侧（找回密码、绑定联系方式）按接口注入。标 {@code @Primary} 让注入拿到路由，
 * 而日志实现退化为路由内部的一个「兜底通道」——调用方不需要知道通道是怎么选的。
 */
@Slf4j
@Primary
@Component
@RequiredArgsConstructor
public class RoutingVerificationCodeSender implements VerificationCodeSender {

    /** 兜底通道：日志输出（开发 / 测试 / 未配置 SMTP 时的唯一出口） */
    private final LoggingVerificationCodeSender loggingSender;

    private final SystemConfigMailService mailService;

    @Override
    public void send(String contactType, String target, String code, int expireMinutes) {
        String channel = ContactRecovery.normalizeContactType(contactType);

        if (ContactRecovery.CONTACT_EMAIL.equals(channel)) {
            MailSettings.Settings settings = mailService.effectiveSettings();
            if (settings.complete()) {
                // 发送失败会抛 BusinessException（原因已归类），调用方据此中止流程 ——
                // 「界面提示已发送、实际没发出去」比直接报错更糟：用户会一直等一条永远不来的邮件。
                mailService.sendVerificationCode(target, code, expireMinutes);
                return;
            }
            log.info("SMTP 未配置完整（{}），邮箱验证码改由日志输出", settings.incompletenessReason());
        }

        // 短信通道未接入（为预留），因此这里必然落到日志实现
        loggingSender.send(contactType, target, code, expireMinutes);
    }
}
