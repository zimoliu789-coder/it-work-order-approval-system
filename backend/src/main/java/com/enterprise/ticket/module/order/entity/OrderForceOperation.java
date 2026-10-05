package com.enterprise.ticket.module.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 超管强制干预记录（；规范 V1.1 未覆盖）
 *
 * <p><b>为什么独立建表而非只写 {@code operation_logs}</b>：工单详情页需要按
 * {@code order_id} 组装「强制操作时间线」，而审计日志是全局流水（含大量非工单事件），
 * 按 order_id 反查既低效也不可靠（日志里的 order_id 是文本化的业务参数，不是索引列）。
 *
 * <p><b>为什么四种操作共用一张表</b>：它们的「共同部分」（谁、对哪笔单、什么原因、
 * 何时、状态怎么变）完全一致，差异集中在 {@code old_/new_} 两组字段 —— 按操作类型
 * 部分留空即可，无需为每种操作各建一张窄表。
 */
@Data
@TableName("order_force_operation")
public class OrderForceOperation {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 工单 orders.id */
    private Long orderId;

    /** 操作类型，取值见 {@link com.enterprise.ticket.common.constant.ForceOperationType} */
    private String operationType;

    /** 操作人 user_id（恒为 super_admin） */
    private Long operatorId;

    /** 强制原因（需求方要求必填） */
    private String reason;

    /** 操作前工单状态 */
    private String oldStatus;

    /** 操作后工单状态（转交类操作前后状态相同） */
    private String newStatus;

    /** 强制转交审批时被改派的待办节点 id；其余操作为空 */
    private Long nodeId;

    /** 强制转交审批：原审批人 user_id */
    private Long oldApproverId;

    /** 强制转交审批：新审批人 user_id */
    private Long newApproverId;

    /** 强制转交执行人：原实际执行人 user_id（未分配时为空） */
    private Long oldHandlerId;

    /** 强制转交执行人：新实际执行人 user_id */
    private Long newHandlerId;

    private LocalDateTime createdAt;
}
