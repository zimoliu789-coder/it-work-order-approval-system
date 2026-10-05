package com.enterprise.ticket.module.device.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.ticket.module.device.entity.DeviceFault;
import org.apache.ibatis.annotations.Mapper;

/**
 * 设备故障记录 Mapper
 */
@Mapper
public interface DeviceFaultMapper extends BaseMapper<DeviceFault> {
}
