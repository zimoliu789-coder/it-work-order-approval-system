package com.enterprise.ticket.module.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.enterprise.ticket.module.system.dto.vo.ConfigCatalogVO;
import com.enterprise.ticket.module.system.entity.SystemConfig;
import com.enterprise.ticket.module.system.support.MailSettings;
import com.enterprise.ticket.module.system.support.SmsSettings;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 系统配置服务：统一读取  /  中定义的可配置参数。
 */
public interface SystemConfigService extends IService<SystemConfig> {

    String getString(String key, String defaultValue);

    int getInt(String key, int defaultValue);

    boolean getBoolean(String key, boolean defaultValue);

    /**
     * 获取全部配置（含管理界面需要的分组与说明）
     */
    Map<String, String> asMap();

    /**
     * 更新配置项并刷新缓存
     */
    void updateValue(String key, String value);

    // ------------------------------------------------------------------
    // 需求方三波·第一波·：系统参数写入 + 配置页
    // ------------------------------------------------------------------

    /**
     * 管理界面用配置列表。
     *
     * <p>与 {@link #asMap()} 的区别：这是给「人看的」，因此
     * ① 保留分组 / 说明 / 是否可改等元信息；
     * ② <b>排除 {@code internal} 分组</b> —— 那是系统内部状态位（如初始化标记），
     * 让它们出现在参数页只会让运维困惑，甚至诱发误改。
     */
    List<SystemConfig> listForAdmin();

    /**
     * 批量更新配置（整体校验，任一不合法则全部不写入）
     *
     * <p><b>为什么是整体事务而不是逐条尽力而为</b>：参数之间常有隐含的配套关系
     * （例如先放宽了 {@code login_fail_max_count} 又改错了 {@code login_lock_minutes}），
     * 若部分成功，界面会停在一个「一半新一半旧」的中间状态，且用户不知道哪条生效了。
     * 全成或全败的语义最容易被理解和解释。
     *
     * @param values 配置键 → 新值；键不存在抛 {@code CONFIG_KEY_NOT_FOUND}，
     *               不可编辑抛 {@code CONFIG_NOT_EDITABLE}，取值越界抛 {@code CONFIG_VALUE_INVALID}
     * @return 实际发生变更的配置项数量（值未变的项不写库，便于审计区分「真改了」与「重复提交」）
     */
    int updateValues(Map<String, String> values);

    // ---------------- 业务语义化快捷方法 ----------------

    /** 设备临时锁超时（分钟）， */
    int lockTimeoutMinutes();

    /** 自动顺延最大次数， */
    int autoExtendMaxCount();

    /** 主动延期最大次数， */
    int extendMaxCount();

    /** 借用到期提前预警天数， */
    int borrowExpireWarningDays();

    /** 超时告警重复推送间隔（小时）， */
    int timeoutAlertIntervalHours();

    /** 连续登录失败锁定阈值， */
    int loginFailMaxCount();

    /** 登录失败锁定时长（分钟）， */
    int loginLockMinutes();

    /**
     * 登录接口：同 IP 限流（次/分钟），
     *
     * <p>由原「账号 + IP 组合」单键拆分为「同 IP」「同账号」两个维度：
     * 单键只能拦住「同一台机器猜同一个账号」，既拦不住「一台机器换账号撞库」，
     * 也拦不住「多台机器集中猜一个账号」，而这两种恰恰是真实攻击的常见形态。
     */
    int loginIpRateLimitPerMinute();

    /** 登录接口：同账号限流（次/分钟）， —— 与上面的同 IP 维度共同生效 */
    int loginAccountRateLimitPerMinute();

    /** 工单提交限流（次/分钟）， */
    int orderSubmitRateLimitPerMinute();

    // ---------------- 全局限流（Docker 部署 + 限流加固新增） ----------------

    /** 全局 API 限流总开关（关闭后仅保留登录/提交两条接口级限流） */
    boolean apiRateLimitEnabled();

    /** 全局：单 IP 每分钟请求数 */
    int apiRateLimitIpPerMinute();

    /** 全局：单 IP 突发上限（令牌桶容量） */
    int apiRateLimitIpBurst();

