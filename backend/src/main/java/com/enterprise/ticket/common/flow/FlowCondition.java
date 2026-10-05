package com.enterprise.ticket.common.flow;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 条件组：若干节点（单条规则 / 嵌套条件组）按 AND / OR 组合（； · M3-A 支持嵌套）。
 *
 * <h2>从"单层"到"三层"的取舍</h2>
 * <p>第二期刻意<b>只做单层</b>（一组规则 + 一个逻辑运算符）。M3 放开嵌套，但**限定 3 层**
 * （见 {@link #MAX_DEPTH}）：三层 AND/OR 已能表达 "(A 且 B) 或 (C 且 D)" 这类真实需求，
 * 而设计器 UI、说明文案、校验器的复杂度随层数**线性上升**，收益却迅速递减。
 *
 * <h2>深度怎么数</h2>
 * <p>**最外层条件组算第 1 层**，它内部再嵌一个组就是第 2 层，依此类推。
 * 于是「一个组里只放规则」= 深度 1 —— 与第二期完全同形，这正是零回归的来源：
 * 存量定义天然就是深度 1，落在上限之内，不需要任何迁移。
 *
 * <h2>等价表示的一个可观察差异</h2>
 * <p>存量 JSON 里没有 {@code kind} 字段。重新保存一份旧定义后，序列化结果会多出
 * {@code "kind":"RULE"}（{@link ConditionRule} 的字段默认值非 null，因此不会被省略）。
 * 这属于**同一份配置的等价表示**，不影响求值、不影响说明文案，只是 diff 里会看到它。
 */
@Data
public class FlowCondition {

    /** 组合逻辑：AND（全部满足）/ OR（任一满足） */
    private String logic = LOGIC_AND;

    /** 子节点：单条规则（{@code kind=RULE}）或嵌套组（{@code kind=GROUP}） */
    private List<ConditionRule> rules = new ArrayList<>();

    public static final String LOGIC_AND = "AND";
    public static final String LOGIC_OR = "OR";

    /**
     * 条件树的最大嵌套层数（最外层算 1 层）。
     *
     * <p>它同时是**校验器**的硬约束与**前端**禁用"添加条件组"按钮的阈值 ——
     * 双端引用同一个数值，避免"设计器允许加、后端拒绝发布"这种最令人困惑的组合。
     */
    public static final int MAX_DEPTH = 3;

    public boolean isOr() {
        return LOGIC_OR.equalsIgnoreCase(logic);
    }

    public List<ConditionRule> getRules() {
        return rules == null ? new ArrayList<>() : rules;
    }

    /**
     * 本组下是否**直接**挂着至少一个嵌套组。
     *
     * <p>用途只有一个：让调用方在"绝大多数条件都是单层"的常见情形下走快路径。
     * 注意它是"直接子级"而非"任意深度" —— 需要整棵树的信息时请用
     * {@link FlowPathResolver#depthOf} 或直接递归，别把这个方法当"含嵌套"的全量判据。
     */
    public boolean hasNestedGroup() {
        for (ConditionRule rule : getRules()) {
            if (rule != null && rule.isGroup()) {
                return true;
            }
        }
        return false;
    }
}
