package com.enterprise.ticket.module.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.ticket.module.order.entity.OrderExtend;
import org.apache.ibatis.annotations.Mapper;

/**
 * 借用延期子工单 Mapper
 */
@Mapper
public interface OrderExtendMapper extends BaseMapper<OrderExtend> {
}