    /** 匿名（未登录）来源：单 IP 每分钟请求数，取比已登录更严的值 */
    int apiRateLimitAnonPerMinute();

    /** 匿名来源：单 IP 突发上限 */
    int apiRateLimitAnonBurst();

    /** 已登录用户：每分钟请求数 */
    int apiRateLimitUserPerMinute();

    /** 已登录用户：突发上限 */
    int apiRateLimitUserBurst();

    /**
     * 限流白名单（IP / CIDR，逗号分隔）。
     *
     * <p>命中白名单的来源<b>完全不受限流约束</b>。典型用途：内网监控探针、
     * 批量数据核对脚本、超管自己的固定出口 IP —— 这些流量突发且合法，
     * 被限流挡住会造成「系统误伤自己人」的假故障。
     */
    String rateLimitWhitelist();

    /** 密码最小长度， */
    int passwordMinLength();

    /** 密码最少字符类别数， */
    int passwordMinCharTypes();

    /** JWT 有效期（分钟） */
    int jwtExpireMinutes();

    /** 审批超时提醒时长（小时）， */
    int approvalTimeoutRemindHours();

    /**
     * 设备金额审批阈值（元），；配置键 {@code approval_device_amount_threshold}，默认 5000。
     *
     * <h2>它决定预置借用流程走三级还是四级</h2>
     * <p>：「设备金额 ≤ 5000 元走三级审批；&gt; 5000 元加一级上级部门主管」。
     * 这里的阈值就是那条分界线的唯一来源 —— 预置流程的条件分支在**物化时**读它，
     * 因此管理员在「系统参数 → 借用与审批」里改一个数字即可生效，**不需要画流程图**。
     *
     * <h2>为什么返回 {@link BigDecimal} 而不是 int</h2>
     * <p>被比较的另一侧是 {@code device.amount DECIMAL(12,2)}，可能是 6999.50 这样的小数。
     * 用 int 承载阈值会把「金额 5000.50 是否超过阈值」这类边界交给隐式取整去决定；
     * 用同一种数值类型让比较两侧的语义一致。
     *
     * <p>登记了本方法而不是让调用方自己 {@code getInt}：参数键与默认值只能有一处，
     * 否则「默认 5000」改起来要在两个模块里同步。
     */
    BigDecimal approvalDeviceAmountThreshold();

    /**
     * 催办冷却时长（分钟），；配置键 {@code urge_cooldown_minutes}，默认 60。
     *
     * <p>语义：同一工单的同一审批节点（或同一次归还催办）在该时长内只能催办一次。
     * 做成配置项而非硬编码，便于现场按节奏调整。
     */
    int urgeCooldownMinutes();

    /** 是否启用应用内数据库自动备份（P0）。关闭时定时任务不执行；手动备份不受它影响 */
    boolean backupEnabled();

    /** 每天自动备份的时刻（0-23 整点） */
    int backupHour();

    /** 备份保留天数 */
    int backupRetentionDays();

    /** 备份目录；留空表示使用应用配置 {@code app.backup.dir} */
    String backupDir();

    // ---------------- AD 域控同步 ----------------
    //
    // ：`ad_sync_enabled` / `ad_sync_hour` 两个参数已下线，
    // 搬到 `ad_config.sync_enabled` / `ad_config.sync_hour`（迁移 V36）。
    // 因此这里不再提供 adSyncEnabled() / adSyncHour()，读取方改为 AdConfigService。
    // 【为什么必须搬走而不是两处都能读】两个入口读同一件事，就会出现
    // 「系统参数页改了不生效」或「AD 页改了不生效」这种无法解释的分歧；
    // 而且 AD 的「连接 + 同步」本来就是一页维护（）。

    // ---------------- 运行时条件引擎 ----------------

