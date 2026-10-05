package com.enterprise.ticket.common.flow;

import com.enterprise.ticket.common.form.FormField;
import com.enterprise.ticket.common.form.FormSchema;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 流程内核单测的共享夹具（Phase 15 · Wave 1）。
 *
 * <p>为什么单独抽一个类：{@code FlowDefinitionValidatorTest} / {@code FlowPathResolverTest} /
 * {@code FlowDefinitionCodecTest} 都需要**同一份"结构正确"的流程定义**作为基准，
 * 然后各自逐项破坏它来验证某个具体校验/求值分支。若各写一份，基准一旦漂移，
 * 三个测试对"什么是合法流程"的理解就会不一致 —— 而这类不一致恰恰会掩盖真实缺陷。
 *
 * <p>基准结构与接口回归 {@code _p15-regression.sh} 使用的定义同构：条件分支的两条出口
 * **汇合**到同一后继，用来覆盖"一个节点被两条边指向"这一 DAG 的关键情形。
 */
final class FlowTestFixtures {

    private FlowTestFixtures() {
    }

    // ------------------------------------------------------------------
    // 基准流程
    // ------------------------------------------------------------------

    /**
     * 主管审批 →（金额>5000 走财务复核、否则走归档确认）→ 归档确认 → 结束
     *
     * <p>注意 {@code n2.next} 与 {@code b2.next} 都指向 {@code n3}：两条支链在此汇合。
     */
    static FlowDefinition regressionFlow() {
        FlowDefinition definition = new FlowDefinition();
        definition.setStart("n1");
        definition.setNodes(new ArrayList<>(List.of(
                approval("n1", "主管审批", "ANY_SIGN", "c1", roleRule("admin")),
                condition("c1", "金额判断",
                        branch("b1", "金额大于5000", "n2", cond("AND", rule("amount", "GT", "5000"))),
                        elseBranch("b2", "其它情况", "n3")),
                approval("n2", "财务复核", "ALL_SIGN", "n3", applicantChoose("ALL", null, 1, 2)),
                approval("n3", "归档确认", "ANY_SIGN", "end", formUserField("receiver")),
                end("end", "结束")
        )));
        return definition;
    }

    /** 深拷贝（用序列化往返，保证与运行时同样的 JSON 语义） */
    static FlowDefinition copyOf(FlowDefinition definition) {
        return FlowDefinitionCodec.read(FlowDefinitionCodec.write(definition));
    }

