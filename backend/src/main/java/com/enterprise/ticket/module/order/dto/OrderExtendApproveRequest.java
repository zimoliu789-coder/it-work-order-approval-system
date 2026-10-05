package com.enterprise.ticket.module.order.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 延期子工单审批请求（，复用主单审批规则）
 *
 * <p>与主单 {@link OrderApproveRequest} 完全同构：驳回必须填写原因（ 的规则
 * 同样适用于延期审批）。
 */
@Data
public class OrderExtendApproveRequest {

    /** true=通过，false=驳回 */
    @NotNull(message = "请选择审批结果")
    private Boolean approved;

    /** 审批意见；驳回时必填 */
    @Size(max = 500, message = "审批意见长度不能超过 500 个字符")
    private String comment;
}
