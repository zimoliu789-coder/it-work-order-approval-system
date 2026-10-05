package com.enterprise.ticket.module.auth.service;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import com.enterprise.ticket.security.RateLimitGuard;
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

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登录 / 提交限流单测（部署与限流加固 · 规范 §19、§29）
 *
 * <p>核心断言是「双维度独立判定」这一设计：同 IP 与同账号各自计数，任一超限即拒绝。
 * 该设计针对两类真实攻击 —— 一台机器撞库（同 IP 枚举多账号）与多机定向爆破（多 IP 猜同一账号）——
 * 单键模型对两者都无效。本类另外钉死「IP 维度先判、命中即短路」，避免超限时仍去计数账号维度
 * 而把正常账号的额度一并消耗掉。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LoginProtectionServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private SystemConfigService systemConfigService;

    @Mock
    private RateLimitGuard rateLimitGuard;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private LoginProtectionService service;

    private static final RateLimitGuard.Decision ALLOW = new RateLimitGuard.Decision(true, 0L);

    // ------------------------------------------------------------------
    // checkLoginRateLimit：双维度
    // ------------------------------------------------------------------

    @Test
    @DisplayName("两维度均未超限：放行，且分别使用配置的 IP / 账号阈值")
    void loginAllowedWhenBothWithinLimit() {
        when(systemConfigService.loginIpRateLimitPerMinute()).thenReturn(5);
        when(systemConfigService.loginAccountRateLimitPerMinute()).thenReturn(3);
        when(rateLimitGuard.tryAcquireFixedWindow(anyString(), anyInt(), any(Duration.class))).thenReturn(ALLOW);

        service.checkLoginRateLimit("alice", "198.51.100.7");

        verify(rateLimitGuard).tryAcquireFixedWindow(startsWith("ticket:rate:login:ip:"), eq(5), any(Duration.class));
        verify(rateLimitGuard).tryAcquireFixedWindow(startsWith("ticket:rate:login:acct:"), eq(3), any(Duration.class));
    }

    @Test
    @DisplayName("同 IP 超限：抛 429（RATE_LIMITED）并带 Retry-After 秒数，且短路不再计数账号维度")
    void loginRejectedByIpDimension() {
        when(systemConfigService.loginIpRateLimitPerMinute()).thenReturn(5);
        when(rateLimitGuard.tryAcquireFixedWindow(startsWith("ticket:rate:login:ip:"), anyInt(), any(Duration.class)))
                .thenReturn(new RateLimitGuard.Decision(false, 37L));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.checkLoginRateLimit("alice", "198.51.100.7"));

        assertEquals(ErrorCode.RATE_LIMITED, ex.getErrorCode());
        assertEquals(37, ex.getRetryAfterSeconds());
        assertTrue(ex.getMessage().contains("37"));
        // 关键：IP 维度已拒绝，不应再去消耗账号维度额度
        verify(rateLimitGuard, never())
                .tryAcquireFixedWindow(startsWith("ticket:rate:login:acct:"), anyInt(), any(Duration.class));
    }

    @Test
    @DisplayName("账号维度超限：抛 429，提示指明是账号维度")
    void loginRejectedByAccountDimension() {
        when(systemConfigService.loginIpRateLimitPerMinute()).thenReturn(5);
        when(systemConfigService.loginAccountRateLimitPerMinute()).thenReturn(3);
        when(rateLimitGuard.tryAcquireFixedWindow(startsWith("ticket:rate:login:ip:"), anyInt(), any(Duration.class)))
                .thenReturn(ALLOW);
        when(rateLimitGuard.tryAcquireFixedWindow(startsWith("ticket:rate:login:acct:"), anyInt(), any(Duration.class)))
                .thenReturn(new RateLimitGuard.Decision(false, 12L));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.checkLoginRateLimit("alice", "198.51.100.7"));

        assertEquals(ErrorCode.RATE_LIMITED, ex.getErrorCode());
        assertEquals(12, ex.getRetryAfterSeconds());
        assertTrue(ex.getMessage().contains("该账号"));
    }

    @Test
    @DisplayName("账号 / IP 为空时用 unknown 兜底，不产生空键")
    void loginUsesUnknownPlaceholder() {
        when(systemConfigService.loginIpRateLimitPerMinute()).thenReturn(5);
        when(systemConfigService.loginAccountRateLimitPerMinute()).thenReturn(3);
        when(rateLimitGuard.tryAcquireFixedWindow(anyString(), anyInt(), any(Duration.class))).thenReturn(ALLOW);

        service.checkLoginRateLimit(null, null);

        verify(rateLimitGuard).tryAcquireFixedWindow(eq("ticket:rate:login:ip:unknown"), eq(5), any(Duration.class));
        verify(rateLimitGuard).tryAcquireFixedWindow(eq("ticket:rate:login:acct:unknown"), eq(3), any(Duration.class));
    }

    // ------------------------------------------------------------------
    // checkSubmitRateLimit：单用户维度
    // ------------------------------------------------------------------

    @Test
    @DisplayName("提交限流：未超限放行；超限抛 429")
    void submitRateLimit() {
        when(systemConfigService.orderSubmitRateLimitPerMinute()).thenReturn(3);
        when(rateLimitGuard.tryAcquireFixedWindow(anyString(), anyInt(), any(Duration.class))).thenReturn(ALLOW);
        service.checkSubmitRateLimit(1001L);
        verify(rateLimitGuard).tryAcquireFixedWindow(eq("ticket:rate:submit:1001"), eq(3), any(Duration.class));

        when(rateLimitGuard.tryAcquireFixedWindow(anyString(), anyInt(), any(Duration.class)))
                .thenReturn(new RateLimitGuard.Decision(false, 20L));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.checkSubmitRateLimit(1001L));
        assertEquals(ErrorCode.RATE_LIMITED, ex.getErrorCode());
        assertEquals(20, ex.getRetryAfterSeconds());
    }

    // ------------------------------------------------------------------
    // 失败锁定（与限流叠加的第二层防护）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("记录失败：未达阈值时只计数、不锁定")
    void recordFailureBelowThreshold() {
        when(systemConfigService.loginFailMaxCount()).thenReturn(5);
        when(systemConfigService.loginLockMinutes()).thenReturn(15);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("ticket:login:fail:alice")).thenReturn(3L);

        int count = service.recordLoginFailure("alice");

        assertEquals(3, count);
        verify(valueOperations, never()).set(startsWith("ticket:login:lock:"), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("记录失败：达到阈值时写锁定键并清除失败计数")
    void recordFailureLocksAtThreshold() {
        when(systemConfigService.loginFailMaxCount()).thenReturn(5);
        when(systemConfigService.loginLockMinutes()).thenReturn(15);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("ticket:login:fail:alice")).thenReturn(5L);

        int count = service.recordLoginFailure("alice");

        assertEquals(5, count);
        verify(valueOperations).set(eq("ticket:login:lock:alice"), anyString(), eq(Duration.ofMinutes(15)));
        verify(redisTemplate).delete("ticket:login:fail:alice");
    }

    @Test
    @DisplayName("查询锁定状态与剩余秒数")
    void lockState() {
        when(redisTemplate.hasKey("ticket:login:lock:alice")).thenReturn(true);
        when(redisTemplate.getExpire("ticket:login:lock:alice")).thenReturn(420L);
        assertTrue(service.isLocked("alice"));
        assertEquals(420L, service.getLockRemainSeconds("alice"));

        when(redisTemplate.hasKey("ticket:login:lock:bob")).thenReturn(false);
        when(redisTemplate.getExpire("ticket:login:lock:bob")).thenReturn(-2L);
        assertFalse(service.isLocked("bob"));
        assertEquals(0L, service.getLockRemainSeconds("bob"));
    }

    @Test
    @DisplayName("登录成功清除失败计数")
    void clearFailureOnSuccess() {
        service.clearLoginFailure("alice");
        verify(redisTemplate).delete("ticket:login:fail:alice");
    }
}
