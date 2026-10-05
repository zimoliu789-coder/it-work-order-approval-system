package com.enterprise.ticket.module.order.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.ApprovalNodeStatus;
import com.enterprise.ticket.common.constant.RoleCode;
import com.enterprise.ticket.common.constant.SignType;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.flow.ApproverRule;
import com.enterprise.ticket.common.flow.ApproverRuleResolver;
import com.enterprise.ticket.common.flow.ApproverRuleType;
import com.enterprise.ticket.common.flow.FlowNodeType;
import com.enterprise.ticket.common.flow.FlowPathResolver;
import com.enterprise.ticket.common.flow.NodeActivation;
import com.enterprise.ticket.module.order.entity.OrderApprovalNode;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 流程节点物化器。
 *
 * <h2>为什么从 OrderServiceImpl 里抽出来</h2>
 * <p>在 M2 之前，"把一个求值结果物化成节点行"只有<b>一个</b>调用点（提交）。
 * 引入运行期激活后变成<b>两个</b>：提交时的初始物化，以及
 * {@code FlowActivationService#recompute} 在运行期把 INACTIVE 节点激活为 PENDING。
 *
 * <p>两处必须产出<b>完全一致</b>的行（同样的审批人解析、同样的超管兜底、同样的
 * 「审批人=申请人则跳过自己那一行」、同样的时限快照）—— 如果各写一份，
 * 就会出现"提交时走的节点和运行期激活出来的节点规则不一样"这种最难发现的分叉。
 * 因此把这段逻辑收敛成一个组件，两处都只调它。
 *
 * <h2>它不负责什么</h2>
 * <p>不负责消息通知，也不负责写库。通知由调用方按返回的节点行决定
 * （抄送谁、直属领导兜底该通知谁，这两个问题的答案随工单类型不同而不同，
 * 放在这里会把消息模块的依赖硬塞进来）；写库由调用方在同一个事务里完成。
 */
@Slf4j
@Component
public class FlowNodeMaterializer {

    /**
     * 直属领导规则解析失败的一次兜底记录。
     *
     * <p>登记后由调用方在工单落库（拿到工单号）后向申请人 / 超管 / 管理员三方发消息 ——
     * 「节点由超管兜底了」这件事必须让人知道，否则申请人只会看到"审批人是超管"而不知为何。
     */
    public record LeaderNotice(String nodeName, String reason) {
    }

    private final ApproverRuleResolver approverRuleResolver;
    private final UserMapper userMapper;

    public FlowNodeMaterializer(ApproverRuleResolver approverRuleResolver, UserMapper userMapper) {
        this.approverRuleResolver = approverRuleResolver;
        this.userMapper = userMapper;
    }

    /**
     * 物化一个求值结果节点。
     *
     * <p>按激活态分派：
     * <ul>
     *   <li>{@link NodeActivation#ACTIVE} → 解析审批人并落 PENDING（抄送落 CC_NOTIFIED）；</li>
     *   <li>{@link NodeActivation#SKIPPED} → 落 SKIPPED（带"为什么没走"的说明）；</li>
     *   <li>{@link NodeActivation#INACTIVE} → 落一行占位（等待运行期判定）。</li>
     * </ul>
     *
     * @param selections 申请人自选审批人（key = nodeKey）。运行期激活时传 null ——
     *                   运行期流程禁止 APPLICANT_CHOOSE（发布期校验），因为选择结果不会随工单保存，
     *                   运行期无法复现；若因历史数据真走到这里，会抛"需选择 N 位审批人"而不是静默选错人。
     */
    public List<OrderApprovalNode> materialize(FlowPathResolver.ResolvedNode resolved, User applicant,
                                               Map<String, Object> formData,
                                               Map<String, List<Long>> selections,
                                               List<LeaderNotice> leaderNotices) {
        if (resolved.activation() == NodeActivation.SKIPPED) {
            return List.of(skippedFlowNode(resolved));
        }
        if (resolved.activation() == NodeActivation.INACTIVE) {
            return List.of(inactiveFlowNode(resolved));
        }
        // ACTIVE：抄送节点提交时即解析即物化为终态（不阻塞推进）
        if (resolved.isCc()) {
            return ccFlowNodes(resolved, applicant, formData);
        }
        // 「上一节点审批人指定」：提交时人未知 → 物化 count 行占位（PENDING + approver_id=NULL）
        int prevAssign = prevAssignCount(resolved);
        if (prevAssign > 0) {
            return pendingAssignFlowNodes(resolved, prevAssign);
        }
        return approvalFlowNodes(resolved, applicant, formData, selections, leaderNotices);
    }

    /**
     * 审批节点（ACTIVE）物化：解析审批人 → 逐人一行 PENDING。
     *
     * <h2>兜底口径与一期一致</h2>
     * <p>规则解析不出人时由超级管理员兜底并打标（ / ）。
     * 让整单卡死在「没人能审」比换一位更严格的人来审糟糕得多。
     * 若失败的是「直属领导」规则，额外登记一条兜底通知。
     */
    public List<OrderApprovalNode> approvalFlowNodes(FlowPathResolver.ResolvedNode resolved, User applicant,
                                                     Map<String, Object> formData,
                                                     Map<String, List<Long>> selections,
                                                     List<LeaderNotice> leaderNotices) {
        List<Long> approverIds = resolveFlowApprovers(resolved, applicant, formData, selections);
        boolean fallback = false;
        if (approverIds.isEmpty()) {
            approverIds = List.of(requireSuperAdminId());
            fallback = true;
            log.warn("流程节点「{}」未解析出在职可用的审批人，已由超级管理员兜底", resolved.nodeName());
            String leaderReason = leaderFailureReason(resolved, applicant);
            if (leaderReason != null && leaderNotices != null) {
                leaderNotices.add(new LeaderNotice(resolved.nodeName(), leaderReason));
            }
        }
        LocalDateTime now = LocalDateTime.now();
        boolean multiple = approverIds.size() > 1;
        List<OrderApprovalNode> nodes = new ArrayList<>();
        for (Long approverId : approverIds) {
            OrderApprovalNode node = newNode(resolved.stepOrder(), approverId, resolved.signType());
            node.setNodeKey(resolved.nodeKey());
            node.setNodeName(resolved.nodeName());
            node.setNodeType(FlowNodeType.APPROVAL.name());
            node.setConditionDesc(resolved.conditionDesc());
            node.setDeadlineAt(deadlineOf(resolved, now));
            node.setActivatedAt(now);
            node.setFallback(fallback);
            if (!fallback && multiple && Objects.equals(approverId, applicant.getId())) {
                // 同：节点有多人而其中一个恰好是申请人 → 跳过他自己那一行。
                // 若该节点只有申请人一人，则不跳过（他得自己审自己，否则无人可审）。
                node.setStatus(ApprovalNodeStatus.SKIPPED.name());
            }
            nodes.add(node);
        }
        return nodes;
    }

    /**
     * 抄送节点物化：每人一行 {@code node_type=CC} + 终态 {@code CC_NOTIFIED}。
     *
     * <p>抄送对象来自与审批节点<b>完全相同</b>的 {@link ApproverRuleResolver}，
     * 因此「指定人员 / 角色 / 部门审批人 / 处理小组成员 / 表单人员字段 / 直属领导」
     * 都可作为抄送来源（发布校验已禁用 APPLICANT_CHOOSE 与 PREV_ASSIGN 两类）。
     *
     * <p>解析不出人时<b>静默不抄送</b>：抄送是知会，把知会强行套给超管没有意义。
     * 这与审批节点「解析为空 → 超管兜底」的处理刻意不同。
     */
    public List<OrderApprovalNode> ccFlowNodes(FlowPathResolver.ResolvedNode resolved, User applicant,
                                               Map<String, Object> formData) {
        List<Long> ccIds = approverRuleResolver.resolve(resolved.approverRules(), applicant, formData);
        if (ccIds.isEmpty()) {
            log.info("流程抄送节点「{}」未解析出抄送对象，已跳过抄送", resolved.nodeName());
            return List.of();
        }
        List<OrderApprovalNode> rows = new ArrayList<>();
        for (Long ccId : ccIds) {
            OrderApprovalNode node = new OrderApprovalNode();
            node.setStepOrder(resolved.stepOrder());
            node.setNodeKey(resolved.nodeKey());
            node.setNodeName(resolved.nodeName());
            node.setNodeType(FlowNodeType.CC.name());
            node.setConditionDesc(resolved.conditionDesc());
            node.setApproverId(ccId);
            node.setSignType(SignType.ANY_SIGN);
            node.setStatus(ApprovalNodeStatus.CC_NOTIFIED.name());
            node.setActionTime(LocalDateTime.now());
            node.setSuperBackup(false);
            node.setFallback(false);
            rows.add(node);
        }
        return rows;
    }

    /**
     * 「上一节点审批人指定」节点物化：{@code count} 行 {@code PENDING + approver_id=NULL} 占位。
     *
     * <p>提交时人未知，真正的 {@code approver_id} 由上一节点通过时回填。
     * 多行共享同一 {@code step_order}（或签：任一被指定人通过即推进）。
     */
    public List<OrderApprovalNode> pendingAssignFlowNodes(FlowPathResolver.ResolvedNode resolved, int count) {
        int safeCount = Math.max(1, count);
        LocalDateTime now = LocalDateTime.now();
        List<OrderApprovalNode> rows = new ArrayList<>();
        for (int i = 0; i < safeCount; i++) {
            OrderApprovalNode node = newNode(resolved.stepOrder(), null, SignType.ANY_SIGN);
            node.setNodeKey(resolved.nodeKey());
            node.setNodeName(resolved.nodeName());
            node.setNodeType(FlowNodeType.APPROVAL.name());
            node.setConditionDesc(resolved.conditionDesc());
            node.setDeadlineAt(deadlineOf(resolved, now));
            node.setActivatedAt(now);
            rows.add(node);
        }
        return rows;
    }

    /**
     * 未激活节点（M2）：一行占位，状态 {@code INACTIVE}、无审批人。
     *
     * <p>为什么要有这一行而不是"什么都不落"：
     * ① 详情页的「完整骨架」视图需要看到它（否则看不出一条流程有几个环节）；
     * ② {@code step_order} 是提交时分配并冻结的，先占位才能保证运行期激活出来的节点
     *    落在正确的位置上，不需要重排。
     *
     * <p>{@code conditionDesc} 保留"待判定"的说明（如"依赖运行期数据…"）——
     * 与 SKIPPED 的"不会走"是两种不同解释，合并会丢失信息。
     */
    public OrderApprovalNode inactiveFlowNode(FlowPathResolver.ResolvedNode resolved) {
        OrderApprovalNode node = new OrderApprovalNode();
        node.setStepOrder(resolved.stepOrder());
        node.setNodeKey(resolved.nodeKey());
        node.setNodeName(resolved.nodeName());
        node.setNodeType(resolved.nodeType());
        node.setConditionDesc(resolved.conditionDesc());
        node.setSignType(SignType.normalize(resolved.signType()));
        node.setApproverId(null);
        node.setStatus(ApprovalNodeStatus.INACTIVE.name());
        node.setRuntimeReason(resolved.conditionDesc());
        node.setSuperBackup(false);
        node.setFallback(false);
        return node;
    }

    /** 未命中分支的节点：状态 SKIPPED、无审批人，只保留「在哪一步、叫什么、为什么跳过」 */
    public OrderApprovalNode skippedFlowNode(FlowPathResolver.ResolvedNode resolved) {
        OrderApprovalNode node = new OrderApprovalNode();
        node.setStepOrder(resolved.stepOrder());
        node.setNodeKey(resolved.nodeKey());
        node.setNodeName(resolved.nodeName());
        node.setNodeType(resolved.nodeType());
        node.setConditionDesc(resolved.conditionDesc());
        node.setSignType(SignType.normalize(resolved.signType()));
        node.setApproverId(null);
        node.setStatus(ApprovalNodeStatus.SKIPPED.name());
        node.setSuperBackup(false);
        node.setFallback(false);
        return node;
    }

    /** 节点时限截止时间快照：{@code now + timeLimitHours}；未配时限 → null（不限时） */
    public LocalDateTime deadlineOf(FlowPathResolver.ResolvedNode resolved, LocalDateTime now) {
        Integer hours = resolved.timeLimitHours();
        return hours == null || hours <= 0 ? null : now.plusHours(hours);
    }

    /**
     * 节点「上一节点审批人指定」规则配置的人数。
     *
     * @return 0 表示该节点未使用该规则；规则存在但未配人数时默认 1
     */
    public int prevAssignCount(FlowPathResolver.ResolvedNode resolved) {
        List<ApproverRule> rules = resolved.approverRules() == null ? List.of() : resolved.approverRules();
        ApproverRule prev = rules.stream()
                .filter(rule -> rule != null
                        && ApproverRuleType.of(rule.getType()) == ApproverRuleType.PREV_ASSIGN)
                .findFirst()
                .orElse(null);
        if (prev == null) {
            return 0;
        }
        Integer count = prev.getAssignCount();
        return count == null || count < 1 ? 1 : count;
    }

    /**
     * 解析一个命中节点的最终审批人。
     *
     * <p>两类来源取并集：规则自动解析（指定人员 / 角色 / 分组审批人 / 处理小组 / 表单人员字段）
     * 与申请人自选（{@code APPLICANT_CHOOSE}）。自选在前，体现「申请人明确指定的人排在最前」——
     * 这不影响或签/会签的判定（判定只看人数与 signType，不看顺序），但让待办列表的展示顺序
     * 与申请人的意图一致。
     */
    public List<Long> resolveFlowApprovers(FlowPathResolver.ResolvedNode node, User applicant,
                                           Map<String, Object> formData,
                                           Map<String, List<Long>> selections) {
        List<ApproverRule> rules = node.approverRules() == null ? List.of() : node.approverRules();
        List<Long> resolved = approverRuleResolver.resolve(rules, applicant, formData);
        List<Long> chosen = chosenApprovers(node, rules, selections);
        if (chosen.isEmpty()) {
            return resolved;
        }
        List<Long> merged = new ArrayList<>(chosen);
        for (Long id : resolved) {
            if (!merged.contains(id)) {
                merged.add(id);
            }
        }
        return merged;
    }

    /**
     * 校验并取出「申请人自选」的人。
     *
     * <p><b>这里是自选审批人唯一的真校验点</b>：{@code /flow-preview} 只负责把可选范围和人数
     * 告诉前端好让它渲染选择器，而请求体是客户端完全可控的 —— 直接构造一个提交请求
     * 就能塞进任意 user_id。因此人数区间与可选范围必须在服务端重新算一遍，
     * 与预览下发的候选池同源（{@code ApproverRuleResolver#isChoosable} 与
     * {@code #choosablePool} 读同一张表、同一组条件），保证「界面上能选到的人」与
     * 「服务端肯收的人」是同一集合 —— 区别只是前者用 COUNT 判定、**零物化**
     * （W4-D：此前为一次包含判定把 ALL 域的全部在职用户拉进内存）。
     */
    public List<Long> chosenApprovers(FlowPathResolver.ResolvedNode node, List<ApproverRule> rules,
                                      Map<String, List<Long>> selections) {
        ApproverRule chooseRule = rules.stream()
                .filter(rule -> rule != null
                        && ApproverRuleType.of(rule.getType()) == ApproverRuleType.APPLICANT_CHOOSE)
                .findFirst()
                .orElse(null);
        if (chooseRule == null) {
            return List.of();
        }
        List<Long> picked = selections == null || selections.get(node.nodeKey()) == null
                ? List.of()
                : selections.get(node.nodeKey()).stream().filter(Objects::nonNull).distinct().toList();
        int min = chooseRule.getMinCount() == null ? 1 : chooseRule.getMinCount();
        int max = chooseRule.getMaxCount() == null ? min : chooseRule.getMaxCount();
        if (picked.size() < min || picked.size() > max) {
            String expect = min == max ? min + " 位" : min + "-" + max + " 位";
            throw new BusinessException(ErrorCode.FLOW_APPROVER_SELECTION_INVALID,
                    "节点「" + node.nodeName() + "」需选择 " + expect + "审批人（当前已选 "
                            + picked.size() + " 位）");
        }
        boolean outOfScope = picked.stream().anyMatch(id -> !approverRuleResolver.isChoosable(chooseRule, id));
        if (outOfScope) {
            throw new BusinessException(ErrorCode.FLOW_APPROVER_SELECTION_INVALID,
                    "节点「" + node.nodeName() + "」所选审批人不在允许范围内，请重新选择");
        }
        return picked;
    }

    /**
     * 若节点含「直属领导」规则且解析失败，返回<b>可读的失败原因</b>（供通知管理员去维护）；
     * 返回 null 表示「不是直属领导失败」（或解析正常）。
     *
     * <p>三种失败：未配置领导 / 领导为本人（自审回避）/ 领导已离职或停用或不存在。
     */
    public String leaderFailureReason(FlowPathResolver.ResolvedNode resolved, User applicant) {
        List<ApproverRule> rules = resolved.approverRules() == null ? List.of() : resolved.approverRules();
        boolean hasLeader = rules.stream().anyMatch(rule -> rule != null
                && ApproverRuleType.of(rule.getType()) == ApproverRuleType.LEADER);
        if (!hasLeader) {
            return null;
        }
        if (applicant.getLeaderId() == null) {
            return "未配置直属领导";
        }
        if (Objects.equals(applicant.getLeaderId(), applicant.getId())) {
            return "直属领导被配置为本人（自审回避）";
        }
        User leader = userMapper.selectById(applicant.getLeaderId());
        if (leader == null) {
            return "直属领导账号不存在";
        }
        if (Boolean.TRUE.equals(leader.getDimission())) {
            return "直属领导已离职";
        }
        if (!Boolean.TRUE.equals(leader.getEnabled())) {
            return "直属领导账号已停用";
        }
        return null;
    }

    /** 抄送节点集合按 nodeKey 归组（供调用方发抄送消息时按节点分别发送） */
    public Map<String, List<Long>> ccRecipientsByNode(List<OrderApprovalNode> nodes) {
        return nodes.stream()
                .filter(node -> FlowNodeType.CC.name().equals(node.getNodeType()))
                .filter(node -> node.getApproverId() != null)
                .collect(Collectors.groupingBy(
                        node -> node.getNodeKey() == null ? "-" : node.getNodeKey(),
                        java.util.LinkedHashMap::new,
                        Collectors.mapping(OrderApprovalNode::getApproverId, Collectors.toList())));
    }

    private OrderApprovalNode newNode(Integer stepOrder, Long approverId, String signType) {
        OrderApprovalNode node = new OrderApprovalNode();
        node.setStepOrder(stepOrder);
        node.setApproverId(approverId);
        node.setSignType(signType);
        node.setStatus(ApprovalNodeStatus.PENDING.name());
        node.setSuperBackup(false);
        node.setFallback(false);
        return node;
    }

    /** super_admin 兜底账号；系统必须至少存在一个超管，否则审批无法兜底 */
    public Long requireSuperAdminId() {
        List<User> superAdmins = userMapper.selectList(Wrappers.<User>lambdaQuery()
                .eq(User::getRole, RoleCode.SUPER_ADMIN)
                .orderByAsc(User::getId)
                .last("LIMIT 1"));
        if (superAdmins.isEmpty()) {
            throw new BusinessException(ErrorCode.APPROVAL_FLOW_NOT_CONFIGURED,
                    "系统尚未创建超级管理员账号，无法进行兜底审批");
        }
        return superAdmins.get(0).getId();
    }
}
