package com.enterprise.ticket.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * 令牌黑名单（需求方三波·第一波·「登出拉黑」）
 *
 * <h2>为什么用 Redis 而不是数据库</h2>
 * <p>黑名单是<b>纯 TTL 语义</b>的数据：条目只需活到 Token 自然过期为止，
 * 过期后自动消失才是对的。用表存就得自己写清理任务，而且会在认证路径上
 * 引入一次磁盘查询。Redis 的 {@code SET key value EX ttl} 一次性表达完「存在 + 到期自删」。
 *
 * <h2>fail-open 还是 fail-closed</h2>
 * <p>Redis 不可用时本实现选择 <b>fail-open</b>（放行），理由：
 * <ul>
 *   <li>登出拉黑是「用户主动登出后缩短凭证暴露窗口」的加固手段，
 *       不是身份认证本身；认证仍由 JWT 签名 + 版本号 + 用户启用状态三重保证；</li>
 *   <li>若 fail-closed，一次 Redis 抖动会让<b>全站所有人无法登录</b> ——
 *       用可用性换取的这点安全收益明显不划算；</li>
 *   <li>「改密后旧 Token 立即失效」这一关键需求<b>不依赖 Redis</b>（走 users.token_version），
 *       因此 Redis 故障时最坏也只是「登出后旧 Cookie 还能用到过期」。</li>
 * </ul>
 * 故障时记 warn 日志，便于运维察觉。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenBlacklistService {

    /** key 前缀，与登录限流 / 定时任务锁保持同一命名空间 */
    private static final String KEY_PREFIX = "ticket:token:revoked:";

    private final StringRedisTemplate redisTemplate;

    /**
     * 拉黑一枚 Token
     *
     * @param jti        Token 唯一标识
     * @param ttl        剩余有效期（Token 过期后黑名单条目自动消失，无需清理任务）
     */
    public void revoke(String jti, Duration ttl) {
        if (!StringUtils.hasText(jti) || ttl == null || ttl.isZero() || ttl.isNegative()) {
            // Token 已自然过期：无需拉黑（拉黑反而会让 Redis 多一条永不使用的键）
            return;
        }
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + jti, "1", ttl);
        } catch (Exception e) {
            log.warn("写入令牌黑名单失败（已按 fail-open 放行，不影响本次登出）：jti={}", jti, e);
        }
    }

    /**
     * 该 Token 是否已被拉黑
     *
     * <p>Redis 异常时返回 {@code false}（放行），见类注释的 fail-open 说明。
     */
    public boolean isRevoked(String jti) {
        if (!StringUtils.hasText(jti)) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + jti));
        } catch (Exception e) {
            log.warn("查询令牌黑名单失败（已按 fail-open 放行）：jti={}", jti, e);
            return false;
        }
    }
}