    /**
     * 运行时条件引擎总开关，配置键 {@code flow_runtime_condition_enabled}，<b>默认 false</b>。
     *
     * <h2>它同时是功能开关与回滚闸门</h2>
     * <p>关闭时（默认）：
     * <ul>
     *   <li>流程仍在<b>提交时一次性定格</b>全部节点，与第二期行为逐行一致；</li>
     *   <li>{@code FlowActivationService#recompute} 的所有调用点短路；</li>
     *   <li>含运行期特性（引用 {@code process.*} 的条件 / onReject 改道 / onTimeout 加签）
     *       的流程定义在<b>发布侧被拒绝</b> —— 避免"配了但静默不生效"。</li>
     * </ul>
     * <p>默认值刻意取 {@code false} 而不是 {@code true}：这是全期改动面最大的一个能力，
     * 上线时应当<b>先允许发布、观察、再开启</b>，而不是一开就是全量生效。
     */
    boolean flowRuntimeConditionEnabled();

    // ---------------- 站点品牌（：系统名称 / logo 可配置） ----------------

    /**
     * 系统名称，配置键 {@code system.site-name}。
     *
     * <p>配置缺失或留空时回落内置默认值「设备借用工单系统」——
     * 因此调用方<b>永远拿到非空值</b>，可直接写进 {@code <title>} 与侧边栏，不必再判空。
     */
    String siteName();
    /**
     * logo 的原始取值，配置键 {@code system.site-logo}。
     *
     * <p>可能是文字（如 {@code IT}），也可能是 {@code FILE:<落盘文件名>}（上传的图片）；
     * 由 {@code SiteBranding} 的两个方法区分形态。配置缺失时回落默认文字。
     */
    String siteLogoRaw();

    /**
     * 版权文字，配置键 {@code system.copyright}（）。
     *
     * <p>与系统名称刻意不同：<b>允许为空</b>，且空值<b>不回落</b>任何默认文案，
     * 原样返回空串。调用方（登录页、侧边栏页脚）据此决定「不渲染这一行」——
     * 说明是「填了就显示，不填就不显示」，服务端替它编一句版权声明是不合适的。
     */
    String siteCopyright();

    // ---------------- 找回密码与验证渠道（ / 三 / 五） ----------------

    /** 找回密码验证码长度（位），配置键 {@code forgot_code_length}，默认 6 */
    int forgotCodeLength();

    /** 找回密码验证码有效期（分钟），配置键 {@code forgot_code_expire_minutes}，默认 5 */
    int forgotCodeExpireMinutes();

    /** 是否启用手机验证（短信渠道），配置键 {@code sms_verify_enabled}，默认开 */
    boolean smsVerifyEnabled();

    /** 是否启用邮箱验证（邮件渠道），配置键 {@code email_verify_enabled}，默认开 */
    boolean emailVerifyEnabled();

    // ------------------------------------------------------------------
    // ：卡片式系统参数页（目录接口）
    // ------------------------------------------------------------------

    /**
     * 系统参数目录：分组 + 中文标签 + 控件类型 + 单位 + 区间 + 说明 + 当前值。
     *
     * <p>数据源是 {@code SystemConfigCatalog}（代码即事实源），当前值取自配置缓存。
     * 密文项下发的 {@code value} 是<b>掩码</b>而非密文（见 {@code ConfigCatalogVO}）。
     */
    ConfigCatalogVO catalog();

    // ------------------------------------------------------------------
    // 邮件通知（）
    // ------------------------------------------------------------------

    /**
     * 生效中的 SMTP 设置（授权码已解密）。
     *
     * <p>授权码解密失败（如 JWT_SECRET 变更导致旧密文不可解）时返回的 {@code password} 为空串，
     * 于是 {@code complete()} 为 {@code false}，发码自动退回日志输出 ——
     * <b>这是刻意的降级方向</b>：宁可「验证码只写日志、运维能捞出来」，也不要
     * 「发信失败、用户既收不到码也看不到原因」。
     */
    MailSettings.Settings mailSettings();

    /**
     * 生效中的短信设置（AccessKey Secret 已解密）—— 预留 +  的「发送测试短信」。
     *
     * <p>与 {@link #mailSettings()} 同源：解密失败返回 {@code null} 而不抛异常，
     * 由 {@code SmsSettings.Settings#problems()} 报成「未填写 / 无法解密」。
     */
    SmsSettings.Settings smsSettings();

    // ------------------------------------------------------------------
    // 文件存储与保留天数（； 的清理任务据此执行）
    // ------------------------------------------------------------------

