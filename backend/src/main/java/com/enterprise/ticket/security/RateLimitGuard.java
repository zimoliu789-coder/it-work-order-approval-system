package com.enterprise.ticket.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 限流计数器（：使用 Redis 实现限流计数器）
 *
 * <h2>职责边界</h2>
 * <p>本类只做「计数 + 判定 + 告诉调用方还要等多久」，不关心业务语义，也不抛业务异常 ——
 * 调用方决定用哪个维度、超限时给什么提示。这样登录、提交、全局 API 三条限流线
 * 共用同一套 Redis 语义，不会出现「登录用固定窗口、提交用别的算法」这类口径分裂。
 *
 * <h2>两种算法为什么都要有</h2>
 * <ul>
 *   <li><b>固定窗口</b>（{@link #tryAcquireFixedWindow}）：与「1 分钟最多 5 次」
 *       的字面口径一致，用于登录 / 提交这类「次数敏感」的接口；</li>
 *   <li><b>令牌桶</b>（{@link #tryAcquireToken}）：用于全局 API 兜底限流。
 *       固定窗口在窗口边界会出现「双倍突发」（59 秒打满 + 01 秒再打满），
 *       全局限流若用固定窗口，恰好会在边界被打穿；令牌桶按速率匀速补充，
 *       并可用 burst 显式表达「允许多大的突发」，是规范要求的「全局 QPS + 突发数」的正解。</li>
 * </ul>
 *
 * <h2>故障策略：fail-open（宁可放行也不阻断业务）</h2>
 * <p>Redis 不可用时<b>放行</b>并打 WARN。理由：Redis 同时是临时锁、令牌黑名单的依赖，
 * 它挂掉时系统已处于降级状态；此时若再让限流 fail-closed，会把「部分功能不可用」
 * 放大成「所有人无法登录」。限流是<b>抗滥用的加固</b>，不是业务的正确性前提，
 * 因此选择可用性优先，并把故障暴露在日志里（而非静默）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitGuard {

    /**
     * 令牌桶 Lua：单键内原子完成「补令牌 → 扣令牌 → 回写」，避免读写分离导致超发。
     *
     * <pre>
     * KEYS[1] = 桶键
     * ARGV[1] = rate  每秒补充令牌数（可为小数）
     * ARGV[2] = burst 桶容量（突发上限）
     * ARGV[3] = now   当前毫秒时间戳
     * ARGV[4] = requested 本次请求令牌数（固定 1）
     * 返回    = {是否放行(0/1), 剩余令牌, 距下次可用的毫秒数}
     * </pre>
     */
    private static final String TOKEN_BUCKET_LUA = """
            local key = KEYS[1]
            local rate = tonumber(ARGV[1])
            local burst = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local requested = tonumber(ARGV[4])

            local data = redis.call('HMGET', key, 'tokens', 'ts')
            local tokens = tonumber(data[1])
            local ts = tonumber(data[2])
            if tokens == nil then
              tokens = burst
              ts = now
            end
            -- 时钟回拨保护：以「现在」为新基准，避免负数时间差把令牌冲爆
            if now < ts then
              ts = now
            end

            local delta = (now - ts) / 1000.0
            tokens = math.min(burst, tokens + delta * rate)

            local allowed = 0
            local waitMs = 0
            if tokens >= requested then
              tokens = tokens - requested
              allowed = 1
            else
              waitMs = math.ceil((requested - tokens) / rate * 1000)
            end

            redis.call('HSET', key, 'tokens', tokens, 'ts', now)
            -- 桶从空补满需 burst/rate 秒；TTL 取该值 2 倍并留 1 秒余量，防止键无限堆积
            redis.call('PEXPIRE', key, math.ceil(burst / rate * 2000) + 1000)
            return {allowed, math.floor(tokens), waitMs}
            """;

    private static final RedisScript<List> TOKEN_BUCKET_SCRIPT =
            new DefaultRedisScript<>(TOKEN_BUCKET_LUA, List.class);

    private final StringRedisTemplate redisTemplate;

    /**
     * 限流判定结果
     *
     * @param allowed           是否放行
     * @param retryAfterSeconds 被拒时建议的重试等待秒数（用于 Retry-After 响应头与前端提示）
     */
    public record Decision(boolean allowed, long retryAfterSeconds) {

        static Decision allow() {
            return new Decision(true, 0L);
        }

        static Decision reject(long retryAfterSeconds) {
            return new Decision(false, Math.max(1L, retryAfterSeconds));
        }
    }

    /**
     * 固定窗口限流：窗口内第 limit+1 次请求被拒。
     *
     * @param limit  &lt;= 0 表示不限流（配置项被关掉时的统一语义）
     * @param window 窗口长度
     */
    public Decision tryAcquireFixedWindow(String key, int limit, Duration window) {
        if (limit <= 0) {
            return Decision.allow();
        }
        try {
            Long current = redisTemplate.opsForValue().increment(key);
            if (current == null) {
                return Decision.allow();
            }
            if (current == 1L) {
                redisTemplate.expire(key, window);
            }
            if (current <= limit) {
                return Decision.allow();
            }
            return Decision.reject(remainingSeconds(key, window));
        } catch (Exception e) {
            return failOpen("固定窗口", key, e);
        }
    }

    /**
     * 令牌桶限流：容量 burst、每秒补充 ratePerMinute/60 个令牌。
     *
     * @param ratePerMinute 每分钟允许的请求数（&lt;= 0 表示不限流）
     * @param burst         允许的瞬时突发上限（&lt;= 0 时退化为 1，避免配置写 0 导致全部拒绝）
     */
    public Decision tryAcquireToken(String key, int ratePerMinute, int burst) {
        if (ratePerMinute <= 0) {
            return Decision.allow();
        }
        double rate = ratePerMinute / 60.0;
        int capacity = Math.max(1, burst);
        try {
            List<?> raw = redisTemplate.execute(TOKEN_BUCKET_SCRIPT, List.of(key),
                    String.valueOf(rate),
                    String.valueOf(capacity),
                    String.valueOf(System.currentTimeMillis()),
                    "1");
            if (raw == null || raw.size() < 3) {
                return Decision.allow();
            }
            boolean allowed = toLong(raw.get(0)) == 1L;
            if (allowed) {
                return Decision.allow();
            }
            long waitMs = toLong(raw.get(2));
            return Decision.reject(waitMs <= 0 ? 1L : (waitMs + 999L) / 1000L);
        } catch (Exception e) {
            return failOpen("令牌桶", key, e);
        }
    }

    /**
     * 一次性标记：仅在 TTL 内首次调用返回 true。
     *
     * <p>用途：限流被拒时「写一条操作日志」这件事本身必须限频 ——
     * 否则攻击者只要持续打接口，审计表就会被自己的拦截记录写爆，
     * 从「抗滥用」变成「被滥用写爆磁盘」。
     */
    public boolean tryMarkOnce(String key, Duration ttl) {
        try {
            Boolean first = redisTemplate.opsForValue().setIfAbsent(key, "1", ttl);
            return Boolean.TRUE.equals(first);
        } catch (Exception e) {
            log.warn("限流标记写入失败（Redis 异常），本次按「可记录」处理: key={} err={}", key, e.getMessage());
            return true;
        }
    }

    /** 键的剩余存活秒数；无 TTL 或键不存在时返回 fallback */
    private long remainingSeconds(String key, Duration fallback) {
        try {
            Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
            if (ttl != null && ttl > 0) {
                return ttl;
            }
        } catch (Exception e) {
            log.warn("读取限流键 TTL 失败: key={} err={}", key, e.getMessage());
        }
        return Math.max(1L, fallback.toSeconds());
    }

    private Decision failOpen(String algorithm, String key, Exception e) {
        log.warn("限流计数器（{}）执行失败，本次放行以免阻断业务: key={} err={}",
                algorithm, key, e.getMessage());
        return Decision.allow();
    }

    /** Lua 数字返回可能是 Long / Integer / String，统一收敛 */
    private long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }
}
