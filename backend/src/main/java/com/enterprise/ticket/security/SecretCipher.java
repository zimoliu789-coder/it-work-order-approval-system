package com.enterprise.ticket.security;

import com.enterprise.ticket.config.AppProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 静态密钥加解密（AD 绑定密码、SMTP 授权码、短信 AccessKey Secret 等
 * 「必须存下来、但绝不能明文落库」的凭据）
 *
 * <h2>为什么需要它，而不是「哈希就行」</h2>
 * <p>用户口令只做单向哈希即可（我们永远不需要还原它）。但 AD 的<b>绑定密码</b>不同：
 * 每次同步与登录都要拿它去 bind 域控，所以必须是<b>可还原</b>的密文。
 * 于是需要对称加密 —— 这里选 <b>AES-256-GCM</b>：
 * <ul>
 *   <li>GCM 是 AEAD，自带完整性校验：密文被篡改会在解密时直接失败，
 *       而不是解出一段乱码去 bind 域控（后者会表现为难以定位的「认证莫名其妙失败」）；</li>
 *   <li>每次加密使用随机 IV，相同明文两次加密得到不同密文，
 *       避免「密文相同 ⇒ 明文相同」的推断。</li>
 * </ul>
 *
 * <h2>按用途隔离（{@code purpose}）</h2>
 * <p>同一把密钥要保护多种凭据（AD 绑定密码 / SMTP 授权码 / 短信 Secret）。
 * GCM 的<b>附加认证数据（AAD）</b>被用来把密文绑定到它的用途上：不同 {@code purpose}
 * 派生出不同的 AAD，于是
 * <ul>
 *   <li>A 用途的密文放到 B 用途的字段里，解密时校验直接失败 ——
 *       而不是「静静地解出一段看似正常的字符串」；</li>
 *   <li>这挡住的是「运维把 SMTP 授权码误粘进 AD 绑定密码框」这类真实会发生的搬运，
 *       否则后果是 AD 认证以一个与输入毫不相干的凭据反复失败。</li>
 * </ul>
 * <b>历史的 AD 用途 AAD 逐字不可改</b>（见 {@link #AAD_AD_BIND} 注释），
 * 其余用途统一走 {@value #PURPOSE_NAMESPACE} 前缀派生，避免与历史值相撞。
 *
 * <h2>密钥从哪来</h2>
 * <p>不新增环境变量，由 {@code JWT_SECRET} 经 <b>PBKDF2-HMAC-SHA256</b>（12 万次迭代）派生。
 * 这样做的取舍必须说清楚：
 * <ul>
 *   <li><b>优点</b>：部署面不变（ 只要求注入 JWT_SECRET），不会出现「忘了配 AD 密钥导致
 *       功能报错」的运维事故；</li>
 *   <li><b>代价</b>：JWT_SECRET 泄露时，AD 绑定密码的密文也一并可解。
 *       这在内网单机部署下是可接受的 —— 能拿到 JWT_SECRET 的人本就能伪造任意身份令牌，
 *       已经是「完全接管」级别的泄露，多泄露一个绑定账号并不会让处境更糟。</li>
 * </ul>
 * 若将来需要轮换：旧密文以 {@value #PREFIX} 前缀标识版本，届时按前缀分支解密即可平滑迁移。
 *
 * <h2>不使用它的场景</h2>
 * <p>任何「只需要校验、不需要还原」的场景（用户口令）继续走 Argon2id，
 * 不要因为本类的存在而把口令改成可逆加密 —— 那会把一次拖库的后果从
 * 「口令不可逆」升级为「全部口令可读」。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SecretCipher {

    /** 密文版本前缀：用于将来更换算法时识别旧密文，避免「换了算法，旧数据全解不开」 */
    public static final String PREFIX = "ENC1:";

    /** 脱敏占位符：接口返回绑定密码 / 授权码时统一用它，前端据此判断「没改过」 */
    public static final String MASK = "****";

    // ------------------------------------------------------------------
    // 用途标签（purpose）
    // ------------------------------------------------------------------

    /** AD 域控绑定密码 */
    public static final String PURPOSE_AD_BIND = "ad-bind-password";

    /** SMTP 授权码 */
    public static final String PURPOSE_SMTP_PASSWORD = "smtp-auth-code";

    /** 短信服务商 AccessKey Secret */
    public static final String PURPOSE_SMS_SECRET = "sms-access-key-secret";

    /**
     * AD 用途的 AAD。
     *
     * <p><b>这个字面量绝对不可改动。</b>它已经参与了历史密文的认证标签计算，
     * 一改就意味着「库里所有 AD 绑定密码同时解不开」—— 表现为升级后 AD 全线认证失败。
     * 新增用途请走 {@link #aad(String)} 的非默认分支。
     */
    private static final byte[] AAD_AD_BIND = "ad-bind-password".getBytes(StandardCharsets.UTF_8);

    /** 非默认用途 AAD 的命名空间前缀（与历史字面量刻意不同，避免相撞） */
    private static final String PURPOSE_NAMESPACE = "ticket-system::secret::";

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";
    private static final String KDF_ALGORITHM = "PBKDF2WithHmacSHA256";

    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int PBKDF2_ITERATIONS = 120_000;

    /**
     * 固定盐值。
     *
     * <p>通常 KDF 的盐应当随机且随密文存储；这里用固定值是可以接受的，因为：
     * 入参是<b>高熵的机器密钥</b>（JWT_SECRET ≥ 32 字节随机串），不是人类口令 ——
     * 盐的作用是防止「彩虹表 / 跨用户并行破解弱口令」，而这些攻击的前提都不成立。
     * 固定盐换来的是「不需要额外存储与版本管理」的简单性。
     */
    private static final byte[] SALT =
            "ticket-system::secret-cipher::v1".getBytes(StandardCharsets.UTF_8);

    private final AppProperties appProperties;

    private final SecureRandom random = new SecureRandom();

    private SecretKey secretKey;

    @PostConstruct
    void init() {
        String jwtSecret = appProperties.getJwt() == null ? null : appProperties.getJwt().getSecret();
        if (!StringUtils.hasText(jwtSecret)) {
            // 与 JwtTokenProvider 的失败语义一致：密钥缺失属于部署错误，直接拒绝启动，
            // 而不是退化成「用固定默认密钥加密」—— 那等于把密文变成了可公开解密的数据。
            throw new IllegalStateException(
                    "无法派生凭据加密密钥：JWT_SECRET 未配置。请通过环境变量 JWT_SECRET 注入（≥32 字节）。");
        }
        try {
            PBEKeySpec spec = new PBEKeySpec(jwtSecret.toCharArray(), SALT, PBKDF2_ITERATIONS, KEY_LENGTH_BITS);
            byte[] derived = SecretKeyFactory.getInstance(KDF_ALGORITHM).generateSecret(spec).getEncoded();
            this.secretKey = new SecretKeySpec(derived, KEY_ALGORITHM);
        } catch (Exception e) {
            throw new IllegalStateException("派生凭据加密密钥失败：" + e.getMessage(), e);
        }
    }

    /** 由用途标签派生 AAD；空 / AD 用途回落到历史字面量（见 {@link #AAD_AD_BIND}） */
    private static byte[] aad(String purpose) {
        if (!StringUtils.hasText(purpose) || PURPOSE_AD_BIND.equals(purpose)) {
            return AAD_AD_BIND;
        }
        return (PURPOSE_NAMESPACE + purpose).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 加密（AD 绑定密码用途）。
     *
     * @param plain 明文；{@code null} 或空串原样返回空串（语义为「未设置」）
     * @return {@value #PREFIX} 前缀 + Base64(IV ‖ 密文‖标签)
     */
    public String encrypt(String plain) {
        return encrypt(plain, PURPOSE_AD_BIND);
    }

    /**
     * 加密（指定用途）。
     *
     * @param purpose 用途标签，见 {@code PURPOSE_*} 常量；用于把密文绑定到具体字段
     */
    public String encrypt(String plain, String purpose) {
        if (!StringUtils.hasText(plain)) {
            return "";
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            cipher.updateAAD(aad(purpose));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            // 不把明文带进日志；异常本身足以定位（通常是 JDK 安全策略或密钥长度问题）
            log.error("凭据加密失败（用途 {}）", purpose, e);
            throw new IllegalStateException("凭据加密失败：" + e.getMessage(), e);
        }
    }

    /**
     * 解密（AD 绑定密码用途）；空串返回空串，格式非法或校验失败返回 {@code null}。
     */
    public String decrypt(String stored) {
        return decrypt(stored, PURPOSE_AD_BIND);
    }

    /**
     * 解密（指定用途）。
     *
     * <p>刻意返回 {@code null} 而不抛异常：调用方（读配置去连 AD / SMTP）需要区分
     * 「没配密码」与「密码坏了」，但两者都只能导向同一个动作（提示管理员重填），
     * 抛异常只会把一次配置级问题升级成 500。
     */
    public String decrypt(String stored, String purpose) {
        if (!StringUtils.hasText(stored)) {
            return "";
        }
        if (!stored.startsWith(PREFIX)) {
            // 兼容历史 / 人工写入的明文值：直接当作明文使用，但留下告警，
            // 因为「库里出现明文口令」本身就是需要处理的安全事件。
            log.warn("检测到未加密的凭据值（可能由人工 SQL 写入，用途 {}），建议在对应配置页重新保存以启用加密存储",
                    purpose);
            return stored;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            if (combined.length <= IV_LENGTH) {
                log.warn("凭据密文长度非法，无法解密（用途 {}）", purpose);
                return null;
            }
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
            byte[] cipherText = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, IV_LENGTH, cipherText, 0, cipherText.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            cipher.updateAAD(aad(purpose));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 最常见的三种原因：JWT_SECRET 被更换（旧密文解不开）、用途不匹配（密文被搬到了别的字段）、
            // 数据库值被人工改过。用途不匹配是新增场景，必须写进日志，否则会表现为「凭据明明填对了却认证失败」。
            log.error("凭据解密失败（用途 {}）：通常是 JWT_SECRET 已变更、或密文与用途不匹配（被搬到别的字段）", purpose);
            return null;
        }
    }

    /** 是否已是本类产出的密文（用于判断「是否需要重新加密」） */
    public static boolean isEncrypted(String stored) {
        return StringUtils.hasText(stored) && stored.startsWith(PREFIX);
    }
}
