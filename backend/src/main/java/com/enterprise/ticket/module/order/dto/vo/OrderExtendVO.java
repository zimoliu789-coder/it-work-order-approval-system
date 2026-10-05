package com.enterprise.ticket.module.order.dto.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 延期子工单视图
 *
 * <p>用于「工单详情 → 延期时间线」展示：申请时间、原定结束时间、申请延长到的时间、
 * 当前状态与审批链路快照。
 */
@Data
public class OrderExtendVO {

    private Long id;

    /** 主工单 id */
    private Long orderId;

    /** 主工单编号（便于前端直接展示） */
    private String orderNo;

    /** 发起人 */
    private Long applicantId;

    private String applicantName;

    /** 发起时所处的原计划结束时间（展示「原定 → 新定」对比，便于审批人判断） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime originalEndTime;

    /** 申请延长到的新结束时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime newEndTime;

    private String reason;

    /** PENDING_APPROVAL / APPROVED / REJECTED */
    private String status;

    private String statusLabel;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime actionTime;

    private String actionComment;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    /** 审批快照链路（复用主单的 {@link ApprovalNodeView} 结构） */
    private List<ApprovalNodeView> nodes;
}
