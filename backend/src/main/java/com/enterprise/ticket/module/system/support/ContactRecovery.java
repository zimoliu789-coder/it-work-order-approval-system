package com.enterprise.ticket.module.system.support;

import java.util.Set;

/**
 * 联系方式与找回密码相关参数（ / 三 / 五）。
 *
 * <h2>为什么单独建一个类而不是塞进 {@code SiteBranding}</h2>
 * <p>{@code SiteBranding} 的语义是「站点外观（名称 / logo）」，与「账号联系方式与
 * 验证码下发」没有任何关系。两者唯一的共同点只是「都属于仅内置超管可改的参数」——
 * 而「哪些键仅内置超管可改」是一条<b>权限口径</b>，不是一个类该承担的业务语义。
 * 因此这里只登记本需求自己的键，并各自暴露 {@link #ADMIN_ONLY_KEYS}，
 * 由 {@code SystemConfigServiceImpl} 把两组合并判定（见其 {@code isAdminOnlyKey}）。
 * 这样将来再出现第三组「仅内置超管可改」的参数时，只需新增一个类并并入判定，
 * 不必把一个类塑造成「所有受限参数的集合」。
 *
 * <h2>为什么两个开关只把「开关」列进 ADMIN_ONLY_KEYS</h2>
 * <p>的原文是「这两个开关只有 administrator 能改」。验证码长度与有效期属于
 * 常规安全参数，其他超管调整它不会动摇「谁能进系统」这件事；
 * 而验证渠道开关一旦被关掉，<b>全站所有人都无法自助找回密码</b>——
 * 这是「能让别人失去自救能力」的开关，因此和站点品牌同级，只归内置超管。
 *
 * <h2>「是否需要强制绑定」这条推导为什么放在这里</h2>
 * <p>它是纯粹的布尔合取，与 Spring、数据库都无关，因此做成静态方法，
 * 供登录响应（{@code AuthService}）与每请求闸门（{@code JwtAuthenticationFilter}）
 * 共用同一份判定 —— 避免「登录时说不必绑定、刷新页面后又被拦住」这类漂移。
 * 这条规则历史上踩过坑：内置超管豁免曾经只加在登录响应一处，
 * 导致刷新页面后行为变回去（见 {@code AuthService#fillPermissionInfo} 的注释）。
 *
 * <h2>渠道「可用」而不是「开关开着」</h2>
 * <p>本方法收到的第二个参数是 {@code anyChannelUsable} 而不是「开关状态」，
 * 两者的差别是本轮修复的核心：开关开着但 SMTP 没配齐 / 短信网关没接入时，
 * 渠道<b>发不出验证码</b>，此时强制绑定等于把用户永久堵在绑定页上。
 * 可用性判定收敛在 {@link VerificationChannelStatus} 一处，本类不重复推导。
 */
public final class ContactRecovery {

    private ContactRecovery() {
    }

    // ------------------------------------------------------------------
    // 配置键（与 V26 迁移脚本里的 config_key 逐字对应）
    // ------------------------------------------------------------------

    /** 验证码长度（位） */
    public static final String KEY_CODE_LENGTH = "forgot_code_length";

    /** 验证码有效期（分钟） */
    public static final String KEY_CODE_EXPIRE_MINUTES = "forgot_code_expire_minutes";

    /** 是否启用手机验证 */
    public static final String KEY_SMS_ENABLED = "sms_verify_enabled";

    /** 是否启用邮箱验证 */
    public static final String KEY_EMAIL_ENABLED = "email_verify_enabled";

    // ------------------------------------------------------------------
    // 内置默认值（配置行缺失时回落；与 V26 的初值保持一致）
    // ------------------------------------------------------------------

    public static final int DEFAULT_CODE_LENGTH = 6;

    public static final int DEFAULT_CODE_EXPIRE_MINUTES = 5;

    public static final boolean DEFAULT_SMS_ENABLED = true;

    public static final boolean DEFAULT_EMAIL_ENABLED = true;

    // ------------------------------------------------------------------
    // 验证渠道编码（接口入参 contactType 的取值域）
    // ------------------------------------------------------------------

    /** 手机短信 */
    public static final String CONTACT_SMS = "SMS";

    /** 电子邮件 */
    public static final String CONTACT_EMAIL = "EMAIL";

    /** 仅内置超管可改的配置键（见类注释第二段） */
    public static final Set<String> ADMIN_ONLY_KEYS = Set.of(KEY_SMS_ENABLED, KEY_EMAIL_ENABLED);

    /** 是否为「仅内置超管可改」的键 */
    public static boolean isAdminOnlyKey(String key) {
        return key != null && ADMIN_ONLY_KEYS.contains(key.trim());
    }

    /** 渠道是否合法（SMS / EMAIL，忽略大小写与首尾空白） */
    public static boolean isKnownContactType(String contactType) {
        return CONTACT_SMS.equalsIgnoreCase(safe(contactType))
                || CONTACT_EMAIL.equalsIgnoreCase(safe(contactType));
    }

    /** 归一化为大写的渠道编码；非法返回 null */
    public static String normalizeContactType(String contactType) {
        String value = safe(contactType);
        if (CONTACT_SMS.equalsIgnoreCase(value)) {
            return CONTACT_SMS;
        }
        if (CONTACT_EMAIL.equalsIgnoreCase(value)) {
            return CONTACT_EMAIL;
        }
        return null;
    }

    /** 渠道的中文名，用于消息文案与错误提示（唯一出处，避免多处各写一份） */
    public static String label(String contactType) {
        String normalized = normalizeContactType(contactType);
        if (CONTACT_SMS.equals(normalized)) {
            return "手机短信";
        }
        if (CONTACT_EMAIL.equals(normalized)) {
            return "邮箱";
        }
        return "联系方式";
    }

    /**
     * 是否需要把用户强制引导到「绑定联系方式」页。
     *
     * <h2>三条判据，缺一不可</h2>
     * <ol>
     *   <li><b>{@code !hasContact}</b> —— 库中手机与邮箱<b>都</b>为空。
     *       绑了任意一个即视为已完成，不再打扰；</li>
     *   <li><b>{@code anyChannelUsable}</b> —— 至少有一个验证渠道此刻真的能发出验证码。
     *       注意收的是「可用」而不是「开关开着」：SMTP 尚未配齐 / 短信网关尚未接入时
     *       渠道不可用，此时强制引导只会把用户永久堵在绑定页上（他收不到任何验证码）；</li>
     *   <li><b>{@code !builtinAdmin}</b> —— 内置超级管理员单独豁免。
     *       它是「系统永远能被救回来」的最后入口，必须在<b>任何</b>配置状态下都能直达工作台
     *       去配置 SMTP —— 让唯一的救火入口被「自己还没配好的渠道」挡住，是自锁。
     *       超管想绑定时自行进个人资料页操作，不受闸门影响。</li>
     * </ol>
     * <p>第 2 条是治本逻辑（渠道真的就绪才拦人），第 3 条是叠加在内置超管身份上的单独豁免，
     * 两者是<b>与</b>关系：渠道就绪了也不拦超管。
     *
     * @param hasContact        库中是否已有手机号或邮箱
     * @param anyChannelUsable  是否至少有一个渠道真的能发出验证码（见 {@link VerificationChannelStatus#anyUsable()}）
     * @param builtinAdmin      是否为内置超级管理员
     */
    public static boolean requiresContactBinding(boolean hasContact, boolean anyChannelUsable,
                                                 boolean builtinAdmin) {
        return !hasContact && anyChannelUsable && !builtinAdmin;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
