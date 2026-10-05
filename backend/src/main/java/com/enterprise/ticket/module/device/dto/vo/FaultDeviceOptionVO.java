package com.enterprise.ticket.module.device.dto.vo;

import lombok.Data;

/**
 * 故障上报「可选设备」下拉项
 *
 * <p>用于故障报修表单的设备选择器。两种取数口径由服务层按角色区分：
 * <ul>
 *   <li><b>普通用户</b>：只返回其本人「使用中」工单对应的设备，且必带 {@code orderId}
 *       （工单内上报路径，设备保持使用中）；</li>
 *   <li><b>管理员 / 超管</b>：返回「可用」设备（{@code orderId} 为空，台账直接登记路径）
 *       <b>加上</b>全部「使用中」工单对应的设备（带 {@code orderId}，可代借用人上报）。</li>
 * </ul>
 *
 * <p>{@code orderId} 是否为空，直接决定前端提交时走哪条上报路径 —— 与
 * {@code DeviceFaultRequest.orderId} 语义一一对应，前端不需要再猜。
 */
@Data
public class FaultDeviceOptionVO {

    private Long deviceId;

    private String deviceName;

    private String assetNo;

    /** 设备当前状态编码 */
    private String deviceStatus;

    /** 设备当前状态中文名 */
    private String deviceStatusLabel;

    /** 关联工单 id；为空表示无工单（台账直接登记路径） */
    private Long orderId;

    /** 关联工单编号；为空表示无工单 */
    private String orderNo;

    /** 借用类型编码（短期借用 / 长期领用），仅工单路径有值 */
    private String useType;

    /** 借用类型中文名 */
    private String useTypeLabel;

    /** 申请人姓名，仅工单路径有值（管理员代报时用于区分是谁在用） */
    private String applicantName;

    /**
     * 展示文案：{@code 设备名（资产编号）}，工单路径追加 {@code · 使用中：申请人}，
     * 由后端统一拼装，避免前端各页各拼一遍导致口径不一致。
     */
    private String label;
}
