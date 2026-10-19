package com.enterprise.ticket.module.device.dto.vo;

import lombok.Data;

/**
 * 报修时「这台设备能不能报、绑定到哪张单」的统一答案（V57 / 第 10 项）。
 *
 * <h2>为什么一个 VO 同时服务「下拉」与「扫码查询」两个入口</h2>
 * <p>第 10 项的两个填报模式看着不同（一个选、一个查），但要回答的问题完全一样：
 * 这台设备叫什么、属于什么分类、**当前能不能报修、报修时绑定哪张工单**。
 * 若给两个模式各写一份 VO，两边的「可报修」判定迟早会分叉 ——
 * 表现为「下拉里能选、提交却被拒」这种最难解释的错。
 *
 * <h2>三个字段决定前端怎么走</h2>
 * <ul>
 *   <li>{@link #reportable} —— 为 false 时前端直接禁用提交并展示 {@link #reason}；</li>
 *   <li>{@link #orderId} —— 非空表示「本人使用中」，提交时带上它走工单路径
 *       （设备保持使用中，去向由归还登记决定）；为空走台账登记路径（设备 可用 → 维修中）；</li>
 *   <li>{@link #found} —— 仅扫码查询会为 false（查不到该资产编号）。</li>
 * </ul>
 */
@Data
public class FaultDeviceLookupVO {

    /** 是否查到设备（仅按资产编号查询时可能为 false） */
    private boolean found;

    private Long deviceId;

    private String deviceName;

    private String assetNo;

    /** 一级分类名（确认「扫到的是不是那一类东西」的最短依据） */
    private String primaryCategoryName;

    private String secondaryCategoryName;

    /** 设备当前状态编码 */
    private String deviceStatus;

    /** 设备当前状态中文名 */
    private String deviceStatusLabel;

    /**
     * 可关联的工单 id。
     *
     * <p><b>只在「该设备正被我本人使用中」时有值</b> —— 报修带 orderId 会走工单路径，
     * 而服务端只允许工单借用人（或管理员）关联，因此不是「有工单就填」。
     */
    private Long orderId;

    private String orderNo;

    /** 当前登录用户能否就此设备提交报修 */
    private boolean reportable;

    /** 不可报修的原因（可直接展示；可报修时为空） */
    private String reason;
}
