package com.enterprise.ticket.module.order.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 超时未处理的审批节点（需求方三波·第二波·）
 *
 * <p>专为「审批超时提醒」任务设计的只读投影：只带消息正文需要的最小字段集，
 * 避免把整个订单聚合拉进内存（任务每小时跑一次，扫描很便宜，但没必要更贵）。
 */
@Data
public class OverdueApprovalNode {

    /** 审批节点主键 */
    private Long nodeId;

    private Long orderId;

    private String orderNo;

    /** 当前应处理该节点的审批人 user_id */
    private Long approverId;

    private Integer stepOrder;

    /** 工单提交时间（超时时长的起算点；无 deadline_at 时的回落口径） */
    private LocalDateTime submittedAt;

    private String applicantName;

    /**
     * 申请人 user_id（：超时提醒同时发给申请人，让他知道工单卡在谁那里）
     */
    private Long applicantId;

    private String deviceName;

    /**
     * 节点审批时限截止时间（；NULL = 不限时，回落「提交后 N 小时」全局阈值）。
     *
     * <p>有值时以它为准判定超时（「已超过约定审批时限」），
     * 无值时沿用既有的 {@code orders.created_at + hours} 口径。
     */
    private LocalDateTime deadlineAt;
}
