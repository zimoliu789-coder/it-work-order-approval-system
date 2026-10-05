package com.enterprise.ticket.common.flow;

import com.enterprise.ticket.common.constant.FormFieldType;
import com.enterprise.ticket.common.constant.RoleCode;
import com.enterprise.ticket.common.constant.UseType;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.form.FormField;
import com.enterprise.ticket.common.form.FormSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 16 Wave 1 / M1 · 借用单接入自定义流程的专项单测。
 *
 * <p>覆盖四块**只在借用域里才成立**的规则，它们无法复用 CUSTOM 域的既有测试：
 * <ol>
 *   <li>{@link BorrowFieldCatalog}：字段清单的形态 + 值域转换（尤其「长期领用没有天数」这一坑）；</li>
 *   <li>{@link FlowScope#BORROW} 下的禁用规则（{@code FORM_USER_FIELD} / {@code APPLICANT_CHOOSE}）；</li>
 *   <li>借用域的路径求值：用 {@code borrow.*} 条件跑通分支，且引擎零改动；</li>
 *   <li>{@link BorrowFlowCatalog}：Phase 19 批次 D 的预置三级/四级流程（含金额分档）。</li>
 * </ol>
 *
 * <p><b>为什么单独建一个测试类而不是塞进 FlowDefinitionValidatorTest</b>：
 * 那个类的基准流程是 CUSTOM 域的（{@code amount} / {@code receiver} 字段），
 * 借用域用的是 {@code borrow.*} 命名空间。混在一起后，读测试的人无法一眼看出
 * 「这条断言属于哪个域」—— 而域的边界正是 M1 最容易出错的地方。
 */
class BorrowFlowTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 22);

    // ==================================================================
    // 1. BorrowFieldCatalog
    // ==================================================================

    @Nested
    @DisplayName("借用内置字段清单")
    class Catalog {

        @Test
        @DisplayName("字段 key 全部以 borrow. 前缀命名，且数量与文档一致")
        void keysAreNamespaced() {
            FormSchema schema = BorrowFieldCatalog.schema();
            assertThat(schema.getFields()).hasSize(5);
            assertThat(schema.getFields()).extracting(FormField::getKey).allSatisfy(key ->
                    assertThat(key).startsWith("borrow."));
            assertThat(schema.getFields()).extracting(FormField::getKey).containsExactly(
                    BorrowFieldCatalog.USE_TYPE,
                    BorrowFieldCatalog.EXPECTED_DAYS,
                    BorrowFieldCatalog.DEVICE_CATEGORY_ID,
                    BorrowFieldCatalog.REASON,
                    BorrowFieldCatalog.DEVICE_AMOUNT);
        }

        @Test
        @DisplayName("借用类型是 SELECT 且选项来自 UseType 枚举（不另抄一份值域）")
        void useTypeOptionsComeFromEnum() {
            FormField useType = fieldOf(BorrowFieldCatalog.USE_TYPE);
            assertThat(useType.getType()).isEqualTo(FormFieldType.SELECT.name());
            assertThat(useType.getOptions()).hasSize(UseType.values().length);
            assertThat(useType.getOptions()).extracting(option -> option.getValue())
                    .containsExactly(UseType.SHORT_TERM.name(), UseType.LONG_TERM.name());
        }

        @Test
        @DisplayName("数值条件只对 NUMBER 字段可用：天数/分类/金额是 NUMBER，用途是 TEXTAREA")
        void numericFieldsAreNumbers() {
            assertThat(fieldOf(BorrowFieldCatalog.EXPECTED_DAYS).getType())
                    .isEqualTo(FormFieldType.NUMBER.name());
            assertThat(fieldOf(BorrowFieldCatalog.DEVICE_CATEGORY_ID).getType())
                    .isEqualTo(FormFieldType.NUMBER.name());
            assertThat(fieldOf(BorrowFieldCatalog.DEVICE_AMOUNT).getType())
                    .isEqualTo(FormFieldType.NUMBER.name());
            assertThat(fieldOf(BorrowFieldCatalog.REASON).getType())
                    .isEqualTo(FormFieldType.TEXTAREA.name());
        }

        @Test
        @DisplayName("formData 的 key 与 schema 的 key 逐字一致（否则条件会静默永不命中）")
        void formDataKeysMatchSchema() {
            Map<String, Object> data = BorrowFieldCatalog.formData(
                    UseType.SHORT_TERM.name(), 7, 3L, "驻场开发", new BigDecimal("6999.50"));
            assertThat(data).containsOnlyKeys(
                    BorrowFieldCatalog.USE_TYPE,
                    BorrowFieldCatalog.EXPECTED_DAYS,
                    BorrowFieldCatalog.DEVICE_CATEGORY_ID,
                    BorrowFieldCatalog.REASON,
                    BorrowFieldCatalog.DEVICE_AMOUNT);
            // 反向核验：schema 里每个 key 都能被 formData 产出的 map 覆盖到
            // （这条断言正是"新增字段忘了接进 formData"的拦截点 —— 那样的条件永远不命中且不报错）
            for (FormField field : BorrowFieldCatalog.schema().getFields()) {
                assertThat(data).containsKey(field.getKey());
            }
        }

        @Test
        @DisplayName("空值 / 空白串不放入 map —— 求值器据此把「无值」判为条件不成立")
        void nullAndBlankAreOmitted() {
            Map<String, Object> data = BorrowFieldCatalog.formData(
                    UseType.LONG_TERM.name(), null, null, "  ", null);
            assertThat(data).containsOnlyKeys(BorrowFieldCatalog.USE_TYPE);
            assertThat(data).doesNotContainKey(BorrowFieldCatalog.EXPECTED_DAYS);
            assertThat(data).doesNotContainKey(BorrowFieldCatalog.REASON);
            assertThat(data).doesNotContainKey(BorrowFieldCatalog.DEVICE_CATEGORY_ID);
            // 未录入金额 → 同样不入 map（需求口径：未录入按「不超过阈值」处理）
            assertThat(data).doesNotContainKey(BorrowFieldCatalog.DEVICE_AMOUNT);
        }

        @Test
        @DisplayName("设备金额 0 元是可比较的值，不能被当成「未录入」丢掉")
        void zeroAmountIsKept() {
            Map<String, Object> data = BorrowFieldCatalog.formData(
                    UseType.SHORT_TERM.name(), 1, null, null, BigDecimal.ZERO);
            // 0 与 null 语义不同：0 表示"确认这台设备金额为 0"，
            // 因此必须入 map —— 否则「金额 > 0」这类条件会把 0 元设备误判为未录入
            assertThat(data).containsEntry(BorrowFieldCatalog.DEVICE_AMOUNT, BigDecimal.ZERO);
        }

        @Test
        @DisplayName("长期领用不得把天数写成 0（否则 expectedDays > 0 会误命中）")
        void longTermHasNoDays() {
            // 长期领用没有归还日期 → expectedDays 为 null
            assertThat(BorrowFieldCatalog.expectedDays(TODAY, null)).isNull();
            Map<String, Object> data = BorrowFieldCatalog.formData(
                    UseType.LONG_TERM.name(), BorrowFieldCatalog.expectedDays(TODAY, null), null, null, null);
            // 关键：是「没有这个 key」，而不是「值为 0」
            assertThat(data).doesNotContainKey(BorrowFieldCatalog.EXPECTED_DAYS);
        }

        @Test
        @DisplayName("天数换算：正常日期取差值，早于今天取 0，任一端为空取 null")
        void expectedDaysConversion() {
            assertThat(BorrowFieldCatalog.expectedDays(TODAY, TODAY)).isZero();
            assertThat(BorrowFieldCatalog.expectedDays(TODAY, TODAY.plusDays(30))).isEqualTo(30);
            // 脏数据兜底：早于今天的日期取 0 而不是负数
            assertThat(BorrowFieldCatalog.expectedDays(TODAY, TODAY.minusDays(5))).isZero();
            assertThat(BorrowFieldCatalog.expectedDays(null, TODAY)).isNull();
            assertThat(BorrowFieldCatalog.expectedDays(TODAY, null)).isNull();
        }
    }

    // ==================================================================
    // 2. BORROW 域的禁用规则
    // ==================================================================

    @Nested
    @DisplayName("BORROW 域禁用规则（发布期硬约束）")
    class ForbiddenRules {

        @Test
        @DisplayName("FORM_USER_FIELD 在借用域被拒（借用单没有表单人员字段）")
        void formUserFieldIsRejected() {
            FlowDefinition definition = borrowFlow(FlowTestFixtures.formUserField("receiver"));
            List<String> problems = FlowDefinitionValidator.collectProblems(
                    definition, null, FlowScope.BORROW);
            assertThat(problems).anySatisfy(problem -> {
                assertThat(problem).contains("表单人员字段");
                assertThat(problem).contains("借用");
            });
        }

        @Test
        @DisplayName("APPLICANT_CHOOSE 在借用域被拒（借用提交页没有选人器）")
        void applicantChooseIsRejected() {
            FlowDefinition definition = borrowFlow(FlowTestFixtures.applicantChoose("ALL", null, 1, 2));
            List<String> problems = FlowDefinitionValidator.collectProblems(
                    definition, null, FlowScope.BORROW);
            assertThat(problems).anySatisfy(problem -> {
                assertThat(problem).contains("申请人自选");
                assertThat(problem).contains("借用");
            });
        }

        @Test
        @DisplayName("两种禁用规则同时出现时各报一条（收集模式不短路）")
        void bothForbiddenRulesReported() {
            // 同一节点放两条规则：一条 FORM_USER_FIELD、一条 APPLICANT_CHOOSE
            FlowNode node = FlowTestFixtures.approval("n1", "审批", "ANY_SIGN", "end",
                    FlowTestFixtures.formUserField("receiver"),
                    FlowTestFixtures.applicantChoose("ALL", null, 1, 1));
            FlowDefinition definition = new FlowDefinition();
            definition.setStart("n1");
            definition.setNodes(new ArrayList<>(List.of(node, FlowTestFixtures.end("end", "结束"))));

            List<String> problems = FlowDefinitionValidator.collectProblems(
                    definition, null, FlowScope.BORROW);
            // 注意：两条消息的结尾都含「表单人员字段」（共用后缀「没有表单人员字段/选人器」），
            // 因此必须匹配**被引号括起的规则名**「表单人员字段」，否则会把申请人自选那条也算进来。
            assertThat(problems).filteredOn(problem -> problem.contains("不支持「表单人员字段」")).hasSize(1);
            assertThat(problems).filteredOn(problem -> problem.contains("不支持「申请人自选」")).hasSize(1);
        }

        @Test
        @DisplayName("同一规则在 CUSTOM 域仍然合法（禁用只作用于借用域，不误伤既有流程）")
        void customScopeStillAllowsBoth() {
            FlowDefinition definition = borrowFlow(FlowTestFixtures.formUserField("receiver"));
            // CUSTOM 域：不报「不支持」这类问题（字段是否存在由 schema 决定，此处不传 schema）
            List<String> problems = FlowDefinitionValidator.collectProblems(
                    definition, null, FlowScope.CUSTOM);
            assertThat(problems).noneSatisfy(problem -> assertThat(problem).contains("不支持"));
        }

        @Test
        @DisplayName("scope=null 视为 CUSTOM（既有调用点 validate(def, schema) 行为不变）")
        void nullScopeDefaultsToCustom() {
            FlowDefinition definition = borrowFlow(FlowTestFixtures.formUserField("receiver"));
            List<String> withNull = FlowDefinitionValidator.collectProblems(definition, null, null);
            List<String> withCustom = FlowDefinitionValidator.collectProblems(
                    definition, null, FlowScope.CUSTOM);
            assertThat(withNull).isEqualTo(withCustom);
        }

        @Test
        @DisplayName("借用域仍允许 LEADER / PREV_ASSIGN / SPECIFIC_USER / 上级部门主管")
        void allowedRulesStillPass() {
            FlowDefinition definition = new FlowDefinition();
            definition.setStart("n1");
            definition.setNodes(new ArrayList<>(List.of(
                    FlowTestFixtures.approval("n1", "主管审批", "ANY_SIGN", "n2",
                            FlowTestFixtures.leaderRule()),
                    FlowTestFixtures.approval("n2", "指定人审批", "ANY_SIGN", "n2b",
                            FlowTestFixtures.prevAssignRule(2)),
                    FlowTestFixtures.approval("n2b", "上级部门主管审批", "ANY_SIGN", "n3",
                            FlowTestFixtures.parentDeptApproverRule()),
                    FlowTestFixtures.approval("n3", "兜底审批", "ANY_SIGN", "end",
                            FlowTestFixtures.specificUserRule(7L)),
                    FlowTestFixtures.end("end", "结束")
            )));
            List<String> problems = FlowDefinitionValidator.collectProblems(
                    definition, null, FlowScope.BORROW);
            assertThat(problems).noneSatisfy(problem -> assertThat(problem).contains("不支持"));
        }

        @Test
        @DisplayName("BORROW 域发布校验走 BORROW 字段域：borrow.* 字段存在性检查通过")
        void borrowFieldsAreKnownToValidator() {
            // 条件引用 borrow.expectedDays（NUMBER）做数值比较 → 应合法
            FlowDefinition definition = borrowConditionFlow(
                    FlowTestFixtures.rule(BorrowFieldCatalog.EXPECTED_DAYS, "GT", "30"));
            List<String> problems = FlowDefinitionValidator.collectProblems(
                    definition, null, FlowScope.BORROW);
            assertThat(problems).isEmpty();
        }

        @Test
        @DisplayName("borrow.useType（SELECT）不允许数值比较 —— 复用既有类型可比性规则")
        void borrowUseTypeRejectsNumericOperator() {
            FlowDefinition definition = borrowConditionFlow(
                    FlowTestFixtures.rule(BorrowFieldCatalog.USE_TYPE, "GT", "1"));
            List<String> problems = FlowDefinitionValidator.collectProblems(
                    definition, null, FlowScope.BORROW);
            assertThat(problems).anySatisfy(problem -> {
                assertThat(problem).contains("只能用于数字或日期字段");
                assertThat(problem).contains("借用类型");
            });
        }

        @Test
        @DisplayName("引用不存在字段（动态表单字段名）在借用域被拒")
        void unknownFieldRejected() {
            FlowDefinition definition = borrowConditionFlow(FlowTestFixtures.rule("amount", "GT", "5000"));
            List<String> problems = FlowDefinitionValidator.collectProblems(
                    definition, null, FlowScope.BORROW);
            assertThat(problems).anySatisfy(problem -> assertThat(problem).contains("amount"));
        }

        @Test
        @DisplayName("CUSTOM 域不受借用字段影响：borrow.* 在自定义表单里不存在")
        void borrowFieldsNotVisibleToCustomScope() {
            FormSchema customSchema = FlowTestFixtures.schema(
                    FlowTestFixtures.field("amount", "金额", FormFieldType.NUMBER.name()));
            FlowDefinition definition = borrowConditionFlow(
                    FlowTestFixtures.rule(BorrowFieldCatalog.EXPECTED_DAYS, "GT", "30"));
            List<String> problems = FlowDefinitionValidator.collectProblems(
                    definition, customSchema, FlowScope.CUSTOM);
            assertThat(problems).anySatisfy(problem ->
                    assertThat(problem).contains(BorrowFieldCatalog.EXPECTED_DAYS));
        }

        @Test
        @DisplayName("validate(def) 抛出的仍是第一条问题（发布路径逐字节不变）")
        void validateStillThrowsFirstProblem() {
            FlowDefinition definition = borrowFlow(FlowTestFixtures.formUserField("receiver"));
            assertThatThrownBy(() -> FlowDefinitionValidator.validate(
                    definition, null, FlowScope.BORROW))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getMessage())
                            .contains("表单人员字段"));
        }
    }

    // ==================================================================
    // 3. 借用域路径求值（引擎零改动）
    // ==================================================================

    @Nested
    @DisplayName("借用域条件路径求值")
    class PathResolution {

        @Test
        @DisplayName("条件命中：预计借用 40 天 → 走部门经理节点")
        void dayConditionHits() {
            FlowDefinition definition = borrowConditionFlow(
                    FlowTestFixtures.rule(BorrowFieldCatalog.EXPECTED_DAYS, "GTE", "30"));
            Map<String, Object> data = BorrowFieldCatalog.formData(
                    UseType.SHORT_TERM.name(), 40, null, null, null);

            FlowPathResolver.Result path = FlowPathResolver.resolve(
                    definition, BorrowFieldCatalog.schema(), data);

            assertThat(path.onPathNodes()).extracting(FlowPathResolver.ResolvedNode::nodeKey)
                    .containsExactly("n1", "n2");
            assertThat(path.skippedNodes()).extracting(FlowPathResolver.ResolvedNode::nodeKey)
                    .containsExactly("n3");
        }

        @Test
        @DisplayName("条件未命中：预计借用 10 天 → 走 else 的归档节点")
        void dayConditionMisses() {
            FlowDefinition definition = borrowConditionFlow(
                    FlowTestFixtures.rule(BorrowFieldCatalog.EXPECTED_DAYS, "GTE", "30"));
            Map<String, Object> data = BorrowFieldCatalog.formData(
                    UseType.SHORT_TERM.name(), 10, null, null, null);

            FlowPathResolver.Result path = FlowPathResolver.resolve(
                    definition, BorrowFieldCatalog.schema(), data);

            assertThat(path.onPathNodes()).extracting(FlowPathResolver.ResolvedNode::nodeKey)
                    .containsExactly("n1", "n3");
            assertThat(path.skippedNodes()).extracting(FlowPathResolver.ResolvedNode::nodeKey)
                    .containsExactly("n2");
        }

        @Test
        @DisplayName("长期领用（无天数）不会误命中 expectedDays > 0 的条件")
        void longTermDoesNotMatchDayCondition() {
            FlowDefinition definition = borrowConditionFlow(
                    FlowTestFixtures.rule(BorrowFieldCatalog.EXPECTED_DAYS, "GT", "0"));
            // 长期领用：expectedDays 为 null → 不入 map → 数值比较不成立
            Map<String, Object> data = BorrowFieldCatalog.formData(
                    UseType.LONG_TERM.name(), BorrowFieldCatalog.expectedDays(TODAY, null), null, null, null);

            FlowPathResolver.Result path = FlowPathResolver.resolve(
                    definition, BorrowFieldCatalog.schema(), data);

            // 若把 null 写成 0，这里会错误地走 n2（命中分支）
            assertThat(path.onPathNodes()).extracting(FlowPathResolver.ResolvedNode::nodeKey)
                    .containsExactly("n1", "n3");
        }

        @Test
        @DisplayName("借用类型条件：长期领用走专用分支，命中说明里带中文字段名")
        void useTypeCondition() {
            FlowDefinition definition = borrowConditionFlow(
                    FlowTestFixtures.rule(BorrowFieldCatalog.USE_TYPE, "EQ", UseType.LONG_TERM.name()));
            Map<String, Object> data = BorrowFieldCatalog.formData(
                    UseType.LONG_TERM.name(), null, null, null, null);

            FlowPathResolver.Result path = FlowPathResolver.resolve(
                    definition, BorrowFieldCatalog.schema(), data);

            assertThat(path.onPathNodes()).extracting(FlowPathResolver.ResolvedNode::nodeKey)
                    .containsExactly("n1", "n2");
            // 条件说明用 schema 里的 label（界面直接展示，无需前端再做映射）
            assertThat(path.onPathNodes()).extracting(FlowPathResolver.ResolvedNode::conditionDesc)
                    .anySatisfy(desc -> assertThat(desc).contains("借用类型"));
        }

        @Test
        @DisplayName("设备分类条件：命中说明带设备主分类中文名")
        void deviceCategoryCondition() {
            FlowDefinition definition = borrowConditionFlow(
                    FlowTestFixtures.rule(BorrowFieldCatalog.DEVICE_CATEGORY_ID, "EQ", 3));
            Map<String, Object> data = BorrowFieldCatalog.formData(
                    UseType.SHORT_TERM.name(), 5, 3L, null, null);

            FlowPathResolver.Result path = FlowPathResolver.resolve(
                    definition, BorrowFieldCatalog.schema(), data);

            assertThat(path.onPathNodes()).extracting(FlowPathResolver.ResolvedNode::nodeKey)
                    .containsExactly("n1", "n2");
        }

        @Test
        @DisplayName("抄送节点在借用域正常参与路径（含 CC 的流程不需要改引擎）")
        void ccNodeWorksInBorrowFlow() {
            FlowDefinition definition = new FlowDefinition();
            definition.setStart("n1");
            definition.setNodes(new ArrayList<>(List.of(
                    FlowTestFixtures.approval("n1", "主管审批", "ANY_SIGN", "cc1",
                            FlowTestFixtures.specificUserRule(1L)),
                    FlowTestFixtures.cc("cc1", "抄送管理员", "end",
                            FlowTestFixtures.roleRule("admin")),
                    FlowTestFixtures.end("end", "结束")
            )));
            Map<String, Object> data = BorrowFieldCatalog.formData(
                    UseType.SHORT_TERM.name(), 3, null, null, null);

            FlowPathResolver.Result path = FlowPathResolver.resolve(
                    definition, BorrowFieldCatalog.schema(), data);

            assertThat(path.onPathNodes()).extracting(FlowPathResolver.ResolvedNode::nodeKey)
                    .containsExactly("n1", "cc1");
            assertThat(path.onPathNodes()).filteredOn(FlowPathResolver.ResolvedNode::isCc).hasSize(1);
            // 抄送节点是终态：不参与「当前步骤」判定，因此其后的 END 不在路径上
            assertThat(path.onPathNodes()).noneMatch(node -> "end".equals(node.nodeKey()));
        }

        @Test
        @DisplayName("借用域流程通过 BORROW 校验（结构 + 条件字段 + 禁用规则）")
        void borrowFlowPassesFullValidation() {
            FlowDefinition definition = borrowConditionFlow(
                    FlowTestFixtures.rule(BorrowFieldCatalog.EXPECTED_DAYS, "GTE", "30"));
            List<String> problems = FlowDefinitionValidator.collectProblems(
                    definition, null, FlowScope.BORROW);
            assertThat(problems).isEmpty();
        }
    }

    // ==================================================================
    // 4. Phase 19 批次 D：预置借用流程
    // ==================================================================

    /**
     * 预置流程的三条硬性质：**结构正确**、**金额分档按阈值走**、**执行人由上一节点指定**。
     *
     * <p>这个预置定义是**代码生成**的（不像模板那样在发布时经过校验器），
     * 因此这里必须自己把它送进 {@link FlowDefinitionValidator} 跑一遍 ——
     * 否则"代码写出来的流程不合法"这件事要到提交工单时才会暴露。
     */
    @Nested
    @DisplayName("预置借用流程（批次 D）")
    class PresetFlow {

        @Test
        @DisplayName("预置流程通过 BORROW 域的全量发布校验（代码生成的也必须合法）")
        void presetPassesValidation() {
            List<String> problems = FlowDefinitionValidator.collectProblems(
                    BorrowFlowCatalog.preset(), BorrowFieldCatalog.schema(), FlowScope.BORROW);
            assertThat(problems).isEmpty();
        }

        @Test
        @DisplayName("结构固定：直属主管 → 金额分档 → 上级部门主管 → IT主管 → IT执行人 → 结束")
        void presetStructure() {
            FlowDefinition definition = BorrowFlowCatalog.preset();

            assertThat(definition.getStart()).isEqualTo(BorrowFlowCatalog.NODE_DIRECT_MANAGER);
            assertThat(definition.getNodes()).extracting(FlowNode::getKey).containsExactly(
                    BorrowFlowCatalog.NODE_DIRECT_MANAGER,
                    BorrowFlowCatalog.NODE_AMOUNT_SPLIT,
                    BorrowFlowCatalog.NODE_PARENT_DEPT_MANAGER,
                    BorrowFlowCatalog.NODE_IT_MANAGER,
                    BorrowFlowCatalog.NODE_IT_EXECUTOR,
                    BorrowFlowCatalog.NODE_END);

            assertThat(definition.node(BorrowFlowCatalog.NODE_DIRECT_MANAGER).getApproverRules())
                    .extracting(ApproverRule::getType)
                    .containsExactly(ApproverRuleType.BIZ_GROUP_APPROVERS.name());
            assertThat(definition.node(BorrowFlowCatalog.NODE_IT_MANAGER).getApproverRules())
                    .extracting(ApproverRule::getRoleCode)
                    .containsExactly(RoleCode.IT_MANAGER);
            assertThat(definition.node(BorrowFlowCatalog.NODE_PARENT_DEPT_MANAGER).getApproverRules())
                    .extracting(ApproverRule::getType)
                    .containsExactly(ApproverRuleType.PARENT_DEPT_APPROVERS.name());
        }

        @Test
        @DisplayName("每级审批节点都限时 24 小时（需求：超时自动提醒）")
        void everyLevelHasTimeLimit() {
            FlowDefinition definition = BorrowFlowCatalog.preset();
            for (String key : List.of(BorrowFlowCatalog.NODE_DIRECT_MANAGER,
                    BorrowFlowCatalog.NODE_PARENT_DEPT_MANAGER,
                    BorrowFlowCatalog.NODE_IT_MANAGER,
                    BorrowFlowCatalog.NODE_IT_EXECUTOR)) {
                assertThat(definition.node(key).getTimeLimitHours())
                        .as("节点 %s 的审批时限", key)
                        .isEqualTo(BorrowFlowCatalog.TIME_LIMIT_HOURS);
            }
        }

        @Test
        @DisplayName("第 3 级是「上一节点指定」，且指派范围限定为 IT执行人")
        void executorLevelIsPrevAssignWithScope() {
            FlowNode executor = BorrowFlowCatalog.preset().node(BorrowFlowCatalog.NODE_IT_EXECUTOR);
            assertThat(executor.getSignType()).isEqualTo("ANY_SIGN");
            assertThat(executor.getApproverRules()).hasSize(1);
            ApproverRule rule = executor.getApproverRules().get(0);
            assertThat(rule.getType()).isEqualTo(ApproverRuleType.PREV_ASSIGN.name());
            assertThat(rule.getAssignCount()).isEqualTo(BorrowFlowCatalog.EXECUTOR_ASSIGN_COUNT);
            assertThat(rule.getAssignScope()).isEqualTo(ApproverRuleType.AssignScope.IT_EXECUTOR.name());
        }

        @Test
        @DisplayName("金额 ≤ 阈值走三级：直属主管 → IT主管 → IT执行人")
        void withinThresholdTakesThreeLevels() {
            FlowDefinition definition = BorrowFlowCatalog.preset(new BigDecimal("5000"));
            Map<String, Object> data = BorrowFieldCatalog.formData(
                    UseType.SHORT_TERM.name(), 3, null, "出差", new BigDecimal("5000"));

            FlowPathResolver.Result path = FlowPathResolver.resolve(
                    definition, BorrowFieldCatalog.schema(), data);

            assertThat(path.onPathNodes()).extracting(FlowPathResolver.ResolvedNode::nodeKey)
                    .containsExactly(BorrowFlowCatalog.NODE_DIRECT_MANAGER,
                            BorrowFlowCatalog.NODE_IT_MANAGER,
                            BorrowFlowCatalog.NODE_IT_EXECUTOR);
            assertThat(path.skippedNodes()).extracting(FlowPathResolver.ResolvedNode::nodeKey)
                    .containsExactly(BorrowFlowCatalog.NODE_PARENT_DEPT_MANAGER);
        }

        @Test
        @DisplayName("金额 > 阈值走四级：中间插入上级部门主管")
        void overThresholdTakesFourLevels() {
            FlowDefinition definition = BorrowFlowCatalog.preset(new BigDecimal("5000"));
            Map<String, Object> data = BorrowFieldCatalog.formData(
                    UseType.SHORT_TERM.name(), 3, null, "出差", new BigDecimal("5000.01"));

            FlowPathResolver.Result path = FlowPathResolver.resolve(
                    definition, BorrowFieldCatalog.schema(), data);

            assertThat(path.onPathNodes()).extracting(FlowPathResolver.ResolvedNode::nodeKey)
                    .containsExactly(BorrowFlowCatalog.NODE_DIRECT_MANAGER,
                            BorrowFlowCatalog.NODE_PARENT_DEPT_MANAGER,
                            BorrowFlowCatalog.NODE_IT_MANAGER,
                            BorrowFlowCatalog.NODE_IT_EXECUTOR);
        }

        @Test
        @DisplayName("金额未录入按「不超过阈值」处理，走三级（需求明确口径）")
        void missingAmountTakesThreeLevels() {
            FlowDefinition definition = BorrowFlowCatalog.preset(new BigDecimal("5000"));
            // deviceAmount 传 null → formData 不入该 key → 数值比较不成立
            Map<String, Object> data = BorrowFieldCatalog.formData(
                    UseType.SHORT_TERM.name(), 3, null, "出差", null);

            FlowPathResolver.Result path = FlowPathResolver.resolve(
                    definition, BorrowFieldCatalog.schema(), data);

            assertThat(path.onPathNodes()).extracting(FlowPathResolver.ResolvedNode::nodeKey)
                    .containsExactly(BorrowFlowCatalog.NODE_DIRECT_MANAGER,
                            BorrowFlowCatalog.NODE_IT_MANAGER,
                            BorrowFlowCatalog.NODE_IT_EXECUTOR);
        }

        @Test
        @DisplayName("阈值是唯一输入：改成 1000 后同一台 5000 元设备改走四级")
        void thresholdIsTheOnlyKnob() {
            Map<String, Object> data = BorrowFieldCatalog.formData(
                    UseType.SHORT_TERM.name(), 3, null, null, new BigDecimal("5000"));

            assertThat(onPathKeys(BorrowFlowCatalog.preset(new BigDecimal("5000")), data))
                    .doesNotContain(BorrowFlowCatalog.NODE_PARENT_DEPT_MANAGER);
            assertThat(onPathKeys(BorrowFlowCatalog.preset(new BigDecimal("1000")), data))
                    .contains(BorrowFlowCatalog.NODE_PARENT_DEPT_MANAGER);
        }

        @Test
        @DisplayName("阈值非法（null / 负数）回落出厂值 5000，而不是造出恒不命中的条件")
        void invalidThresholdFallsBackToDefault() {
            Map<String, Object> data = BorrowFieldCatalog.formData(
                    UseType.SHORT_TERM.name(), 3, null, null, new BigDecimal("5000"));
            // 5000 元设备 + 默认阈值 5000 → 不命中大额分支
            assertThat(onPathKeys(BorrowFlowCatalog.preset(null), data))
                    .doesNotContain(BorrowFlowCatalog.NODE_PARENT_DEPT_MANAGER);
            assertThat(onPathKeys(BorrowFlowCatalog.preset(new BigDecimal("-1")), data))
                    .doesNotContain(BorrowFlowCatalog.NODE_PARENT_DEPT_MANAGER);
        }

        @Test
        @DisplayName("stepOrder 沿任意路径严格递增（拓扑距离口径）")
        void stepOrdersAreMonotonic() {
            List<FlowPathResolver.ResolvedNode> nodes = FlowPathResolver.resolve(
                    BorrowFlowCatalog.preset(new BigDecimal("5000")),
                    BorrowFieldCatalog.schema(),
                    BorrowFieldCatalog.formData(UseType.SHORT_TERM.name(), 3, null, null,
                            new BigDecimal("9999"))).onPathNodes();
            int previous = 0;
            for (FlowPathResolver.ResolvedNode node : nodes) {
                assertThat(node.stepOrder()).isGreaterThan(previous);
                previous = node.stepOrder();
            }
        }

        @Test
        @DisplayName("prevAssignNodeKeys 只圈出「IT执行人处理」这一个节点")
        void prevAssignNodeKeysOnlyExecutor() {
            assertThat(BorrowFlowCatalog.preset().prevAssignNodeKeys())
                    .containsExactly(BorrowFlowCatalog.NODE_IT_EXECUTOR);
            // 无 PREV_ASSIGN 的定义返回空集（不是 null），调用方无需判空
            FlowDefinition plain = borrowFlow(FlowTestFixtures.specificUserRule(1L));
            assertThat(plain.prevAssignNodeKeys()).isEmpty();
        }

        @Test
        @DisplayName("指派范围缺省即「不限制」—— 存量流程不带 assignScope 时行为不变")
        void assignScopeDefaultsToAll() {
            assertThat(ApproverRuleType.AssignScope.of(null))
                    .isEqualTo(ApproverRuleType.AssignScope.ALL);
            assertThat(ApproverRuleType.AssignScope.of("  "))
                    .isEqualTo(ApproverRuleType.AssignScope.ALL);
            assertThat(ApproverRuleType.AssignScope.of("IT_EXECUTOR").isItExecutor()).isTrue();
            assertThat(ApproverRuleType.AssignScope.ALL.isItExecutor()).isFalse();
        }

        private List<String> onPathKeys(FlowDefinition definition, Map<String, Object> data) {
            return FlowPathResolver.resolve(definition, BorrowFieldCatalog.schema(), data)
                    .onPathNodes().stream()
                    .map(FlowPathResolver.ResolvedNode::nodeKey)
                    .toList();
        }
    }

    // ------------------------------------------------------------------
    // 夹具：借用域流程
    // ------------------------------------------------------------------

    /**
     * 单审批节点流程：{@code n1 主管审批 → end}。
     *
     * <p>用于验证「规则是否被接受/拒绝」—— 结构刻意做到最简，
     * 这样断言就只反映规则本身，不会被无关的结构问题干扰。
     */
    private static FlowDefinition borrowFlow(ApproverRule... rules) {
        FlowDefinition definition = new FlowDefinition();
        definition.setStart("n1");
        definition.setNodes(new ArrayList<>(List.of(
                FlowTestFixtures.approval("n1", "主管审批", "ANY_SIGN", "end", rules),
                FlowTestFixtures.end("end", "结束")
        )));
        return definition;
    }

    /**
     * 带一个条件分支的借用流程：
     * {@code n1 主管审批 → c1（条件命中 → n2 部门经理，否则 → n3 归档）→ end}
     *
     * <p>两条出口汇合到 {@code end}，保证「无死路」校验通过。
     */
    private static FlowDefinition borrowConditionFlow(ConditionRule conditionRule) {
        FlowDefinition definition = new FlowDefinition();
        definition.setStart("n1");
        definition.setNodes(new ArrayList<>(List.of(
                FlowTestFixtures.approval("n1", "主管审批", "ANY_SIGN", "c1",
                        FlowTestFixtures.specificUserRule(1L)),
                FlowTestFixtures.condition("c1", "借用条件判断",
                        FlowTestFixtures.branch("b1", "满足条件", "n2",
                                FlowTestFixtures.cond("AND", conditionRule)),
                        FlowTestFixtures.elseBranch("b2", "其它情况", "n3")),
                FlowTestFixtures.approval("n2", "部门经理审批", "ANY_SIGN", "end",
                        FlowTestFixtures.specificUserRule(2L)),
                FlowTestFixtures.approval("n3", "归档确认", "ANY_SIGN", "end",
                        FlowTestFixtures.specificUserRule(3L)),
                FlowTestFixtures.end("end", "结束")
        )));
        return definition;
    }

    private static FormField fieldOf(String key) {
        return BorrowFieldCatalog.schema().getFields().stream()
                .filter(field -> key.equals(field.getKey()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("字段清单里不存在：" + key));
    }
}
