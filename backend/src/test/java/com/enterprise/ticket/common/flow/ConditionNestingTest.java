package com.enterprise.ticket.common.flow;

import com.enterprise.ticket.common.form.FormSchema;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.enterprise.ticket.common.flow.FlowTestFixtures.approval;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.branch;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.cond;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.condition;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.copyOf;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.end;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.elseBranch;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.field;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.find;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.roleRule;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.rule;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.schema;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3-A 条件多层 AND/OR 嵌套专项测试（Phase 16 · Wave 3）。
 *
 * <h2>为什么值得一个独立测试类</h2>
 * <p>本批的改动不是"新增一个功能"，而是<b>把内核四处的遍历从单层改成递归</b>：
 * 求值、说明文案、运行期字段收集、运行期依赖判定。四处里**任何一处漏了递归都不是编译错误**，
 * 而是三种不同形态的静默失效：
 * <ul>
 *   <li>求值漏递归 → 嵌套分支永远不走，工单默默走 else；</li>
 *   <li>说明漏递归 → 详情页文案丢掉嵌套部分的括号，配置者核对不出自己配了什么；</li>
 *   <li>运行期依赖判定漏递归 → 提交时就被判成"可下结论"，本该 INACTIVE 的节点直接定了态。</li>
 * </ul>
 * 第三条最危险：不报错、不进日志，只让工单走错分支。因此本类把"四处都必须递归"
 * 各写成独立用例，而不是笼统地"测一下嵌套能用"。
 *
 * <h2>同时钉死的两件事</h2>
 * <ol>
 *   <li><b>向后兼容</b>：没有 {@code kind} 的存量 JSON 必须与显式 {@code kind=RULE} 求值逐项一致；</li>
 *   <li><b>深度上限</b>：{@code MAX_DEPTH} 同时是校验器的硬约束与前端禁用按钮的阈值，
 *       用常量断言防止有人单边调整，造出"设计器允许加、后端拒绝发布"的组合。</li>
 * </ol>
 */
class ConditionNestingTest {

    /**
     * 测试用表单 schema。
     *
     * <p>三个字段是<b>刻意凑齐</b>的：{@code threeLevelNested()} 里用到了 {@code remark}，
     * 若它不在 schema 里，「合法三层嵌套必须零问题」这条用例会因"引用的表单字段不存在"
     * 而变红 —— 那是夹具不自洽，不是实现有问题。schema 与夹具用同一套字段，
     * 才能让"校验通过"这件事只由被考察的规则决定。
     */
    private static final FormSchema SCHEMA = schema(
            field("amount", "金额", "NUMBER"),
            field("title", "标题", "TEXT"),
            field("remark", "备注", "TEXT"));

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /** 构造一个嵌套组信封（与设计器/演示数据走同一个工厂，避免测试自造第三种形状） */
    private static ConditionRule group(FlowCondition inner) {
        return ConditionRule.group(inner);
    }

    /**
     * 三层嵌套条件：AND（金额&gt;5000 且 OR（标题含「紧急」 或 AND（备注含「加急」）））。
     *
     * <p>三层的形状刻意选成 "AND 套 OR 套 AND"：若实现里某一层的 logic 被串了
     * （典型写法错误是在递归时传错 logic 或复用外层变量），全 AND 的形状恰好也能通过，
     * 而这个形状不会。
     */
    private static FlowCondition threeLevelNested() {
        return cond("AND",
                rule("amount", "GT", 5000),
                group(cond("OR",
                        rule("title", "CONTAINS", "紧急"),
                        group(cond("AND",
                                rule("remark", "CONTAINS", "加急"))))));
    }

    /** 把给定条件装进一个结构完整的流程，便于走校验器与求值器 */
    private static FlowDefinition flowWith(FlowCondition condition) {
        FlowDefinition definition = new FlowDefinition();
        definition.setStart("n1");
        definition.setNodes(new ArrayList<>(List.of(
                approval("n1", "主管审批", "ANY_SIGN", "c1", roleRule("admin")),
                condition("c1", "条件判断",
                        branch("b1", "命中", "n2", condition),
                        elseBranch("b2", "其它情况", "end")),
                approval("n2", "复核", "ANY_SIGN", "end", roleRule("admin")),
                end("end", "结束"))));
        return definition;
    }

