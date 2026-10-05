package com.enterprise.ticket.module.system.support;

import org.springframework.util.StringUtils;

/**
 * 邮件（SMTP）参数契约（）。
 *
 * <h2>为什么抽成一个类</h2>
 * <p>「SMTP 是否配置完整」这条判定有三个消费方：配置页的依赖警告、发送测试邮件接口、
 * 以及找回密码的发码路由（{@link com.enterprise.ticket.module.auth.support.RoutingVerificationCodeSender}）。
 * 三处各写一遍 {@code host != null && port > 0 && ...} 必然漂移 ——
 * 典型后果是「页面说没配好、后端却当配好了去发信」，然后发码以超时告终。
 * 因此判定只在这里实现一次，其余三处全部调用它。
 *
 * <h2>「完整」的口径</h2>
 * <p>只认<b>真正能让一封信发出去</b>的四项：host / port / username / password。
 * 发件人显示名（{@code smtp_from_name}）缺失时回落默认名，不影响投递；
 * 把它算进「完整性」只会制造「明明能发却被判不完整」的假告警。
 *
 * <h2>发件地址为什么取 username</h2>
 * <p>主流邮箱（QQ / 企业微信 / 阿里企业邮箱）的 SMTP 都要求「发件地址 = 认证账号」，
 * 另设一个 {@code from} 字段反而会造出「认证过了但被服务商拒发」的配置组合。
 * 需要改显示名时改的是 {@code smtp_from_name}（DisplayName），不是地址。
 */
public final class MailSettings {

    private MailSettings() {
    }

    // ------------------------------------------------------------------
    // 配置键（与 V28 迁移脚本里的 config_key 逐字对应）
    // ------------------------------------------------------------------

    /** SMTP 服务器地址 */
    public static final String KEY_HOST = "smtp_host";

    /** SMTP 端口 */
    public static final String KEY_PORT = "smtp_port";

    /** 发件邮箱账号（同时作为 SMTP 认证用户与发件地址） */
    public static final String KEY_USERNAME = "smtp_username";

    /** SMTP 授权码（加密存储，见 {@code SecretCipher#PURPOSE_SMTP_PASSWORD}） */
    public static final String KEY_PASSWORD = "smtp_password";

    /** 发件人显示名 */
    public static final String KEY_FROM_NAME = "smtp_from_name";

    /** 是否启用 SSL */
    public static final String KEY_SSL = "smtp_ssl";

    // ------------------------------------------------------------------
    // 内置默认值（配置行缺失时回落）
    // ------------------------------------------------------------------

    public static final int DEFAULT_PORT = 465;

    public static final boolean DEFAULT_SSL = true;

    public static final String DEFAULT_FROM_NAME = "设备借用工单系统";

    /** 常用邮箱的参数参考（配置页展示；不参与任何运行时判定） */
    public static final String[] REFERENCE_NOTES = {
            "QQ 邮箱：smtp.qq.com / 465 / SSL 开启；授权码在「设置 → 账户 → POP3/SMTP 服务」生成，不是登录密码。",
            "企业微信邮箱：smtp.exmail.qq.com / 465 / SSL 开启；账号填完整邮箱地址。",
            "阿里企业邮箱：smtp.qiye.aliyun.com / 465 / SSL 开启；授权码需在邮箱后台单独开通。",
            "用 587 端口时通常需要 STARTTLS（本系统的「SSL」开关对应 465 的隐式加密，587 请改用 465 或关闭 SSL 后确认服务商支持）。"
    };

    /**
     * 生效中的 SMTP 设置（明文口令已解密）
     *
     * @param host     SMTP 服务器
     * @param port     端口
     * @param username 认证账号 / 发件地址
     * @param password 授权码明文（解密后）
     * @param fromName 发件人显示名（空则回落 {@link #DEFAULT_FROM_NAME}）
     * @param ssl      是否启用隐式 SSL
     */
    public record Settings(String host, int port, String username, String password,
                           String fromName, boolean ssl) {

        /** 是否可以真的发出一封信（判定见类注释第二段） */
        public boolean complete() {
            return StringUtils.hasText(host)
                    && port > 0 && port <= 65535
                    && StringUtils.hasText(username)
                    && StringUtils.hasText(password);
        }

        /** 不完整时的可读原因（供配置页警告与测试接口提示）；完整时返回 {@code null} */
        public String incompletenessReason() {
            if (!StringUtils.hasText(host)) {
                return "未填写 SMTP 服务器地址";
            }
            if (port <= 0 || port > 65535) {
                return "SMTP 端口不在 1 ~ 65535 范围内";
            }
            if (!StringUtils.hasText(username)) {
                return "未填写发件邮箱账号";
            }
            if (!StringUtils.hasText(password)) {
                return "未填写 SMTP 授权码（或授权码无法解密，请重新保存）";
            }
            return null;
        }

        /** 发件人显示名（空值回落到默认名） */
        public String displayName() {
            return StringUtils.hasText(fromName) ? fromName : DEFAULT_FROM_NAME;
        }

        /** 发件地址（= 认证账号，见类注释第三段） */
        public String fromAddress() {
            return username;
        }
    }
}
