package com.enterprise.ticket.module.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.ticket.module.inventory.entity.InventoryTaskItem;
import org.apache.ibatis.annotations.Mapper;

/**
 * 盘点明细 Mapper（P2）
 */
@Mapper
public interface InventoryTaskItemMapper extends BaseMapper<InventoryTaskItem> {
}
