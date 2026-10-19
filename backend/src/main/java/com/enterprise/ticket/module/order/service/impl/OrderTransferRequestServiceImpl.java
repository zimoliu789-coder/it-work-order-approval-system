package com.enterprise.ticket.module.order.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.MessageType;
import com.enterprise.ticket.common.constant.OrderStatus;
import com.enterprise.ticket.common.constant.RoleCode;
import com.enterprise.ticket.common.constant.TransferRequestStatus;
import com.enterprise.ticket.common.constant.TransferScope;
import com.enterprise.ticket.common.constant.TransferType;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.util.SecurityUtils;
import com.enterprise.ticket.module.department.entity.Department;
import com.enterprise.ticket.module.department.mapper.DepartmentMapper;
import com.enterprise.ticket.module.department.service.DepartmentService;
import com.enterprise.ticket.module.message.entity.Message;
import com.enterprise.ticket.module.message.mapper.MessageMapper;
import com.enterprise.ticket.module.message.service.MessageService;
import com.enterprise.ticket.module.order.dto.TransferRequestCreateRequest;
import com.enterprise.ticket.module.order.dto.TransferRequestDecideRequest;
import com.enterprise.ticket.module.order.dto.vo.TransferCandidateVO;
import com.enterprise.ticket.module.order.dto.vo.TransferGroupOptionVO;
import com.enterprise.ticket.module.order.dto.vo.TransferRequestVO;
import com.enterprise.ticket.module.order.entity.Order;
import com.enterprise.ticket.module.order.entity.OrderApprovalNode;
import com.enterprise.ticket.module.order.entity.OrderHandlerTransfer;
import com.enterprise.ticket.module.order.entity.OrderHandlerTransferRequest;
import com.enterprise.ticket.module.order.mapper.OrderApprovalNodeMapper;
import com.enterprise.ticket.module.order.mapper.OrderHandlerTransferMapper;
import com.enterprise.ticket.module.order.mapper.OrderHandlerTransferRequestMapper;
import com.enterprise.ticket.module.order.mapper.OrderMapper;
import com.enterprise.ticket.module.order.service.OrderTransferRequestService;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 转单申请实现（V55 / D5.7）。
 *
 * <h2>三条不可让步的判定</h2>
 * <ol>
 *   <li><b>同组转单</b>：发起人必须是当前执行人（super_admin 例外），目标必须是本组在职成员，
 *       由**本组管理员**审批；<b>发起人本人就是本组管理员时当场生效</b>（自己批自己没有意义，
 *       只会多一条假审批记录）。</li>
 *   <li><b>跨组转单</b>：发起人必须是**本组管理员**（super_admin 例外），目标只能是
 *       **对方小组的管理员**所在的组 —— 不允许直接选对方普通成员，因为"派谁"是对方组长的职责。</li>
 *   <li><b>生效必须带条件</b>：改执行人时带上「原状态 + 原执行人（跨组再加原小组）」，
 *       命中 0 行即说明工单已被并发推进（归还 / 撤回 / 他人转单），整笔回滚并报
 *       {@code TRANSFER_CONFLICT} —— 绝不写一条"历史与实际不符"的记录。</li>
 * </ol>
 *
 * <h2>为什么通知要发这么多条</h2>
 * <p>转单是「责任移交」，涉及两类人：<b>被移走的人</b>（要确认自己不用管了）与
 * <b>接收的人</b>（要知道多了一笔活）。少发任何一条，都会出现「他以为还在自己名下」或
 * 「没人知道这单转过来了」—— 而这两件事都不会报错，只会体现为工单静静地卡住。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderTransferRequestServiceImpl implements OrderTransferRequestService {

    /** 与既有转交一致的「可转」状态清单（由 OrderStatus 投影，避免两处各写一份） */
    private static final List<String> TRANSFERABLE_STATUSES = OrderStatus.transferableNames();

    /** 原执行人待办消息的标记前缀（与原执行人待办处理的既有约定一致） */
    private static final String TRANSFERRED_MARK = "【工单已转交】";

    private final OrderMapper orderMapper;
    private final OrderHandlerTransferMapper transferMapper;
    private final OrderHandlerTransferRequestMapper requestMapper;
    private final OrderApprovalNodeMapper orderNodeMapper;
    private final DepartmentMapper departmentMapper;
    private final DepartmentService departmentService;
    private final UserMapper userMapper;
    private final MessageMapper messageMapper;
    private final MessageService messageService;

    // ------------------------------------------------------------------
    // 发起
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(Long orderId, TransferRequestCreateRequest request) {
        Long currentUserId = requireCurrentUserId();
        boolean superAdmin = RoleCode.isSuperAdmin(currentRole());
        Order order = requireOrder(orderId);
        assertTransferable(order);
        assertNoPendingRequest(orderId);

        TransferScope scope = TransferScope.of(request.getScope());
        if (scope == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "转单类型取值不合法：" + request.getScope() + "（应为 SAME_GROUP 或 CROSS_GROUP）");
        }
        Long fromDepartmentId = order.getHandlerDepartmentId();
        if (fromDepartmentId == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "该工单没有归属处理小组，无法转单；请先用「转交」指定执行人");
        }
        String reason = request.getReason().trim();

        return scope == TransferScope.SAME_GROUP
                ? createSameGroup(order, fromDepartmentId, currentUserId, superAdmin, request, reason)
                : createCrossGroup(order, fromDepartmentId, currentUserId, superAdmin, request, reason);
    }

    private Long createSameGroup(Order order, Long fromDepartmentId, Long currentUserId,
                                 boolean superAdmin, TransferRequestCreateRequest request, String reason) {
        Long oldHandlerId = order.getActualFinalHandlerId();
        // 普通组员只能为自己名下的工单发起；super_admin 可代发（离职兜底等场景）
        if (!superAdmin && !Objects.equals(oldHandlerId, currentUserId)) {
            throw new BusinessException(ErrorCode.TRANSFER_NOT_HANDLER,
                    "只有该工单的当前执行人才能发起同组转单");
        }
        Long targetHandlerId = request.getTargetHandlerId();
        if (targetHandlerId == null) {
            throw new BusinessException(ErrorCode.TRANSFER_TARGET_INVALID, "请选择本组内的接手人");
        }
        assertTargetHandler(order, fromDepartmentId, targetHandlerId, oldHandlerId);

        Long adminId = groupAdminIdOf(fromDepartmentId);
        if (adminId == null && !superAdmin) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "本处理小组未配置小组管理员，无法提交同组转单；请联系管理员在「组织与人员」中设置部门主管");
        }
        // 发起人自己就是审批人 ⇒ 免审批立即生效。让自己给自己发一条"待审批"，只会制造噪音。
        boolean selfApprove = superAdmin || Objects.equals(adminId, currentUserId);

        LocalDateTime now = LocalDateTime.now();
        OrderHandlerTransferRequest row = newRow(order, TransferScope.SAME_GROUP,
                fromDepartmentId, fromDepartmentId, oldHandlerId, targetHandlerId, adminId,
                selfApprove ? TransferRequestStatus.ACCEPTED : TransferRequestStatus.PENDING,
                currentUserId, reason, now);
        if (selfApprove) {
            row.setDecideBy(currentUserId);
            row.setDecideAt(now);
            row.setDecideComment("发起人即本组管理员，免审批直接生效");
        }
        requestMapper.insert(row);

        if (selfApprove) {
            applyTransfer(order, row, targetHandlerId, TransferType.MANUAL);
            notifyTransferred(order, oldHandlerId, targetHandlerId, reason, "同组转单");
        } else {
            messageService.send(adminId, MessageType.ORDER_TRANSFERRED, "同组转单待审批",
                    "工单 %s 的当前执行人 %s 申请把工单转给 %s（原因：%s），请审批。"
                            .formatted(order.getOrderNo(), displayNameOf(oldHandlerId),
                                    displayNameOf(targetHandlerId), reason),
                    order.getId());
            log.info("工单 {} 提交同组转单申请（{} → {}），待管理员 {} 审批",
                    order.getOrderNo(), oldHandlerId, targetHandlerId, adminId);
        }
        return row.getId();
    }

    private Long createCrossGroup(Order order, Long fromDepartmentId, Long currentUserId,
                                  boolean superAdmin, TransferRequestCreateRequest request, String reason) {
        if (!superAdmin && !isGroupAdmin(currentUserId, fromDepartmentId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "只有本处理小组的管理员才能发起跨组转单");
        }
        Long toDepartmentId = request.getTargetDepartmentId();
        if (toDepartmentId == null) {
            throw new BusinessException(ErrorCode.TRANSFER_TARGET_INVALID, "请选择目标处理小组");
        }
        if (Objects.equals(toDepartmentId, fromDepartmentId)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "目标小组与当前小组相同；组内换人请选择「同组转单」");
        }
        Department targetGroup = departmentMapper.selectById(toDepartmentId);
        if (targetGroup == null
                || !Boolean.TRUE.equals(targetGroup.getHandlerGroup())
                || !Boolean.TRUE.equals(targetGroup.getStatus())) {
            throw new BusinessException(ErrorCode.TRANSFER_TARGET_INVALID,
                    "目标小组不存在、已停用，或不是处理小组");
        }
        Long toAdminId = groupAdminIdOf(toDepartmentId);
        if (toAdminId == null) {
            throw new BusinessException(ErrorCode.TRANSFER_TARGET_INVALID,
                    "目标小组「" + targetGroup.getDeptName() + "」未配置管理员，无法接收转单");
        }

        LocalDateTime now = LocalDateTime.now();
        OrderHandlerTransferRequest row = newRow(order, TransferScope.CROSS_GROUP,
                fromDepartmentId, toDepartmentId, order.getActualFinalHandlerId(), null, toAdminId,
                TransferRequestStatus.PENDING, currentUserId, reason, now);
        requestMapper.insert(row);

        messageService.send(toAdminId, MessageType.ORDER_TRANSFERRED, "跨组转单待接收",
                "%s 想把工单 %s 转到你们小组（原因：%s），请接收并在组内分配执行人，或拒绝退回。"
                        .formatted(displayNameOf(currentUserId), order.getOrderNo(), reason),
                order.getId());
        log.info("工单 {} 发起跨组转单（组 {} → 组 {}），待目标管理员 {} 接收",
                order.getOrderNo(), fromDepartmentId, toDepartmentId, toAdminId);
        return row.getId();
    }

    // ------------------------------------------------------------------
    // 处理
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void decide(Long requestId, TransferRequestDecideRequest request) {
        Long currentUserId = requireCurrentUserId();
        boolean superAdmin = RoleCode.isSuperAdmin(currentRole());
        OrderHandlerTransferRequest row = requestId == null ? null : requestMapper.selectById(requestId);
        if (row == null) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND, "转单申请不存在");
        }
        if (!TransferRequestStatus.PENDING.name().equals(row.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "该转单申请已处理（当前状态：" + TransferRequestStatus.labelOf(row.getStatus()) + "）");
        }
        if (!superAdmin && !Objects.equals(row.getToAdminId(), currentUserId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有该申请的审批人 / 接收人可以处理");
        }
        Order order = requireOrder(row.getOrderId());
        boolean accepted = Boolean.TRUE.equals(request.getAccepted());
        LocalDateTime now = LocalDateTime.now();

        if (!accepted) {
            reject(row, order, currentUserId, request.getComment(), now);
            return;
        }

        TransferScope scope = TransferScope.of(row.getTransferScope());
        Long targetHandlerId;
        if (scope == TransferScope.CROSS_GROUP) {
            targetHandlerId = request.getTargetHandlerId();
            if (targetHandlerId == null) {
                throw new BusinessException(ErrorCode.TRANSFER_TARGET_INVALID,
                        "接收跨组转单时必须同时指定本组的执行人");
            }
            assertTargetHandler(order, row.getToDepartmentId(), targetHandlerId, row.getFromHandlerId());
        } else {
            // 同组转单的目标执行人在发起时就定了；此处忽略请求体里的值，避免被中途偷换
            targetHandlerId = row.getToHandlerId();
        }

        // 工单状态可能在申请挂起期间变了（被归还 / 撤回 / 终止）：此时不能再改执行人
        assertTransferable(order);

        row.setStatus(TransferRequestStatus.ACCEPTED.name());
        row.setToHandlerId(targetHandlerId);
        row.setDecideBy(currentUserId);
        row.setDecideAt(now);
        row.setDecideComment(trimToNull(request.getComment()));
        row.setUpdatedAt(now);
        requestMapper.updateById(row);

        TransferType type = scope == TransferScope.CROSS_GROUP ? TransferType.CROSS_GROUP : TransferType.MANUAL;
        applyTransfer(order, row, targetHandlerId, type);

        String scopeLabel = TransferScope.labelOf(row.getTransferScope());
        notifyTransferred(order, row.getFromHandlerId(), targetHandlerId, row.getReason(), scopeLabel);
        // 跨组：发起人也需要知道"对方接了、派给了谁"，否则他只能自己猜
        if (scope == TransferScope.CROSS_GROUP && !Objects.equals(row.getOperatorId(), row.getDecideBy())) {
            messageService.send(row.getOperatorId(), MessageType.ORDER_TRANSFERRED, "跨组转单已接收",
                    "工单 %s 已由 %s 接收，并分配给 %s。"
                            .formatted(order.getOrderNo(), displayNameOf(currentUserId),
                                    displayNameOf(targetHandlerId)),
                    order.getId());
        }
        log.info("转单申请 {} 生效：工单 {} 由 {} 转给 {}（{}）",
                row.getId(), order.getOrderNo(), row.getFromHandlerId(), targetHandlerId, scopeLabel);
    }

    private void reject(OrderHandlerTransferRequest row, Order order, Long currentUserId,
                        String comment, LocalDateTime now) {
        row.setStatus(TransferRequestStatus.REJECTED.name());
        row.setDecideBy(currentUserId);
        row.setDecideAt(now);
        row.setDecideComment(trimToNull(comment));
        row.setUpdatedAt(now);
        requestMapper.updateById(row);

        // 工单留在原组原执行人手里：本方法**不改工单任何字段**，所以「回流」是天然的，
        // 不需要再做一次反向转交（那次反向操作会凭空造出一条转交历史，污染时间线）。
        String scopeLabel = TransferScope.labelOf(row.getTransferScope());
        messageService.send(row.getOperatorId(), MessageType.ORDER_TRANSFERRED,
                scopeLabel + "申请被拒绝",
                "工单 %s 的%s申请被 %s 拒绝%s，工单仍在原组原执行人手中。"
                        .formatted(order.getOrderNo(), scopeLabel, displayNameOf(currentUserId),
                                comment == null || comment.isBlank() ? "" : ("（原因：" + comment.trim() + "）")),
                order.getId());
        log.info("转单申请 {} 被 {} 拒绝（工单 {}，{}）", row.getId(), currentUserId,
                order.getOrderNo(), scopeLabel);
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    @Override
    public List<TransferRequestVO> listByOrder(Long orderId) {
        Long currentUserId = requireCurrentUserId();
        Order order = requireOrder(orderId);
        List<OrderHandlerTransferRequest> rows = requestMapper.selectList(
                Wrappers.<OrderHandlerTransferRequest>lambdaQuery()
                        .eq(OrderHandlerTransferRequest::getOrderId, orderId)
                        .orderByAsc(OrderHandlerTransferRequest::getId));
        assertCanView(order, currentUserId, rows);
        return toVOList(rows, order, currentUserId);
    }

    @Override
    public List<TransferRequestVO> myTodo() {
        Long currentUserId = requireCurrentUserId();
        List<OrderHandlerTransferRequest> rows = requestMapper.selectList(
                Wrappers.<OrderHandlerTransferRequest>lambdaQuery()
                        .eq(OrderHandlerTransferRequest::getToAdminId, currentUserId)
                        .eq(OrderHandlerTransferRequest::getStatus, TransferRequestStatus.PENDING.name())
                        .orderByAsc(OrderHandlerTransferRequest::getId));
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, Order> orders = orderMapper.selectBatchIds(
                        rows.stream().map(OrderHandlerTransferRequest::getOrderId).distinct().toList())
                .stream().collect(Collectors.toMap(Order::getId, order -> order, (a, b) -> a));
        // 姓名一次取全（待办列表可能有十几条，逐条查会变成 N+1）
        Map<Long, String> names = nameMapOf(rows);
        List<TransferRequestVO> result = new ArrayList<>(rows.size());
        for (OrderHandlerTransferRequest row : rows) {
            result.add(toVO(row, orders.get(row.getOrderId()), names, currentUserId));
        }
        return result;
    }

    @Override
    public List<TransferGroupOptionVO> transferGroups() {
        requireCurrentUserId();
        List<Department> groups = departmentMapper.selectList(Wrappers.<Department>lambdaQuery()
                .eq(Department::getHandlerGroup, true)
                .eq(Department::getStatus, true)
                .orderByAsc(Department::getSortOrder)
                .orderByAsc(Department::getId));
        if (groups.isEmpty()) {
            return List.of();
        }
        List<TransferGroupOptionVO> options = new ArrayList<>();
        for (Department group : groups) {
            List<Long> admins = departmentService.managerIdsOf(group.getId());
            Long adminId = admins.isEmpty() ? null : admins.get(0);
            int memberCount = departmentService.activeMemberIdsOf(group.getId()).size();
            TransferGroupOptionVO vo = new TransferGroupOptionVO();
            vo.setDepartmentId(group.getId());
            vo.setDepartmentName(group.getDeptName());
            vo.setAdminId(adminId);
            vo.setAdminName(adminId == null ? null : displayNameOf(adminId));
            vo.setAdminCount(admins.size());
            vo.setMemberCount(memberCount);
            // 没有管理员就没人能"接收"，没有人就没人能"执行"—— 两种情况都不该可选
            vo.setSelectable(adminId != null && memberCount > 0);
            options.add(vo);
        }
        return options;
    }

    @Override
    public List<TransferCandidateVO> groupMembers(Long departmentId) {
        requireCurrentUserId();
        if (departmentId == null) {
            return List.of();
        }
        List<Long> memberIds = departmentService.activeMemberIdsOf(departmentId);
        if (memberIds.isEmpty()) {
            return List.of();
        }
        Map<Long, String> names = nameMapOfIds(memberIds);
        List<TransferCandidateVO> result = new ArrayList<>(memberIds.size());
        for (Long id : memberIds) {
            TransferCandidateVO vo = new TransferCandidateVO();
            vo.setUserId(id);
            vo.setDisplayName(names.get(id));
            result.add(vo);
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 内部：写
    // ------------------------------------------------------------------
    /**
     * 生效：改工单执行人（跨组时连小组一起改）→ 写转交历史 → 标记原执行人待办。
     */
    private void applyTransfer(Order order, OrderHandlerTransferRequest row, Long targetHandlerId,
                               TransferType type) {
        boolean crossGroup = TransferScope.CROSS_GROUP.name().equals(row.getTransferScope());
        int updated = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .eq(Order::getId, order.getId())
                .eq(Order::getStatus, order.getStatus())
                .eq(Order::getActualFinalHandlerId, row.getFromHandlerId())
                // 跨组时再锁一次原小组：防止「管理员 A 提交的申请，在小组已被别的方式改走后仍生效」
                .eq(crossGroup, Order::getHandlerDepartmentId, row.getFromDepartmentId())
                .set(Order::getActualFinalHandlerId, targetHandlerId)
                .set(crossGroup, Order::getHandlerDepartmentId, row.getToDepartmentId()));
        if (updated == 0) {
            throw new BusinessException(ErrorCode.TRANSFER_CONFLICT,
                    "工单状态或执行人已变化（可能已被归还 / 撤回 / 转交），请刷新后重试");
        }

        OrderHandlerTransfer record = new OrderHandlerTransfer();
        record.setOrderId(order.getId());
        record.setOldHandlerId(row.getFromHandlerId());
        record.setNewHandlerId(targetHandlerId);
        record.setTransferOperatorId(row.getDecideBy() == null ? row.getOperatorId() : row.getDecideBy());
        record.setTransferComment(buildHistoryComment(row));
        record.setTransferType(type.name());
        record.setCreatedAt(LocalDateTime.now());
        transferMapper.insert(record);

        markOldHandlerTodo(order.getId(), row.getFromHandlerId());
        // 内存里的 order 保持与库一致，供通知文案使用
        order.setActualFinalHandlerId(targetHandlerId);
        if (crossGroup) {
            order.setHandlerDepartmentId(row.getToDepartmentId());
        }
    }

    /** 转交历史里的备注：原因 + 谁批的（跨组还带目标小组），让时间线自己讲清楚来龙去脉 */
    private String buildHistoryComment(OrderHandlerTransferRequest row) {
        StringBuilder text = new StringBuilder(row.getReason());
        boolean crossGroup = TransferScope.CROSS_GROUP.name().equals(row.getTransferScope());
        if (crossGroup) {
            Department target = row.getToDepartmentId() == null ? null
                    : departmentMapper.selectById(row.getToDepartmentId());
            text.append("（跨组转单：转至「")
                    .append(target == null ? ("小组 id=" + row.getToDepartmentId()) : target.getDeptName())
                    .append("」）");
        }
        if (row.getDecideComment() != null && !row.getDecideComment().isBlank()) {
            text.append("；处理意见：").append(row.getDecideComment());
        }
        String value = text.toString();
        return value.length() > 500 ? value.substring(0, 500) : value;
    }

    /** 原执行人的该工单待办置为已读并打「已转交」前缀（与既有转交同口径） */
    private void markOldHandlerTodo(Long orderId, Long oldHandlerId) {
        if (oldHandlerId == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        messageMapper.update(null, Wrappers.<Message>lambdaUpdate()
                .eq(Message::getUserId, oldHandlerId)
                .eq(Message::getOrderId, orderId)
                .eq(Message::getIsRead, false)
                .notLikeRight(Message::getTitle, TRANSFERRED_MARK)
                .set(Message::getIsRead, true)
                .set(Message::getReadAt, now)
                .setSql("title = CONCAT('" + TRANSFERRED_MARK + "', title)"));
    }

    /** 转单生效后的三方通知：新执行人（待办）、原执行人（已移交） */
    private void notifyTransferred(Order order, Long oldHandlerId, Long newHandlerId,
                                   String reason, String scopeLabel) {
        messageService.send(newHandlerId, MessageType.ORDER_TRANSFERRED, scopeLabel + "待办",
                "工单 %s 已转交给您（原因：%s），请及时处理。"
                        .formatted(order.getOrderNo(), reason),
                order.getId());
        if (oldHandlerId != null && !Objects.equals(oldHandlerId, newHandlerId)) {
            messageService.send(oldHandlerId, MessageType.ORDER_TRANSFERRED, "工单已转出",
                    "您名下的工单 %s 已通过%s转交给 %s，该工单的后续待办已移交。"
                            .formatted(order.getOrderNo(), scopeLabel, displayNameOf(newHandlerId)),
                    order.getId());
        }
    }

    // ------------------------------------------------------------------
    // 内部：校验
    // ------------------------------------------------------------------

    private void assertTransferable(Order order) {
        if (!TRANSFERABLE_STATUSES.contains(order.getStatus())) {
            throw new BusinessException(ErrorCode.TRANSFER_ORDER_STATUS_INVALID,
                    "工单当前状态为「" + OrderStatus.labelOf(order.getStatus()) + "」，不允许转单");
        }
        if (order.getActualFinalHandlerId() == null) {
            throw new BusinessException(ErrorCode.TRANSFER_NOT_HANDLER,
                    "该工单尚未分配实际执行人，无法转单");
        }
    }

    /** 同一工单同时只允许一条待处理申请：否则两个管理员各批一条，后一条必然 0 行冲突 */
    private void assertNoPendingRequest(Long orderId) {
        long pending = requestMapper.selectCount(Wrappers.<OrderHandlerTransferRequest>lambdaQuery()
                .eq(OrderHandlerTransferRequest::getOrderId, orderId)
                .eq(OrderHandlerTransferRequest::getStatus, TransferRequestStatus.PENDING.name()));
        if (pending > 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "该工单已有待处理的转单申请，请先等待处理或拒绝后再发起");
        }
    }

    /** 目标执行人必须是目标小组的在职成员，且不是申请人、不是当前执行人 */
    private void assertTargetHandler(Order order, Long departmentId, Long targetHandlerId, Long oldHandlerId) {
        User target = userMapper.selectById(targetHandlerId);
        if (target == null
                || !Boolean.TRUE.equals(target.getEnabled())
                || Boolean.TRUE.equals(target.getDimission())) {
            throw new BusinessException(ErrorCode.TRANSFER_TARGET_INVALID,
                    "转单目标不存在、已离职或已停用");
        }
        if (Objects.equals(targetHandlerId, oldHandlerId)) {
            throw new BusinessException(ErrorCode.TRANSFER_TARGET_INVALID,
                    "该工单当前已由所选员工执行，无需转单");
        }
        if (Objects.equals(targetHandlerId, order.getApplicantId())) {
            throw new BusinessException(ErrorCode.TRANSFER_TARGET_INVALID, "不能把工单转给申请人本人");
        }
        if (departmentId == null
                || !departmentService.activeMemberIdsOf(departmentId).contains(targetHandlerId)) {
            throw new BusinessException(ErrorCode.TRANSFER_TARGET_NOT_IN_GROUP,
                    "所选员工不在目标处理小组的在职成员中");
        }
    }

    /**
     * 转单记录可见性：能看主单的人（申请人 / 当前执行人 / 管理员）、
     * 参与过该工单转单的人（原执行人 / 目标执行人 / 发起人 / 处理人）、以及本单审批人。
     *
     * <p>与既有 {@code OrderTransferServiceImpl#assertCanViewTransfer} 同口径 ——
     * 详情页要同时装配「转交历史」与「转单申请」，两者可见性不一致会让详情页时好时坏。
     */
    private void assertCanView(Order order, Long currentUserId, List<OrderHandlerTransferRequest> rows) {
        if (RoleCode.isAdminOrAbove(currentRole())
                || Objects.equals(order.getApplicantId(), currentUserId)
                || Objects.equals(order.getActualFinalHandlerId(), currentUserId)) {
            return;
        }
        boolean participated = rows.stream().anyMatch(row ->
                Objects.equals(row.getFromHandlerId(), currentUserId)
                        || Objects.equals(row.getToHandlerId(), currentUserId)
                        || Objects.equals(row.getOperatorId(), currentUserId)
                        || Objects.equals(row.getToAdminId(), currentUserId));
        if (participated) {
            return;
        }
        boolean isApprover = orderNodeMapper.selectCount(Wrappers.<OrderApprovalNode>lambdaQuery()
                .eq(OrderApprovalNode::getOrderId, order.getId())
                .eq(OrderApprovalNode::getApproverId, currentUserId)) > 0;
        if (!isApprover) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权查看该工单的转单记录");
        }
    }

    // ------------------------------------------------------------------
    // 内部：装配与查询辅助
    // ------------------------------------------------------------------

    private List<TransferRequestVO> toVOList(List<OrderHandlerTransferRequest> rows, Order order,
                                             Long currentUserId) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, String> names = nameMapOf(rows);
        List<TransferRequestVO> result = new ArrayList<>(rows.size());
        for (OrderHandlerTransferRequest row : rows) {
            result.add(toVO(row, order, names, currentUserId));
        }
        return result;
    }

    private TransferRequestVO toVO(OrderHandlerTransferRequest row, Order order,
                                   Map<Long, String> names, Long currentUserId) {
        TransferRequestVO vo = new TransferRequestVO();
        vo.setId(row.getId());
        vo.setOrderId(row.getOrderId());
        vo.setOrderNo(order == null ? null : order.getOrderNo());
        vo.setTransferScope(row.getTransferScope());
        vo.setTransferScopeLabel(TransferScope.labelOf(row.getTransferScope()));
        vo.setFromDepartmentId(row.getFromDepartmentId());
        vo.setFromDepartmentName(departmentNameOf(row.getFromDepartmentId()));
        vo.setToDepartmentId(row.getToDepartmentId());
        vo.setToDepartmentName(departmentNameOf(row.getToDepartmentId()));
        vo.setFromHandlerId(row.getFromHandlerId());
        vo.setFromHandlerName(names.get(row.getFromHandlerId()));
        vo.setToHandlerId(row.getToHandlerId());
        vo.setToHandlerName(names.get(row.getToHandlerId()));
        vo.setToAdminId(row.getToAdminId());
        vo.setToAdminName(names.get(row.getToAdminId()));
        vo.setStatus(row.getStatus());
        vo.setStatusLabel(TransferRequestStatus.labelOf(row.getStatus()));
        vo.setReason(row.getReason());
        vo.setOperatorId(row.getOperatorId());
        vo.setOperatorName(names.get(row.getOperatorId()));
        vo.setDecideBy(row.getDecideBy());
        vo.setDecideByName(names.get(row.getDecideBy()));
        vo.setDecideAt(row.getDecideAt());
        vo.setDecideComment(row.getDecideComment());
        vo.setCreatedAt(row.getCreatedAt());
        vo.setUpdatedAt(row.getUpdatedAt());
        vo.setCanDecide(TransferRequestStatus.PENDING.name().equals(row.getStatus())
                && (RoleCode.isSuperAdmin(currentRole())
                || Objects.equals(row.getToAdminId(), currentUserId)));
        return vo;
    }

    private Map<Long, String> nameMapOf(Collection<OrderHandlerTransferRequest> rows) {
        Set<Long> ids = new LinkedHashSet<>();
        for (OrderHandlerTransferRequest row : rows) {
            ids.add(row.getFromHandlerId());
            ids.add(row.getToHandlerId());
            ids.add(row.getToAdminId());
            ids.add(row.getOperatorId());
            ids.add(row.getDecideBy());
        }
        return nameMapOfIds(ids);
    }

    /** user_id 集合 → 显示名（null id 自动跳过；空集合返回空 Map，避免 Map.of() 的 get(null) NPE） */
    private Map<Long, String> nameMapOfIds(Collection<Long> ids) {
        Set<Long> clean = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id != null) {
                clean.add(id);
            }
        }
        if (clean.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(clean).stream()
                .collect(Collectors.toMap(User::getId,
                        user -> user.getDisplayName() == null ? user.getUsername() : user.getDisplayName(),
                        (a, b) -> a));
    }

    /** 部门名（单条查；转单记录条数有限，且详情页已按工单聚合，不会形成 N+1 风暴） */
    private String departmentNameOf(Long departmentId) {
        if (departmentId == null) {
            return null;
        }
        Department department = departmentMapper.selectById(departmentId);
        return department == null ? null : department.getDeptName();
    }

    private String displayNameOf(Long userId) {
        if (userId == null) {
            return "（未知）";
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            return "（用户 " + userId + "）";
        }
        return user.getDisplayName() == null ? user.getUsername() : user.getDisplayName();
    }

    /** 本组管理员（多主管时取 user_id 最小者，与组织页「主主管」同口径） */
    private Long groupAdminIdOf(Long departmentId) {
        return departmentId == null ? null : departmentService.primaryManagerOf(departmentId);
    }

    private boolean isGroupAdmin(Long userId, Long departmentId) {
        return departmentId != null && departmentService.managerIdsOf(departmentId).contains(userId);
    }

    private OrderHandlerTransferRequest newRow(Order order, TransferScope scope,
                                               Long fromDepartmentId, Long toDepartmentId,
                                               Long fromHandlerId, Long toHandlerId, Long toAdminId,
                                               TransferRequestStatus status, Long operatorId,
                                               String reason, LocalDateTime now) {
        OrderHandlerTransferRequest row = new OrderHandlerTransferRequest();
        row.setOrderId(order.getId());
        row.setTransferScope(scope.name());
        row.setFromDepartmentId(fromDepartmentId);
        row.setToDepartmentId(toDepartmentId);
        row.setFromHandlerId(fromHandlerId);
        row.setToHandlerId(toHandlerId);
        row.setToAdminId(toAdminId);
        row.setStatus(status.name());
        row.setReason(reason);
        row.setOperatorId(operatorId);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private Order requireOrder(Long orderId) {
        Order order = orderId == null ? null : orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }
        return order;
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
}
