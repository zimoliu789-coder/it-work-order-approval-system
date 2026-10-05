package com.enterprise.ticket.module.order.support;

import com.enterprise.ticket.common.flow.ApproverRule;
import com.enterprise.ticket.common.flow.ApproverRuleType;
import com.enterprise.ticket.common.flow.BorrowFlowCatalog;
import com.enterprise.ticket.common.flow.FlowDefinitionCodec;
import com.enterprise.ticket.module.order.entity.OrderApprovalNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「待上一节点指定审批人」解析的单测（Phase 19 批次 D）。
 *
 * <h2>为什么测这个类而不是测 {@code OrderServiceImpl}</h2>
 * 原实现是 {@code OrderServiceImpl} 里的两段私有代码：一段匿名 stream 过滤 +
 * 一段从流程快照反查规则的 try/catch。它们被三个调用方共用（审批写入、候选接口、
 * 范围校验），却没有任何直接覆盖 —— 因为 {@code OrderServiceImpl} 的依赖太重，
 * 为查一个 null 判空去造十个 mock 不划算。抽成本类之后，口径本身就能被钉死。
 */
class PendingAssignSupportTest {

    private static OrderApprovalNode node(Integer step, String status, Long approverId, String nodeKey) {
        OrderApprovalNode node = new OrderApprovalNode();
        node.setStepOrder(step);
        node.setStatus(status);
        node.setApproverId(approverId);
        node.setNodeKey(nodeKey);
        node.setNodeName("节点-" + nodeKey);
        return node;
    }

    @Nested
    @DisplayName("placeholders：只认「当前步骤 + PENDING + 无审批人」")
    class Placeholders {

        @Test
        @DisplayName("两类干扰行都必须排除：已指定好人（approverId 非空）与已处理（非 PENDING）")
        void filtersWrongRows() {
            OrderApprovalNode todo = node(3, "PENDING", null, "it_executor_handle");
            OrderApprovalNode assigned = node(3, "PENDING", 10001L, "it_executor_handle");
            OrderApprovalNode handled = node(3, "APPROVED", null, "it_executor_handle");
            OrderApprovalNode otherStep = node(2, "PENDING", null, "it_manager_approval");

            assertThat(PendingAssignSupport.placeholders(
                    List.of(todo, assigned, handled, otherStep), 3))
                    .containsExactly(todo);
        }

        @Test
        @DisplayName("同一步骤有两行占位时全部返回（会签人数 = 行数，前端据此要求选 2 人）")
        void returnsAllRowsOfTheStep() {
            OrderApprovalNode first = node(3, "PENDING", null, "it_executor_handle");
            OrderApprovalNode second = node(3, "PENDING", null, "it_executor_handle");

            assertThat(PendingAssignSupport.placeholders(List.of(first, second), 3))
                    .containsExactly(first, second);
        }

        @Test
        @DisplayName("stepOrder 为 null（工单没有待处理步骤）返回空而不是抛错")
        void nullStepYieldsEmpty() {
            assertThat(PendingAssignSupport.placeholders(
                    List.of(node(3, "PENDING", null, "k")), null)).isEmpty();
        }

        @Test
        @DisplayName("节点列表为 null / 空返回空")
        void emptyNodesYieldEmpty() {
            assertThat(PendingAssignSupport.placeholders(null, 3)).isEmpty();
            assertThat(PendingAssignSupport.placeholders(List.of(), 3)).isEmpty();
        }

        @Test
        @DisplayName("列表里的 null 元素不影响其余行的判定")
        void ignoresNullElements() {
            OrderApprovalNode todo = node(3, "PENDING", null, "k");
            assertThat(PendingAssignSupport.placeholders(
                    java.util.Arrays.asList(null, todo), 3)).containsExactly(todo);
        }
    }

    @Nested
    @DisplayName("placeholdersAfterMyApproval：站在「我这一票还没投」的视角做预测")
    class AfterMyApproval {

