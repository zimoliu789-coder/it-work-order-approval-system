package com.enterprise.ticket.module.order.dto;

import lombok.Data;

/**
 * 超管强制干预请求（）
 *
 * <p>四种操作共用一个请求体，只有「转交类」需要目标人字段：
 * <ul>
 *   <li>{@code FORCE_REJECT} / {@code FORCE_TERMINATE} —— 仅 {@code reason}；</li>
 *   <li>{@code FORCE_TRANSFER_APPROVAL} —— {@code reason} + {@code targetApproverId}；</li>
 *   <li>{@code FORCE_TRANSFER_HANDLER} —— {@code reason} + {@code targetHandlerId}。</li>
 * </ul>
 *
 * <p><b>刻意不使用 {@code @NotBlank} 等 Bean Validation</b>：那样校验失败会统一降级为
 * {@code PARAM_INVALID}，把「必须填原因」这一具体业务约束淹没成通用参数错误。
 * 校验放在服务层，返回 {@code FORCE_REASON_REQUIRED} / {@code FORCE_TARGET_*_REQUIRED}
 * 等明确错误码（与「时间校验统一放服务层」的项目约定一致）。
 */
@Data
public class OrderForceRequest {

    /** 操作类型，取值见 {@link com.enterprise.ticket.common.constant.ForceOperationType} */
    private String operationType;

    /** 强制原因（必填） */
    private String reason;

    /** 强制转交执行人：目标执行人 user_id */
    private Long targetHandlerId;

    /** 强制转交审批：目标审批人 user_id */
    private Long targetApproverId;
}
