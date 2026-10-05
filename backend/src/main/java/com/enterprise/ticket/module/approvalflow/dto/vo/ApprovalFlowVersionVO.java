package com.enterprise.ticket.module.approvalflow.dto.vo;

import com.enterprise.ticket.common.flow.FlowDefinition;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 流程版本
 */
@Data
public class ApprovalFlowVersionVO {

    private Long id;
    private Long flowId;
    private Integer versionNo;
    /** 是否草稿（未发布） */
    private Boolean draft;
    private Integer nodeCount;
    private LocalDateTime publishedAt;

    /** 仅版本详情接口返回（列表不返回完整定义，避免响应过大） */
    private FlowDefinition definition;
}
