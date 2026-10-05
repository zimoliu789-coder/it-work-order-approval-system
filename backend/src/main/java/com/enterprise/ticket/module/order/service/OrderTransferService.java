package com.enterprise.ticket.module.order.service;

import com.enterprise.ticket.module.order.dto.OrderTransferRequest;
import com.enterprise.ticket.module.order.dto.vo.OrderTransferVO;
import com.enterprise.ticket.module.order.dto.vo.TransferCandidateVO;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 工单转交服务（ + 需求方  ）
 *
 * <p>可转交状态与 完全一致：{@code PENDING_DELIVERY / BORROWED / PENDING_RETURN}；
 * 终态（{@code RETURNED / REJECTED / CANCELLED}）拒绝转交。
 *
 * <p>权限边界：
 * <ul>
 *   <li>普通组员（当前 {@code actual_final_handler_id} 本人）→ 只能转给同 {@code handler_department_id}
 *       小组内 enabled 且在职的成员；</li>
 *   <li>super_admin → 不受小组限制，可转给任意系统内有效用户；</li>
 *   <li>申请人本人不可作为转交目标（需求方  ，避免「把活转回给需求方」）。</li>
 * </ul>
 *
 * <p>转交后执行人变更通过更新 {@code orders.actual_final_handler_id} 完成 —— 超时告警、
 * 归还确认权限、我的待处理列表全部按该字段查询，因此自动跟随新执行人，无需额外同步。
 */
public interface OrderTransferService {

    /**
     * 人工转交工单。
     *
     * <p>写操作：更新 {@code orders.actual_final_handler_id} → 追加 {@code order_handler_transfer}
     * → 通知新执行人 → 原执行人的该工单待办消息标记「工单已转交」。
     */
    void transfer(Long orderId, OrderTransferRequest request);

    /** 某工单的转交历史（工单详情时间线），按转交时间升序 */
    List<OrderTransferVO> listByOrder(Long orderId);

    /** 转交候选对象（弹窗下拉）：普通执行人＝同组其他在职成员；super_admin＝全部有效用户 */
    List<TransferCandidateVO> candidates(Long orderId);

    /**
     * 离职自动转交（需求方  ）：把离职员工名下所有在办工单转给同组在职成员。
     *
     * <p>选择策略经需求方确认：<b>负载最少优先</b>（名下在办工单数最少者），并列时随机 ——
     * 避免把离职者的活全压给同一个人。同组无其他在职成员时<b>不转交也不报错</b>，
     * 保留原样并返回 skipped 计数，由调用方告警（避免卡死流程）。
     *
     * @param userId       离职员工 user_id（其作为 {@code actual_final_handler_id} 的工单）
     * @param operatorName 离职员工姓名，用于转交备注文案
     */
    AutoTransferResult transferOnDimission(Long userId, String operatorName);

    /** 一批工单的转交次数（列表「已转交」标记用，一次查完避免 N+1） */
    Map<Long, Integer> transferCountByOrders(Collection<Long> orderIds);

    /**
     * 离职自动转交的执行结果。
     *
     * @param transferred 成功转交的单数
     * @param skipped     未能转交的单数（同组无其他在职成员，或并发下已被推进）
     */
    record AutoTransferResult(int transferred, int skipped) {
    }
}
