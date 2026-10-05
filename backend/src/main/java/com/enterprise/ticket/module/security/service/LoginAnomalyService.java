package com.enterprise.ticket.module.security.service;

import com.enterprise.ticket.common.alert.AlertLevel;
import com.enterprise.ticket.common.alert.AlertService;
import com.enterprise.ticket.common.constant.MessageType;
import com.enterprise.ticket.common.constant.RoleCode;
import com.enterprise.ticket.module.message.service.UserNotificationService;
import com.enterprise.ticket.module.security.support.SecurityEventType;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import com.enterprise.ticket.module.user.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 异常登录检测与通知（P2 安全修复）。
 *
 * <h2>检测三类情形</h2>
 * <ol>
 *   <li><b>凌晨登录</b>：0–6 点之间的登录（正常办公时段的登录不算）；</li>
 *   <li><b>新设备</b>：该账号此前从未出现过的 User-Agent（UA 哈希判重，避免明文落 Redis）；</li>
 *   <li><b>非常用 IP</b>：该账号此前登录过的 IP 集合里没有本次来源 IP（首次登录不判，因为没有基线）。</li>
 * </ol>
 *
 * <h2>基线怎么维护</h2>
 * <p>用两个 Redis Set 记录「该账号已知的 IP / 设备指纹」，TTL 90 天（活跃用户持续登录会自动续期，
 * 离职或长期不用的账号 90 天后基线自然清空）。<b>无论本次是否异常都会写入基线</b> ——
 * 否则同一个新设备会被永久判为「新」。
 *
 * <h2>通知策略（避免告警疲劳）</h2>
 * <ul>
 *   <li>命中即落一条 {@link SecurityEventType#LOGIN_ANOMALY} 安全事件（**不自动告警**，
 *       否则每次新设备登录都会推给全体超管）；</li>
 *   <li>通知<b>用户本人</b>（站内 + 邮件）—— 这是「是不是你本人」的第一道确认；</li>
 *   <li><b>仅当账号是管理员及以上</b>（{@link RoleCode#isAdminOrAbove}）时，额外告警超管 ——
 *       管理员账号一旦被冒用，影响面远超普通员工。</li>
 * </ul>
 *
 * <h2>它绝不抛异常</h2>
 * <p>调用点在「登录成功」的收尾路径上。这里抛出去会把一次成功的登录变成 500，
 * 反而制造可用性问题。所有异常一律降级为日志。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginAnomalyService {

    /** 已知 IP / 设备基线的保留期 */
    private static final Duration KNOWN_TTL = Duration.ofDays(90);

    private static final String KEY_KNOWN_IP = "ticket:login:known:ip:";
    private static final String KEY_KNOWN_UA = "ticket:login:known:ua:";

    /** 凌晨时段 [0, 6) 点 */
    private static final int OFF_HOURS_START = 0;
    private static final int OFF_HOURS_END = 6;

    /** UA 最大长度：与 security_event.user_agent / operation_logs 的口径一致 */
    private static final int MAX_UA = 255;

    private final StringRedisTemplate redisTemplate;
    private final SecurityEventRecorder securityEventRecorder;
    private final SystemConfigService systemConfigService;
    private final UserNotificationService userNotificationService;
    private final AlertService alertService;

    /**
     * 登录成功后调用，检测并处理异常登录（非阻断）。
     *
     * @param user      已通过认证的用户
     * @param ip        本次登录的来源 IP
     * @param userAgent 本次登录的 User-Agent
     */
    public void inspect(User user, String ip, String userAgent) {
        try {
            if (user == null || user.getId() == null) {
                return;
            }
            if (!systemConfigService.securityLoginAnomalyEnabled()) {
                return;
            }

            LocalDateTime now = LocalDateTime.now();
            List<String> reasons = new ArrayList<>();
            if (isOffHours(now)) {
                reasons.add("凌晨时段（0–6 点）登录");
            }

            String ipKey = normalize(ip, 64);
            String uaKey = userAgentHash(userAgent);

            // 「新设备」与「非常用 IP」都必须**先有基线**才有意义：
            // 首次登录时两个基线都为空，若据此判定，功能上线后每个人的第一次登录都会被误报一次。
            boolean hasUaBaseline = hasKnown(KEY_KNOWN_UA, user.getId());
            if (uaKey != null && hasUaBaseline && !isKnown(KEY_KNOWN_UA, user.getId(), uaKey)) {
                reasons.add("新设备登录");
            }
            boolean hasIpBaseline = hasKnown(KEY_KNOWN_IP, user.getId());
            if (ipKey != null && hasIpBaseline && !isKnown(KEY_KNOWN_IP, user.getId(), ipKey)) {
                reasons.add("非常用 IP 登录（" + ipKey + "）");
            }

            // 无论是否异常，都把本次指纹写入基线
            if (ipKey != null) {
                remember(KEY_KNOWN_IP, user.getId(), ipKey);
            }
            if (uaKey != null) {
                remember(KEY_KNOWN_UA, user.getId(), uaKey);
            }

            if (reasons.isEmpty()) {
                return;
            }

            String detail = "命中：" + String.join("；", reasons)
                    + "，ip=" + (ipKey == null ? "-" : ipKey);
            securityEventRecorder.recordQuietly(SecurityEventType.LOGIN_ANOMALY,
                    user.getUsername(), user.getId(), ip, userAgent, detail);

            notifyUser(user, reasons);
            if (RoleCode.isAdminOrAbove(user.getRole())) {
                alertAdmins(user, detail);
            }
            log.warn("[异常登录] 账号 {}（id={}）命中异常登录：{}",
                    user.getUsername(), user.getId(), String.join("；", reasons));
        } catch (Exception e) {
            log.warn("[异常登录] 检测失败（不影响登录）：userId={} 原因={}",
                    user == null ? null : user.getId(), e.getMessage());
        }
    }

    /** 通知用户本人（站内 + 邮件）：异常登录的第一处置是让本人确认「是不是我」 */
    private void notifyUser(User user, List<String> reasons) {
        String body = "你的账号于 " + AlertService.now() + " 发生了一次异常登录：\n"
                + "· " + String.join("\n· ", reasons) + "\n\n"
                + "如果这是你本人的操作，可以忽略本提醒。\n"
                + "如果不是你本人操作，请立即修改密码，并联系管理员核查。";
        userNotificationService.notify(user.getId(), MessageType.SECURITY_ALERT,
                "异常登录提醒", body, null);
    }

    /** 管理员及以上账号：额外告警超管（P2 只发超管，不再扩散到运维邮箱） */
    private void alertAdmins(User user, String detail) {
        String title = "管理员账号异常登录";
        String body = "检测到管理员账号「" + user.getUsername() + "」（id=" + user.getId() + "）发生异常登录。\n"
                + detail + "\n"
                + "发生时间：" + AlertService.now() + "\n\n"
                + "处理建议：到「系统设置 → 安全日志」按类型 LOGIN_ANOMALY 检索完整事件；"
                + "确认非本人操作时，请立即强制该账号下线（重置密码）并修改其口令。";
        alertService.alert(AlertLevel.P2, MessageType.SECURITY_ALERT, title, body,
                "SECURITY_LOGIN_ANOMALY",
                "管理员异常登录 | username=" + user.getUsername() + " | " + detail);
    }

    private boolean isOffHours(LocalDateTime now) {
        int hour = now.getHour();
        return hour >= OFF_HOURS_START && hour < OFF_HOURS_END;
    }

    private boolean isKnown(String keyPrefix, Long userId, String value) {
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(keyPrefix + userId, value));
    }

    private boolean hasKnown(String keyPrefix, Long userId) {
        Long size = redisTemplate.opsForSet().size(keyPrefix + userId);
        return size != null && size > 0;
    }

    private void remember(String keyPrefix, Long userId, String value) {
        String key = keyPrefix + userId;
        redisTemplate.opsForSet().add(key, value);
        redisTemplate.expire(key, KNOWN_TTL);
    }

    private String normalize(String raw, int maxLength) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String value = raw.trim();
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    /** UA 取 SHA-256 十六进制：避免把完整 UA 串写进 Redis（也压缩了长度） */
    private String userAgentHash(String userAgent) {
        String ua = normalize(userAgent, MAX_UA);
        if (ua == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(ua.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            // 极端情况下拿不到摘要算法：退回明文（长度已截断），总比不检测强
            return ua;
        }
    }
}
