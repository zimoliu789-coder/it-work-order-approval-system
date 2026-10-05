package com.enterprise.ticket.module.order.dto.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 超管强制干预记录视图（）
 *
 * <p>用于工单详情页的「强制操作时间线」：与转交历史、催办记录并列展示。
 * 中文标签由服务端统一装配（{@code operationTypeLabel} / {@code oldStatusLabel} …），
 * 前端不重复维护一份映射表。
 */
@Data
public class OrderForceOperationVO {

    private Long id;

    private Long orderId;

    /** 操作类型编码 */
    private String operationType;

    private String operationTypeLabel;

    private Long operatorId;

    /** 操作人姓名 */
    private String operatorName;

    /** 强制原因 */
    private String reason;

    private String oldStatus;

    private String oldStatusLabel;

    private String newStatus;

    private String newStatusLabel;

    // ---- 强制转交审批 ----

    private Long oldApproverId;

    private String oldApproverName;

    private Long newApproverId;

    private String newApproverName;

    // ---- 强制转交执行人 ----

    private Long oldHandlerId;

    private String oldHandlerName;

    private Long newHandlerId;

    private String newHandlerName;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
