package com.enterprise.ticket.module.order.dto.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 转单申请视图（V55 / D5.7）。
 *
 * <p>审计要素一次性给全：操作人、时间、工单号、原小组、原执行人、目标小组、
 * 目标执行人、原因、流转状态。缺任何一项，「这笔单为什么到了这个组」都答不完整。
 */
@Data
public class TransferRequestVO {

    private Long id;

    private Long orderId;

    private String orderNo;

    /** SAME_GROUP / CROSS_GROUP */
    private String transferScope;

    private String transferScopeLabel;

    private Long fromDepartmentId;

    /** 原处理小组名称（小组被删时为空，但 id 与历史仍可查） */
    private String fromDepartmentName;

    private Long toDepartmentId;

    private String toDepartmentName;

    private Long fromHandlerId;

    private String fromHandlerName;

    /** 目标执行人；跨组转单在「接收并分配」之前为空 */
    private Long toHandlerId;

    private String toHandlerName;

    /** 待处理人（同组=本组管理员；跨组=目标组管理员） */
    private Long toAdminId;

    private String toAdminName;

    /** PENDING / ACCEPTED / REJECTED / CANCELLED */
    private String status;

    private String statusLabel;

    private String reason;

    private Long operatorId;

    private String operatorName;

    private Long decideBy;

    private String decideByName;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime decideAt;

    private String decideComment;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;

    /**
     * 当前登录者能否处理这条申请（服务端判定，前端只渲染按钮）。
     *
     * <p>与 {@code OrderTransferRequestServiceImpl#decide} 的准入条件同一套 ——
     * 前端若自己再判一遍（"我是不是管理员"），两处迟早漂移成「按钮可点但接口 403」。
     */
    private Boolean canDecide;
}
