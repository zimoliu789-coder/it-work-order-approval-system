package com.enterprise.ticket.module.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.ticket.module.order.entity.OrderExtendApprovalNode;
import org.apache.ibatis.annotations.Mapper;

/**
 * 延期子工单审批快照 Mapper
 */
@Mapper
public interface OrderExtendApprovalNodeMapper extends BaseMapper<OrderExtendApprovalNode> {
}