    static FlowNode find(FlowDefinition definition, String key) {
        return definition.getNodes().stream()
                .filter(node -> key.equals(node.getKey()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("夹具中不存在节点：" + key));
    }

    // ------------------------------------------------------------------
    // 节点 / 分支 / 条件
    // ------------------------------------------------------------------

    static FlowNode approval(String key, String name, String signType, String next, ApproverRule... rules) {
        FlowNode node = new FlowNode();
        node.setKey(key);
        node.setType(FlowNodeType.APPROVAL.name());
        node.setName(name);
        node.setSignType(signType);
        node.setNext(next);
        node.setApproverRules(new ArrayList<>(Arrays.asList(rules)));
        return node;
    }

    static FlowNode condition(String key, String name, FlowBranch... branches) {
        FlowNode node = new FlowNode();
        node.setKey(key);
        node.setType(FlowNodeType.CONDITION.name());
        node.setName(name);
        node.setBranches(new ArrayList<>(Arrays.asList(branches)));
        return node;
    }

    static FlowNode end(String key, String name) {
        FlowNode node = new FlowNode();
        node.setKey(key);
        node.setType(FlowNodeType.END.name());
        node.setName(name);
        return node;
    }

    static FlowBranch branch(String key, String name, String next, FlowCondition condition) {
        FlowBranch branch = new FlowBranch();
        branch.setKey(key);
        branch.setName(name);
        branch.setNext(next);
        branch.setElseBranch(false);
        branch.setCondition(condition);
        return branch;
    }

    static FlowBranch elseBranch(String key, String name, String next) {
        FlowBranch branch = new FlowBranch();
        branch.setKey(key);
        branch.setName(name);
        branch.setNext(next);
        branch.setElseBranch(true);
        return branch;
    }

    static FlowCondition cond(String logic, ConditionRule... rules) {
        FlowCondition condition = new FlowCondition();
        condition.setLogic(logic);
        condition.setRules(new ArrayList<>(Arrays.asList(rules)));
        return condition;
    }

    static ConditionRule rule(String field, String op, Object value) {
        ConditionRule rule = new ConditionRule();
        rule.setField(field);
        rule.setOp(op);
        rule.setValue(value);
        return rule;
    }

    // ------------------------------------------------------------------
    // 审批人规则
    // ------------------------------------------------------------------

    static ApproverRule roleRule(String roleCode) {
        ApproverRule rule = new ApproverRule();
        rule.setType(ApproverRuleType.ROLE.name());
        rule.setRoleCode(roleCode);
        return rule;
    }

    static ApproverRule specificUserRule(Long... userIds) {
        ApproverRule rule = new ApproverRule();
        rule.setType(ApproverRuleType.SPECIFIC_USER.name());
        rule.setUserIds(new ArrayList<>(Arrays.asList(userIds)));
        return rule;
    }

    static ApproverRule bizGroupApproverRule() {
        ApproverRule rule = new ApproverRule();
        rule.setType(ApproverRuleType.BIZ_GROUP_APPROVERS.name());
        return rule;
    }

    /** 上级部门主管（批次 D）：无参数 */
    static ApproverRule parentDeptApproverRule() {
        ApproverRule rule = new ApproverRule();
        rule.setType(ApproverRuleType.PARENT_DEPT_APPROVERS.name());
        return rule;
    }

    static ApproverRule handlerGroupRule(Long groupId) {
        ApproverRule rule = new ApproverRule();
        rule.setType(ApproverRuleType.HANDLER_GROUP.name());
        rule.setHandlerGroupId(groupId);
        return rule;
    }

    static ApproverRule formUserField(String fieldKey) {
        ApproverRule rule = new ApproverRule();
        rule.setType(ApproverRuleType.FORM_USER_FIELD.name());
        rule.setFieldKey(fieldKey);
        return rule;
    }

    static ApproverRule applicantChoose(String scope, String scopeValue, Integer min, Integer max) {
        ApproverRule rule = new ApproverRule();
        rule.setType(ApproverRuleType.APPLICANT_CHOOSE.name());
        rule.setScope(scope);
        rule.setScopeValue(scopeValue);
        rule.setMinCount(min);
        rule.setMaxCount(max);
        return rule;
    }

    // ------------------------------------------------------------------
    // 表单 schema
    // ------------------------------------------------------------------

    static FormSchema schema(FormField... fields) {
        FormSchema schema = new FormSchema();
        schema.setFields(new ArrayList<>(Arrays.asList(fields)));
        return schema;
    }

    static FormField field(String key, String label, String type) {
        FormField field = new FormField();
        field.setKey(key);
        field.setLabel(label);
        field.setType(type);
        return field;
    }

    // ------------------------------------------------------------------
    // Wave 2：抄送 / 审批时限 / 直属领导 / 上一节点指定
    // ------------------------------------------------------------------

    /** 抄送节点（无 signType、无 branches、无时限——发布校验会拒掉多余的语义字段） */
    static FlowNode cc(String key, String name, String next, ApproverRule... rules) {
        FlowNode node = new FlowNode();
        node.setKey(key);
        node.setType(FlowNodeType.CC.name());
        node.setName(name);
        node.setNext(next);
        node.setApproverRules(new ArrayList<>(Arrays.asList(rules)));
        return node;
    }

    /** 带审批时限的审批节点（timeLimitHours 为 null 表示不限时） */
    static FlowNode approvalWithLimit(String key, String name, String signType, Integer timeLimitHours,
                                      String next, ApproverRule... rules) {
        FlowNode node = approval(key, name, signType, next, rules);
        node.setTimeLimitHours(timeLimitHours);
        return node;
    }

    /** 申请人直属领导 */
    static ApproverRule leaderRule() {
        ApproverRule rule = new ApproverRule();
        rule.setType(ApproverRuleType.LEADER.name());
        return rule;
    }

    /** 上一节点审批人指定（assignCount 为占位行数，默认 1；不带指派范围） */
    static ApproverRule prevAssignRule(Integer assignCount) {
        return prevAssignRule(assignCount, null);
    }

    /**
     * 上一节点审批人指定 + 指派范围（批次 D）。
     *
     * @param assignScope {@code ALL} / {@code IT_EXECUTOR} / {@code null}（表示"不限制"，
     *                    即存量定义没有这个参数的形态）
     */
    static ApproverRule prevAssignRule(Integer assignCount, String assignScope) {
        ApproverRule rule = new ApproverRule();
        rule.setType(ApproverRuleType.PREV_ASSIGN.name());
        rule.setAssignCount(assignCount);
        rule.setAssignScope(assignScope);
        return rule;
    }

    /**
     * Wave 2 全要素流程：部门负责人审批(限时24h) → 抄送管理员 → 金额判断
     * →（>5000）直属领导审批(限时48h) → 上一节点指定 2 人 → 结束
     * →（else）上一节点指定 2 人 → 结束
     *
     * <p>注意两条分支都汇合到 {@code n3}（PREV_ASSIGN 节点）：
     * 这是「PRESENT_ASSIGN 的前置审批节点是谁」在两条路径下答案不同的关键场景，
     * 用一条流程同时覆盖「有前置审批节点」与「前置节点是条件分支」两种运行形态。
     */
    static FlowDefinition wave2Flow() {
        FlowDefinition definition = new FlowDefinition();
        definition.setStart("n1");
        definition.setNodes(new ArrayList<>(List.of(
                approvalWithLimit("n1", "部门负责人审批", "ANY_SIGN", 24, "cc1", roleRule("admin")),
                cc("cc1", "抄送管理员", "c1", roleRule("admin")),
                condition("c1", "金额判断",
                        branch("b1", "金额大于5000", "n2", cond("AND", rule("amount", "GT", "5000"))),
                        elseBranch("b2", "其它情况", "n3")),
                approvalWithLimit("n2", "直属领导审批", "ANY_SIGN", 48, "n3", leaderRule()),
                approval("n3", "上一节点指定的审批人", "ANY_SIGN", "end", prevAssignRule(2)),
                end("end", "结束")
        )));
        return definition;
    }
}
