package com.enterprise.ticket.module.system.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 验证渠道的「有效性」契约 —— 全系统唯一事实源。
 *
 * <h2>为什么需要它：开关开着 ≠ 能发得出去</h2>
 * <p>改造前，「渠道能不能用」只判 {@code sms_verify_enabled} / {@code email_verify_enabled}
 * 两个布尔开关。但这两个开关表达的是<b>管理员的意愿</b>，不是<b>系统当前的能力</b>：
 * 一个全新部署里两个开关的默认值都是「开」，而 SMTP 一个字都没配、
 * 短信网关也还没接入 —— 此时任何一次「发验证码」都必然落空
 * （邮箱验证码被 {@code RoutingVerificationCodeSender} 退回日志，短信则必然走日志）。
 * 于是登录闸门会把一个「连渠道都没有」的用户<b>强制</b>推到绑定页，而他将永远收不到验证码。
 *
 * <p>因此判定升级为「意愿 ∧ 能力」两段合取：
 * <ul>
 *   <li><b>短信可用</b> = 开关开 <b>且</b> 网关已接入（{@link SmsSettings#GATEWAY_INTEGRATED}）；</li>
 *   <li><b>邮箱可用</b> = 开关开 <b>且</b> SMTP 四项配齐（{@link MailSettings.Settings#complete()}）。</li>
 * </ul>
 * 这条推导覆盖了「两个开关都关」这个旧口径 —— 开关全关时两个渠道都不可用，
 * 因此旧有的「全关 ⇒ 找回密码整体不可用 / 不弹绑定引导」语义被完整保留，
 * 且额外把「开关开着但链路没配好」纳入了同一口径。
 *
 * <h2>为什么做成不可变值对象，而不是几个静态布尔方法</h2>
 * <p>「渠道是否可用」有 7 个消费点（登录闸门两处、找回密码四处、个人资料绑定一处）。
 * 若各消费点自己拼 {@code smsEnabled && GATEWAY_INTEGRATED}，就会出现
 * 「页面说渠道不可用、后端却按可用去发码」这类漂移 ——
 * 本项目的 {@code MailSettings} 类注释已经记录过同一种事故。
 * 收敛成一个值对象后，消费点只能<b>整体读取</b>，无法局部重算。
 *
 * <h2>为什么字段里也留着 {@code smsGatewayIntegrated}</h2>
 * <p>正常情况下它恒等于 {@link SmsSettings#GATEWAY_INTEGRATED}（当前 {@code false}）。
 * 做成字段而非直接读常量，是为了让单测能构造「网关已接入」的未来态去覆盖那条分支，
 * 而不必为了测试去改生产常量。
 *
 * <h2>构造开销与「为什么不做缓存」</h2>
 * <p>{@link #of} 需要取一次 SMTP 设置（内含一次 AES-GCM 解密），而登录过滤器会按请求调用它。
 * 解密耗时在微秒量级，相对于过滤器中「每请求查库加载用户」的一次数据库往返可以忽略。
 * 刻意<b>不做第二层缓存</b>：配置缓存本身已在「保存时立即刷新」与「60 秒兜底刷新」两处失效，
 * 再叠一层派生缓存只会多出一个失效点 —— 那正是本类想要消灭的那类缺陷。
 */
public record VerificationChannelStatus(
        boolean smsSwitchedOn,
        boolean emailSwitchedOn,
        boolean smsGatewayIntegrated,
        boolean smtpComplete,
        String smtpIncompleteReason) {

    /**
     * 由「开关状态 + 当前 SMTP 设置」装配。
     *
     * @param smsSwitchedOn   {@code sms_verify_enabled}
     * @param emailSwitchedOn {@code email_verify_enabled}
     * @param mail            生效中的 SMTP 设置（可为 {@code null}，表示一行都没配）
     */
    public static VerificationChannelStatus of(boolean smsSwitchedOn, boolean emailSwitchedOn,
                                               MailSettings.Settings mail) {
        boolean complete = mail != null && mail.complete();
        String reason = mail == null ? "未配置邮件服务器（SMTP）" : mail.incompletenessReason();
        return new VerificationChannelStatus(smsSwitchedOn, emailSwitchedOn,
                SmsSettings.GATEWAY_INTEGRATED, complete, reason);
    }

    /** 短信渠道此刻是否真的能发出验证码（开关开 <b>且</b> 网关已接入） */
    public boolean smsUsable() {
        return smsSwitchedOn && smsGatewayIntegrated;
    }

    /** 邮箱渠道此刻是否真的能发出验证码（开关开 <b>且</b> SMTP 配齐） */
    public boolean emailUsable() {
        return emailSwitchedOn && smtpComplete;
    }

    /**
     * 是否至少有一个渠道真的能发出验证码。
     *
     * <p>这是「登录强制绑定闸门」与「找回密码整体可用」的共同判据 ——
     * 为 {@code false} 时两者都必须放行 / 关闭：没有任何渠道能验证归属，
     * 强制绑定只会把用户堵死，而开放找回密码只会让用户白等一条永远不来的验证码。
     */
    public boolean anyUsable() {
        return smsUsable() || emailUsable();
    }

    /** 指定渠道此刻是否可用；取值非法一律返回 {@code false} */
    public boolean usable(String contactType) {
        String channel = ContactRecovery.normalizeContactType(contactType);
        if (ContactRecovery.CONTACT_SMS.equals(channel)) {
            return smsUsable();
        }
        if (ContactRecovery.CONTACT_EMAIL.equals(channel)) {
            return emailUsable();
        }
        return false;
    }

    /** 此刻真正可用的渠道编码（顺序固定 SMS → EMAIL，供界面稳定渲染） */
    public List<String> usableChannels() {
        List<String> out = new ArrayList<>(2);
        if (smsUsable()) {
            out.add(ContactRecovery.CONTACT_SMS);
        }
        if (emailUsable()) {
            out.add(ContactRecovery.CONTACT_EMAIL);
        }
        return out;
    }

    /**
     * 渠道不可用的可读原因；渠道可用时返回 {@code null}。
     *
     * <h2>为什么文案由服务端给</h2>
     * <p>「不可用」有三种互不相干的成因（管理员关掉了 / 网关未接入 / SMTP 未配齐），
     * 前端若自己拼文案，就必须复制同样的三分支判断 ——
     * 一旦后端新增第四种成因，前端会静默显示一句错的话。
     * 服务端给文案、前端只负责展示，是唯一不会漂移的分工。
     *
     * <h2>为什么「开关关闭」优先于「能力未就绪」</h2>
     * <p>两种成因同时成立时（开关关着、SMTP 也没配），对用户最有意义的说法是
     * 「管理员已关闭」—— 它是一个明确的、可找管理员解决的状态；
     * 而「SMTP 未配置」是管理员侧的运维细节，说给普通员工听既无用也容易引起误解。
     */
    public String unusableReason(String contactType) {
        String channel = ContactRecovery.normalizeContactType(contactType);
        if (ContactRecovery.CONTACT_SMS.equals(channel)) {
            if (smsUsable()) {
                return null;
            }
            return smsSwitchedOn
                    ? "短信网关尚未接入，暂不能通过手机号接收验证码"
                    : "管理员已关闭手机验证，暂不能通过手机号接收验证码";
        }
        if (ContactRecovery.CONTACT_EMAIL.equals(channel)) {
            if (emailUsable()) {
                return null;
            }
            if (!emailSwitchedOn) {
                return "管理员已关闭邮箱验证，暂不能通过邮箱接收验证码";
            }
            String detail = smtpIncompleteReason == null ? "" : "（" + smtpIncompleteReason + "）";
            return "邮件服务器尚未配置完成" + detail + "，暂不能通过邮箱接收验证码";
        }
        return "不支持的验证方式";
    }

    /**
     * 渠道编码 → 不可用原因（只含不可用渠道）。
     *
     * <p>供前端一次性取全：绑定页与个人资料页据此把对应输入框置灰并展示原因，
     * 不必对每个渠道各发一次询问。
     */
    public Map<String, String> unusableReasons() {
        Map<String, String> out = new LinkedHashMap<>(2);
        for (String channel : List.of(ContactRecovery.CONTACT_SMS, ContactRecovery.CONTACT_EMAIL)) {
            String reason = unusableReason(channel);
            if (reason != null) {
                out.put(channel, reason);
            }
        }
        return out;
    }
}