    private static Map<String, Object> data(Object amount, Object title, Object remark) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("amount", amount);
        map.put("title", title);
        map.put("remark", remark);
        return map;
    }

    private static ConditionRule withKind(String kind) {
        ConditionRule candidate = new ConditionRule();
        candidate.setKind(kind);
        return candidate;
    }

    // ==================================================================
    // 1. 模型层
    // ==================================================================

    @Nested
    @DisplayName("① 模型：kind 归一、组信封、JSON 形态")
    class Model {

        @Test
        @DisplayName("kind 为 null / 空白 / 未知值一律判「不是组」—— 无法识别的取值退化成最保守的旧语义")
        void kindNormalization() {
            assertFalse(new ConditionRule().isGroup(), "字段初始值 RULE");
            assertFalse(withKind(null).isGroup(), "null kind 必须判 false");
            assertFalse(withKind("").isGroup(), "空白 kind 必须判 false");
            assertFalse(withKind("   ").isGroup(), "全空白 kind 必须判 false");
            assertFalse(withKind("XOR").isGroup(), "未知取值判 false（由校验器报错，求值器不抛）");
            assertTrue(withKind("GROUP").isGroup());
            assertTrue(withKind("group").isGroup(), "大小写不敏感");
            assertTrue(withKind("  GROUP  ").isGroup(), "两侧空白必须被裁掉");
            assertFalse(withKind("RULE").isGroup());
        }

        @Test
        @DisplayName("isRule 与 isGroup 恒互补 —— 不存在两者都 false 的第三态")
        void ruleAndGroupAreComplementary() {
            for (String kind : new String[]{null, "", "  ", "RULE", "rule", "GROUP", "Group", "XOR"}) {
                ConditionRule candidate = withKind(kind);
                assertTrue(candidate.isGroup() != candidate.isRule(),
                        "kind=" + kind + " 时 isGroup/isRule 出现同真或同假");
            }
        }

        @Test
        @DisplayName("group 工厂只写 kind 与 condition，三个 RULE 专用字段保持 null —— 不制造脏值")
        void groupFactoryKeepsRuleFieldsNull() {
            ConditionRule envelope = ConditionRule.group(cond("AND", rule("amount", "GT", 1)));
            assertTrue(envelope.isGroup());
            assertEquals(ConditionRule.KIND_GROUP, envelope.getKind());
            assertNotNull(envelope.getCondition());
            assertNull(envelope.getField());
            assertNull(envelope.getOp());
            assertNull(envelope.getValue());
        }

        @Test
        @DisplayName("hasNestedGroup 只判「直接子级」；需要全量深度信息时用 depthOf")
        void hasNestedGroupIsShallow() {
            assertFalse(cond("AND", rule("amount", "GT", 1)).hasNestedGroup());
            assertTrue(cond("AND", rule("amount", "GT", 1), group(cond("OR"))).hasNestedGroup());
            FlowCondition withGrandChild = cond("AND", group(cond("OR", group(cond("AND")))));
            assertTrue(withGrandChild.hasNestedGroup(), "直接子级就是组 → true");
            assertEquals(3, FlowPathResolver.depthOf(withGrandChild), "而实际深度是 3，浅判定不代替全量判定");
        }

        @Test
        @DisplayName("序列化：kind 进 JSON，isGroup / isRule 不进（JsonIgnore）—— 否则每条规则都多一个 \"group\":false")
        void groupFlagIsNotSerialized() throws Exception {
            String json = FlowDefinitionCodec.write(cond("AND", rule("amount", "GT", 1)));
            JsonNode ruleNode = new ObjectMapper().readTree(json).path("rules").get(0);
            assertEquals("RULE", ruleNode.path("kind").asText(), "kind 必须带默认值并写进 JSON");
            assertFalse(ruleNode.has("group"),
                    "isGroup() 是手写 isXxx()，不标 @JsonIgnore 会被 Jackson 当成只读属性写出去");
            assertFalse(ruleNode.has("rule"), "isRule() 同理");

            String groupJson = FlowDefinitionCodec.write(
                    cond("AND", group(cond("OR", rule("title", "EQ", "x")))));
            JsonNode envelope = new ObjectMapper().readTree(groupJson).path("rules").get(0);
            assertEquals("GROUP", envelope.path("kind").asText());
            assertEquals("OR", envelope.path("condition").path("logic").asText());
        }

        @Test
        @DisplayName("反序列化：没有 kind 的存量 JSON 读出来就是 RULE，condition 为 null")
        void legacyJsonWithoutKindReadsAsRule() throws Exception {
            String legacy = "{\"logic\":\"AND\",\"rules\":[{\"field\":\"amount\",\"op\":\"GT\",\"value\":5000}]}";
            ObjectMapper mapper = new ObjectMapper()
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            FlowCondition parsed = mapper.readValue(legacy, FlowCondition.class);
            ConditionRule first = parsed.getRules().get(0);
            assertFalse(first.isGroup(), "缺 kind 必须按 RULE 解析");
            assertEquals(ConditionRule.KIND_RULE, first.getKind(), "字段初始值必须保留，而不是被反序列化成 null");
            assertNull(first.getCondition());
        }
    }

    // ==================================================================
    // 2. 求值
    // ==================================================================

    @Nested
    @DisplayName("② 求值：三层嵌套真值表与边界")
    class Evaluation {

        @Test
        @DisplayName("三层嵌套四种组合全部正确（含「外层假 + 内层真 → 整体假」这条防串 logic 的用例）")
        void threeLevelTruthTable() {
            FlowCondition condition = threeLevelNested();
            assertTrue(FlowPathResolver.evaluate(condition, data(6000, "紧急采购", null)),
                    "外层真 + 内层第一支真");
            assertTrue(FlowPathResolver.evaluate(condition, data(6000, "普通", "加急处理")),
                    "外层真 + 内层第二支（更深一层的 AND）真");
            assertFalse(FlowPathResolver.evaluate(condition, data(6000, "普通", "常规")),
                    "外层真 + 内层两支都假");
            assertFalse(FlowPathResolver.evaluate(condition, data(100, "紧急采购", "加急处理")),
                    "外层假时，内层即使命中也必须整体不成立");
        }

        @Test
        @DisplayName("组信封里 condition 为 null 时判 false 且不抛 NPE —— 坏配置不该把求值器打挂")
        void nullInnerConditionIsFalse() {
            ConditionRule broken = new ConditionRule();
            broken.setKind(ConditionRule.KIND_GROUP);
            broken.setCondition(null);
            assertFalse(FlowPathResolver.evaluate(cond("AND", broken), data(6000, "紧急", "加急")),
                    "AND 组含一个判不了的项 → 整体不成立（保守取假：宁可少走分支，不可错走）");
            assertTrue(FlowPathResolver.evaluate(
                            cond("OR", broken, rule("amount", "GT", 5000)), data(6000, null, null)),
                    "OR 组里坏项判假，不应阻断后面正常的项");
        }

        @Test
        @DisplayName("空组恒判 false：AND 组被拖成假、OR 组继续看后面的项")
        void emptyGroupIsFalse() {
            FlowCondition empty = cond("AND");
            assertTrue(FlowPathResolver.evaluate(
                    cond("AND", rule("amount", "GT", 5000)), data(6000, null, null)), "基准：不含空组时命中");
            assertFalse(FlowPathResolver.evaluate(
                    cond("AND", rule("amount", "GT", 5000), group(empty)), data(6000, null, null)));
            assertTrue(FlowPathResolver.evaluate(
                    cond("OR", group(empty), rule("amount", "GT", 5000)), data(6000, null, null)));
        }

        @Test
        @DisplayName("OR 组首项命中即短路返回（顺序敏感语义与第二期一致）")
        void orShortCircuitsOnFirstHit() {
            FlowCondition condition = cond("OR",
                    rule("title", "CONTAINS", "紧急"),
                    rule("amount", "GT", 5000));
            assertTrue(FlowPathResolver.evaluate(condition, data(1, "紧急", null)),
                    "首项命中，后面的项不影响结果");
        }

        @Test
        @DisplayName("向后兼容：无 kind 的旧条件与显式 kind=RULE 的条件，求值结果逐项一致")
        void legacyRuleAndExplicitRuleAgree() {
            Map<String, Object>[] cases = new Map[]{
                    data(6000, "紧急", "加急"),
                    data(6000, "普通", "加急"),
                    data(6000, "普通", "常规"),
                    data(100, "紧急", "加急"),
                    data(null, null, null),
            };
            FlowCondition legacy = cond("AND", rule("amount", "GT", 5000), rule("title", "CONTAINS", "紧急"));
            FlowCondition explicit = cond("AND",
                    explicitRule("amount", "GT", 5000), explicitRule("title", "CONTAINS", "紧急"));
            for (Map<String, Object> formData : cases) {
                assertEquals(FlowPathResolver.evaluate(legacy, formData),
                        FlowPathResolver.evaluate(explicit, formData),
                        "同一份配置的两种等价表示求值结果不同，formData=" + formData);
            }
        }

        private ConditionRule explicitRule(String field, String op, Object value) {
            ConditionRule explicit = rule(field, op, value);
            explicit.setKind(ConditionRule.KIND_RULE);
            return explicit;
        }
    }

    // ==================================================================
    // 3. 深度
    // ==================================================================

    @Nested
    @DisplayName("③ 深度：depthOf 与 MAX_DEPTH")
    class Depth {

        @Test
        @DisplayName("depthOf：null=0、单层=1、逐层 +1，混排时取最深的那条链")
        void depthCounting() {
            assertEquals(0, FlowPathResolver.depthOf(null));
            assertEquals(1, FlowPathResolver.depthOf(cond("AND", rule("amount", "GT", 1))), "只有规则 = 1 层");
            assertEquals(1, FlowPathResolver.depthOf(cond("AND")), "空组也是 1 层");
            assertEquals(2, FlowPathResolver.depthOf(
                    cond("AND", group(cond("OR", rule("amount", "GT", 1))))));
            assertEquals(3, FlowPathResolver.depthOf(threeLevelNested()));
            assertEquals(3, FlowPathResolver.depthOf(cond("AND",
                    rule("amount", "GT", 1),
                    group(cond("OR", group(cond("AND", rule("title", "EQ", "x"))))))),
                    "一条浅链 + 一条深链并存时取最深");
        }

        @Test
        @DisplayName("MAX_DEPTH 恒为 3 —— 校验器与前端按钮共用同一数值，单边改动必须让测试变红")
        void maxDepthIsThree() {
            assertEquals(3, FlowCondition.MAX_DEPTH,
                    "改这个值等于同时改了「后端允许多深」与「前端何时禁用添加组」，"
                            + "必须先更新金样例与前端常量");
        }
    }

    // ==================================================================
    // 4. 运行期依赖判定（本批静默失效风险最高的一处）
    // ==================================================================

    @Nested
    @DisplayName("④ 运行期依赖判定：必须递归到任意深度")
    class RuntimeDependency {

        @Test
        @DisplayName("直接子级引用 process.* → 依赖（第二期行为必须原样保持）")
        void directProcessField() {
            assertTrue(ProcessFieldCatalog.isRuntimeDependent(
                    cond("AND", rule("amount", "GT", 1), rule("process.prevNodeResult", "EQ", "REJECTED"))));
            assertFalse(ProcessFieldCatalog.isRuntimeDependent(
                    cond("AND", rule("amount", "GT", 1), rule("title", "EQ", "x"))));
        }

        @Test
        @DisplayName("只有嵌套组里引用 process.* → 依然必须判为依赖（漏这一条会让工单静默走错分支）")
        void processFieldInsideNestedGroup() {
            FlowCondition condition = cond("AND",
                    rule("amount", "GT", 5000),
                    group(cond("OR",
                            rule("process.prevNodeResult", "EQ", "REJECTED"),
                            rule("title", "CONTAINS", "紧急"))));
            assertTrue(ProcessFieldCatalog.isRuntimeDependent(condition),
                    "判不出来的话，提交时就会给这条分支定态，而不是留到运行期再算");
        }

        @Test
        @DisplayName("三层深处才有 process.* → 同样必须判为依赖（递归不能只走一层）")
        void processFieldAtThirdLevel() {
            FlowCondition condition = cond("AND",
                    rule("amount", "GT", 5000),
                    group(cond("OR",
                            rule("title", "CONTAINS", "紧急"),
                            group(cond("AND", rule("process.elapsedHours", "GT", 48))))));
            assertTrue(ProcessFieldCatalog.isRuntimeDependent(condition));
        }

        @Test
        @DisplayName("嵌套组里全是表单字段 / 空组 / 空条件 → 不依赖")
        void nestedWithoutProcessFields() {
            assertFalse(ProcessFieldCatalog.isRuntimeDependent(threeLevelNested()),
                    "三层全是表单字段，提交时一次就能算完");
            assertFalse(ProcessFieldCatalog.isRuntimeDependent(cond("AND", group(cond("OR")))));
            assertFalse(ProcessFieldCatalog.isRuntimeDependent(null));
            assertFalse(ProcessFieldCatalog.isRuntimeDependent(cond("AND")));
        }

        @Test
        @DisplayName("端到端：嵌套组里的 process.* 必须让整个流程被识别为运行期流程")
        void hasRuntimeFeatureSeesNestedDependency() {
            FlowDefinition custom = flowWith(cond("AND",
                    rule("amount", "GT", 5000),
                    group(cond("OR", rule("process.anyRejected", "EQ", "true")))));
            assertTrue(custom.hasRuntimeFeature(),
                    "hasRuntimeFeature 是「走不走新代码路径」的唯一判据；漏了嵌套组就会用第二期的路径处理运行期条件");

            assertFalse(flowWith(cond("AND", rule("amount", "GT", 5000))).hasRuntimeFeature());
        }
    }

    // ==================================================================
    // 5. 说明文案
    // ==================================================================

    @Nested
    @DisplayName("⑤ 说明文案：括号与顺序")
    class Descriptions {

        @Test
        @DisplayName("三层嵌套文案逐字钉死：组内必须加括号，层级不能被压平")
        void nestedTextKeepsParentheses() {
            String text = FlowPathResolver.describeCondition(
                    threeLevelNested(), data(6000, "紧急采购", "加急"), Map.of());
            assertEquals("amount 大于 5000（实际 6000） 且 （title 包含 紧急（实际 紧急采购）"
                            + " 或 （remark 包含 加急（实际 加急）））",
                    text,
                    "「A 且 B 或 C」与「A 且 (B 或 C)」是两份完全不同的配置，括号不可省");
        }

        @Test
        @DisplayName("单条规则的组也会加括号 —— 括号表达的是「这是一组」，不是「里面有几条」")
        void singleRuleGroupStillHasParentheses() {
            String text = FlowPathResolver.describeCondition(
                    cond("AND", group(cond("OR", rule("amount", "GT", 5000)))),
                    data(6000, null, null), Map.of());
            assertEquals("（amount 大于 5000（实际 6000））", text);
        }

        @Test
        @DisplayName("嵌套组里的运行期字段在「待判定」文案里也要被列出来（否则配置者只能一层层点开找）")
        void nestedProcessFieldAppearsInDeferredHint() {
            FlowDefinition definition = flowWith(cond("AND",
                    rule("amount", "GT", 5000),
                    group(cond("OR", rule("process.prevNodeResult", "EQ", "REJECTED")))));
            FlowPathResolver.Result result = FlowPathResolver.resolve(definition, SCHEMA, data(6000, null, null));
            FlowPathResolver.ResolvedNode n2 = nodeOf(result, "n2");
            assertEquals(NodeActivation.INACTIVE, n2.activation(),
                    "提交时判不了的分支下游必须 INACTIVE，不能直接定态");
            assertNotNull(n2.conditionDesc());
            assertTrue(n2.conditionDesc().contains("上一节点结果"),
                    "待判定说明必须点名依赖了哪个运行期字段，实际：" + n2.conditionDesc());
            assertTrue(n2.conditionDesc().contains("自动判定"));
        }

        private FlowPathResolver.ResolvedNode nodeOf(FlowPathResolver.Result result, String key) {
            return result.nodes().stream()
                    .filter(node -> key.equals(node.nodeKey()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("结果里没有节点：" + key));
        }
    }

    // ==================================================================
    // 6. 校验器
    // ==================================================================

    @Nested
    @DisplayName("⑥ 校验器：深度上限 / 空组 / 脏值 / 非法 kind")
    class Validation {

        @Test
        @DisplayName("合法的三层嵌套零问题，且发布路径（validate）不抛")
        void validThreeLevelPasses() {
            FlowDefinition definition = flowWith(threeLevelNested());
            assertEquals(List.of(), FlowDefinitionValidator.collectProblems(definition, SCHEMA));
            FlowDefinitionValidator.validate(definition, SCHEMA);
        }

        @Test
        @DisplayName("第 3 层合法、第 4 层报错，且只报一条（不按层数复述同一个问题）")
        void tooDeepReportsExactlyOnce() {
            FlowCondition four = cond("AND",
                    rule("amount", "GT", 5000),
                    group(cond("AND",
                            rule("title", "CONTAINS", "紧急"),
                            group(cond("AND",
                                    rule("remark", "CONTAINS", "加急"),
                                    group(cond("AND", rule("amount", "GT", 10000))))))));
            assertEquals(3, FlowPathResolver.depthOf(threeLevelNested()));
            assertEquals(4, FlowPathResolver.depthOf(four));

            List<String> problems = FlowDefinitionValidator.collectProblems(flowWith(four), SCHEMA);
            assertEquals(1, problems.size(), "深度超限只应报一条，实际：" + problems);
            assertTrue(problems.get(0).contains("条件嵌套层数超过上限"), problems.get(0));
            assertTrue(problems.get(0).contains("最多 3 层"), "文案要写明上限：" + problems.get(0));
        }

        @Test
        @DisplayName("空组：rules 为空 → 「嵌套条件组为空」；condition 为 null → 「是空的嵌套条件组」")
        void emptyGroupRejected() {
            List<String> emptyRules = FlowDefinitionValidator.collectProblems(
                    flowWith(cond("AND", rule("amount", "GT", 5000), group(cond("AND")))), SCHEMA);
            assertEquals(1, emptyRules.size(), emptyRules.toString());
            assertTrue(emptyRules.get(0).contains("嵌套条件组为空"), emptyRules.get(0));

            ConditionRule nullInner = new ConditionRule();
            nullInner.setKind(ConditionRule.KIND_GROUP);
            List<String> nullInnerProblems = FlowDefinitionValidator.collectProblems(
                    flowWith(cond("AND", nullInner)), SCHEMA);
            assertEquals(1, nullInnerProblems.size(), nullInnerProblems.toString());
            assertTrue(nullInnerProblems.get(0).contains("是空的嵌套条件组"), nullInnerProblems.get(0));
        }

        @Test
        @DisplayName("组信封上残留 field/op/value → 报错（设计器切换类型写出的半残对象不许进库）")
        void staleRuleFieldsOnGroupRejected() {
            ConditionRule dirty = ConditionRule.group(cond("AND", rule("title", "CONTAINS", "紧急")));
            dirty.setField("amount");
            dirty.setOp("GT");
            dirty.setValue(5000);
            List<String> problems = FlowDefinitionValidator.collectProblems(flowWith(cond("AND", dirty)), SCHEMA);
            assertEquals(1, problems.size(), problems.toString());
            assertTrue(problems.get(0).contains("不应携带字段"), problems.get(0));
        }

        @Test
        @DisplayName("不认识的 kind → 报错而不是静默当 RULE 用（否则会被误报成「未选择字段」）")
        void unknownKindRejected() {
            ConditionRule weird = new ConditionRule();
            weird.setKind("XOR");
            weird.setField("amount");
            weird.setOp("GT");
            weird.setValue(5000);
            List<String> problems = FlowDefinitionValidator.collectProblems(flowWith(cond("AND", weird)), SCHEMA);
            assertEquals(1, problems.size(), problems.toString());
            assertTrue(problems.get(0).contains("的种类不合法"), problems.get(0));
            assertTrue(problems.get(0).contains("XOR"), problems.get(0));
        }

        @Test
        @DisplayName("递归把字段存在性校验带进嵌套组")
        void fieldCheckRecursesIntoGroups() {
            List<String> problems = FlowDefinitionValidator.collectProblems(
                    flowWith(cond("AND",
                            rule("amount", "GT", 5000),
                            group(cond("OR", rule("nope", "EQ", "x"))))),
                    SCHEMA);
            assertEquals(1, problems.size(), problems.toString());
            assertTrue(problems.get(0).contains("引用的表单字段「nope」不存在"), problems.get(0));
        }

        @Test
        @DisplayName("递归把类型可比性校验带进嵌套组")
        void typeCheckRecursesIntoGroups() {
            List<String> problems = FlowDefinitionValidator.collectProblems(
                    flowWith(cond("AND",
                            rule("amount", "GT", 5000),
                            group(cond("OR", rule("title", "GT", 1))))),
                    SCHEMA);
            assertEquals(1, problems.size(), problems.toString());
            assertTrue(problems.get(0).contains("只能用于数字或日期字段"), problems.get(0));
        }

        @Test
        @DisplayName("嵌套组里的 process.*：校验通过 + 同时被识别为运行期依赖（两件事必须同时成立）")
        void nestedProcessFieldValidButRuntimeDependent() {
            FlowDefinition definition = flowWith(cond("AND",
                    rule("amount", "GT", 5000),
                    group(cond("OR", rule("process.prevNodeResult", "EQ", "REJECTED")))));
            assertEquals(List.of(), FlowDefinitionValidator.collectProblems(definition, SCHEMA),
                    "运行期字段是合法字段，校验不该报错");
            assertTrue(definition.hasRuntimeFeature(), "但它必须让流程被识别为运行期流程");
        }
    }

    // ==================================================================
    // 7. 端到端
    // ==================================================================

    @Nested
    @DisplayName("⑦ 端到端：嵌套条件决定实际路径")
    class EndToEnd {

        @Test
        @DisplayName("命中嵌套条件 → n2 激活；不命中 → n2 跳过，且无 INACTIVE（第二期两分法仍成立）")
        void nestedConditionDrivesActualPath() {
            FlowDefinition definition = flowWith(threeLevelNested());
            FlowPathResolver.Result hit = FlowPathResolver.resolve(definition, SCHEMA, data(6000, "紧急采购", null));
            assertEquals(NodeActivation.ACTIVE, activationOf(hit, "n2"));
            assertTrue(hit.skippedNodes().isEmpty(), "命中时 else 出口直连 END，不该有节点被标记跳过");

            FlowPathResolver.Result miss = FlowPathResolver.resolve(definition, SCHEMA, data(100, "普通", "常规"));
            assertEquals(NodeActivation.SKIPPED, activationOf(miss, "n2"));
            assertTrue(miss.inactiveNodes().isEmpty(), "全是表单字段 → 不存在「还没定」的节点");
        }

        @Test
        @DisplayName("嵌套运行期条件：提交时下游 INACTIVE，喂上运行期上下文后收敛为 ACTIVE / SKIPPED")
        void runtimeContextResolvesNestedCondition() {
            FlowDefinition definition = flowWith(cond("AND",
                    rule("amount", "GT", 5000),
                    group(cond("OR", rule("process.prevNodeResult", "EQ", "REJECTED")))));

            FlowPathResolver.Result submitTime = FlowPathResolver.resolve(definition, SCHEMA, data(6000, null, null));
            assertEquals(NodeActivation.INACTIVE, activationOf(submitTime, "n2"), "提交时判不了 → 未判定");

            FlowPathResolver.Result rejected = FlowPathResolver.resolve(definition, SCHEMA, data(6000, null, null),
                    new RuntimeContext("REJECTED", 3d, 10d, true, 1, 1), null);
            assertEquals(NodeActivation.ACTIVE, activationOf(rejected, "n2"), "上一节点被驳回 → 命中嵌套组");

            FlowPathResolver.Result approved = FlowPathResolver.resolve(definition, SCHEMA, data(6000, null, null),
                    new RuntimeContext("APPROVED", 3d, 10d, false, 0, 1), null);
            assertEquals(NodeActivation.SKIPPED, activationOf(approved, "n2"), "上一节点通过 → 内层 OR 不命中");

            FlowPathResolver.Result lowAmount = FlowPathResolver.resolve(definition, SCHEMA, data(100, null, null),
                    new RuntimeContext("REJECTED", 3d, 10d, true, 1, 1), null);
            assertEquals(NodeActivation.SKIPPED, activationOf(lowAmount, "n2"),
                    "外层 RULE 不成立时，内层即使命中也必须整体不成立（AND 语义）");
        }

        @Test
        @DisplayName("定义经 JSON 往返后求值结果与深度都不变（嵌套结构可安全持久化）")
        void nestedConditionSurvivesCodecRoundTrip() {
            FlowDefinition definition = flowWith(threeLevelNested());
            FlowDefinition restored = copyOf(definition);
            Map<String, Object> formData = data(6000, "普通", "加急处理");
            FlowCondition original = find(definition, "c1").getBranches().get(0).getCondition();
            FlowCondition roundTripped = find(restored, "c1").getBranches().get(0).getCondition();
            assertEquals(FlowPathResolver.evaluate(original, formData),
                    FlowPathResolver.evaluate(roundTripped, formData));
            assertTrue(FlowPathResolver.evaluate(roundTripped, formData), "往返后仍应命中");
            assertEquals(3, FlowPathResolver.depthOf(roundTripped), "往返后深度不能变浅");
        }

        private NodeActivation activationOf(FlowPathResolver.Result result, String key) {
            return result.nodes().stream()
                    .filter(node -> key.equals(node.nodeKey()))
                    .findFirst()
                    .map(FlowPathResolver.ResolvedNode::activation)
                    .orElseThrow(() -> new IllegalStateException("结果里没有节点：" + key));
        }
    }
}
