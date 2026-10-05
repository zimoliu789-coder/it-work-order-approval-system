package com.enterprise.ticket.module.approvalflow.dto;

import com.enterprise.ticket.common.flow.FlowDefinition;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 流程模板保存请求。
 *
 * <p>{@code definition} 允许为半成品：保存草稿**宽松**（只要能序列化即可），
 * 结构校验（可达 / 无环 / 穷尽分支 / 条件字段）只在**发布**时严格做。
 * 这与一期表单模板"草稿宽松、发布严格"的取舍完全一致——设计一半时不该被拦。
 */
@Data
public class ApprovalFlowSaveRequest {

    @NotBlank(message = "流程编码不能为空")
    private String flowCode;

    @NotBlank(message = "流程名称不能为空")
    private String flowName;

    private String description;

    /** 流程定义（草稿允许不完整） */
    private FlowDefinition definition;
}
