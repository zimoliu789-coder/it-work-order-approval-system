package com.enterprise.ticket.module.approvalflow.dto.vo;

import com.enterprise.ticket.common.flow.FlowDefinition;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 流程模板详情：供设计器加载
 */
@Data
public class ApprovalFlowDetailVO {

    private Long id;
    private String flowCode;
    private String flowName;
    private String description;
    private String status;
    private String statusLabel;

    /** 当前草稿版本 id（无草稿时为空；设计器保存草稿即写入该版本） */
    private Long draftVersionId;
    /** 最新已发布版本号 */
    private Integer publishedVersionNo;

    /** 流程定义（有草稿给草稿，无草稿给最新已发布版本） */
    private FlowDefinition definition;

    /** 版本历史（倒序，含草稿） */
    private List<ApprovalFlowVersionVO> versions;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
