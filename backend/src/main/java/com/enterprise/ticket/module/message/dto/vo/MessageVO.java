package com.enterprise.ticket.module.message.dto.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 站内消息列表视图
 */
@Data
public class MessageVO {

    private Long id;

    private String title;

    private String content;

    /** 关联工单 orders.id，为空表示该消息不带跳转目标 */
    private Long orderId;

    /** 关联工单编号（便于前端直接展示「工单 BO2026…」而不必再查一次详情） */
    private String orderNo;

    /** 消息类型，取值见 MessageType */
    private String messageType;

    private String messageTypeLabel;

    private Boolean isRead;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime readAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
