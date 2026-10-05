package com.enterprise.ticket.common.flow;

import com.enterprise.ticket.common.constant.ApprovalNodeStatus;
import com.enterprise.ticket.common.constant.FormFieldType;
import com.enterprise.ticket.common.form.FormSchema;
import com.enterprise.ticket.module.order.entity.OrderApprovalNode;
import com.enterprise.ticket.module.order.support.OrderApprovalNodeSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.enterprise.ticket.common.flow.FlowTestFixtures.approval;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.branch;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.cond;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.condition;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.elseBranch;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.end;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.field;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.prevAssignRule;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.roleRule;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.rule;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.schema;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「stepOrder 口径 → 当前步骤判定 → 上一步指定审批人」这条链的契约测试
 * （Phase 16 Wave 4 · W4-C.5）。
 *
 * <h2>为什么单独成类</h2>
 * <p>W4-C 上报的 stepOrder 顺序缺陷，最直接的业务后果<b>不是</b>界面上的先后，
 * 而是「最小含 PENDING 的 step」选错了节点 —— 而 {@code applyNextStepAssignment}（PREV_ASSIGN）
 * 正是靠 {@link OrderApprovalNodeSupport#currentStepOrder} 找到「该由谁指定下一节点审批人」的。
 * 选错节点意味着：需要指派的占位行拿不到指派，同时<b>不需要指派</b>的汇合点被要求指派，
 * 表现为「上一节点通过时报错说本步骤不需要指定审批人」这种无从下手的提示。
 *
 * <p>因此这里刻意跨模块断言：左侧是纯函数求值器算出的编号（{@code FlowPathResolver}），
 * 右侧是任务侧真正用来定步骤的判定（{@code OrderApprovalNodeSupport}）——
 * 只测其中一侧都看不出来这个问题。
 *
 * <p>纯逻辑，不依赖 Spring / 数据库 / Mapper。
 */
class FlowStepOrderContractTest {

    private static final FormSchema SCHEMA =
            schema(field("amount", "金额", FormFieldType.NUMBER.name()));

    /**
     * 缺陷拓扑：条件的两条分支汇合到 {@code n4}，且 else 出口是
     * <b>「部门经理审批」（PREV_ASSIGN 占位节点）</b> → 汇合点。
     *
     * <p>三者同时成立才触发旧口径的错序：≥2 分支 ＋ 非首条分支上有节点 ＋ 与首条分支汇合。
     */
    private static FlowDefinition elseChainFlowWithPrevAssign() {
        FlowDefinition definition = new FlowDefinition();
        definition.setStart("n1");
        definition.setNodes(new ArrayList<>(List.of(
                approval("n1", "主管审批", "ANY_SIGN", "c1", roleRule("admin")),
                condition("c1", "金额判断",
                        branch("b1", "金额大于5000", "n2", cond("AND", rule("amount", "GT", "5000"))),
                        elseBranch("b2", "其它情况", "n3")),
                approval("n2", "财务复核", "ANY_SIGN", "n4", roleRule("admin")),
                approval("n3", "部门经理审批", "ANY_SIGN", "n4", prevAssignRule(1)),
                approval("n4", "归档确认", "ANY_SIGN", "end", roleRule("admin")),
                end("end", "结束")
        )));
        return definition;
    }

    @Test
    @DisplayName("走 else：当前步骤必须是 else 链上的占位节点，而不是汇合点（否则 PREV_ASSIGN 指派落空）")
    void currentStepOnElsePath_isThePlaceholderNodeNotTheConvergencePoint() {
        FlowPathResolver.Result result = FlowPathResolver.resolve(
                elseChainFlowWithPrevAssign(), SCHEMA, Map.of("amount", 100));

        Map<String, Integer> steps = new LinkedHashMap<>();
        result.nodes().forEach(node -> steps.put(node.nodeKey(), node.stepOrder()));
        assertEquals(1, steps.get("n1").intValue());
        assertEquals(2, steps.get("n2").intValue(), "被跳过的 n2 仍占号");
        assertEquals(3, steps.get("n3").intValue());
        assertEquals(4, steps.get("n4").intValue(), "汇合点必须在两条分支之后");

        // n1 已通过后的行快照：n2 被条件绕开（SKIPPED）、n3/n4 待审
        List<OrderApprovalNode> rows = rowsAfterFirstApproval(steps);
        Integer current = OrderApprovalNodeSupport.currentStepOrder(rows);
        assertEquals(steps.get("n3"), current, "当前步骤是「部门经理审批」（占位行）");

        List<OrderApprovalNode> currentRows = rows.stream()
                .filter(row -> Objects.equals(row.getStepOrder(), current))
                .filter(row -> ApprovalNodeStatus.PENDING.name().equals(row.getStatus()))
                .toList();
        assertEquals(List.of("n3"), currentRows.stream().map(OrderApprovalNode::getNodeKey).toList());
        assertTrue(currentRows.stream().allMatch(row -> row.getApproverId() == null),
                "当前步骤全是「待上一节点指定」的占位行 —— 上一步通过时会被要求指定审批人");
    }

    /**
     * 反例（同一份行数据，只把编号换成旧口径 {@code n1=1,n2=2,n4=3,n3=4}）：
     * 当前步骤落到汇合点 {@code n4} 上，占位行 {@code n3} 反而被跳过。
     *
     * <p>保留这条不是为了"测缺陷"，而是让「上一条用例为什么必须存在」有据可查 ——
     * 它同时证明上一条断言对本类偏差是<b>敏感</b>的（而不是恒绿的摆设）。
     */
    @Test
    @DisplayName("反例：编号退回 DFS 前序时，当前步骤落到汇合点、占位行拿不到指派")
    void legacyDfsNumbering_putsTheConvergencePointFirst() {
        Map<String, Integer> legacy = new LinkedHashMap<>();
        legacy.put("n1", 1);
        legacy.put("n2", 2);
        legacy.put("n4", 3);   // 旧口径：首条分支的整条链（含汇合点）先编号
        legacy.put("n3", 4);

        List<OrderApprovalNode> rows = rowsAfterFirstApproval(legacy);
        Integer current = OrderApprovalNodeSupport.currentStepOrder(rows);
        assertEquals(3, current);

        List<OrderApprovalNode> currentRows = rows.stream()
                .filter(row -> Objects.equals(row.getStepOrder(), current))
                .filter(row -> ApprovalNodeStatus.PENDING.name().equals(row.getStatus()))
                .toList();
        assertEquals(List.of("n4"), currentRows.stream().map(OrderApprovalNode::getNodeKey).toList(),
                "旧口径下当前步骤是汇合点 —— 与设计器上画的先后相反");
        assertTrue(currentRows.stream().noneMatch(row -> row.getApproverId() == null),
                "于是占位行既不是当前步骤、也拿不到指派");
    }

    /** n1 已通过、n2 被绕开（SKIPPED）、n3/n4 待审的行快照（与提交物化的落库形态同构） */
    private static List<OrderApprovalNode> rowsAfterFirstApproval(Map<String, Integer> steps) {
        return new ArrayList<>(Arrays.asList(
                node("n1", steps.get("n1"), ApprovalNodeStatus.APPROVED.name(), 7L),
                node("n2", steps.get("n2"), ApprovalNodeStatus.SKIPPED.name(), null),
                node("n3", steps.get("n3"), ApprovalNodeStatus.PENDING.name(), null),
                node("n4", steps.get("n4"), ApprovalNodeStatus.PENDING.name(), 7L)));
    }

    private static OrderApprovalNode node(String nodeKey, Integer stepOrder, String status, Long approverId) {
        OrderApprovalNode node = new OrderApprovalNode();
        node.setId((long) (stepOrder == null ? 0 : stepOrder));
        node.setOrderId(1L);
        node.setNodeKey(nodeKey);
        node.setNodeName(nodeKey);
        node.setNodeType(FlowNodeType.APPROVAL.name());
        node.setStepOrder(stepOrder);
        node.setStatus(status);
        node.setApproverId(approverId);
        return node;
    }
}
