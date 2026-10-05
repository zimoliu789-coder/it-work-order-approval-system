package com.enterprise.ticket.module.system.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.SetupKeys;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.permission.BuiltinAdmin;
import com.enterprise.ticket.config.AppProperties;
import com.enterprise.ticket.module.system.dto.vo.ConfigCatalogVO;
import com.enterprise.ticket.module.system.entity.SystemConfig;
import com.enterprise.ticket.module.system.mapper.SystemConfigMapper;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import com.enterprise.ticket.module.system.support.ConfigRules;
import com.enterprise.ticket.module.system.support.ContactRecovery;
import com.enterprise.ticket.module.system.support.ExceptionAlertSettings;
import com.enterprise.ticket.module.system.support.MailSettings;
import com.enterprise.ticket.module.system.support.SiteBranding;
import com.enterprise.ticket.module.system.support.SiteLogoStorage;
import com.enterprise.ticket.module.system.support.SmsSettings;
import com.enterprise.ticket.module.security.support.SecuritySettings;
import com.enterprise.ticket.module.system.support.StorageSettings;
import com.enterprise.ticket.module.system.support.SystemConfigCatalog;
import com.enterprise.ticket.security.SecretCipher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 系统配置服务实现。
 *
 * <p>配置读取频率高（限流、锁超时、密码策略），因此采用进程内缓存 + 定时刷新（60 秒），
 * 更新配置时立即刷新缓存，避免每次请求打库。
 *
 * <h2>密文项（SMTP 授权码 / 短信 AccessKey Secret）的处理口径</h2>
 * <p>三个方向必须一致，缺一个就会出现「页面显示得对、库里存得不对」这类看不见的缺陷：
 * <ul>
 *   <li><b>写入</b>：明文先过 {@link ConfigRules#normalize}（校验长度），再交给
 *       {@link SecretCipher} 按用途加密后落库；</li>
 *   <li><b>下发</b>：只回传掩码 {@link SecretCipher#MASK}，密文不出服务端；</li>
 *   <li><b>再提交</b>：提交值等于掩码 → 视为「用户没碰这个框」，<b>跳过不写</b>。
 *       若不做这一步，页面每保存一次都会把掩码本身当成新授权码加密存进去，
 *       下一次发信就以「****」去认证 —— 故障点与现象相隔极远，极难排查。</li>
 * </ul>
 */
@Slf4j
@Service
public class SystemConfigServiceImpl extends ServiceImpl<SystemConfigMapper, SystemConfig>
        implements SystemConfigService {

    private static final String DEFAULT_GROUP = "common";

    /** 内部状态位分组：不出现在参数设置页（） */
    private static final String INTERNAL_GROUP = "internal";

    /** 附件根目录的最终兜底（连部署配置都缺失时才会用到） */
    private static final String FALLBACK_ATTACHMENT_ROOT = "./data/attachments";

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /**
     * 应用配置。用于「站点品牌仅内置超管可改」判定与附件目录兜底 ——
     * 判定需要内置超管的登录名（来自 {@code app.super-admin.username}），
     * 兜底需要 {@code app.attachment.storage-root}。
     */
    private final AppProperties appProperties;

    /** logo 落盘支撑：仅用于「logo 由图片改为文字」时清掉旧图，别处不经手文件 */
    private final SiteLogoStorage logoStorage;

    /** 凭据加解密：仅密文项（SMTP 授权码 / 短信 Secret）经手 */
    private final SecretCipher secretCipher;

    private volatile boolean loaded = false;

    public SystemConfigServiceImpl(AppProperties appProperties, SiteLogoStorage logoStorage,
                                   SecretCipher secretCipher) {
        this.appProperties = appProperties;
        this.logoStorage = logoStorage;
        this.secretCipher = secretCipher;
    }

    @Override
    public String getString(String key, String defaultValue) {
        ensureLoaded();
        String value = cache.get(key);
        return StringUtils.hasText(value) ? value : defaultValue;
    }

    @Override
    public int getInt(String key, int defaultValue) {
        String value = getString(key, null);
        if (!StringUtils.hasText(value)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            log.warn("系统配置 {} 的值 [{}] 不是合法整数，回退默认值 {}", key, value, defaultValue);
            return defaultValue;
        }
    }

    @Override
    public boolean getBoolean(String key, boolean defaultValue) {
        String value = getString(key, null);
        if (!StringUtils.hasText(value)) {
            return defaultValue;
        }
        String normalized = value.trim().toLowerCase();
        if ("1".equals(normalized) || "true".equals(normalized) || "yes".equals(normalized)) {
            return true;
        }
        if ("0".equals(normalized) || "false".equals(normalized) || "no".equals(normalized)) {
            return false;
        }
        return defaultValue;
    }

    @Override
    public Map<String, String> asMap() {
        ensureLoaded();
        return Collections.unmodifiableMap(new LinkedHashMap<>(cache));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateValue(String key, String value) {
        SystemConfig config = getOne(Wrappers.<SystemConfig>lambdaQuery().eq(SystemConfig::getConfigKey, key));
        if (config == null) {
            config = new SystemConfig();
            config.setConfigKey(key);
            config.setConfigValue(value);
            config.setConfigGroup(DEFAULT_GROUP);
            config.setEditable(true);
            save(config);
        } else {
            config.setConfigValue(value);
            updateById(config);
        }
        cache.put(key, value);
    }

    @Override
    public List<SystemConfig> listForAdmin() {
        // 排除 internal 分组（见接口注释）；按分组 + 键名排序保证界面顺序稳定
        List<SystemConfig> configs = list(Wrappers.<SystemConfig>lambdaQuery()
                .ne(SystemConfig::getConfigGroup, INTERNAL_GROUP)
                .orderByAsc(SystemConfig::getConfigGroup)
                .orderByAsc(SystemConfig::getConfigKey));

        // 站点品牌（）：系统名称与 logo 只有**内置超管**可改，其他超管与 admin 只读置灰。
        //
        // 为什么在这里改写 editable 而不是把 DB 的 editable 置 0：
        // 「不可改」是按**调用者**区分的，而 editable 列是按参数区分的 ——
        // 置 0 会把内置超管自己一起挡掉。
        // 就地改返回对象的字段是安全的：list() 每次返回全新实例，不共享缓存。
        if (!BuiltinAdmin.isCurrentUserBuiltinAdmin(appProperties)) {
            for (SystemConfig config : configs) {
                if (isAdminOnlyKey(config.getConfigKey())) {
                    config.setEditable(false);
                }
            }
        }
        return configs;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateValues(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "没有需要保存的参数");
        }

        // 一次性取出全部配置行：逐个 getOne 在参数页（保存时通常整页提交）会产生几十次查询
        Map<String, SystemConfig> byKey = new LinkedHashMap<>();
        for (SystemConfig config : list()) {
            byKey.put(config.getConfigKey(), config);
        }

        // ---------------- 第一遍：逐项自身校验 + 归一化（先不写库） ----------------
        //
        // 【为什么拆成三遍而不是边校验边写】
        // 第二遍的通道联动校验需要看到**本次提交里所有开关的最终值**。
        // 若边遍历边判定，「同一份提交换个 key 顺序」会得到不同结果 ——
        // 而 JSON 对象的成员顺序对调用方是不可见的契约，这种依赖顺序的行为
        // 会表现为「前端提交能过、脚本提交被拒」的灵异缺陷。
        Map<String, String> pending = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim();
            SystemConfig config = byKey.get(key);
            if (config == null) {
                throw new BusinessException(ErrorCode.CONFIG_KEY_NOT_FOUND, "配置项不存在：" + key);
            }
            if (!Boolean.TRUE.equals(config.getEditable())) {
                // 即使前端已把输入框置灰，服务端也必须拒绝 —— 前端禁用只是体验
                throw new BusinessException(ErrorCode.CONFIG_NOT_EDITABLE, "配置项「" + key + "」不允许在界面修改");
            }
            if (isAdminOnlyKey(key) && !BuiltinAdmin.isCurrentUserBuiltinAdmin(appProperties)) {
                // 站点品牌（含版权文字）、验证渠道开关、以及各类凭据（SMTP 授权码 / 短信 Secret）
                // 都归内置超管专管。其他超管虽然角色也是 super_admin（因此能通过 config:manage
                // 的方法级校验），但**不能**改这些项。这一层不能省：没有它，
                // 「只读」就只是前端置灰的观感，用 curl 直连 PUT /api/system/configs 就能绕过去。
                throw new BusinessException(ErrorCode.CONFIG_NOT_EDITABLE,
                        "配置项「" + key + "」仅内置超级管理员 " + BuiltinAdmin.username(appProperties) + " 可修改");
            }

            // 密文项：提交的是掩码（= 用户没碰这个框）则跳过；否则校验明文长度后加密落库
            if (SystemConfigCatalog.secretKeys().contains(key)) {
                String submitted = entry.getValue() == null ? "" : entry.getValue().trim();
                if (submitted.isEmpty() || SecretCipher.MASK.equals(submitted)) {
                    continue;
                }
                String plain = ConfigRules.normalize(key, submitted);
                pending.put(key, secretCipher.encrypt(plain, SystemConfigCatalog.cipherPurposeOf(key)));
            } else {
                pending.put(key, ConfigRules.normalize(key, entry.getValue()));
            }
        }

        // ---------------- 第二遍：通道联动校验（ / ） ----------------
        assertChannelParamsWritable(pending, byKey);

        // ---------------- 第三遍：真正写库（值未变的项不写） ----------------
        List<SystemConfig> changed = new ArrayList<>();
        for (Map.Entry<String, String> entry : pending.entrySet()) {
            String key = entry.getKey();
            SystemConfig config = byKey.get(key);
            String normalized = entry.getValue();
            if (Objects.equals(normalized, config.getConfigValue())) {
                continue;
            }
            // 站点 logo 由「图片」改成「文字」时，磁盘上的旧图再无人引用，必须顺手清掉：
            // 单张上限 10MB，而这条路径正是「把上传的图片换回文字」的入口。
            // 上传接口（SiteInfoController#uploadLogo）只覆盖「换图」，覆盖不到这里 ——
            // 两个入口都要有清理，缺一个就会静默累积出一批读不到的文件。
            if (SiteBranding.KEY_SITE_LOGO.equals(key)) {
                String previousFile = SiteBranding.logoFileName(config.getConfigValue());
                String nextFile = SiteBranding.logoFileName(normalized);
                if (previousFile != null && !previousFile.equals(nextFile)) {
                    logoStorage.deleteQuietly(previousFile);
                }
            }
            config.setConfigValue(normalized);
            changed.add(config);
        }

        if (!changed.isEmpty()) {
            updateBatchById(changed);
        }
        // 立即刷新进程内缓存：参数必须「保存即生效」，
        // 若等 60 秒兜底刷新，验收时看到的会是「改了但没生效」，与 bug 无法区分
        loadFromDb();
        log.info("系统参数已更新：{} 项（提交 {} 项）", changed.size(), values.size());
        return changed.size();
    }

    /**
     * 通道联动校验：通道关闭时，它的参数不允许被修改（ ·  / 六）。
     *
     * <h2>为什么这条校验要看「提交前」和「提交后」两个时刻的并集</h2>
     * <p>只看其中一个时刻都会把正常的保存动作拦死，而两次失败的场景刚好相反：
     * <ul>
     *   <li><b>只看提交前</b>：管理员把关闭的通道重新打开，同时把参数一起填好提交 ——
     *       提交前是「关」，于是这次提交被自己拦死，报「通道未启用」，
     *       而用户刚刚明明打开了它。这是最让人费解的一类报错。</li>
     *   <li><b>只看提交后</b>：管理员在通道开启时改好参数，顺手把它关掉一起提交 ——
     *       提交后是「关」，同样被自己拦死。而「先把参数配好、但暂时不启用」
     *       恰恰是最常见的准备动作。</li>
     * </ul>
     * 因此判据是：<b>本次提交前后只要有一刻通道是开着的，就允许写它的参数</b>。
     * 真正要拦的是第三种情形 —— 通道一直关着（提交前后都是关），
     * 却有人直接 curl PUT 往里塞参数；那种写入在界面上本来就不可能发生
     * （参数框是灰的），拦下它才能让「灰掉不可编辑」这句话对脚本同样成立。
     *
     * <h2>为什么开关自身必须豁免</h2>
     * <p>{@code SystemConfigCatalog.Section#controlledItems()} 已经把开关自己排除掉了。
     * 若开关也受自己控制，一旦关掉就再也没有入口打开它 —— 参数页会把自己锁死。
     */
    private void assertChannelParamsWritable(Map<String, String> pending, Map<String, SystemConfig> byKey) {
        for (String key : pending.keySet()) {
            SystemConfigCatalog.Section section = SystemConfigCatalog.sectionOf(key);
            if (section == null || !section.controlled()) {
                continue;
            }
            String switchKey = section.dependsOnKey();
            if (switchKey.equals(key)) {
                // 开关自身：不受自己控制（见方法注释）
                continue;
            }
            String before = storedValue(byKey.get(switchKey));
            String after = pending.getOrDefault(switchKey, before);
            if (!switchEnabled(before) && !switchEnabled(after)) {
                throw new BusinessException(ErrorCode.CONFIG_CHANNEL_DISABLED,
                        "「" + section.label() + "」当前未启用，无法修改它的参数。"
                                + "请先打开该通道的启用开关，保存后再修改参数。");
            }
        }
    }

    private static String storedValue(SystemConfig config) {
        return config == null ? null : config.getConfigValue();
    }

    /**
     * 开关是否处于「开」。
     *
     * <p>只有明确写成假值（{@code 0 / false / no}）才算关；配置行缺失或留空一律按「开」处理。
     * 这个方向是刻意的（fail-open）：
     * <ul>
     *   <li>开关的内置默认就是开（见 {@code ContactRecovery.DEFAULT_*_ENABLED}）；</li>
     *   <li>「行缺失」发生在旧库尚未执行对应迁移时，此时若按「关」处理，
     *       管理员会面对一个空开关 + 连参数都保存不了的两难；</li>
     *   <li>本方法守护的是「界面一致」，不是安全边界 ——
     *       真正的安全边界（渠道关闭后拒绝发码 / 绑定）在
     *       {@code ForgotPasswordService} 与 {@code ContactBindCodeService} 里，
     *       那里判的是配置缓存的实际布尔值，与此处无耦合。</li>
     * </ul>
     */
    private static boolean switchEnabled(String raw) {
        if (raw == null) {
            return true;
        }
        String value = raw.trim().toLowerCase();
        return !("0".equals(value) || "false".equals(value) || "no".equals(value));
    }

    @Override
    public ConfigCatalogVO catalog() {
        ensureLoaded();
        boolean builtinAdmin = BuiltinAdmin.isCurrentUserBuiltinAdmin(appProperties);

        // 取一次库内的行：既要当前值，也要 DB 上的 editable 标记 ——
        // 「目录里有、库里没有行」时若仍显示成可编辑，用户会遇到「填了却保存失败」，
        // 因此 editable 必须同时满足「行存在」「DB 允许」「调用者有权限」三个条件。
        Map<String, SystemConfig> rows = new LinkedHashMap<>();
        for (SystemConfig config : list()) {
            rows.put(config.getConfigKey(), config);
        }

        List<ConfigCatalogVO.GroupVO> groups = new ArrayList<>();
        for (SystemConfigCatalog.Group group : SystemConfigCatalog.groups()) {
            List<ConfigCatalogVO.ItemVO> items = new ArrayList<>();
            List<ConfigCatalogVO.SectionVO> sections = new ArrayList<>();
            for (SystemConfigCatalog.Section section : group.sections()) {
                List<ConfigCatalogVO.ItemVO> sectionItems = new ArrayList<>();
                for (SystemConfigCatalog.Item item : section.items()) {
                    sectionItems.add(toItemVO(item, rows, builtinAdmin));
                }
                // 扁平视图与分区视图**共享同一批 ItemVO 实例**（不是各 new 一份）：
                // 两份分别构造会让「同一项在两个数组里是两个对象」，将来往其中一份
                // 加字段时另一份会静默不同步 —— 这类不一致在页面上表现为
                // 「搜索能搜到、渲染区却没有」这种极难定位的现象。
                items.addAll(sectionItems);
                sections.add(new ConfigCatalogVO.SectionVO(
                        section.code(), section.label(), section.description(), section.badge(),
                        section.notes(), section.dependsOnKey(), sectionItems));
            }
            groups.add(new ConfigCatalogVO.GroupVO(
                    group.code(), group.label(), group.description(), group.badge(),
                    group.notes(), group.collapsed(), sections, items));
        }
        return new ConfigCatalogVO(groups);
    }

    /**
     * 组装单个参数项的下发视图。
     *
     * <p>抽成方法是因为 之后的调用点从 1 处变成「每个分区各一处」，
     * 而这段逻辑里有三件事只允许有一份实现：当前值取值（含密文打码）、
     * editable 的三条件判定、区间回查 {@link ConfigRules}。
     */
    private ConfigCatalogVO.ItemVO toItemVO(SystemConfigCatalog.Item item,
                                            Map<String, SystemConfig> rows, boolean builtinAdmin) {
        SystemConfig row = rows.get(item.key());
        boolean adminOnly = isAdminOnlyKey(item.key());
        boolean editable = row != null
                && Boolean.TRUE.equals(row.getEditable())
                && (builtinAdmin || !adminOnly);

        String value;
        if (row == null) {
            value = item.secret() ? "" : item.defaultValue();
        } else if (item.secret()) {
            // 密文不出服务端：配过就给掩码，没配过给空串
            value = StringUtils.hasText(row.getConfigValue()) ? SecretCipher.MASK : "";
        } else {
            value = row.getConfigValue() == null ? "" : row.getConfigValue();
        }

        ConfigRules.Rule rule = ConfigRules.ruleOf(item.key());
        Integer min = null;
        Integer max = null;
        Integer maxLength = null;
        boolean required = false;
        if (rule != null) {
            required = rule.required();
            if (rule.kind() == ConfigRules.Kind.INTEGER) {
                min = rule.min();
                max = rule.max();
            } else if (rule.secretSized()) {
                maxLength = rule.max();
            }
        }

        List<ConfigCatalogVO.OptionVO> options = new ArrayList<>();
        for (SystemConfigCatalog.Option option : item.options()) {
            options.add(new ConfigCatalogVO.OptionVO(option.value(), option.label()));
        }

        return new ConfigCatalogVO.ItemVO(
                item.key(), item.label(), item.type().name(), item.unit(),
                value, item.defaultValue(), item.description(),
                editable, adminOnly, item.secret(), required,
                item.widget(), options, min, max, maxLength, item.layout().name());
    }

    @Override
    public MailSettings.Settings mailSettings() {
        return new MailSettings.Settings(
                getString(MailSettings.KEY_HOST, ""),
                getInt(MailSettings.KEY_PORT, MailSettings.DEFAULT_PORT),
                getString(MailSettings.KEY_USERNAME, ""),
                // 解密失败返回 null（例如 JWT_SECRET 变更导致旧密文不可解）：
                // 此处**刻意不抛异常**，让它退化成「授权码为空」→ complete() = false → 发码回退日志。
                secretCipher.decrypt(getString(MailSettings.KEY_PASSWORD, ""),
                        SecretCipher.PURPOSE_SMTP_PASSWORD),
                getString(MailSettings.KEY_FROM_NAME, ""),
                getBoolean(MailSettings.KEY_SSL, MailSettings.DEFAULT_SSL));
    }

    @Override
    public SmsSettings.Settings smsSettings() {
        return new SmsSettings.Settings(
                getString(SmsSettings.KEY_PROVIDER, SmsSettings.DEFAULT_PROVIDER),
                getString(SmsSettings.KEY_ACCESS_KEY_ID, ""),
                // 与 SMTP 授权码同口径：解密失败返回 null 而不是抛异常。
                // 让「密钥解不开」退化成「Secret 为空」，由 problems() 报成
                // 「未填写 AccessKey Secret（或密文无法解密，请重新保存）」——
                // 直接抛异常会让「发送测试短信」在密钥轮换后变成一个报数据库错的黑盒。
                secretCipher.decrypt(getString(SmsSettings.KEY_ACCESS_KEY_SECRET, ""),
                        SecretCipher.PURPOSE_SMS_SECRET),
                getString(SmsSettings.KEY_SIGN_NAME, ""),
                getString(SmsSettings.KEY_TEMPLATE_CODE, ""));
    }

    @Override
    public String effectiveAttachmentRoot() {
        String fallback = FALLBACK_ATTACHMENT_ROOT;
        if (appProperties.getAttachment() != null
                && StringUtils.hasText(appProperties.getAttachment().getStorageRoot())) {
            fallback = appProperties.getAttachment().getStorageRoot();
        }
        return StorageSettings.resolveRoot(
                getString(StorageSettings.KEY_ATTACHMENT_PATH, StorageSettings.DEFAULT_ATTACHMENT_PATH),
                fallback);
    }

    @Override
    public int attachmentRetentionDays() {
        // 下限 1 天：清理任务与「工单删除」之间存在时间窗，配成 0 会把仍在引用中的文件删掉
        return Math.max(1, getInt(StorageSettings.KEY_ATTACHMENT_RETENTION_DAYS,
                StorageSettings.DEFAULT_ATTACHMENT_RETENTION_DAYS));
    }

    @Override
    public int exportRetentionDays() {
        return Math.max(1, getInt(StorageSettings.KEY_EXPORT_RETENTION_DAYS,
                StorageSettings.DEFAULT_EXPORT_RETENTION_DAYS));
    }

    // ------------------------------------------------------------------
    // 异常告警
    //
    // 每一项都做**区间收敛**：静默期 / 汇总周期 / 保留天数配成 0 或负数时，
    // 会让「静默期恒成立」（= 永不告警）或「清理任务删光今天的数据」这类静默故障。
    // 收敛在读取侧而不是校验侧：系统参数页允许管理员填任意数字，
    // 真正生效的值必须在这里被夹住，才不会有「页面显示 0、实际按 1 跑」的错位。
    // ------------------------------------------------------------------

    @Override
    public boolean exceptionAlertEnabled() {
        return getBoolean(ExceptionAlertSettings.KEY_ENABLED, ExceptionAlertSettings.DEFAULT_ENABLED);
    }

    @Override
    public int exceptionAlertSilenceMinutes() {
        return Math.max(1, getInt(ExceptionAlertSettings.KEY_SILENCE_MINUTES,
                ExceptionAlertSettings.DEFAULT_SILENCE_MINUTES));
    }

    @Override
    public int exceptionAlertSummaryMinutes() {
        return Math.max(1, getInt(ExceptionAlertSettings.KEY_SUMMARY_MINUTES,
                ExceptionAlertSettings.DEFAULT_SUMMARY_MINUTES));
    }

    @Override
    public String exceptionAlertExtraRecipients() {
        return getString(ExceptionAlertSettings.KEY_EXTRA_RECIPIENTS,
                ExceptionAlertSettings.DEFAULT_EXTRA_RECIPIENTS);
    }

    @Override
    public String exceptionAlertIgnoreCategories() {
        return getString(ExceptionAlertSettings.KEY_IGNORE_CATEGORIES,
                ExceptionAlertSettings.DEFAULT_IGNORE_CATEGORIES);
    }

    @Override
    public String exceptionAlertQuietHours() {
        return getString(ExceptionAlertSettings.KEY_QUIET_HOURS,
                ExceptionAlertSettings.DEFAULT_QUIET_HOURS);
    }

    @Override
    public int exceptionLogRetentionDays() {
        // 下限 1 天：配成 0 会让每日清理把「刚刚发生的异常」也删掉，排查时什么都查不到
        return Math.max(1, getInt(ExceptionAlertSettings.KEY_RETENTION_DAYS,
                ExceptionAlertSettings.DEFAULT_RETENTION_DAYS));
    }

    // ------------------------------------------------------------------
    // 安全防护
    //
    // 与异常告警同一取向：所有阈值都在读取侧做区间收敛 ——
    // 封禁阈值配成 0 会让**每一个** IP 在第一次失败时就被封（包括管理员自己），
    // 封禁时长配成 0 则等于不封。收敛放这里，页面上填什么都拦不住它生效。
    // ------------------------------------------------------------------

    @Override
    public boolean securityIpBlockEnabled() {
        return getBoolean(SecuritySettings.KEY_IP_BLOCK_ENABLED, SecuritySettings.DEFAULT_IP_BLOCK_ENABLED);
    }

    @Override
    public int securityIpBlockMaxCount() {
        // 下限 2 次：配成 1 会让「第一次输错密码就封 IP」，正常用户完全无法接受
        return Math.max(2, getInt(SecuritySettings.KEY_IP_BLOCK_MAX_COUNT,
                SecuritySettings.DEFAULT_IP_BLOCK_MAX_COUNT));
    }

    @Override
    public int securityIpBlockMinutes() {
        return Math.max(1, getInt(SecuritySettings.KEY_IP_BLOCK_MINUTES,
                SecuritySettings.DEFAULT_IP_BLOCK_MINUTES));
    }

    @Override
    public int securityIpBlockLongMaxCount() {
        // 下限取二级阈值必须 > 一级阈值，否则两级会被合并成一档；此处只做基本兜底
        return Math.max(3, getInt(SecuritySettings.KEY_IP_BLOCK_LONG_MAX_COUNT,
                SecuritySettings.DEFAULT_IP_BLOCK_LONG_MAX_COUNT));
    }

    @Override
    public int securityIpBlockLongMinutes() {
        return Math.max(1, getInt(SecuritySettings.KEY_IP_BLOCK_LONG_MINUTES,
                SecuritySettings.DEFAULT_IP_BLOCK_LONG_MINUTES));
    }

    @Override
    public String securityIpWhitelist() {
        // ⚠️ 刻意**不走 getString(key, default)**：后者在「值存在但为空」时会回落到默认值，
        // 于是管理员把白名单清空（意图＝「没有任何豁免」）会静默变成「用默认的内网网段」——
        // 一个把「清空」当成「恢复默认」的陷阱。这里区分两种情形：
        //   · 配置行不存在（raw == null）→ 用默认值（新库/未登记）；
        //   · 配置行存在且为空串     → 尊重为空（＝无豁免）。
        String raw = rawValue(SecuritySettings.KEY_IP_WHITELIST);
        return raw == null ? SecuritySettings.DEFAULT_IP_WHITELIST : raw;
    }

    /** 读取配置原始值（不回落默认；键不存在返回 null）——仅用于需要区分「空」与「未设」的项 */
    private String rawValue(String key) {
        ensureLoaded();
        return cache.get(key);
    }

    @Override
    public int securityEventRetentionDays() {
        return Math.max(1, getInt(SecuritySettings.KEY_EVENT_RETENTION_DAYS,
                SecuritySettings.DEFAULT_EVENT_RETENTION_DAYS));
    }

    @Override
    public boolean securityLoginAnomalyEnabled() {
        return getBoolean(SecuritySettings.KEY_LOGIN_ANOMALY_ENABLED,
                SecuritySettings.DEFAULT_LOGIN_ANOMALY_ENABLED);
    }

    // ------------------------------------------------------------------
    // 初始化（本次新增）
    // ------------------------------------------------------------------

    @Override
    public String superAdminUsername() {
        // 用 rawValue 而非 getString(key, default)：本键**没有**默认值语义，
        // 「未初始化」必须是 null（而不是某个兜底登录名），否则向导判断会失真。
        return rawValue(SetupKeys.KEY_SUPER_ADMIN_USERNAME);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveSuperAdminUsername(String username) {
        SystemConfig config = getOne(Wrappers.<SystemConfig>lambdaQuery()
                .eq(SystemConfig::getConfigKey, SetupKeys.KEY_SUPER_ADMIN_USERNAME));
        if (config == null) {
            config = new SystemConfig();
            config.setConfigKey(SetupKeys.KEY_SUPER_ADMIN_USERNAME);
            config.setConfigValue(username);
            // 归 internal 分组：系统参数页不会渲染它，管理员无从在界面上改掉系统身份
            config.setConfigGroup(INTERNAL_GROUP);
            config.setEditable(false);
            config.setConfigDesc("初始化向导固化的内置超管登录名（不可修改、不展示）");
            save(config);
        } else {
            config.setConfigValue(username);
            updateById(config);
        }
        cache.put(SetupKeys.KEY_SUPER_ADMIN_USERNAME, username);
    }

    @Override
    public boolean prodInitCompleted() {
        return "true".equalsIgnoreCase(rawValue(SetupKeys.KEY_PROD_INIT_DONE));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markProdInitCompleted() {
        saveInternalFlag(SetupKeys.KEY_PROD_INIT_DONE, "true", "生产初始化清库完成标记（一次性）");
    }

    /** 写一个 internal 分组的一次性标记（幂等） */
    private void saveInternalFlag(String key, String value, String desc) {
        SystemConfig config = getOne(Wrappers.<SystemConfig>lambdaQuery()
                .eq(SystemConfig::getConfigKey, key));
        if (config == null) {
            config = new SystemConfig();
            config.setConfigKey(key);
            config.setConfigValue(value);
            config.setConfigGroup(INTERNAL_GROUP);
            config.setEditable(false);
            config.setConfigDesc(desc);
            save(config);
        } else {
            config.setConfigValue(value);
            updateById(config);
        }
        cache.put(key, value);
    }

    /**
     * 兜底刷新：即使多实例部署，也能在 60 秒内感知配置变更
     */
    @Scheduled(fixedDelay = 60_000L, initialDelay = 60_000L)
    public void refresh() {
        loadFromDb();
    }

    private void ensureLoaded() {
        if (!loaded) {
            synchronized (this) {
                if (!loaded) {
                    loadFromDb();
                }
            }
        }
    }

    private void loadFromDb() {
        try {
            Map<String, String> fresh = new ConcurrentHashMap<>();
            for (SystemConfig config : list()) {
                fresh.put(config.getConfigKey(), config.getConfigValue() == null ? "" : config.getConfigValue());
            }
            cache.clear();
            cache.putAll(fresh);
            loaded = true;
        } catch (Exception e) {
            // 启动早期数据库可能尚未就绪，不允许因配置读取失败导致应用不可用
            log.warn("加载系统配置失败，本次使用默认值：{}", e.getMessage());
        }
    }

    // ---------------- 业务语义化快捷方法 ----------------

    @Override
    public int lockTimeoutMinutes() {
        return getInt("lock_timeout_minutes", 5);
    }

    @Override
    public boolean backupEnabled() {
        return getBoolean("backup_enabled", false);
    }

    @Override
    public int backupHour() {
        // 收口到 0-23：参数被写坏（例如 25）时按默认的凌晨 2 点处理，
        // 而不是让定时判据永假、任务静默不跑（界面上开关还开着，最难发现的一类失效）
        return Math.min(23, Math.max(0, getInt("backup_hour", 2)));
    }

    @Override
    public int backupRetentionDays() {
        // 下限 1 天：保留 0 天等于「本次成功即删掉刚做出来的归档」
        return Math.max(1, getInt("backup_retention_days", 30));
    }

    @Override
    public String backupDir() {
        return getString("backup_dir", "");
    }

    @Override
    public int autoExtendMaxCount() {
        return getInt("auto_extend_max_count", 2);
    }

    @Override
    public int extendMaxCount() {
        return getInt("extend_max_count", 2);
    }

    @Override
    public int borrowExpireWarningDays() {
        return getInt("borrow_expire_warning_days", 1);
    }

    @Override
    public int timeoutAlertIntervalHours() {
        return getInt("timeout_alert_interval_hours", 24);
    }

    @Override
    public int loginFailMaxCount() {
        return getInt("login_fail_max_count", 5);
    }

    @Override
    public int loginLockMinutes() {
        // P2 安全修复：默认锁定 30 分钟（原 15 分钟），与需求清单口径一致
        return getInt("login_lock_minutes", 30);
    }

    @Override
    public int loginIpRateLimitPerMinute() {
        return getInt("login_ip_rate_limit_per_minute", 5);
    }

    @Override
    public int loginAccountRateLimitPerMinute() {
        return getInt("login_account_rate_limit_per_minute", 3);
    }

    @Override
    public int orderSubmitRateLimitPerMinute() {
        return getInt("order_submit_rate_limit_per_minute", 3);
    }

    @Override
    public boolean apiRateLimitEnabled() {
        return getBoolean("rate_limit_enabled", true);
    }

    @Override
    public int apiRateLimitIpPerMinute() {
        return getInt("rate_limit_ip_per_minute", 300);
    }

    @Override
    public int apiRateLimitIpBurst() {
        return getInt("rate_limit_ip_burst", 60);
    }

    @Override
    public int apiRateLimitAnonPerMinute() {
        return getInt("rate_limit_anon_per_minute", 60);
    }

    @Override
    public int apiRateLimitAnonBurst() {
        return getInt("rate_limit_anon_burst", 20);
    }

    @Override
    public int apiRateLimitUserPerMinute() {
        return getInt("rate_limit_user_per_minute", 240);
    }

    @Override
    public int apiRateLimitUserBurst() {
        return getInt("rate_limit_user_burst", 40);
    }

    @Override
    public String rateLimitWhitelist() {
        return getString("rate_limit_whitelist", "");
    }

    @Override
    public int passwordMinLength() {
        return getInt("password_min_length", 8);
    }

    @Override
    public int passwordMinCharTypes() {
        return getInt("password_min_char_types", 2);
    }

    @Override
    public int jwtExpireMinutes() {
        return getInt("jwt_expire_minutes", 720);
    }

    @Override
    public int approvalTimeoutRemindHours() {
        return getInt("approval_timeout_remind_hours", 24);
    }

    @Override
    public BigDecimal approvalDeviceAmountThreshold() {
        // 走 getInt 而不是解析 config_value 原文：getInt 内部已处理"值缺失 / 非数字"的兜底，
        // 返回 BigDecimal 只是把同一种数值类型交给比较方（device.amount 是 DECIMAL）。
        return BigDecimal.valueOf(getInt("approval_device_amount_threshold", 5000));
    }

    @Override
    public int urgeCooldownMinutes() {
        // 下限钳到 1 分钟：配成 0 会让冷却形同虚设（可被脚本刷消息）
        return Math.max(getInt("urge_cooldown_minutes", 60), 1);
    }

    @Override
    public boolean flowRuntimeConditionEnabled() {
        // 默认 false 是刻意的（与「AD 自动同步默认关闭」同一取舍，但这个更关键）：
        // 本开关关闭时整个运行时条件引擎不参与任何代码路径，
        // 系统行为与第二期逐行一致 —— 这是本波「零回归」的物理载体。
        return getBoolean("flow_runtime_condition_enabled", false);
    }

    // ---------------- 站点品牌（） ----------------

    @Override
    public String siteName() {
        // 空值回落到内置默认名：调用方（前端标题、登录页）因此不必再判空，
        // 也避免「管理员清空输入框」把整个站点的标题变成空白
        return getString(SiteBranding.KEY_SITE_NAME, SiteBranding.DEFAULT_SITE_NAME);
    }

    @Override
    public String siteLogoRaw() {
        String raw = getString(SiteBranding.KEY_SITE_LOGO, SiteBranding.DEFAULT_LOGO_TEXT);
        return StringUtils.hasText(raw) ? raw : SiteBranding.DEFAULT_LOGO_TEXT;
    }

    @Override
    public String siteCopyright() {
        // 与 siteName() 的关键差别：不回落默认文案（默认值本身就是空串），
        // 也不做 hasText 兜底 —— 空就是空，前端据此决定不渲染页脚那一行。
        // 用 getString 而不是直接读缓存：配置行缺失（旧库未跑 V37）时也要拿到 ""，
        // 而不是 null（null 会在前端模板里渲染成 "null" 两个字）。
        String value = getString(SiteBranding.KEY_COPYRIGHT, SiteBranding.DEFAULT_COPYRIGHT);
        return value == null ? SiteBranding.DEFAULT_COPYRIGHT : value.trim();
    }

    // ---------------- 找回密码与验证渠道（ / 三 / 五） ----------------

    @Override
    public int forgotCodeLength() {
        // 钳到 [4, 10]：写 1 位等于没有验证码；写 20 位用户根本抄不对。
        // 钳制而不是报错，是因为本方法被登录路径（找回密码）调用，
        // 参数页的写入侧本来就有 ConfigRules 的区间校验；这里只是防止
        // 「有人直接改库」把通道彻底堵死。
        int length = getInt(ContactRecovery.KEY_CODE_LENGTH, ContactRecovery.DEFAULT_CODE_LENGTH);
        return Math.max(4, Math.min(length, 10));
    }

    @Override
    public int forgotCodeExpireMinutes() {
        // 下限 1 分钟：配成 0 会让验证码「生成即过期」，用户永远输不对
        int minutes = getInt(ContactRecovery.KEY_CODE_EXPIRE_MINUTES,
                ContactRecovery.DEFAULT_CODE_EXPIRE_MINUTES);
        return Math.max(1, minutes);
    }

    @Override
    public boolean smsVerifyEnabled() {
        return getBoolean(ContactRecovery.KEY_SMS_ENABLED, ContactRecovery.DEFAULT_SMS_ENABLED);
    }

    @Override
    public boolean emailVerifyEnabled() {
        return getBoolean(ContactRecovery.KEY_EMAIL_ENABLED, ContactRecovery.DEFAULT_EMAIL_ENABLED);
    }

    /**
     * 该配置键是否「仅内置超管可改」。
     *
     * <p>三组键分别登记在各自的业务类里（{@link SiteBranding} 站点品牌、
     * {@link ContactRecovery} 验证渠道、{@link SystemConfigCatalog} 凭据类），
     * 这里合并成唯一判定入口 —— 读写两侧（{@code listForAdmin} / {@code catalog}
     * 与 {@code updateValues}）都必须走这里，否则会出现「列表置灰了、接口却能写」
     * 这类前后不一致。
     *
     * <p><b>凭据类为什么也算「仅内置超管」</b>：SMTP 授权码与短信 AccessKey Secret
     * 决定「系统用哪个身份往外发消息」。能替换它的人就能把验证码与通知改发到自己邮箱，
     * 这比「关掉验证渠道」更隐蔽 —— 关掉会被用户立刻发现，替换凭据则一切看起来都正常。
     */
    private boolean isAdminOnlyKey(String key) {
        return SiteBranding.isAdminOnlyKey(key)
                || ContactRecovery.isAdminOnlyKey(key)
                || SystemConfigCatalog.secretKeys().contains(key);
    }
}
