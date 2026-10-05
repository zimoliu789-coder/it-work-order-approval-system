package com.enterprise.ticket.module.system.support;

import com.enterprise.ticket.module.security.support.SecuritySettings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 系统参数目录（：把系统参数页做成非技术人员能维护的卡片式配置页）。
 *
 * <h2>为什么要有这个类（而不是让前端自己写死标签）</h2>
 * <p>改造前配置页直接铺英文 key，管理员看到的是 {@code lock_timeout_minutes} 这一串字符，
 * 得靠猜才能知道它是什么意思。要让页面说人话，就必须有一份「key → 中文标签 + 分组 + 控件 + 单位 + 说明」的映射。
 * 这份映射<b>只能有一份</b>，而且必须放在服务端：
 * <ul>
 *   <li>放前端 → 后端新增参数时页面静默不显示（表现为「参数加了但界面找不到」），
 *       而且「哪些项仅内置超管可改」这类<b>权限口径</b>绝不能由前端定义；</li>
 *   <li>放服务端 → 新增参数只需改这一处 + 一条迁移，页面自动出现；
 *       与 {@code PermissionCatalog}「代码即事实源」同一思路。</li>
 * </ul>
 *
 * <h2>与 {@link ConfigRules} 的分工</h2>
 * <p>本类只说「这项叫什么、长什么样」，<b>不说取值范围</b> ——
 * 区间一律通过 {@link ConfigRules#ruleOf(String)} 回查，避免「前端一套区间、后端一套区间」的漂移
 * （漂移的表现是「前端放行、后端拒绝」，最难向用户解释）。
 *
 * <h2>三层结构：分组 → 分区 → 项（ 引入中间那一层）</h2>
 * <p>{@link Group#code()} 决定左侧目录的条目，{@link Section} 决定卡片内部的小标题。
 * 引入分区是为了满足的「按业务域重组」：重组后一张卡片要同时装下
 * <b>短信通道 + 邮箱通道 + 验证码</b> 三件事，而这三件事各有自己的
 * <ul>
 *   <li>参考说明（{@code SmsSettings.REFERENCE_NOTES} 与 {@code MailSettings.REFERENCE_NOTES}
 *       讲的根本不是一回事）；</li>
 *   <li>角标（短信是「暂未启用」，邮箱不是 —— 把角标挂在分组上会变成
 *       「邮件通知也暂未启用」这种明显错误的暗示）；</li>
 *   <li>开关依赖（短信参数受 {@code sms_verify_enabled} 控制，邮箱参数受
 *       {@code email_verify_enabled} 控制，两者必须能分别灰化）。</li>
 * </ul>
 * 若只有「分组 → 项」两层，上述三样只能退化成「全卡的公共说明 + 全卡的公共角标」，
 * 信息会在合并的那一刻被抹平 —— 而抹平的方向恰好是让用户看不懂。
 *
 * <h2>单分区分组为什么允许「匿名分区」</h2>
 * <p>「登录与安全」「借用与审批」「高级参数」这三组天然只有一个语义块，
 * 硬给它们编一个小标题（如「基本设置」）只会在同一张卡片上出现
 * 「标题下面又是一个几乎同名的标题」的观感。
 * 因此约定：{@code section.label == null} 表示「不渲染小标题」，
 * 且这种匿名分区<b>只允许出现在只有一个分区的分组里</b>（由单测钉住）。
 *
 * <h2>分组顺序就是界面顺序</h2>
 * <p>{@link #groups()} 返回顺序即卡片从上到下的顺序，前端<b>不重排</b>。
 * 顺序按「管理员最常改的在最上面」排：站点外观与通知通道日常会动，
 * 高级参数（全局限流）一年也未必碰一次，因此放最后并默认折叠。
 */
public final class SystemConfigCatalog {

    private SystemConfigCatalog() {
    }

    /** 控件类型：决定配置页渲染成什么 */
    public enum Type {
        /** 开关（布尔） */
        TOGGLE,
        /** 数字输入（带单位后缀） */
        NUMBER,
        /** 单行文本 */
        TEXT,
        /** 密码框（带显隐切换）；落库前加密、下发时掩码 */
        PASSWORD,
        /** 下拉选择 */
        SELECT
    }

    /**
     * 单个参数项
     *
     * @param key          配置键（与 {@code system_config.config_key}、迁移脚本逐字对应）
     * @param label        中文标签（页面上显示的「名字」）
     * @param type         控件类型
     * @param unit         单位后缀（NUMBER 用，如「分钟」「天」）；无单位为空串
     * @param defaultValue 内置默认值（「恢复默认」按钮回填的就是它；必须与迁移脚本初值一致）
     * @param description  灰色小字说明（一句人话，讲清「改大了会怎样」）
     * @param purpose      密文用途（仅 PASSWORD 用，传给 {@code SecretCipher}）
     * @param options      下拉选项（仅 SELECT 用）：value → label
     * @param widget       特殊渲染提示（如 {@code logo} 表示用图片上传控件）；普通项为 {@code null}
     */
    public record Item(String key, String label, Type type, String unit, String defaultValue,
                       String description, String purpose, List<Option> options, String widget,
                       Layout layout) {

        /**
         * 兼容构造：不指定跨度时按「一行两个」渲染。
         *
         * <p>目录里的项一律经 {@link #item} 工厂创建（跨度由 {@link #layoutOf} 统一决定），
         * 这个重载只为「测试或别处临时造项」保留，免得它们被新增的组件逼着改签名。
         */
        public Item(String key, String label, Type type, String unit, String defaultValue,
                    String description, String purpose, List<Option> options, String widget) {
            this(key, label, type, unit, defaultValue, description, purpose, options, widget,
                    Layout.MEDIUM);
        }

        /** 是否密文项（落库需加密、下发需掩码） */
        public boolean secret() {
            return type == Type.PASSWORD;
        }
    }

    /**
     * 参数项在配置页网格里占的宽度（12 栅格）。
     *
     * <p>的痛点原文是「不要每个字段占一整行……短字段和长字段合理搭配，整体紧凑不浪费空间」。
     * 跨度放在**后端目录**而不是前端写死，是因为「这一项该有多宽」取决于它的语义
     * （路径要长、开关要短）—— 前端只按跨度渲染、不猜；以后调宽度也不必动前端。
     *
     * <p>跨度只是**宽屏**的排布建议：窄屏由前端媒体查询统一降为整行
     * （见 {@code views/system/config/index.vue} 末尾的媒体查询）。
     */
    public enum Layout {
        /** 4 列（一行三个）：开关与纯数字输入框 —— 控件本身很窄，说明也只有一句。 */
        SHORT(4),
        /** 6 列（一行两个）：普通文本、下拉、密文。 */
        MEDIUM(6),
        /** 12 列（整行）：路径 / 白名单这类长文本、带按钮的图标控件、以及「管一整段」的总开关。 */
        FULL(12);

        private final int span;

        Layout(int span) {
            this.span = span;
        }

        /** 12 栅格下的列跨度（前端据此算 grid-column） */
        public int span() {
            return span;
        }
    }

    /** 下拉选项 */
    public record Option(String value, String label) {
    }

    /**
     * 必须独占整行的参数键。
     *
     * <p>两类：① 内容本身很长的 —— 存储目录、限流白名单、备份目录、站点图标（带两个按钮）；
     * ② 「管一整段」的通道 / 功能总开关（短信、邮箱、数据库备份、运行时条件引擎）——
     * 它们控制的分区就在自己下方，独占一行才把「这一段归它管」表达清楚。
     */
    private static final Set<String> FULL_WIDTH_KEYS = Set.of(
            SiteBranding.KEY_SITE_LOGO,
            StorageSettings.KEY_ATTACHMENT_PATH,
            ContactRecovery.KEY_SMS_ENABLED,
            ContactRecovery.KEY_EMAIL_ENABLED,
            "rate_limit_whitelist",
            "backup_enabled",
            "backup_dir",
            "flow_runtime_condition_enabled",
            // 异常告警：这两个都是「一串值」，窄栏里根本看不全，改起来容易漏
            ExceptionAlertSettings.KEY_EXTRA_RECIPIENTS,
            ExceptionAlertSettings.KEY_IGNORE_CATEGORIES);

    /**
     * 按「键 + 控件类型」决定跨度。
     *
     * <p>刻意**不**按描述长度这类文本特征推断：描述改一句话就让整页布局跳动，
     * 改动与效果之间没有可解释的因果关系。这里只依据两个稳定事实 ——
     * **这一项是什么控件**、**它是不是那个总开关**。
     */
    private static Layout layoutOf(String key, Type type) {
        if (FULL_WIDTH_KEYS.contains(key)) {
            return Layout.FULL;
        }
        return switch (type) {
            case NUMBER, TOGGLE -> Layout.SHORT;
            case TEXT, PASSWORD, SELECT -> Layout.MEDIUM;
        };
    }

    /**
     * 组内分区（= 卡片内部的一小节）
     *
     * @param code         分区编码（仅内部使用与前端取键，不显示在界面上）
     * @param label        小标题；{@code null} 表示不渲染小标题（见类注释「匿名分区」）
     * @param description  小标题下的一句引导语；无则 {@code null}
     * @param badge        分区角标（如「暂未启用」）；无则 {@code null}
     * @param notes        分区底部的参考说明（数组，可为空）
     * @param dependsOnKey 受哪个开关键控制：该键值为 0 时，本分区<b>除开关自身</b>外
     *                     所有项都要置灰；不受控制时为 {@code null}
     * @param items        参数项（顺序即界面顺序）
     */
    public record Section(String code, String label, String description, String badge,
                          List<String> notes, String dependsOnKey, List<Item> items) {

        /** 是否受某个开关控制 */
        public boolean controlled() {
            return dependsOnKey != null && !dependsOnKey.isBlank();
        }

        /**
         * 受开关控制的项（<b>不含开关自身</b>）。
         *
         * <p>「开关自身不受自己控制」这条不写清楚就会出人命：开关若也置灰，
         * 关掉之后就再也没有入口把它打开，参数页会把自己锁死。
         * 服务层写权限校验与前端置灰都走这个方法，避免两处各写一遍过滤条件。
         */
        public List<Item> controlledItems() {
            if (!controlled()) {
                return List.of();
            }
            List<Item> out = new ArrayList<>();
            for (Item item : items) {
                if (!dependsOnKey.equals(item.key())) {
                    out.add(item);
                }
            }
            return out;
        }
    }

    /**
     * 参数分组（= 配置页上的一张卡片）
     *
     * @param code        分组编码（左侧目录与滚动高亮用，不显示在界面上）
     * @param label       卡片标题
     * @param description 卡片顶部的一句通俗说明
     * @param badge       卡片右上角角标；无则 {@code null}（重组后角标都下沉到分区）
     * @param notes       卡片底部的参考说明（数组，可为空）
     * @param collapsed   是否默认折叠（高级参数用）
     * @param sections    组内分区（顺序即界面顺序）
     */
    public record Group(String code, String label, String description, String badge,
                        List<String> notes, boolean collapsed, List<Section> sections) {

        /**
         * 组内全部项（扁平）。
         *
         * <p>供 {@link #allKeys()} / {@link #itemOf(String)} / {@link #secretKeys()}
         * 与服务层装配 VO 使用 —— 这三处的语义都是「把整组当成一个项的集合」，
         * 让它们各自去遍历分区只会多出三份同样的双层循环。
         */
        public List<Item> items() {
            List<Item> out = new ArrayList<>();
            for (Section section : sections) {
                out.addAll(section.items());
            }
            return out;
        }
    }

    /** 全部键（供测试核对「目录是否覆盖了除 internal 外的所有参数」） */
    public static Set<String> allKeys() {
        Set<String> keys = new java.util.LinkedHashSet<>();
        for (Group group : groups()) {
            for (Item item : group.items()) {
                keys.add(item.key());
            }
        }
        return keys;
    }

    /** 查某项；未登记返回 {@code null} */
    public static Item itemOf(String key) {
        if (key == null) {
            return null;
        }
        for (Group group : groups()) {
            for (Item item : group.items()) {
                if (item.key().equals(key.trim())) {
                    return item;
                }
            }
        }
        return null;
    }

    /**
     * 查某项所属的分区；未登记返回 {@code null}。
     *
     * <p>供服务层做「开关联动校验」时定位开关项 —— 判定某个键是否受开关控制，
     * 必须知道它在哪个分区里，而不是靠一张写死的「哪些键属于短信通道」清单
     * （那种清单会与目录漂移，漂移的表现是「新加的短信参数不受开关约束」）。
     */
    public static Section sectionOf(String key) {
        if (key == null) {
            return null;
        }
        String target = key.trim();
        for (Group group : groups()) {
            for (Section section : group.sections()) {
                for (Item item : section.items()) {
                    if (item.key().equals(target)) {
                        return section;
                    }
                }
            }
        }
        return null;
    }

    /** 全部密文键（服务层据此决定「写入前加密、下发前掩码」） */
    public static Set<String> secretKeys() {
        Set<String> keys = new java.util.LinkedHashSet<>();
        for (Group group : groups()) {
            for (Item item : group.items()) {
                if (item.secret()) {
                    keys.add(item.key());
                }
            }
        }
        return keys;
    }

    /**
     * 目录本体（每次返回全新实例；分组与项的顺序即界面顺序）。
     *
     * <h2> 的重组口径（）</h2>
     * <p>由 8 组 53 项重组为 <b>5 组</b>，按业务域而不是按技术模块归类：
     * <ol>
     *   <li>{@code basic} 基础设置 —— 站点品牌 + 文件存储 + 附件与导出；</li>
     *   <li>{@code notify} 通知与验证 —— 短信（开关 + 5 项参数）+ 邮箱（开关 + 6 项参数）+ 验证码设置；</li>
     *   <li>{@code security} 登录与安全 —— 密码策略 / 登录锁定 / 登录频率 / 登录有效期；</li>
     *   <li>{@code borrow} 借用与审批 —— 借用时长、顺延、催办、审批超时；</li>
     *   <li>{@code advanced} 高级参数 —— 全局限流、日志留存、运行时条件引擎（默认折叠）。</li>
     * </ol>
     * <p>原文还列了第 6 组「AD 域控」，但 AD 的连接与同步已在 并成
     * 独立页面（{@code /system/ad}，数据落在 {@code ad_config} 表），
     * 因此这里<b>整组不出现</b>，也不留跳转提示卡 —— 空组或跳转卡只会让维护人员
     * 以为「参数页里还有 AD 设置没配」。细节见下方 {@code advanced} 前的注释。
     */
    public static List<Group> groups() {
        List<Group> groups = new ArrayList<>();

        // ---------------- 1. 基础设置 ----------------
        groups.add(new Group(SiteBranding.GROUP_BASIC, "基础设置",
                "系统外观、文件放在哪台机器上、附件与导出的规则。这些设置对全站生效。",
                null,
                List.of(),
                false,
                List.of(
                        // 1.1 站点品牌
                        new Section("brand", "站点品牌",
                                "决定登录页、侧边栏与浏览器标签上显示的名称、图标与版权文字。",
                                null,
                                List.of("系统名称会出现在浏览器标签与侧边栏顶部；留空会被拒绝保存，"
                                                + "避免出现「保存成功但界面没变」。",
                                        "图标支持 PNG / JPG，单张不超过 10MB；换成文字图标时旧图片会被自动清理。",
                                        "版权文字允许留空 —— 留空时登录页与侧边栏底部不显示这一行。"),
                                null,
                                List.of(
                                        text(SiteBranding.KEY_SITE_NAME, "系统名称",
                                                "显示在浏览器标签与侧边栏顶部的名字，例如「设备借用工单系统」。",
                                                SiteBranding.DEFAULT_SITE_NAME),
                                        text(SiteBranding.KEY_COPYRIGHT, "版权文字",
                                                "显示在登录页与侧边栏底部的一行小字，例如「© 2026 XX公司 版权所有」。"
                                                        + "留空则不显示这一行。",
                                                SiteBranding.DEFAULT_COPYRIGHT),
                                        // 站点图标带「上传图片 / 恢复默认」两个按钮，跨度是整行；
                                        // 排到最后，是为了让上面两个中等宽度的项并成一行、不留半行空位。
                                        text(SiteBranding.KEY_SITE_LOGO, "系统图标",
                                                "登录页与侧边栏的图标，可上传图片或使用文字缩写。",
                                                SiteBranding.DEFAULT_LOGO_TEXT, "logo"))),
                        // 1.2 文件存储
                        new Section("storage", "文件存储",
                                "附件与导出文件落在哪台机器上、留多久。主备双机部署时两台机器必须挂同一个目录。",
                                null,
                                List.of(StorageSettings.REFERENCE_NOTES),
                                null,
                                List.of(
                                        text(StorageSettings.KEY_ATTACHMENT_PATH, "附件存储目录",
                                                "附件落盘目录，可填写 NAS 挂载路径（如 /mnt/nas/attachments）。"
                                                        + "留空则用部署配置里的本地目录。",
                                                StorageSettings.DEFAULT_ATTACHMENT_PATH),
                                        number(StorageSettings.KEY_ATTACHMENT_RETENTION_DAYS, "已删工单附件保留", "天",
                                                "工单被删除后，其附件保留多少天再物理删除。这是误删恢复的最后机会，不宜过短。",
                                                String.valueOf(StorageSettings.DEFAULT_ATTACHMENT_RETENTION_DAYS)),
                                        number(StorageSettings.KEY_EXPORT_RETENTION_DAYS, "导出文件保留", "天",
                                                "导出的 Excel 临时文件保留多少天。导出文件可随时重新生成，无需长期留存。",
                                                String.valueOf(StorageSettings.DEFAULT_EXPORT_RETENTION_DAYS)))),
                        // 1.3 附件与导出
                        new Section("attach", "附件与导出",
                                "附件的容量与类型限制，以及导出 Excel 的触发方式与临时文件、无主文件的处理规则。",
                                null,
                                List.of("导出行数超过阈值时不再同步返回，而是转为后台生成、生成完再下载。",
                                        "无主文件指「已落盘但没有对应记录」的文件，宽限期太短会把正在写入的文件误删。"),
                                null,
                                List.of(
                                        number("export_async_threshold", "异步导出阈值", "行",
                                                "导出行数超过这个值时改为后台生成，避免页面长时间等待。",
                                                "10000"),
                                        number("export_zombie_timeout_minutes", "导出超时判定", "分钟",
                                                "导出任务超过这个时长仍未完成则判定为失败。小于 5 分钟会误杀大导出。",
                                                "30"),
                                        number("export_orphan_grace_hours", "导出无主文件宽限期", "小时",
                                                "导出文件多久未被引用才判定为可清理，留出下载时间余量。",
                                                "12"),
                                        // 附件与导出是两件事，各归一组：上面三项都属「导出」，
                                        // 这一项属「附件」，单独收尾比混在中间好找。
                                        number("attachment_orphan_grace_hours", "附件无主文件宽限期", "小时",
                                                "附件落盘后多久仍未关联记录才判定为无主文件，留出写入时间余量。",
                                                "24"))))));

        // ---------------- 2. 通知与验证 ----------------
        // 【为什么把两个渠道开关从「登录与安全」搬到这里】
        // 的本质是「通道开关 ↔ 通道参数」的联动，两者必须同屏可见：
        // 开关在上一张卡、被它控制的参数在下一张卡，用户根本看不出因果。
        // 搬家不涉及 DDL —— 配置键仍是 sms_verify_enabled / email_verify_enabled，
        // config_group 列也不动（那一列只影响旧扁平接口的排序，与卡片无关）。
        groups.add(new Group("notify", "通知与验证",
                "短信与邮箱两条通道的开关和参数，以及验证码的基本设置。"
                        + "关闭某条通道后，用户端对应的绑定与自助找回入口会同步隐藏。",
                null,
                List.of(),
                false,
                List.of(
                        // 2.1 短信通道（含总开关；参数受开关控制）
                        new Section("sms", "短信通知",
                                "短信通道的开关与参数。关闭后，用户端不再出现「绑定手机号」入口。",
                                "暂未启用",
                                List.of(SmsSettings.REFERENCE_NOTES),
                                ContactRecovery.KEY_SMS_ENABLED,
                                List.of(
                                        toggle(ContactRecovery.KEY_SMS_ENABLED, "启用短信通知",
                                                "关闭后，用户将无法绑定手机号、无法通过短信验证码登录或找回密码。"),
                                        select(SmsSettings.KEY_PROVIDER, "服务商",
                                                "计划使用的短信服务商；接入网关后据此选择调用方式。",
                                                SmsSettings.DEFAULT_PROVIDER),
                                        text(SmsSettings.KEY_ACCESS_KEY_ID, "AccessKey ID",
                                                "服务商控制台里创建的访问密钥 ID。",
                                                ""),
                                        password(SmsSettings.KEY_ACCESS_KEY_SECRET, "AccessKey Secret",
                                                "访问密钥密文。加密存储，页面上只显示掩码。",
                                                cipherPurposeOf(SmsSettings.KEY_ACCESS_KEY_SECRET)),
                                        text(SmsSettings.KEY_SIGN_NAME, "短信签名",
                                                "短信正文开头显示的品牌名，需在服务商后台备案通过。",
                                                ""),
                                        text(SmsSettings.KEY_TEMPLATE_CODE, "模板代码",
                                                "验证码短信使用的模板编号，不同服务商格式不同。",
                                                ""))),
                        // 2.2 邮箱通道（含总开关；参数受开关控制）
                        new Section("mail", "邮件通知",
                                "邮箱通道的开关与 SMTP 参数。关闭后，用户端不再出现「绑定邮箱」入口。",
                                null,
                                List.of(MailSettings.REFERENCE_NOTES),
                                ContactRecovery.KEY_EMAIL_ENABLED,
                                List.of(
                                        toggle(ContactRecovery.KEY_EMAIL_ENABLED, "启用邮箱通知",
                                                "关闭后，用户将无法绑定邮箱、无法通过邮箱接收通知和验证码。"),
                                        // 点名的例子：「SMTP 服务器地址 + 端口 + SSL 放一组」。
                                        // 这三项是「连上服务器」的三件套，必须相邻；
                                        // 账号与授权码属于「凭据」，排在它们之后才符合排障顺序（先通、再登）。
                                        text(MailSettings.KEY_HOST, "SMTP 服务器地址",
                                                "发信服务器地址，例如 QQ 邮箱是 smtp.qq.com。留空表示暂不使用邮件。",
                                                ""),
                                        number(MailSettings.KEY_PORT, "SMTP 端口", "",
                                                "常用 465（SSL 加密）。企业内网自建中继请填对方提供的端口。",
                                                String.valueOf(MailSettings.DEFAULT_PORT)),
                                        toggle(MailSettings.KEY_SSL, "启用 SSL 加密",
                                                "465 端口需要开启；用 587 端口的服务商请先确认是否支持隐式 SSL。"),
                                        text(MailSettings.KEY_USERNAME, "发件邮箱账号",
                                                "同时作为 SMTP 登录账号与发件地址，请填完整邮箱地址。",
                                                ""),
                                        password(MailSettings.KEY_PASSWORD, "SMTP 授权码",
                                                "邮箱后台生成的授权码，不是登录密码。加密存储，页面上只显示掩码。",
                                                cipherPurposeOf(MailSettings.KEY_PASSWORD)),
                                        text(MailSettings.KEY_FROM_NAME, "发件人显示名",
                                                "收件人看到的发件人名字，留空则显示「设备借用工单系统」。",
                                                MailSettings.DEFAULT_FROM_NAME))),
                        // 2.3 验证码设置（与渠道开关彼此独立）
                        new Section("code", "验证码设置",
                                "验证码本身的位数与有效期，与上面两条通道的开关彼此独立。",
                                null,
                                List.of("通道全部关闭时这两项仍然可调 —— 它们决定的是「码长什么样」，"
                                        + "不是「码走哪条路」；重新开启通道后立即按这里的设置生效。"),
                                null,
                                List.of(
                                        number(ContactRecovery.KEY_CODE_LENGTH, "验证码位数", "位",
                                                "发给用户的验证码长度。太短容易被猜中，太长用户抄不对，建议 6 位。",
                                                "6"),
                                        number(ContactRecovery.KEY_CODE_EXPIRE_MINUTES, "验证码有效期", "分钟",
                                                "验证码从发出到失效的时间。太长会放大「验证码被别人看到」的风险。",
                                                "5"))))));

        // ---------------- 3. 登录与安全 ----------------
        // ：验证渠道开关移出本组（见上方 notify 的注释），
        // 本组从此只讲「密码与登录行为」，不再横跨通知能力。
        groups.add(new Group("security", "登录与安全",
                "管住「谁能进系统」：密码强度、登录失败锁定与登录频率。验证渠道开关已移到「通知与验证」。",
                null,
                List.of("密码长度与字符种类是下限，调低会让弱口令更容易通过。",
                        "登录失败锁定是「连续错几次就锁多久」，锁定期内即使密码正确也进不去。",
                        "两个验证开关都关掉后，所有人都无法自助找回密码 —— 通知卡会给出黄色警告。"),
                false,
                List.of(
                        // ⚠️  起本组**有两个分区**（本分区 + 下方的「攻击防护」），
                        //    因此它不能再是匿名分区 —— 匿名分区只在「单分区卡片」里成立，
                        //    否则界面上会出现「有内容却没有任何标题」的一节，用户分不清它属于谁。
                        //    （这条约束由 SystemConfigCatalogTest#sections 守着，改错会立刻红。）
                        new Section("security-main", "密码与登录",
                                "密码强度与登录行为（连续失败锁定、登录频率）的阈值。",
                                null, List.of(), null,
                                List.of(
                                        number("password_min_length", "密码最小长度", "位",
                                                "新建与改密时要求的最少字符数。低于 6 位会显著削弱口令强度。",
                                                "8"),
                                        number("password_min_char_types", "密码字符种类", "种",
                                                "要求包含几类字符（大写字母 / 小写字母 / 数字 / 符号），范围 1 ~ 4。",
                                                "2"),
                                        number("login_fail_max_count", "登录失败锁定阈值", "次",
                                                "同一账号连续输错几次密码后锁定。调大会给撞库留出更多尝试次数。",
                                                "5"),
                                        number("login_lock_minutes", "登录锁定时长", "分钟",
                                                "触发锁定后多久自动解锁。锁定期内即使密码正确也无法登录。",
                                                "30"),
                                        number("login_ip_rate_limit_per_minute", "同 IP 登录频率上限", "次/分钟",
                                                "同一来源 IP 每分钟最多能发起几次登录。防「一台机器轮流撞多个账号」。",
                                                "5"),
                                        number("login_account_rate_limit_per_minute", "同账号登录频率上限", "次/分钟",
                                                "同一账号每分钟最多能尝试几次登录。防「多台机器撞同一个账号」。",
                                                "3"),
                                        number("jwt_expire_minutes", "登录有效期", "分钟",
                                                "登录后多久需要重新登录。越短越安全，但用户会频繁被打断。",
                                                "720"))),
                                // 攻击防护：与上面的「登录失败锁定」是同一主题的两半 ——
                                // 上面管「账号」（一个人被锁），这里管「来源 IP」（一个出口被挡）。
                                // 分成两个分区是因为作用对象不同：调账号阈值只影响一个人，
                                // 调 IP 阈值可能影响一整个办公室，顾虑完全不一样。
                                new Section("attack", "攻击防护",
                                        "同一来源 IP 反复尝试登录时的自动封禁，以及永不封禁的白名单。",
                                        null,
                                        List.of("封禁阈值应比账号阈值**宽**：一个 IP 后面往往是整个办公室共用的出口。",
                                                "封禁分两级：累计失败到「一级阈值」短时封禁，到「二级阈值」长时封禁（默认 24 小时）。",
                                                "白名单支持单个 IP 与网段（如 192.168.1.0/24），命中的 IP 永不封禁；默认已预置内网网段。",
                                                "自动封禁到期即自动解封；人工封禁（在安全日志页操作）可以是永久的。"),
                                        SecuritySettings.KEY_IP_BLOCK_ENABLED,
                                        List.of(
                                                toggle(SecuritySettings.KEY_IP_BLOCK_ENABLED, "启用 IP 封禁",
                                                        "关闭后仍会记录安全事件，但不再自动封禁来源 IP。"),
                                                number(SecuritySettings.KEY_IP_BLOCK_MAX_COUNT, "一级封禁阈值", "次",
                                                        "同一 IP 累计失败多少次后触发短时封禁。调小会把「输错密码的正常用户」也封掉。",
                                                        String.valueOf(SecuritySettings.DEFAULT_IP_BLOCK_MAX_COUNT)),
                                                number(SecuritySettings.KEY_IP_BLOCK_MINUTES, "一级封禁时长", "分钟",
                                                        "一级封禁的时长，到期自动解封。调大能压制持续攻击，但误封的代价也随之放大。",
                                                        String.valueOf(SecuritySettings.DEFAULT_IP_BLOCK_MINUTES)),
                                                number(SecuritySettings.KEY_IP_BLOCK_LONG_MAX_COUNT, "二级封禁阈值", "次",
                                                        "累计失败达到该值时改为长时封禁（应明显大于一级阈值）。",
                                                        String.valueOf(SecuritySettings.DEFAULT_IP_BLOCK_LONG_MAX_COUNT)),
                                                number(SecuritySettings.KEY_IP_BLOCK_LONG_MINUTES, "二级封禁时长", "分钟",
                                                        "二级（长时）封禁的时长，默认 1440 分钟（24 小时）。",
                                                        String.valueOf(SecuritySettings.DEFAULT_IP_BLOCK_LONG_MINUTES)),
                                                text(SecuritySettings.KEY_IP_WHITELIST, "IP 白名单",
                                                        "多个用英文逗号分隔，支持网段（如 192.168.1.0/24）。"
                                                                + "白名单内的 IP 永不封禁 —— 办公网出口常被多人共用，误封会整片人上不来。"
                                                                + "默认预置回环与内网网段。",
                                                        SecuritySettings.DEFAULT_IP_WHITELIST),
                                                number(SecuritySettings.KEY_EVENT_RETENTION_DAYS, "安全事件保留", "天",
                                                        "超期事件由每日清理任务删除。太短会让「上周谁在扫我们的账号」查不到。",
                                                        String.valueOf(SecuritySettings.DEFAULT_EVENT_RETENTION_DAYS)),
                                                toggle(SecuritySettings.KEY_LOGIN_ANOMALY_ENABLED, "异常登录提醒",
                                                        "开启后，凌晨 0–6 点登录 / 新设备登录 / 非常用 IP 登录会记录安全事件"
                                                                + "并通知用户本人（站内 + 邮件）；管理员账号凌晨登录会额外告警超管。"))))));

        // ---------------- 3.5 异常告警 ----------------
        // 说明：「系统出现异常时自动发送邮件通知管理员。同时记录详细的异常日志。
        // 这类功能需要频率控制，不能发太频繁，比如同一问题短时间内不重复提醒。」
        // ⇒ 本组的每一项都直接对应那句话里的一个约束。
        groups.add(new Group("alert", "异常告警",
                "未预期异常（5xx / 代码没接住的异常）的邮件与站内消息告警。"
                        + "业务异常（用户填错字段这类 4xx）刻意不告警 —— 否则告警会多到没人看。",
                null,
                List.of("告警只发给全部超级管理员，可在「额外收件人」里再加运维同事的邮箱。",
                        "同一个问题在静默期内只提醒一次，重复发生只累计次数；次数会出现在汇总邮件里。",
                        "静默时段内 P2 级异常不即时发送，攒到次日 09:00 的日报一起发；P0/P1 不受影响。"),
                false,
                List.of(
                        new Section("exception", "异常告警",
                                "异常的采集、分级与通知节奏。关闭总开关后异常仍会记录到「异常日志」，只是不再发送通知。",
                                null,
                                List.of("P0（数据库等）在 1 分钟内发出；P1（网络、未预期）随下一轮汇总发出；"
                                        + "P2（第三方通道等）整点汇总。",
                                        "分类可在「不告警的分类」里整体忽略 —— 例如测试环境把 THIRD_PARTY 静音。"),
                                ExceptionAlertSettings.KEY_ENABLED,
                                List.of(
                                        toggle(ExceptionAlertSettings.KEY_ENABLED, "启用异常告警",
                                                "关闭后异常仍会记录到「异常日志」页，但不再发送站内消息与邮件。"),
                                        number(ExceptionAlertSettings.KEY_SILENCE_MINUTES, "同类静默期", "分钟",
                                                "同一问题在这个时间内重复出现只累计次数、不重复提醒。调小会让邮箱更快被刷屏。",
                                                String.valueOf(ExceptionAlertSettings.DEFAULT_SILENCE_MINUTES)),
                                        number(ExceptionAlertSettings.KEY_SUMMARY_MINUTES, "汇总周期", "分钟",
                                                "待告警异常合并成一封邮件的节奏。调大能减少邮件数量，但发现故障会更慢。",
                                                String.valueOf(ExceptionAlertSettings.DEFAULT_SUMMARY_MINUTES)),
                                        text(ExceptionAlertSettings.KEY_EXTRA_RECIPIENTS, "额外收件人邮箱",
                                                "多个用英文逗号分隔。他们只收邮件（没有系统账号，收不到站内消息）；"
                                                        + "仅 P0 / P1 级告警会发给他们。",
                                                ExceptionAlertSettings.DEFAULT_EXTRA_RECIPIENTS),
                                        text(ExceptionAlertSettings.KEY_IGNORE_CATEGORIES, "不告警的分类",
                                                "可选值：DATABASE、NETWORK、THIRD_PARTY、PARAM、BUSINESS、UNKNOWN。"
                                                        + "多个用英文逗号分隔，留空表示全部分类都告警。",
                                                ExceptionAlertSettings.DEFAULT_IGNORE_CATEGORIES),
                                        text(ExceptionAlertSettings.KEY_QUIET_HOURS, "静默时段",
                                                "格式 HH:mm-HH:mm（可跨零点）。该时段内 P2 级异常不即时发送、攒入日报；"
                                                        + "P0 / P1 随时发送。",
                                                ExceptionAlertSettings.DEFAULT_QUIET_HOURS),
                                        number(ExceptionAlertSettings.KEY_RETENTION_DAYS, "异常日志保留", "天",
                                                "超期日志由每日清理任务删除。取证数据不必长期堆积，但太短会查不到上周的事故。",
                                                String.valueOf(ExceptionAlertSettings.DEFAULT_RETENTION_DAYS)))))));

        // ---------------- 4. 借用与审批 ----------------
        groups.add(new Group("borrow", "借用与审批",
                "控制设备借用的时长、顺延次数，以及超时未处理时的提醒与催办节奏。",
                null,
                List.of("临时锁时长决定「提交申请后设备被锁定多久」；锁过期未归还，锁会自动释放。",
                        "顺延次数为 0 表示不允许顺延；催办冷却太短会让审批人收到大量重复提醒。"),
                false,
                List.of(
                        anonymous("borrow-main", List.of(),
                                List.of(
                                        number("lock_timeout_minutes", "临时锁时长", "分钟",
                                                "提交借用申请后设备被锁定的默认时长。",
                                                "5"),
                                        number("lock_timeout_min_minutes", "临时锁时长下限", "分钟",
                                                "管理员手工设置锁时长的最小可填值。",
                                                "1"),
                                        number("lock_timeout_max_minutes", "临时锁时长上限", "分钟",
                                                "管理员手工设置锁时长的最大可填值。",
                                                "60"),
                                        number("borrow_expire_warning_days", "到期提前提醒", "天",
                                                "设备到期前几天开始提醒借用人归还。",
                                                "1"),
                                        number("auto_extend_max_count", "自动顺延次数上限", "次",
                                                "系统自动顺延最多能触发几次。0 表示不允许自动顺延。",
                                                "2"),
                                        number("extend_max_count", "手工顺延次数上限", "次",
                                                "借用人手工申请顺延最多几次。",
                                                "2"),
                                        number("timeout_alert_interval_hours", "超时提醒间隔", "小时",
                                                "工单超时后每隔多久再提醒一次，避免提醒过于频繁。",
                                                "24"),
                                        number("urge_cooldown_minutes", "催办冷却时间", "分钟",
                                                "同一个人两次催办之间的最短间隔，防止重复催办刷屏。",
                                                "60"),
                                        number("approval_timeout_remind_hours", "审批超时提醒", "小时",
                                                "审批人多久未处理就发送超时提醒。",
                                                "24"),
                                        number("approval_device_amount_threshold", "设备金额审批阈值", "元",
                                                "借用设备的金额超过此值时，在预置的借用审批流程里加一级「上级部门主管」审批。"
                                                        + "设备台账里未录入金额的设备按「不超过阈值」处理。",
                                                "5000"),
                                        number("order_submit_rate_limit_per_minute", "提交申请频率上限", "次/分钟",
                                                "同一用户每分钟最多提交几次申请，防止误操作或脚本刷单。",
                                                "3"))))));

        // ---------------- 5. 高级参数 ----------------
        // 原「AD 域控」组已随  下线，提到的第 6 组同样不再出现：
        // ad_sync_enabled / ad_sync_hour 两个参数搬进了 ad_config（迁移 V36），
        // 由「AD 域控配置」页面（/system/ad）统一维护（：AD 的连接 + 同步在一个页面里配完）。
        // 【为什么整组删掉而不是留一张跳转卡】跳转卡会在参数页留下一个「点进去什么都不配」的入口，
        // 维护人员只会以为是页面坏了；AD 的配置入口已经在左侧菜单里独立存在。
        groups.add(new Group("advanced", "高级参数",
                "接口限流与日志留存等兜底配置。默认折叠，设置不当会影响全站可用性，请谨慎修改。",
                null,
                List.of("限流是「防打垮」的兜底阈值，不是业务节流阀；内部系统正常流量远低于默认值。",
                        "白名单支持单个 IP 与网段（CIDR）写法，多个用英文逗号分隔；留空表示所有请求都受限流约束。",
                        "运行时条件引擎开关是回滚闸门：关闭后系统行为与上线条件引擎之前完全一致。"),
                true,
                List.of(
                        // 5.1 限流与日志留存
                        // ⚠️ 本组现在有**两个**分区，因此不能再用法 anonymous(...)：匿名分区（label=null）
                        // 只允许出现在**单分区**分组里 —— 同组多个分区时，用户看到的是一串没有小标题的参数，
                        // 看不出这一节属于谁。这条规矩由 SystemConfigCatalogTest#sections 守着
                        // （P0 加第二个分区时正是被它拦下的，不是靠人记得）。
                        new Section("advanced-main", "限流与日志留存",
                                "接口限流、白名单与日志留存的兜底阈值；设置不当会影响全站可用性。",
                                null,
                                List.of(),
                                null,
                                List.of(
                                        toggle("rate_limit_enabled", "启用接口限流",
                                                "总开关。关闭后不再对任何接口限流，仅在排障时临时使用。"),
                                        number("rate_limit_ip_per_minute", "同 IP 每分钟上限", "次",
                                                "同一来源 IP 每分钟最多请求多少次。",
                                                "300"),
                                        number("rate_limit_ip_burst", "同 IP 瞬时并发", "次",
                                                "允许的瞬时突发请求数，超出会立即被拒。",
                                                "60"),
                                        number("rate_limit_anon_per_minute", "未登录每分钟上限", "次",
                                                "未登录请求每分钟上限，登录页与找回密码都算在内。",
                                                "60"),
                                        number("rate_limit_anon_burst", "未登录瞬时并发", "次",
                                                "未登录请求允许的瞬时突发数。",
                                                "20"),
                                        number("rate_limit_user_per_minute", "已登录每分钟上限", "次",
                                                "单个已登录用户每分钟的请求上限。",
                                                "240"),
                                        number("rate_limit_user_burst", "已登录瞬时并发", "次",
                                                "单个已登录用户允许的瞬时突发数。",
                                                "40"),
                                        text("rate_limit_whitelist", "限流白名单",
                                                "不受限流约束的 IP 或网段，例如 10.0.0.0/8。多个用英文逗号分隔。",
                                                ""),
                                        number("operation_log_retention_days", "操作日志保留", "天",
                                                "操作日志保留多少天，超期自动清理。审计要求通常会要求保持较长时间。",
                                                "90"),
                                        toggle("flow_runtime_condition_enabled", "运行时条件引擎",
                                                "控制审批流程中的条件分支是否在运行时求值。"
                                                        + "关闭可回滚到条件引擎上线前的行为。",
                                                "0"))),

                        // 备份（P0）：与上面一组「限流 / 日志留存」同属兜底配置，
                        // 故并入「高级参数」而不是单开一组 —— 单开一组会让左侧目录多出一个
                        // 只有 4 项的组，而「高级参数默认折叠」已经把它们藏好了。
                        // 5.2 数据库备份（P0）
                        new Section("advanced-backup", "数据库备份",
                                "应用内数据库自动备份的开关、时刻与保留策略。",
                                null,
                                List.of(),
                                null,
                                List.of(
                                        toggle("backup_enabled", "启用数据库自动备份",
                                                "总开关。Docker 部署请改用 deploy/backup 备份容器，两者不要同时开启。"
                                                        + "本开关只管自动执行，不影响页面上的手动备份。",
                                                "0"),
                                        number("backup_hour", "自动备份时刻", "点",
                                                "每天几点执行（0-23 整点）。若该时刻应用未运行，恢复后会自动补跑一次。",
                                                "2"),
                                        number("backup_retention_days", "备份保留", "天",
                                                "超期归档只在【本次备份成功后】才被清理，避免备份失败与删旧归档叠加。",
                                                "30"),
                                        text("backup_dir", "备份目录",
                                                "备份文件存放目录，可指向 NAS 挂载点。留空则使用应用配置 app.backup.dir。",
                                                ""))))));

        return groups;
    }

    // ------------------------------------------------------------------
    // 构造助手：把「类型 + 默认值」的样板收敛，避免每项都写一长串参数
    // ------------------------------------------------------------------

    /**
     * 单分区分组里的「匿名分区」：不渲染小标题（见类注释）。
     *
     * @param code  分区编码
     * @param notes 分区底部参考说明
     * @param items 参数项
     */
    private static Section anonymous(String code, List<String> notes, List<Item> items) {
        return new Section(code, null, null, null, notes, null, items);
    }

    private static Item number(String key, String label, String unit, String description, String defaultValue) {
        return number(key, label, unit, description, defaultValue, Type.NUMBER);
    }

    private static Item number(String key, String label, String unit, String description,
                               String defaultValue, Type type) {
        return item(key, label, type, unit, defaultValue, description, null, List.of(), null);
    }

    /**
     * 唯一的「造项」入口。
     *
     * <p>跨度在这里**统一算出**（{@link #layoutOf}），因此下面几个工厂方法与目录里
     * 几十处调用点都不必各自关心布局 —— 只有 {@link #FULL_WIDTH_KEYS} 里的个别项破例。
     */
    private static Item item(String key, String label, Type type, String unit, String defaultValue,
                             String description, String purpose, List<Option> options, String widget) {
        return new Item(key, label, type, unit, defaultValue, description, purpose, options, widget,
                layoutOf(key, type));
    }

    /** 开关项（TOGGLE）：布尔项的单位恒为空串，默认开启 */
    private static Item toggle(String key, String label, String description) {
        return toggle(key, label, description, "1");
    }

    /**
     * 开关项（TOGGLE）· 指定默认值。
     *
     * <p>默认值必须与迁移脚本初值逐字一致 —— 它是「恢复默认」按钮回填的值，
     * 写错会让用户点一下「恢复默认」就把开关从关拨到开（或反之），
     * 且保存前看不出异常。{@code flow_runtime_condition_enabled} 是唯一默认为关的开关。
     */
    private static Item toggle(String key, String label, String description, String defaultValue) {
        return item(key, label, Type.TOGGLE, "", defaultValue, description, null, List.of(), null);
    }

    private static Item text(String key, String label, String description, String defaultValue) {
        return text(key, label, description, defaultValue, null);
    }

    private static Item text(String key, String label, String description, String defaultValue, String widget) {
        return item(key, label, Type.TEXT, "", defaultValue, description, null, List.of(), widget);
    }

    private static Item password(String key, String label, String description, String purpose) {
        return item(key, label, Type.PASSWORD, "", "", description, purpose, List.of(), null);
    }

    private static Item select(String key, String label, String description, String defaultValue) {
        List<Option> options = new ArrayList<>();
        options.add(new Option("", "未选择"));
        for (String code : SmsSettings.PROVIDERS) {
            options.add(new Option(code, SmsSettings.providerLabel(code)));
        }
        return item(key, label, Type.SELECT, "", defaultValue, description, null, options, null);
    }

    /** 密文用途：与 {@code SecretCipher} 的常量一一对应，这里做一次映射以便目录只表达业务含义 */
    public static String cipherPurposeOf(String key) {
        Map<String, String> purposes = new LinkedHashMap<>();
        purposes.put(MailSettings.KEY_PASSWORD, com.enterprise.ticket.security.SecretCipher.PURPOSE_SMTP_PASSWORD);
        purposes.put(SmsSettings.KEY_ACCESS_KEY_SECRET, com.enterprise.ticket.security.SecretCipher.PURPOSE_SMS_SECRET);
        return purposes.get(key);
    }
}
