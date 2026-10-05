package com.enterprise.ticket.module.security.service;

import com.enterprise.ticket.common.util.IpMatcher;
import com.enterprise.ticket.module.security.entity.IpBlock;
import com.enterprise.ticket.module.security.mapper.IpBlockMapper;
import com.enterprise.ticket.module.security.mapper.SecurityEventMapper;
import com.enterprise.ticket.module.security.support.SecurityEventType;
import com.enterprise.ticket.module.security.support.SecuritySettings;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * IP 封禁—— 自动封禁 / 自动解封 / 白名单放行 / 人工解封。
 *
 * <h2>为什么账号锁定之外还要封 IP</h2>
 * 说明是「同一账号**或**同一 IP 连续登录失败 5 次」——两者都要：
 * 只锁账号 ⇒ 攻击者换账号继续爆破（撞库）；只封 IP ⇒ 攻击者换 IP 继续（定向爆破）。
 * 账号锁定早已存在（{@code LoginProtectionService}，Redis 计数），本类补上 IP 这一侧。
 *
 * <h2>白名单为什么是「永不封禁」而不是「少封一点」</h2>
 * 办公网出口 IP 常常是**一整个办公室共用**的：一个人输错几次密码就会连累全楼。
 * 因此白名单必须是无条件的放行，而不是「降低权重」——后者在数学上仍然可能触发，
 * 而一旦触发，被挡在门外的人根本不知道自己为什么进不来。
 *
 * <h2>自动解封靠「懒判定」而不是定时任务</h2>
 * 每次判定都比一次时间（{@link IpBlock#isEffective}）。若靠定时任务改状态，
 * 任务一停摆，说好的「30 分钟」就变成永久 —— 那是最难解释的一类故障。
 * 定时任务只做「把过期行标为失效」的视图清理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IpBlockService {

    private final IpBlockMapper ipBlockMapper;
    private final SecurityEventMapper securityEventMapper;
    private final SecurityEventRecorder securityEventRecorder;
    private final SystemConfigService systemConfigService;

    // ------------------------------------------------------------------
    // 判定
    // ------------------------------------------------------------------

    /**
     * 该 IP 当前是否被封禁（白名单优先放行）。
     *
     * <p>白名单判定放在**查库之前**：白名单里的 IP 永远不该被拦，
     * 连一次无谓的查询都不必做。
     */
    public boolean isBlocked(String ip) {
        if (!StringUtils.hasText(ip) || inWhitelist(ip)) {
            return false;
        }
        if (!ipBlockEnabled()) {
            return false;
        }
        try {
            return ipBlockMapper.countEffectiveByIp(ip, LocalDateTime.now()) > 0;
        } catch (Exception e) {
            // fail-open：查库失败时放行而不是拦截。
            // 拦错的代价是「所有人都登不进来」，放行的代价是「这一轮没拦住」——
            // 后者有账号锁定兜底，前者没有兜底。
            log.warn("[IP封禁] 查询封禁状态失败，按放行处理：ip={} 原因={}", ip, e.getMessage());
            return false;
        }
    }

    /** 该 IP 是否命中白名单（支持单个 IP 与网段，见 {@code IpMatcher}） */
    public boolean inWhitelist(String ip) {
        if (!StringUtils.hasText(ip)) {
            return false;
        }
        String spec = readString(SecuritySettings.KEY_IP_WHITELIST, SecuritySettings.DEFAULT_IP_WHITELIST);
        if (!StringUtils.hasText(spec)) {
            return false;
        }
        try {
            return IpMatcher.of(splitCsv(spec)).matches(ip);
        } catch (Exception e) {
            // 白名单解析失败按「不命中」处理：宁可多封一点，也不能因为配置写错把白名单变成「全放行」
            log.warn("[IP封禁] 白名单解析失败，按不命中处理：{}", e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 自动封禁
    // ------------------------------------------------------------------

    /**
     * 记一次登录失败；若该 IP 在窗口内累计失败达阈值则自动封禁（**两级**）。
     *
     * <h2>两级封禁（P2 安全修复）</h2>
     * <ul>
     *   <li><b>一级</b>：累计失败达 {@code security_ip_block_max_count}（默认 10）→ 封
     *       {@code security_ip_block_minutes}（默认 30 分钟）；</li>
     *   <li><b>二级</b>：累计失败达 {@code security_ip_block_long_max_count}（默认 20）→ 封
     *       {@code security_ip_block_long_minutes}（默认 1440 分钟 = 24 小时）。</li>
     * </ul>
     * 二级优先判定：一旦够到二级直接把封禁时长顶到长封。封禁期间被拒的请求同样会记
     * {@code LOGIN_FAIL}（见 {@code AuthService}），因此计数会继续增长 ⇒ 一级封禁中的来源
     * 若持续攻击，会被<b>升级</b>为二级长封（判据是「现有封禁时长 < 目标时长」，避免同一 IP 刷屏）。
     *
     * @return 本次是否**刚刚**写入/升级了封禁（调用方据此决定要不要提示「已封禁」）
     */
    public boolean recordFailureAndMaybeBlock(String ip, String username, String userAgent) {
        if (!StringUtils.hasText(ip) || inWhitelist(ip)) {
            return false;
        }
        try {
            if (!ipBlockEnabled()) {
                return false;
            }
            int threshold = readInt(SecuritySettings.KEY_IP_BLOCK_MAX_COUNT,
                    SecuritySettings.DEFAULT_IP_BLOCK_MAX_COUNT);
            int blockMinutes = readInt(SecuritySettings.KEY_IP_BLOCK_MINUTES,
                    SecuritySettings.DEFAULT_IP_BLOCK_MINUTES);
            int longThreshold = readInt(SecuritySettings.KEY_IP_BLOCK_LONG_MAX_COUNT,
                    SecuritySettings.DEFAULT_IP_BLOCK_LONG_MAX_COUNT);
            int longMinutes = readInt(SecuritySettings.KEY_IP_BLOCK_LONG_MINUTES,
                    SecuritySettings.DEFAULT_IP_BLOCK_LONG_MINUTES);
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime since = now.minusMinutes(SecuritySettings.ipFailWindowMinutes(blockMinutes));

            long fails = securityEventMapper.countLoginFailByIpSince(ip, since);

            // 选出本次要达到的封禁时长；未达一级阈值则不动
            int targetMinutes;
            if (longThreshold > threshold && fails >= longThreshold) {
                targetMinutes = longMinutes;
            } else if (fails >= threshold) {
                targetMinutes = blockMinutes;
            } else {
                return false;
            }

            // 已有生效封禁时只在「可升级到更长一档」时才重写，避免同一 IP 被反复刷屏。
            // expire_at 为 null 表示人工永久封禁 → 不再需要自动改写。
            IpBlock active = ipBlockMapper.selectActiveRow(ip);
            if (active != null) {
                if (active.getExpireAt() == null) {
                    return false;
                }
                boolean effective = active.getExpireAt().isAfter(now);
                long currentMinutes = active.getBlockedAt() == null ? 0L
                        : Duration.between(active.getBlockedAt(), active.getExpireAt()).toMinutes();
                if (effective && currentMinutes >= targetMinutes) {
                    return false;
                }
            }
            return block(ip, (int) fails, targetMinutes, username, userAgent);
        } catch (Exception e) {
            log.warn("[IP封禁] 自动封禁判定失败：ip={} 原因={}", ip, e.getMessage());
            return false;
        }
    }

    private boolean block(String ip, int failCount, int blockMinutes, String username, String userAgent) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expireAt = now.plusMinutes(Math.max(1, blockMinutes));

        IpBlock row = ipBlockMapper.selectActiveRow(ip);
        if (row == null) {
            row = new IpBlock();
            row.setIp(ip);
            row.setSource("AUTO");
            row.setBlockedAt(now);
        }
        row.setReason("连续登录失败 " + failCount + " 次");
        row.setFailCount(failCount);
        row.setExpireAt(expireAt);
        row.setUnblockedAt(null);
        row.setUnblockedBy(null);
        row.setActive(true);
        if (row.getId() == null) {
            ipBlockMapper.insert(row);
        } else {
            ipBlockMapper.updateById(row);
        }

        securityEventRecorder.record(SecurityEventType.IP_BLOCKED, username, null, ip, userAgent,
                "连续登录失败 " + failCount + " 次，已自动封禁 " + blockMinutes + " 分钟");
        log.warn("[IP封禁] 已自动封禁 ip={}（{} 次失败，{} 分钟）", ip, failCount, blockMinutes);
        return true;
    }

    private long securityEventMapperCount(String ip, LocalDateTime since) {
        return securityEventMapper.countLoginFailByIpSince(ip, since);
    }

    // ------------------------------------------------------------------
    // 列表与解封
    // ------------------------------------------------------------------

    /** 当前生效中的封禁列表（页面「当前封禁」区） */
    public List<IpBlock> effectiveList() {
        try {
            return ipBlockMapper.selectEffective(LocalDateTime.now());
        } catch (Exception e) {
            log.warn("[IP封禁] 读取封禁列表失败：{}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 人工解封。
     *
     * <p>刻意**不校验来源**（自动封禁也能人工解）：误封是真实存在的，
     * 而「必须等自动过期」会让误封的代价从 30 分钟变成 30 分钟且无法干预。
     */
    public boolean unblock(Long id, Long operatorId) {
        int rows = ipBlockMapper.manualUnblock(id, LocalDateTime.now(), operatorId);
        if (rows > 0) {
            IpBlock row = ipBlockMapper.selectById(id);
            securityEventRecorder.record(SecurityEventType.IP_UNBLOCKED, null, operatorId,
                    row == null ? null : row.getIp(), null,
                    "人工解封（操作人 " + (operatorId == null ? "系统" : operatorId) + "）");
            return true;
        }
        return false;
    }

    /** 把已过期的封禁行标为失效（仅供「历史」视图；不是解封的必要条件，见类注释） */
    public int cleanupExpired() {
        try {
            return ipBlockMapper.markExpired(LocalDateTime.now());
        } catch (Exception e) {
            log.warn("[IP封禁] 标记过期失败：{}", e.getMessage());
            return 0;
        }
    }

    // ------------------------------------------------------------------
    // 配置读取（全部容错）
    // ------------------------------------------------------------------

    public boolean ipBlockEnabled() {
        return readBoolean(SecuritySettings.KEY_IP_BLOCK_ENABLED, SecuritySettings.DEFAULT_IP_BLOCK_ENABLED);
    }

    private boolean readBoolean(String key, boolean fallback) {
        try {
            if (SecuritySettings.KEY_IP_BLOCK_ENABLED.equals(key)) {
                return systemConfigService.securityIpBlockEnabled();
            }
            return fallback;
        } catch (Exception e) {
            log.warn("[IP封禁] 读取开关失败，用缺省值 {}：{}", fallback, e.getMessage());
            return fallback;
        }
    }

    private int readInt(String key, int fallback) {
        try {
            if (SecuritySettings.KEY_IP_BLOCK_MAX_COUNT.equals(key)) {
                return systemConfigService.securityIpBlockMaxCount();
            }
            if (SecuritySettings.KEY_IP_BLOCK_MINUTES.equals(key)) {
                return systemConfigService.securityIpBlockMinutes();
            }
            if (SecuritySettings.KEY_IP_BLOCK_LONG_MAX_COUNT.equals(key)) {
                return systemConfigService.securityIpBlockLongMaxCount();
            }
            if (SecuritySettings.KEY_IP_BLOCK_LONG_MINUTES.equals(key)) {
                return systemConfigService.securityIpBlockLongMinutes();
            }
            return fallback;
        } catch (Exception e) {
            log.warn("[IP封禁] 读取配置失败，用缺省值 {}：{}", fallback, e.getMessage());
            return fallback;
        }
    }

    private String readString(String key, String fallback) {
        try {
            if (SecuritySettings.KEY_IP_WHITELIST.equals(key)) {
                return systemConfigService.securityIpWhitelist();
            }
            return fallback;
        } catch (Exception e) {
            log.warn("[IP封禁] 读取配置失败：{}", e.getMessage());
            return fallback;
        }
    }

    private List<String> splitCsv(String value) {
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .toList();
    }
}
