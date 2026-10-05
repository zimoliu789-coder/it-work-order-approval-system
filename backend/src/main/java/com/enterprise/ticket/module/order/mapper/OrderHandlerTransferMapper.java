package com.enterprise.ticket.module.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.ticket.module.order.entity.OrderHandlerTransfer;
import org.apache.ibatis.annotations.Mapper;

/**
 * 工单转交记录 Mapper
 *
 * <p>保持纯 BaseMapper：统计类查询（转交次数、「已转交」筛选）都用
 * {@code selectList(...).select(只需要的列)} 在服务层聚合，避免把业务语义写进注解 SQL
 * —— 与 「延期次数统计」的做法保持一致。
 */
@Mapper
public interface OrderHandlerTransferMapper extends BaseMapper<OrderHandlerTransfer> {
}
