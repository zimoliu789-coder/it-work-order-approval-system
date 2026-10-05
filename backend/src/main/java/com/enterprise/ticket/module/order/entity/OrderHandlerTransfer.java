package com.enterprise.ticket.module.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工单转交记录（ 新增，）
 *
 * <p><b>为什么单独建表而不是在 orders 上加审计字段</b>：一笔工单可以被转交多次
 * （A → B → C），「当前执行人」是状态（{@code orders.actual_final_handler_id}，
 * 每次转交被覆盖），「谁在什么时候因为什么转给谁」是历史（本表，只追加不修改）。
 * 二者混在一张表里必然丢失中间过程。
 *
 * <p><b>字段命名逐字沿用</b>，仅追加 {@code transferType}（规范字段清单之外）
 * 用于区分人工转交与离职自动转交（需求方  ）。
 *
 * <p>本表为追加型历史表，没有 {@code updated_at} —— 转交记录一经写入不应再被修改。
 */
@Data
@TableName("order_handler_transfer")
public class OrderHandlerTransfer {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 工单 orders.id */
    private Long orderId;

    /** 原实际执行人 user_id（ old_handler_id） */
    private Long oldHandlerId;

    /** 新实际执行人 user_id（ new_handler_id） */
    private Long newHandlerId;

    /** 转交操作人 user_id（ transfer_operator_id）：人工转交为原执行人本人或代转超管；自动转交为原执行人 */
    private Long transferOperatorId;

    /** 转交备注（ transfer_comment）：人工转交必填；自动转交由服务层写入系统说明 */
    private String transferComment;

    /** 转交来源，取值见 {@link com.enterprise.ticket.common.constant.TransferType} */
    private String transferType;

    /** 转交时间（ created_at） */
    private LocalDateTime createdAt;
}
