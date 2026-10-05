package com.enterprise.ticket.security;

import com.enterprise.ticket.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 签发与解析（ 会话机制、 JWT_SECRET 由 .env 注入）
 *
 * <p>Token 只承载身份标识（userId / username / role），权限与账号状态每次请求
 * 由服务端重新加载，保证「员工离职禁用后立即失效」，不依赖 Token 过期时间。
 *
 * <h2> 新增的两个声明（三波遗漏补做·第一波）</h2>
 * <ul>
 *   <li>{@code jti}（JWT ID）—— 每个 Token 的唯一标识。用于<b>单次登出拉黑</b>：
 *       用户退出时把这一枚 jti 放进 Redis 黑名单（TTL = 剩余有效期），
 *       只作废「这一次会话」，其他设备上的登录不受影响。</li>
 *   <li>{@code ver}（token_version）—— 用户级版本号。改密 / 管理员重置 / 强制下线时
 *       库中的 {@code users.token_version} +1，于是该用户<b>所有</b>已签发 Token
 *       在下一次请求就被判定失效。</li>
 * </ul>
 *
 * <h2>为什么两者都要，而不是只用其中一个</h2>
 * <p>它们解决的是不同粒度的问题，缺一不可：
 * <ul>
 *   <li>只有版本号：用户在一台设备点「退出登录」，会把他在手机、家里电脑上的会话
 *       一起踢掉 —— 对「我只是想关掉这个标签页」而言是明显的过度反应。</li>
 *   <li>只有 jti 黑名单：改密后旧 Token 仍能用，因为服务端根本不知道「哪些 Token 属于
 *       改密之前」；要作废就得维护「用户全部活跃 jti」的集合，成本与可靠性都更差。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

    private static final int MIN_SECRET_BYTES = 32;

    /** application-dev.yml 中的占位密钥前缀：一旦发现它出现在非 dev 环境，立即拒绝启动 */
    private static final String DEV_PLACEHOLDER_SECRET_PREFIX = "dev-only-secret";

    /** 令牌版本号声明名 */
    public static final String CLAIM_TOKEN_VERSION = "ver";

    private final AppProperties appProperties;
    private final Environment environment;

    private SecretKey secretKey;

    @PostConstruct
    void init() {
        String secret = appProperties.getJwt().getSecret();
        if (!StringUtils.hasText(secret)) {
            throw new IllegalStateException(
                    "JWT 密钥未配置。请通过环境变量 JWT_SECRET 注入（长度不少于 32 字节），"
                            + "开发环境可使用 application-dev.yml 中的占位密钥。");
        }
        if (secret.startsWith(DEV_PLACEHOLDER_SECRET_PREFIX)) {
            boolean devProfile = Arrays.asList(environment.getActiveProfiles()).contains("dev");
            if (!devProfile) {
                throw new IllegalStateException(
                        "检测到开发占位 JWT 密钥在非 dev 环境生效，拒绝启动。"
                                + "该密钥已公开于源码仓库，任何人都能据此伪造任意身份的令牌，"
                                + "请通过环境变量 JWT_SECRET 注入真实密钥。");
            }
            log.warn("当前使用开发占位 JWT 密钥，仅限本地调试，切勿用于生产环境。");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT 密钥长度不足 " + MIN_SECRET_BYTES + " 字节，拒绝启动。");
        }
        this.secretKey = Keys.hmacShaKeyFor(bytes);
    }

    /**
     * 签发 Token
     *
     * @param userId       用户主键
     * @param username     登录名
     * @param role         角色编码
     * @param tokenVersion 用户当前令牌版本号（写入 {@code ver} 声明）
     * @param expireMinutes 有效期（分钟）
     */
    public String createToken(Long userId, String username, String role,
                             int tokenVersion, int expireMinutes) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + expireMinutes * 60_000L);
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("role", role)
                .claim(CLAIM_TOKEN_VERSION, tokenVersion)
                .issuedAt(now)
                .expiration(expiration)
                .signWith(secretKey)
                .compact();
    }

    /**
     * 解析 Token，非法或过期返回 null（由调用方统一按未登录处理）
     */
    public Claims parse(String token) {
        if (!StringUtils.hasText(token)) {
            return null;
        }
        try {
            return Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            log.debug("JWT 已过期");
            return null;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT 解析失败: {}", e.getMessage());
            return null;
        }
    }

    public Long getUserId(Claims claims) {
        if (claims == null || !StringUtils.hasText(claims.getSubject())) {
            return null;
        }
        try {
            return Long.valueOf(claims.getSubject());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 取 Token 的 jti；缺失返回 null。
     *
     * <p>兼容性说明：V12 之前签发的 Token 没有 jti。这类 Token 允许通过
     * （版本号校验仍然生效），只是无法被单次登出拉黑 —— 用户重新登录即恢复正常。
     */
    public String getTokenId(Claims claims) {
        if (claims == null) {
            return null;
        }
        String id = claims.getId();
        return StringUtils.hasText(id) ? id : null;
    }

    /**
     * 取 Token 的版本号声明；缺失按 0 处理（等价于 V12 迁移写入的默认值）。
     */
    public int getTokenVersion(Claims claims) {
        if (claims == null) {
            return 0;
        }
        Object raw = claims.get(CLAIM_TOKEN_VERSION);
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * 计算 Token 的剩余有效期，用于把 jti 放进黑名单时对齐 TTL。
     *
     * <p>不能直接用配置里的 {@code jwt_expire_minutes}：Token 可能已经用了一半时间，
     * 按原值写黑名单会让 Redis 里的垃圾条目比 Token 本身活得更久。
     *
     * @return 剩余时长；Token 无过期时间或已过期时返回 {@link Duration#ZERO}
     */
    public Duration remainingLifetime(Claims claims) {
        if (claims == null || claims.getExpiration() == null) {
            return Duration.ZERO;
        }
        long millis = claims.getExpiration().getTime() - System.currentTimeMillis();
        return millis <= 0 ? Duration.ZERO : Duration.ofMillis(millis);
    }
}
