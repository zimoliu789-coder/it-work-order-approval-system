package com.enterprise.ticket.module.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 借用延期子工单（ 新增，）
 *
 * <p>与主工单的关系（ 明确）：
 * <ul>
 *   <li>延期是**子工单**，独立一行，**不修改主工单的申请记录**（{@link Order} 的申请字段保持不变）；</li>
 *   <li>审批通过后只回写主单三个「借用时间」字段：{@code planned_end_time}（延长）、
 *       {@code borrow_timeout}（重算）、{@code auto_extend_count}（归零）；</li>
 *   <li>最终处理人沿用主单当前 {@code actual_final_handler_id}，不重新分配。</li>
 * </ul>
 *
 * <p>审批快照见 {@link OrderExtendApprovalNode}。
 */
@Data
@TableName("order_extend")
public class OrderExtend {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 主工单 orders.id */
    private Long orderId;

    /** 发起人 user_id（服务层校验其必须等于主单申请人，「申请人可主动发起」） */
    private Long applicantId;

    /** 发起时的原计划结束时间（快照，用于审批人对比「原定 → 新定」） */
    private LocalDateTime originalEndTime;

    /** 申请延长到的新结束时间（必须晚于当前系统时间，） */
    private LocalDateTime newEndTime;

    /** 延期原因（必填） */
    private String reason;

    /** 状态，取值见 {@link com.enterprise.ticket.common.constant.ExtendStatus} */
    private String status;

    /** 审批动作时间（通过 / 驳回） */
    private LocalDateTime actionTime;

    /** 审批意见（驳回时必填） */
    private String actionComment;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
