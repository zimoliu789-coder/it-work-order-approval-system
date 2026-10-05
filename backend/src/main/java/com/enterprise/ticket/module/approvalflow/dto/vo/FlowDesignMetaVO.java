package com.enterprise.ticket.module.approvalflow.dto.vo;

import lombok.Data;

import java.util.List;

/**
 * 流程设计器元数据（ · W4-D / C8）。
 *
 * <h2>它解决什么问题</h2>
 * <p>设计器（前端）需要知道三件事才能正确渲染并即时提示：条件树深度上限、
 * 每个业务域禁用了哪些审批人来源、每种来源要配哪些参数。这三件事此前在前端各有
 * 一份**硬编码副本**（{@code BORROW_FORBIDDEN_RULE_TYPES} / {@code FLOW_CONDITION_MAX_DEPTH} /
 * {@code APPROVER_RULE_TYPE_OPTIONS}）。副本与后端的漂移不会报错，只会表现为
 * 「设计器里配得好好的，点发布被后端拒掉」——正是 M4a 起就在治理的那类缝隙。
 *
 * <p>因此把这三份事实**从后端下发**，前端只保留"接口不可用时的兜底默认"。
 *
 * <h2>为什么下发里没有「节点类型」「运算符」等其它枚举</h2>
 * <p>只下发**会在后端产生硬拒绝**的那些：深度超限与禁用规则会让发布直接失败，
 * 参数槽位决定"填了但后端不认"还是"没填导致校验失败"。节点类型 / 运算符 / 签署方式
 * 是纯粹的编辑器词汇，前端自己定义即可 —— 把它们也搬过来只会增加耦合而无收益。
 */
@Data
public class FlowDesignMetaVO {

    /**
     * 条件树最大嵌套层数（最外层算第 1 层）——数据源是 {@code FlowCondition.MAX_DEPTH}。
     *
     * <p>它与后端发布校验是同一个常量，杜绝了"设计器允许加、后端拒绝发布"。
     */
    private int conditionMaxDepth;

    /** 各业务域（数据源 {@code FlowScope}），含该域的禁用规则集 */
    private List<Scope> scopes;

    /** 审批人来源（数据源 {@code ApproverRuleType}），含其参数槽位 */
    private List<RuleType> ruleTypes;

    /** 一个业务域的元数据 */
    @Data
    public static class Scope {

        /** 域编码（与 {@code FlowScope} 枚举名一致） */
        private String code;

        /** 域名称（用于提示文案；前端下拉文案属编辑器词汇，不强制取自此处） */
        private String label;

        /** 本域下**不可用**的审批人来源编码（有序；空数组 = 无禁用） */
        private List<String> forbiddenRuleTypes;
    }

    /** 一种审批人来源的元数据 */
    @Data
    public static class RuleType {

        /** 来源编码（与 {@code ApproverRuleType} 枚举名一致） */
        private String code;

        /** 来源名称（与后端报错文案同源，避免同一规则在两端叫不同名字） */
        private String label;

        /** 需要配置的参数槽位（有序；空数组 = 无需参数） */
        private List<String> params;
    }
}
