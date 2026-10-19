package com.enterprise.ticket.module.device.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.ticket.module.device.entity.DeviceModel;
import org.apache.ibatis.annotations.Mapper;

/**
 * 设备型号字典 Mapper（R3）。
 *
 * <p>没有自定义 SQL：字典的查询/去重都能用 {@code LambdaQueryWrapper} 表达。
 * 存量整理（{@code syncFromLedger}）走的是「先查后写」，不是批量 SQL ——
 * 那条路径一年跑几次，可读性比吞吐更重要。
 */
@Mapper
public interface DeviceModelMapper extends BaseMapper<DeviceModel> {
}
