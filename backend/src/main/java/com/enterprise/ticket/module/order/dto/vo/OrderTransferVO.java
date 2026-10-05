package com.enterprise.ticket.module.order.dto.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工单转交记录视图（ + 「工单详情页展示转交历史记录」）
 */
@Data
public class OrderTransferVO {

    private Long id;

    private Long orderId;

    private String orderNo;

    /** 原实际执行人 */
    private Long oldHandlerId;

    private String oldHandlerName;

    /** 新实际执行人 */
    private Long newHandlerId;

    private String newHandlerName;

    /** 转交操作人（与 oldHandlerId 不同表示由超管代为转交） */
    private Long transferOperatorId;

    private String transferOperatorName;

    /** 转交原因 / 备注（ transfer_comment） */
    private String transferComment;

    /** MANUAL 人工转交 / AUTO_DIMISSION 离职自动转交 */
    private String transferType;

    private String transferTypeLabel;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
