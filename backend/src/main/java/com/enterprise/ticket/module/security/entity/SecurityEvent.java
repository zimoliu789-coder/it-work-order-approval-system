package com.enterprise.ticket.module.security.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 安全事件。
 *
 * <p>与 {@code operation_log} 的区别：操作日志是**审计**（谁做了什么，用于追责），
 * 本表是**威胁视图**（谁在攻击，用于封禁与趋势判断）。「最近 24 小时失败最多的 10 个 IP」
 * 这类查询在操作日志上要全表扫文本，在这里是一句带索引的 GROUP BY。
 *
 * <p>刻意不加外键：攻击者输入的账号名可能根本不存在，用户也可能事后被删。
 * 安全事件必须原样保留当时的输入。
 */
@Data
@TableName("security_event")
public class SecurityEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** {@code SecurityEventType} 的枚举名 */
    private String eventType;

    /** 尝试登录的账号名（可能不存在于 users 表） */
    private String username;

    /** 匹配到的用户 id；账号不存在时为 null */
    private Long userId;

    private String ip;

    /** User-Agent（截断到列宽） */
    private String userAgent;

    private String detail;

    private LocalDateTime occurredAt;

    private LocalDateTime createdAt;
}
