package com.enterprise.ticket.module.system.support;

import com.enterprise.ticket.security.SecretCipher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 系统参数目录的结构不变量（P18-B 需求二；P19-F 需求六重组后同步扩充）。
 *
 * <h2>为什么这类"结构测试"比功能测试更值钱</h2>
 * <p>目录是配置页的<b>唯一数据源</b>，而它跟 {@link ConfigRules} 是一份"隐式契约"：
 * 目录负责"这项长什么样"，规则负责"这项能填什么"。两者一旦不同步，出现的缺陷都很隐蔽：
 * <ul>
 *   <li>目录多了个键、规则里没有 → 该键不受任何值域校验，能写出让系统停摆的值；</li>
 *   <li>目录把整数项标成 {@code TEXT} → 页面渲染成自由文本框，用户输入"十天"直接落库；</li>
 *   <li>目录把密文项漏标 {@code secret} → 授权码明文落库，且下发时不打码。</li>
 * </ul>
 * 这些都不是"某个函数算错了"，而是"两份声明不一致"，
 * 靠读代码很难发现（要同时看完两个文件），靠单测一行就能钉住。
 *
 * <h2>批次 F 新增的中间层为什么也要钉</h2>
 * <p>重组把结构从「分组 → 项」变成「分组 → 分区 → 项」。这一层是<b>纯视觉分组</b>，
 * 但两件事因此变成了隐式约定，且都不会在编译期被拦下：
 * <ol>
 *   <li>{@code GroupVO} 同时下发 {@code sections} 与扁平 {@code items}（见该类注释），
 *       两者必须严格互为「展平」关系 —— 不一致会让「页面上有这项、校验却看不到它」；</li>
 *   <li>{@code Section.dependsOnKey} 指向的开关必须<b>就在本分区里</b> ——
 *       指向别处的开关时，前端的置灰判定会在一个「本分区没有的项」上取不到值，
 *       表现为「参数永远灰着、点开关也不变」。</li>
 * </ol>
 */
class SystemConfigCatalogTest {

    /** 需求六定义的分组顺序（Phase 19 批次 F；「AD 域控」整组移除，故只有 5 组） */
    private static final List<String> EXPECTED_GROUPS =
            List.of("basic", "notify", "security", "alert", "borrow", "advanced");

    /** 全部项（扁平） */
    private static List<SystemConfigCatalog.Item> allItems() {
        List<SystemConfigCatalog.Item> items = new ArrayList<>();
        for (SystemConfigCatalog.Group group : SystemConfigCatalog.groups()) {
            items.addAll(group.items());
        }
        return items;
    }

    /** 全部分区（扁平） */
    private static List<SystemConfigCatalog.Section> allSections() {
        List<SystemConfigCatalog.Section> sections = new ArrayList<>();
        for (SystemConfigCatalog.Group group : SystemConfigCatalog.groups()) {
            sections.addAll(group.sections());
        }
        return sections;
    }

    @Test
    @DisplayName("分组与项都非空，且键不重复")
    void structure() {
        List<SystemConfigCatalog.Group> groups = SystemConfigCatalog.groups();
        assertFalse(groups.isEmpty(), "目录不能没有分组");

        Set<String> seen = new HashSet<>();
        for (SystemConfigCatalog.Group group : groups) {
            assertFalse(group.items().isEmpty(), "分组「" + group.label() + "」不能是空卡片");
            assertNotNull(group.label(), "分组必须有中文标题");
            assertNotNull(group.description(), "分组必须有说明（卡片顶部那句话）");
            assertNotNull(group.code());
            assertNotNull(group.notes(), "notes 不能为 null（无说明时用空数组）");
            for (SystemConfigCatalog.Item item : group.items()) {
                assertTrue(seen.add(item.key()), "配置键重复：" + item.key());
            }
        }
        assertEquals(seen.size(), SystemConfigCatalog.allKeys().size(), "allKeys 必须与目录一致");
    }

    @Test
    @DisplayName("重组后恰好 6 组且顺序固定；不再出现 AD 域控组（批次 E 已并入独立页面）")
    void groupOrder() {
        List<String> codes = new ArrayList<>();
        for (SystemConfigCatalog.Group group : SystemConfigCatalog.groups()) {
            codes.add(group.code());
        }
        assertEquals(EXPECTED_GROUPS, codes,
                "分组顺序即界面顺序（前端不重排），改动必须同步 .docs/_p18b-verify.sh 的分组断言");
        assertFalse(codes.contains("ad"), "AD 域控组已随批次 E 下线，不应再出现在参数页");
        assertFalse(codes.contains("site"), "原「站点品牌」卡已并入 basic 组，不应单独存在");
        assertFalse(codes.contains("mail"), "原「邮件通知」卡已并入 notify 组，不应单独存在");
        assertFalse(codes.contains("sms"), "原「短信通知」卡已并入 notify 组，不应单独存在");
        assertFalse(codes.contains("storage"), "原「文件存储」卡已并入 basic 组，不应单独存在");
        assertFalse(codes.contains("attach"), "原「附件与导出」卡已并入 basic 组，不应单独存在");
    }

