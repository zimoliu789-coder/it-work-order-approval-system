package com.enterprise.ticket.module.order.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 处理转单申请（V55 / D5.7）：同组审批 / 跨组接收或拒绝，统一走这一个请求体。
 *
 * <h2>为什么「接收」必须同时给目标执行人</h2>
 * <p>把「接收」与「分配」拆成两步，会让工单出现一个**无人负责**的中间态：
 * 原组已转出、新组还没指定人，而超时告警 / 催还 / 待办列表全部以执行人为查询键 ——
 * 它们会集体找不到人。因此这里要求「接收即分配」，三态也才干净。
 */
@Data
public class TransferRequestDecideRequest {

    /** true = 同意（同组通过 / 跨组接收并分配）；false = 拒绝（工单回流原组原执行人） */
    @NotNull(message = "请选择同意或拒绝")
    private Boolean accepted;

    /**
     * 跨组接收时**必填**：目标小组内的执行人 user_id。
     *
     * <p>同组转单忽略本字段（目标执行人在发起时就定了）。
     */
    private Long targetHandlerId;

    /** 处理意见（拒绝时建议填写原因，便于发起人知道下一步怎么办） */
    @Size(max = 500, message = "处理意见不能超过 500 字")
    private String comment;
}
