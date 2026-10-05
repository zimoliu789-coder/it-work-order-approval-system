package com.enterprise.ticket.module.ad.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AD 地址 / DN 自动推导（Phase 19 批次 E；需求四「系统自动处理」）—— 纯函数单测
 *
 * <p>为什么这类「小工具」值得穷尽覆盖：推导错了<b>不会报错</b>，
 * 只会变成一条看起来正常、连上去才失败的配置 ——
 * 例如把 {@code dc01.company.com} 推成 {@code DC=dc01,DC=company,DC=com}，
 * 界面上平淡无奇，实际必须在生产登录失败后才发现。
 *
 * <p>三组用例分别钉住：
 * <ul>
 *   <li>{@link AdDnResolver#deriveBaseDn} —— 剥主机名前缀的<b>边界</b>（只剥封闭集合，不剥 365company 这种）；</li>
 *   <li>{@link AdDnResolver#toBindDn} —— 三种绑定写法都归一到 DN，且<b>已是 DN 的不改写</b>；</li>
 *   <li>「推不出来时必须返回 null」—— IP 地址 / 单段主机名 / 非法字符，
 *       宁可让维护人员去高级选项填，也不要猜一条错的。</li>
 * </ul>
 */
@DisplayName("AD 地址 / DN 自动推导（纯函数）")
class AdDnResolverTest {

    // ------------------------------------------------------------------
    // 基础 DN：主机名 → DC=...
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("deriveBaseDn：从域地址推导基础 DN")
    class DeriveBaseDn {

        @Test
        @DisplayName("需求四原文示例：dc01.company.com → DC=company,DC=com")
        void specExample() {
            assertEquals("DC=company,DC=com", AdDnResolver.deriveBaseDn("dc01.company.com"));
        }

        @Test
        @DisplayName("剥掉常见主机名前缀：dc1 / ad / ldap / ldaps / srv / domain（可带数字）")
        void stripsServerLabels() {
            assertEquals("DC=company,DC=com", AdDnResolver.deriveBaseDn("dc1.company.com"));
            assertEquals("DC=company,DC=com", AdDnResolver.deriveBaseDn("ad.company.com"));
            assertEquals("DC=company,DC=com", AdDnResolver.deriveBaseDn("ldap.company.com"));
            assertEquals("DC=company,DC=com", AdDnResolver.deriveBaseDn("ldaps.company.com"));
            assertEquals("DC=company,DC=com", AdDnResolver.deriveBaseDn("srv02.company.com"));
            assertEquals("DC=company,DC=com", AdDnResolver.deriveBaseDn("DOMAIN.company.com"));
        }

        @Test
        @DisplayName("不带主机名前缀时原样映射，且保留多级域名")
        void keepsAllLabelsWhenNoServerPrefix() {
            assertEquals("DC=company,DC=com", AdDnResolver.deriveBaseDn("company.com"));
            assertEquals("DC=corp,DC=example,DC=com", AdDnResolver.deriveBaseDn("ldap.corp.example.com"));
            assertEquals("DC=company,DC=local", AdDnResolver.deriveBaseDn("dc2.company.local"));
        }

        @Test
        @DisplayName("首段不是封闭集合时**不得**误剥（365company.com 必须保住 365company）")
        void doesNotStripArbitraryLeadingLabel() {
            assertEquals("DC=365company,DC=com", AdDnResolver.deriveBaseDn("365company.com"));
            assertEquals("DC=company2,DC=com", AdDnResolver.deriveBaseDn("company2.com"));
        }

        @Test
        @DisplayName("容忍协议前缀 / 端口 / 路径 — 与 AdConnection.parseHosts 同一口径")
        void toleratesUrlForms() {
            assertEquals("DC=company,DC=com", AdDnResolver.deriveBaseDn("ldap://dc01.company.com:389"));
            assertEquals("DC=company,DC=com", AdDnResolver.deriveBaseDn("ldaps://dc01.company.com/DC=company,DC=com"));
            assertEquals("DC=company,DC=com", AdDnResolver.deriveBaseDn("  dc01.company.com  "));
        }

        @Test
        @DisplayName("多台服务器时取第一台（配置顺序即主备顺序）")
        void takesFirstHost() {
            assertEquals("DC=company,DC=com",
                    AdDnResolver.deriveBaseDn("dc1.company.com, dc2.company.com"));
            assertEquals("DC=corp,DC=com",
                    AdDnResolver.deriveBaseDn("dc1.corp.com;dc2.company.com"));
        }

        @Test
        @DisplayName("IP 地址推不出域名 → null（不做「猜一个」）")
        void ipAddressYieldsNull() {
            assertNull(AdDnResolver.deriveBaseDn("192.168.1.10"));
            assertNull(AdDnResolver.deriveBaseDn("10.0.0.1"));
            assertNull(AdDnResolver.deriveBaseDn("ldap://192.168.1.10:389"));
            assertNull(AdDnResolver.deriveBaseDn("[::1]"));
        }

        @Test
        @DisplayName("单段主机名（NetBIOS 名）→ null，不拼出 DC=dc01 误导人")
        void singleLabelYieldsNull() {
            assertNull(AdDnResolver.deriveBaseDn("dc01"));
            assertNull(AdDnResolver.deriveBaseDn("company"));
        }

        @Test
        @DisplayName("空 / null / 非法字符 → null")
        void blankYieldsNull() {
            assertNull(AdDnResolver.deriveBaseDn(null));
            assertNull(AdDnResolver.deriveBaseDn(""));
            assertNull(AdDnResolver.deriveBaseDn("   "));
            assertNull(AdDnResolver.deriveBaseDn(",,,,"));
            // 尾随点号（FQDN 的合法写法）会被 Java 的 split 去掉空尾段 → 仍能正常推导
            assertEquals("DC=company,DC=com", AdDnResolver.deriveBaseDn("dc01.company.com."));
            // 下划线不是合法域名段（DNS 主机名不允许），不猜
            assertNull(AdDnResolver.deriveBaseDn("dc01.my_company.com"));
        }

        @Test
        @DisplayName("单主机版本与多主机版本口径一致")
        void hostVersionMatches() {
            assertEquals(AdDnResolver.deriveBaseDn("dc01.company.com"),
                    AdDnResolver.deriveBaseDnFromHost("dc01.company.com"));
            assertNull(AdDnResolver.deriveBaseDnFromHost(null));
        }
    }

    // ------------------------------------------------------------------
    // 基础 DN：显式优先
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("resolveBaseDn：显式配置优先于推导")
    class ResolveBaseDn {

        @Test
        @DisplayName("填了就绝不自作主张改写（自研目录 / 多域森林依赖这条）")
        void explicitWins() {
            assertEquals("DC=custom,DC=com",
                    AdDnResolver.resolveBaseDn("dc01.company.com", "DC=custom,DC=com"));
            assertEquals("DC=custom,DC=com",
                    AdDnResolver.resolveBaseDn("dc01.company.com", "  DC=custom,DC=com  "));
        }

        @Test
        @DisplayName("留空（null / 空串 / 空白）时才推导")
        void fallsBackToDerivation() {
            assertEquals("DC=company,DC=com", AdDnResolver.resolveBaseDn("dc01.company.com", null));
            assertEquals("DC=company,DC=com", AdDnResolver.resolveBaseDn("dc01.company.com", ""));
            assertEquals("DC=company,DC=com", AdDnResolver.resolveBaseDn("dc01.company.com", "   "));
        }

        @Test
        @DisplayName("都没给 → null（由完整性校验提示去高级选项填）")
        void nothingAvailable() {
            assertNull(AdDnResolver.resolveBaseDn("192.168.1.10", null));
            assertNull(AdDnResolver.resolveBaseDn(null, null));
        }
    }

    // ------------------------------------------------------------------
    // 绑定身份 → 完整 DN
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("toBindDn：三种绑定写法归一到完整 DN")
    class ToBindDn {

        private static final String BASE = "DC=company,DC=com";

        @Test
        @DisplayName("下行式 company\\query → CN=query,CN=Users,DC=company,DC=com")
        void downLevelName() {
            assertEquals("CN=query,CN=Users,DC=company,DC=com",
                    AdDnResolver.toBindDn("company\\query", BASE));
            // 域段大小写不影响（真正的归属由 baseDn 决定）
            assertEquals("CN=query,CN=Users,DC=company,DC=com",
                    AdDnResolver.toBindDn("COMPANY\\query", BASE));
        }

        @Test
        @DisplayName("UPN query@company.com → 用**UPN 自身的域**推 baseDn（比参数里的更贴切）")
        void upnUsesItsOwnDomain() {
            assertEquals("CN=query,CN=Users,DC=company,DC=com",
                    AdDnResolver.toBindDn("query@company.com", null));
            assertEquals("CN=query,CN=Users,DC=sub,DC=company,DC=com",
                    AdDnResolver.toBindDn("query@sub.company.com", "DC=wrong,DC=com"));
        }

        @Test
        @DisplayName("裸账号 → 完全依赖 baseDn")
        void bareAccount() {
            assertEquals("CN=ldapquery,CN=Users,DC=company,DC=com",
                    AdDnResolver.toBindDn("ldapquery", BASE));
        }

        @Test
        @DisplayName("已经是 DN → 原样返回（不做 RFC 4514 重写）")
        void alreadyDnPassesThrough() {
            String dn = "CN=ldapquery,OU=ServiceAccounts,DC=company,DC=com";
            assertEquals(dn, AdDnResolver.toBindDn(dn, BASE));
            // 带转义的长 DN 同样不被动过
            String escaped = "CN=张伟\\, 三,OU=研发,DC=company,DC=com";
            assertEquals(escaped, AdDnResolver.toBindDn(escaped, BASE));
        }

        @Test
        @DisplayName("推不出 baseDn 时退回原样 —— 此时下行式 / UPN 本身就是可用的绑定标识")
        void fallsBackToRawWhenBaseDnUnavailable() {
            assertEquals("company\\query", AdDnResolver.toBindDn("company\\query", null));
            assertEquals("query@company", AdDnResolver.toBindDn("query@company", null));
            assertEquals("ldapquery", AdDnResolver.toBindDn("ldapquery", null));
        }

        @Test
        @DisplayName("账号里出现 DN 分隔符时做最小转义（从 ADUC 复制粘贴的常见形态）")
        void escapesAccountValue() {
            assertEquals("CN=zhang\\,wei,CN=Users,DC=company,DC=com",
                    AdDnResolver.toBindDn("zhang,wei", BASE));
        }

        @Test
        @DisplayName("空 / 只有域段 / 只有分隔符 → 不生成半截 DN")
        void blankInputs() {
            assertNull(AdDnResolver.toBindDn(null, BASE));
            assertNull(AdDnResolver.toBindDn("", BASE));
            assertNull(AdDnResolver.toBindDn("   ", BASE));
            // "company\"：域段在、账号为空 → 原样返回（随后由校验判非法）
            assertEquals("company\\", AdDnResolver.toBindDn("company\\", BASE));
        }

        @Test
        @DisplayName("归一结果必须能通过 looksLikeBindIdentity（前端预览与后端校验同一口径）")
        void resultPassesValidation() {
            assertTrue(AdConfigValidator.looksLikeBindIdentity(AdDnResolver.toBindDn("company\\query", BASE)));
            assertTrue(AdConfigValidator.looksLikeBindIdentity(AdDnResolver.toBindDn("query@company.com", null)));
            assertTrue(AdConfigValidator.looksLikeBindIdentity(AdDnResolver.toBindDn("ldapquery", BASE)));
        }
    }

    // ------------------------------------------------------------------
    // 一次推导（保存流程与预览接口共用）
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("resolve：一次推导同时给出 baseDn 与 bindDn")
    class ResolveBoth {

        @Test
        @DisplayName("主机名 + 下行式账号 → 两项都是完整值")
        void bothDerived() {
            AdDnResolver.ResolvedDns r =
                    AdDnResolver.resolve("dc01.company.com", null, "company\\query");
            assertEquals("DC=company,DC=com", r.baseDn());
            assertEquals("CN=query,CN=Users,DC=company,DC=com", r.bindDn());
        }

        @Test
        @DisplayName("自定义基础 DN 会被绑定 DN 复用（两步推导必须串起来）")
        void customBaseUsedByBind() {
            AdDnResolver.ResolvedDns r =
                    AdDnResolver.resolve("dc01.company.com", "DC=custom,DC=com", "company\\query");
            assertEquals("DC=custom,DC=com", r.baseDn());
            assertEquals("CN=query,CN=Users,DC=custom,DC=com", r.bindDn());
        }

        @Test
        @DisplayName("IP 域地址 → 两项都推不出来，原样保留（界面据此提示去高级选项）")
        void ipServerDegrades() {
            AdDnResolver.ResolvedDns r =
                    AdDnResolver.resolve("192.168.1.10", null, "company\\query");
            assertNull(r.baseDn());
            assertEquals("company\\query", r.bindDn());
        }
    }

    // ------------------------------------------------------------------
    // firstRdnValue
    // ------------------------------------------------------------------

    @Test
    @DisplayName("firstRdnValue：取首个 RDN 的值，非 DN 返回 null")
    void firstRdnValue() {
        assertEquals("query", AdDnResolver.firstRdnValue("CN=query,CN=Users,DC=company,DC=com"));
        assertEquals("company", AdDnResolver.firstRdnValue("DC=company,DC=com"));
        assertEquals("query", AdDnResolver.firstRdnValue("CN=query"));
        assertNull(AdDnResolver.firstRdnValue("query"));
        assertNull(AdDnResolver.firstRdnValue(null));
    }

    @Test
    @DisplayName("常量与校验器常量保持可用（防止有人把它们改成 private）")
    void sanity() {
        assertFalse(AdConfigValidator.looksLikeBindIdentity(null));
        assertTrue(AdConfigValidator.DEFAULT_SYNC_HOUR >= AdConfigValidator.MIN_SYNC_HOUR);
    }
}