        @Test
        @DisplayName("下一步是待指派占位行 → 返回它（前端据此显示选择器；否则工单会静默卡死）")
        void predictsThePlaceholder() {
            OrderApprovalNode dmDone = node(0, "APPROVED", 4L, BorrowFlowCatalog.NODE_DIRECT_MANAGER);
            OrderApprovalNode itManager = node(3, "PENDING", 9L, BorrowFlowCatalog.NODE_IT_MANAGER);
            OrderApprovalNode placeholder = node(4, "PENDING", null, BorrowFlowCatalog.NODE_IT_EXECUTOR);

            assertThat(PendingAssignSupport.placeholdersAfterMyApproval(
                    List.of(dmDone, itManager, placeholder), 9L))
                    .containsExactly(placeholder);
        }

        @Test
        @DisplayName("INACTIVE 的中间步骤不构成「下一步」：条件未命中的节点不能把人挡在前面")
        void skipsInactiveSteps() {
            OrderApprovalNode dmDone = node(0, "APPROVED", 4L, BorrowFlowCatalog.NODE_DIRECT_MANAGER);
            OrderApprovalNode skipped = node(2, "INACTIVE", null, BorrowFlowCatalog.NODE_PARENT_DEPT_MANAGER);
            OrderApprovalNode itManager = node(3, "PENDING", 9L, BorrowFlowCatalog.NODE_IT_MANAGER);
            OrderApprovalNode placeholder = node(4, "PENDING", null, BorrowFlowCatalog.NODE_IT_EXECUTOR);
            List<OrderApprovalNode> nodes = List.of(dmDone, skipped, itManager, placeholder);

            assertThat(PendingAssignSupport.placeholdersAfterMyApproval(nodes, 9L))
                    .as("IT主管通过后才轮到待指派")
                    .containsExactly(placeholder);
            assertThat(PendingAssignSupport.placeholdersAfterMyApproval(nodes, 4L))
                    .as("直属主管通过后直接进 IT主管（有人），不该要点名")
                    .isEmpty();
        }

        @Test
        @DisplayName("会签：同一步骤还有别人没批 → 不能提前要点名（与服务端「我不是最后一个」的判断一致）")
        void countsOnlyWhenIAmTheLastSigner() {
            OrderApprovalNode mate = node(1, "PENDING", 10002L, "n1");
            OrderApprovalNode me = node(1, "PENDING", 10001L, "n1");
            OrderApprovalNode placeholder = node(2, "PENDING", null, "it_executor_handle");
            List<OrderApprovalNode> nodes = List.of(mate, me, placeholder);

            assertThat(PendingAssignSupport.placeholdersAfterMyApproval(nodes, 10001L))
                    .as("我还不是最后一个，本步骤不会推进")
                    .isEmpty();

            OrderApprovalNode mateDone = node(1, "APPROVED", 10002L, "n1");
            assertThat(PendingAssignSupport.placeholdersAfterMyApproval(
                    List.of(mateDone, me, placeholder), 10001L))
                    .as("只剩我一票时，通过后就会推进到待指派步骤")
                    .containsExactly(placeholder);
        }

        @Test
        @DisplayName("我只是旁观者（不是本步骤审批人）：不应误报「要你点名」")
        void noFalsePositiveForBystander() {
            OrderApprovalNode someoneElse = node(1, "PENDING", 10001L, "n1");
            OrderApprovalNode placeholder = node(2, "PENDING", null, "it_executor_handle");

            assertThat(PendingAssignSupport.placeholdersAfterMyApproval(
                    List.of(someoneElse, placeholder), 99999L))
                    .isEmpty();
        }

