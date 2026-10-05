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
 * <h2>「两个都关 = 找回密码整体不可用」这条推导放在这里</h2>
 * <p>它是纯粹的布尔推导（{@code !sms && !email}），与 Spring、数据库都无关，
 * 因此做成静态方法，供服务层与单测共用同一份判定 —— 避免「页面说不可用、
 * 后端仍然放行」这类两处各写一遍导致的漂移。
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
     * 两个验证渠道是否都已关闭。
     *
     * <p>为 {@code true} 时：找回密码整体不可用（登录页隐藏「无法登录？」入口、
     * 后端接口直接拒绝），且首次登录不再弹出绑定引导 —— 因为没有渠道能验证，
     * 强制绑定只会把用户堵在门外（）。
     */
    public static boolean allChannelsDisabled(boolean smsEnabled, boolean emailEnabled) {
        return !smsEnabled && !emailEnabled;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
