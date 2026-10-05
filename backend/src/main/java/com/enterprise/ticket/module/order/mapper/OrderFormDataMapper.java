package com.enterprise.ticket.module.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.ticket.module.order.entity.OrderFormData;
import org.apache.ibatis.annotations.Mapper;

/**
 * 工单自定义表单数据 Mapper
 */
@Mapper
public interface OrderFormDataMapper extends BaseMapper<OrderFormData> {
}
