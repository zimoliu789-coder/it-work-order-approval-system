package com.enterprise.ticket.module.security.service;

import com.enterprise.ticket.common.alert.AlertLevel;
import com.enterprise.ticket.common.alert.AlertService;
import com.enterprise.ticket.common.alert.ExceptionClassifier;
import com.enterprise.ticket.common.constant.MessageType;
import com.enterprise.ticket.module.security.entity.SecurityEvent;
import com.enterprise.ticket.module.security.mapper.SecurityEventMapper;
import com.enterprise.ticket.module.security.support.SecurityEventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 安全事件记录与告警。
 *
 * <h2>不是每一次登录失败都发邮件</h2>
 * 登录失败是**最频繁**的安全事件（用户自己也会输错密码）。逐条告警会让邮箱在一天内被填满，
 * 而真正需要被看到的「有人正在爆破」会淹没在里面。因此：
 * <ul>
 *   <li>{@code LOGIN_FAIL} —— **只落库、不告警**。它的价值在于累计计数（触发 IP 封禁）
 *       与趋势视图（「最近 1 小时失败最多的 IP」）；</li>
 *   <li>{@code ACCOUNT_LOCKED} / {@code IP_BLOCKED} / {@code PERM_ESCALATION_ATTEMPT}
 *       —— **告警**。它们代表「防护已经介入」或「有人越界」，是必须知情的节点。</li>
 * </ul>
 * 这正是「告警疲劳」的直接对策：只把**已经发生后果**的事件推给人。
 *
 * <h2>它自己绝不抛异常</h2>
 * 调用方在登录路径上（一个正在做安全判定的请求线程里）。
 * 这里抛出去会让「记录一次失败」变成「登录接口 500」，反而制造了新的可用性问题。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SecurityEventRecorder {

    private static final int MAX_DETAIL = 500;
    private static final int MAX_UA = 255;

    private final SecurityEventMapper securityEventMapper;
    private final AlertService alertService;

    /** 记录一条安全事件（默认不告警；是否需要告警由事件类型决定，见类注释） */
    public void record(SecurityEventType type, String username, Long userId, String ip,
                       String userAgent, String detail) {
        record(type, username, userId, ip, userAgent, detail, true);
    }

    /**
     * 记录一条安全事件，但**不自动告警**。
     *
     * <p>供「告警与否由调用方另行决定」的场景使用：异常登录（{@link SecurityEventType#LOGIN_ANOMALY}）
     * 对普通员工只通知本人，只有管理员账号才额外告警超管 —— 若沿用「按类型告警」，
     * 每一次新设备登录都会推给全体超管，很快就会被当成噪音忽略。
     */
    public void recordQuietly(SecurityEventType type, String username, Long userId, String ip,
                              String userAgent, String detail) {
        record(type, username, userId, ip, userAgent, detail, false);
    }

    private void record(SecurityEventType type, String username, Long userId, String ip,
                        String userAgent, String detail, boolean allowAlert) {
        try {
            SecurityEvent event = new SecurityEvent();
            event.setEventType(type.name());
            event.setUsername(ExceptionClassifier.truncate(username, 64));
            event.setUserId(userId);
            event.setIp(ExceptionClassifier.truncate(ip, 64));
            event.setUserAgent(ExceptionClassifier.truncate(userAgent, MAX_UA));
            event.setDetail(ExceptionClassifier.truncate(detail, MAX_DETAIL));
            event.setOccurredAt(LocalDateTime.now());
            securityEventMapper.insert(event);

            if (allowAlert && shouldAlert(type)) {
                dispatchAlert(type, username, ip, detail);
            }
        } catch (Exception e) {
            log.warn("[安全事件] 落库失败：{}", e.getMessage());
        }
    }

    /**
     * 哪些类型需要告警。
     *
     * <p>判据是「防护是否已经介入」或「是否越界」：
     * 锁定与封禁说明有人已经撞到墙了（也意味着「再不管就会成功」）；
     * 越权尝试说明有人在试探边界。单纯的失败则留给计数与趋势。
     */
    private boolean shouldAlert(SecurityEventType type) {
        return switch (type) {
            case ACCOUNT_LOCKED, IP_BLOCKED, PERM_ESCALATION_ATTEMPT, LOGIN_ANOMALY -> true;
            case LOGIN_FAIL, IP_UNBLOCKED -> false;
        };
    }

    private void dispatchAlert(SecurityEventType type, String username, String ip, String detail) {
        String title = "安全事件：" + type.label();
        String body = "系统检测到一起需要关注的安全事件。\n"
                + "事件类型：" + type.label() + "（" + type.name() + "）\n"
                + "账号：" + safe(username) + "\n"
                + "来源 IP：" + safe(ip) + "\n"
                + (detail == null || detail.isBlank() ? "" : "说明：" + detail + "\n")
                + "发生时间：" + AlertService.now() + "\n\n"
                + "处理建议：到「系统设置 → 安全日志」按 IP 与类型检索完整事件；"
                + "若确认是误封（例如办公网出口 IP 被多人共用），可在该页手动解封，"
                + "并把该 IP 段加入系统参数里的「IP 白名单」。";
        alertService.alert(AlertLevel.P1, MessageType.SECURITY_ALERT, title, body,
                "SECURITY_" + type.name(),
                "安全事件 | type=" + type.name() + " | username=" + safe(username) + " | ip=" + safe(ip));
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "-" : value.trim();
    }
}
