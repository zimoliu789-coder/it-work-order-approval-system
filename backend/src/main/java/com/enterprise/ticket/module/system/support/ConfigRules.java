package com.enterprise.ticket.module.system.support;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 系统参数校验规则（需求方三波·第一波·「系统参数写入 + 配置页」）
 *
 * <h2>为什么必须有这张表，而不是「存进去就行」</h2>
 * <p>参数一旦可写，它就是<b>能让系统停摆的开关</b>：
 * 把 {@code lock_timeout_minutes} 写成 0，临时锁永不过期，设备会一台台被锁死；
 * 把 {@code jwt_expire_minutes} 写成 0，所有人下一次请求就掉线；
 * 把 {@code password_min_length} 写成 1，密码策略形同虚设。
 * 改造前参数只读，这些问题不存在；开放写入的同时必须把取值范围一起锁住。
 *
 * <h2>为什么是纯函数类</h2>
 * <p>取值校验是<b>与 Spring / 数据库无关的纯逻辑</b>，抽出来就能被单测穷尽覆盖
 * （见 {@code ConfigRulesTest}）—— 而「参数写坏了」这类缺陷一旦上线，
 * 排查成本远高于写几个用例。与 {@code OperationLogLabels} 同一思路。
 *
 * <h2>本表同时是「数值区间」的唯一事实源</h2>
 * <p>{@link SystemConfigCatalog} 负责给配置页描述<b>标签 / 分组 / 控件类型 / 单位 / 说明</b>，
 * 但数值区间<b>不重复声明</b>，一律通过 {@link #ruleOf(String)} 回查本表 ——
 * 区间写两份必然漂移，而漂移的表现是「前端放行、后端拒绝」这种最难向用户解释的失败。
 *
 * <h2>未知键怎么处理</h2>
 * <p>{@code RULES} 里没有的键<b>不做值域校验</b>（仅要求存在且 editable）：
 * 未来新增参数时不必同时改这里，避免「加了个配置项却被旧版本拒绝保存」的耦合。
 * 但类型明显错误（例如布尔键填了中文）仍会由该键自己登记的那条规则拦下。
 */
public final class ConfigRules {

    private ConfigRules() {
    }

    /** 取值类型 */
    public enum Kind {
        /** 整数，必须落在 [min, max] 闭区间 */
        INTEGER,
        /** 布尔：0/1/true/false/yes/no（统一归一化成 0 或 1 落库） */
        BOOLEAN,
        /** 文本：仅做长度与非空校验 */
        TEXT
    }

    /**
     * 单条规则
     *
     * @param kind     类型
     * @param min      整数下界（含）；非整数类型忽略
     * @param max      整数上界（含）；TEXT 时表示长度上限（0 = 使用全局上限）
     * @param required 是否允许留空（留空表示「使用代码默认值」）
     */
    public record Rule(Kind kind, int min, int max, boolean required) {

        public static Rule intRange(int min, int max) {
            return new Rule(Kind.INTEGER, min, max, true);
        }

        public static Rule bool(boolean required) {
            return new Rule(Kind.BOOLEAN, 0, 0, required);
        }

        /** 文本（长度上限走全局 {@link ConfigRules#MAX_TEXT_LENGTH}） */
        public static Rule text(boolean required) {
            return new Rule(Kind.TEXT, 0, 0, required);
        }

        /**
         * 文本 + 显式长度上限。
         *
         * <p>密文类参数必须用它：{@code config_value} 是 VARCHAR(512)，
         * 而密文长度约为「明文长度 × 4/3 + 40」（Base64 + IV + 认证标签 + 前缀）。
         * 若沿用 500 的全局上限，一条 500 字的授权码密文会直接超出列宽，
         * 在保存时报数据库错误 —— 用户看到的是「保存失败」而不是「太长了」。
         */
        public static Rule text(int maxLength, boolean required) {
            return new Rule(Kind.TEXT, 0, maxLength, required);
        }

        /** 是否密文类参数（长度上限另有更严的约束） */
        public boolean secretSized() {
            return kind == Kind.TEXT && max > 0;
        }
    }

    /** 文本类参数最大长度：与 system_config.config_value VARCHAR(512) 对齐并留足余量 */
    private static final int MAX_TEXT_LENGTH = 500;

    /**
     * 密文类参数（SMTP 授权码 / 短信 AccessKey Secret）的<b>明文</b>长度上限。
     *
     * <p>取值理由：真实授权码不超过 64 字符，200 已极为宽松；
     * 按 200 估算密文 ≈ 200 × 4/3 + 40 ≈ 307，安全落在 VARCHAR(512) 之内。
     */
    public static final int MAX_SECRET_LENGTH = 200;

    private static final Map<String, Rule> RULES = buildRules();

    private static Map<String, Rule> buildRules() {
        Map<String, Rule> rules = new LinkedHashMap<>();

        // 临时锁（；锁时长上下限本身也是可配项，此处用其默认上限 60 / 240 兜底）
        rules.put("lock_timeout_minutes", Rule.intRange(1, 60));
        rules.put("lock_timeout_min_minutes", Rule.intRange(1, 60));
        rules.put("lock_timeout_max_minutes", Rule.intRange(1, 240));

        // 借用与顺延（ / ）
        rules.put("auto_extend_max_count", Rule.intRange(0, 10));
        rules.put("extend_max_count", Rule.intRange(0, 10));
        rules.put("borrow_expire_warning_days", Rule.intRange(0, 30));
        rules.put("timeout_alert_interval_hours", Rule.intRange(1, 168));
        rules.put("urge_cooldown_minutes", Rule.intRange(1, 1440));

        // 审批（：审批超时提醒）
        rules.put("approval_timeout_remind_hours", Rule.intRange(1, 720));

        // 数据库备份（P0）
        // backup_hour 收口到 0-23：这是「每天几点」的整点值，越界值会让定时判据永假
        // （当前小时永远不等于 25 ⇒ 任务静默不跑，而界面上开关还开着）。
        // backup_dir 不设 required：留空是**合法语义**（回落到应用配置 app.backup.dir）。
        rules.put("backup_enabled", Rule.bool(true));
        rules.put("backup_hour", Rule.intRange(0, 23));
        rules.put("backup_retention_days", Rule.intRange(1, 365));
        rules.put("backup_dir", Rule.text(false));

        // 设备金额审批阈值（ · ）。
        // 这是**预置借用流程**金额分档的唯一依据：金额 > 阈值 才加一级「上级部门主管」。
        // 下限 0 是必要的：0 表示"任何已录入金额的设备都走大额分支"，
        // 在"我们单位所有借用都要上级批"的场景下是一个合法配置，不该被拒绝。
        // 上限 100 万：超过这个量级的资产不会走借用单，配更大的值只会掩盖单位错误
        // （把 50000 敲成 500000 后界面看不出异常，但分档从此永远走大额）。
        rules.put("approval_device_amount_threshold", Rule.intRange(0, 1_000_000));

        // 运行时条件引擎总开关
        // 用 BOOLEAN 规则而不是「不加规则」：本开关是**回滚闸门**，
        // 一旦被写成 "TRUE" / "yes" / "开" 这类五花八门的写法，
        // 读取侧 getBoolean 的容错范围就成了唯一真相 —— 让写入侧先归一化成 0/1，
        // 回滚时运维看到的值才与代码判定一致。
        rules.put("flow_runtime_condition_enabled", Rule.bool(true));

        // 登录安全（ / ）
        rules.put("login_fail_max_count", Rule.intRange(1, 20));
        rules.put("login_lock_minutes", Rule.intRange(1, 1440));
        // 登录限流拆成「同 IP」「同账号」两个维度（原 login_rate_limit_per_minute 单键已由 V14 移除）：
        // 单键拦不住「一台机器换账号撞库」与「多台机器撞一个账号」两种真实攻击形态
        rules.put("login_ip_rate_limit_per_minute", Rule.intRange(1, 1000));
        rules.put("login_account_rate_limit_per_minute", Rule.intRange(1, 1000));
        rules.put("order_submit_rate_limit_per_minute", Rule.intRange(1, 1000));
        // 全局限流（Docker 部署 + 限流加固）。
        // 上限一律给到 100000/分钟级别：这是「防打垮」的兜底阈值，不是业务节流阀，
        // 内部系统正常流量远低于此；把上限收窄只会制造「运维调不上去」的死锁。
        rules.put("rate_limit_enabled", Rule.bool(true));
        rules.put("rate_limit_ip_per_minute", Rule.intRange(1, 100000));
        rules.put("rate_limit_ip_burst", Rule.intRange(1, 10000));
        rules.put("rate_limit_anon_per_minute", Rule.intRange(1, 100000));
        rules.put("rate_limit_anon_burst", Rule.intRange(1, 10000));
        rules.put("rate_limit_user_per_minute", Rule.intRange(1, 100000));
        rules.put("rate_limit_user_burst", Rule.intRange(1, 10000));
        // 白名单允许为空（表示「谁都受限流约束」），格式为 IP / CIDR，逗号分隔
        rules.put("rate_limit_whitelist", Rule.text(false));
        // 下限 6：即便管理员把策略放宽到最松，也不允许出现 1 位密码
        rules.put("password_min_length", Rule.intRange(6, 64));
        rules.put("password_min_char_types", Rule.intRange(1, 4));
        // 下限 5 分钟：有效期配成 0 会让所有人立刻掉线，属于误操作而非配置意图
        rules.put("jwt_expire_minutes", Rule.intRange(5, 10080));

        // 导出与日志（ / ）
        rules.put("export_async_threshold", Rule.intRange(100, 1_000_000));
        rules.put("operation_log_retention_days", Rule.intRange(7, 3650));

        // 异常告警。
        // 静默期 / 汇总周期的下限是 1 分钟：配成 0 会让「静默期恒成立」（= 永不告警），
        // 而那是**静默故障**——页面上一切正常，只是再也收不到任何告警。
        rules.put("exception_alert_enabled", Rule.bool(true));
        rules.put("exception_alert_silence_minutes", Rule.intRange(1, 1440));
        rules.put("exception_alert_summary_minutes", Rule.intRange(1, 1440));
        // 三个文本项都允许为空：空 = 「不加额外收件人 / 不忽略任何分类 / 不设静默时段」
        rules.put("exception_alert_extra_recipients", Rule.text(false));
        rules.put("exception_alert_ignore_categories", Rule.text(false));
        rules.put("exception_alert_quiet_hours", Rule.text(false));
        // 保留期下限 1 天：配成 0 会让清理任务把「刚刚发生的异常」也删掉，排查时什么都查不到
        rules.put("exception_log_retention_days", Rule.intRange(1, 3650));

        // 安全防护。
        // 封禁阈值下限 2：配成 1 会让「第一次输错密码就封 IP」，正常用户完全无法接受。
        rules.put("security_ip_block_enabled", Rule.bool(true));
        rules.put("security_ip_block_max_count", Rule.intRange(2, 1000));
        // 上限 7 天：再长的自动封禁已经等价于「永久」，而永久封禁应当走人工决策
        rules.put("security_ip_block_minutes", Rule.intRange(1, 10080));
        // 二级（长封）：阈值应明显大于一级（页面文案提示），时长允许到 30 天（43200 分钟）
        rules.put("security_ip_block_long_max_count", Rule.intRange(3, 100000));
        rules.put("security_ip_block_long_minutes", Rule.intRange(1, 43200));
        // 白名单允许为空（= 没有豁免来源），格式为 IP / CIDR，逗号分隔
        rules.put("security_ip_whitelist", Rule.text(false));
        rules.put("security_event_retention_days", Rule.intRange(1, 3650));
        // 异常登录检测开关（P2 安全修复）：布尔，允许 true/false
        rules.put("security_login_anomaly_enabled", Rule.bool(true));

        // 文件维护类（需求方三波·第三波· / 14）
        // 宽限期下限 1 小时：0 会把「落盘与插库之间」的窗口文件误判为孤儿，属于危险配置
        rules.put("attachment_orphan_grace_hours", Rule.intRange(1, 720));
        rules.put("export_orphan_grace_hours", Rule.intRange(1, 720));
        // 僵尸判定下限 5 分钟：太小会把正常进行中的大导出误判为僵尸而置为失败
        rules.put("export_zombie_timeout_minutes", Rule.intRange(5, 1440));

        // AD 域控： 起 AD 配置本体就在独立的 ad_config 表（那组字段必须整体自洽，
        // 键值化后无法约束「端口 636 但没开 SSL」这类矛盾组合，也无法把绑定密码限制在密文列里）。
        //  把原 `ad_sync_enabled` / `ad_sync_hour` 两个参数**也搬进了** ad_config
        // （迁移 V36）—— 于是本文件再没有任何 ad_* 规则。搬走的原因见 SystemConfigService 的注释。

        // 站点品牌（：系统名称 / logo 可配置）。
        // 两者都允许留空 —— 留空表示「回落内置默认值」（设备借用工单系统 / IT），
        // 而不是「把标题变成空白」；回落逻辑在 SiteInfoController 一处完成。
        // 登记它们而不是依赖「未登记键原样保存」，是为了让长度上限（500 字）
        // 对系统名称同样生效：名称会进 <title> 与侧边栏，超长值会把界面撑坏。
        // 系统名称**必填**（required=true）：留空虽然由 siteName() 回落到默认名、界面不会变白，
        // 但那会得到「保存成功、界面却没变」的观感 —— 管理员会以为保存坏了。
        // 直接拒绝空值，让「没生效」这件事在提交处就说清楚。
        rules.put(SiteBranding.KEY_SITE_NAME, Rule.text(true));
        // logo 允许留空（留空 = 回落默认文字图标「IT」），这里与名称刻意不同
        rules.put(SiteBranding.KEY_SITE_LOGO, Rule.text(false));
        // 版权文字（）：与 logo 同口径允许留空 —— 留空 = 不显示这一行。
        // 登记它而不是依赖「未登记键原样保存」，是为了让 500 字上限同样生效：
        // 版权会渲染在登录页与侧边栏底部，超长值会把布局撑坏。
        rules.put(SiteBranding.KEY_COPYRIGHT, Rule.text(false));

        // 找回密码与验证渠道（）。
        //
        // 四项都要登记，理由与站点品牌同源：未登记键「不做值域校验、原样保存」，
        // 而这里的每一项都能让找回密码功能彻底失效 ——
        //   长度写 1      → 验证码退化成 1 位，等于没有防护；
        //   有效期写 0    → 生成即过期，用户永远输不对；
        //   开关填中文    → getBoolean 的容错范围成了唯一真相，界面显示与实际判定可能不一致。
        // 登记后由本表统一归一化（整数钳区间、布尔归一化成 0/1），
        // 运维在库里看到的值与代码判定必然一致。
        //
        // 取值范围：长度 4~10（1 位无意义，超过 10 位用户抄不对）；
        //           有效期 1~60 分钟（够长但不至于让验证码长期有效）。
        rules.put(ContactRecovery.KEY_CODE_LENGTH, Rule.intRange(4, 10));
        rules.put(ContactRecovery.KEY_CODE_EXPIRE_MINUTES, Rule.intRange(1, 60));
        rules.put(ContactRecovery.KEY_SMS_ENABLED, Rule.bool(true));
        rules.put(ContactRecovery.KEY_EMAIL_ENABLED, Rule.bool(true));

        // 邮件通知（）。
        // 全部允许留空 —— 「留空 = 尚未配置」，此时发码退回日志输出
        // （见 RoutingVerificationCodeSender）。
        // 端口用 1~65535 而不是「常用端口枚举」：企业内网常自建中继，枚举会把它们挡在门外。
        rules.put(MailSettings.KEY_HOST, Rule.text(false));
        rules.put(MailSettings.KEY_PORT, Rule.intRange(1, 65535));
        rules.put(MailSettings.KEY_USERNAME, Rule.text(false));
        // 授权码：密文入库，明文长度用更严的上限（见 Rule.text(int, boolean) 注释）
        rules.put(MailSettings.KEY_PASSWORD, Rule.text(MAX_SECRET_LENGTH, false));
        rules.put(MailSettings.KEY_FROM_NAME, Rule.text(false));
        rules.put(MailSettings.KEY_SSL, Rule.bool(false));

        // 短信通知（，预留）。
        // 与邮件同源：留空即可，不做「完整性」判定（见 SmsSettings 类注释）。
        rules.put(SmsSettings.KEY_PROVIDER, Rule.text(false));
        rules.put(SmsSettings.KEY_ACCESS_KEY_ID, Rule.text(false));
        rules.put(SmsSettings.KEY_ACCESS_KEY_SECRET, Rule.text(MAX_SECRET_LENGTH, false));
        rules.put(SmsSettings.KEY_SIGN_NAME, Rule.text(false));
        rules.put(SmsSettings.KEY_TEMPLATE_CODE, Rule.text(false));

        // 文件存储与保留天数（）。
        // 路径上限 300：足以覆盖「/mnt/nas/attachments/ticket-system/prod」这类真实挂载点，
        // 同时避免超长值把界面撑坏。
        rules.put(StorageSettings.KEY_ATTACHMENT_PATH, Rule.text(300, false));
        // 下限 1 天：见 StorageSettings 类注释第二段（删除与落盘之间的时间窗）
        rules.put(StorageSettings.KEY_ATTACHMENT_RETENTION_DAYS, Rule.intRange(1, 3650));
        rules.put(StorageSettings.KEY_EXPORT_RETENTION_DAYS, Rule.intRange(1, 3650));

        return rules;
    }

    /** 是否登记过该键 */
    public static boolean isKnown(String key) {
        return key != null && RULES.containsKey(key);
    }

    /** 已登记的配置键（供测试与文档核对） */
    public static Set<String> knownKeys() {
        return RULES.keySet();
    }

    /**
     * 回查某键的校验规则；未登记返回 {@code null}。
     *
     * <p>供 {@link SystemConfigCatalog} 复用区间声明，避免「前端一套区间、后端一套区间」。
     */
    public static Rule ruleOf(String key) {
        return key == null ? null : RULES.get(key.trim());
    }

    /**
     * 校验并归一化取值
     *
     * @param key      配置键
     * @param rawValue 提交值（可为空字符串，表示清空为「使用代码默认值」）
     * @return 归一化后应落库的字符串
     * @throws BusinessException 取值不合法（{@code CONFIG_VALUE_INVALID}）
     */
    public static String normalize(String key, String rawValue) {
        Rule rule = RULES.get(key);
        String value = rawValue == null ? "" : rawValue.trim();

        if (value.isEmpty()) {
            if (rule != null && rule.required()) {
                throw invalid(key, "不能为空");
            }
            return "";
        }
        // 长度上限：密文类参数自带更严的上限（见 Rule.text(int, boolean)）
        int maxLength = (rule != null && rule.secretSized()) ? rule.max() : MAX_TEXT_LENGTH;
        if (value.length() > maxLength) {
            throw invalid(key, "长度不能超过 " + maxLength + " 个字符");
        }
        if (rule == null) {
            // 未登记键：不做值域校验，原样保存（见类注释）
            return value;
        }
        return switch (rule.kind()) {
            case INTEGER -> normalizeInt(key, value, rule);
            case BOOLEAN -> normalizeBool(key, value);
            case TEXT -> value;
        };
    }

    private static String normalizeInt(String key, String value, Rule rule) {
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw invalid(key, "必须是整数");
        }
        if (parsed < rule.min() || parsed > rule.max()) {
            throw invalid(key, "取值范围为 " + rule.min() + " ~ " + rule.max());
        }
        return String.valueOf(parsed);
    }

    private static String normalizeBool(String key, String value) {
        String normalized = value.toLowerCase();
        return switch (normalized) {
            case "1", "true", "yes" -> "1";
            case "0", "false", "no" -> "0";
            default -> throw invalid(key, "只能填 0 或 1");
        };
    }

    private static BusinessException invalid(String key, String reason) {
        return new BusinessException(ErrorCode.CONFIG_VALUE_INVALID, "参数「" + key + "」" + reason);
    }
}
