package com.enterprise.ticket.common.flow;

import com.enterprise.ticket.common.constant.FormFieldType;
import com.enterprise.ticket.common.form.FormSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.enterprise.ticket.common.flow.FlowTestFixtures.approval;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.branch;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.cond;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.condition;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.elseBranch;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.end;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.field;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.regressionFlow;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.roleRule;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.rule;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.schema;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.wave2Flow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流程路径求值（Phase 15 · Wave 1）—— {@link FlowPathResolver} 回归测试。
 *
 * <p>这是本期最关键的一步：它把"提交时按表单数据求值的 DAG"降维成"一条线性审批链 + 若干
 * 被跳过的节点"，从而让既有审批引擎（step_order + ANY/ALL + 驳回作废）**零改动**复用。
 *
 * <p>因此这里重点固化三件事，任何一件错了都会让线上行为诡异且难查：
 * <ol>
 *   <li><b>命中路径的顺序</b>必须与表单数据一致（金额大 → 走财务复核；金额小 → 跳过它）；</li>
 *   <li><b>被跳过的节点必须仍被返回</b>且带"为什么跳过"的说明（详情页要展示，落库为 SKIPPED）；</li>
 *   <li><b>stepOrder 唯一且覆盖全部审批节点</b>（含被跳过的） —— 跳过节点不能因为"没走"
 *       就缺号或以 PENDING 落库，否则会卡住"最小含 PENDING 的步骤"这一推进判定。
 *       此外还有一条同样硬的顺序契约：<b>命中路径上的 stepOrder 严格递增</b>
 *       （W4-C.5 起由「拓扑距离」口径保证，见本类末节的三个用例）。</li>
 * </ol>
 *
 * <p>纯静态逻辑，不依赖 Spring / 数据库。
 */
class FlowPathResolverTest {

    private static final FormSchema SCHEMA = schema(
            field("amount", "金额", FormFieldType.NUMBER.name()),
            field("title", "标题", FormFieldType.TEXT.name()),
            field("receiver", "接收人", FormFieldType.USER.name())
    );

