package com.enterprise.ticket.module.inventory.dto;

import lombok.Data;

/**
 * 盘点任务列表查询（P2）
 */
@Data
public class InventoryTaskQuery {

    private long page = 1L;

    private long size = 20L;

    /** 状态筛选（IN_PROGRESS / COMPLETED / CANCELLED），空为不限 */
    private String status;

    /** 关键词（任务编号或名称，模糊） */
    private String keyword;
}
