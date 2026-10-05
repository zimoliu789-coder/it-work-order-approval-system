package com.enterprise.ticket.module.order.service;

import com.enterprise.ticket.common.api.PageResult;
import com.enterprise.ticket.module.order.dto.OrderExtendApproveRequest;
import com.enterprise.ticket.module.order.dto.OrderExtendRequest;
import com.enterprise.ticket.module.order.dto.vo.OrderExtendVO;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 借用延期子工单服务
 *
 * <p>职责边界：延期是**主工单的从属子单**，只延长时间，不改变主工单状态、不动设备、
 * 不重新分配执行人。所有方法都在服务层做角色/归属判定（：前端隐藏只是体验）。
 */
public interface OrderExtendService {

    /**
     * 申请人发起延期申请
     *
     * <p>校验：主单必须处于 {@code BORROWED}；发起人必须是主单申请人；短期借用（有计划结束时间）；
     * 新结束时间晚于当前时间；未超过 {@code extend_max_count}；当前没有审批中的延期。
     * 通过后生成延期快照并给当前审批节点推送待办。
     *
     * @return 延期子工单 id
     */
    Long request(Long orderId, OrderExtendRequest request);

    /** 延期审批待办（当前轮到我审批的延期单） */
    PageResult<OrderExtendVO> pageMyApproval(long page, long size);

    /** 审批延期（通过 / 驳回）；驳回必须填写原因 */
    void approve(Long extendId, OrderExtendApproveRequest request);

    /** 某主工单下的全部延期记录（按时间升序），用于工单详情时间线 */
    List<OrderExtendVO> listByOrder(Long orderId);

    /**
     * 批量统计延期情况，供列表页一次性判断「能否发起延期」而不产生 N+1。
     *
     * @param orderIds 主工单 id 集合
     * @return 主工单 id → 统计；未出现过延期的工单不会出现在 Map 中
     */
    Map<Long, ExtendStat> statByOrders(Collection<Long> orderIds);

    /**
     * 单个主工单的延期统计（{@code statByOrders} 的单条版本）
     */
    ExtendStat statOfOrder(Long orderId);

    /**
     * 延期统计：已使用次数（含审批中）+ 是否有审批中的单据。
     *
     * @param used    已发起的延期数（不含已驳回 —— 被驳回不占用次数，「驳回不影响原有借用时间」）
     * @param pending 是否存在审批中的延期单
     */
    record ExtendStat(int used, boolean pending) {
    }
}
