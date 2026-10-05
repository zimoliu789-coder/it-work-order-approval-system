package com.enterprise.ticket.module.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 延期子工单审批快照节点（ 新增，「独立生成延期快照」）
 *
 * <p>结构与 {@link OrderApprovalNode} 完全一致（一行 = 一个「步骤 × 审批人」），
 * 但**物理独立成表**：主单审批链路已交付并大量使用（待办查询依赖其索引与谓词），
 * 共用一张表会让「主单审批待办」误匹配到延期节点，因此选择独立表。
 *
 * <p>生成方式：申请人提交延期申请的瞬间，复制主工单已固化的审批快照
 * （{@code approverId / signType / stepOrder}），并按 重新解析审批人可用性、
 * 按 应用「审批人 = 申请人则跳过」。
 */
@Data
@TableName("order_extend_approval_nodes")
public class OrderExtendApprovalNode {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 延期子工单 order_extend.id */
    private Long extendId;

    /** 审批步骤序号（同延期单内按此升序执行） */
    private Integer stepOrder;

    /** 审批人 user_id（快照固化） */
    private Long approverId;

    /** 会签 ALL_SIGN / 或签 ANY_SIGN（快照固化） */
    private String signType;

    /** 节点状态，取值见 {@link com.enterprise.ticket.common.constant.ApprovalNodeStatus} */
    private String status;

    private LocalDateTime actionTime;

    private String actionComment;

    /** 是否 super_admin 兜底审批节点 */
    @TableField("is_super_backup")
    private Boolean superBackup;

    /** 是否因原审批人离职/禁用自动替换为兜底 */
    @TableField("is_fallback")
    private Boolean fallback;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
