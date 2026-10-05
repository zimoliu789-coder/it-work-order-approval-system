package com.enterprise.ticket.module.order.support;

import com.enterprise.ticket.common.constant.ApprovalNodeStatus;
import com.enterprise.ticket.common.flow.ApproverRule;
import com.enterprise.ticket.common.flow.ApproverRuleType;
import com.enterprise.ticket.common.flow.FlowDefinitionCodec;
import com.enterprise.ticket.common.flow.FlowNode;
import com.enterprise.ticket.module.order.entity.OrderApprovalNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;

/**
 * 「待上一节点指定审批人」的解析。
 *
 * <h2>为什么单独抽一个类</h2>
 * 同一套口径有三个调用方：审批写入 side 的 {@code applyNextStepAssignment}、
 * 候选名单接口的 {@code assignCandidates}、以及随后的指派范围校验。
 * 三处各写一遍 {@code filter(status=PENDING).filter(approverId==null)} 时，
 * 只要有一处漏掉后半句，就会把「已经指定好人的节点」也算成待指派 ——
 * 前端让审批人再点一次名，服务端却因为「本步骤没有需要指定的人」而报错。
 * 这类"两处口径不一致"的错误不会自己暴露，所以把口径收成一个方法。
 *
 * <p>本类**只做解析**，不写库、不发消息 —— 那些属于 {@code OrderServiceImpl}。
 */
@Slf4j
public final class PendingAssignSupport {

    private PendingAssignSupport() {
    }

    /**
     * 某个步骤下「待上一节点指定」的占位行。
     *
     * <p>判据是两点同时成立：{@code status = PENDING} <b>且</b> {@code approver_id = null}。
     * 只判前半句会把"已经指定好人、正在等待该人处理"的节点也算进来；
     * 只判后半句则会把"已通过 / 已驳回 / 已跳过"的历史占位行也算进来。
     *
     * @param nodes     该工单的审批节点快照
     * @param stepOrder 步骤号；{@code null} 表示工单没有待处理步骤，直接返回空
     */
    public static List<OrderApprovalNode> placeholders(List<OrderApprovalNode> nodes, Integer stepOrder) {
        if (nodes == null || nodes.isEmpty() || stepOrder == null) {
            return List.of();
        }
        return nodes.stream()
                .filter(node -> node != null && Objects.equals(node.getStepOrder(), stepOrder))
                .filter(node -> ApprovalNodeStatus.PENDING.name().equals(node.getStatus()))
                .filter(node -> node.getApproverId() == null)
                .toList();
    }

    /**
     * 站在「我这一票还没投」的视角，预测**我通过之后**会落到哪个步骤的待指派占位行。
     *
     * <h2>为什么不能直接复用 {@link #placeholders(List, Integer)}</h2>
     * <p>{@code placeholders(nodes, currentStepOrder(nodes))} 是**审批之后**的口径：
     * 那时我这一行已经变成 APPROVED，{@code currentStepOrder} 自然落到下一步。
     * 而候选名单接口是在**审批之前**调的 —— 此刻当前步骤仍然是"我"，
     * 直接套用只会查到一个审批人齐全的普通节点，返回 0 人。
     *
     * <p>后果不是"少个提示"那么轻：前端据此隐藏选择器 ⇒ 审批人看不到该点名，
     * 点「通过」被服务端以「下一步骤的审批人需由你指定」拒绝 ——
     * 页面没有任何可操作项，工单静默卡死。三个调用方共用同一口径的前提，
     * 是它们必须描述**同一个时刻**；本方法就是把这个时刻对齐。
     *
     * <h2>只排除「我」在当前步骤的那一行</h2>
     * <p>刻意不加"排除我的所有 PENDING 行"：同一个人完全可能在后面的步骤再当一次审批人，
     * 那一行此刻是 PENDING 且**不归这一步管**；把它一起抹掉会让预测的步骤一路后跳，
     * 漏掉真正等待指派的那个节点。
     *
     * <p>会签也由此自动正确：同一步骤还有别人没批时，排除我这一行后
     * {@code currentStepOrder} 仍停在原步骤，占位行查询自然为空 ——
     * 与 {@code applyNextStepAssignment} 在"我不是最后一个"时的判断完全一致。
     */
    public static List<OrderApprovalNode> placeholdersAfterMyApproval(List<OrderApprovalNode> nodes,
                                                                     Long currentUserId) {
        // 必须在调用 currentStepOrder 之前挡住 null/空：它刻意不做判空（W4-A2 的零行为变更约束），
        // 传 null 会直接 NPE。判空放在本方法，语义也更准确 ——「没有节点」本就等于「没有人要被指定」。
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }
        Integer current = OrderApprovalNodeSupport.currentStepOrder(nodes);
        if (current == null) {
            return List.of();
        }
        List<OrderApprovalNode> afterMyVote = nodes.stream()
                .filter(node -> node != null)
                .filter(node -> !(Objects.equals(node.getStepOrder(), current)
                        && ApprovalNodeStatus.PENDING.name().equals(node.getStatus())
                        && Objects.equals(node.getApproverId(), currentUserId)))
                .toList();
        return placeholders(afterMyVote, OrderApprovalNodeSupport.currentStepOrder(afterMyVote));
    }

    /**
     * 取某个「待指派」节点上的 {@code PREV_ASSIGN} 规则。
     *
     * <p>三条返回 null 的路径都是**刻意降级为"不限制"**的：
     * <ul>
     *   <li>占位行没有 {@code node_key}（非流程模式）；</li>
     *   <li>流程快照里找不到该节点 key；</li>
     *   <li>流程快照读不出来（存量脏数据）—— 此时宁可放过，
     *       也不要让一笔卡在审批中的老工单因为快照损坏而整单无法继续。</li>
     * </ul>
     * 而"不限制"正是这些定义在 之前的行为，因此降级不会改变任何既有单的走向。
     *
     * @param flowJson    该工单**提交时冻结**的流程定义 JSON（{@code orders.approval_flow_json}）
     * @param placeholder 待指派占位行
     */
    public static ApproverRule prevAssignRuleOf(String flowJson, OrderApprovalNode placeholder) {
        if (placeholder == null || !StringUtils.hasText(placeholder.getNodeKey())
                || !StringUtils.hasText(flowJson)) {
            return null;
        }
        try {
            FlowNode node = FlowDefinitionCodec.read(flowJson).node(placeholder.getNodeKey());
            if (node == null) {
                return null;
            }
            for (ApproverRule rule : node.getApproverRules()) {
                if (rule != null && ApproverRuleType.PREV_ASSIGN == ApproverRuleType.of(rule.getType())) {
                    return rule;
                }
            }
            return null;
        } catch (RuntimeException e) {
            log.warn("流程快照无法解析，本次「指定下一节点审批人」不做范围校验：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析规则上的可选范围；缺省与非法值一律回落 {@link ApproverRuleType.AssignScope#ALL}。
     *
     * <p>回落方向必须是"不限制"：反方向（回落成 {@code IT_EXECUTOR}）会让一份参数写错的
     * 存量流程在无人改动的情况下开始拒绝指派 —— 那是最难查的一类故障。
     */
    public static ApproverRuleType.AssignScope assignScopeOf(ApproverRule rule) {
        ApproverRuleType.AssignScope scope = ApproverRuleType.AssignScope.of(
                rule == null ? null : rule.getAssignScope());
        return scope == null ? ApproverRuleType.AssignScope.ALL : scope;
    }
}
