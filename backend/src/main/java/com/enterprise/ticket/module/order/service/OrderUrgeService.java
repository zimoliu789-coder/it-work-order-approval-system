package com.enterprise.ticket.module.order.service;

import com.enterprise.ticket.module.order.dto.vo.OrderUrgeVO;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 工单催办服务（ 新增；规范 V1.1 未覆盖，按需求方 2026-09-18 确认方案实现）
 *
 * <p>两类催办：
 * <ul>
 *   <li><b>审批催办</b> —— 申请人对「审批中」工单催当前节点审批人，冷却键＝「工单 + 当前节点」，
 *       审批推进到下一步后可再次催办；</li>
 *   <li><b>归还催办</b> —— 实际执行人对「使用中且已到期 / 已超时」的工单催使用人归还。</li>
 * </ul>
 *
 * <p><b>催办不改变工单状态</b>：只发一条站内消息 + 在本表留痕（需求方明确）。
 */
public interface OrderUrgeService {

    /** 审批催办（申请人 → 当前审批节点审批人） */
    void urgeApproval(Long orderId);

    /** 归还催办（实际执行人 → 借用人） */
    void urgeReturn(Long orderId);

    /** 某工单的催办记录（工单详情时间线），按时间升序 */
    List<OrderUrgeVO> listByOrder(Long orderId);

    /** 单工单催办状态（详情页用） */
    UrgeStat statOfOrder(Long orderId);

    /** 一批工单的催办状态（列表页灰化 + 倒计时用，一次查完避免 N+1） */
    Map<Long, UrgeStat> statByOrders(Collection<Long> orderIds);

    /** 催办冷却时长（分钟），来自 {@code system_config.urge_cooldown_minutes}，默认 60 */
    int cooldownMinutes();

    /**
     * 催办状态快照。
     *
     * <p>审批催办按<b>节点</b>分桶返回（{@code nodeId → 最近一次催办时间}）而不是直接给剩余秒数：
     * 「当前节点是哪一个」只有调用方（已加载审批节点的列表 / 详情装配逻辑）才知道，
     * 由它比对当前节点即可得到准确的可催办性与倒计时，避免服务间来回查询。
     *
     * @param approvalLastAtByNode 各审批节点最近一次催办时间（无记录则为空 Map）
     * @param returnLastAt         最近一次归还催办时间；从未催办为 null
     */
    record UrgeStat(Map<Long, LocalDateTime> approvalLastAtByNode, LocalDateTime returnLastAt) {

        public static final UrgeStat EMPTY = new UrgeStat(Map.of(), null);
    }
}
