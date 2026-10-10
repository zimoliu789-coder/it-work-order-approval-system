package com.enterprise.ticket.module.system.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证渠道有效性契约（P0 绑定闸门 · 方案② 治本逻辑）。
 *
 * <h2>为什么这条规则必须有独立单测</h2>
 * <p>它同时决定四件事的行为：登录强制绑定闸门（两处）、找回密码整体可用性、
 * 绑定联系方式能否发码。改造前的口径是「开关开着就算可用」，而这恰恰是缺陷本身 ——
 * 一个 SMTP 都没配齐的全新部署里两个开关默认开着，于是用户被强制推到绑定页，
 * 却永远收不到验证码。本类把「意愿 ∧ 能力」这条合取钉进构建期，
 * 避免它将来又被简化回「只看开关」。
 */
@DisplayName("验证渠道有效性契约")
class VerificationChannelStatusTest {

    /** 可以让一封信真的发出去的四项齐备 */
    private static final MailSettings.Settings MAIL_READY =
            new MailSettings.Settings("smtp.example.com", 465, "it@example.com", "auth-code", "", true);

    /** 一行都没配（host / username / password 全空） */
    private static final MailSettings.Settings MAIL_EMPTY =
            new MailSettings.Settings("", 465, "", "", "", true);

    // ------------------------------------------------------------------
    // 装配
    // ------------------------------------------------------------------

    @Test
    @DisplayName("of()：SMTP 四项齐备 → complete，且不产生原因")
    void ofReadsMailCompleteness() {
        VerificationChannelStatus status = VerificationChannelStatus.of(true, true, MAIL_READY);

        assertTrue(status.smtpComplete());
        assertNull(status.smtpIncompleteReason());
        assertTrue(status.emailUsable());
    }

    @Test
    @DisplayName("of()：SMTP 未配齐 → 不 complete，并带上可直接展示的原因")
    void ofCarriesIncompletenessReason() {
        VerificationChannelStatus status = VerificationChannelStatus.of(true, true, MAIL_EMPTY);

        assertFalse(status.smtpComplete());
        assertEquals("未填写 SMTP 服务器地址", status.smtpIncompleteReason());
        assertFalse(status.emailUsable());
    }

    @Test
    @DisplayName("of()：SMTP 设置为 null（一行都没配）按不完整处理，不抛异常")
    void ofToleratesNullMail() {
        VerificationChannelStatus status = VerificationChannelStatus.of(false, true, null);

        assertFalse(status.smtpComplete());
        assertFalse(status.emailUsable());
        assertFalse(status.anyUsable());
    }

    // ------------------------------------------------------------------
    // 可用性：意愿 ∧ 能力
    // ------------------------------------------------------------------

    @Test
    @DisplayName("开关关掉：能力再完备也不可用")
    void switchOffWinsOverCapability() {
        VerificationChannelStatus status =
                new VerificationChannelStatus(false, false, true, true, null);

        assertFalse(status.smsUsable());
        assertFalse(status.emailUsable());
        assertFalse(status.anyUsable());
        assertTrue(status.usableChannels().isEmpty());
    }

    @Test
    @DisplayName("链路未就绪：开关开着也不可用（本轮修复的核心口径）")
    void capabilityMissingWinsOverSwitch() {
        VerificationChannelStatus status =
                new VerificationChannelStatus(true, true, false, false, "未填写 SMTP 服务器地址");

        // 短信：开关开，但网关尚未接入
        assertFalse(status.smsUsable());
        // 邮箱：开关开，但 SMTP 未配齐
        assertFalse(status.emailUsable());
        assertFalse(status.anyUsable());
        assertTrue(status.usableChannels().isEmpty());
    }

    @Test
    @DisplayName("只有邮箱一条链路就绪：anyUsable 为真，渠道列表只含 EMAIL")
    void anyUsableWhenSingleChannelReady() {
        VerificationChannelStatus status =
                new VerificationChannelStatus(false, true, false, true, null);

        assertFalse(status.smsUsable());
        assertTrue(status.emailUsable());
        assertTrue(status.anyUsable());
        assertEquals(List.of(ContactRecovery.CONTACT_EMAIL), status.usableChannels());
    }

    @Test
    @DisplayName("usable()：非法 / 空白渠道编码一律 false，不做兜底放行")
    void usableRejectsUnknownChannel() {
        VerificationChannelStatus ready =
                new VerificationChannelStatus(true, true, true, true, null);

        assertFalse(ready.usable(null));
        assertFalse(ready.usable(""));
        assertFalse(ready.usable("WECHAT"));
        assertTrue(ready.usable("sms"));
        assertTrue(ready.usable(" Email "));
    }

    // ------------------------------------------------------------------
    // 不可用原因
    // ------------------------------------------------------------------

    @Test
    @DisplayName("unusableReason()：三种成因给出不同文案，可用渠道返回 null")
    void unusableReasonDistinguishesCauses() {
        // ① 管理员关闭（开关关）
        VerificationChannelStatus off =
                new VerificationChannelStatus(false, false, true, true, null);
        assertTrue(off.unusableReason(ContactRecovery.CONTACT_SMS).contains("管理员已关闭手机验证"));
        assertTrue(off.unusableReason(ContactRecovery.CONTACT_EMAIL).contains("管理员已关闭邮箱验证"));

        // ② 短信网关尚未接入（开关开着，能力缺失）
        // ③ SMTP 尚未配置完成（开关开着，能力缺失）—— 原因里要带上具体缺哪一项
        // 两个渠道的开关都开着、但两条链路都没就绪，正是「开关开着 ≠ 能发出去」的核心场景；
        // 若这里把 emailSwitchedOn 传成 false，邮箱会落入「管理员已关闭」分支而测不到 ③。
        VerificationChannelStatus capabilityMissing =
                new VerificationChannelStatus(true, true, false, false, "未填写 SMTP 服务器地址");
        assertTrue(capabilityMissing.unusableReason(ContactRecovery.CONTACT_SMS).contains("短信网关尚未接入"));

        String emailReason = capabilityMissing.unusableReason(ContactRecovery.CONTACT_EMAIL);
        assertTrue(emailReason.contains("邮件服务器尚未配置完成"), emailReason);
        assertTrue(emailReason.contains("未填写 SMTP 服务器地址"), emailReason);

        // ④ 可用渠道不产生原因
        VerificationChannelStatus ready =
                new VerificationChannelStatus(true, true, true, true, null);
        assertNull(ready.unusableReason(ContactRecovery.CONTACT_SMS));
        assertNull(ready.unusableReason(ContactRecovery.CONTACT_EMAIL));
    }

    @Test
    @DisplayName("unusableReasons()：只登记不可用的渠道，可用渠道不出现")
    void unusableReasonsOnlyListsBrokenChannels() {
        VerificationChannelStatus status =
                new VerificationChannelStatus(true, true, false, true, null);

        Map<String, String> reasons = status.unusableReasons();

        assertEquals(1, reasons.size());
        assertTrue(reasons.containsKey(ContactRecovery.CONTACT_SMS));
        assertFalse(reasons.containsKey(ContactRecovery.CONTACT_EMAIL));
    }
}
