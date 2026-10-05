package com.enterprise.ticket.module.order.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ticket.common.constant.ApprovalNodeStatus;
import com.enterprise.ticket.common.constant.SignType;
import com.enterprise.ticket.common.flow.ApproverRule;
import com.enterprise.ticket.common.flow.ApproverRuleResolver;
import com.enterprise.ticket.common.flow.FlowDefinition;
import com.enterprise.ticket.common.flow.FlowDefinitionCodec;
import com.enterprise.ticket.common.flow.FlowNode;
import com.enterprise.ticket.common.flow.FlowPathResolver;
import com.enterprise.ticket.common.flow.NodeActivation;
import com.enterprise.ticket.common.flow.ProcessFieldCatalog;
import com.enterprise.ticket.common.flow.RejectAction;
import com.enterprise.ticket.common.flow.RuntimeContext;
import com.enterprise.ticket.common.flow.TimeoutAction;
import com.enterprise.ticket.module.order.entity.Order;
import com.enterprise.ticket.module.order.entity.OrderApprovalNode;
import com.enterprise.ticket.module.order.entity.OrderFlowActivationLog;
import com.enterprise.ticket.module.order.mapper.OrderApprovalNodeMapper;
import com.enterprise.ticket.module.order.mapper.OrderFlowActivationLogMapper;
import com.enterprise.ticket.module.order.support.FlowInputResolver;
import com.enterprise.ticket.module.order.support.FlowNodeMaterializer;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 流程节点激活服务—— 本波的核心。
 *
 * <h2>职责</h2>
 * <p>把「提交时一次性定格」升级为「骨架定格 + 运行期逐步激活」：
 * 提交时 {@code FlowPathResolver} 把判不了的分支下游物化为 {@code INACTIVE}；
 * 之后每当前置节点完成，本服务用<b>同一份定义快照 + 运行期上下文</b>再求值一次，
 * 把 {@code INACTIVE} 收敛为 {@code PENDING}（激活）或 {@code SKIPPED}（确定不走）。
 *
 * <h2>四条不变式（缺一条都会出事故）</h2>
 * <ol>
 *   <li><b>幂等</b>：同上下文同结果。{@code recompute} 会被高频调用（每次审批动作、
 *       每次超时扫描），必须保证重复调用不产生额外副作用 —— 只有真正发生状态变更才写库与写日志。</li>
 *   <li><b>不回头</b>：已是 {@code PENDING} 或终态的节点<b>绝不改写</b>。
 *       否则一笔已通过的节点可能被"重新激活"，等于把审批结果抹掉。</li>
 *   <li><b>单事务</b>：一次 {@code recompute} 的全部写库在同一事务内，
 *       避免出现"节点激活了但日志没写"或"删了占位行但新的没插进去"的中间态。</li>
 *   <li><b>有账</b>：每次决策写 {@code order_flow_activation_log}，
 *       这是对"破坏提交定格可复现性"的直接补偿。</li>
 * </ol>
 *
 * <h2>为什么所有节点状态的写入都收敛到这里</h2>
 * <p>第二期只有"提交"一个写入点；本波之后，节点状态会由<b>多个触发源</b>改变
 * （审批推进、超时扫描、超管干预、撤回/终止）。若各写各的，很容易出现
 * "超管把节点 CANCELLED 了，但 recompute 仍基于旧状态判定"这类不一致。
 * 因此本类同时提供 {@link #cancelOpenNodes}，成为节点状态变更的单一写库入口。
 * 开关关闭时这些方法全部短路或退化为原行为 —— 这就是零回归的物理载体。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlowActivationService {

    /**
     * 超时加签行在 {@code node_key} 上的标记（形如 {@code n1#addsign-1234567890}）。
     *
     * <p><b>为什么必须是共享常量而不是各写各的字面量</b>：这个标记同时被三处依赖 ——
     * ① 加签时生成 key；② {@link #alreadyAddedSign} 判断"是否已经加过签"（幂等位）；
     * ③ M7 流程监控做节点聚合时要把加签行**折回父节点**
     * （否则每个加签行都是唯一 key，监控页会凭空多出一堆只出现过一次的"节点"）。
     * 三处只要有一处拼写漂移，症状就是"幂等失效"或"监控多出幽灵节点"这类
     * **不会报错、只会静默算错**的问题。因此提取为常量，并把它作为参数传给聚合 SQL，
     * 让 SQL 侧不出现第二份字面量。
     */
    public static final String ADDSIGN_KEY_MARKER = "#addsign-";

    private final SystemConfigService systemConfigService;
    private final OrderApprovalNodeMapper nodeMapper;
    private final OrderFlowActivationLogMapper activationLogMapper;
    private final FlowInputResolver flowInputResolver;
    private final FlowNodeMaterializer materializer;
    private final ApproverRuleResolver approverRuleResolver;
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;

    /** 提交时是否采用「骨架定格 + 逐步激活」口径（= 总开关是否开启） */
    public boolean deferralEnabled() {
        return systemConfigService.flowRuntimeConditionEnabled();
    }

    /**
     * 该工单是否是有运行期特性的流程单（用于重算的入口判定）。
     *
     * <h2>为什么 recompute 的守卫<b>不</b>看总开关（相对设计稿的一处修正）</h2>
     * <p>设计稿写的是「开关关闭时 recompute 全部调用点短路」。照此实现会有一个
     * 真实的回滚事故：把开关关掉之后，<b>已经在途的</b>运行期工单里那些 INACTIVE 节点
     * 再也不会被判定 —— 它们既不是 PENDING（推进不动）、也不是终态（流程完不了），
     * 工单会永远停在「审批中」，且没有任何操作能救活它。
     *
     * <p>正确的划分是：<b>开关闸"新建"，不闸"收敛"</b> ——
     * <ul>
     *   <li>开关关闭 → 发布侧拒绝含运行期特性的定义、提交时按「无运行期上下文」求值（= 第二期口径），
     *       因此<b>不会再有新的</b> INACTIVE 行产生；</li>
     *   <li>但已存在的 INACTIVE 行必须仍能被重算定性，否则回滚即等于制造死单。</li>
     * </ul>
     * <p>零回归不受影响：既有的第二期工单根本没有 INACTIVE 行，
     * 本方法在那种工单上返回 false，recompute 直接返回"无变更"。
     */
    public boolean isRuntimeOrder(Order order) {
        if (order == null || !StringUtils.hasText(order.getApprovalFlowJson())) {
            return false;
        }
        FlowDefinition definition = FlowDefinitionCodec.read(order.getApprovalFlowJson());
        return definition != null && definition.hasRuntimeFeature();
    }

    /** 解析工单的流程定义快照；无快照或非法返回 null */
    public FlowDefinition definitionOf(Order order) {
        if (order == null || !StringUtils.hasText(order.getApprovalFlowJson())) {
            return null;
        }
        return FlowDefinitionCodec.read(order.getApprovalFlowJson());
    }

    // ------------------------------------------------------------------
    // 提交物化（运行期定义）
    // ------------------------------------------------------------------

    /**
     * 提交物化：把「提交时判不了的分支下游」落为 INACTIVE。
     *
     * <h2>开关关闭时的口径（关键取舍）</h2>
     * <p>开关关闭 → 传一个<b>"已知但为空"</b>的运行期上下文（{@code RuntimeContext.empty()}），
     * 从而 {@code runtimeReady=true}、不做任何延迟判定 —— 所有条件一律用表单数据求值完，
     * 与第二期逐行一致（例如 {@code process.prevNodeResult EQ "APPROVED"} 此刻求值为 false，
     * 于是走默认分支，而不是"待判定"）。
     *
     * <p>这比"关开关时改用一个不带运行期字段的旧求值器"更稳：求值逻辑只有一份，
     * 差异只在"喂进去的上下文是否标记为已知"，不会出现两套代码在边界条件上分叉。
     */
    public FlowPathResolver.Result resolveForSubmit(FlowDefinition definition,
                                                   com.enterprise.ticket.common.form.FormSchema schema,
                                                   Map<String, Object> formData) {
        RuntimeContext context = deferralEnabled() ? null : RuntimeContext.empty();
        return FlowPathResolver.resolve(definition, schema, formData, context, null);
    }

    // ------------------------------------------------------------------
    // 运行期重算
    // ------------------------------------------------------------------

    /**
     * 重算结果。
     *
     * @param activated   被激活为 PENDING 的节点数
     * @param skipped     被判定为"确定不走"的节点数
     * @param notices     需要调用方补发通知的激活事件（新审批人 / 新抄送对象 / 改道目标）
     */
    public record RecomputeResult(int activated, int skipped, List<ActivationNotice> notices) {

        public static RecomputeResult none() {
            return new RecomputeResult(0, 0, List.of());
        }

        public boolean changed() {
            return activated > 0 || skipped > 0;
        }
    }

    /**
     * 一次激活事件（供调用方发消息）。
     *
     * @param nodeKey     节点标识
     * @param nodeName    节点名
     * @param nodeType    APPROVAL / CC
     * @param approverIds 该节点最终落到的人（占位指派节点为空列表）
     * @param reason      激活原因（用于消息正文，解释"为什么轮到你了"）
     */
    public record ActivationNotice(String nodeKey, String nodeName, String nodeType,
                                   List<Long> approverIds, String reason) {
    }

    /**
     * 重算某工单的节点激活态（幂等）。
     *
     * <p>调用时机（必须与推进链顺序一致，见 {@code OrderServiceImpl#approve}）：
     * <b>recompute → 审批人在职校验 → 上一节点指派 → 有无待办判定</b>。
     * recompute 放在最前，是为了让刚被激活的节点在本轮就接受在职校验与指派 ——
     * 否则会短暂出现"已激活但审批人失效/未指派"的窗口，此时审批人点进来会拿到误导性报错。
     */
    @Transactional(rollbackFor = Exception.class)
    public RecomputeResult recompute(Order order) {
        return recompute(order, Set.of());
    }

    /**
     * 重算（可指定"强制激活"的节点，供驳回/超时改道使用）。
     *
     * @param forceActiveKeys 强制判定为"会走"的节点 key（改道目标）。
     *        它们同时会被并入阻塞集 —— 因为"即将被激活"意味着"尚未完成"，
     *        其下游此刻不该被一起激活。
     */
    @Transactional(rollbackFor = Exception.class)
    public RecomputeResult recompute(Order order, Set<String> forceActiveKeys) {
        Set<String> forced = forceActiveKeys == null ? Set.of() : forceActiveKeys;
        if (!isRuntimeOrder(order)) {
            return RecomputeResult.none();
        }
        FlowDefinition definition = definitionOf(order);
        List<OrderApprovalNode> rows = listNodes(order.getId());
        // 没有任何 INACTIVE 行且无改道注入 → 无事可做。这一步不只是性能优化：
        // 它让「第二期工单」与「不含运行期条件的流程单」在本方法上**零成本短路**，
        // 每次审批动作多出来的开销只剩一次按工单的节点查询。
        if (rows.isEmpty() || (!hasInactive(rows) && forced.isEmpty())) {
            return RecomputeResult.none();
        }

        // 「阻塞集」= 当前有 PENDING 行的节点 ∪ 本次将被激活的节点。它们之后的条件此刻不该被求值：
        // 那些条件的"当时取值"尚未到来，提前算出来会让下游节点抢在当前节点之前变成待办。
        Set<String> blocked = rows.stream()
                .filter(row -> ApprovalNodeStatus.PENDING.name().equals(row.getStatus()))
                .map(OrderApprovalNode::getNodeKey)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        blocked.addAll(forced);

        RuntimeContext context = buildContext(order, rows);
        Map<String, Object> formData = flowInputResolver.of(order);
        FlowPathResolver.Result result = FlowPathResolver.resolve(definition,
                flowInputResolver.schemaOf(order), formData, context, blocked, forced);

        Map<String, List<OrderApprovalNode>> byKey = rows.stream()
                .filter(row -> row.getNodeKey() != null)
                .collect(Collectors.groupingBy(OrderApprovalNode::getNodeKey,
                        LinkedHashMap::new, Collectors.toList()));

        User applicant = userMapper.selectById(order.getApplicantId());
        List<FlowNodeMaterializer.LeaderNotice> leaderNotices = new ArrayList<>();
        List<ActivationNotice> notices = new ArrayList<>();
        String ctxJson = writeContext(context);
        int activated = 0;
        int skipped = 0;

        for (FlowPathResolver.ResolvedNode resolved : result.nodes()) {
            List<OrderApprovalNode> existing = byKey.get(resolved.nodeKey());
            if (existing == null || existing.isEmpty()) {
                continue;
            }
            boolean isForced = forced.contains(resolved.nodeKey());
            boolean allInactive = existing.stream()
                    .allMatch(row -> ApprovalNodeStatus.INACTIVE.name().equals(row.getStatus()));
            // 不变式「不回头」：只要不是"全部处于 INACTIVE"（或改道目标上的"全部 SKIPPED"），
            // 就一律不动 —— 否则一笔已通过的节点可能被"重新激活"，等于把审批结果抹掉。
            boolean allSkipped = existing.stream()
                    .allMatch(row -> ApprovalNodeStatus.SKIPPED.name().equals(row.getStatus()));
            if (!allInactive && !(isForced && allSkipped)) {
                continue;
            }
            if (resolved.activation() == NodeActivation.ACTIVE) {
                String toStatus = activate(order, resolved, applicant, formData, leaderNotices, ctxJson, isForced);
                activated++;
                notices.add(new ActivationNotice(resolved.nodeKey(), resolved.nodeName(),
                        resolved.nodeType(), approverIdsOf(order.getId(), resolved.nodeKey()),
                        describeActivation(resolved)));
                log.info("工单 {} 节点「{}」已激活（{}）", order.getOrderNo(), resolved.nodeName(), toStatus);
            } else if (resolved.activation() == NodeActivation.SKIPPED && allInactive) {
                int updated = nodeMapper.update(null, Wrappers.<OrderApprovalNode>lambdaUpdate()
                        .eq(OrderApprovalNode::getOrderId, order.getId())
                        .eq(OrderApprovalNode::getNodeKey, resolved.nodeKey())
                        .eq(OrderApprovalNode::getStatus, ApprovalNodeStatus.INACTIVE.name())
                        .set(OrderApprovalNode::getStatus, ApprovalNodeStatus.SKIPPED.name())
                        .set(OrderApprovalNode::getRuntimeReason, resolved.conditionDesc()));
                if (updated > 0) {
                    skipped++;
                    writeLog(order.getId(), resolved.nodeKey(), ApprovalNodeStatus.INACTIVE.name(),
                            ApprovalNodeStatus.SKIPPED.name(), resolved.conditionDesc(), ctxJson);
                }
            }
        }

        // 直属领导兜底通知的登记交回调用方：只有调用方知道该工单的"类型名"与通知文案
        if (!leaderNotices.isEmpty()) {
            log.warn("工单 {} 运行期激活过程中有 {} 个节点由超管兜底", order.getOrderNo(), leaderNotices.size());
        }
        return new RecomputeResult(activated, skipped, notices);
    }

    /**
     * 激活单个节点：删掉 INACTIVE 占位行 → 用物化器重新生成正式行。
     *
     * <h2>为什么是「删 + 插」而不是「就地改」</h2>
     * <p>占位行固定一行，而激活后可能需要 <b>N 行</b>（节点解析出 N 个审批人时
     * ANY/ALL 签的既有语义就是"同 step_order 多行"）。就地改无法表达"一行变多行"，
     * 而先删后插天然支持，且 step_order 已在提交时冻结、无需重排。
     *
     * @return 激活后的实际状态（PENDING / CC_NOTIFIED / SKIPPED）—— 三种都可能：
     *         抄送无对象时整行消失、审批人=申请人且多人时该行被跳过
     */
    private String activate(Order order, FlowPathResolver.ResolvedNode resolved, User applicant,
                            Map<String, Object> formData,
                            List<FlowNodeMaterializer.LeaderNotice> leaderNotices, String ctxJson,
                            boolean allowSkipped) {
        // 前置条件带上状态，防止并发（超时扫描与审批动作同时触发）下重复激活。
        // 只有「改道目标」才允许从 SKIPPED 重新打开 —— 那正是"这条分支原本被判为不走、
        // 现在因改道而要走了"的语义；其余情况 SKIPPED 是终局结论，不回头。
        List<String> fromStatuses = allowSkipped
                ? List.of(ApprovalNodeStatus.INACTIVE.name(), ApprovalNodeStatus.SKIPPED.name())
                : List.of(ApprovalNodeStatus.INACTIVE.name());
        String fromStatus = allowSkipped && onlySkipped(order.getId(), resolved.nodeKey())
                ? ApprovalNodeStatus.SKIPPED.name()
                : ApprovalNodeStatus.INACTIVE.name();
        int removed = nodeMapper.delete(Wrappers.<OrderApprovalNode>lambdaQuery()
                .eq(OrderApprovalNode::getOrderId, order.getId())
                .eq(OrderApprovalNode::getNodeKey, resolved.nodeKey())
                .in(OrderApprovalNode::getStatus, fromStatuses));
        if (removed == 0) {
            // 已被另一次 recompute 激活 → 幂等返回，不再插入
            return ApprovalNodeStatus.PENDING.name();
        }
        List<OrderApprovalNode> fresh = materializer.materialize(resolved, applicant, formData, null, leaderNotices);
        String toStatus = ApprovalNodeStatus.SKIPPED.name();
        for (OrderApprovalNode row : fresh) {
            row.setOrderId(order.getId());
            row.setRuntimeReason(describeActivation(resolved));
            nodeMapper.insert(row);
            if (ApprovalNodeStatus.CC_NOTIFIED.name().equals(row.getStatus())) {
                toStatus = ApprovalNodeStatus.CC_NOTIFIED.name();
            } else if (ApprovalNodeStatus.PENDING.name().equals(row.getStatus())) {
                toStatus = ApprovalNodeStatus.PENDING.name();
            }
        }
        writeLog(order.getId(), resolved.nodeKey(), fromStatus, toStatus,
                describeActivation(resolved), ctxJson);
        return toStatus;
    }

    private boolean onlySkipped(Long orderId, String nodeKey) {
        return listNodes(orderId).stream()
                .filter(row -> Objects.equals(row.getNodeKey(), nodeKey))
                .allMatch(row -> ApprovalNodeStatus.SKIPPED.name().equals(row.getStatus()));
    }

    // ------------------------------------------------------------------
    // 节点状态变更的单一写库入口
    // ------------------------------------------------------------------

    /**
     * 作废未完成的节点（原 {@code cancelPendingNodes}）。
     *
     * <h2>相对第二期的唯一差别：把 INACTIVE 也一起作废</h2>
     * <p>{@code INACTIVE} 的 {@code isFinished()} 为 false，属于"尚未完成"。
     * 若整单被驳回/终止/撤回时只作废 PENDING 行，那些 INACTIVE 行会残留下来，
     * 之后任何一次 {@code recompute} 都可能把它们激活 —— 表现为
     * <b>一笔已经终止的工单突然又冒出待审批节点</b>。
     * 开关关闭时不存在 INACTIVE 行，本方法的 SQL 与改造前等价。
     */
    public int cancelOpenNodes(Long orderId, Long exceptNodeId) {
        return nodeMapper.update(null, Wrappers.<OrderApprovalNode>lambdaUpdate()
                .eq(OrderApprovalNode::getOrderId, orderId)
                .in(OrderApprovalNode::getStatus, ApprovalNodeStatus.PENDING.name(),
                        ApprovalNodeStatus.INACTIVE.name())
                .ne(exceptNodeId != null, OrderApprovalNode::getId, exceptNodeId)
                .set(OrderApprovalNode::getStatus, ApprovalNodeStatus.CANCELLED.name()));
    }

    /**
     * 节点级改派（把当前待审节点的审批人换成另一个人）。
     *
     * <p>与 {@code OrderTransferServiceImpl}（执行人转交，改的是 {@code actual_final_handler_id}）
     * 语义完全不同：那个换的是"谁来交付设备"，这个换的是"谁来审这一棒"。
     * 收敛到此处的意义是：改派后若需要重算，调用方拿到的一定是同一个写库入口的语义，
     * 不会出现"改派用了另一套 SQL、状态没带上 INACTIVE 条件"的错漏。
     *
     * @return 实际更新的行数（0 表示节点已变化，调用方应提示刷新）
     */
    public int reassignStepApprover(Long orderId, Integer stepOrder, Long targetUserId) {
        return nodeMapper.update(null, Wrappers.<OrderApprovalNode>lambdaUpdate()
                .eq(OrderApprovalNode::getOrderId, orderId)
                .eq(OrderApprovalNode::getStepOrder, stepOrder)
                .eq(OrderApprovalNode::getStatus, ApprovalNodeStatus.PENDING.name())
                .set(OrderApprovalNode::getApproverId, targetUserId));
    }

    // ------------------------------------------------------------------
    // 超时加签（onTimeout = ADD_SIGN）
    // ------------------------------------------------------------------

    /**
     * 超时加签：在当前节点之后动态插入一个审批节点。
     *
     * <h2>step_order 的插入策略</h2>
     * <p>新节点取「当前节点 step_order + 1」，并把其后所有 {@code step_order >= 该值} 的行
     * <b>整体 +1</b> —— 一次 UPDATE 完成，顺序始终可读、可审计，也不需要在应用层加分布式锁。
     * 这与「就地改」的取舍相同：宁可付一次全表（单工单内十几行）更新，也不要一个
     * "部分行已改、部分未改"的中间态。
     *
     * <p><b>这套位移只在「stepOrder 沿实际路径严格递增」时才成立</b>（W4-C.5 注记）：
     * 位移的判据是<b>数值</b>大小，一旦编号不单调，"当前节点 + 1" 就不再等价于
     * "排在当前节点之后的第一个位置"，被位移的行会与实际路径顺序脱节。
     * 旧口径（全图 DFS 前序枚举）在「≥2 分支 ＋ 非首条分支有节点 ＋ 与首条分支汇合」的拓扑下
     * 恰好会破坏这个前提；C.5 换成「拓扑距离（最长路径层号）」后该前提恒成立。
     * 契约由 {@code FlowPathResolverTest#stepOrder_invariantAcrossFixtures} 守着。
     *
     * <h2>上限护栏</h2>
     * <p>单工单动态插入次数上限 {@link ProcessFieldCatalog#MAX_DYNAMIC_INSERT}。
     * 加签是运行期自动行为，配置写错（例如 afterHours=1 而阈值判定有误）会让它反复触发；
     * 有上限之后这类错误最多产生 5 个多余节点，而不是把工单撑爆。
     *
     * @return 是否插入成功
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean addSignNode(Order order, OrderApprovalNode currentNode, TimeoutAction action,
                               List<Long> approverIds, String reason) {
        if (approverIds == null || approverIds.isEmpty()) {
            return false;
        }
        long dynamicCount = listNodes(order.getId()).stream()
                .filter(row -> row.getRuntimeReason() != null)
                .count();
        if (dynamicCount >= ProcessFieldCatalog.MAX_DYNAMIC_INSERT) {
            log.warn("工单 {} 动态插入节点已达上限 {}，本次加签被拒绝（节点「{}」）",
                    order.getOrderNo(), ProcessFieldCatalog.MAX_DYNAMIC_INSERT, currentNode.getNodeName());
            return false;
        }
        Integer currentStep = currentNode.getStepOrder();
        int insertStep = (currentStep == null ? 0 : currentStep) + 1;
        // 先整体后移，再插入 —— 顺序不能反，否则新行也会被自己顶走
        nodeMapper.update(null, Wrappers.<OrderApprovalNode>lambdaUpdate()
                .eq(OrderApprovalNode::getOrderId, order.getId())
                .ge(OrderApprovalNode::getStepOrder, insertStep)
                .setSql("step_order = step_order + 1"));

        LocalDateTime now = LocalDateTime.now();
        Integer limit = action == null ? null : action.getAfterHours();
        for (Long approverId : approverIds) {
            OrderApprovalNode node = new OrderApprovalNode();
            node.setOrderId(order.getId());
            node.setStepOrder(insertStep);
            node.setNodeKey(currentNode.getNodeKey() + ADDSIGN_KEY_MARKER + System.nanoTime());
            node.setNodeName(currentNode.getNodeName() + "（超时加签）");
            node.setNodeType(com.enterprise.ticket.common.flow.FlowNodeType.APPROVAL.name());
            node.setApproverId(approverId);
            node.setSignType(SignType.ANY_SIGN);
            node.setStatus(ApprovalNodeStatus.PENDING.name());
            node.setSuperBackup(false);
            node.setFallback(false);
            node.setActivatedAt(now);
            // 加签节点的时限：沿用原节点的时限口径（若原节点不限时则不限时）
            if (limit != null && limit > 0) {
                node.setDeadlineAt(now.plusHours(limit));
            }
            node.setRuntimeReason(reason);
            nodeMapper.insert(node);
        }
        writeLog(order.getId(), currentNode.getNodeKey(), ApprovalNodeStatus.PENDING.name(),
                ApprovalNodeStatus.PENDING.name(), reason, null);
        log.info("工单 {} 节点「{}」超时加签 {} 人，插入 step={}",
                order.getOrderNo(), currentNode.getNodeName(), approverIds.size(), insertStep);
        return true;
    }

    // ------------------------------------------------------------------
    // 驳回改道（onReject = GOTO）
    // ------------------------------------------------------------------

    /**
     * 驳回改道：本节点已 REJECTED，但整单不终止，转而激活 {@code target} 分支。
     *
     * <h2>为什么不能只"激活目标节点"了事</h2>
     * <p>改道意味着"本分支放弃、改走另一支"。而另一支在提交时很可能已经被判定为
     * <b>SKIPPED</b>（条件未命中）或 <b>INACTIVE</b>（还没判），被放弃的这一支则
     * 可能留有 PENDING / INACTIVE 的行。三件事必须一起做，缺一件就是一个死单：
     * <ol>
     *   <li><b>作废被放弃分支上的未完成节点</b>：否则它们永远无法被判定
     *       （既不是 PENDING 推进不动，也不是终态流程完不了），工单停在"审批中"没人能救；</li>
     *   <li><b>重新打开目标分支上被判为跳过的节点</b>：它们之所以是 SKIPPED，
     *       是因为"原分支被选中"；改道之后这个前提不成立了。不重开的话，
     *       目标节点即使被激活，它后面的环节也永远走不到；</li>
     *   <li><b>强制激活目标节点本身</b>：目标多半不在任何"条件命中"的路径上，
     *       光靠条件求值永远算不出它该走（信息源头是"发生过一次驳回"这个事件），
     *       因此必须显式注入（{@code forceActive}）。</li>
     * </ol>
     *
     * <h2>工单状态</h2>
     * <p>保持 {@code PENDING_APPROVAL}、<b>不释放设备</b>（需求方 Q3 已确认）：
     * 驳回只是"这一支不走了"，整单仍在审批中，此时释放设备会造成
     * "设备已回可用池、但审批还没结束"的并发冲突。
     *
     * @return true 表示已按改道处理（调用方不应再走"整单驳回"路径）
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean applyRejectGoto(Order order, OrderApprovalNode rejectedNode, String comment) {
        FlowDefinition definition = definitionOf(order);
        RejectAction action = rejectActionOf(definition, rejectedNode.getNodeKey());
        if (action == null || !action.isGoto()) {
            return false;
        }
        FlowNode target = definition.node(action.getTarget());
        if (target == null) {
            // 发布期已校验目标存在；这里是纵深防御 —— 配置被改脏时宁可退回"整单驳回"
            // （保守、可解释），也不要静默地让工单卡在没有任何待办节点的状态
            log.warn("工单 {} 节点「{}」的驳回改道目标「{}」不存在，退回整单驳回",
                    order.getOrderNo(), rejectedNode.getNodeKey(), action.getTarget());
            return false;
        }
        writeLog(order.getId(), rejectedNode.getNodeKey(), ApprovalNodeStatus.PENDING.name(),
                ApprovalNodeStatus.REJECTED.name(),
                "驳回并改道到「" + nodeLabel(target) + "」"
                        + (comment == null ? "" : "，意见：" + comment),
                null);

        // 作废被放弃分支 → 重开目标分支 → 强制激活目标并重算（与超时改道共用同一段动作）
        int[] counts = switchBranchTo(order, definition, target, rejectedNode.getId());
        log.warn("工单 {} 驳回改道：节点「{}」→「{}」，作废 {} 个节点、重开 {} 个、激活 {} 个",
                order.getOrderNo(), rejectedNode.getNodeName(), target.getKey(),
                counts[0], counts[1], counts[2]);
        return true;
    }

    // ------------------------------------------------------------------
    // 超时动作（onTimeout = ADD_SIGN / GOTO）
    // ------------------------------------------------------------------

    /**
     * 执行某节点的超时动作（由 {@code ApprovalTimeoutJobService} 在同一轮扫描里调用）。
     *
     * <h2>与「超时提醒」的关系（需求方 D：不互斥）</h2>
     * <p>提醒由 job 无条件发出，本方法只负责<b>额外</b>的流程动作：提醒的对象是"人"，
     * 动作的对象是"流程"。因此本方法返回 false（NOTIFY-only、加签人解析为空、目标缺失等）
     * 时，提醒照旧已经发过了 —— 绝不因为动作没做成而把提醒也吞掉。
     *
     * <h2>总开关</h2>
     * <p>与 {@code recompute} 同口径：只看工单定义是否含运行期特性，<b>不看开关</b> ——
     * 关开关后已在途的运行期工单仍需收敛，否则会制造死单。开关只闸"新建"。
     *
     * @return true 表示确实改变了流程（发生了加签或改道）
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean applyTimeoutAction(Order order, Long nodeId) {
        if (order == null || nodeId == null || !isRuntimeOrder(order)) {
            return false;
        }
        OrderApprovalNode node = nodeMapper.selectById(nodeId);
        // 只对「仍在待办」的节点升级：节点可能在本轮扫描前后已被处理（审批/作废），此时动作无意义
        if (node == null || !ApprovalNodeStatus.PENDING.name().equals(node.getStatus())) {
            return false;
        }
        FlowDefinition definition = definitionOf(order);
        TimeoutAction action = timeoutActionOf(definition, node.getNodeKey());
        if (action == null || action.isNotifyOnly()) {
            return false;
        }
        if (action.isAddSign()) {
            return applyTimeoutAddSign(order, definition, node, action);
        }
        if (action.isGoto()) {
            return applyTimeoutGoto(order, definition, node, action);
        }
        return false;
    }

    /** 超时加签：解析加签人 → 追加一个审批节点 */
    private boolean applyTimeoutAddSign(Order order, FlowDefinition definition,
                                        OrderApprovalNode node, TimeoutAction action) {
        // 幂等：同一节点只加签一次。加签节点是"追加在超时节点之后"的，而超时节点本身仍是
        // PENDING；若不显式去重，"每天一次"的扫描会在每个窗口重复加签（虽有 MAX_DYNAMIC_INSERT
        // 兜底，但那会让工单凭空多出 5 个节点）。以 nodeKey 前缀识别自己之前插过的行。
        if (alreadyAddedSign(order.getId(), node.getNodeKey())) {
            return false;
        }
        List<Long> approverIds = resolveAddSignApprovers(order, definition, node, action);
        if (approverIds.isEmpty()) {
            log.warn("工单 {} 节点「{}」超时加签未解析出在职可用的加签人，已跳过（提醒不受影响）",
                    order.getOrderNo(), node.getNodeName());
            return false;
        }
        String reason = "节点「" + nodeLabel(node) + "」超时未及时处理，按规则自动加签";
        return addSignNode(order, node, action, approverIds, reason);
    }

    /**
     * 解析加签人。
     *
     * <p>规则为空 → <b>继承原节点规则</b>（需求方 Q2）：重新解析该节点定义上的
     * {@code approverRules}，走与提交时同一个 {@link ApproverRuleResolver}，
     * 因此"指定人员 / 角色 / 直属领导 / 分组审批人 / 处理小组"都能被继承；填了则<b>完全替换</b>。
     *
     * <p>无论哪种来源都剔除申请人本人（ 自审回避）：加签是"再找个人来看"，
     * 把申请人加进来毫无意义，还会让"申请人给自己加签"成为一条无意义的路径。
     */
    private List<Long> resolveAddSignApprovers(Order order, FlowDefinition definition,
                                               OrderApprovalNode node, TimeoutAction action) {
        User applicant = userMapper.selectById(order.getApplicantId());
        Map<String, Object> formData = flowInputResolver.of(order);
        List<ApproverRule> rules = action.getApprovers();
        if (rules == null || rules.isEmpty()) {
            FlowNode defNode = definition == null ? null : definition.node(node.getNodeKey());
            rules = defNode == null ? List.of() : defNode.getApproverRules();
        }
        return approverRuleResolver.resolve(rules, applicant, formData).stream()
                .filter(id -> !Objects.equals(id, order.getApplicantId()))
                .toList();
    }

    /** 超时改道：放弃超时节点本身，改走 target 指向的分支 */
    private boolean applyTimeoutGoto(Order order, FlowDefinition definition,
                                     OrderApprovalNode node, TimeoutAction action) {
        FlowNode target = definition == null ? null : definition.node(action.getTarget());
        if (target == null) {
            // 发布期已校验目标存在；这里纵深防御 —— 配置被改脏时宁可什么都不做（提醒已发），
            // 也不要静默地把工单改到一条不存在的分支上
            log.warn("工单 {} 节点「{}」超时改道目标「{}」不存在，已跳过改道（提醒不受影响）",
                    order.getOrderNo(), node.getNodeKey(), action.getTarget());
            return false;
        }
        // 1) 超时节点本身不再是待办：作废它，否则它仍 PENDING、整单推不动
        nodeMapper.update(null, Wrappers.<OrderApprovalNode>lambdaUpdate()
                .eq(OrderApprovalNode::getOrderId, order.getId())
                .eq(OrderApprovalNode::getId, node.getId())
                .eq(OrderApprovalNode::getStatus, ApprovalNodeStatus.PENDING.name())
                .set(OrderApprovalNode::getStatus, ApprovalNodeStatus.CANCELLED.name()));
        // 2) 公共改道动作（作废旁支 / 重开目标分支 / 强制激活目标）
        int[] counts = switchBranchTo(order, definition, target, node.getId());
        writeLog(order.getId(), node.getNodeKey(), ApprovalNodeStatus.PENDING.name(),
                ApprovalNodeStatus.CANCELLED.name(),
                "超时改道：节点超时未处理，改走「" + nodeLabel(target) + "」", null);
        log.warn("工单 {} 超时改道：节点「{}」→「{}」，作废 {} 个节点、重开 {} 个、激活 {} 个",
                order.getOrderNo(), node.getNodeName(), target.getKey(),
                counts[0], counts[1], counts[2]);
        return true;
    }

    /**
     * 改道公共动作：作废被放弃分支 → 重开目标分支 → 强制激活目标并重算。
     *
     * <p>驳回改道与超时改道共用这一段：两者在"当前节点是 REJECTED 还是 CANCELLED"
     * 以及日志文案上有差异，但图上的动作完全相同。共用可避免两条路径在
     * "该作废哪些节点"上出现分歧 —— 这是最容易写漏、也最难在测试里发现的一类错误。
     *
     * @return {@code [作废数, 重开数, 激活数]}
     */
    private int[] switchBranchTo(Order order, FlowDefinition definition, FlowNode target, Long exceptNodeId) {
        Set<String> targetReach = reachableFrom(definition, target.getKey());
        int cancelled = nodeMapper.update(null, Wrappers.<OrderApprovalNode>lambdaUpdate()
                .eq(OrderApprovalNode::getOrderId, order.getId())
                .in(OrderApprovalNode::getStatus, ApprovalNodeStatus.PENDING.name(),
                        ApprovalNodeStatus.INACTIVE.name())
                .ne(exceptNodeId != null, OrderApprovalNode::getId, exceptNodeId)
                .notIn(OrderApprovalNode::getNodeKey, targetReach)
                .set(OrderApprovalNode::getStatus, ApprovalNodeStatus.CANCELLED.name()));
        int reopened = nodeMapper.update(null, Wrappers.<OrderApprovalNode>lambdaUpdate()
                .eq(OrderApprovalNode::getOrderId, order.getId())
                .in(OrderApprovalNode::getNodeKey, targetReach)
                .eq(OrderApprovalNode::getStatus, ApprovalNodeStatus.SKIPPED.name())
                .set(OrderApprovalNode::getStatus, ApprovalNodeStatus.INACTIVE.name())
                .set(OrderApprovalNode::getRuntimeReason, "改道：原路径已放弃，本节点重新进入待判定"));
        RecomputeResult result = recompute(order, targetReach.contains(target.getKey())
                ? Set.of(target.getKey()) : Set.of());
        return new int[]{cancelled, reopened, result.activated()};
    }

    /** 该节点是否已经加过签（识别 {@code nodeKey + ADDSIGN_KEY_MARKER} 前缀的插入行） */
    private boolean alreadyAddedSign(Long orderId, String nodeKey) {
        if (!StringUtils.hasText(nodeKey)) {
            return false;
        }
        String prefix = nodeKey + ADDSIGN_KEY_MARKER;
        return listNodes(orderId).stream()
                .anyMatch(row -> row.getNodeKey() != null && row.getNodeKey().startsWith(prefix));
    }

    private String nodeLabel(OrderApprovalNode node) {
        return StringUtils.hasText(node.getNodeName()) ? node.getNodeName() : node.getNodeKey();
    }

    private String nodeLabel(FlowNode node) {
        return node.getName() == null ? node.getKey() : node.getName();
    }

    /** 从某节点出发可达的全部节点 key（含自身）：沿 next 与条件分支出口遍历 */
    private Set<String> reachableFrom(FlowDefinition definition, String startKey) {
        Set<String> visited = new LinkedHashSet<>();
        collectReachable(definition, startKey, visited);
        return visited;
    }

    private void collectReachable(FlowDefinition definition, String key, Set<String> visited) {
        if (!StringUtils.hasText(key) || !visited.add(key)) {
            return;
        }
        FlowNode node = definition.node(key);
        if (node == null) {
            return;
        }
        if (StringUtils.hasText(node.getNext())) {
            collectReachable(definition, node.getNext(), visited);
        }
        for (com.enterprise.ticket.common.flow.FlowBranch branch : node.getBranches()) {
            if (branch != null) {
                collectReachable(definition, branch.getNext(), visited);
            }
        }
    }

    // ------------------------------------------------------------------
    // 运行期上下文
    // ------------------------------------------------------------------

    /**
     * 由工单与当前节点行构造运行期上下文。
     *
     * <h2>「上一节点」的口径</h2>
     * <p>取 step_order 最大的、已产生审批结果的步骤（APPROVED / REJECTED），而不是
     * "列表里的上一行" —— 未激活与已跳过的节点不构成"上一步"。
     * 同一步骤多人时以<b>驳回优先</b>：会签里只要有一人驳回，这一步的结论就是驳回。
     *
     * <h2>耗时怎么算</h2>
     * <p>起点取节点的 {@code activated_at}（没有则回落 {@code created_at}）——
     * 用 "轮到它" 的时刻而不是"提交时刻"，否则后续步骤的耗时会把前序等待时间也算进去，
     * 让 {@code process.prevNodeHours} 这个字段名与它的值对不上。
     */
    public RuntimeContext buildContext(Order order, List<OrderApprovalNode> rows) {
        List<OrderApprovalNode> acted = rows.stream()
                .filter(row -> !com.enterprise.ticket.common.flow.FlowNodeType.CC.name().equals(row.getNodeType()))
                .filter(row -> ApprovalNodeStatus.APPROVED.name().equals(row.getStatus())
                        || ApprovalNodeStatus.REJECTED.name().equals(row.getStatus()))
                .sorted(Comparator.comparing(OrderApprovalNode::getStepOrder,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();

        String prevResult = null;
        Double prevHours = null;
        if (!acted.isEmpty()) {
            Integer lastStep = acted.get(acted.size() - 1).getStepOrder();
            List<OrderApprovalNode> lastStepRows = acted.stream()
                    .filter(row -> Objects.equals(row.getStepOrder(), lastStep))
                    .toList();
            boolean rejected = lastStepRows.stream()
                    .anyMatch(row -> ApprovalNodeStatus.REJECTED.name().equals(row.getStatus()));
            prevResult = rejected ? ApprovalNodeStatus.REJECTED.name() : ApprovalNodeStatus.APPROVED.name();
            LocalDateTime finishedAt = lastStepRows.stream()
                    .map(OrderApprovalNode::getActionTime)
                    .filter(Objects::nonNull)
                    .max(LocalDateTime::compareTo)
                    .orElse(null);
            LocalDateTime startedAt = lastStepRows.stream()
                    .map(row -> row.getActivatedAt() != null ? row.getActivatedAt() : row.getCreatedAt())
                    .filter(Objects::nonNull)
                    .min(LocalDateTime::compareTo)
                    .orElse(null);
            prevHours = hoursBetween(startedAt, finishedAt);
        }

        List<OrderApprovalNode> rejectedSteps = rows.stream()
                .filter(row -> ApprovalNodeStatus.REJECTED.name().equals(row.getStatus()))
                .toList();
        int rejectCount = (int) rejectedSteps.stream()
                .map(OrderApprovalNode::getStepOrder)
                .distinct()
                .count();
        int activatedCount = (int) rows.stream()
                .filter(row -> !com.enterprise.ticket.common.flow.FlowNodeType.CC.name().equals(row.getNodeType()))
                .filter(row -> !ApprovalNodeStatus.INACTIVE.name().equals(row.getStatus()))
                .map(OrderApprovalNode::getStepOrder)
                .distinct()
                .count();

        LocalDateTime submittedAt = order.getCreatedAt();
        return new RuntimeContext(prevResult, prevHours,
                hoursBetween(submittedAt, LocalDateTime.now()),
                !rejectedSteps.isEmpty(), rejectCount, activatedCount);
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private List<OrderApprovalNode> listNodes(Long orderId) {
        return nodeMapper.selectList(Wrappers.<OrderApprovalNode>lambdaQuery()
                .eq(OrderApprovalNode::getOrderId, orderId)
                .orderByAsc(OrderApprovalNode::getStepOrder)
                .orderByAsc(OrderApprovalNode::getId));
    }

    /**
     * 该工单是否存在「尚未判定」的节点。
     *
     * <p>供调用方做三段式终态判定：无 PENDING 但有 INACTIVE 时不能直接进终态，
     * 必须先重算一次再判 —— 否则一笔"还有节点可能要走"的工单会被误判成"流程走完"。
     */
    public boolean hasInactiveNodes(Long orderId) {
        return hasInactive(listNodes(orderId));
    }

    private boolean hasInactive(List<OrderApprovalNode> rows) {
        return rows.stream().anyMatch(row -> ApprovalNodeStatus.INACTIVE.name().equals(row.getStatus()));
    }

    private List<Long> approverIdsOf(Long orderId, String nodeKey) {
        return listNodes(orderId).stream()
                .filter(row -> Objects.equals(row.getNodeKey(), nodeKey))
                .filter(row -> ApprovalNodeStatus.PENDING.name().equals(row.getStatus())
                        || ApprovalNodeStatus.CC_NOTIFIED.name().equals(row.getStatus()))
                .map(OrderApprovalNode::getApproverId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /** 激活原因：优先用求值器给出的说明（"为什么走这条"），兜底给一句通用文案 */
    private String describeActivation(FlowPathResolver.ResolvedNode resolved) {
        String desc = resolved.conditionDesc();
        if (StringUtils.hasText(desc)) {
            return desc;
        }
        return "前置节点已完成，本节点在运行期被激活";
    }

    private void writeLog(Long orderId, String nodeKey, String from, String to, String reason, String ctxJson) {
        OrderFlowActivationLog row = new OrderFlowActivationLog();
        row.setOrderId(orderId);
        row.setNodeKey(nodeKey);
        row.setFromStatus(from);
        row.setToStatus(to);
        row.setReason(reason);
        row.setCtxJson(ctxJson);
        row.setCreatedAt(LocalDateTime.now());
        activationLogMapper.insert(row);
    }

    /** 上下文快照 JSON（供回放）；序列化失败不影响主流程 —— 审计的缺失不该阻断业务 */
    private String writeContext(RuntimeContext context) {
        try {
            return objectMapper.writeValueAsString(context.toMap());
        } catch (Exception e) {
            log.warn("运行期上下文序列化失败，激活日志将不含上下文快照", e);
            return null;
        }
    }

    private static Double hoursBetween(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            return null;
        }
        return Duration.between(start, end).toMillis() / 3600000d;
    }

    /** 供调用方读取节点的超时动作（定义层属性，不落节点表） */
    public TimeoutAction timeoutActionOf(FlowDefinition definition, String nodeKey) {
        FlowNode node = definition == null ? null : definition.node(nodeKey);
        return node == null ? null : node.getOnTimeout();
    }

    /** 供调用方读取节点的驳回动作（定义层属性，不落节点表） */
    public RejectAction rejectActionOf(FlowDefinition definition, String nodeKey) {
        FlowNode node = definition == null ? null : definition.node(nodeKey);
        return node == null ? null : node.getOnReject();
    }
}
