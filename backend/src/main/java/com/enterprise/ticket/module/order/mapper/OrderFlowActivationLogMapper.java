package com.enterprise.ticket.module.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.ticket.module.order.entity.OrderFlowActivationLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 流程节点激活决策日志 Mapper。
 *
 * <p>只做插入与按工单查询，因此沿用 {@link BaseMapper} 即可，
 * 不额外写 @Select —— 详情页的激活史按 {@code order_id} 升序取，
 * 由 {@code idx_ofal_order(order_id, created_at)} 索引覆盖。
 */
@Mapper
public interface OrderFlowActivationLogMapper extends BaseMapper<OrderFlowActivationLog> {
}
