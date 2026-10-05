package com.enterprise.ticket.common.flow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「业务域禁用规则集」的单一事实源契约测试（Wave 4 · W4-D / C8）。
 *
 * <h2>它锁定的不是某一条规则，而是「两条路径是否读同一份数据」</h2>
 * <p>W4-D 之前，禁用集以硬编码形式存在**两处**：后端 {@code FlowNodeValidator} 里内联的
 * {@code if (FORM_USER_FIELD || APPLICANT_CHOOSE)}，以及前端设计器里的
 * {@code BORROW_FORBIDDEN_RULE_TYPES}。两处漂移的表现是"设计器里配得好好的，
 * 点发布被后端拒掉"——这类缺陷不会报错，只会在生产上被管理员撞见。
 *
 * <p>改造后禁用集只有一处声明（{@link FlowScope#forbiddenRuleTypes()}），
 * 校验器与设计器元数据接口都读它。本测试就钉这条**派生关系**：
 * 对每个业务域 × 每一种审批人来源，遍历断言
 * <b>「校验器是否报『不支持』」严格等价于「该来源是否在枚举的禁用集里」</b>。
 *
 * <h2>为什么必须遍历全矩阵，而不是挑两条例</h2>
 * <p>挑两条例只能证明"这两个 case 当前是对的"；遍历矩阵才能证明"没有第三种来源
 * 被悄悄放过或被悄悄误伤"。将来若给某个域新增一条禁用规则，只需改枚举，
 * 本测试自动覆盖新组合；反之若有人绕过枚举在 {@code FlowNodeValidator} 里补一个
 * {@code if}，非禁用来源那一半会立刻变红。
 */
class FlowScopeForbiddenRuleContractTest {

    @Test
    @DisplayName("每个域的实际拒绝集 == 枚举声明的禁用集（全矩阵遍历）")
    void validatorRejectsExactlyTheRulesDeclaredForbiddenByScope() {
        for (FlowScope scope : FlowScope.values()) {
            for (ApproverRuleType type : ApproverRuleType.values()) {
                boolean rejected = rejectedByValidator(scope, type);
                boolean declaredForbidden = scope.forbiddenRuleTypes().contains(type);

                String where = "域 " + scope.name() + " × 来源 " + type.name();
                if (declaredForbidden) {
                    assertTrue(rejected, where + " 在枚举里被声明为禁用，校验器却放过了 —— "
                            + "说明有人绕过了 FlowScope#forbiddenRuleTypes 另写了一份判定");
                } else {
                    assertFalse(rejected, where + " 未在枚举里声明禁用，校验器却报了「不支持」 —— "
                            + "说明枚举与校验器已不同源（这正是 C8 要根除的漂移）");
                }
            }
        }
    }

    @Test
    @DisplayName("CUSTOM 域无任何禁用来源（零回归：既有自定义流程不受影响）")
    void customScopeKeepsEveryRuleTypeAvailable() {
        assertTrue(FlowScope.CUSTOM.forbiddenRuleTypes().isEmpty(),
                "自定义表单域是 Phase 15 起的既有行为，必须保持「全部来源可用」");

        for (ApproverRuleType type : ApproverRuleType.values()) {
            assertFalse(rejectedByValidator(FlowScope.CUSTOM, type),
                    "CUSTOM 域不应拒绝任何来源，但 " + type.name() + " 被拒了");
        }
    }

    @Test
    @DisplayName("禁用集顺序稳定（= 枚举声明顺序），供接口下发与逐字断言")
    void forbiddenRuleTypesOrderIsStable() {
        // 借用域的禁用集是接口下发与提示文案的顺序来源，两处都依赖它稳定：
        // 数组顺序抖动会让前端下拉/提示顺序随机，也会让逐字断言变成脆弱的偶发失败。
        assertTrue(FlowScope.BORROW.forbiddenRuleTypes()
                        .equals(List.of(ApproverRuleType.FORM_USER_FIELD, ApproverRuleType.APPLICANT_CHOOSE)),
                "借用域禁用集顺序应为 [FORM_USER_FIELD, APPLICANT_CHOOSE]，实际："
                        + FlowScope.BORROW.forbiddenRuleTypes());
    }

    // ------------------------------------------------------------------
    // 判定辅助
    // ------------------------------------------------------------------

    /** 用一条只含该来源的审批节点跑一遍发布校验，看是否出现「不支持」 */
    private static boolean rejectedByValidator(FlowScope scope, ApproverRuleType type) {
        FlowDefinition definition = flowWithSingleRule(ruleOf(type));
        String marker = "不支持「" + type.getLabel() + "」";
        return FlowDefinitionValidator.collectProblems(definition, null, scope).stream()
                .anyMatch(problem -> problem.contains(marker));
    }

    /** 结构合法的最小流程：n1（审批，带被测规则）→ end。结构合法才能走到节点级校验 */
    private static FlowDefinition flowWithSingleRule(ApproverRule rule) {
        FlowDefinition definition = new FlowDefinition();
        definition.setStart("n1");
        definition.setNodes(new ArrayList<>(List.of(
                FlowTestFixtures.approval("n1", "审批", "ANY_SIGN", "end", rule),
                FlowTestFixtures.end("end", "结束"))));
        return definition;
    }

    /**
     * 每种来源各取一个**参数完备**的样本规则。
     *
     * <p>取完备样本（而不是残缺样本）是刻意的：残缺样本会额外触发参数校验问题，
     * 虽然不影响"是否出现「不支持」"的判定，但会让失败信息里混进无关噪音，
     * 排查时容易误判成禁用集的问题。参数完备保证了除禁用集之外的输入恒定。
     */
    private static ApproverRule ruleOf(ApproverRuleType type) {
        return switch (type) {
            case SPECIFIC_USER -> FlowTestFixtures.specificUserRule(1L);
            case ROLE -> FlowTestFixtures.roleRule("admin");
            case LEADER -> FlowTestFixtures.leaderRule();
            case BIZ_GROUP_APPROVERS -> FlowTestFixtures.bizGroupApproverRule();
            case PARENT_DEPT_APPROVERS -> FlowTestFixtures.parentDeptApproverRule();
            case HANDLER_GROUP -> FlowTestFixtures.handlerGroupRule(1L);
            case FORM_USER_FIELD -> FlowTestFixtures.formUserField("receiver");
            case PREV_ASSIGN -> FlowTestFixtures.prevAssignRule(1);
            case APPLICANT_CHOOSE -> FlowTestFixtures.applicantChoose("ALL", null, 1, 1);
        };
    }
}
