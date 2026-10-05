package com.enterprise.ticket.module.ad.ldap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AD 属性映射（Phase 13；需求一.1）—— 纯函数单测
 *
 * <p>钉死三处「写错了不会报错、只在生产才炸」的细节：
 * <ul>
 *   <li><b>属性名大小写不敏感</b>：不同 LDAP 客户端返回的大小写不统一，
 *       严格匹配会出现「测试连接成功、正式同步却读不到登录名」；</li>
 *   <li><b>userAccountControl 位运算</b>：512 是正常账号（非零！）、514 才是禁用，
 *       用「是否非零」判断会把全公司账号禁掉；</li>
 *   <li><b>objectGUID 规范化</b>：去掉花括号与连字符并转小写，保证跨次同步可比对；</li>
 *   <li><b>手机号属性（Phase 19 批次 E）</b>：与邮箱同属「可留空的 AD 权威字段」，
 *       未配属性名时必须返回 null 而不是空串，否则会把本地已绑定的手机号覆盖成空。</li>
 * </ul>
 */
@DisplayName("AD 属性映射（纯函数）")
class AdAttributeMappingTest {

    private final AdAttributeMapping mapping = new AdAttributeMapping(
            "sAMAccountName", "displayName", "mail", "telephoneNumber", "department", "userAccountControl");

    private Map<String, List<String>> attrs(Object... kv) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            Object value = kv[i + 1];
            if (value == null) {
                map.put((String) kv[i], null);
            } else {
                map.put((String) kv[i], List.of(String.valueOf(value)));
            }
        }
        return map;
    }

    @Test
    @DisplayName("基本映射：登录名 / 姓名 / 邮箱 / 部门 / 禁用位")
    void basicMapping() {
        AdUser user = mapping.map("CN=张伟,OU=研发,DC=company,DC=com", attrs(
                "sAMAccountName", "zhangwei",
                "displayName", "张伟",
                "mail", "zhangwei@company.com",
                "telephoneNumber", "13800001111",
                "department", "研发部",
                "userAccountControl", "512"));

        assertEquals("zhangwei", user.account());
        assertEquals("张伟", user.name());
        assertEquals("zhangwei@company.com", user.email());
        assertEquals("13800001111", user.phone());
        assertEquals("研发部", user.department());
        assertFalse(user.disabled());
        assertTrue(user.usable());
    }

    @Test
    @DisplayName("属性名大小写不敏感（samaccountname / DISPLAYNAME 也能命中）")
    void attributeLookupIsCaseInsensitive() {
        AdUser user = mapping.map("CN=x", attrs(
                "SAMACCOUNTNAME", "lisi",
                "DisplayName", "李四"));

        assertEquals("lisi", user.account());
        assertEquals("李四", user.name());
    }

    @Test
    @DisplayName("displayName 为空时回退 cn")
    void nameFallsBackToCn() {
        AdUser user = mapping.map("CN=wangwu", attrs(
                "sAMAccountName", "wangwu",
                "displayName", "",
                "cn", "王五"));

        assertEquals("王五", user.name());
    }

    @Test
    @DisplayName("displayName 与 cn 都为空时回退登录名")
    void nameFallsBackToAccount() {
        AdUser user = mapping.map("CN=zhaoliu", attrs("sAMAccountName", "zhaoliu"));

        assertEquals("zhaoliu", user.name());
    }

    @Test
    @DisplayName("缺失的可选属性返回 null，不抛异常")
    void missingOptionalAttrs() {
        AdUser user = mapping.map("CN=x", attrs("sAMAccountName", "someone"));

        assertNull(user.email());
        assertNull(user.phone());
        assertNull(user.department());
        assertNull(user.objectGuid());
    }

    @Test
    @DisplayName("手机号属性：大小写不敏感，多值时取第一个非空值")
    void phoneMapping() {
        AdUser user = mapping.map("CN=x", attrs(
                "sAMAccountName", "zhangsan",
                "TELEPHONENUMBER", "13900002222"));

        assertEquals("13900002222", user.phone());
    }

    @Test
    @DisplayName("手机号属性配成空串 → 返回 null（不得写空串去覆盖本地已绑定号码）")
    void blankPhoneAttributeYieldsNull() {
        AdAttributeMapping noPhone = new AdAttributeMapping(
                "sAMAccountName", "displayName", "mail", "", "department", "userAccountControl");

        AdUser user = noPhone.map("CN=x", attrs(
                "sAMAccountName", "zhangsan",
                "telephoneNumber", "13900002222"));

        assertNull(user.phone(), "未配置手机号属性时不应取到值");
    }

    @Test
    @DisplayName("手机号属性为 null 时不抛异常")
    void nullPhoneAttributeYieldsNull() {
        AdAttributeMapping noPhone = new AdAttributeMapping(
                "sAMAccountName", "displayName", "mail", null, "department", "userAccountControl");

        assertNull(noPhone.map("CN=x", attrs("sAMAccountName", "zhangsan")).phone());
    }

    // ------------------------------------------------------------------
    // userAccountControl 位判定
    // ------------------------------------------------------------------

    @Test
    @DisplayName("userAccountControl：512 正常（非零！）、514 禁用、0 不禁用")
    void disabledBitSemantics() {
        assertFalse(AdAttributeMapping.isDisabled("512"), "512 是正常账号，不能被判成禁用");
        assertTrue(AdAttributeMapping.isDisabled("514"), "514 = 512 | 2，禁用位为 1");
        assertFalse(AdAttributeMapping.isDisabled("0"));
        assertFalse(AdAttributeMapping.isDisabled("66048"), "其它非禁用位组合不应误判");
    }

    @Test
    @DisplayName("userAccountControl：空 / 非数字 → 倾向「未禁用」（宁可漏禁不可误禁）")
    void disabledFallbackIsFalse() {
        assertFalse(AdAttributeMapping.isDisabled(null));
        assertFalse(AdAttributeMapping.isDisabled(""));
        assertFalse(AdAttributeMapping.isDisabled("   "));
        assertFalse(AdAttributeMapping.isDisabled("not-a-number"));
    }

    // ------------------------------------------------------------------
    // objectGUID / 二进制
    // ------------------------------------------------------------------

    @Test
    @DisplayName("objectGUID 规范化：去花括号 / 连字符并转小写")
    void guidNormalization() {
        AdUser user = mapping.map("CN=x", attrs(
                "sAMAccountName", "someone",
                "objectGUID", "{0A1B2C3D-4E5F-6789-ABCD-EF0011223344}"));

        assertEquals("0a1b2c3d4e5f6789abcdef0011223344", user.objectGuid());
    }

    @Test
    @DisplayName("hexOf：二进制 → 32 位十六进制小写")
    void hexOfBytes() {
        byte[] bytes = new byte[]{0x0A, 0x1B, (byte) 0xFF, 0x00};
        assertEquals("0a1bff00", AdAttributeMapping.hexOf(bytes));
        assertNull(AdAttributeMapping.hexOf(null));
        assertNull(AdAttributeMapping.hexOf(new byte[0]));
    }

    @Test
    @DisplayName("textOf：byte[] 按 ISO-8859-1 还原，字符串原样返回")
    void textOfValues() {
        assertEquals("abc", AdAttributeMapping.textOf("abc"));
        assertNull(AdAttributeMapping.textOf(null));
        byte[] raw = new byte[]{(byte) 0xC3, (byte) 0xA9};
        assertEquals(new String(raw, StandardCharsets.ISO_8859_1), AdAttributeMapping.textOf(raw));
    }
}
