package com.enterprise.ticket.module.system.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 联系方式与找回密码的纯逻辑契约（P0 绑定闸门 · 方案①+②）。
 *
 * <h2>为什么「是否需要强制绑定」这条真值表要单独钉住</h2>
 * <p>它有两个消费点：登录响应下发的一次性标志（{@code AuthService}）与每请求闸门
 * （{@code JwtAuthenticationFilter}）。两者必须给出同一个答案 ——
 * 历史上内置超管豁免只加在登录响应一侧，结果是「登录时没弹、刷新页面后被拦住」。
 * 把真值表钉在本类的纯函数上，任何一侧的改动只要偏离契约就会立刻红。
 */
@DisplayName("联系方式与找回密码契约")
class ContactRecoveryTest {

    // ------------------------------------------------------------------
    // 渠道编码
    // ------------------------------------------------------------------

    @Test
    @DisplayName("渠道编码归一化：忽略大小写与首尾空白，非法返回 null")
    void normalizeContactType() {
        assertEquals(ContactRecovery.CONTACT_SMS, ContactRecovery.normalizeContactType(" sms "));
        assertEquals(ContactRecovery.CONTACT_EMAIL, ContactRecovery.normalizeContactType("Email"));
        assertNull(ContactRecovery.normalizeContactType("WECHAT"));
        assertNull(ContactRecovery.normalizeContactType(null));
    }

    @Test
    @DisplayName("渠道中文名唯一出处：SMS / EMAIL / 非法兜底")
    void label() {
        assertEquals("手机短信", ContactRecovery.label("SMS"));
        assertEquals("邮箱", ContactRecovery.label("email"));
        assertEquals("联系方式", ContactRecovery.label(null));
    }

    @Test
    @DisplayName("仅内置超管可改的键：就是那两个验证渠道开关")
    void adminOnlyKeys() {
        assertTrue(ContactRecovery.isAdminOnlyKey(ContactRecovery.KEY_SMS_ENABLED));
        assertTrue(ContactRecovery.isAdminOnlyKey(ContactRecovery.KEY_EMAIL_ENABLED));
        assertFalse(ContactRecovery.isAdminOnlyKey(ContactRecovery.KEY_CODE_LENGTH));
        assertFalse(ContactRecovery.isAdminOnlyKey(null));
    }

    // ------------------------------------------------------------------
    // 强制绑定真值表
    // ------------------------------------------------------------------

    @Test
    @DisplayName("真值表：未绑定 + 渠道可用 + 非超管 → 拦（唯一为真的组合）")
    void requiresBindingOnlyWhenAllThreeHold() {
        assertTrue(ContactRecovery.requiresContactBinding(false, true, false));
    }

    @Test
    @DisplayName("真值表：三条判据任一不成立都不拦")
    void anySingleJudgementReleases() {
        // 已绑定任意一种联系方式
        assertFalse(ContactRecovery.requiresContactBinding(true, true, false));
        // 没有任何渠道真的能发出验证码 —— 强制引导只会把用户永久堵在绑定页
        assertFalse(ContactRecovery.requiresContactBinding(false, false, false));
        // 内置超管单独豁免：它是唯一的救火入口，必须在任何配置状态下都能直达工作台
        assertFalse(ContactRecovery.requiresContactBinding(false, true, true));
    }

    @Test
    @DisplayName("真值表穷尽：8 种组合里只有 1 种为真")
    void truthTableExhaustive() {
        int hit = 0;
        for (int hasContact = 0; hasContact <= 1; hasContact++) {
            for (int anyChannelUsable = 0; anyChannelUsable <= 1; anyChannelUsable++) {
                for (int builtinAdmin = 0; builtinAdmin <= 1; builtinAdmin++) {
                    boolean actual = ContactRecovery.requiresContactBinding(
                            hasContact == 1, anyChannelUsable == 1, builtinAdmin == 1);
                    boolean expected = hasContact == 0 && anyChannelUsable == 1 && builtinAdmin == 0;
                    assertEquals(expected, actual,
                            "hasContact=" + hasContact + " anyUsable=" + anyChannelUsable
                                    + " builtinAdmin=" + builtinAdmin);
                    if (actual) {
                        hit++;
                    }
                }
            }
        }
        assertEquals(1, hit, "为真的组合必须恰好只有一种，否则说明某条判据被写成了并集");
    }
}
