package com.enterprise.ticket.common.job;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 定时任务分布式锁（「所有定时任务必须幂等」+ 需求方「加分布式锁防重复执行」）
 *
 * <p><b>为什么是「优化」而不是「正确性前提」</b>：本项目的每个定时任务都靠数据层面的
 * <b>幂等位</b>保证不重复处理（{@code remind_before_sent_at} / {@code due_reminded_at} /
 * {@code last_timeout_alert_at} / {@code auto_extend_count}），并且每个状态推进都带
 * 状态前置条件的条件 UPDATE。也就是说：即便两个实例同时跑同一个任务，也不会重复通知或重复顺延。
 * 分布式锁的作用是<b>减少无效扫描与数据库争用</b>，不是防重复的唯一手段。
 *
 * <p>正因为如此，本实现采取 <b>fail-open</b> 策略：Redis 不可用时返回「已加锁」并继续执行，
 * 而不是让任务彻底停摆。反过来若采用 fail-closed，一次 Redis 抖动就会导致全系统当天
 * 所有到期预警、顺延与超时告警都不发出 —— 那是更严重的业务事故。
 *
 * <p><b>令牌语义</b>：锁值写入随机令牌，释放时先比对令牌再删除，避免「A 实例的锁已因 TTL 过期
 * 被 B 实例重新持有，此时 A 才姗姗来迟地释放」而误删 B 的锁。比对与删除之间存在极小的竞态窗口，
 * 由于其后果仅是「多跑一次任务」（而任务本身幂等），故不为此引入 Lua 脚本的复杂度。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobLockService {

    /** 锁 key 前缀，与登录限流等既有 Redis key 保持同一命名空间 */
    private static final String KEY_PREFIX = "ticket:job:lock:";

    private final StringRedisTemplate redisTemplate;

    /**
     * 尝试获取锁
     *
     * @return 令牌；返回 {@code null} 表示锁已被其他实例持有，调用方应跳过本次执行
     */
    public String tryLock(String jobName, Duration ttl) {
        String key = KEY_PREFIX + jobName;
        String token = UUID.randomUUID().toString();
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, token, ttl);
            if (Boolean.TRUE.equals(acquired)) {
                return token;
            }
            return null;
        } catch (Exception e) {
            // fail-open：Redis 抖动不应让定时任务整体停摆（任务本身幂等，多跑一次无害）
            log.warn("定时任务分布式锁获取异常，按 fail-open 策略继续执行：job={}", jobName, e);
            return token;
        }
    }

    /**
     * 释放锁（仅当令牌匹配时删除，见类注释）
     */
    public void unlock(String jobName, String token) {
        if (!StringUtils.hasText(token)) {
            return;
        }
        String key = KEY_PREFIX + jobName;
        try {
            String current = redisTemplate.opsForValue().get(key);
            if (token.equals(current)) {
                redisTemplate.delete(key);
            }
        } catch (Exception e) {
            // 释放失败不影响业务：锁会在 TTL 到期后自动消失
            log.warn("定时任务分布式锁释放异常（将由 TTL 自动过期）：job={}", jobName, e);
        }
    }

    /**
     * 在分布式锁保护下执行任务
     *
     * @param jobName      任务名
     * @param ttl          锁有效期（应显著大于任务正常执行耗时，又小于触发间隔）
     * @param action       任务体
     * @param skippedValue 未获取到锁时返回的结果
     */
    public <T> T runLocked(String jobName, Duration ttl, Supplier<T> action, Supplier<T> skippedValue) {
        String token = tryLock(jobName, ttl);
        if (token == null) {
            log.info("定时任务 {} 未获取到分布式锁，本实例跳过本次执行", jobName);
            return skippedValue.get();
        }
        try {
            return action.get();
        } finally {
            unlock(jobName, token);
        }
    }
}