        @Test
        @DisplayName("同一个人在后面步骤还有一票时，只排除他在**当前步骤**的那一行 —— "
                + "否则预测会一路后跳，漏掉真正等待指派的节点")
        void doesNotSkipMyLaterRow() {
            OrderApprovalNode mineNow = node(1, "PENDING", 7L, "n1");
            OrderApprovalNode placeholder = node(2, "PENDING", null, "it_executor_handle");
            // 同一人作为「被指定者」已经在第 3 步落了一行（PENDING），
            // 它不归当前步骤管，抹掉它会让 min PENDING 跳到不存在的第 4 步
            OrderApprovalNode mineLater = node(3, "PENDING", 7L, "n3");

            assertThat(PendingAssignSupport.placeholdersAfterMyApproval(
                    List.of(mineNow, placeholder, mineLater), 7L))
                    .containsExactly(placeholder);
        }

        @Test
        @DisplayName("与 applyNextStepAssignment 的口径一致：审批前的预测 == 审批后的实测")
        void matchesPostApprovalView() {
            OrderApprovalNode mine = node(3, "PENDING", 9L, BorrowFlowCatalog.NODE_IT_MANAGER);
            OrderApprovalNode placeholder = node(4, "PENDING", null, BorrowFlowCatalog.NODE_IT_EXECUTOR);

            List<OrderApprovalNode> before = List.of(mine, placeholder);
            OrderApprovalNode approved = node(3, "APPROVED", 9L, BorrowFlowCatalog.NODE_IT_MANAGER);
            List<OrderApprovalNode> after = List.of(approved, placeholder);

            assertThat(PendingAssignSupport.placeholdersAfterMyApproval(before, 9L))
                    .isEqualTo(PendingAssignSupport.placeholders(
                            after, OrderApprovalNodeSupport.currentStepOrder(after)));
        }

        @Test
        @DisplayName("没有待处理步骤（全部终态）返回空而不是抛错")
        void noPendingStepYieldsEmpty() {
            assertThat(PendingAssignSupport.placeholdersAfterMyApproval(
                    List.of(node(0, "APPROVED", 4L, "n1")), 4L)).isEmpty();
            assertThat(PendingAssignSupport.placeholdersAfterMyApproval(null, 4L)).isEmpty();
        }
    }

    @Nested
    @DisplayName("prevAssignRuleOf：读不出来一律降级为「不限制」")
    class PrevAssignRule {

        @Test
        @DisplayName("预置流程的执行人节点上取到 PREV_ASSIGN 规则，且范围是 IT_EXECUTOR")
        void readsPresetExecutorRule() {
            String json = FlowDefinitionCodec.write(BorrowFlowCatalog.preset(new BigDecimal("5000")));
            OrderApprovalNode placeholder =
                    node(4, "PENDING", null, BorrowFlowCatalog.NODE_IT_EXECUTOR);

            ApproverRule rule = PendingAssignSupport.prevAssignRuleOf(json, placeholder);

            assertThat(rule).isNotNull();
            assertThat(ApproverRuleType.of(rule.getType())).isEqualTo(ApproverRuleType.PREV_ASSIGN);
            assertThat(PendingAssignSupport.assignScopeOf(rule))
                    .isEqualTo(ApproverRuleType.AssignScope.IT_EXECUTOR);
        }

        @Test
        @DisplayName("节点上没有 PREV_ASSIGN 规则时返回 null（普通审批节点不该被要求点名）")
        void returnsNullWhenNodeHasNoPrevAssign() {
            String json = FlowDefinitionCodec.write(BorrowFlowCatalog.preset(new BigDecimal("5000")));
            OrderApprovalNode placeholder =
                    node(2, "PENDING", null, BorrowFlowCatalog.NODE_DIRECT_MANAGER);

            assertThat(PendingAssignSupport.prevAssignRuleOf(json, placeholder)).isNull();
        }

        @Test
        @DisplayName("占位行没有 node_key（非流程模式）返回 null")
        void returnsNullWithoutNodeKey() {
            String json = FlowDefinitionCodec.write(BorrowFlowCatalog.preset(new BigDecimal("5000")));

            assertThat(PendingAssignSupport.prevAssignRuleOf(json, node(1, "PENDING", null, null)))
                    .isNull();
        }

