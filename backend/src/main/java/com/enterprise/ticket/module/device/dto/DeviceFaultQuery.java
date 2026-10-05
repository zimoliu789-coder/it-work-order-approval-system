package com.enterprise.ticket.module.device.dto;

import lombok.Data;

/**
 * 设备故障记录查询条件
 */
@Data
public class DeviceFaultQuery {

    /** 页码，从 1 开始 */
    private long page = 1L;

    /** 每页条数 */
    private long size = 10L;

    /**
     * 故障状态过滤：{@code null} 全部、{@code PENDING_REPAIR} 待维修、
     * {@code REPAIRED} 维修完成、{@code SCRAPPED} 已报废。
     */
    private String status;

    /** 设备筛选（按设备名 / 资产编号模糊匹配） */
    private String deviceKeyword;
}
