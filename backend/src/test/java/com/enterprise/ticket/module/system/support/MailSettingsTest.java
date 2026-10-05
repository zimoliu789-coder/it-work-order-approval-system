package com.enterprise.ticket.module.system.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SMTP 完整性判定（P18-B 需求三）。
 *
 * <h2>为什么这条判定值得单测</h2>
 * <p>{@link MailSettings.Settings#complete()} 有三个消费方：配置页的依赖警告、
 * 发送测试邮件接口、以及找回密码的发码路由。它判错的后果是<b>两个方向都很难查</b>：
 * <ul>
 *   <li>判「完整」而实际发不出去 → 用户点「获取验证码」，界面说已发送，邮件永远不来；</li>
 *   <li>判「不完整」而其实能发 → 明明配好了却总是走日志通道，用户抱怨「配了没用」。</li>
 * </ul>
 * 因此把「哪几项算数、哪项不算数」用用例钉死 —— 尤其是
 * <b>发件人显示名不参与完整性判定</b>这一条：它缺失时回落默认名，不影响投递，
 * 若把它算进去，就会出现「配置明明可用、页面却一直报不完整」的假告警。
 */
class MailSettingsTest {

    private static MailSettings.Settings settings(String host, int port, String username, String password) {
        return new MailSettings.Settings(host, port, username, password, null, true);
    }

    @Nested
    @DisplayName("完整性判定")
    class Completeness {

        @Test
        @DisplayName("四项齐全才算完整")
        void complete() {
            assertTrue(settings("smtp.qq.com", 465, "a@qq.com", "authcode").complete());
        }

        @Test
        @DisplayName("缺任意一项都不完整，且原因指向缺的那项")
        void missingFields() {
            assertFalse(settings("", 465, "a@qq.com", "code").complete());
            assertEquals("未填写 SMTP 服务器地址", settings("", 465, "a@qq.com", "code").incompletenessReason());

            assertFalse(settings("smtp.qq.com", 465, "", "code").complete());
            assertEquals("未填写发件邮箱账号", settings("smtp.qq.com", 465, "", "code").incompletenessReason());

            assertFalse(settings("smtp.qq.com", 465, "a@qq.com", "").complete());
            assertTrue(settings("smtp.qq.com", 465, "a@qq.com", "").incompletenessReason().contains("授权码"));
        }

        @Test
        @DisplayName("端口越界不算完整（0 与 65536 都不行）")
        void invalidPort() {
            assertFalse(settings("smtp.qq.com", 0, "a@qq.com", "code").complete());
            assertFalse(settings("smtp.qq.com", 65536, "a@qq.com", "code").complete());
            assertTrue(settings("smtp.qq.com", 1, "a@qq.com", "code").complete());
            assertTrue(settings("smtp.qq.com", 65535, "a@qq.com", "code").complete());
        }

        @Test
        @DisplayName("完整时原因为 null（页面据此不显示警告）")
        void reasonNullWhenComplete() {
            assertNull(settings("smtp.qq.com", 465, "a@qq.com", "code").incompletenessReason());
        }

        @Test
        @DisplayName("发件人显示名不参与完整性判定，只影响展示名")
        void fromNameDoesNotAffectCompleteness() {
            MailSettings.Settings withoutName =
                    new MailSettings.Settings("smtp.qq.com", 465, "a@qq.com", "code", "", true);
            assertTrue(withoutName.complete(), "显示名缺失不应让配置被判为不完整");
            assertEquals(MailSettings.DEFAULT_FROM_NAME, withoutName.displayName());
            assertEquals("a@qq.com", withoutName.fromAddress(), "发件地址取认证账号");
        }
    }
}
