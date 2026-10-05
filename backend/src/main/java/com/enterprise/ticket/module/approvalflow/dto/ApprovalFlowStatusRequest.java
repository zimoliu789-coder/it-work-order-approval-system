package com.enterprise.ticket.module.approvalflow.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 流程模板状态切换请求：ENABLED / DISABLED
 */
@Data
public class ApprovalFlowStatusRequest {

    @NotBlank(message = "状态不能为空")
    private String status;
}
