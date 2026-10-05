package com.enterprise.ticket.common.flow;

import org.springframework.util.StringUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 第三段：图结构校验（ ·  · W4-A1，自 {@link FlowDefinitionValidator} 拆出）。
 *
 * <h2>为什么这一段必须存在（而不是靠前两段顺带覆盖）</h2>
 * <p>流程定义是整体 JSON，数据库给不了"无环""可达""穷尽"这三类保证。一旦放过，
 * 运行时可能出现"走到一个永远到不了 END 的分支"—— 表现为工单卡在"待审批"再也推不动，
 * 且没有任何报错指向流程本身。发布时一次静态校验，就把这类问题挡在了产生数据之前。
 *
 * <h2>五个子检查 + 两项计数，次序即对外表现</h2>
 * <ol>
 *   <li>环（DFS 三色标记）；</li>
 *   <li>从 start 可达（BFS）；</li>
 *   <li>所有节点都能到达 END（反向 BFS）；</li>
 *   <li>{@code PREV_ASSIGN} 前面必须有审批节点（以 APPROVAL 为屏障的 BFS）；</li>
 *   <li>运行期条件前面必须有审批节点（同构的屏障 BFS）；</li>
 *   <li>至少一个审批节点；</li>
 *   <li>审批节点数不超上限。</li>
 * </ol>
 *
 * <p>前五项**互不短路**：谓词本身 null 安全，多报几条不同的图问题比只报第一条更有用。
 * 顺序由 {@code FlowValidatorProblemOrderTest} 锁定。
 *
 * <h2>为什么把计数也放在这里</h2>
 * <p>「至少一个审批节点」「审批节点数超上限」本质是对图规模的判断，与两项前置检查同属图段。
 * 而对外公开的 {@link FlowDefinitionValidator#countApprovalNodes} 仍留在门面上（发布路径要写
 * {@code node_count}），内部委托到本类，避免同一份计数逻辑出现两处实现。
 */
final class FlowGraphValidator {

    private FlowGraphValidator() {
    }

    /** 图段编排：五项检查 + 两项计数，次序不可调换 */
    static void validate(FlowDefinition definition, Map<String, FlowNode> index,
                         FlowProblemCollector collector) {
        detectCycle(definition.getStart(), index, collector);
        assertAllReachable(definition.getStart(), index, collector);
        assertAllLeadToEnd(index, collector);
        assertPrevAssignHasPrecedingApproval(definition.getStart(), index, collector);
        assertRuntimeConditionHasPrecedingApproval(definition.getStart(), index, collector);

        int approvalCount = countApprovalNodes(definition);
        if (approvalCount == 0) {
            collector.add("流程中至少要有一个审批节点");
        }
        if (approvalCount > FlowValidationLimits.MAX_NODES) {
            collector.add("审批节点数量超过上限 " + FlowValidationLimits.MAX_NODES);
        }
    }

    /** 审批节点数量（发布校验产物，写入 approval_flow_version.node_count） */
    static int countApprovalNodes(FlowDefinition definition) {
        if (definition == null) {
            return 0;
        }
        return (int) definition.getNodes().stream()
                .filter(node -> node != null && FlowNodeType.APPROVAL.name().equals(node.getType()))
                .count();
    }

    // ------------------------------------------------------------------
    // 图遍历基础设施
    // ------------------------------------------------------------------

    /** 出边：APPROVAL 与 CC 的 next，CONDITION 的各分支 next */
    private static List<String> outgoing(FlowNode node) {
        List<String> result = new ArrayList<>();
        if (node == null) {
            return result;
        }
        FlowNodeType type = node.typeEnum();
        if ((type == FlowNodeType.APPROVAL || type == FlowNodeType.CC)
                && StringUtils.hasText(node.getNext())) {
            result.add(node.getNext());
        }
        if (type == FlowNodeType.CONDITION) {
            for (FlowBranch branch : node.getBranches()) {
                if (branch != null && StringUtils.hasText(branch.getNext())) {
                    result.add(branch.getNext());
                }
            }
        }
        return result;
    }

    /**
     * 以「审批节点」为屏障，收集所有**未经审批即可到达**的节点。
     *
     * <p>这是 {@code PREV_ASSIGN} 前置检查与运行期条件前置检查**共用**的遍历：
     * 两者的图论形状完全相同（都需要知道"某个节点能不能在第一个审批步骤之前就被走到"），
     * 差异只在"看节点的哪个字段"。抽成一个方法而不是写两遍 BFS，
     * 是为了让两条约束永远不会因为一份漏改而分叉。
     */
    private static Set<String> reachableWithoutApproval(String start, Map<String, FlowNode> index) {
        Set<String> reachable = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(start);
        reachable.add(start);
        while (!queue.isEmpty()) {
            String key = queue.poll();
            FlowNode node = index.get(key);
            if (node == null) {
                continue;
            }
            // 审批节点是屏障：到达它就已经"经过了一个审批步骤"，不再往外扩散
            if (node.typeEnum() == FlowNodeType.APPROVAL) {
                continue;
            }
            for (String next : outgoing(node)) {
                if (reachable.add(next)) {
                    queue.add(next);
                }
            }
        }
        return reachable;
    }

    /**
     * 「上一节点审批人指定」的图级硬约束：该节点**前面必须至少有一个审批节点**。
     *
     * <p>判定方式：从 start 出发，把 APPROVAL 节点当作**屏障**（到达即不再展开），
     * 收集所有"未经审批就能到达"的节点。若其中任何一个自身带 {@code PREV_ASSIGN} 规则，
     * 说明存在一条路径让它成为**首个审批步骤** —— 此时"上一节点"根本不存在，无从指定，故拒绝。
     */
    private static void assertPrevAssignHasPrecedingApproval(String start, Map<String, FlowNode> index,
                                                             FlowProblemCollector collector) {
        for (String key : reachableWithoutApproval(start, index)) {
            FlowNode node = index.get(key);
            if (node == null || node.typeEnum() != FlowNodeType.APPROVAL) {
                continue;
            }
            boolean prevAssign = node.getApproverRules().stream()
                    .anyMatch(rule -> rule != null
                            && ApproverRuleType.of(rule.getType()) == ApproverRuleType.PREV_ASSIGN);
            if (prevAssign) {
                collector.add("节点「" + key + "」使用了「上一节点审批人指定」，但它前面没有审批节点，"
                        + "没有上一节点可供指定（请把它放在至少一个审批节点之后）");
            }
        }
    }

    /**
     * 运行期条件的图级硬约束（M2）：引用 {@code process.*} 的条件**前面必须至少有一个审批节点**。
     *
     * <h2>为什么必须有这条约束（它挡住的是一笔死单）</h2>
     * <p>运行期条件在提交时判不了，其下游会被物化为 {@code INACTIVE} 等待激活。
     * 而"激活"的触发点是<b>前一个节点完成</b>。若这样的条件排在第一个审批节点之前，
     * 提交后会出现：工单状态是"待审批"，但没有任何 PENDING 节点 ——
     * 没有一个待办能推动它，也没有任何操作能救活它。
     *
     * <p>换句话说：运行期条件需要一个"上一步"才能成立（它引用的正是"上一节点结果 / 已耗时"）。
     * 把一个需要"上一步"的条件放在第一步之前，本身就是自相矛盾的配置。
     *
     * <p>判定方式与 PREV_ASSIGN 那条完全对称：共用 {@link #reachableWithoutApproval}。
     */
    private static void assertRuntimeConditionHasPrecedingApproval(String start, Map<String, FlowNode> index,
                                                                  FlowProblemCollector collector) {
        for (String key : reachableWithoutApproval(start, index)) {
            FlowNode node = index.get(key);
            if (node == null || node.typeEnum() != FlowNodeType.CONDITION) {
                continue;
            }
            boolean runtimeDependent = node.getBranches().stream()
                    .anyMatch(branch -> branch != null
                            && ProcessFieldCatalog.isRuntimeDependent(branch.getCondition()));
            if (runtimeDependent) {
                collector.add("条件节点「" + key + "」引用了运行期字段（上一节点结果 / 已耗时等），"
                        + "但它前面没有审批节点，提交后将没有任何待办能推动它"
                        + "（请把它放在至少一个审批节点之后）");
            }
        }
    }

    // ------------------------------------------------------------------
    // 环 / 可达 / 到 END
    // ------------------------------------------------------------------

    private static void detectCycle(String start, Map<String, FlowNode> index,
                                    FlowProblemCollector collector) {
        // 0 未访问 / 1 在栈上 / 2 已完成
        Map<String, Integer> state = new HashMap<>();
        Deque<String> path = new ArrayDeque<>();
        dfsDetect(start, index, state, path, collector);
    }

    private static void dfsDetect(String key, Map<String, FlowNode> index,
                                  Map<String, Integer> state, Deque<String> path,
                                  FlowProblemCollector collector) {
        int current = state.getOrDefault(key, 0);
        if (current == 2) {
            return;
        }
        if (current == 1) {
            List<String> cycle = new ArrayList<>(path);
            cycle.add(key);
            collector.add("流程存在环：" + String.join(" → ", cycle));
            return;
        }
        state.put(key, 1);
        path.addLast(key);
        for (String next : outgoing(index.get(key))) {
            dfsDetect(next, index, state, path, collector);
        }
        path.removeLast();
        state.put(key, 2);
    }

    private static void assertAllReachable(String start, Map<String, FlowNode> index,
                                           FlowProblemCollector collector) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty()) {
            String key = queue.poll();
            for (String next : outgoing(index.get(key))) {
                if (seen.add(next)) {
                    queue.add(next);
                }
            }
        }
        List<String> unreachable = index.keySet().stream().filter(k -> !seen.contains(k)).toList();
        if (!unreachable.isEmpty()) {
            collector.add("存在从起始节点不可达的节点：" + String.join("、", unreachable));
        }
    }

    private static void assertAllLeadToEnd(Map<String, FlowNode> index, FlowProblemCollector collector) {
        // 反向边：能到达 END 的集合
        Map<String, List<String>> incoming = new LinkedHashMap<>();
        index.keySet().forEach(k -> incoming.put(k, new ArrayList<>()));
        for (Map.Entry<String, FlowNode> entry : index.entrySet()) {
            for (String next : outgoing(entry.getValue())) {
                if (incoming.containsKey(next)) {
                    incoming.get(next).add(entry.getKey());
                }
            }
        }
        Set<String> reachesEnd = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, FlowNode> entry : index.entrySet()) {
            if (FlowNodeType.END == entry.getValue().typeEnum()) {
                reachesEnd.add(entry.getKey());
                queue.add(entry.getKey());
            }
        }
        while (!queue.isEmpty()) {
            String key = queue.poll();
            for (String prev : incoming.getOrDefault(key, List.of())) {
                if (reachesEnd.add(prev)) {
                    queue.add(prev);
                }
            }
        }
        List<String> deadEnds = index.keySet().stream().filter(k -> !reachesEnd.contains(k)).toList();
        if (!deadEnds.isEmpty()) {
            collector.add("存在无法到达结束节点的" + (deadEnds.size() > 1 ? "死路" : "节点")
                    + "：" + String.join("、", deadEnds));
        }
    }
}
