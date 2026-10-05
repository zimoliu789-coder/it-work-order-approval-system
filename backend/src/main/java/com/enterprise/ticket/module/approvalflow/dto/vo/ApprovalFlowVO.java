package com.enterprise.ticket.module.approvalflow.dto.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 流程模板列表项
 */
@Data
public class ApprovalFlowVO {

    private Long id;
    private String flowCode;
    private String flowName;
    private String description;
    private String status;
    private String statusLabel;

    /** 最新已发布版本号（无已发布版本时为空） */
    private Integer latestPublishedVersionNo;
    /** 最新已发布版本 id（供申请类型绑定选择） */
    private Long latestPublishedVersionId;
    /** 是否存在未发布的草稿 */
    private Boolean hasDraft;
    /** 已发布版本中的审批节点数 */
    private Integer nodeCount;
    /** 被多少个申请类型引用 */
    private Long usedByTypeCount;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
