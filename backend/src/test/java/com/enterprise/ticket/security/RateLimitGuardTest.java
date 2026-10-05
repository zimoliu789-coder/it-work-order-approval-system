package com.enterprise.ticket.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 限流计数器单测（部署与限流加固 · 规范 §29）
 *
 * <p>{@link RateLimitGuard} 对上层暴露的是「允许 / 拒绝 + 还要等几秒」的纯判定，
 * 本类把它的四个关键契约钉死：
 * <ol>
 *   <li><b>关闭语义</b>：阈值 &le; 0 时不限流，且不触碰 Redis（配置关闭后不应产生任何计数键）；</li>
 *   <li><b>固定窗口</b>：窗口内第 limit+1 次被拒，且 Retry-After 取自键的真实剩余 TTL；</li>
 *   <li><b>令牌桶</b>：Lua 返回的等待毫秒被向上取整为秒；</li>
 *   <li><b>fail-open</b>：Redis 异常时放行（可用性优先），而不是抛错阻断登录。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RateLimitGuardTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private RateLimitGuard guard;

    private static final Duration ONE_MINUTE = Duration.ofMinutes(1);

    // ------------------------------------------------------------------
    // 固定窗口
    // ------------------------------------------------------------------

    @Test
    @DisplayName("阈值 <= 0 视为不限流，且不产生任何 Redis 计数")
    void fixedWindowDisabledWhenLimitNotPositive() {
        assertTrue(guard.tryAcquireFixedWindow("k", 0, ONE_MINUTE).allowed());
        assertTrue(guard.tryAcquireFixedWindow("k", -1, ONE_MINUTE).allowed());
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    @DisplayName("窗口内未超限：放行")
    void fixedWindowAllowsWithinLimit() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("k")).thenReturn(1L);

        RateLimitGuard.Decision decision = guard.tryAcquireFixedWindow("k", 5, ONE_MINUTE);

        assertTrue(decision.allowed());
        assertEquals(0L, decision.retryAfterSeconds());
    }

    @Test
    @DisplayName("窗口内超限：拒绝，Retry-After 取键真实剩余 TTL")
    void fixedWindowRejectsOverLimit() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("k")).thenReturn(6L);
        when(redisTemplate.getExpire("k", TimeUnit.SECONDS)).thenReturn(42L);

        RateLimitGuard.Decision decision = guard.tryAcquireFixedWindow("k", 5, ONE_MINUTE);

        assertFalse(decision.allowed());
        assertEquals(42L, decision.retryAfterSeconds());
    }

    @Test
    @DisplayName("首次计数时设置过期时间（避免计数键永久残留）")
    void fixedWindowSetsExpireOnFirstHit() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("k")).thenReturn(1L);

        guard.tryAcquireFixedWindow("k", 5, ONE_MINUTE);

        verify(redisTemplate).expire("k", ONE_MINUTE);
    }

    @Test
    @DisplayName("Redis 异常时 fail-open：放行而不阻断业务")
    void fixedWindowFailOpenOnRedisError() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("connection refused"));

        RateLimitGuard.Decision decision = guard.tryAcquireFixedWindow("k", 5, ONE_MINUTE);

        assertTrue(decision.allowed());
    }

    // ------------------------------------------------------------------
    // 令牌桶
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void stubTokenBucket(List<?> result) {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn(result);
    }

    @Test
    @DisplayName("令牌桶：Lua 返回放行")
    void tokenBucketAllows() {
        stubTokenBucket(List.of(1L, 59L, 0L));

        RateLimitGuard.Decision decision = guard.tryAcquireToken("k", 300, 60);

        assertTrue(decision.allowed());
    }

    @Test
    @DisplayName("令牌桶：拒绝时把等待毫秒向上取整为秒")
    void tokenBucketRejectsWithCeilSeconds() {
        stubTokenBucket(List.of(0L, 0L, 1500L));

        RateLimitGuard.Decision decision = guard.tryAcquireToken("k", 60, 20);

        assertFalse(decision.allowed());
        assertEquals(2L, decision.retryAfterSeconds()); // 1500ms → 2s（向上取整，宁可多等不少等）
    }

    @Test
    @DisplayName("令牌桶：速率 <= 0 视为不限流，不触碰 Redis")
    void tokenBucketDisabled() {
        assertTrue(guard.tryAcquireToken("k", 0, 20).allowed());
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("令牌桶：Redis 异常时 fail-open")
    void tokenBucketFailOpen() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("timeout"));

        assertTrue(guard.tryAcquireToken("k", 300, 60).allowed());
    }

    // ------------------------------------------------------------------
    // 一次性标记（限流审计限频）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("tryMarkOnce：TTL 内首次为 true，其后为 false")
    void markOnce() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("m"), eq("1"), any(Duration.class))).thenReturn(Boolean.TRUE);
        assertTrue(guard.tryMarkOnce("m", Duration.ofMinutes(1)));

        when(valueOperations.setIfAbsent(eq("m"), eq("1"), any(Duration.class))).thenReturn(Boolean.FALSE);
        assertFalse(guard.tryMarkOnce("m", Duration.ofMinutes(1)));
    }

    @Test
    @DisplayName("tryMarkOnce：Redis 异常时按「可记录」处理，避免审计因缓存故障整体失效")
    void markOnceFailOpen() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("down"));
        assertTrue(guard.tryMarkOnce("m", Duration.ofMinutes(1)));
    }
}
