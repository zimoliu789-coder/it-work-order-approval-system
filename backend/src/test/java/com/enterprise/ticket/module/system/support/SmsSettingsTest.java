package com.enterprise.ticket.module.system.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 短信参数的「问题清单」判定（P19-F 需求五的「发送测试短信」）。
 *
 * <h2>为什么钉的是「清单」而不是「能不能发」</h2>
 * <p>短信没有跨服务商通用的完整性口径（见 {@link SmsSettings} 类注释）。
 * 一个布尔量只能回答「能不能发」，而管理员点「发送测试短信」时唯一想知道的是
 * <b>差哪几项</b>。因此这里逐条断言清单内容 —— 包括「一次把所有缺项都列出来」，
 * 因为「修一项、再报下一项」会让管理员来回点五次。
 *
 * <h2>边界刻意不覆盖「签名是否备案 / 模板是否存在」</h2>
 * <p>这两件事只有服务商能判。本地假装能判，就会把「填得全都对、服务商说不行」
 * 误报成「配置错误」，把管理员引向错误的方向。
 */
@DisplayName("短信参数：问题清单")
class SmsSettingsTest {

    private static final String SECRET = "secret-value";

    private static SmsSettings.Settings full() {
        return new SmsSettings.Settings("ALIYUN", "LTAI-xxx", SECRET, "设备借用", "SMS_123456");
    }

    @Test
    @DisplayName("五项都填齐且服务商可识别时没有问题")
    void complete() {
        List<String> problems = full().problems();
        assertTrue(problems.isEmpty(), "不应有问题，实际：" + problems);
    }

    @Test
    @DisplayName("空值配置应一次列出全部 5 项缺项（不是只报第一项）")
    void allMissing() {
        SmsSettings.Settings empty = new SmsSettings.Settings("", "", "", "", "");
        List<String> problems = empty.problems();
        assertEquals(5, problems.size(), "应一次报出全部缺项，实际：" + problems);
        assertTrue(problems.get(0).contains("服务商"));
        assertTrue(problems.stream().anyMatch(p -> p.contains("AccessKey ID")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("AccessKey Secret")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("短信签名")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("模板代码")));
    }

    @Test
    @DisplayName("null 值等同于未填（配置行缺失时不要抛 NPE）")
    void nullSafe() {
        SmsSettings.Settings nulls = new SmsSettings.Settings(null, null, null, null, null);
        assertEquals(5, nulls.problems().size());
    }

    @Test
    @DisplayName("服务商编码不在枚举内时单独报「无法识别」，且不重复报「未选择」")
    void unknownProvider() {
        SmsSettings.Settings bad = new SmsSettings.Settings("DINGTALK", "id", SECRET, "签名", "TPL");
        List<String> problems = bad.problems();
        assertEquals(1, problems.size(), "只有服务商一项有问题，实际：" + problems);
        assertTrue(problems.get(0).contains("无法识别"));
        assertTrue(problems.get(0).contains("ALIYUN"), "提示里应给出可选值，便于照着改");
    }

    @Test
    @DisplayName("服务商编码大小写不敏感（下拉值来自后端，但直接改库/脚本时不该被拒）")
    void providerCaseInsensitive() {
        SmsSettings.Settings lower = new SmsSettings.Settings("aliyun", "id", SECRET, "签名", "TPL");
        assertTrue(lower.problems().isEmpty());
    }

    @Test
    @DisplayName("密钥解密失败（null）被报成「未填写或无法解密」，而不是抛异常")
    void undecryptableSecret() {
        SmsSettings.Settings broken = new SmsSettings.Settings("ALIYUN", "id", null, "签名", "TPL");
        List<String> problems = broken.problems();
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("无法解密"),
                "要提示「重新保存」，否则管理员会以为是填漏了");
    }

    @Test
    @DisplayName("服务商显示名走同一份映射（未选择时为空串）")
    void providerLabel() {
        assertEquals("阿里云短信", full().providerLabel());
        assertEquals("", new SmsSettings.Settings("", "id", SECRET, "签名", "TPL").providerLabel());
        assertTrue(SmsSettings.Settings.class.isRecord(),
                "Settings 必须是 record：它被当作不可变值对象在服务层与控制器之间传递");
    }
}
