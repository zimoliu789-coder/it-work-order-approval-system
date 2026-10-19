package com.enterprise.ticket.module.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.ticket.module.order.entity.OrderHandlerTransferRequest;
import org.apache.ibatis.annotations.Mapper;

/**
 * 转单申请 Mapper（V55）
 *
 * <p>保持纯 BaseMapper：所有条件（订单 / 待处理人 / 状态）都在服务层用
 * {@code LambdaQueryWrapper} 表达，与既有 {@link OrderHandlerTransferMapper} 一致 ——
 * 业务语义不写进注解 SQL，改条件时不必同时改 XML。
 */
@Mapper
public interface OrderHandlerTransferRequestMapper extends BaseMapper<OrderHandlerTransferRequest> {
}
