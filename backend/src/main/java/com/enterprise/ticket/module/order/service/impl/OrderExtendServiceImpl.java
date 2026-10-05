package com.enterprise.ticket.module.order.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.api.PageResult;
import com.enterprise.ticket.common.constant.ApprovalNodeStatus;
import com.enterprise.ticket.common.constant.ExtendStatus;
import com.enterprise.ticket.common.constant.MessageType;
import com.enterprise.ticket.common.constant.OrderStatus;
import com.enterprise.ticket.common.constant.RoleCode;
import com.enterprise.ticket.common.constant.SignType;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.flow.FlowNodeType;
import com.enterprise.ticket.common.util.SecurityUtils;
import com.enterprise.ticket.module.message.service.MessageService;
import com.enterprise.ticket.module.order.dto.OrderExtendApproveRequest;
import com.enterprise.ticket.module.order.dto.OrderExtendRequest;
import com.enterprise.ticket.module.order.dto.vo.ApprovalNodeView;
import com.enterprise.ticket.module.order.dto.vo.OrderExtendVO;
import com.enterprise.ticket.module.order.entity.Order;
import com.enterprise.ticket.module.order.entity.OrderApprovalNode;
import com.enterprise.ticket.module.order.entity.OrderExtend;
import com.enterprise.ticket.module.order.entity.OrderExtendApprovalNode;
import com.enterprise.ticket.module.order.mapper.OrderApprovalNodeMapper;
import com.enterprise.ticket.module.order.mapper.OrderExtendApprovalNodeMapper;
import com.enterprise.ticket.module.order.mapper.OrderExtendMapper;
import com.enterprise.ticket.module.order.mapper.OrderMapper;
import com.enterprise.ticket.module.order.service.OrderExtendService;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 借用延期子工单服务实现
 *
 * <p><b>审批链路复用方式</b>：申请人提交延期时，复制主工单已固化的审批快照
 * （{@code order_approval_nodes} 的 approver / signType / step），重新解析审批人可用性
 * （离职/禁用 → super_admin 兜底，）并应用「审批人 = 申请人则跳过」，
 * 落到独立的 {@code order_extend_approval_nodes} 表。主单审批链路完全不改动，零回归风险。
 *
 * <p><b>审批通过后的副作用</b>：只回写主单三个字段 ——
 * {@code planned_end_time}（延长到新时间）、{@code borrow_timeout}（按新时间重算）、
 * {@code auto_extend_count}（归零，重新给予顺延机会）；另清空三个预警幂等位
 * （{@code remind_before_sent_at / due_reminded_at / last_timeout_alert_at}），
 * 使新到期日重新触发到期预警与顺延。
 *
 * <p><b>不改动的东西</b>：主单申请记录、主单状态、设备状态、{@code actual_final_handler_id}
 * （「最终处理人沿用主工单，不重新分配」）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderExtendServiceImpl implements OrderExtendService {

    private static final long MAX_PAGE_SIZE = 100L;

    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 延期审批待办子查询：我有一笔「位于当前步骤」的待审批延期节点。
     *
     * <p>与主单待办同理，必须限定 {@code step_order = MIN(PENDING 的 step_order)}，
     * 否则后续步骤审批人会提前看到待办。
     */
    private static final String PENDING_EXTEND_IN_SQL =
            "SELECT ean.extend_id FROM order_extend_approval_nodes ean "
                    + "WHERE ean.approver_id = %d AND ean.status = 'PENDING' "
                    + "AND ean.step_order = (SELECT MIN(n2.step_order) FROM order_extend_approval_nodes n2 "
                    + "WHERE n2.extend_id = ean.extend_id AND n2.status = 'PENDING')";

    private final OrderExtendMapper extendMapper;
    private final OrderExtendApprovalNodeMapper extendNodeMapper;
    private final OrderMapper orderMapper;
    private final OrderApprovalNodeMapper orderNodeMapper;
    private final UserMapper userMapper;
    private final MessageService messageService;
    private final SystemConfigService systemConfigService;

    // ------------------------------------------------------------------
    // 发起延期
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long request(Long orderId, OrderExtendRequest request) {
        Long currentUserId = requireCurrentUserId();
        Order order = requireOrder(orderId);

        // ：申请人主动发起
        if (!Objects.equals(order.getApplicantId(), currentUserId)) {
            throw new BusinessException(ErrorCode.ORDER_NOT_APPLICANT, "只有主工单申请人可以发起延期");
        }
        // ：触发条件为 BORROWED
        if (!OrderStatus.BORROWED.name().equals(order.getStatus())) {
            throw new BusinessException(ErrorCode.EXTEND_NOT_ALLOWED,
                    "主工单当前状态为「" + OrderStatus.labelOf(order.getStatus()) + "」，仅「使用中」可申请延期");
        }
        // 长期领用没有固定归还日期，没有「延期」语义（设计取舍，见交付说明）
        if (order.getPlannedEndTime() == null) {
            throw new BusinessException(ErrorCode.EXTEND_LONG_TERM_NOT_SUPPORTED);
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime newEndTime = request.getNewEndTime();
        if (newEndTime == null || !newEndTime.isAfter(now)) {
            throw new BusinessException(ErrorCode.EXTEND_TIME_INVALID);
        }

        int max = Math.max(systemConfigService.extendMaxCount(), 1);
        ExtendStat stat = statOfOrder(orderId);
        if (stat.pending()) {
            throw new BusinessException(ErrorCode.EXTEND_PENDING_EXISTS);
        }
        if (stat.used() >= max) {
            throw new BusinessException(ErrorCode.EXTEND_LIMIT_EXCEEDED,
                    "延期次数已达上限（" + max + " 次），无法再次申请");
        }

        OrderExtend extend = new OrderExtend();
        extend.setOrderId(orderId);
        extend.setApplicantId(currentUserId);
        extend.setOriginalEndTime(order.getPlannedEndTime());
        extend.setNewEndTime(newEndTime);
        extend.setReason(request.getReason().trim());
        extend.setStatus(ExtendStatus.PENDING_APPROVAL.name());
        extendMapper.insert(extend);

        List<OrderExtendApprovalNode> nodes = buildNodes(extend.getId(), order);

        // 主单当年免审批（申请人本身是 super_admin 且分组未配置审批节点，）时，
        // 延期同样免审批：直接生效，避免出现永远无人可批的死单。
        if (nodes.isEmpty()) {
            applyApproved(order, extend, now, null);
            log.info("主工单 {} 无审批链路，延期申请 {} 免审批直接生效", order.getOrderNo(), extend.getId());
            return extend.getId();
        }

        for (OrderExtendApprovalNode node : nodes) {
            extendNodeMapper.insert(node);
        }
        notifyCurrentStep(order, extend, nodes);
        log.info("工单 {} 提交延期申请 {}，延至 {}，已生成 {} 个审批节点",
                order.getOrderNo(), extend.getId(), newEndTime, nodes.size());
        return extend.getId();
    }

    /**
     * 依据主工单已固化的审批快照生成延期审批节点（「复用主工单审批快照链路」）。
     *
     * <p>返回空列表表示主单当年无需审批，延期同样免审批。
     *
     * <h2>为什么要排除抄送（CC）行（M1 顺带修复）</h2>
     * <p>抄送行与审批行同在 {@code order_approval_nodes} 表里，{@code approver_id} 指向的是
     * <b>抄送对象</b>而非审批人。若把抄送行也复制进延期节点，下面会把状态强置为
     * {@code PENDING}，于是<b>抄送人凭空变成审批人</b> —— 他们既没被告知要审，也不知道该怎么审，
     * 而这笔延期会因此卡在一个"不该有人审"的节点上。
     *
     * <p>排除抄送行还有语义上的必要性：抄送是「这笔单据发生了这件事，知会你一下」，
     * 是一个**提交时刻的既成事实**，不存在"再抄送一次"的审批动作。
     */
    private List<OrderExtendApprovalNode> buildNodes(Long extendId, Order order) {
        List<OrderApprovalNode> source = orderNodeMapper.selectList(Wrappers.<OrderApprovalNode>lambdaQuery()
                .eq(OrderApprovalNode::getOrderId, order.getId())
                // 只取审批行：CC 行的 approver_id 是抄送对象，复制过来会变成审批人
                .ne(OrderApprovalNode::getNodeType, FlowNodeType.CC.name())
                // M2：排除「未激活」行。延期单的审批链是主单审批链的**静态副本**，
                // 它不承载运行期条件（没有自己的流程定义快照与运行期上下文）。
                // 而 INACTIVE 的含义正是"还没定要不要走" —— 把它复制过来并强置 PENDING，
                // 等于把"还没轮到的人"变成"现在必须审的人"，与 CC 行那个缺陷是同一类错误。
                // 主单走到可延期状态（已借用）时审批早已结束、不存在 INACTIVE 行，
                // 因此这条过滤在正常情况下命中 0 行；它是一道纵深防御。
                .ne(OrderApprovalNode::getStatus, ApprovalNodeStatus.INACTIVE.name())
                .orderByAsc(OrderApprovalNode::getStepOrder));
        if (source.isEmpty()) {
            return List.of();
        }

        // 先解析最终审批人（含离职/禁用兜底替换），再判断「申请人是否为唯一审批人」（ / ）
        Map<OrderApprovalNode, Resolved> resolved = new LinkedHashMap<>();
        Set<Long> resolvedApproverIds = new LinkedHashSet<>();
        for (OrderApprovalNode src : source) {
            // 占位行（「待上一节点指定」，approver_id 为空）不参与解析：它的人由主单指定，
            // 延期单沿用同一套占位语义，交由后续指派流程处理，此处不能替换成超管
            if (src.getApproverId() == null) {
                resolved.put(src, new Resolved(null, false));
                continue;
            }
            User approver = userMapper.selectById(src.getApproverId());
            boolean unavailable = approver == null
                    || !Boolean.TRUE.equals(approver.getEnabled())
                    || Boolean.TRUE.equals(approver.getDimission());
            Long approverId = unavailable ? requireSuperAdminId() : src.getApproverId();
            resolved.put(src, new Resolved(approverId, unavailable));
            resolvedApproverIds.add(approverId);
        }
        boolean applicantIsOnlyApprover = resolvedApproverIds.size() == 1
                && resolvedApproverIds.contains(order.getApplicantId());

        List<OrderExtendApprovalNode> nodes = new ArrayList<>();
        for (Map.Entry<OrderApprovalNode, Resolved> entry : resolved.entrySet()) {
            OrderApprovalNode src = entry.getKey();
            Resolved target = entry.getValue();
            OrderExtendApprovalNode node = new OrderExtendApprovalNode();
            node.setExtendId(extendId);
            node.setStepOrder(src.getStepOrder());
            node.setApproverId(target.approverId());
            node.setSignType(SignType.normalize(src.getSignType()));
            node.setStatus(ApprovalNodeStatus.PENDING.name());
            node.setSuperBackup(Boolean.TRUE.equals(src.getSuperBackup()));
            node.setFallback(target.fallback());
            if (!target.fallback() && !applicantIsOnlyApprover
                    && Objects.equals(target.approverId(), order.getApplicantId())) {
                // ：审批人 = 申请人且还有其他审批人时，跳过该节点
                node.setStatus(ApprovalNodeStatus.SKIPPED.name());
            }
            nodes.add(node);
        }
        return nodes;
    }

    // ------------------------------------------------------------------
    // 审批
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void approve(Long extendId, OrderExtendApproveRequest request) {
        Long currentUserId = requireCurrentUserId();
        OrderExtend extend = requireExtend(extendId);
        if (!ExtendStatus.PENDING_APPROVAL.name().equals(extend.getStatus())) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_TRANSITION_INVALID,
                    "该延期申请已处理完毕，请勿重复提交");
        }
        Order order = requireOrder(extend.getOrderId());
        if (!OrderStatus.BORROWED.name().equals(order.getStatus())) {
            throw new BusinessException(ErrorCode.EXTEND_NOT_ALLOWED,
                    "主工单当前状态为「" + OrderStatus.labelOf(order.getStatus()) + "」，延期不再适用");
        }

        List<OrderExtendApprovalNode> nodes = listNodes(extendId);
        Integer currentStep = currentStepOrder(nodes);
        if (currentStep == null) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_TRANSITION_INVALID, "该延期申请没有待处理的审批节点");
        }
        OrderExtendApprovalNode myNode = nodes.stream()
                .filter(node -> Objects.equals(node.getStepOrder(), currentStep))
                .filter(node -> Objects.equals(node.getApproverId(), currentUserId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.APPROVER_NOT_CURRENT_NODE));
        if (!ApprovalNodeStatus.PENDING.name().equals(myNode.getStatus())) {
            throw new BusinessException(ErrorCode.APPROVAL_NODE_HANDLED);
        }

        LocalDateTime now = LocalDateTime.now();
        String comment = trimToNull(request.getComment());

        if (Boolean.FALSE.equals(request.getApproved())) {
            if (comment == null) {
                throw new BusinessException(ErrorCode.REJECT_COMMENT_REQUIRED, "驳回必须填写驳回原因");
            }
            actionNode(myNode.getId(), ApprovalNodeStatus.REJECTED, comment, now);
            cancelPendingNodes(extendId, myNode.getId());
            extendMapper.update(null, Wrappers.<OrderExtend>lambdaUpdate()
                    .eq(OrderExtend::getId, extendId)
                    .set(OrderExtend::getStatus, ExtendStatus.REJECTED.name())
                    .set(OrderExtend::getActionTime, now)
                    .set(OrderExtend::getActionComment, comment));
            // ：驳回不影响原有借用时间，因此主单不做任何改动
            messageService.send(order.getApplicantId(), MessageType.EXTEND_RESULT, "延期申请被驳回",
                    "工单 " + order.getOrderNo() + " 的延期申请未通过。原因：" + comment, order.getId());
            log.info("延期申请 {}（工单 {}）被 {} 驳回，主单借用时间不变", extendId, order.getOrderNo(), currentUserId);
            return;
        }

        actionNode(myNode.getId(), ApprovalNodeStatus.APPROVED, comment, now);
        boolean anySign = nodes.stream()
                .filter(node -> Objects.equals(node.getStepOrder(), currentStep))
                .anyMatch(node -> SignType.ANY_SIGN.equals(node.getSignType()));
        if (anySign) {
            skipPendingNodesOfStep(extendId, currentStep);
        }

        List<OrderExtendApprovalNode> latest = listNodes(extendId);
        boolean hasPending = latest.stream()
                .anyMatch(node -> ApprovalNodeStatus.PENDING.name().equals(node.getStatus()));
        if (hasPending) {
            // 还有后续步骤 → 把待办推给下一步审批人
            notifyCurrentStep(order, extend, latest);
            return;
        }
        applyApproved(order, extend, now, comment);
    }

    /**
     * 延期审批全部通过：回写主单借用时间。
     *
     * <p>UPDATE 带 {@code status = BORROWED} 前置条件做并发保护 —— 若期间主单已归还/被撤回，
     * 命中 0 行则整笔事务回滚，不产生「已归还的工单被延期」的脏状态。
     */
    private void applyApproved(Order order, OrderExtend extend, LocalDateTime now, String comment) {
        boolean timeout = extend.getNewEndTime().isBefore(now);
        int updated = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .eq(Order::getId, order.getId())
                .eq(Order::getStatus, OrderStatus.BORROWED.name())
                .set(Order::getPlannedEndTime, extend.getNewEndTime())
                .set(Order::getBorrowTimeout, timeout)
                .set(Order::getAutoExtendCount, 0)
                // 新的到期日重新开始计预警与顺延，清空三个幂等位（否则新日期不再触发到期预警）
                .set(Order::getRemindBeforeSentAt, null)
                .set(Order::getDueRemindedAt, null)
                .set(Order::getLastTimeoutAlertAt, null));
        if (updated == 0) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_TRANSITION_INVALID,
                    "主工单状态已变化，延期未生效，请刷新后重试");
        }

        if (ExtendStatus.PENDING_APPROVAL.name().equals(extend.getStatus())) {
            extendMapper.update(null, Wrappers.<OrderExtend>lambdaUpdate()
                    .eq(OrderExtend::getId, extend.getId())
                    .set(OrderExtend::getStatus, ExtendStatus.APPROVED.name())
                    .set(OrderExtend::getActionTime, now)
                    .set(OrderExtend::getActionComment, comment));
        }

        messageService.send(order.getApplicantId(), MessageType.EXTEND_RESULT, "延期申请已通过",
                "工单 " + order.getOrderNo() + " 的延期申请已通过，新的归还时间为 "
                        + extend.getNewEndTime().format(DATETIME_FMT) + "。", order.getId());
        log.info("延期申请 {}（工单 {}）已通过，计划结束时间延长至 {}",
                extend.getId(), order.getOrderNo(), extend.getNewEndTime());
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    @Override
    public PageResult<OrderExtendVO> pageMyApproval(long page, long size) {
        Long currentUserId = requireCurrentUserId();
        long safePage = Math.max(page, 1L);
        long safeSize = Math.min(Math.max(size, 1L), MAX_PAGE_SIZE);

        IPage<OrderExtend> resultPage = extendMapper.selectPage(new Page<>(safePage, safeSize),
                Wrappers.<OrderExtend>lambdaQuery()
                        .eq(OrderExtend::getStatus, ExtendStatus.PENDING_APPROVAL.name())
                        .inSql(OrderExtend::getId, PENDING_EXTEND_IN_SQL.formatted(currentUserId))
                        .orderByAsc(OrderExtend::getId));

        List<OrderExtend> records = resultPage.getRecords();
        if (records.isEmpty()) {
            return PageResult.empty(safePage, safeSize);
        }
        Map<Long, Order> orders = ordersOf(records.stream().map(OrderExtend::getOrderId).toList());
        Set<Long> userIds = new LinkedHashSet<>();
        records.forEach(extend -> userIds.add(extend.getApplicantId()));
        orders.values().forEach(order -> userIds.add(order.getApplicantId()));
        Map<Long, String> userNames = userNameMap(userIds);
        Map<Long, List<OrderExtendApprovalNode>> nodeMap = nodesOf(records.stream().map(OrderExtend::getId).toList());

        return PageResult.of(resultPage, extend -> toVO(extend, orders.get(extend.getOrderId()),
                userNames, nodeMap.get(extend.getId())));
    }

    @Override
    public List<OrderExtendVO> listByOrder(Long orderId) {
        Long currentUserId = requireCurrentUserId();
        Order order = requireOrder(orderId);
        List<OrderExtend> extends_ = extendMapper.selectList(Wrappers.<OrderExtend>lambdaQuery()
                .eq(OrderExtend::getOrderId, orderId)
                .orderByAsc(OrderExtend::getId));
        if (extends_.isEmpty()) {
            return List.of();
        }
        // 可见性：能看主单的人（申请人 / 执行人 / 管理员）或本延期的审批人
        assertCanViewExtend(order, currentUserId, extends_);

        Set<Long> userIds = new LinkedHashSet<>();
        extends_.forEach(extend -> userIds.add(extend.getApplicantId()));
        userIds.add(order.getApplicantId());
        Map<Long, String> userNames = userNameMap(userIds);
        Map<Long, List<OrderExtendApprovalNode>> nodeMap = nodesOf(extends_.stream().map(OrderExtend::getId).toList());
        return extends_.stream()
                .map(extend -> toVO(extend, order, userNames, nodeMap.get(extend.getId())))
                .toList();
    }

    @Override
    public Map<Long, ExtendStat> statByOrders(Collection<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return Map.of();
        }
        List<OrderExtend> rows = extendMapper.selectList(Wrappers.<OrderExtend>lambdaQuery()
                .select(OrderExtend::getOrderId, OrderExtend::getStatus)
                .in(OrderExtend::getOrderId, orderIds)
                .ne(OrderExtend::getStatus, ExtendStatus.REJECTED.name()));
        Map<Long, int[]> counters = new HashMap<>();
        for (OrderExtend row : rows) {
            int[] counter = counters.computeIfAbsent(row.getOrderId(), key -> new int[2]);
            counter[0]++;
            if (ExtendStatus.PENDING_APPROVAL.name().equals(row.getStatus())) {
                counter[1]++;
            }
        }
        Map<Long, ExtendStat> result = new HashMap<>();
        counters.forEach((orderId, counter) -> result.put(orderId, new ExtendStat(counter[0], counter[1] > 0)));
        return result;
    }

    @Override
    public ExtendStat statOfOrder(Long orderId) {
        return statByOrders(List.of(orderId)).getOrDefault(orderId, new ExtendStat(0, false));
    }

    // ------------------------------------------------------------------
    // 视图装配
    // ------------------------------------------------------------------

    private OrderExtendVO toVO(OrderExtend extend, Order order, Map<Long, String> userNames,
                               List<OrderExtendApprovalNode> nodes) {
        OrderExtendVO vo = new OrderExtendVO();
        vo.setId(extend.getId());
        vo.setOrderId(extend.getOrderId());
        vo.setOrderNo(order == null ? null : order.getOrderNo());
        vo.setApplicantId(extend.getApplicantId());
        vo.setApplicantName(userNames.get(extend.getApplicantId()));
        vo.setOriginalEndTime(extend.getOriginalEndTime());
        vo.setNewEndTime(extend.getNewEndTime());
        vo.setReason(extend.getReason());
        vo.setStatus(extend.getStatus());
        vo.setStatusLabel(ExtendStatus.labelOf(extend.getStatus()));
        vo.setActionTime(extend.getActionTime());
        vo.setActionComment(extend.getActionComment());
        vo.setCreatedAt(extend.getCreatedAt());
        vo.setNodes(nodes == null ? List.of() : nodes.stream()
                .map(node -> toNodeView(node, userNames))
                .toList());
        return vo;
    }

    private ApprovalNodeView toNodeView(OrderExtendApprovalNode node, Map<Long, String> userNames) {
        ApprovalNodeView view = new ApprovalNodeView();
        view.setId(node.getId());
        view.setStepOrder(node.getStepOrder());
        view.setApproverId(node.getApproverId());
        view.setApproverName(userNames.get(node.getApproverId()));
        view.setSignType(node.getSignType());
        view.setSignTypeLabel(SignType.ALL_SIGN.equals(node.getSignType()) ? "会签" : "或签");
        view.setStatus(node.getStatus());
        view.setStatusLabel(ApprovalNodeStatus.labelOf(node.getStatus()));
        view.setActionTime(node.getActionTime());
        view.setActionComment(node.getActionComment());
        view.setSuperBackup(Boolean.TRUE.equals(node.getSuperBackup()));
        view.setFallback(Boolean.TRUE.equals(node.getFallback()));
        return view;
    }

    // ------------------------------------------------------------------
    // 审批节点内部操作（与主单同语义，作用于独立表）
    // ------------------------------------------------------------------

    private List<OrderExtendApprovalNode> listNodes(Long extendId) {
        return extendNodeMapper.selectList(Wrappers.<OrderExtendApprovalNode>lambdaQuery()
                .eq(OrderExtendApprovalNode::getExtendId, extendId)
                .orderByAsc(OrderExtendApprovalNode::getStepOrder));
    }

    private Map<Long, List<OrderExtendApprovalNode>> nodesOf(Collection<Long> extendIds) {
        if (extendIds == null || extendIds.isEmpty()) {
            return Map.of();
        }
        return extendNodeMapper.selectList(Wrappers.<OrderExtendApprovalNode>lambdaQuery()
                        .in(OrderExtendApprovalNode::getExtendId, extendIds)
                        .orderByAsc(OrderExtendApprovalNode::getStepOrder)
                        .orderByAsc(OrderExtendApprovalNode::getId))
                .stream()
                .collect(Collectors.groupingBy(OrderExtendApprovalNode::getExtendId));
    }

    /** 当前应处理的步骤号：最小仍为 PENDING 的 step_order；无 PENDING 返回 null */
    private Integer currentStepOrder(List<OrderExtendApprovalNode> nodes) {
        return nodes.stream()
                .filter(node -> ApprovalNodeStatus.PENDING.name().equals(node.getStatus()))
                .map(OrderExtendApprovalNode::getStepOrder)
                .min(Integer::compareTo)
                .orElse(null);
    }

    private void actionNode(Long nodeId, ApprovalNodeStatus status, String comment, LocalDateTime actionTime) {
        extendNodeMapper.update(null, Wrappers.<OrderExtendApprovalNode>lambdaUpdate()
                .eq(OrderExtendApprovalNode::getId, nodeId)
                .set(OrderExtendApprovalNode::getStatus, status.name())
                .set(OrderExtendApprovalNode::getActionComment, comment)
                .set(OrderExtendApprovalNode::getActionTime, actionTime));
    }

    /** 驳回后作废同延期单内其余未完成节点（ 同语义） */
    private void cancelPendingNodes(Long extendId, Long exceptNodeId) {
        extendNodeMapper.update(null, Wrappers.<OrderExtendApprovalNode>lambdaUpdate()
                .eq(OrderExtendApprovalNode::getExtendId, extendId)
                .eq(OrderExtendApprovalNode::getStatus, ApprovalNodeStatus.PENDING.name())
                .ne(exceptNodeId != null, OrderExtendApprovalNode::getId, exceptNodeId)
                .set(OrderExtendApprovalNode::getStatus, ApprovalNodeStatus.CANCELLED.name()));
    }

    /** 或签通过后跳过同步骤其余待办节点（ 同语义） */
    private void skipPendingNodesOfStep(Long extendId, Integer stepOrder) {
        extendNodeMapper.update(null, Wrappers.<OrderExtendApprovalNode>lambdaUpdate()
                .eq(OrderExtendApprovalNode::getExtendId, extendId)
                .eq(OrderExtendApprovalNode::getStepOrder, stepOrder)
                .eq(OrderExtendApprovalNode::getStatus, ApprovalNodeStatus.PENDING.name())
                .set(OrderExtendApprovalNode::getStatus, ApprovalNodeStatus.SKIPPED.name()));
    }

    /** 给「当前步骤」的待办审批人推送站内待办（「新审批待办」） */
    private void notifyCurrentStep(Order order, OrderExtend extend, List<OrderExtendApprovalNode> nodes) {
        Integer step = currentStepOrder(nodes);
        if (step == null) {
            return;
        }
        List<Long> approverIds = nodes.stream()
                .filter(node -> Objects.equals(node.getStepOrder(), step))
                .filter(node -> ApprovalNodeStatus.PENDING.name().equals(node.getStatus()))
                .map(OrderExtendApprovalNode::getApproverId)
                .distinct()
                .toList();
        if (approverIds.isEmpty()) {
            return;
        }
        String content = "工单 " + order.getOrderNo() + " 提交了借用延期申请：" + extend.getOriginalEndTime().format(DATETIME_FMT)
                + " → " + extend.getNewEndTime().format(DATETIME_FMT) + "，请审批。";
        messageService.send(approverIds, MessageType.APPROVAL_TODO, "延期审批待办", content, order.getId());
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private Map<Long, Order> ordersOf(Collection<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return Map.of();
        }
        return orderMapper.selectBatchIds(orderIds).stream()
                .collect(Collectors.toMap(Order::getId, Function.identity(), (a, b) -> a));
    }

    private Map<Long, String> userNameMap(Collection<Long> userIds) {
        Collection<Long> ids = userIds == null ? List.of() : userIds.stream().filter(Objects::nonNull).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(User::getId,
                        user -> user.getDisplayName() == null ? user.getUsername() : user.getDisplayName(),
                        (a, b) -> a));
    }

    /** 延期记录可见性：能看主单的人，或本延期任一节点的审批人 */
    private void assertCanViewExtend(Order order, Long currentUserId, List<OrderExtend> extends_) {
        String role = currentRole();
        if (RoleCode.isAdminOrAbove(role)
                || Objects.equals(order.getApplicantId(), currentUserId)
                || Objects.equals(order.getActualFinalHandlerId(), currentUserId)) {
            return;
        }
        long approverCount = extendNodeMapper.selectCount(Wrappers.<OrderExtendApprovalNode>lambdaQuery()
                .in(OrderExtendApprovalNode::getExtendId, extends_.stream().map(OrderExtend::getId).toList())
                .eq(OrderExtendApprovalNode::getApproverId, currentUserId));
        if (approverCount == 0) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权查看该工单的延期记录");
        }
    }

    private Order requireOrder(Long orderId) {
        Order order = orderId == null ? null : orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }
        return order;
    }

    private OrderExtend requireExtend(Long extendId) {
        OrderExtend extend = extendId == null ? null : extendMapper.selectById(extendId);
        if (extend == null) {
            throw new BusinessException(ErrorCode.EXTEND_NOT_FOUND);
        }
        return extend;
    }

    private Long requireCurrentUserId() {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }

    private String currentRole() {
        var loginUser = SecurityUtils.getCurrentUser();
        return loginUser == null ? null : loginUser.getRole();
    }

    /** super_admin 兜底账号 */
    private Long requireSuperAdminId() {
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

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record Resolved(Long approverId, boolean fallback) {
    }
}
