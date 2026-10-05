package com.enterprise.ticket.common.flow;

import java.util.List;

/**
 * 流程业务域（ / M1）。
 *
 * <h2>为什么需要这个概念</h2>
 * <p> 的流程校验器只有一种输入：动态表单的 schema。条件分支里能引用的字段，
 * 全部来自 {@code form_template_version.schema_json}；审批人规则里能引用的「表单人员字段」，
 * 也来自它。
 *
 * <p>但 M1 要让**借用单**也走流程，而借用单**没有动态表单** —— 它的条件字段是内置的
 * （借用类型、预计借用天数、设备分类、使用地点、借用原因，见 {@link BorrowFieldCatalog}）。
 * 于是校验器必须知道「我现在校验的是哪个业务域的流程」，才能决定两件事：
 * <ol>
 *   <li><b>条件字段从哪来</b>：动态表单 schema，还是内置的 {@code borrow.*} 字段域；</li>
 *   <li><b>哪些审批人规则不可用</b>：借用单没有表单人员字段可选、也没有选人器，
 *       因此 {@code FORM_USER_FIELD} 与 {@code APPLICANT_CHOOSE} 在借用域下必须禁用
 *       （配了也解析不出人，只会让提交时莫名失败或落到超管兜底）。</li>
 * </ol>
 *
 * <h2>为什么是「枚举 + 两处分流」而不是「两套校验器」</h2>
 * <p>节点结构、图约束（无环 / 可达 / 达 END / 恰一个 else）、条件求值、时限、抄送、
 * 上一节点指定 —— 这些规则在两种业务域下**完全相同**。若为此复制出第二套校验器，
 * 就等于把 M4a 刚刚消掉的「规则写两遍」问题重新引入一次。
 *
 * <p>因此这里只让 {@code FlowScope} 承担**最小的差异面**（字段域 + 禁用规则集），
 * 其余全部共用同一份实现 —— 新增业务域时只需再补一个 case，而不是再抄一遍校验逻辑。
 *
 * <p>{@link #CUSTOM} 是本概念引入**之前**的全部既有行为，因此不带 scope 的旧签名
 * 一律委托给它，从而保证  /  的发布路径与既有测试逐字节不变。
 *
 * <h2>禁用规则集是「单一事实源」（ · W4-D / C8）</h2>
 * <p>在 W4-D 之前，禁用规则集以 `if (ruleType == FORM_USER_FIELD || ruleType == APPLICANT_CHOOSE)`
 * 的形式**内联**在 {@code FlowNodeValidator} 里，而设计器（前端）另有一份硬编码副本。
 * 两处各自演化时会造出最难排查的组合：**设计器允许配置、后端发布时拒绝**。
 *
 * <p>现在禁用集由本枚举声明（{@link #forbiddenRuleTypes()}），并由
 * {@code GET /api/approval-flows/design-meta} 原样下发 —— 校验与下发读同一份数据，
 * 前端不再需要（也不允许）自己维护一份。前端仅保留一份"接口不可用时的兜底默认"，
 * 其与本枚举的一致性由共享金样例 {@code test-fixtures/golden/flow-design-meta.json} 钉死。
 */
public enum FlowScope {

    /**
     * 动态表单业务域（自定义申请类型的 FLOW 模式）。
     *
     * <p>条件字段来自 {@code form_template_version.schema_json}；审批人规则允许
     * {@code FORM_USER_FIELD} 与 {@code APPLICANT_CHOOSE}。这是  起的既有行为，
     * 引入 {@code FlowScope} 后**逐字节不变**。
     */
    CUSTOM("自定义表单", List.of()),

    /**
     * 借用单业务域（M1）。
     *
     * <p>条件字段来自内置的 {@link BorrowFieldCatalog}；因为借用提交页既没有表单人员字段、
     * 也没有申请人自选审批人的交互，以下两类审批人规则**一律禁用**：
     * <ul>
     *   <li>{@code FORM_USER_FIELD} —— 没有表单，字段无从引用；</li>
     *   <li>{@code APPLICANT_CHOOSE} —— 没有选人器，配了申请人也无法指定。</li>
     * </ul>
     * 二者都在**发布时**就拦下（而不是等到提交时才落超管兜底）——配置错误的代价不对称：
     * 发布时拦住只是让管理员改一下，放过则要等到有人提交时才发现。
     */
    BORROW("借用单", List.of(ApproverRuleType.FORM_USER_FIELD, ApproverRuleType.APPLICANT_CHOOSE));

    private final String label;

    /**
     * 本业务域下**不可用**的审批人规则（有序，见 {@link #forbiddenRuleTypes()}）。
     *
     * <p>构造参数用 {@code List} 而非 {@code Set}：需要一个**稳定顺序**来同时满足
     * 「接口下发的数组顺序确定」与「测试可逐字断言」。规模恒为 0~2，不需要哈希集合。
     */
    private final List<ApproverRuleType> forbiddenRuleTypes;

    FlowScope(String label, List<ApproverRuleType> forbiddenRuleTypes) {
        this.label = label;
        this.forbiddenRuleTypes = List.copyOf(forbiddenRuleTypes);
    }

    public String getLabel() {
        return label;
    }

    /**
     * 本业务域下不可用的审批人规则（顺序 = 枚举声明顺序，稳定）。
     *
     * <p>这是「哪些规则在哪个域不可用」的**唯一事实源**：发布校验
     * （{@code FlowNodeValidator#validateScopeForbiddenRules}）与设计器元数据接口
     * （{@code /api/approval-flows/design-meta}）都读它，因此不存在"界面允许、后端拒绝"的缝隙。
     */
    public List<ApproverRuleType> forbiddenRuleTypes() {
        return forbiddenRuleTypes;
    }

    public static FlowScope of(String value) {
        if (value == null) {
            return null;
        }
        for (FlowScope scope : values()) {
            if (scope.name().equals(value)) {
                return scope;
            }
        }
        return null;
    }
}
