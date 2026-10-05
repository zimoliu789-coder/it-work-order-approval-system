package com.enterprise.ticket.common.flow;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.FormFieldType;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.form.FormSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.enterprise.ticket.common.flow.FlowTestFixtures.approval;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.cc;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.cond;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.copyOf;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.end;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.field;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.find;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.leaderRule;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.prevAssignRule;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.regressionFlow;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.roleRule;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.rule;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.schema;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.wave2Flow;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 15 · Wave 2 流程内核回归：抄送节点 / 审批时限 / 直属领导 / 上一节点指定。
 *
 * <p>为什么这些必须有单测：Wave 2 的四项能力**都是"发布时不报错、运行时才失效"的类型**。
 * 抄送配了签署方式、时限设成 0、上一节点指定放在流程第一步 —— 这些在界面上都能点出来，
 * 但只有在提交那一刻才会表现为"没人收到消息""永远不会提醒""工单卡住不动"。
 * 因此这里的每一个断言都对应一条"用户看得见的失败"，而不是为了覆盖率凑数。
 *
 * <p>纯静态逻辑，不依赖 Spring / Mockito / 数据库。
 */
class FlowWave2Test {

    private static final FormSchema SCHEMA = schema(
            field("amount", "金额", FormFieldType.NUMBER.name()),
            field("title", "标题", FormFieldType.TEXT.name()),
            // regressionFlow() 的归档确认节点用「表单人员字段 receiver」，复用基准流程时必须一并声明
            field("receiver", "接收人", FormFieldType.USER.name())
    );

    /** 断言校验不通过，并返回异常信息（供进一步断言定位文案） */
    private static String invalid(FlowDefinition definition, FormSchema schema) {
        BusinessException exception = org.junit.jupiter.api.Assertions.assertThrows(
                BusinessException.class, () -> FlowDefinitionValidator.validate(definition, schema));
        assertEquals(ErrorCode.FLOW_DEFINITION_INVALID, exception.getErrorCode());
        return exception.getMessage();
    }

    /** 只含「抄送 → 结束」的最小流程，抄送节点由调用方改造 */
    private static FlowDefinition ccOnly(FlowNode ccNode) {
        FlowDefinition definition = new FlowDefinition();
        definition.setStart(ccNode.getKey());
        // 至少要有一个审批节点，否则会先撞上「流程中至少要有一个审批节点」而掩盖真正要测的规则
        ccNode.setNext("n1");
        definition.setNodes(new ArrayList<>(List.of(
                ccNode,
                approval("n1", "后续审批", "ANY_SIGN", "end", roleRule("admin")),
                end("end", "结束")
        )));
        return definition;
    }

    // ------------------------------------------------------------------
    // 基准：Wave 2 全要素流程本身必须合法
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Wave 2 全要素流程（抄送 + 时限 + 直属领导 + 上一节点指定）通过校验")
    void wave2Flow_passes() {
        assertDoesNotThrow(() -> FlowDefinitionValidator.validate(wave2Flow(), SCHEMA));
        assertDoesNotThrow(() -> FlowDefinitionValidator.validate(wave2Flow(), null),
                "未绑定表单时也应通过结构校验（条件字段存在性检查会跳过）");
    }

    // ------------------------------------------------------------------
    // 抄送节点
    // ------------------------------------------------------------------

    @Test
    @DisplayName("抄送节点：禁止配置签署方式")
    void ccNode_rejectsSignType() {
        FlowNode node = cc("cc1", "抄送管理员", null, roleRule("admin"));
        node.setSignType("ANY_SIGN");
        assertTrue(invalid(ccOnly(node), SCHEMA).contains("抄送节点，不应配置签署方式"));
    }

    @Test
    @DisplayName("抄送节点：禁止配置分支")
    void ccNode_rejectsBranches() {
        FlowNode node = cc("cc1", "抄送管理员", null, roleRule("admin"));
        node.setBranches(new ArrayList<>(List.of(
                FlowTestFixtures.elseBranch("b1", "默认", "n1"))));
        assertTrue(invalid(ccOnly(node), SCHEMA).contains("抄送节点，不应配置分支"));
    }

    @Test
    @DisplayName("抄送节点：禁止配置审批时限")
    void ccNode_rejectsTimeLimit() {
        FlowNode node = cc("cc1", "抄送管理员", null, roleRule("admin"));
        node.setTimeLimitHours(12);
        assertTrue(invalid(ccOnly(node), SCHEMA).contains("抄送节点，不应配置审批时限"));
    }