    @Test
    @DisplayName("每个分组至少一个非空分区；匿名分区只允许出现在单分区分组里")
    void sections() {
        for (SystemConfigCatalog.Group group : SystemConfigCatalog.groups()) {
            assertFalse(group.sections().isEmpty(), "分组「" + group.code() + "」必须有分区");
            Set<String> sectionCodes = new HashSet<>();
            for (SystemConfigCatalog.Section section : group.sections()) {
                assertFalse(section.items().isEmpty(),
                        "分区「" + section.code() + "」不能是空的小节");
                assertNotNull(section.code(), "分区必须有编码（前端取键用）");
                assertNotNull(section.notes(), "分区 notes 不能为 null（无说明时用空数组）");
                assertTrue(sectionCodes.add(section.code()),
                        "分组「" + group.code() + "」内分区编码重复：" + section.code());
                if (section.label() == null) {
                    // 匿名分区（label=null 表示不渲染小标题）只在单分区卡片里成立：
                    // 多分区卡片里出现匿名分区，界面会出现「有内容却没有任何标题」的一节，
                    // 用户无法判断它属于短信还是邮箱。
                    assertEquals(1, group.sections().size(),
                            "分组「" + group.code() + "」有多个分区，其中的匿名分区「" + section.code()
                                    + "」会让用户看不出这一节属于谁；请给它一个中文小标题");
                } else {
                    assertFalse(section.label().isBlank());
                }
            }
        }
    }

    @Test
    @DisplayName("组内扁平 items 必须等于 sections 展平的结果（两份视图不允许漂移）")
    void flatItemsMatchSections() {
        for (SystemConfigCatalog.Group group : SystemConfigCatalog.groups()) {
            List<String> flat = new ArrayList<>();
            for (SystemConfigCatalog.Item item : group.items()) {
                flat.add(item.key());
            }
            List<String> fromSections = new ArrayList<>();
            for (SystemConfigCatalog.Section section : group.sections()) {
                for (SystemConfigCatalog.Item item : section.items()) {
                    fromSections.add(item.key());
                }
            }
            assertEquals(fromSections, flat,
                    "分组「" + group.code() + "」的两份视图不一致：接口同时下发 items 与 sections，"
                            + "漂移会导致「页面上有这项、校验却看不到它」");
        }
    }

    @Test
    @DisplayName("受控分区的开关必须在本分区内，且受控项不含开关自身")
    void controlledSections() {
        for (SystemConfigCatalog.Section section : allSections()) {
            if (!section.controlled()) {
                continue;
            }
            boolean switchFound = false;
            for (SystemConfigCatalog.Item item : section.items()) {
                if (section.dependsOnKey().equals(item.key())) {
                    switchFound = true;
                    assertEquals(SystemConfigCatalog.Type.TOGGLE, item.type(),
                            "受控分区的开关「" + item.key() + "」必须是 TOGGLE");
                }
            }
            assertTrue(switchFound,
                    "分区「" + section.code() + "」的 dependsOnKey=" + section.dependsOnKey()
                            + " 不在本分区的项里；前端据此置灰时会取不到开关状态，参数会永远灰着");

            // 开关自身必须被排除：若开关也受自己控制，关掉之后就再无入口打开它
            List<String> controlledKeys = new ArrayList<>();
            for (SystemConfigCatalog.Item item : section.controlledItems()) {
                controlledKeys.add(item.key());
            }
            assertEquals(section.items().size() - 1, controlledKeys.size(),
                    "受控项应当恰好是「分区内全部项去掉开关自身」");
            assertFalse(controlledKeys.contains(section.dependsOnKey()));
        }
    }

