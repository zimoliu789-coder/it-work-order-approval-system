package com.enterprise.ticket.module.approvalflow.dto.vo;

import lombok.Data;

import java.util.List;

/**
 * 流程定义校验结果（M4a）。
 *
 * <p>刻意包一层对象而不是直接返回 {@code List<String>}：后续若要加
 * 「按节点分组」「严重级别」等字段，不必改动前端调用契约。
 *
 * <p>{@code valid} 是由 {@code problems} 派生的便捷字段，前端可直接用于
 * 「通过 / 未通过」两态渲染，无需自己判空数组。
 */
@Data
public class FlowValidateResultVO {

    /** 是否通过（problems 为空即通过） */
    private boolean valid;

    /** 全部问题（保持发现顺序；为空数组代表通过） */
    private List<String> problems;

    public FlowValidateResultVO(List<String> problems) {
        this.problems = problems == null ? List.of() : problems;
        this.valid = this.problems.isEmpty();
    }
}