        @Test
        @DisplayName("流程快照为空返回 null")
        void returnsNullWithoutFlowJson() {
            OrderApprovalNode placeholder = node(1, "PENDING", null, "any");

            assertThat(PendingAssignSupport.prevAssignRuleOf(null, placeholder)).isNull();
            assertThat(PendingAssignSupport.prevAssignRuleOf("   ", placeholder)).isNull();
        }

        @Test
        @DisplayName("占位行为 null 返回 null")
        void returnsNullForNullPlaceholder() {
            assertThat(PendingAssignSupport.prevAssignRuleOf("{}", null)).isNull();
        }

        @Test
        @DisplayName("快照损坏（非法 JSON）时降级为 null 而不是抛出 —— "
                + "损坏的快照不该让一笔卡在审批中的老工单整单无法继续")
        void degradesOnBrokenJson() {
            OrderApprovalNode placeholder = node(1, "PENDING", null, "any");

            assertThat(PendingAssignSupport.prevAssignRuleOf("{ this is not json", placeholder)).isNull();
        }

        @Test
        @DisplayName("快照里没有该 node_key 时返回 null")
        void returnsNullWhenNodeKeyAbsent() {
            String json = FlowDefinitionCodec.write(BorrowFlowCatalog.preset(new BigDecimal("5000")));
            OrderApprovalNode placeholder = node(1, "PENDING", null, "ghost_node_key");

            assertThat(PendingAssignSupport.prevAssignRuleOf(json, placeholder)).isNull();
        }
    }

    @Nested
    @DisplayName("assignScopeOf：缺省与非法值一律回落「不限制」")
    class AssignScopeFallback {

        @Test
        @DisplayName("规则为 null（读不出 PREV_ASSIGN）→ ALL")
        void nullRuleIsAll() {
            assertThat(PendingAssignSupport.assignScopeOf(null))
                    .isEqualTo(ApproverRuleType.AssignScope.ALL);
        }

        @Test
        @DisplayName("未配 assignScope（存量流程）→ ALL，不能因为新增参数就改变旧行为")
        void missingScopeIsAll() {
            ApproverRule rule = new ApproverRule();
            rule.setType(ApproverRuleType.PREV_ASSIGN.name());
            rule.setAssignCount(1);

            assertThat(PendingAssignSupport.assignScopeOf(rule))
                    .isEqualTo(ApproverRuleType.AssignScope.ALL);
        }

        @Test
        @DisplayName("空串 / 空白 → ALL")
        void blankScopeIsAll() {
            ApproverRule rule = new ApproverRule();
            rule.setAssignScope("");
            assertThat(PendingAssignSupport.assignScopeOf(rule))
                    .isEqualTo(ApproverRuleType.AssignScope.ALL);

            rule.setAssignScope("   ");
            assertThat(PendingAssignSupport.assignScopeOf(rule))
                    .isEqualTo(ApproverRuleType.AssignScope.ALL);
        }

        @Test
        @DisplayName("未知取值 → ALL（回落方向必须是「放开」：回落成 IT_EXECUTOR 会让"
                + "一份参数写错的存量流程在无人改动时开始拒绝指派）")
        void unknownScopeIsAll() {
            ApproverRule rule = new ApproverRule();
            rule.setAssignScope("IT_MANAGER");

            assertThat(PendingAssignSupport.assignScopeOf(rule))
                    .isEqualTo(ApproverRuleType.AssignScope.ALL);
        }

        @Test
        @DisplayName("显式 IT_EXECUTOR 被原样识别")
        void recognizesItExecutor() {
            ApproverRule rule = new ApproverRule();
            rule.setAssignScope("IT_EXECUTOR");

            assertThat(PendingAssignSupport.assignScopeOf(rule))
                    .isEqualTo(ApproverRuleType.AssignScope.IT_EXECUTOR);
        }
    }
}