    @Test
    @DisplayName("通知与验证的两个通道各自受自己的开关控制（需求五联动的数据基础）")
    void notifyChannelsAreControlled() {
        SystemConfigCatalog.Section sms = null;
        SystemConfigCatalog.Section mail = null;
        for (SystemConfigCatalog.Section section : allSections()) {
            if ("sms".equals(section.code())) {
                sms = section;
            }
            if ("mail".equals(section.code())) {
                mail = section;
            }
        }
        assertNotNull(sms, "通知与验证组里必须有 sms 分区");
        assertNotNull(mail, "通知与验证组里必须有 mail 分区");
        assertEquals(ContactRecovery.KEY_SMS_ENABLED, sms.dependsOnKey());
        assertEquals(ContactRecovery.KEY_EMAIL_ENABLED, mail.dependsOnKey());

        // 开关与参数必须在同一个分区里 —— 否则「开关在上面那张卡、被它控制的东西在下面那张卡」，
        // 用户看不出因果，这正是批次 F 要把两个开关从「登录与安全」搬过来的原因。
        for (SystemConfigCatalog.Item item : sms.controlledItems()) {
            assertNotNull(SystemConfigCatalog.sectionOf(item.key()));
            assertEquals("sms", SystemConfigCatalog.sectionOf(item.key()).code());
        }
        for (SystemConfigCatalog.Item item : mail.controlledItems()) {
            assertEquals("mail", SystemConfigCatalog.sectionOf(item.key()).code());
        }
    }

    @Test
    @DisplayName("sectionOf 能定位任意项所属分区；未登记返回 null")
    void sectionOf() {
        assertEquals("sms", SystemConfigCatalog.sectionOf(SmsSettings.KEY_SIGN_NAME).code());
        assertEquals("mail", SystemConfigCatalog.sectionOf(MailSettings.KEY_HOST).code());
        assertEquals("brand", SystemConfigCatalog.sectionOf(SiteBranding.KEY_SITE_NAME).code());
        assertNull(SystemConfigCatalog.sectionOf("not.exists.key"));
        assertNull(SystemConfigCatalog.sectionOf(null));
    }

    @Test
    @DisplayName("目录里每一项都必须有取值规则（否则该键不受任何校验）")
    void everyItemHasRule() {
        for (SystemConfigCatalog.Item item : allItems()) {
            assertNotNull(ConfigRules.ruleOf(item.key()),
                    "目录项「" + item.key() + "」在 ConfigRules 里没有规则，写入将不受值域约束");
        }
    }

    @Test
    @DisplayName("控件类型必须与规则类型一致（否则页面用错控件）")
    void typeMatchesRule() {
        for (SystemConfigCatalog.Item item : allItems()) {
            ConfigRules.Rule rule = ConfigRules.ruleOf(item.key());
            assertNotNull(rule);
            switch (item.type()) {
                case TOGGLE -> assertEquals(ConfigRules.Kind.BOOLEAN, rule.kind(),
                        "开关项「" + item.key() + "」的规则必须是 BOOLEAN");
                case NUMBER -> assertEquals(ConfigRules.Kind.INTEGER, rule.kind(),
                        "数字项「" + item.key() + "」的规则必须是 INTEGER");
                case TEXT, PASSWORD, SELECT -> assertEquals(ConfigRules.Kind.TEXT, rule.kind(),
                        "文本类项「" + item.key() + "」的规则必须是 TEXT");
            }
        }
    }

    @Test
    @DisplayName("数字项必须能从规则里拿到区间（页面据此做即时校验）")
    void numberItemsExposeRange() {
        for (SystemConfigCatalog.Item item : allItems()) {
            if (item.type() != SystemConfigCatalog.Type.NUMBER) {
                continue;
            }
            ConfigRules.Rule rule = ConfigRules.ruleOf(item.key());
            assertNotNull(rule);
            assertTrue(rule.max() > rule.min(),
                    "数字项「" + item.key() + "」的区间必须有效（" + rule.min() + " ~ " + rule.max() + "）");
        }
    }

    @Test
    @DisplayName("密文项恰好是 SMTP 授权码与短信 Secret，且各有独立用途")
    void secretKeys() {
        assertEquals(
                Set.of(MailSettings.KEY_PASSWORD, SmsSettings.KEY_ACCESS_KEY_SECRET),
                SystemConfigCatalog.secretKeys(),
                "密文项集合发生变化时必须同步检查「加密落库 + 掩码下发」两条路径");

        assertEquals(SecretCipher.PURPOSE_SMTP_PASSWORD,
                SystemConfigCatalog.cipherPurposeOf(MailSettings.KEY_PASSWORD));
        assertEquals(SecretCipher.PURPOSE_SMS_SECRET,
                SystemConfigCatalog.cipherPurposeOf(SmsSettings.KEY_ACCESS_KEY_SECRET));
        assertNull(SystemConfigCatalog.cipherPurposeOf("smtp_host"),
                "非密文键不应有密文用途");
    }

    @Test
    @DisplayName("密文项的默认值必须是空串（预置明文等于留一个永久口子）")
    void secretDefaultsAreEmpty() {
        for (SystemConfigCatalog.Item item : allItems()) {
            if (item.secret()) {
                assertEquals("", item.defaultValue(),
                        "密文项「" + item.key() + "」不能有默认值");
            }
        }
    }

