package com.enterprise.ticket.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 账号格式与联系方式打码（P18-A 需求五：补齐本类的单测）。
 *
 * <h2>为什么这个纯静态类值得单测</h2>
 * <p>它是三件事的<b>唯一出处</b>：登录名 / 姓名 / 手机号 / 邮箱的形态判定、以及
 * 「这次输入该去查哪一列」的分派（{@link AccountFormats#shapeOf}）。判错一个分支的后果
 * 不是报错而是<b>静默走错查询路径</b>：用户输入手机号却按登录名查，得到「账号不存在」，
 * 而他明明能收到短信。这类缺陷无法从界面上看出，只能靠单测钉住。
 *
 * <p>打码（{@link AccountFormats#maskContact}）在 P18-A 需求四被收敛到此处：
 * 收敛前同一规则散落四处、且已经出现 {@code hasText}(trim) 与 {@code isEmpty}(不 trim)
 * 的分叉。本类把边界（空值 / 短串 / 单字符前缀邮箱）全部钉死，防止再次分叉。
 */
class AccountFormatsTest {

    @Nested
    @DisplayName("形态判定")
    class Shapes {

        @Test
        @DisplayName("登录名：5 位以上纯数字才算")
        void username() {
            assertTrue(AccountFormats.isUsername("10001"));
            assertTrue(AccountFormats.isUsername("100000"));
            assertFalse(AccountFormats.isUsername("1000"), "4 位数字不是合法登录名");
            assertFalse(AccountFormats.isUsername("10001a"));
            assertFalse(AccountFormats.isUsername("张伟"), "中文绝不能当登录名");
            assertFalse(AccountFormats.isUsername(null));
        }

        @Test
        @DisplayName("姓名：纯中文 2~20 字，带序号或字母一律不算")
        void chineseName() {
            assertTrue(AccountFormats.isChineseName("张伟"));
            assertTrue(AccountFormats.isChineseName("欧阳娜娜"));
            assertFalse(AccountFormats.isChineseName("张伟_2"), "重名曾用 _2 后缀，这不是合法姓名");
            assertFalse(AccountFormats.isChineseName("zhangwei"));
            assertFalse(AccountFormats.isChineseName("张"));
            assertFalse(AccountFormats.isChineseName(null));
        }

        @Test
        @DisplayName("手机号：1 + 3~9 + 9 位")
        void phone() {
            assertTrue(AccountFormats.isPhone("13900000001"));
            assertTrue(AccountFormats.isPhone("18812345678"));
            assertFalse(AccountFormats.isPhone("1390000000"), "10 位不是手机号");
            assertFalse(AccountFormats.isPhone("23900000001"), "第二位不在 3~9");
            assertFalse(AccountFormats.isPhone(null));
        }

        @Test
        @DisplayName("邮箱：允许 + 与 _，不追求 RFC 全量校验")
        void email() {
            assertTrue(AccountFormats.isEmail("a@b.com"));
            assertTrue(AccountFormats.isEmail("zhang.wei+it@example.com"));
            assertFalse(AccountFormats.isEmail("a@b"), "无顶级域名");
            assertFalse(AccountFormats.isEmail("zhangwei"), "裸姓名不是邮箱");
            assertFalse(AccountFormats.isEmail(null));
        }
    }

    @Nested
    @DisplayName("shapeOf 的判定顺序（决定去查哪一列）")
    class ShapeOf {

        @Test
        @DisplayName("含 @ 一律判为邮箱（哪怕其余部分长得像别的）")
        void emailWins() {
            assertEquals(AccountFormats.AccountShape.EMAIL, AccountFormats.shapeOf("13900000001@x.com"));
        }

        @Test
        @DisplayName("11 位手机号判为 PHONE，而不是「纯数字登录名」")
        void phoneBeatsUsername() {
            // 这是最容易写错的一处：号码本身也满足「5 位以上纯数字」，
            // 顺序颠倒会让所有手机号输入都去查 username 列，必然查不到。
            assertEquals(AccountFormats.AccountShape.PHONE, AccountFormats.shapeOf("13900000001"));
        }

        @Test
        @DisplayName("5 位纯数字判为 USERNAME")
        void usernameShape() {
            assertEquals(AccountFormats.AccountShape.USERNAME, AccountFormats.shapeOf("10001"));
        }

        @Test
        @DisplayName("中文姓名与「格式不对的东西」都归为 NAME（兜底同一条查询路径）")
        void fallbackToName() {
            assertEquals(AccountFormats.AccountShape.NAME, AccountFormats.shapeOf("张伟"));
            assertEquals(AccountFormats.AccountShape.NAME, AccountFormats.shapeOf("10001a"));
        }

        @Test
        @DisplayName("空白与 null 归为 NAME，不抛异常")
        void nullSafe() {
            assertEquals(AccountFormats.AccountShape.NAME, AccountFormats.shapeOf(null));
            assertEquals(AccountFormats.AccountShape.NAME, AccountFormats.shapeOf("   "));
        }
    }

    @Nested
    @DisplayName("联系方式打码（唯一实现）")
    class Masking {

        @Test
        @DisplayName("手机号：前 3 + **** + 后 4")
        void maskPhone() {
            assertEquals("139****0001", AccountFormats.maskContact("13900000001"));
        }

        @Test
        @DisplayName("邮箱：首字符 + *** + @域名")
        void maskEmail() {
            assertEquals("z***@example.com", AccountFormats.maskContact("zhangwei@example.com"));
        }

        @Test
        @DisplayName("单字符前缀邮箱原样返回（打码反而暴露更多）")
        void maskSingleCharLocalPart() {
            assertEquals("a@qq.com", AccountFormats.maskContact("a@qq.com"));
        }

        @Test
        @DisplayName("无 @ 且长度不足 7 的裸串原样返回，不越界")
        void maskShortValue() {
            // 收敛前的四处实现里出现过 substring(0, 3)，对短串会抛
            // StringIndexOutOfBoundsException —— 把一个输入问题变成 500。
            assertEquals("123456", AccountFormats.maskContact("123456"));
        }

        @Test
        @DisplayName("恰好 7 位：前 3 与后 4 会重叠，但不得抛异常")
        void maskBoundaryLength() {
            assertEquals("123****4567", AccountFormats.maskContact("1234567"));
        }

        @Test
        @DisplayName("空值与纯空白统一返回空串（trim 语义）")
        void maskBlank() {
            assertEquals("", AccountFormats.maskContact(null));
            assertEquals("", AccountFormats.maskContact(""));
            assertEquals("", AccountFormats.maskContact("   "));
        }
    }
}
