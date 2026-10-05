package com.enterprise.ticket.module.auth.service;

import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import com.enterprise.ticket.security.RateLimitGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * 登录防护：失败计数、账号锁定、接口限流（、）
 *
 * <p>计数一律落在 Redis（：使用 Redis 实现限流计数器），
 * MySQL 仍是业务最终事实来源。计数算法委托给 {@link RateLimitGuard}，
 * 本类只负责「用哪些维度、给什么提示」这类业务口径。
 *
 * <h2>登录限流为什么是两个维度而不是一个</h2>
 * <p>早期实现用单键 {@code 账号:IP}，但它同时漏掉两类真实攻击：
 * <ul>
 *   <li><b>撞库</b>：一台机器用同一 IP 枚举大量账号 —— 每个「账号:IP」组合计数都是 1，永不触发；</li>
 *   <li><b>定向爆破</b>：多台机器集中猜同一个人 —— 每个 IP 的计数也都很低。</li>
 * </ul>
 * 因此按需求方口径拆为「同 IP 5 次/分钟」+「同账号 3 次/分钟」，两者<b>同时</b>判定，
 * 任一超限即拒绝；再叠加既有的「连续失败 5 次锁定 30 分钟」，形成
 * 「频率限制 + 失败次数锁定」的双层防护。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginProtectionService {

    private static final String KEY_FAIL = "ticket:login:fail:";
    private static final String KEY_LOCK = "ticket:login:lock:";
    private static final String KEY_RATE_LOGIN_IP = "ticket:rate:login:ip:";
    private static final String KEY_RATE_LOGIN_ACCOUNT = "ticket:rate:login:acct:";
    private static final String KEY_RATE_SUBMIT = "ticket:rate:submit:";

    private static final Duration ONE_MINUTE = Duration.ofMinutes(1);

    private final StringRedisTemplate redisTemplate;
    private final SystemConfigService systemConfigService;
    private final RateLimitGuard rateLimitGuard;

    /**
     * 登录接口限流：同 IP 与同账号两个维度各自独立判定
     *
     * @throws BusinessException 任一维度超限（{@code RATE_LIMITED}，HTTP 429，带 Retry-After）
     */
    public void checkLoginRateLimit(String account, String ip) {
        String ipKey = KEY_RATE_LOGIN_IP + safe(ip);
        RateLimitGuard.Decision byIp = rateLimitGuard.tryAcquireFixedWindow(
                ipKey, systemConfigService.loginIpRateLimitPerMinute(), ONE_MINUTE);
        if (!byIp.allowed()) {
            throw BusinessException.rateLimited(
                    "登录请求过于频繁，请 " + byIp.retryAfterSeconds() + " 秒后重试", byIp.retryAfterSeconds());
        }

        String accountKey = KEY_RATE_LOGIN_ACCOUNT + safe(account);
        RateLimitGuard.Decision byAccount = rateLimitGuard.tryAcquireFixedWindow(
                accountKey, systemConfigService.loginAccountRateLimitPerMinute(), ONE_MINUTE);
        if (!byAccount.allowed()) {
            throw BusinessException.rateLimited(
                    "该账号登录请求过于频繁，请 " + byAccount.retryAfterSeconds() + " 秒后重试",
                    byAccount.retryAfterSeconds());
        }
    }

    /**
     * 工单提交 / 延期提交限流：单用户维度（：1 分钟最多 3 次）
     *
     * <p>注意：即使提交因业务校验失败（如设备已被占用）也会消耗一次配额。
     * 这是刻意的 —— 只在「成功」时计数，等于给攻击者留了一条「无限次失败尝试」的通道，
     * 而反复提交正是要限制的行为本身。
     */
    public void checkSubmitRateLimit(Long userId) {
        RateLimitGuard.Decision decision = rateLimitGuard.tryAcquireFixedWindow(
                KEY_RATE_SUBMIT + safe(String.valueOf(userId)),
                systemConfigService.orderSubmitRateLimitPerMinute(), ONE_MINUTE);
        if (!decision.allowed()) {
            throw BusinessException.rateLimited(
                    "提交过于频繁，请 " + decision.retryAfterSeconds() + " 秒后重试", decision.retryAfterSeconds());
        }
    }

    /**
     * 账号是否处于失败锁定期（：连续失败 5 次锁定 30 分钟）
     */
    public boolean isLocked(String account) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_LOCK + safe(account)));
    }

    /**
     * 返回剩余锁定秒数，用于提示文案
     */
    public long getLockRemainSeconds(String account) {
        Long ttl = redisTemplate.getExpire(KEY_LOCK + safe(account));
        return ttl == null || ttl < 0 ? 0L : ttl;
    }

    /**
     * 记录一次登录失败，达到阈值则锁定账号
     *
     * @return 当前累计失败次数
     */
    public int recordLoginFailure(String account) {
        String key = KEY_FAIL + safe(account);
        int maxCount = systemConfigService.loginFailMaxCount();
        int lockMinutes = systemConfigService.loginLockMinutes();

        Long count = redisTemplate.opsForValue().increment(key);
        if (count == null) {
            return 0;
        }
        // 失败计数窗口 = 锁定时长的 4 倍，避免长期不登录时计数永不过期
        redisTemplate.expire(key, Duration.ofMinutes(Math.max(lockMinutes, 5) * 4L));

        if (count >= maxCount) {
            redisTemplate.opsForValue().set(KEY_LOCK + safe(account), String.valueOf(System.currentTimeMillis()),
                    Duration.ofMinutes(lockMinutes));
            redisTemplate.delete(key);
            log.warn("账号 [{}] 连续登录失败 {} 次，已锁定 {} 分钟", account, count, lockMinutes);
        }
        return count.intValue();
    }

    /**
     * 登录成功后清除失败计数
     */
    public void clearLoginFailure(String account) {
        redisTemplate.delete(KEY_FAIL + safe(account));
    }

    private String safe(String value) {
        return StringUtils.hasText(value) ? value : "unknown";
    }
}