    @Test
    @DisplayName("每项都有标签、说明，且默认值可与键对应查询")
    void metadataComplete() {
        for (SystemConfigCatalog.Item item : allItems()) {
            assertNotNull(item.label());
            assertFalse(item.label().isBlank(), "项「" + item.key() + "」缺中文标签");
            assertFalse(item.description().isBlank(), "项「" + item.key() + "」缺说明（页面要显示灰字）");
            assertNotNull(item.defaultValue(), "项「" + item.key() + "」缺默认值（「恢复默认」要用）");
            assertNotNull(item.unit(), "unit 不能为 null（无单位时用空串）");
            assertNotNull(item.options(), "options 不能为 null");
            if (item.type() == SystemConfigCatalog.Type.SELECT) {
                assertFalse(item.options().isEmpty(), "下拉项「" + item.key() + "」必须有选项");
            }
        }
        assertEquals(SystemConfigCatalog.Type.PASSWORD,
                SystemConfigCatalog.itemOf(MailSettings.KEY_PASSWORD).type());
        assertNull(SystemConfigCatalog.itemOf("not.exists.key"), "未登记的键应返回 null");
    }

    @Test
    @DisplayName("开关项的单位恒为空串、默认值只能是 0 或 1")
    void toggleShape() {
        for (SystemConfigCatalog.Item item : allItems()) {
            if (item.type() != SystemConfigCatalog.Type.TOGGLE) {
                continue;
            }
            assertEquals("", item.unit(), "开关项「" + item.key() + "」不该有单位后缀");
            assertTrue(Set.of("0", "1").contains(item.defaultValue()),
                    "开关项「" + item.key() + "」的默认值必须是 0 或 1（「恢复默认」直接回填它）");
        }
    }

    @Test
    @DisplayName("角标只出现在预留的短信分区；折叠只出现在高级参数卡")
    void badgeAndCollapse() {
        for (SystemConfigCatalog.Group group : SystemConfigCatalog.groups()) {
            // 重组后角标一律下沉到分区：挂在分组上会把「短信暂未启用」
            // 顺带说成「邮件也暂未启用」，而这两个通道的状态完全无关。
            assertNull(group.badge(), "分组的角标已下沉到分区，实际是：" + group.code());
            if (group.collapsed()) {
                assertEquals("advanced", group.code(),
                        "默认折叠只应用于高级参数卡，实际是：" + group.code());
            }
            for (SystemConfigCatalog.Section section : group.sections()) {
                if (section.badge() != null) {
                    assertEquals("sms", section.code(),
                            "「暂未启用」角标只应出现在预留的短信分区上，实际是："
                                    + group.code() + "/" + section.code());
                }
            }
        }
    }

    @Test
    @DisplayName("站点图标用特殊控件渲染（不能当普通文本框，否则会把引用串改坏）")
    void logoWidget() {
        SystemConfigCatalog.Item logo = SystemConfigCatalog.itemOf(SiteBranding.KEY_SITE_LOGO);
        assertNotNull(logo);
        assertEquals("logo", logo.widget());
    }

    @Test
    @DisplayName("版权文字：基础设置 → 站点品牌，TEXT、可留空、默认空串、仅内置超管可改（需求六）")
    void copyright() {
        SystemConfigCatalog.Item item = SystemConfigCatalog.itemOf(SiteBranding.KEY_COPYRIGHT);
        assertNotNull(item, "站点品牌分区必须有「版权文字」这一项");
        assertEquals(SystemConfigCatalog.Type.TEXT, item.type());
        assertEquals("", item.defaultValue(),
                "版权默认值必须是空串 —— 预置「© 20xx XX公司」等于替所有部署单位乱签名");
        assertEquals("brand", SystemConfigCatalog.sectionOf(SiteBranding.KEY_COPYRIGHT).code(),
                "版权文字属于「站点品牌」分区");
        assertTrue(ConfigRules.isKnown(SiteBranding.KEY_COPYRIGHT),
                "必须登记取值规则，否则绕过 500 字上限、超长文案会把页脚撑坏");
        assertEquals("", ConfigRules.normalize(SiteBranding.KEY_COPYRIGHT, ""),
                "允许留空（留空 = 不显示这一行）");
        assertTrue(SiteBranding.isAdminOnlyKey(SiteBranding.KEY_COPYRIGHT),
                "版权与系统名称、图标同属站点外观，权限口径必须按卡片划一");
    }

    @Test
    @DisplayName("已退役的分组配置键不再出现在目录里（AD 同步 / 使用地点 / 备注）")
    void retiredKeysAbsent() {
        for (String key : List.of("ad_sync_enabled", "ad_sync_hour", "use_place", "remark")) {
            assertNull(SystemConfigCatalog.itemOf(key), "已退役的配置键不应再出现在目录里：" + key);
        }
    }
}
