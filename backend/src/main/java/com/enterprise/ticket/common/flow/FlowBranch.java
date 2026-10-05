package com.enterprise.ticket.common.flow;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 条件分支的一条出口。
 *
 * <p>每条出口要么带条件（{@code condition}），要么是**默认出口**（{@code else: true}）。
 * 一个条件节点必须**恰有一个默认出口**，否则"所有条件都不命中"时流程无路可走。
 */
@Data
public class FlowBranch {

    /** 分支标识（同一条件节点内唯一，用于展示与追溯） */
    private String key;

    /** 分支名称（展示用，如"金额大于 5000"） */
    private String name;

    /**
     * 是否默认出口。
     *
     * <p>字段名不能直接叫 {@code else}（Java 关键字），故用 {@code elseBranch} +
     * {@link JsonProperty} 映射回 JSON 的 {@code "else"}，对外契约仍是需求方习惯的写法。
     */
    @JsonProperty("else")
    private Boolean elseBranch;

    /** 命中本分支所需满足的条件（默认出口为 null） */
    private FlowCondition condition;

    /** 命中本分支后前往的节点 key */
    private String next;

    public boolean isElse() {
        return Boolean.TRUE.equals(elseBranch);
    }
}