    private static FlowPathResolver.ResolvedNode node(FlowPathResolver.Result result, String key) {
        return result.nodes().stream()
                .filter(item -> key.equals(item.nodeKey()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("结果中不存在节点：" + key));
    }

    // ------------------------------------------------------------------
    // 命中路径
    // ------------------------------------------------------------------

    @Test
    @DisplayName("金额 8000：走「金额大于5000」分支，财务复核在命中路径上")
    void bigAmount_takesConditionBranch() {
        FlowPathResolver.Result result = FlowPathResolver.resolve(
                regressionFlow(), SCHEMA, Map.of("amount", 8000, "receiver", 7));

        List<String> onPath = result.onPathNodes().stream().map(FlowPathResolver.ResolvedNode::nodeKey).toList();
        assertEquals(List.of("n1", "n2", "n3"), onPath, "命中路径应为 主管→财务复核→归档确认");
        assertTrue(result.skippedNodes().isEmpty(), "该分支下没有节点被跳过");

        // 命中分支的节点带上"为什么走这条"的说明
        String desc = node(result, "n2").conditionDesc();
        assertNotNull(desc);
        assertTrue(desc.contains("金额判断"), "说明应带上条件节点名：" + desc);
        assertTrue(desc.contains("金额大于5000"), "说明应带上命中分支名：" + desc);
        assertTrue(desc.contains("8000"), "说明应带上实际值：" + desc);
        // 条件节点之前的审批节点没有分支说明
        assertNull(node(result, "n1").conditionDesc());
    }

    @Test
    @DisplayName("金额 100：不命中条件分支，走默认出口，财务复核被标记跳过并给出原因")
    void smallAmount_takesElseBranch() {
        FlowPathResolver.Result result = FlowPathResolver.resolve(
                regressionFlow(), SCHEMA, Map.of("amount", 100, "receiver", 7));

        List<String> onPath = result.onPathNodes().stream().map(FlowPathResolver.ResolvedNode::nodeKey).toList();
        assertEquals(List.of("n1", "n3"), onPath, "命中路径应为 主管→归档确认（跳过财务复核）");

        List<String> skipped = result.skippedNodes().stream().map(FlowPathResolver.ResolvedNode::nodeKey).toList();
        assertEquals(List.of("n2"), skipped, "被绕开的节点必须仍被返回（落库为 SKIPPED）");

        String skippedDesc = node(result, "n2").conditionDesc();
        assertNotNull(skippedDesc);
        assertTrue(skippedDesc.contains("未命中"), "应说明「未命中」某分支：" + skippedDesc);
        assertTrue(skippedDesc.contains("金额大于5000"), "应带上未命中的分支名：" + skippedDesc);
        assertTrue(skippedDesc.contains("已跳过"), "应明确该节点已被跳过：" + skippedDesc);

        // 默认分支之后的节点带上"以上条件均未命中"的说明
        String desc = node(result, "n3").conditionDesc();
        assertNotNull(desc);
        assertTrue(desc.contains("默认分支"), "应说明走了默认分支：" + desc);
    }

    @Test
    @DisplayName("stepOrder 唯一、从 1 连续递增，且覆盖全部审批节点（含被跳过的）")
    void stepOrder_uniqueAndCoversSkipped() {
        FlowPathResolver.Result result = FlowPathResolver.resolve(
                regressionFlow(), SCHEMA, Map.of("amount", 100));

        List<Integer> steps = result.nodes().stream().map(FlowPathResolver.ResolvedNode::stepOrder).toList();
        assertEquals(List.of(1, 2, 3), steps, "被跳过的 n2 也占一个 stepOrder，不能缺号");

        // n2 被跳过、n3 命中：命中链的 stepOrder 出现跳跃（1 → 3）。
        // 这是**正常现象、不是缺号**：编号是按「从起点出发的层号」对全部审批节点一次性分配的，
        // 被绕开的 n2 在自己的那一层上占了号。推进判定看的是"最小含 PENDING 的步骤"，
        // SKIPPED 不参与，所以跳跃不影响顺序。
        // 真正必须成立的契约是「**命中路径上的 stepOrder 严格递增**」——
        // 由 stepOrder_invariantAcrossFixtures 与两个汇合场景用例守着。
        List<Integer> onPathSteps = result.onPathNodes().stream()
                .map(FlowPathResolver.ResolvedNode::stepOrder).toList();
        assertEquals(List.of(1, 3), onPathSteps);
        assertFalse(node(result, "n2").onPath());
        assertTrue(node(result, "n3").onPath());
    }

    @Test
    @DisplayName("同一条边被两条分支指向（汇合）时不会重复枚举节点")
    void convergingBranches_enumeratedOnce() {
        FlowPathResolver.Result result = FlowPathResolver.resolve(
                regressionFlow(), SCHEMA, Map.of("amount", 8000));

        long n3Count = result.nodes().stream().filter(item -> "n3".equals(item.nodeKey())).count();
        assertEquals(1L, n3Count, "汇合点只应出现一次");
    }

    // ------------------------------------------------------------------
    // stepOrder 顺序契约（Phase 16 Wave 4 · W4-C.5）
    //
    // 编号口径 = 「从起点出发的最长路径层号升序，同层按定义书写顺序」。
    // 这一节把口径的**两个必要条件**各自钉成一条用例：
    //   ① 汇合场景下，汇合点不能抢到 else 链前面（这是 W4-C 上报的那个缺陷）；
    //   ② 长短两条路径汇合时，必须按最长路径定层（按最短会让长路径上出现 stepOrder 回退）。
    // 再补一条覆盖全部夹具的「路径上严格递增」不变式 —— 那是「运行时路径顺序」的形式化表述，
    // 也是 FlowActivationService 敢用「当前节点 + 1」做动态插入点的前提。
    // ------------------------------------------------------------------

    @Test
    @DisplayName("W4-C.5：else 分支有串联节点并汇合到同一下游 → 汇合点编号必须大于 else 链（旧 DFS 口径下这里是反的）")
    void elseChainAndConvergence_stepOrderFollowsRuntimePath() {
        // 走 else（金额 100）：主管审批 → 部门经理审批 → 归档确认；编号须为 1 < 3 < 4
        FlowPathResolver.Result miss = FlowPathResolver.resolve(elseChainFlow(), SCHEMA, Map.of("amount", 100));
        assertEquals(List.of(1, 3, 4), miss.onPathNodes().stream()
                .map(FlowPathResolver.ResolvedNode::stepOrder).toList());
        assertEquals(List.of("n1", "n3", "n4"), miss.onPathNodes().stream()
                        .map(FlowPathResolver.ResolvedNode::nodeKey).toList(),
                "命中路径的呈现顺序必须与设计器上画的一致");

        // 走首条分支（金额 8000）：主管审批 → 财务复核 → 归档确认
        FlowPathResolver.Result hit = FlowPathResolver.resolve(elseChainFlow(), SCHEMA, Map.of("amount", 8000));
        assertEquals(List.of("n1", "n2", "n4"), hit.onPathNodes().stream()
                .map(FlowPathResolver.ResolvedNode::nodeKey).toList());

        // 全部审批节点仍被编号且不重号（被跳过的也在内）
        assertEquals(List.of(1, 2, 3, 4), miss.nodes().stream()
                .map(FlowPathResolver.ResolvedNode::stepOrder).toList());
    }

    @Test
    @DisplayName("长短两条路径汇合：按「最长路径」定层 —— 按最短会让长路径上出现 stepOrder 回退")
    void unevenBranches_useLongestDepthSoOrderNeverGoesBackwards() {
        // 长链（金额 8000）：n1 → a1 → a2 → a3 → n9
        FlowPathResolver.Result hit = FlowPathResolver.resolve(unevenBranchFlow(), SCHEMA, Map.of("amount", 8000));
        assertEquals(List.of("n1", "a1", "a2", "a3", "n9"), hit.onPathNodes().stream()
                .map(FlowPathResolver.ResolvedNode::nodeKey).toList());
        assertStrictlyIncreasing(hit.onPathNodes());

        // 短链（金额 1）：n1 → b1x → n9 —— 汇合点 n9 必须排在两侧链的最后
        FlowPathResolver.Result miss = FlowPathResolver.resolve(unevenBranchFlow(), SCHEMA, Map.of("amount", 1));
        assertEquals(List.of("n1", "b1x", "n9"), miss.onPathNodes().stream()
                .map(FlowPathResolver.ResolvedNode::nodeKey).toList());
        assertStrictlyIncreasing(miss.onPathNodes());
    }

    @Test
    @DisplayName("不变式：任意夹具流程、任意命中路径上，stepOrder 严格递增且全局唯一")
    void stepOrder_invariantAcrossFixtures() {
        List<FlowDefinition> flows = List.of(regressionFlow(), elseChainFlow(), unevenBranchFlow(), wave2Flow());
        List<Map<String, Object>> inputs = List.of(
                Map.of("amount", 8000, "receiver", 7),
                Map.of("amount", 100, "receiver", 7));

        for (FlowDefinition flow : flows) {
            for (Map<String, Object> input : inputs) {
                FlowPathResolver.Result result = FlowPathResolver.resolve(flow, SCHEMA, input);
                List<Integer> all = result.nodes().stream()
                        .map(FlowPathResolver.ResolvedNode::stepOrder).toList();
                assertEquals(all.size(), all.stream().distinct().count(),
                        "同一份结果里不得重号：" + all);
                assertEquals(1, all.get(0), "编号从 1 起：" + all);
                assertStrictlyIncreasing(result.onPathNodes());
            }
        }
    }

    /** 路径上 stepOrder 必须严格递增 —— 「运行时路径顺序」的形式化表述 */
    private static void assertStrictlyIncreasing(List<FlowPathResolver.ResolvedNode> nodes) {
        List<Integer> steps = nodes.stream().map(FlowPathResolver.ResolvedNode::stepOrder).toList();
        for (int i = 1; i < steps.size(); i++) {
            assertTrue(steps.get(i) > steps.get(i - 1),
                    "路径上第 " + (i + 1) + " 个节点的 stepOrder 应大于前一个：" + steps);
        }
    }

    /**
     * W4-C 上报的缺陷场景：条件的两条分支**汇合**到同一下游节点，
     * 且 else 链本身还有节点（else 出口的节点 → 汇合点）。
     *
     * <p>旧口径（全图 DFS 前序枚举）会把首条分支的整条链连同<b>汇合点</b>先编完，
     * 再回头编 else，于是汇合点编号小于 else 链 —— 走 else 时「当前步骤」错位。
     */
    private static FlowDefinition elseChainFlow() {
        FlowDefinition definition = new FlowDefinition();
        definition.setStart("n1");
        definition.setNodes(new ArrayList<>(List.of(
                approval("n1", "主管审批", "ANY_SIGN", "c1", roleRule("admin")),
                condition("c1", "金额判断",
                        branch("b1", "金额大于5000", "n2", cond("AND", rule("amount", "GT", "5000"))),
                        elseBranch("b2", "其它情况", "n3")),
                approval("n2", "财务复核", "ANY_SIGN", "n4", roleRule("admin")),
                approval("n3", "部门经理审批", "ANY_SIGN", "n4", roleRule("admin")),
                approval("n4", "归档确认", "ANY_SIGN", "end", roleRule("admin")),
                end("end", "结束")
        )));
        return definition;
    }

    /**
     * 一长一短两条分支汇合到 {@code n9}：长链 3 个节点、短链 1 个节点。
     *
     * <p>专门用来分辨「最长路径」与「最短路径」两种定层方式 ——
     * 按最短时 {@code n9} 会被定到第 2 层，进而排在 {@code a3} 之前，
     * 使长链上出现 {@code 2 → 4 → 6 → 5} 这样的回退。
     */
    private static FlowDefinition unevenBranchFlow() {
        FlowDefinition definition = new FlowDefinition();
        definition.setStart("n1");
        definition.setNodes(new ArrayList<>(List.of(
                approval("n1", "主管审批", "ANY_SIGN", "c1", roleRule("admin")),
                condition("c1", "金额判断",
                        branch("b1", "金额大于5000", "a1", cond("AND", rule("amount", "GT", "5000"))),
                        elseBranch("b2", "其它情况", "b1x")),
                approval("a1", "一级复核", "ANY_SIGN", "a2", roleRule("admin")),
                approval("a2", "二级复核", "ANY_SIGN", "a3", roleRule("admin")),
                approval("a3", "三级复核", "ANY_SIGN", "n9", roleRule("admin")),
                approval("b1x", "简易复核", "ANY_SIGN", "n9", roleRule("admin")),
                approval("n9", "归档确认", "ANY_SIGN", "end", roleRule("admin")),
                end("end", "结束")
        )));
        return definition;
    }

    @Test
    @DisplayName("节点解析结果带上签署方式（归一化：非法/缺省 → 或签）")
    void resolvedNodeCarriesSignType() {
        FlowPathResolver.Result result = FlowPathResolver.resolve(
                regressionFlow(), SCHEMA, Map.of("amount", 8000));

        assertEquals("ANY_SIGN", node(result, "n1").signType());
        assertEquals("ALL_SIGN", node(result, "n2").signType());
        assertNotNull(node(result, "n1").approverRules());
        assertEquals(1, node(result, "n1").approverRules().size());
    }

    @Test
    @DisplayName("schema 为 null 时仍可求值（只是条件说明里用字段 key 代替中文名）")
    void worksWithoutSchema() {
        FlowPathResolver.Result result = FlowPathResolver.resolve(regressionFlow(), null, Map.of("amount", 100));
        assertEquals(List.of("n1", "n3"),
                result.onPathNodes().stream().map(FlowPathResolver.ResolvedNode::nodeKey).toList());
        assertTrue(node(result, "n2").conditionDesc().contains("amount"), "无 schema 时用字段 key");
    }

    // ------------------------------------------------------------------
    // 条件求值
    // ------------------------------------------------------------------

    @Test
    @DisplayName("数值比较：字符串与数字可混用（JSON 里 5000 与 \"5000\" 行为一致）")
    void numericComparison_toleratesTypeDifferences() {
        assertTrue(FlowPathResolver.evaluate(cond("AND", rule("amount", "GT", "5000")), Map.of("amount", 8000)));
        assertTrue(FlowPathResolver.evaluate(cond("AND", rule("amount", "GT", 5000)), Map.of("amount", "8000")));
        assertFalse(FlowPathResolver.evaluate(cond("AND", rule("amount", "GT", "5000")), Map.of("amount", 5000)));
        assertTrue(FlowPathResolver.evaluate(cond("AND", rule("amount", "GTE", "5000")), Map.of("amount", 5000)));
        assertTrue(FlowPathResolver.evaluate(cond("AND", rule("amount", "LTE", "5000")), Map.of("amount", 5000)));
        assertTrue(FlowPathResolver.evaluate(cond("AND", rule("amount", "LT", "5000")), Map.of("amount", 4999.5)));
    }

    @Test
    @DisplayName("数值比较：关键字段缺失或不可解析时判为「不命中」（宁可不走分支，也不误走）")
    void numericComparison_missingValueIsFalse() {
        assertFalse(FlowPathResolver.evaluate(cond("AND", rule("amount", "GT", "5000")), Map.of()));
        assertFalse(FlowPathResolver.evaluate(cond("AND", rule("amount", "GT", "5000")), Map.of("amount", "abc")));
        assertFalse(FlowPathResolver.evaluate(cond("AND", rule("amount", "GT", "")), Map.of("amount", 8000)));
    }

    @Test
    @DisplayName("相等 / 不等：数值等价与文本相等都成立")
    void equalityRules() {
        assertTrue(FlowPathResolver.evaluate(cond("AND", rule("title", "EQ", "采购")), Map.of("title", "采购")));
        assertFalse(FlowPathResolver.evaluate(cond("AND", rule("title", "EQ", "采购")), Map.of("title", "报销")));
        assertTrue(FlowPathResolver.evaluate(cond("AND", rule("title", "NE", "采购")), Map.of("title", "报销")));
        assertTrue(FlowPathResolver.evaluate(cond("AND", rule("amount", "EQ", "100")), Map.of("amount", 100)));
    }

    @Test
    @DisplayName("包含 / 不包含：集合按成员判定，文本按子串判定")
    void containsRules() {
        assertTrue(FlowPathResolver.evaluate(cond("AND", rule("tags", "CONTAINS", "IT")),
                Map.of("tags", List.of("IT", "行政"))));
        assertFalse(FlowPathResolver.evaluate(cond("AND", rule("tags", "CONTAINS", "财务")),
                Map.of("tags", List.of("IT", "行政"))));
        assertTrue(FlowPathResolver.evaluate(cond("AND", rule("tags", "NOT_CONTAINS", "财务")),
                Map.of("tags", List.of("IT", "行政"))));
        assertTrue(FlowPathResolver.evaluate(cond("AND", rule("title", "CONTAINS", "购")),
                Map.of("title", "采购申请")));
        assertFalse(FlowPathResolver.evaluate(cond("AND", rule("tags", "CONTAINS", "IT")), Map.of()));
    }

    @Test
    @DisplayName("为空 / 不为空：null、空串、空白串、空集合都算空")
    void emptyRules() {
        assertTrue(FlowPathResolver.evaluate(cond("AND", rule("title", "IS_EMPTY", null)), Map.of()));
        assertTrue(FlowPathResolver.evaluate(cond("AND", rule("title", "IS_EMPTY", null)), Map.of("title", "  ")));
        assertTrue(FlowPathResolver.evaluate(cond("AND", rule("tags", "IS_EMPTY", null)), Map.of("tags", List.of())));
        assertTrue(FlowPathResolver.evaluate(cond("AND", rule("title", "NOT_EMPTY", null)), Map.of("title", "x")));
        assertFalse(FlowPathResolver.evaluate(cond("AND", rule("title", "NOT_EMPTY", null)), Map.of("title", "")));
    }

    @Test
    @DisplayName("组合逻辑：AND 需全中，OR 命中一条即可（含短路不改变结论）")
    void logic_AndOr() {
        Map<String, Object> data = Map.of("amount", 8000, "title", "采购");
        assertTrue(FlowPathResolver.evaluate(cond("AND", rule("amount", "GT", "5000"), rule("title", "EQ", "采购")), data));
        assertFalse(FlowPathResolver.evaluate(cond("AND", rule("amount", "GT", "5000"), rule("title", "EQ", "报销")), data));
        assertTrue(FlowPathResolver.evaluate(cond("OR", rule("amount", "GT", "9999"), rule("title", "EQ", "采购")), data));
        assertFalse(FlowPathResolver.evaluate(cond("OR", rule("amount", "GT", "9999"), rule("title", "EQ", "报销")), data));
    }

    @Test
    @DisplayName("空条件 / 空规则列表判为不成立（不会把「无条件」当成「总是通过」）")
    void emptyCondition_isFalse() {
        assertFalse(FlowPathResolver.evaluate(null, Map.of("amount", 8000)));
        assertFalse(FlowPathResolver.evaluate(cond("AND"), Map.of("amount", 8000)));
    }

    @Test
    @DisplayName("运算符非法时判为不命中（不抛异常，避免脏配置把提交打挂）")
    void unknownOperator_isFalse() {
        assertFalse(FlowPathResolver.evaluate(cond("AND", rule("amount", "APPROX", "5")), Map.of("amount", 8000)));
    }

    // ------------------------------------------------------------------
    // 条件说明与辅助
    // ------------------------------------------------------------------

    @Test
    @DisplayName("describeCondition 用字段中文名 + 实际值拼说明")
    void describeCondition_usesLabels() {
        Map<String, String> labels = FlowPathResolver.labelsOf(SCHEMA);
        String text = FlowPathResolver.describeCondition(
                cond("AND", rule("amount", "GT", "5000")), Map.of("amount", 8000), labels);
        assertTrue(text.contains("金额"), text);
        assertTrue(text.contains("大于"), text);
        assertTrue(text.contains("5000"), text);
        assertTrue(text.contains("8000"), "应带上实际值便于申请人核对：" + text);

        // AND / OR 连接词
        String and = FlowPathResolver.describeCondition(
                cond("AND", rule("amount", "GT", "1"), rule("title", "NOT_EMPTY", null)), Map.of(), labels);
        assertTrue(and.contains(" 且 "), and);
        String or = FlowPathResolver.describeCondition(
                cond("OR", rule("amount", "GT", "1"), rule("title", "NOT_EMPTY", null)), Map.of(), labels);
        assertTrue(or.contains(" 或 "), or);

        assertEquals("默认分支", FlowPathResolver.describeCondition(null, Map.of(), labels));
        assertEquals("默认分支", FlowPathResolver.describeCondition(cond("AND"), Map.of(), labels));
    }

    @Test
    @DisplayName("labelsOf / conditionable：字段中文名映射与「可否作为条件字段」的判据")
    void labelsAndConditionable() {
        Map<String, String> labels = FlowPathResolver.labelsOf(SCHEMA);
        assertEquals("金额", labels.get("amount"));
        assertEquals("接收人", labels.get("receiver"));
        // Phase 16 Wave 2 · M2：labelsOf 由「仅表单字段」扩展为「表单字段 + 运行期字段」。
        // 理由：条件说明的渲染只有一个入口（describeCondition），若运行期字段不在标签表里，
        // 说明文案就会退化成打印 process.prevNodeResult 这种只有开发能看懂的原始 key。
        // 两个集合的 key 空间本不重叠（表单字段命名禁带点号），合并无歧义。
        assertEquals(3 + ProcessFieldCatalog.schema().getFields().size(), labels.size());
        assertEquals("上一节点结果", labels.get(ProcessFieldCatalog.PREV_NODE_RESULT));
        // schema 为 null 时仍返回运行期字段标签（不是空表）—— 借用单/无表单上下文的流程同样需要它
        assertEquals(ProcessFieldCatalog.schema().getFields().size(), FlowPathResolver.labelsOf(null).size());

        assertTrue(FlowPathResolver.conditionable(field("amount", "金额", FormFieldType.NUMBER.name())));
        assertTrue(FlowPathResolver.conditionable(field("title", "标题", FormFieldType.TEXT.name())));
        assertFalse(FlowPathResolver.conditionable(field("note", "说明", FormFieldType.DESCRIPTION.name())));
        assertFalse(FlowPathResolver.conditionable(field("line", "分隔线", FormFieldType.DIVIDER.name())));
        assertFalse(FlowPathResolver.conditionable(null));
    }
}
