package com.enterprise.ticket.module.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.ticket.module.order.entity.OrderForceOperation;
import org.apache.ibatis.annotations.Mapper;

/**
 * 超管强制干预记录 Mapper（）
 *
 * <p>保持纯 BaseMapper：仅需插入与「按工单倒序查时间线」，无需注解 SQL。
 */
@Mapper
public interface OrderForceOperationMapper extends BaseMapper<OrderForceOperation> {
}
