package com.enterprise.ticket.common.flow;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 审批流程定义。
 *
 * <p>整体结构：{@code {start: <nodeKey>, nodes: [FlowNode...]}}。
 * 一份定义是一张**有向无环图**：从 {@code start} 出发，经审批节点与条件分支，最终到达 END 节点。
 *
 * <p>为什么不单独设 START 节点：起点恒为唯一一个，用 {@code start} 字段指向首个节点即可，
 * 多一个恒等节点只会让设计器与校验器多一层无意义的分支。
 */
@Data
public class FlowDefinition {

    /** 起始节点 key */
    private String start;

    private List<FlowNode> nodes = new ArrayList<>();

    public List<FlowNode> getNodes() {
        return nodes == null ? new ArrayList<>() : nodes;
    }

    /** key → 节点 的索引（顺序保持定义顺序，便于稳定遍历） */
    public Map<String, FlowNode> indexByKey() {
        Map<String, FlowNode> index = new LinkedHashMap<>();
        for (FlowNode node : getNodes()) {
            if (node != null && node.getKey() != null) {
                index.put(node.getKey(), node);
            }
        }
        return index;
    }

    public FlowNode node(String key) {
        return key == null ? null : indexByKey().get(key);
    }

    /**
     * 本定义是否携带「运行期特性」。
     *
     * <p>两种情况：
     * <ol>
     *   <li>某个条件分支引用了 {@code process.*} 运行期字段 —— 提交时判定不了，
     *       下游节点须物化为 {@code INACTIVE} 留待运行期激活；</li>
     *   <li>某个审批节点配了 onReject 改道 或 onTimeout 加签/改道 ——
     *       会在运行期动态增删/复活节点。</li>
     * </ol>
     *
     * <h2>它是「要不要走新代码路径」的唯一判据</h2>
     * <p>三处共用且必须一致：发布闸门、提交物化、校验器。
     * 不含运行期特性的定义（即全部存量定义）走既有路径，一行不改 —— 这正是零回归的落点。
     */
    public boolean hasRuntimeFeature() {
        for (FlowNode node : getNodes()) {
            if (node == null) {
                continue;
            }
            if (node.hasRuntimeAction()) {
                return true;
            }
            for (FlowBranch branch : node.getBranches()) {
                if (branch != null && ProcessFieldCatalog.isRuntimeDependent(branch.getCondition())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 本定义里配了「上一节点审批人指定」（{@code PREV_ASSIGN}）的节点 key 集合（保持定义顺序）。
     *
     * <h2>为什么需要它，而不是在各处硬编码节点 key</h2>
     * <p>「这个节点的审批人是被上一节点指定的」这件事**只写在流程定义里**：
     * 节点行只存了被指定的人（{@code approver_id}），并不记录"他是被指定的"。
     * 因此所有需要这个判定的地方（详情页打「由上一节点指定」标记、审批完结时
     * 判断"执行人是否已被指定"）都必须回到定义反查 —— 而反查逻辑写两遍，
     * 迟早会出现"详情页说是被指定的、分配执行人时却当没指定"这种裂缝。
     *
     * @return 节点 key 集合；无 {@code PREV_ASSIGN} 时为空集（不是 null）
     */
    public Set<String> prevAssignNodeKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (FlowNode node : getNodes()) {
            if (node == null || node.getKey() == null) {
                continue;
            }
            for (ApproverRule rule : node.getApproverRules()) {
                if (rule != null && ApproverRuleType.PREV_ASSIGN == ApproverRuleType.of(rule.getType())) {
                    keys.add(node.getKey());
                    break;
                }
            }
        }
        return keys;
    }
}
