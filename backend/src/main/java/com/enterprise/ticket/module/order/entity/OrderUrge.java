package com.enterprise.ticket.module.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工单催办记录（ 新增；规范 V1.1 未覆盖，按需求方 2026-09-18 确认方案实现）
 *
 * <p>本表承载的是「工单事件」而非「消息投递」，两者刻意分离：
 * <ul>
 *   <li>{@code messages} —— 给某个人的一条通知，有已读/未读，是<b>投递</b>语义；</li>
 *   <li>{@code order_urge} —— 某笔工单上发生过一次催办，是<b>事件</b>语义，无已读状态。</li>
 * </ul>
 * 混用会让「同单同节点 1 小时冷却」这类业务查询被迫扫消息表，也会让催办统计被已读状态污染。
 *
 * <p>{@code nodeId} 是冷却判定的关键：审批推进到下一步后节点变化，申请人即可对新节点再次催办。
 * 归还催办没有节点，该字段为空。
 *
 * <p>本表为追加型历史表，没有 {@code updated_at}。
 */
@Data
@TableName("order_urge")
public class OrderUrge {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 工单 orders.id */
    private Long orderId;

    /** 催办类型，取值见 {@link com.enterprise.ticket.common.constant.UrgeType} */
    private String urgeType;

    /** 审批催办时锁定的当前审批节点 order_approval_nodes.id；归还催办为空 */
    private Long nodeId;

    /** 被催办人 user_id（审批催办＝当前节点审批人；归还催办＝借用人） */
    private Long targetUserId;

    /** 催办发起人 user_id（审批催办＝申请人；归还催办＝实际执行人） */
    private Long operatorId;

    /** 催办时间 */
    private LocalDateTime createdAt;
}
