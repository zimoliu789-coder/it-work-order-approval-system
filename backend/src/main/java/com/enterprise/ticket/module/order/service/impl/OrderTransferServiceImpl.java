package com.enterprise.ticket.module.order.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ticket.common.alert.AlertLevel;
import com.enterprise.ticket.common.alert.AlertService;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.MessageType;
import com.enterprise.ticket.common.constant.OrderStatus;
import com.enterprise.ticket.common.constant.RoleCode;
import com.enterprise.ticket.common.constant.TransferType;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.util.SecurityUtils;
import com.enterprise.ticket.module.department.dto.vo.DepartmentOptionVO;
import com.enterprise.ticket.module.department.service.DepartmentService;
import com.enterprise.ticket.module.message.entity.Message;
import com.enterprise.ticket.module.message.mapper.MessageMapper;
import com.enterprise.ticket.module.message.service.MessageService;
import com.enterprise.ticket.module.order.dto.OrderTransferRequest;
import com.enterprise.ticket.module.order.dto.vo.OrderTransferVO;
import com.enterprise.ticket.module.order.dto.vo.TransferCandidateVO;
import com.enterprise.ticket.module.order.entity.Order;
import com.enterprise.ticket.module.order.entity.OrderApprovalNode;
import com.enterprise.ticket.module.order.entity.OrderHandlerTransfer;
import com.enterprise.ticket.module.order.mapper.OrderApprovalNodeMapper;
import com.enterprise.ticket.module.order.mapper.OrderHandlerTransferMapper;
import com.enterprise.ticket.module.order.mapper.OrderMapper;
import com.enterprise.ticket.module.order.service.OrderTransferService;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 工单转交服务实现（ + 需求方  ）
 *
 * <p><b>并发语义</b>：执行人变更用条件 UPDATE（{@code WHERE id=? AND status=? AND actual_final_handler_id=?}）
 * 完成 —— 两个管理员同时转交同一工单时，只有第一个能命中行，第二个拿到 0 行后抛
 * {@code TRANSFER_CONFLICT}，不会出现「两条转交记录、执行人以最后一条为准」的错乱。
 *
 * <p><b>「转交后一切跟随新执行人」是如何实现的</b>：超时告警（{@code BorrowJobServiceImpl}）、
 * 归还确认权限、我的待处理列表全部以 {@code actual_final_handler_id} 为查询键，
 * 因此只改这一个字段就自然生效，无需在别处做同步 —— 这也是需求方「orders 表不加字段」的前提。
 *
 * <p><b>跨模块依赖</b>：单表数据（消息）走 {@code MessageMapper}；
 * 需要「部门成员」这类**有业务口径**的数据时走 {@code DepartmentService}，
 * 而不是自己去查 {@code user_department} —— 成员判定（主部门 ∪ 兼职 + 在职）
 * 只允许有一个实现，否则又会出现「组织页显示 3 人、转交候选人 0 人」的错位。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderTransferServiceImpl implements OrderTransferService {

    /**
     * ：仅这三种状态允许转交。
     *
     * <p>状态名清单由 {@link OrderStatus#transferableNames()} 投影而来 —— 不在此处再写一份
     * 字面量，否则「写接口的 {@code IN} 条件」与「VO 里的 {@code canTransfer}」会各自漂移，
     * 出现「按钮可点但接口拒绝」（或反过来）的错位。一致性由 {@code OrderStatusTest} 固化。
     */
    private static final List<String> TRANSFERABLE_STATUSES = OrderStatus.transferableNames();

    /** 原执行人待办消息的标记前缀（「标记『工单已转交』」） */
    private static final String TRANSFERRED_MARK = "【工单已转交】";

    private final OrderMapper orderMapper;
    private final OrderHandlerTransferMapper transferMapper;
    private final OrderApprovalNodeMapper orderNodeMapper;
    /** 成员口径的唯一出处（主部门 ∪ 兼职）；不要在本类再写一份查询 */
    private final DepartmentService departmentService;
    private final UserMapper userMapper;
    private final MessageMapper messageMapper;
    private final MessageService messageService;
    /**
     * 「离职自动转交失败」的人工兜底告警出口（只发超管）。
     *
     * <p>依赖方向是单向的（AlertService 只依赖 message / log / mail / 配置 / user），
     * 且 {@code DepartmentServiceImpl} 已在用它，因此不会引入循环依赖。
     */
    private final AlertService alertService;

    // ------------------------------------------------------------------
    // 人工转交
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void transfer(Long orderId, OrderTransferRequest request) {
        Long currentUserId = requireCurrentUserId();
        boolean superAdmin = RoleCode.isSuperAdmin(currentRole());

        Order order = requireOrder(orderId);
        assertTransferable(order);
        Long oldHandlerId = order.getActualFinalHandlerId();

        // ：普通组员只能转自己的单；super_admin 可任意转交（离职兜底等场景）
        if (!superAdmin && !Objects.equals(oldHandlerId, currentUserId)) {
            throw new BusinessException(ErrorCode.TRANSFER_NOT_HANDLER);
        }

        Long newHandlerId = request.getNewHandlerId();
        User target = newHandlerId == null ? null : userMapper.selectById(newHandlerId);
        if (target == null
                || !Boolean.TRUE.equals(target.getEnabled())
                || Boolean.TRUE.equals(target.getDimission())) {
            throw new BusinessException(ErrorCode.TRANSFER_TARGET_INVALID,
                    "转交目标不存在、已离职或已停用");
        }
        if (Objects.equals(newHandlerId, oldHandlerId)) {
            throw new BusinessException(ErrorCode.TRANSFER_TARGET_INVALID,
                    "该工单当前已由所选员工执行，无需转交");
        }
        // 需求方  ：不能转给申请人本人
        if (Objects.equals(newHandlerId, order.getApplicantId())) {
            throw new BusinessException(ErrorCode.TRANSFER_TARGET_INVALID, "不能把工单转交给申请人本人");
        }
        // ：普通组员限同小组在职成员；super_admin 不受小组限制
        if (!superAdmin && !activeHandlerIds(order.getHandlerDepartmentId()).contains(newHandlerId)) {
            throw new BusinessException(ErrorCode.TRANSFER_TARGET_NOT_IN_GROUP);
        }

        String comment = request.getComment().trim();
        LocalDateTime now = LocalDateTime.now();
        applyTransfer(order, oldHandlerId, newHandlerId, currentUserId, comment, TransferType.MANUAL, now);

        String oldName = displayNameOf(oldHandlerId);
        messageService.send(newHandlerId, MessageType.ORDER_TRANSFERRED, "工单转交待办",
                "工单 %s 由 %s 转交给您（转交原因：%s），请及时处理。"
                        .formatted(order.getOrderNo(), oldName, comment),
                order.getId());
        // 原执行人：把「待办已移交」讲清楚，否则只看到标题被改会莫名
        messageService.send(oldHandlerId, MessageType.ORDER_TRANSFERRED, "工单已转交",
                "您名下的工单 %s 已转交给 %s，该工单的后续待办已移交。"
                        .formatted(order.getOrderNo(), displayNameOf(newHandlerId)),
                order.getId());
        log.info("工单 {}（{}）执行人由 {} 转交给 {}，操作人 {}",
                order.getOrderNo(), OrderStatus.labelOf(order.getStatus()),
                oldHandlerId, newHandlerId, currentUserId);
    }

    // ------------------------------------------------------------------
    // 离职自动转交（需求方  ）
    // ------------------------------------------------------------------

    /**
     * 离职自动转交：把离职者名下在办工单改派给同组其他在职成员（负载最少优先、并列随机）。
     *
     * <h2>2026-10-19 裁决（方案 B）：自动转交保留，「本组管理员手动操作」= 失败时的人工兜底</h2>
     * <p>批次 2 的转单需求写着「执行人<b>离职不自动转单</b>，由本组管理员手动操作」，
     * 与本方法相反。用户裁决<b>保留自动转交</b>，把那句话理解为「自动转交办不成时的人工兜底」。
     * 因此本方法的行为是：
     * <ol>
     *   <li><b>能转就转</b>（下方原逻辑不动）：同组有在职成员即按负载分配；</li>
     *   <li><b>转不了就喊人</b>：同组无其他在职成员 / 并发冲突导致条件 UPDATE 落空时，
     *       除保留原有的 {@code log.warn} 外，额外发一条 P1 告警给超管
     *       （{@link MessageType#DIMISSION_TRANSFER_FALLBACK}），正文附工单号清单与可执行动作。</li>
     * </ol>
     *
     * <h2>为什么兜底消息只发超管</h2>
     * <p>此刻工单挂在<b>已禁用</b>的离职账号上，而组管理员在现有权限模型下动不了别人的单
     * （转交要求本人是当前执行人、同组转单要求发起人是当前执行人、强制转交要求超管级权限码）。
     * 把「要你处理」发给做不了事的人，等于把工单推进死胡同。详见 {@link MessageType#DIMISSION_TRANSFER_FALLBACK}。
     *
     * <p><b>绝不阻断离职流程</b>：任何一步失败都只记日志 / 发告警，异常不向外抛
     * —— 离职本身（账号禁用 + 设备转待收回）必须完成。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AutoTransferResult transferOnDimission(Long userId, String operatorName) {
        if (userId == null) {
            return new AutoTransferResult(0, 0);
        }
        List<Order> orders = orderMapper.selectList(Wrappers.<Order>lambdaQuery()
                .eq(Order::getActualFinalHandlerId, userId)
                .in(Order::getStatus, TRANSFERABLE_STATUSES)
                .orderByAsc(Order::getId));
        if (orders.isEmpty()) {
            return new AutoTransferResult(0, 0);
        }

        String name = operatorName == null || operatorName.isBlank() ? "该员工" : operatorName;
        LocalDateTime now = LocalDateTime.now();
        int transferred = 0;
        int skipped = 0;
        // 未能转交的工单（组内无接手人 / 并发冲突）—— 收敛成一条告警，不逐单刷屏
        List<Order> skippedOrders = new ArrayList<>();
        // 同一小组的候选池只算一次（离职员工的在办工单往往集中在同一个小组）
        Map<Long, List<Long>> poolCache = new HashMap<>();

        for (Order order : orders) {
            Long groupId = order.getHandlerDepartmentId();
            List<Long> pool = poolCache.computeIfAbsent(
                    groupId == null ? -1L : groupId, key -> activeHandlerIds(groupId));
            List<Long> candidates = pool.stream()
                    .filter(id -> !Objects.equals(id, userId))
                    .filter(id -> !Objects.equals(id, order.getApplicantId()))
                    .toList();
            if (candidates.isEmpty()) {
                // 同组无其他在职成员：不转交也不报错，保留原样交给管理员处理（避免流程卡死）
                skipped++;
                skippedOrders.add(order);
                log.warn("离职自动转交跳过：工单 {} 的最终处理部门（id={}）内无其他在职成员，"
                        + "原执行人 {} 已离职，需管理员手工转交或收回", order.getOrderNo(), groupId, userId);
                continue;
            }

            // 负载最少优先；并列随机（需求方 2026-09-18 确认的策略）
            // 注意：inFlightCounts 只返回「至少有一单」的人（SQL 分组结果天然不含 0），
            // 因此最小值必须在【候选集合】上取 —— 若在 loads.values() 上取最小值，当某候选 0 单、
            // 另一候选 ≥1 单时会把「有单的人」当成最少负载，恰好选反（单测 transferOnDimission_picksIdleCandidate 覆盖）。
            Map<Long, Integer> loads = inFlightCounts(candidates);
            int min = candidates.stream()
                    .mapToInt(id -> loads.getOrDefault(id, 0))
                    .min()
                    .orElse(0);
            List<Long> leastLoaded = candidates.stream()
                    .filter(id -> loads.getOrDefault(id, 0) == min)
                    .toList();
            Long newHandlerId = leastLoaded.get(ThreadLocalRandom.current().nextInt(leastLoaded.size()));

            int updated = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                    .eq(Order::getId, order.getId())
                    .eq(Order::getStatus, order.getStatus())
                    .eq(Order::getActualFinalHandlerId, userId)
                    .set(Order::getActualFinalHandlerId, newHandlerId));
            if (updated == 0) {
                // 并发下工单已被归还 / 被他人转交：跳过即可，不视为失败（幂等）。
                // 刻意**不**计入 skippedOrders —— 这类单子已经不再挂在这个离职账号上，
                // 没有任何待人工处置的动作；把它们写进告警只会制造「点进去发现没事」的噪音。
                skipped++;
                continue;
            }

            writeTransferRecord(order.getId(), userId, newHandlerId, userId,
                    "实际执行人 " + name + " 已离职，系统自动转交给同组在职成员",
                    TransferType.AUTO_DIMISSION, now);
            messageService.send(newHandlerId, MessageType.ORDER_TRANSFERRED, "工单转交待办（离职自动转交）",
                    "%s 已离职，其名下的工单 %s 已自动转交给您，请及时处理。"
                            .formatted(name, order.getOrderNo()),
                    order.getId());
            transferred++;
        }
        if (!skippedOrders.isEmpty()) {
            // 方案 B 的「人工兜底」出口：自动转交办不成时必须喊人，否则这批单子彻底沉底
            notifyDimissionFallback(name, userId, skippedOrders);
        }
        if (transferred > 0 || skipped > 0) {
            log.info("离职自动转交完成：{} 名下在办工单 {} 笔，成功转交 {} 笔，跳过 {} 笔",
                    name, orders.size(), transferred, skipped);
        }
        return new AutoTransferResult(transferred, skipped);
    }

    /**
     * 「自动转交失败 → 请超管人工处置」的告警。
     *
     * <p><b>收件人只有超管</b>：组管理员在现有权限模型下没有处置这类工单的能力
     * （详见 {@link MessageType#DIMISSION_TRANSFER_FALLBACK}），而超管有三条可执行路径。
     * 正文里直接把三条动作写出来 —— 告警的价值在于「收件人知道下一步点哪里」，
     * 而不是「有人出事了」。
     *
     * <p><b>一次离职只发一条</b>：同一批失败工单合并成一条告警（含清单），
     * 而不是每单一条。离职往往一次带出十几笔在办单，逐单告警会把消息中心刷屏，
     * 真问题反而被淹掉。
     *
     * <p>告警失败只记日志：离职流程的主体（禁用账号 + 设备转待收回）不能因为通知问题失败。
     */
    private void notifyDimissionFallback(String name, Long userId, List<Order> skippedOrders) {
        Map<Long, String> groupNames = handlerGroupNameMap();
        StringBuilder list = new StringBuilder();
        for (Order order : skippedOrders) {
            Long groupId = order.getHandlerDepartmentId();
            String groupLabel = groupId == null
                    ? "（无处理小组）"
                    : groupNames.getOrDefault(groupId, "处理小组#" + groupId);
            list.append("· ").append(order.getOrderNo())
                    .append("（").append(OrderStatus.labelOf(order.getStatus()))
                    .append("，归属 ").append(groupLabel).append("）\n");
        }
        log.warn("离职自动转交失败 {} 笔，需人工处置：员工 {}（id={}）名下 {}",
                skippedOrders.size(), name, userId, list.toString().replace("\n", " / "));
        try {
            alertService.alert(AlertLevel.P1, MessageType.DIMISSION_TRANSFER_FALLBACK,
                    "离职工单待人工转交：" + name + "（" + skippedOrders.size() + " 笔）",
                    name + " 已标记离职并禁用账号，以下在办工单未能自动转交 —— "
                            + "其所属处理小组内没有其他在职成员可接手，工单目前仍挂在离职账号上：\n"
                            + list
                            + "\n可执行动作（三选一）：\n"
                            + "① 工单详情 → 强制干预 →「强制转交执行人」，指定任意在职 IT 接手；\n"
                            + "② 由超管代发一次同组转单（系统允许超管代离职员工发起）；\n"
                            + "③ 若设备已无法收回，用「强制终止」结单。\n"
                            + "根治办法：给该处理小组补充在职成员，或在「组织与人员」调整其归属小组。",
                    "DIMISSION_TRANSFER_FALLBACK",
                    "leavingUserId=" + userId + " | name=" + name
                            + " | skipped=" + skippedOrders.size()
                            + " | orders=" + skippedOrders.stream().map(Order::getOrderNo).toList());
        } catch (RuntimeException e) {
            log.warn("离职转交兜底告警发送失败（不影响离职流程）：{}", e.getMessage());
        }
    }

    /**
     * 处理小组 id → 名称（供告警正文可读）。
     *
     * <p>走 {@link DepartmentService#handlerGroupOptions()} 而不是新建一个 mapper 依赖：
     * 组数量是个位数、这条路径只在「离职转交失败」这种低频场景执行，多一次查询换来的是
     * 「本类不必认识 department 表」这个更干净的边界。取不到名称时降级为 {@code 处理小组#id}。
     */
    private Map<Long, String> handlerGroupNameMap() {
        Map<Long, String> names = new HashMap<>();
        try {
            for (DepartmentOptionVO option : departmentService.handlerGroupOptions()) {
                if (option != null && option.getId() != null) {
                    names.put(option.getId(), option.getDeptName());
                }
            }
        } catch (RuntimeException e) {
            log.warn("离职转交兜底告警：处理小组名称解析失败，正文将退化为 id：{}", e.getMessage());
        }
        return names;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    @Override
    public List<OrderTransferVO> listByOrder(Long orderId) {
        Long currentUserId = requireCurrentUserId();
        Order order = requireOrder(orderId);
        List<OrderHandlerTransfer> rows = transferMapper.selectList(Wrappers.<OrderHandlerTransfer>lambdaQuery()
                .eq(OrderHandlerTransfer::getOrderId, orderId)
                .orderByAsc(OrderHandlerTransfer::getId));
        assertCanViewTransfer(order, currentUserId, rows);
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<Long> userIds = new LinkedHashSet<>();
        rows.forEach(row -> {
            userIds.add(row.getOldHandlerId());
            userIds.add(row.getNewHandlerId());
            userIds.add(row.getTransferOperatorId());
        });
        Map<Long, String> userNames = userNameMap(userIds);
        return rows.stream().map(row -> toVO(row, order, userNames)).toList();
    }

    @Override
    public List<TransferCandidateVO> candidates(Long orderId) {
        Long currentUserId = requireCurrentUserId();
        boolean superAdmin = RoleCode.isSuperAdmin(currentRole());
        Order order = requireOrder(orderId);
        assertTransferable(order);

        Long oldHandlerId = order.getActualFinalHandlerId();
        if (!superAdmin && !Objects.equals(oldHandlerId, currentUserId)) {
            throw new BusinessException(ErrorCode.TRANSFER_NOT_HANDLER);
        }

        List<Long> pool;
        if (superAdmin) {
            // ：super_admin 不受小组限制
            pool = userMapper.selectList(Wrappers.<User>lambdaQuery()
                            .eq(User::getEnabled, true)
                            .eq(User::getDimission, false)
                            .orderByAsc(User::getId))
                    .stream().map(User::getId).toList();
        } else {
            pool = activeHandlerIds(order.getHandlerDepartmentId());
        }
        List<Long> filtered = pool.stream()
                .filter(id -> !Objects.equals(id, oldHandlerId))
                .filter(id -> !Objects.equals(id, order.getApplicantId()))
                .toList();
        if (filtered.isEmpty()) {
            return List.of();
        }
        Map<Long, String> userNames = userNameMap(new LinkedHashSet<>(filtered));
        Map<Long, Integer> loads = inFlightCounts(filtered);
        return filtered.stream().map(id -> {
            TransferCandidateVO vo = new TransferCandidateVO();
            vo.setUserId(id);
            vo.setDisplayName(userNames.get(id));
            vo.setInFlightCount(loads.getOrDefault(id, 0));
            return vo;
        }).toList();
    }

    @Override
    public Map<Long, Integer> transferCountByOrders(Collection<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return Map.of();
        }
        List<OrderHandlerTransfer> rows = transferMapper.selectList(
                Wrappers.<OrderHandlerTransfer>lambdaQuery()
                        .select(OrderHandlerTransfer::getOrderId)
                        .in(OrderHandlerTransfer::getOrderId, orderIds));
        Map<Long, Integer> counts = new HashMap<>();
        for (OrderHandlerTransfer row : rows) {
            counts.merge(row.getOrderId(), 1, Integer::sum);
        }
        return counts;
    }

    // ------------------------------------------------------------------
    // 内部方法
    // ------------------------------------------------------------------

    /**
     * 落地一次转交：条件 UPDATE 执行人 → 追加历史 → 标记原执行人待办消息。
     *
     * <p>条件 UPDATE 命中 0 行说明工单已被并发推进（归还 / 撤回 / 他人转交），
     * 此时抛 {@code TRANSFER_CONFLICT} 让整笔事务回滚，绝不写入「历史与实际不符」的记录。
     */
    private void applyTransfer(Order order, Long oldHandlerId, Long newHandlerId, Long operatorId,
                               String comment, TransferType type, LocalDateTime now) {
        int updated = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .eq(Order::getId, order.getId())
                .eq(Order::getStatus, order.getStatus())
                .eq(Order::getActualFinalHandlerId, oldHandlerId)
                .set(Order::getActualFinalHandlerId, newHandlerId));
        if (updated == 0) {
            throw new BusinessException(ErrorCode.TRANSFER_CONFLICT);
        }
        writeTransferRecord(order.getId(), oldHandlerId, newHandlerId, operatorId, comment, type, now);
        markOldHandlerTodo(order.getId(), oldHandlerId, now, type);
    }

    private void writeTransferRecord(Long orderId, Long oldHandlerId, Long newHandlerId,
                                     Long operatorId, String comment, TransferType type, LocalDateTime now) {
        OrderHandlerTransfer record = new OrderHandlerTransfer();
        record.setOrderId(orderId);
        record.setOldHandlerId(oldHandlerId);
        record.setNewHandlerId(newHandlerId);
        record.setTransferOperatorId(operatorId == null ? oldHandlerId : operatorId);
        record.setTransferComment(comment);
        record.setTransferType(type.name());
        record.setCreatedAt(now);
        transferMapper.insert(record);
    }

    /**
     * 「原执行人的该工单待办消息标记『工单已转交』」。
     *
     * <p>除打标记外还<b>置为已读</b>：该待办已不属于原执行人，继续挂在铃铛未读数里会误导，
     * 也会让「未读消息数」与实际待办数长期不一致。
     */
    private void markOldHandlerTodo(Long orderId, Long oldHandlerId, LocalDateTime now, TransferType type) {
        messageMapper.update(null, Wrappers.<Message>lambdaUpdate()
                .eq(Message::getUserId, oldHandlerId)
                .eq(Message::getOrderId, orderId)
                .eq(Message::getIsRead, false)
                .notLikeRight(Message::getTitle, TRANSFERRED_MARK)
                .set(Message::getIsRead, true)
                .set(Message::getReadAt, now)
                .setSql("title = CONCAT('" + TRANSFERRED_MARK + "', title)"));
        if (type == TransferType.AUTO_DIMISSION) {
            // 离职账号已被禁用、无法登录，再发消息只会占用存储
            log.debug("离职自动转交：跳过向已禁用账号 {} 发送转交说明", oldHandlerId);
        }
    }

    private OrderTransferVO toVO(OrderHandlerTransfer row, Order order, Map<Long, String> userNames) {
        OrderTransferVO vo = new OrderTransferVO();
        vo.setId(row.getId());
        vo.setOrderId(row.getOrderId());
        vo.setOrderNo(order == null ? null : order.getOrderNo());
        vo.setOldHandlerId(row.getOldHandlerId());
        vo.setOldHandlerName(userNames.get(row.getOldHandlerId()));
        vo.setNewHandlerId(row.getNewHandlerId());
        vo.setNewHandlerName(userNames.get(row.getNewHandlerId()));
        vo.setTransferOperatorId(row.getTransferOperatorId());
        vo.setTransferOperatorName(userNames.get(row.getTransferOperatorId()));
        vo.setTransferComment(row.getTransferComment());
        vo.setTransferType(row.getTransferType());
        vo.setTransferTypeLabel(TransferType.labelOf(row.getTransferType()));
        vo.setCreatedAt(row.getCreatedAt());
        return vo;
    }

    /** 可转交状态校验；终态一律拒绝 */
    private void assertTransferable(Order order) {
        if (!TRANSFERABLE_STATUSES.contains(order.getStatus())) {
            throw new BusinessException(ErrorCode.TRANSFER_ORDER_STATUS_INVALID,
                    "工单当前状态为「" + OrderStatus.labelOf(order.getStatus()) + "」，不允许转交");
        }
        if (order.getActualFinalHandlerId() == null) {
            throw new BusinessException(ErrorCode.TRANSFER_NOT_HANDLER,
                    "该工单尚未分配实际执行人，无法转交");
        }
    }

    /**
     * 转交历史可见性：能看主单的人（申请人 / 当前执行人 / 管理员），
     * 或曾经参与过该工单转交的人（原执行人 / 新执行人 / 操作人）。
     */
    private void assertCanViewTransfer(Order order, Long currentUserId, List<OrderHandlerTransfer> rows) {
        if (RoleCode.isAdminOrAbove(currentRole())
                || Objects.equals(order.getApplicantId(), currentUserId)
                || Objects.equals(order.getActualFinalHandlerId(), currentUserId)) {
            return;
        }
        boolean participated = rows.stream().anyMatch(row ->
                Objects.equals(row.getOldHandlerId(), currentUserId)
                        || Objects.equals(row.getNewHandlerId(), currentUserId)
                        || Objects.equals(row.getTransferOperatorId(), currentUserId));
        if (participated) {
            return;
        }
        // 与「工单详情可见性」（OrderServiceImpl#assertCanView）保持一致：本工单审批人同样可见，
        // 否则详情页装配时间线时会被 403 打断
        boolean isApprover = orderNodeMapper.selectCount(Wrappers.<OrderApprovalNode>lambdaQuery()
                .eq(OrderApprovalNode::getOrderId, order.getId())
                .eq(OrderApprovalNode::getApproverId, currentUserId)) > 0;
        if (!isApprover) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权查看该工单的转交记录");
        }
    }

    /** 最终处理部门内「启用且在职」的成员 user_id；无可用成员返回空列表 */
    private List<Long> activeHandlerIds(Long handlerGroupId) {
        return departmentService.activeMemberIdsOf(handlerGroupId);
    }

    /** 一批员工名下的在办工单数（待交付 / 使用中 / 待收回），用于「负载最少优先」 */
    private Map<Long, Integer> inFlightCounts(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        List<Order> rows = orderMapper.selectList(Wrappers.<Order>lambdaQuery()
                .select(Order::getActualFinalHandlerId)
                .in(Order::getActualFinalHandlerId, userIds)
                .in(Order::getStatus, TRANSFERABLE_STATUSES));
        Map<Long, Integer> counts = new HashMap<>();
        for (Order row : rows) {
            counts.merge(row.getActualFinalHandlerId(), 1, Integer::sum);
        }
        return counts;
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

    private String displayNameOf(Long userId) {
        return userNameMap(List.of(userId)).getOrDefault(userId, "该员工");
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
