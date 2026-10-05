package com.enterprise.ticket.common.flow;

import lombok.Data;

import java.util.List;

/**
 * 审批人规则。
 *
 * <p>一个审批节点持有若干条规则，**规则之间取并集**：例如"指定人员 A + 角色 admin"
 * 表示"由 A 和全部 admin 共同审批"，再配合节点的 signType 决定是会签还是或签。
 *
 * <p>用"一个扁平 POJO 承载所有类型的参数"而不是"每个类型一个子类 + Jackson 多态"：
 * 后者序列化更漂亮，但流程定义是给设计器整体读写的，前端也要一一定位字段，
 * 扁平结构在前端处理起来更直接；参数校验由 {@link ApproverRuleValidator} 按 type 分工，
 * 该严的地方一样严。
 */
@Data
public class ApproverRule {

    /** 规则类型，取值见 {@link ApproverRuleType} */
    private String type;

    // ---------------- SPECIFIC_USER ----------------
    /** 指定人员 user_id 列表 */
    private List<Long> userIds;

    // ---------------- ROLE ----------------
    /** 角色码（super_admin / admin / user） */
    private String roleCode;

    // ---------------- HANDLER_GROUP ----------------
    /** 最终处理部门 id */
    private Long handlerGroupId;

    // ---------------- FORM_USER_FIELD ----------------
    /** 表单中「人员选择」字段的 key */
    private String fieldKey;

    // ---------------- APPLICANT_CHOOSE ----------------
    /** 可选范围：ROLE / GROUP / ALL，取值见 {@link ApproverRuleType.ChooseScope} */
    private String scope;
    /** 范围取值：scope=ROLE 时为角色码，scope=GROUP 时为部门 id 字符串，scope=ALL 时忽略 */
    private String scopeValue;
    /** 最少选择人数（>=1） */
    private Integer minCount;
    /** 最多选择人数（>= minCount） */
    private Integer maxCount;

    // ---------------- PREV_ASSIGN ----------------
    /**
     * 上一节点需指定的人数，默认 1。
     *
     * <p>上一节点审批人通过时必须在**同一数量**上选满：不能多、不能少。
     * 该规则只支持或签（{@code ANY_SIGN}）—— "指定若干人任一人审批"。
     */
    private Integer assignCount;

    /**
     * 指定者的可选范围，取值见 {@link ApproverRuleType.AssignScope}。
     *
     * <p>null / 空串视为 {@code ALL}（不限制）—— 存量流程定义没有这个参数，
     * 必须保持旧行为。配了 {@code IT_EXECUTOR} 时，服务端在回填审批人时
     * 会校验被指定人确实落在「IT执行人角色 ∪ 最终处理部门成员」里。
     */
    private String assignScope;

    public static ApproverRule of(String type) {
        ApproverRule rule = new ApproverRule();
        rule.setType(type);
        return rule;
    }
}
