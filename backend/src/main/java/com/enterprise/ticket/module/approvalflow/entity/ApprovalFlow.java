package com.enterprise.ticket.module.approvalflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审批流程模板。
 *
 * <p>与一期 {@code form_template} 同构：模板（可改）+ 版本（发布即冻结）。
 * 工单只引用**版本 id**，因此改流程不会改写历史工单的审批口径。
 */
@Data
@TableName("approval_flow")
public class ApprovalFlow {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 流程编码（全局唯一，字母开头） */
    private String flowCode;

    /** 流程名称 */
    private String flowName;

    /** 流程说明 */
    private String description;

    /** 状态：DRAFT 草稿 / PUBLISHED 已发布 / DISABLED 已停用 */
    private String status;

    /** 创建人 user_id */
    private Long createdBy;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