    @Test
    @DisplayName("抄送节点：禁用「申请人自选」与「上一节点审批人指定」")
    void ccNode_rejectsUnresolvableRules() {
        FlowNode choose = cc("cc1", "抄送管理员", null,
                FlowTestFixtures.applicantChoose("ALL", null, 1, 1));
        assertTrue(invalid(ccOnly(choose), SCHEMA).contains("不支持「申请人自选」"),
                "抄送对象必须在提交那一刻就能算出来，申请人自选要到提交时才有值");

        FlowNode prev = cc("cc1", "抄送管理员", null, prevAssignRule(1));
        assertTrue(invalid(ccOnly(prev), SCHEMA).contains("不支持「上一节点审批人指定」"));
    }

    @Test
    @DisplayName("抄送节点：至少要有一个抄送对象")
    void ccNode_requiresAtLeastOneRule() {
        FlowNode node = cc("cc1", "抄送管理员", null);
        assertTrue(invalid(ccOnly(node), SCHEMA).contains("未配置任何抄送对象"));
    }

    // ------------------------------------------------------------------
    // 上一节点审批人指定
    // ------------------------------------------------------------------

    @Test
    @DisplayName("上一节点指定：最多只能有一条该规则")
    void prevAssign_rejectsMultipleRules() {
        FlowNode node = approval("n3", "待指定", "ANY_SIGN", "end",
                prevAssignRule(1), prevAssignRule(1));
        FlowDefinition definition = new FlowDefinition();
        definition.setStart("n1");
        definition.setNodes(new ArrayList<>(List.of(
                approval("n1", "主管审批", "ANY_SIGN", "n3", roleRule("admin")),
                node,
                end("end", "结束")
        )));
        assertTrue(invalid(definition, SCHEMA).contains("最多只能有 1 条"));
    }

    @Test
    @DisplayName("上一节点指定：必须是该节点唯一的审批人规则")
    void prevAssign_mustBeSoleRule() {
        FlowNode node = approval("n3", "待指定", "ANY_SIGN", "end",
                prevAssignRule(1), roleRule("admin"));
        FlowDefinition definition = new FlowDefinition();
        definition.setStart("n1");
        definition.setNodes(new ArrayList<>(List.of(
                approval("n1", "主管审批", "ANY_SIGN", "n3", roleRule("admin")),
                node,
                end("end", "结束")
        )));
        assertTrue(invalid(definition, SCHEMA).contains("必须是该节点唯一的审批人规则"));
    }

    @Test
    @DisplayName("上一节点指定：只支持或签，不能配会签")
    void prevAssign_rejectsAllSign() {
        FlowNode node = approval("n3", "待指定", "ALL_SIGN", "end", prevAssignRule(2));
        FlowDefinition definition = new FlowDefinition();
        definition.setStart("n1");
        definition.setNodes(new ArrayList<>(List.of(
                approval("n1", "主管审批", "ANY_SIGN", "n3", roleRule("admin")),
                node,
                end("end", "结束")
        )));
        assertTrue(invalid(definition, SCHEMA).contains("只支持或签"));
    }

    @Test
    @DisplayName("上一节点指定：前面必须有审批节点可供指定")
    void prevAssign_requiresPrecedingApproval() {
        // 抄送不是审批节点，不构成「上一节点」——所以它不构成屏障
        FlowDefinition definition = new FlowDefinition();
        definition.setStart("cc1");
        definition.setNodes(new ArrayList<>(List.of(
                cc("cc1", "抄送管理员", "n1", roleRule("admin")),
                approval("n1", "待指定", "ANY_SIGN", "end", prevAssignRule(1)),
                end("end", "结束")
        )));
        assertTrue(invalid(definition, SCHEMA).contains("前面没有审批节点"),
                "抄送不能作为「上一节点」——否则运行时根本找不到可指定的人");
    }

    // ------------------------------------------------------------------
    // 审批时限
    // ------------------------------------------------------------------

    @Test
    @DisplayName("审批时限：必须在 1..720 小时之间")
    void timeLimit_outOfRangeRejected() {
        for (int bad : new int[] {0, -1, 721}) {
            FlowDefinition definition = copyOf(regressionFlow());
            find(definition, "n1").setTimeLimitHours(bad);
            assertTrue(invalid(definition, SCHEMA).contains("审批时限必须在 1..720 小时之间"),
                    "时限 " + bad + " 应被拒绝");
        }
    }

