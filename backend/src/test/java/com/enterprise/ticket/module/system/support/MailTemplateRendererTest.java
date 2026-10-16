package com.enterprise.ticket.module.system.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 邮件模板渲染单测（V52）
 *
 * <p>这个类只有三件事，但每一件都有过真实的坑：
 * <ol>
 *   <li><b>留空回落默认</b> —— 保证「没配过模板」的部署行为与抽模板之前逐字一致（零回归）；</li>
 *   <li><b>未知占位符原样保留</b> —— 管理员笔误要看得见，不能静默少一段；</li>
 *   <li><b>主题必须清除换行</b> —— 主题属于邮件头，换行可被用于<b>邮件头注入</b>。</li>
 * </ol>
 */
class MailTemplateRendererTest {

    private static final Map<String, String> VARS = Map.of(
            "siteName", "设备借用工单系统",
            "code", "654321",
            "minutes", "5");

    // ------------------------------------------------------------------
    // 1. 留空回落默认
    // ------------------------------------------------------------------

    @Test
    @DisplayName("模板为空 ⇒ 用默认文案，且占位符被正确替换")
    void fallsBackToDefaultWhenTemplateBlank() {
        String subject = MailTemplateRenderer.subject(null, MailSettings.DEFAULT_SUBJECT_VERIFY, VARS);
        assertEquals("【设备借用工单系统】邮箱验证码", subject);

        String body = MailTemplateRenderer.body("   ", MailSettings.DEFAULT_BODY_VERIFY, VARS);
        assertTrue(body.contains("654321"), "验证码必须被替换进去");
        assertTrue(body.contains("5 分钟"), "有效期必须被替换进去");
        assertFalse(body.contains("{code}"), "默认文案里的占位符不该残留");
    }

    @Test
    @DisplayName("默认文案与抽模板之前的写死文案一致（零回归）")
    void defaultTemplatesMatchLegacyHardcodedText() {
        String body = MailTemplateRenderer.body(null, MailSettings.DEFAULT_BODY_VERIFY, VARS);
        // 抽模板之前 sendVerificationCode 里的原文
        String legacy = "您的验证码是：654321\n\n"
                + "有效期 5 分钟，请勿转发给他人。\n"
                + "如果这不是您本人的操作，请忽略本邮件。\n\n"
                + "设备借用工单系统";
        assertEquals(legacy, body);
    }

    // ------------------------------------------------------------------
    // 2. 占位符替换
    // ------------------------------------------------------------------

    @Test
    @DisplayName("自定义模板里的占位符逐处替换")
    void replacesPlaceholders() {
        String body = MailTemplateRenderer.body(
                "你好，{code} 是你的验证码（{minutes} 分钟内有效）。—— {siteName}", null, VARS);
        assertEquals("你好，654321 是你的验证码（5 分钟内有效）。—— 设备借用工单系统", body);
    }

    @Test
    @DisplayName("未知占位符**原样保留**（笔误要看得见，不能静默少一段）")
    void keepsUnknownPlaceholders() {
        String body = MailTemplateRenderer.body("验证码：{Code} / {code}", null, VARS);
        assertEquals("验证码：{Code} / 654321", body);
    }

    @Test
    @DisplayName("取值里含 $ 或 \\ 时不被当成正则组引用")
    void dollarSignInValueIsLiteral() {
        Map<String, String> vars = new HashMap<>();
        vars.put("title", "备份失败：路径 C:\\data\\$tmp\\x");
        String subject = MailTemplateRenderer.subject("{title}", null, vars);
        assertTrue(subject.contains("C:\\data\\$tmp\\x"), "取值里的 $ 与 \\ 必须原样输出");
    }

    @Test
    @DisplayName("取值缺失（map 里没有这个键）时保留占位符，不变成空")
    void missingVarKeepsPlaceholder() {
        String body = MailTemplateRenderer.body("{code}|{unknown}", null, VARS);
        assertEquals("654321|{unknown}", body);
    }

    // ------------------------------------------------------------------
    // 3. 主题净化（邮件头注入防护）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("模板里带换行 ⇒ 主题被压成单行（防邮件头注入）")
    void stripsNewlinesFromSubjectTemplate() {
        String subject = MailTemplateRenderer.subject(
                "【{siteName}】验证码\nBcc: attacker@evil.com", null, VARS);

        assertFalse(subject.contains("\n"), "主题里绝不能残留换行");
        assertFalse(subject.contains("\r"), "主题里绝不能残留回车");
        assertTrue(subject.contains("Bcc: attacker@evil.com"), "内容本身保留，只是不再换行");
    }

    @Test
    @DisplayName("占位符**取值**里带换行同样会被清掉（只净化模板挡不住）")
    void stripsNewlinesComingFromVariableValues() {
        Map<String, String> vars = new HashMap<>();
        vars.put("title", "磁盘告警\r\nX-Injected: 1");
        String subject = MailTemplateRenderer.subject("{title}", null, vars);

        assertFalse(subject.contains("\r"));
        assertFalse(subject.contains("\n"));
    }

    @Test
    @DisplayName("制表符与其它控制字符也一并清掉")
    void stripsControlCharacters() {
        assertEquals("a b", MailTemplateRenderer.sanitizeSubject("a\t\u0000\u0007b"));
    }

    @Test
    @DisplayName("净化后两端空白被去掉")
    void trimsSubject() {
        assertEquals("标题", MailTemplateRenderer.sanitizeSubject("  \n 标题 \n "));
    }

    // ------------------------------------------------------------------
    // 4. 长度上限
    // ------------------------------------------------------------------

    @Test
    @DisplayName("主题超长被截断（否则会被各家邮箱客户端随意截）")
    void truncatesLongSubject() {
        String subject = MailTemplateRenderer.subject("A".repeat(500), null, VARS);
        assertTrue(subject.length() <= MailTemplateRenderer.MAX_SUBJECT_LENGTH + 20, "截断后不应远超上限");
        assertTrue(subject.contains("已截断"), "截断要留痕，便于发现「模板写太长」");
    }

    @Test
    @DisplayName("正文超长被截断（防一条配置把邮件撑成几 MB）")
    void truncatesLongBody() {
        String body = MailTemplateRenderer.body("B".repeat(MailTemplateRenderer.MAX_BODY_LENGTH + 100), null, VARS);
        assertTrue(body.contains("已截断"));
        assertTrue(body.length() <= MailTemplateRenderer.MAX_BODY_LENGTH + 20);
    }

    @Test
    @DisplayName("模板与默认值都为空 ⇒ 返回空串，不抛异常")
    void emptyEverything() {
        assertEquals("", MailTemplateRenderer.body(null, null, VARS));
        assertEquals("", MailTemplateRenderer.subject(null, null, VARS));
    }
}
