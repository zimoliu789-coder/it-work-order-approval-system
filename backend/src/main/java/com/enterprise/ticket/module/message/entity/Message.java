package com.enterprise.ticket.module.message.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 站内消息（ 站内消息通知系统）
 *
 * <p>字段与「消息表字段：id、user_id、title、content、order_id（跳转关联）、is_read、created_at」
 * 一一对应，另补 {@code messageType}（前端按类型选图标与跳转目标）与 {@code readAt}（已读时间）。
 *
 * <p><b>写入策略</b>：消息是「业务动作的通知产物」，不是业务事实本身 ——
 * 业务状态（工单状态机、设备状态）已经落库且可由操作日志追溯，因此消息写入采用
 * <b>尽力而为</b>（见 {@code MessageService#send}）：写失败只记 error 日志，不回滚业务事务。
 * 这样「通知表抖动导致归还失败」这类明显不合理的因果不会发生。
 *
 * <p><b>字段命名</b>：{@code isRead} 经 MyBatis-Plus 默认驼峰转下划线后即 {@code is_read}，
 * 与数据库列名一致，无需额外 {@code @TableField}。
 */
@Data
@TableName("messages")
public class Message {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 接收人 user_id */
    private Long userId;

    /** 消息标题 */
    private String title;

    /** 消息正文 */
    private String content;

    /** 跳转关联工单 orders.id，可为空（非工单类消息） */
    private Long orderId;

    /** 消息类型，取值见 {@link com.enterprise.ticket.common.constant.MessageType} */
    private String messageType;

    /** 是否已读：false 未读 / true 已读 */
    private Boolean isRead;

    /** 已读时间 */
    private LocalDateTime readAt;

    private LocalDateTime createdAt;
}
