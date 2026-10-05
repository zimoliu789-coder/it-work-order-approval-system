package com.enterprise.ticket.common.flow;

import com.enterprise.ticket.common.constant.FormFieldType;
import com.enterprise.ticket.common.form.FormSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static com.enterprise.ticket.common.flow.FlowTestFixtures.applicantChoose;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.approval;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.branch;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.cc;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.cond;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.condition;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.copyOf;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.elseBranch;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.end;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.field;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.find;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.prevAssignRule;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.regressionFlow;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.roleRule;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.rule;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.schema;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验问题清单的**逐字顺序锁**（Phase 16 · Wave 4 · W4-A1）。
 *
 * <h2>为什么必须单独有这样一个测试</h2>
 * <p>{@link FlowDefinitionValidator#collectProblems} 的产物不只是"有哪些问题"，还包括
 * <b>问题的先后顺序</b> —— 发布路径 {@link FlowDefinitionValidator#validate} 只抛<b>第一条</b>，
 * 所以「谁排第一」直接决定用户在界面上看到的是哪句话。而顺序又是由三段式推进
 * （结构 → 逐节点 → 图）与各段内部的检查次序共同决定的，横跨十几个检查点。
 *
 * <p>W4-A1 要把这个 916 行的类拆成若干子校验器。拆分类的重构**最容易悄悄改变的就是顺序**：
 * 方法搬了家、把 for 循环换成 stream、把两段合成一段 —— 每一项都可能让某条消息提前或推后，
 * 而这<b>不会让任何一个既有测试变红</b>：现有断言全是
 * {@code assertTrue(message.contains("关键字"))}（只判"有没有"），
 * 以及金样例的 "包含 + 条数"。
 *
 * <p>因此本类只做一件事：把「构造 → 完整的 problems 列表」逐字钉死。
 * 任何顺序或文案的漂移都会在这里立刻变红，并且 diff 会直接指出是哪两条换了位置。
 *
 * <h2>样本怎么挑</h2>
 * <p>不是随机取样，而是**按段序风险点定向构造**：
 * <ul>
 *   <li>「结构段内部按 nodes 原序收集」——多个节点的结构错必须按定义顺序出现；</li>
 *   <li>「结构段致命时不再进入节点段/图段」——否则会冒出一堆由脏索引导致的级联噪音；</li>
 *   <li>「同一节点内的检查次序」——名称 → 分支 → 审批人 → 指派 → 规则 → next → 签署 → 时限 → 动作；</li>
 *   <li>「图段的五个子检查 + 计数」依次为：环 → 不可达 → 死路 → 指派前置 → 运行期条件前置 → 计数；</li>
 *   <li>「条件递归的 where 前缀逐层累加」——靠层数文案钉住递归结构。</li>
 * </ul>
 *
 * <h2>与金样例测试的分工</h2>
 * <p>{@code FlowGoldenSampleTest} 管「前后端对同一份定义是否报了同一个问题」（跨端一致性）；
 * 本类管「后端自己报的顺序是否一字未动」（单端稳定性）。两者不可互相替代：
 * 金样例的期望是"包含"，天然无法表达顺序。
 */
class FlowValidatorProblemOrderTest {

    /** 与 {@code FlowDefinitionValidatorTest} 同构的表单上下文（条件字段存在性/可比性用） */
    private static final FormSchema SCHEMA = schema(
            field("amount", "金额", FormFieldType.NUMBER.name()),
            field("title", "标题", FormFieldType.TEXT.name()));

    private static List<String> msgs(String... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    // ------------------------------------------------------------------
    // 样本集
    // ------------------------------------------------------------------

    private static Stream<Arguments> problemOrderCases() {
        List<Arguments> cases = new ArrayList<>();

        // ---- 段一：定义级结构 ----

        cases.add(Arguments.of("struct-null-definition", null, null,
                msgs("流程定义为空")));

        cases.add(Arguments.of("struct-empty-nodes", new FlowDefinition(), null,
                msgs("流程至少要有一个节点")));

        FlowDefinition tooMany = copyOf(regressionFlow());
        for (int i = 0; i < 50; i++) {
            tooMany.getNodes().add(end("extra" + i, "额外" + i));
        }
        cases.add(Arguments.of("struct-node-count-exceeded", tooMany, null,
                msgs("节点数量（55）超过上限 50")));

        // 结构错按 nodes 原序收集（不是按错误种类聚合）
        FlowDefinition messy = new FlowDefinition();
        messy.setStart("ok");
        List<FlowNode> messyNodes = new ArrayList<>();
        messyNodes.add(approval("", "无标识", "ANY_SIGN", "ok", roleRule("admin")));
        messyNodes.add(null);
        messyNodes.add(approval("dup", "重复甲", "ANY_SIGN", "ok", roleRule("admin")));
        messyNodes.add(approval("dup", "重复乙", "ANY_SIGN", "ok", roleRule("admin")));
        messyNodes.add(approval("1bad", "格式错", "ANY_SIGN", "ok", roleRule("admin")));
        messyNodes.add(approval("ok", "正常", "ANY_SIGN", "ok", roleRule("admin")));
        FlowNode badType = new FlowNode();
        badType.setKey("bad");
        badType.setName("类型错");
        badType.setType("BOGUS");
        messyNodes.add(badType);
        messy.setNodes(messyNodes);
        cases.add(Arguments.of("struct-node-errors-in-listed-order", messy, null, msgs(
                "存在未设置标识（key）的节点",
                "存在空节点",
                "节点标识重复：dup",
                "节点标识不合法：1bad（字母开头，仅字母/数字/下划线，最长 64）",
                "节点「bad」的类型不合法：BOGUS")));

        FlowDefinition blankStart = copyOf(regressionFlow());
        blankStart.setStart("");
        cases.add(Arguments.of("struct-start-blank", blankStart, null,
                msgs("未设置起始节点")));

        FlowDefinition ghostStart = copyOf(regressionFlow());
        ghostStart.setStart("ghost");
        cases.add(Arguments.of("struct-start-missing", ghostStart, null,
                msgs("起始节点不存在：ghost")));

        // 关键：结构段致命时必须**收口**，不得进入节点段与图段。
        // 该定义同时具备「至少要有一个审批节点」的触发条件（只有条件/结束节点），
        // 若短路失效，这里会多出一条图段消息 —— 那就是级联噪音。
        FlowDefinition closedByStructure = new FlowDefinition();
        closedByStructure.setStart("c1");
        closedByStructure.setNodes(new ArrayList<>(List.of(
                condition("c1", "判断", elseBranch("b1", "其它", "end")),
                end("end", "结束"),
                end("end", "重复结束"))));
        cases.add(Arguments.of("struct-errors-close-before-node-stage", closedByStructure, null,
                msgs("节点标识重复：end")));

        // ---- 段二：单节点（审批 / 抄送 / 条件 / 结束） ----

        // 审批节点内的检查次序：名称 → 分支 → 审批人 → 指派 → 规则 → next → 签署 → 时限 → 动作
        FlowDefinition approvalInner = copyOf(regressionFlow());
        FlowNode a1 = find(approvalInner, "n1");
        a1.setName("");
        a1.setBranches(new ArrayList<>(List.of(elseBranch("x", "x", "end"))));
        a1.setApproverRules(new ArrayList<>());
        a1.setSignType("MAYBE_SIGN");
        a1.setTimeLimitHours(0);
        cases.add(Arguments.of("node-approval-inner-order", approvalInner, null, msgs(
                "节点「n1」未设置名称",
                "节点「n1」是审批节点，不应配置分支",
                "节点「n1」未配置任何审批人",
                "节点「n1」的签署方式不合法：MAYBE_SIGN",
                "节点「n1」的审批时限必须在 1..720 小时之间（当前 0）")));

        // 「申请人自选最多一条」必须先于逐条规则校验报出（前者是节点级、后者是规则级）
        FlowDefinition chooseTwice = copyOf(regressionFlow());
        find(chooseTwice, "n2").setApproverRules(new ArrayList<>(List.of(
                applicantChoose("ALL", null, 1, 1),
                applicantChoose("ALL", null, 1, 1))));
        cases.add(Arguments.of("node-approval-applicant-choose-twice", chooseTwice, null,
                msgs("节点「n2」配置了 2 条「申请人自选」规则，最多只能有 1 条")));

        // 抄送节点：规则禁用 → 分支 → 签署方式 → 时限
        FlowDefinition ccInner = new FlowDefinition();
        ccInner.setStart("cc1");
        ccInner.setNodes(new ArrayList<>(List.of(
                cc("cc1", "抄送", "end", applicantChoose("ALL", null, 1, 1)),
                end("end", "结束"))));
        find(ccInner, "cc1").setBranches(new ArrayList<>(List.of(elseBranch("x", "x", "end"))));
        find(ccInner, "cc1").setSignType("ANY_SIGN");
        find(ccInner, "cc1").setTimeLimitHours(24);
        cases.add(Arguments.of("node-cc-inner-order", ccInner, null, msgs(
                "节点「cc1」是抄送节点，不支持「申请人自选」——抄送对象必须在提交时即可确定",
                "节点「cc1」是抄送节点，不应配置分支",
                "节点「cc1」是抄送节点，不应配置签署方式",
                "节点「cc1」是抄送节点，不应配置审批时限",
                "流程中至少要有一个审批节点")));

        // 条件分支内部：按分支序号，各分支内按 标识 → 名称 → 去向 → 条件
        FlowDefinition branchInner = new FlowDefinition();
        branchInner.setStart("n1");
        branchInner.setNodes(new ArrayList<>(List.of(
                approval("n1", "审批", "ANY_SIGN", "c1", roleRule("admin")),
                condition("c1", "判断",
                        branch("b1", "", "ghost", null),
                        elseBranch("b1", "其它", "end")),
                end("end", "结束"))));
        cases.add(Arguments.of("node-condition-branch-inner-order", branchInner, null, msgs(
                "节点「c1」的第 1 个分支未设置名称",
                "节点「c1」的第 1 个分支指向的节点不存在：ghost",
                "节点「c1」的第 1 个分支未配置条件",
                "节点「c1」的第 2 个分支标识重复：b1")));

        // 条件组内部：逻辑 → 空列表
        FlowDefinition condLogic = new FlowDefinition();
        condLogic.setStart("n1");
        condLogic.setNodes(new ArrayList<>(List.of(
                approval("n1", "审批", "ANY_SIGN", "c1", roleRule("admin")),
                condition("c1", "判断",
                        branch("b1", "非法逻辑", "end", cond("XOR")),
                        elseBranch("b2", "其它", "end")),
                end("end", "结束"))));
        cases.add(Arguments.of("node-condition-logic-then-empty", condLogic, null, msgs(
                "节点「c1」的第 1 个分支的组合逻辑不合法：XOR",
                "节点「c1」的第 1 个分支的条件列表为空")));

        // 结束节点：next → 分支 → 审批人
        FlowDefinition endInner = copyOf(regressionFlow());
        FlowNode e = find(endInner, "end");
        e.setNext("n1");
        e.setBranches(new ArrayList<>(List.of(elseBranch("x", "x", "n1"))));
        e.setApproverRules(new ArrayList<>(List.of(roleRule("admin"))));
        cases.add(Arguments.of("node-end-inner-order", endInner, null, msgs(
                "节点「end」是结束节点，不应配置 next",
                "节点「end」是结束节点，不应配置分支",
                "节点「end」是结束节点，不应配置审批人")));

        // ---- 段三：图结构 + 计数 ----

        // 环 → 不可达 → 死路（三个子检查依次产出，不互相短路）
        FlowDefinition graphInner = new FlowDefinition();
        graphInner.setStart("n1");
        graphInner.setNodes(new ArrayList<>(List.of(
                approval("n1", "自环", "ANY_SIGN", "n1", roleRule("admin")),
                approval("orphan", "孤儿", "ANY_SIGN", "end", roleRule("admin")),
                end("end", "结束"))));
        cases.add(Arguments.of("graph-cycle-then-unreachable-then-deadend", graphInner, null, msgs(
                "流程存在环：n1 → n1",
                "存在从起始节点不可达的节点：orphan、end",
                "存在无法到达结束节点的节点：n1")));

        // 指派前置：首个审批节点就用 PREV_ASSIGN
        FlowDefinition prevAssignFirst = new FlowDefinition();
        prevAssignFirst.setStart("n1");
        prevAssignFirst.setNodes(new ArrayList<>(List.of(
                approval("n1", "上级指定", "ANY_SIGN", "end", prevAssignRule(1)),
                end("end", "结束"))));
        cases.add(Arguments.of("graph-prev-assign-without-preceding-approval", prevAssignFirst, null,
                msgs("节点「n1」使用了「上一节点审批人指定」，但它前面没有审批节点，"
                        + "没有上一节点可供指定（请把它放在至少一个审批节点之后）")));

        // 运行期条件前置 → 计数（前置检查在计数之前，顺序不可倒）
        FlowDefinition runtimeCondFirst = new FlowDefinition();
        runtimeCondFirst.setStart("c1");
        runtimeCondFirst.setNodes(new ArrayList<>(List.of(
                condition("c1", "耗时判断",
                        branch("b1", "超过24小时", "end",
                                cond("AND", rule(ProcessFieldCatalog.ELAPSED_HOURS, "GT", "24"))),
                        elseBranch("b2", "其它", "end")),
                end("end", "结束"))));
        cases.add(Arguments.of("graph-runtime-condition-then-count", runtimeCondFirst, null, msgs(
                "条件节点「c1」引用了运行期字段（上一节点结果 / 已耗时等），"
                        + "但它前面没有审批节点，提交后将没有任何待办能推动它"
                        + "（请把它放在至少一个审批节点之后）",
                "流程中至少要有一个审批节点")));

        // ---- 段二（动作子域）：驳回 → 超时 ----

        FlowDefinition actions = new FlowDefinition();
        actions.setStart("n1");
        FlowNode actionNode = approval("n1", "审批", "ANY_SIGN", "end", roleRule("admin"));
        RejectAction reject = new RejectAction();
        reject.setAction(RejectAction.GOTO);
        actionNode.setOnReject(reject);
        TimeoutAction timeout = new TimeoutAction();
        timeout.setAction("OOPS");
        actionNode.setOnTimeout(timeout);
        actions.setNodes(new ArrayList<>(List.of(actionNode, end("end", "结束"))));
        cases.add(Arguments.of("action-reject-then-timeout", actions, null, msgs(
                "节点「n1」的「驳回处理」配置不完整：改道（GOTO）必须指定已存在的目标节点",
                "节点「n1」的「超时处理」配置不合法：加签/改道需有效目标，超时阈值须为正数")));

        // 未识别为运行期流程时的兜底提示排在「配置不合法」之后
        FlowDefinition notRuntime = new FlowDefinition();
        notRuntime.setStart("n1");
        FlowNode plainNode = approval("n1", "审批", "ANY_SIGN", "end", roleRule("admin"));
        TimeoutAction unknownOnly = new TimeoutAction();
        unknownOnly.setAction("OOPS");
        plainNode.setOnTimeout(unknownOnly);
        notRuntime.setNodes(new ArrayList<>(List.of(plainNode, end("end", "结束"))));
        cases.add(Arguments.of("action-timeout-unknown-then-not-runtime", notRuntime, null, msgs(
                "节点「n1」的「超时处理」配置不合法：加签/改道需有效目标，超时阈值须为正数",
                "节点「n1」配置了超时升级动作，但流程未识别为运行期流程")));

        return cases.stream();
    }

    /**
     * 逐字比对：<b>全量问题列表与顺序都不得变化</b>。
     *
     * <p>失败时 JUnit 会打印两个列表的差异，能直接看出是哪一条提前/推后了。
     */
    @DisplayName("W4-A1 顺序锁：collectProblems 的问题清单与顺序逐字不变")
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("problemOrderCases")
    void problemListAndOrderAreStable(String id, FlowDefinition definition,
                                      FormSchema schema, List<String> expected) {
        List<String> actual = FlowDefinitionValidator.collectProblems(definition, schema);
        assertEquals(expected, actual,
                "用例 [" + id + "] 的问题清单或顺序发生变化 —— 拆分类重构不得改动发布校验的对外表现");
    }

    /** 样本自检：防止有人为了"让测试通过"而删样本 */
    @Test
    @DisplayName("W4-A1 顺序锁：样本覆盖三段与全部子检查")
    void sampleSetCoversEveryStage() {
        long count = problemOrderCases().count();
        assertTrue(count >= 18, "顺序锁样本过少（" + count + "），覆盖意义会被削弱");
    }

    /**
     * 条件嵌套深度：where 前缀逐层累加，且**只在超限的那一层报一条**。
     *
     * <p>单独成例（不走参数化）是因为 this 文案较长且核心断言是"层数前缀"，逐字写反而难读。
     * 「深度超限即 return，不按层复述」是 M3-A 的既定语义，这里一并锁住。
     */
    @Test
    @DisplayName("W4-A1 顺序锁：条件嵌套超限只报一条，且带上超限所在层的完整路径")
    void nestedConditionDepthReportsOnceWithFullPath() {
        ConditionRule level4 = ConditionRule.group(cond("AND",
                rule("amount", "GT", "1")));
        ConditionRule level3 = ConditionRule.group(cond("AND", level4));
        ConditionRule level2 = ConditionRule.group(cond("AND", level3));
        ConditionRule level1 = ConditionRule.group(cond("AND", level2));

        FlowDefinition definition = new FlowDefinition();
        definition.setStart("n1");
        definition.setNodes(new ArrayList<>(List.of(
                approval("n1", "审批", "ANY_SIGN", "c1", roleRule("admin")),
                condition("c1", "判断",
                        branch("b1", "嵌套太深", "end", cond("AND", level1)),
                        elseBranch("b2", "其它", "end")),
                end("end", "结束"))));

        List<String> actual = FlowDefinitionValidator.collectProblems(definition, SCHEMA);

        assertEquals(1, actual.size(), "深度超限应只报一条（不得按层复述），实际：" + actual);
        String message = actual.get(0);
        assertTrue(message.startsWith("节点「c1」的第 1 个分支"), "路径前缀丢失：" + message);
        assertTrue(message.contains("的条件嵌套层数超过上限（最多 3 层，当前至少 4 层）"),
                "深度文案变化：" + message);
    }
}
