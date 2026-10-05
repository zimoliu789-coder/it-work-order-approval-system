package com.enterprise.ticket.module.order.service;

import com.enterprise.ticket.module.order.dto.OrderForceRequest;
import com.enterprise.ticket.module.order.dto.vo.OrderForceOperationVO;

import java.util.List;

/**
 * 超管强制干预服务（；规范 V1.1 未覆盖）
 *
 * <p>仅 {@code super_admin} 可用；四类操作：强制驳回 / 强制终止 / 强制转交审批 / 强制转交执行人。
 * 每笔操作「必填原因 + 高危审计（调用方注解）+ 时间线记录 + 通知相关人」。
 * <b>不提供「强制通过」</b>——审批结论必须由审批人本人负责。
 */
public interface OrderForceOperationService {

    /**
     * 执行一次强制干预（按 {@code operationType} 分派）。
     *
     * @throws com.enterprise.ticket.common.exception.BusinessException
     *         类型非法 / 原因缺失 / 当前状态不支持 / 目标人缺失或无效
     */
    void force(Long orderId, OrderForceRequest request);

    /**
     * 某工单的强制干预历史（工单详情时间线）。
     *
     * <p>调用方须已完成工单可见性校验；本方法内部再基于「申请人 / 当前执行人 / 审批人 /
     * admin 以上」复核一次（与工单详情可见性同口径）。
     */
    List<OrderForceOperationVO> listByOrder(Long orderId);
}
