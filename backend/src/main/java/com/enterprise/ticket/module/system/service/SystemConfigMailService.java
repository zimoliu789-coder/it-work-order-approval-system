package com.enterprise.ticket.module.system.service;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.system.support.MailSettings;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import javax.net.ssl.SSLException;

/**
 * 邮件发送（）：发送测试邮件 + 邮箱验证码真实投递。
 *
 * <h2>为什么运行时现建 {@code JavaMailSenderImpl}，而不用 Spring 的自动配置</h2>
 * <p>Spring Boot 的 {@code spring.mail.*} 在启动时把 SMTP 参数固定成单例 Bean，
 * 而本系统的 SMTP 参数存在 {@code system_config} 表里、<b>运行时可改</b>：
 * 管理员在配置页填完点「发送测试邮件」，期望立刻用新参数试一次；
 * 此时若走启动期单例，「没重启就不生效」会被理解成「保存失败」，是最典型的假故障。
 * 因此每次发送按当前生效参数现建 sender —— 代价是每次多建一个对象，
 * 而发信本身是秒级 IO，这点开销可以忽略。
 *
 * <h2>失败必须给可读原因</h2>
 * <p>「发送测试邮件」这个功能的全部价值就在于<b>把配置错误指出来</b>。
 * 若只回一句「发送失败」，管理员只能靠猜。因此这里把底层异常归类成三类最常见的原因
 * （认证失败 / 连不上 / SSL 不匹配），见 {@link #classify(Exception)}。
 *
 * <h2>为什么没有接口（interface）</h2>
 * <p>与 {@code VerificationCodeSender} 不同：那个接口的存在理由是「日志实现 → 网关实现」
 * 必然要换；而「用 SMTP 发信」只有一种做法，将来也不会出现第二种实现。
 * 为一次性可替换性提前抽接口只会增加一层无信息的间接。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemConfigMailService {

    /**
     * 连接 / 读取超时（毫秒）。
     *
     * <p>必须显式设置：JavaMail 默认<b>无限等待</b>。SMTP 地址填错、或防火墙静默丢包时，
     * 「发送测试邮件」会一直转圈直到浏览器自己超时 —— 管理员看到的是「按钮点了没反应」，
     * 而不是「连不上」。10 秒是「公网服务商正常建连」与「人工可接受等待」的折中。
     */
    private static final int TIMEOUT_MS = 10_000;

    private final SystemConfigService systemConfigService;

    /** 当前生效的 SMTP 设置（授权码已解密） */
    public MailSettings.Settings effectiveSettings() {
        return systemConfigService.mailSettings();
    }

    /**
     * 发送邮箱验证码（供 {@code RoutingVerificationCodeSender} 调用）。
     *
     * @throws BusinessException 发送失败（原因已归类为可读文案）
     */
    public void sendVerificationCode(String to, String code, int expireMinutes) {
        MailSettings.Settings settings = effectiveSettings();
        String siteName = systemConfigService.siteName();
        String subject = "【" + siteName + "】邮箱验证码";
        String body = "您的验证码是：" + code + "\n\n"
                + "有效期 " + expireMinutes + " 分钟，请勿转发给他人。\n"
                + "如果这不是您本人的操作，请忽略本邮件。\n\n"
                + siteName;
        send(settings, to, subject, body);
    }

    /**
     * 发送测试邮件（配置页按钮）。
     *
     * <p>正文里带上本次实际使用的参数（授权码只显示长度与掩码，不回显明文），
     * 便于管理员核对「测的到底是不是我想测的那套配置」。
     *
     * @param to       收件人
     * @param settings 本次要试的设置（允许来自<b>未保存</b>的表单值）
     */
    public void sendTestMail(String to, MailSettings.Settings settings) {
        String siteName = systemConfigService.siteName();
        String subject = "【" + siteName + "】SMTP 配置测试邮件";
        String body = "这是一封测试邮件，说明当前 SMTP 配置可以正常发信。\n\n"
                + "服务器：" + settings.host() + "\n"
                + "端口：" + settings.port() + "\n"
                + "SSL：" + (settings.ssl() ? "已启用" : "未启用") + "\n"
                + "发件邮箱：" + settings.username() + "\n"
                + "发件人显示名：" + settings.displayName() + "\n"
                + "授权码：已填写（" + settings.password().length() + " 位，不回显）\n\n"
                + "收到本邮件后即可在系统参数页保存配置，邮箱验证码将改为真实发送。\n\n"
                + siteName;
        send(settings, to, subject, body);
    }

    /**
     * 发送运维告警邮件（ 新增）。
     *
     * <h2>为什么需要它：告警不能只有站内消息一个出口</h2>
     * <p> [185] 行明确要求「如果配置了短信 / 邮箱通知，同时发短信和邮件告警」。
     * 站内消息的接收前提是「有人打开系统看」—— 而主备断连、复制中断这类故障
     * 恰恰最容易发生在<b>没人看系统的深夜</b>。邮箱是唯一能在无人值守时把消息
     * 推到管理员手机上的通道，因此必须真的发出去，而不是只在站内留一条
     * 「已通知（邮件未发送）」的记录。
     *
     * <h2>与 {@link #sendVerificationCode} / {@link #sendTestMail} 的区别</h2>
     * <p>前两者的正文是固定模板（验证码 / 配置核对信息），本方法的正文由调用方给定 ——
     * 因为告警内容跨模块（主备、备份、后续可能还有磁盘、证书），
     * 把模板写在这里会迫使每个模块来改这个类。
     *
     * <p>本方法<b>不吞异常</b>：调用方（{@code HaAlertNotifier}）需要知道
     * 「这封信到底发出去没有」，才能如实记录投递结果；
     * 在该调用方内部会捕获并降级为日志，因为「邮件发不出去」绝不能
     * 连带把「站内消息已发出」这件事回滚掉。
     */
    public void sendAlert(String to, String subject, String body) {
        send(effectiveSettings(), to, subject, body);
    }

    /**
     * 实际发送。
     *
     * @throws BusinessException 参数不完整或发送失败
     */
    private void send(MailSettings.Settings settings, String to, String subject, String body) {
        String reason = settings.incompletenessReason();
        if (reason != null) {
            throw new BusinessException(ErrorCode.CONFIG_VALUE_INVALID, "SMTP 配置不完整：" + reason);
        }
        try {
            JavaMailSenderImpl sender = build(settings);
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(settings.fromAddress(), settings.displayName());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body, false);
            sender.send(message);
            log.info("邮件已发送：收件人 {}，SMTP {}:{}（SSL {}）",
                    maskTarget(to), settings.host(), settings.port(), settings.ssl());
        } catch (MailException e) {
            // 认证失败只记主机与账号，绝不记授权码
            log.warn("邮件发送失败：收件人 {}，SMTP {}:{}，原因 {}",
                    maskTarget(to), settings.host(), settings.port(), rootMessage(e));
            throw classify(e);
        } catch (Exception e) {
            log.warn("邮件发送异常：收件人 {}，SMTP {}:{}，原因 {}",
                    maskTarget(to), settings.host(), settings.port(), rootMessage(e));
            throw new BusinessException(ErrorCode.MAIL_SEND_FAILED, "邮件发送失败：" + rootMessage(e));
        }
    }

    /** 按生效参数构造 sender（每次新建，见类注释第一段） */
    private JavaMailSenderImpl build(MailSettings.Settings settings) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(settings.host());
        sender.setPort(settings.port());
        sender.setUsername(settings.username());
        sender.setPassword(settings.password());
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());

        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.timeout", String.valueOf(TIMEOUT_MS));
        props.put("mail.smtp.connectiontimeout", String.valueOf(TIMEOUT_MS));
        props.put("mail.smtp.writetimeout", String.valueOf(TIMEOUT_MS));
        if (settings.ssl()) {
            // 465 端口的隐式 SSL：建连即 TLS，不做 STARTTLS 升级
            props.put("mail.smtp.ssl.enable", "true");
            props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
            props.put("mail.smtp.socketFactory.fallback", "false");
            props.put("mail.smtp.ssl.checkserveridentity", "false");
        } else {
            // 显式关闭：避免服务商在 25/587 上以 STARTTLS 协商出与「SSL 开关」不符的加密路径
            props.put("mail.smtp.starttls.enable", "false");
        }
        return sender;
    }

    /**
     * 把底层异常归类成三类最常见的原因 —— 这是本功能的核心价值（见类注释第二段）。
     *
     * <p>分类依据是异常链上出现的具体异常类型，而不是异常文案：
     * 不同厂商的 SMTP 服务器返回的提示文本差异很大，匹配文案必然漏判。
     */
    private BusinessException classify(Exception e) {
        String root = rootMessage(e);

        if (e instanceof MailAuthenticationException
                || findCause(e, jakarta.mail.AuthenticationFailedException.class) != null) {
            return new BusinessException(ErrorCode.MAIL_SEND_FAILED,
                    "SMTP 认证失败：请核对「发件邮箱账号」与「SMTP 授权码」。" + detail(root));
        }
        if (findCause(e, UnknownHostException.class) != null) {
            return new BusinessException(ErrorCode.MAIL_SEND_FAILED,
                    "无法解析 SMTP 服务器地址：请检查「SMTP 服务器地址」是否拼写正确。" + detail(root));
        }
        if (findCause(e, ConnectException.class) != null) {
            return new BusinessException(ErrorCode.MAIL_SEND_FAILED,
                    "无法连接 SMTP 服务器：请确认「SMTP 端口」与服务商一致，并检查防火墙 / 网络出网策略。"
                            + detail(root));
        }
        if (findCause(e, SocketTimeoutException.class) != null) {
            return new BusinessException(ErrorCode.MAIL_SEND_FAILED,
                    "连接 SMTP 服务器超时：地址或端口可能被防火墙拦截（" + TIMEOUT_MS / 1000
                            + " 秒内未建立连接）。" + detail(root));
        }
        if (findCause(e, SSLException.class) != null) {
            return new BusinessException(ErrorCode.MAIL_SEND_FAILED,
                    "SSL 握手失败：端口与「启用 SSL 加密」开关不匹配（465 需开启 SSL；用 587 请先确认服务商支持）。"
                            + detail(root));
        }
        if (e instanceof MailSendException) {
            return new BusinessException(ErrorCode.MAIL_SEND_FAILED,
                    "SMTP 服务器拒绝了本次发送：请核对发件账号是否已开通 SMTP 服务、收件地址是否存在。"
                            + detail(root));
        }
        return new BusinessException(ErrorCode.MAIL_SEND_FAILED, "邮件发送失败：" + root);
    }

    /** 在异常链上找指定类型的原因 */
    private Throwable findCause(Throwable e, Class<? extends Throwable> type) {
        Throwable current = e;
        int guard = 0;
        while (current != null && guard++ < 20) {
            if (type.isInstance(current)) {
                return current;
            }
            current = current.getCause();
        }
        return null;
    }

    /** 取最内层可读信息（用于附加到分类文案后面，便于排障） */
    private String rootMessage(Throwable e) {
        Throwable current = e;
        int guard = 0;
        while (current.getCause() != null && guard++ < 20) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return StringUtils.hasText(message) ? message.trim() : current.getClass().getSimpleName();
    }

    private String detail(String root) {
        return "（底层信息：" + root + "）";
    }

    /**
     * 收件人打码：日志与异常文案都不落完整邮箱。
     *
     * <p>与验证码日志同理 —— 日志的留存期远长于一次排障，完整的「邮箱清单」
     * 落在日志里就是一份现成的钓鱼名单。
     */
    private String maskTarget(String target) {
        return com.enterprise.ticket.common.util.AccountFormats.maskContact(target);
    }
}
