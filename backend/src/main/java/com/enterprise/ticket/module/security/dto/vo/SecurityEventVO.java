package com.enterprise.ticket.module.security.dto.vo;

import com.enterprise.ticket.module.security.entity.IpBlock;
import com.enterprise.ticket.module.security.entity.SecurityEvent;
import com.enterprise.ticket.module.security.support.SecurityEventType;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 安全事件列表行。
 */
@Data
public class SecurityEventVO {

    private Long id;

    /** 类型编码：LOGIN_FAIL / ACCOUNT_LOCKED / IP_BLOCKED / IP_UNBLOCKED / PERM_ESCALATION_ATTEMPT */
    private String eventType;

    /** 类型中文标签 */
    private String eventTypeLabel;

    private String username;

    private Long userId;

    private String ip;

    private String userAgent;

    private String detail;

    private LocalDateTime occurredAt;

    public static SecurityEventVO of(SecurityEvent entity) {
        SecurityEventVO vo = new SecurityEventVO();
        vo.setId(entity.getId());
        vo.setEventType(entity.getEventType());
        vo.setEventTypeLabel(SecurityEventType.fromName(entity.getEventType()).label());
        vo.setUsername(entity.getUsername());
        vo.setUserId(entity.getUserId());
        vo.setIp(entity.getIp());
        vo.setUserAgent(entity.getUserAgent());
        vo.setDetail(entity.getDetail());
        vo.setOccurredAt(entity.getOccurredAt());
        return vo;
    }

    /** 当前封禁列表行（含「是否永久」这一关键信息 —— 永久封禁只能人工解除） */
    @Data
    public static class BlockVO {

        private Long id;

        private String ip;

        private String reason;

        /** AUTO 自动 / MANUAL 人工 */
        private String source;

        private String sourceLabel;

        private Integer failCount;

        private LocalDateTime blockedAt;

        /** null 表示永久封禁 */
        private LocalDateTime expireAt;

        private boolean permanent;

        public static BlockVO of(IpBlock entity) {
            BlockVO vo = new BlockVO();
            vo.setId(entity.getId());
            vo.setIp(entity.getIp());
            vo.setReason(entity.getReason());
            vo.setSource(entity.getSource());
            vo.setSourceLabel("AUTO".equals(entity.getSource()) ? "自动" : "人工");
            vo.setFailCount(entity.getFailCount());
            vo.setBlockedAt(entity.getBlockedAt());
            vo.setExpireAt(entity.getExpireAt());
            vo.setPermanent(entity.getExpireAt() == null);
            return vo;
        }
    }
}