    /** 生效的附件根目录：配置了就用配置值，否则回落 {@code app.attachment.storage-root} */
    String effectiveAttachmentRoot();

    /** 已删工单附件的保留天数，配置键 {@code attachment_retention_days}，默认 30 */
    int attachmentRetentionDays();

    /** 导出临时文件保留天数，配置键 {@code export_retention_days}，默认 7 */
    int exportRetentionDays();

    // ------------------------------------------------------------------
    // 异常告警
    //
    // 键名与默认值的事实源是 ExceptionAlertSettings —— 这里只做「带兜底的读取」。
    // 每一项的默认值必须与迁移脚本初值一致（否则「恢复默认」会回填一个与库里不同的值）。
    // ------------------------------------------------------------------

    /** 异常告警总开关，配置键 {@code exception_alert_enabled}，默认 true */
    boolean exceptionAlertEnabled();

    /** 同类异常静默期（分钟），配置键 {@code exception_alert_silence_minutes}，默认 5 */
    int exceptionAlertSilenceMinutes();

    /** 汇总周期（分钟），配置键 {@code exception_alert_summary_minutes}，默认 10 */
    int exceptionAlertSummaryMinutes();

    /** 额外收件人邮箱（逗号分隔），配置键 {@code exception_alert_extra_recipients}，默认空 */
    String exceptionAlertExtraRecipients();

    /** 不告警的分类（逗号分隔枚举名），配置键 {@code exception_alert_ignore_categories}，默认空 */
    String exceptionAlertIgnoreCategories();

    /** 静默时段 {@code HH:mm-HH:mm}，配置键 {@code exception_alert_quiet_hours}，默认 22:00-08:00 */
    String exceptionAlertQuietHours();

    /** 异常日志保留天数，配置键 {@code exception_log_retention_days}，默认 90 */
    int exceptionLogRetentionDays();

    // ------------------------------------------------------------------
    // 安全防护
    //
    // ⚠️ 账号锁定用的 login_fail_max_count / login_lock_minutes 是**既有**读取方法
    // （见本接口上方），不属于本组 —— 账号锁定的实现早就在 LoginProtectionService 里。
    // ------------------------------------------------------------------

    /** IP 封禁总开关，配置键 {@code security_ip_block_enabled}，默认 true */
    boolean securityIpBlockEnabled();

    /** 同一 IP 累计失败多少次的封禁阈值（一级），配置键 {@code security_ip_block_max_count}，默认 10 */
    int securityIpBlockMaxCount();

    /** 一级自动封禁时长（分钟），配置键 {@code security_ip_block_minutes}，默认 30 */
    int securityIpBlockMinutes();

    /** 二级（长封）阈值，配置键 {@code security_ip_block_long_max_count}，默认 20 */
    int securityIpBlockLongMaxCount();

    /** 二级（长封）封禁时长（分钟），配置键 {@code security_ip_block_long_minutes}，默认 1440（24 小时） */
    int securityIpBlockLongMinutes();

    /** IP 白名单（逗号分隔，支持网段），配置键 {@code security_ip_whitelist}，默认预置内网网段 */
    String securityIpWhitelist();

    /** 安全事件保留天数，配置键 {@code security_event_retention_days}，默认 90 */
    int securityEventRetentionDays();

    /** 异常登录检测开关，配置键 {@code security_login_anomaly_enabled}，默认 true */
    boolean securityLoginAnomalyEnabled();

    // ------------------------------------------------------------------
    // 初始化（本次新增）
    // ------------------------------------------------------------------

    /**
     * 内置超管的登录名（初始化向导或环境变量固化的值）；未初始化返回 {@code null}。
     *
     * <p>该键属 {@code internal} 分组，**不在系统参数页展示**，也无法被界面修改 ——
     * 它是系统身份的一部分。
     */
    String superAdminUsername();

    /** 固化内置超管登录名（幂等：已存在则覆盖为同一值） */
    void saveSuperAdminUsername(String username);

    /** 生产初始化清库是否已完成（一次性标记） */
    boolean prodInitCompleted();

    /** 置位「生产初始化清库已完成」 */
    void markProdInitCompleted();
}
