package com.enterprise.ticket.module.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.ticket.module.inventory.entity.InventoryTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * 盘点任务 Mapper（P2）
 */
@Mapper
public interface InventoryTaskMapper extends BaseMapper<InventoryTask> {
}