    @Test
    @DisplayName("审批时限：边界值 1 与 720 合法，未配置（null）表示不限时")
    void timeLimit_boundariesAccepted() {
        FlowDefinition low = copyOf(regressionFlow());
        find(low, "n1").setTimeLimitHours(1);
        assertDoesNotThrow(() -> FlowDefinitionValidator.validate(low, SCHEMA));

        FlowDefinition high = copyOf(regressionFlow());
        find(high, "n1").setTimeLimitHours(720);
        assertDoesNotThrow(() -> FlowDefinitionValidator.validate(high, SCHEMA));

        FlowDefinition none = copyOf(regressionFlow());
        find(none, "n1").setTimeLimitHours(null);
        assertDoesNotThrow(() -> FlowDefinitionValidator.validate(none, SCHEMA));
    }

    // ------------------------------------------------------------------
    // 路径求值：抄送落在链上、时限随节点携带
    // ------------------------------------------------------------------

    @Test
    @DisplayName("路径求值：抄送节点落在命中路径上，并携带 nodeType 与时限")
    void resolver_carriesCcAndTimeLimit() {
        FlowPathResolver.Result result = FlowPathResolver.resolve(wave2Flow(), SCHEMA,
                new LinkedHashMap<>(Map.of("amount", 8000)));

        List<String> onPath = result.onPathNodes().stream()
                .map(FlowPathResolver.ResolvedNode::nodeKey).toList();
        assertEquals(List.of("n1", "cc1", "n2", "n3"), onPath,
                "金额 8000 应命中 n1 → 抄送 → 直属领导审批 → 上一节点指定");

        FlowPathResolver.ResolvedNode ccNode = result.onPathNodes().stream()
                .filter(FlowPathResolver.ResolvedNode::isCc).findFirst().orElseThrow();
        assertEquals("cc1", ccNode.nodeKey());
        assertFalse(ccNode.isApproval(), "抄送不是审批节点");

        FlowPathResolver.ResolvedNode n1 = result.onPathNodes().stream()
                .filter(node -> "n1".equals(node.nodeKey())).findFirst().orElseThrow();
        assertEquals(24, n1.timeLimitHours().intValue(), "n1 的 24 小时时限必须随节点带出");
        assertNull(find(wave2Flow(), "n3").getTimeLimitHours(), "未配时限的节点为 null");

        FlowPathResolver.ResolvedNode n2 = result.onPathNodes().stream()
                .filter(node -> "n2".equals(node.nodeKey())).findFirst().orElseThrow();
        assertEquals(48, n2.timeLimitHours().intValue());
    }

    @Test
    @DisplayName("路径求值：走 else 分支时，直属领导节点被跳过并带出原因")
    void resolver_skipsLeaderOnElseBranch() {
        FlowPathResolver.Result result = FlowPathResolver.resolve(wave2Flow(), SCHEMA,
                new LinkedHashMap<>(Map.of("amount", 100)));

        List<String> onPath = result.onPathNodes().stream()
                .map(FlowPathResolver.ResolvedNode::nodeKey).toList();
        assertEquals(List.of("n1", "cc1", "n3"), onPath);

        FlowPathResolver.ResolvedNode n2 = result.nodes().stream()
                .filter(node -> "n2".equals(node.nodeKey())).findFirst().orElseThrow();
        assertFalse(n2.onPath());
        assertTrue(n2.conditionDesc() != null && n2.conditionDesc().contains("未命中"),
                "被跳过的节点必须能解释「为什么没走这条分支」，否则详情页无法说明");
    }

    @Test
    @DisplayName("路径求值：抄送与审批共享同一条 stepOrder 序列，互不重号")
    void resolver_stepOrderUnique() {
        FlowPathResolver.Result result = FlowPathResolver.resolve(wave2Flow(), SCHEMA,
                new LinkedHashMap<>(Map.of("amount", 8000)));
        List<Integer> steps = result.onPathNodes().stream()
                .map(FlowPathResolver.ResolvedNode::stepOrder).toList();
        assertEquals(steps.size(), steps.stream().distinct().count(),
                "stepOrder 是审批推进的排序依据，重号会让审批顺序错乱");
    }

    // ------------------------------------------------------------------
    // 直属领导规则：类型与语义（解析本体需要 DB，这里只验证规则被正确识别）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("直属领导规则：可作为审批节点的审批人来源且通过校验")
    void leaderRule_accepted() {
        FlowDefinition definition = copyOf(regressionFlow());
        find(definition, "n3").setApproverRules(new ArrayList<>(List.of(leaderRule())));
        assertDoesNotThrow(() -> FlowDefinitionValidator.validate(definition, SCHEMA));
    }
}
