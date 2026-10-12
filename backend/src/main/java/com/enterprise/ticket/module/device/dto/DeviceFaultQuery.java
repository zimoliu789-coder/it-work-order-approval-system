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
     * 故障状态过滤：{@code null} 全部；V50 起取值为
     * {@code PENDING_REPAIR}（待处理）、{@code IN_PROGRESS}（处理中）、
     * {@code REPAIRED}（已修复）、{@code UNREPAIRABLE}（无法修复）、
     * {@code SCRAPPED}（已报废）。
     *
     * <p>枚举名沿用 V50 之前的值（待处理原本叫「待维修」）—— 它们是已落库的字符串，
     * 只有中文标签变过。因此这里写的是**库中取值**，不是界面上看到的中文。
     */
    private String status;

    /** 设备筛选（按设备名 / 资产编号模糊匹配） */
    private String deviceKeyword;

    /**
     * 「待我处理」（V50）：只看**还没结束、且归我管**的报修单。
     *
     * <p>判据是：状态仍为「待处理 / 处理中」，且「没人接单（handled_by 为空）」或「我接的单」。
     * 于是它自然排除了两类不该出现在待办里的东西 ——
     * 已被别人接走的（在别人手上）与已结束的（已修复 / 无法修复 / 已报废）。
     *
     * <p>刻意不做成前端过滤：本页是服务端分页的，
     * 前端过滤只会把「当前这一页里符合的几条」筛出来，
     * 于是「第 1 页显示 2 条、第 2 页又是 3 条」——总数与分页全部失真。
     */
    private Boolean onlyMine;
}
