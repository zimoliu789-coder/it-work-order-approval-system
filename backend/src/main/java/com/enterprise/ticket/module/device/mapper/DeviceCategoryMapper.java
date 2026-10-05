package com.enterprise.ticket.module.device.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.ticket.module.device.entity.DeviceCategory;
import org.apache.ibatis.annotations.Mapper;

/**
 * 设备分类 Mapper
 *
 * <p>分类表不使用逻辑删除：删除前会校验「无子分类且无设备引用」，
 * 校验通过后即为物理删除（与  的 {@code biz_group} 处理方式一致）。
 */
@Mapper
public interface DeviceCategoryMapper extends BaseMapper<DeviceCategory> {
}
