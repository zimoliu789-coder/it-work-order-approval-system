package com.enterprise.ticket.common.flow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.enterprise.ticket.common.flow.FlowTestFixtures.approval;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.branch;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.cond;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.condition;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.elseBranch;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.end;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.regressionFlow;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.roleRule;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.rule;
import static com.enterprise.ticket.common.flow.FlowTestFixtures.specificUserRule;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运行期动作模型单元测试（Phase 16 Wave 2 · M2）。
 *
 * <p>覆盖三类"配置写错会静默失效"的地方，它们的共同点是<b>错误配置在界面上都点得出来</b>：
 * <ul>
 *   <li>{@link NodeActivation} 的汇聚优先级 —— 写反会让"会走"的节点被判成"不走"；</li>
 *   <li>{@link RejectAction} / {@link TimeoutAction} 的合法性 —— 未知 action 若被静默当成默认值，
 *       配置者会以为生效了，而实际每笔都在走另一条路；</li>
 *   <li>{@link FlowDefinition#hasRuntimeFeature()} —— 它是"要不要走新代码路径"的唯一判据，
 *       三处（发布闸门 / 提交物化 / 校验器）必须给出同一答案。</li>
 * </ul>
 */
class FlowRuntimeActionTest {

    // ------------------------------------------------------------------
    // NodeActivation 汇聚优先级
    // ------------------------------------------------------------------

    @Test
    @DisplayName("激活态汇聚：ACTIVE > INACTIVE > SKIPPED，与顺序无关")
    void nodeActivation_strongerIsOrderIndependent() {
        assertEquals(NodeActivation.ACTIVE, NodeActivation.ACTIVE.stronger(NodeActivation.INACTIVE));
        assertEquals(NodeActivation.ACTIVE, NodeActivation.INACTIVE.stronger(NodeActivation.ACTIVE));
        assertEquals(NodeActivation.INACTIVE, NodeActivation.INACTIVE.stronger(NodeActivation.SKIPPED));
        assertEquals(NodeActivation.INACTIVE, NodeActivation.SKIPPED.stronger(NodeActivation.INACTIVE));
        assertEquals(NodeActivation.ACTIVE, NodeActivation.SKIPPED.stronger(NodeActivation.ACTIVE));
        assertEquals(NodeActivation.SKIPPED, NodeActivation.SKIPPED.stronger(NodeActivation.SKIPPED));
    }

    @Test
    @DisplayName("激活态汇聚：与 null 相比返回自身")
    void nodeActivation_strongerNull() {
        assertEquals(NodeActivation.ACTIVE, NodeActivation.ACTIVE.stronger(null));
        assertEquals(NodeActivation.SKIPPED, NodeActivation.SKIPPED.stronger(null));
    }

    // ------------------------------------------------------------------
    // RejectAction
    // ------------------------------------------------------------------

    @Test
    @DisplayName("驳回动作：默认 TERMINATE，与第二期一致（零回归的默认值）")
    void rejectAction_defaultIsTerminate() {
        RejectAction action = new RejectAction();
        assertEquals(RejectAction.TERMINATE, action.getAction());
        assertTrue(action.isTerminate());
        assertFalse(action.isGoto());
        assertTrue(action.isValid());
    }

    @Test
    @DisplayName("驳回动作：GOTO 必须带目标，且目标为空即非法")
    void rejectAction_gotoRequiresTarget() {
        RejectAction action = new RejectAction();
        action.setAction(RejectAction.GOTO);
        assertFalse(action.isValid(), "缺 target 的 GOTO 必须被校验器拒掉，否则运行期会退回整单驳回而配置者不知");

        action.setTarget("n2");
        assertTrue(action.isGoto());
        assertTrue(action.isValid());
    }

    @Test
    @DisplayName("驳回动作：未知 action（如拼错的 GOT）必须判为非法，不得静默降级")
    void rejectAction_unknownActionIsInvalid() {
        RejectAction action = new RejectAction();
        action.setAction("GOT");
        assertFalse(action.isValid(),
                "「看起来在工作」的错配比直接报错危险得多 —— 每笔驳回都在终止整单，却以为改道生效了");
    }

    // ------------------------------------------------------------------
    // TimeoutAction
    // ------------------------------------------------------------------

    @Test
    @DisplayName("超时动作：默认 NOTIFY（仅提醒），与第二期一致")
    void timeoutAction_defaultIsNotify() {
        TimeoutAction action = new TimeoutAction();
        assertTrue(action.isNotifyOnly());
        assertFalse(action.isAddSign());
        assertFalse(action.isGoto());
        assertTrue(action.isValid());
        assertTrue(action.getApprovers().isEmpty(), "未配置加签人时返回空列表（继承原节点规则），不是 null");
    }

    @Test
    @DisplayName("超时动作：ADD_SIGN 合法；afterHours 非正数非法")
    void timeoutAction_addSign() {
        TimeoutAction action = new TimeoutAction();
        action.setAction(TimeoutAction.ADD_SIGN);
        assertTrue(action.isAddSign());
        assertTrue(action.isValid());

        action.setAfterHours(0);
        assertFalse(action.isValid());
        action.setAfterHours(-1);
        assertFalse(action.isValid());
        action.setAfterHours(48);
        assertTrue(action.isValid());
    }

    @Test
    @DisplayName("超时动作：GOTO 必须带目标；未知 action 非法")
    void timeoutAction_gotoAndUnknown() {
        TimeoutAction action = new TimeoutAction();
        action.setAction(TimeoutAction.GOTO);
        assertFalse(action.isValid());

        action.setTarget("n2");
        assertTrue(action.isGoto());
        assertTrue(action.isValid());

        action.setAction("SKIP");
        assertFalse(action.isValid());
    }

    @Test
    @DisplayName("超时动作：显式加签人可配置（空则继承，非空则完全替换）")
    void timeoutAction_explicitApprovers() {
        TimeoutAction action = new TimeoutAction();
        action.setAction(TimeoutAction.ADD_SIGN);
        action.setApprovers(new ArrayList<>(List.of(specificUserRule(9L))));
        assertEquals(1, action.getApprovers().size());
        assertTrue(action.isValid());
    }

    // ------------------------------------------------------------------
    // 运行期特性的唯一判据
    // ------------------------------------------------------------------

    @Test
    @DisplayName("节点运行期动作：仅 GOTO 驳回 / ADD_SIGN·GOTO 超时才算运行期特性")
    void nodeHasRuntimeAction() {
        assertFalse(approval("n1", "审批", "ANY_SIGN", "end", roleRule("admin")).hasRuntimeAction(),
                "未配置任何运行期动作 → 非运行期节点（存量定义全部如此）");

        FlowNode terminate = approval("n1", "审批", "ANY_SIGN", "end", roleRule("admin"));
        RejectAction term = new RejectAction();
        term.setAction(RejectAction.TERMINATE);
        terminate.setOnReject(term);
        assertFalse(terminate.hasRuntimeAction(), "驳回=TERMINATE 是第二期既有行为，不算运行期特性");

        FlowNode gotoReject = approval("n1", "审批", "ANY_SIGN", "end", roleRule("admin"));
        RejectAction go = new RejectAction();
        go.setAction(RejectAction.GOTO);
        go.setTarget("n2");
        gotoReject.setOnReject(go);
        assertTrue(gotoReject.hasRuntimeAction());

        FlowNode notifyTimeout = approval("n1", "审批", "ANY_SIGN", "end", roleRule("admin"));
        TimeoutAction notify = new TimeoutAction();
        notify.setAction(TimeoutAction.NOTIFY);
        notifyTimeout.setOnTimeout(notify);
        assertFalse(notifyTimeout.hasRuntimeAction(), "仅提醒是第二期既有行为");

        FlowNode addSign = approval("n1", "审批", "ANY_SIGN", "end", roleRule("admin"));
        TimeoutAction add = new TimeoutAction();
        add.setAction(TimeoutAction.ADD_SIGN);
        addSign.setOnTimeout(add);
        assertTrue(addSign.hasRuntimeAction());
    }

    @Test
    @DisplayName("定义运行期特性：存量流程为 false；含改道 / 含 process.* 条件为 true")
    void definitionHasRuntimeFeature() {
        assertFalse(regressionFlow().hasRuntimeFeature(),
                "存量（第二期）流程定义必须判为非运行期 —— 这是零回归的判据");

        // 仅把超时动作换成 ADD_SIGN，其它一字不动
        FlowDefinition withTimeout = FlowTestFixtures.copyOf(regressionFlow());
        TimeoutAction add = new TimeoutAction();
        add.setAction(TimeoutAction.ADD_SIGN);
        FlowTestFixtures.find(withTimeout, "n1").setOnTimeout(add);
        assertTrue(withTimeout.hasRuntimeFeature());

        // 仅把条件字段换成运行期字段
        FlowDefinition withProcessCondition = new FlowDefinition();
        withProcessCondition.setStart("n1");
        withProcessCondition.setNodes(new ArrayList<>(List.of(
                approval("n1", "主管审批", "ANY_SIGN", "c1", roleRule("admin")),
                condition("c1", "是否被驳回过",
                        branch("b1", "曾被驳回", "n2", cond("AND",
                                rule(ProcessFieldCatalog.REJECT_COUNT, "GT", "0"))),
                        elseBranch("b2", "顺利", "n3")),
                approval("n2", "复核", "ANY_SIGN", "end", roleRule("admin")),
                approval("n3", "归档", "ANY_SIGN", "end", roleRule("admin")),
                end("end", "结束")
        )));
        assertTrue(withProcessCondition.hasRuntimeFeature());
    }

    // ------------------------------------------------------------------
    // 向后兼容：既有访问器语义不变
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ResolvedNode.onPath()：等价于 activation == ACTIVE（第二期调用方零改动）")
    void resolvedNodeOnPathBackwardCompatible() {
        FlowPathResolver.Result result = FlowPathResolver.resolve(regressionFlow(), null,
                new java.util.LinkedHashMap<>(java.util.Map.of("amount", 8000)));
        for (FlowPathResolver.ResolvedNode node : result.nodes()) {
            assertEquals(node.activation() == NodeActivation.ACTIVE, node.onPath(), node.nodeKey());
        }
        assertDoesNotThrow(() -> result.inactiveNodes());
    }
}
