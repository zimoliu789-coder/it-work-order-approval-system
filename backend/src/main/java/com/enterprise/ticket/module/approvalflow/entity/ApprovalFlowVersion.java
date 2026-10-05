package com.enterprise.ticket.module.approvalflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审批流程版本—— 发布后冻结。
 *
 * <p>流程定义整体存 {@code definition_json}：流程是**一次性整体读写**的配置
 * （设计器里整棵画布一起保存），拆成"节点表 + 连线表"只会让保存变成对三类行做 diff，
 * 而连线合法性（可达 / 无环 / 穷尽分支）本就必须由服务层校验器判定，外键给不了这些保证。
 */
@Data
@TableName("approval_flow_version")
public class ApprovalFlowVersion {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属流程 approval_flow.id */
    private Long flowId;

    /** 版本号（同一流程内从 1 递增） */
    private Integer versionNo;

    /** 流程定义 JSON：{start, nodes:[...]} */
    private String definitionJson;

    /** 审批节点数量（发布校验产物，供列表展示） */
    private Integer nodeCount;

    /** 发布时间（NULL = 草稿） */
    private LocalDateTime publishedAt;

    /** 发布人 user_id */
    private Long publishedBy;

    public boolean isDraft() {
        return publishedAt == null;
    }
}
