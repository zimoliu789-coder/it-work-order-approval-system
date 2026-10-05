package com.enterprise.ticket.module.order.dto.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工单摘要（扫码借还场景，P1）
 *
 * <h2>为什么不复用 {@link OrderVO}</h2>
 * <p>{@code OrderVO} 由 {@code OrderViewAssembler} 装配，涉及审批节点、转交计数、催办冷却、
 * 延期统计等十余次查询；扫码只需要「跳哪、叫什么、能不能还」几个事实。
 * 为单条记录去构造一个分页对象既别扭又白跑一堆查询，故单独定义一个最小摘要。
 *
 * <p>⚠️ 本 VO 刻意**只服务于扫码场景**。若要扩到列表/详情，请改用 {@code OrderVO} ——
 * 两套口径并存会让「同一个字段在两个接口不一致」这类问题变得难查。
 */
@Data
public class ScanOrderBriefVO {

    private Long id;

    private String orderNo;

    /** 工单状态码，见 {@code OrderStatus} */
    private String status;

    private String statusLabel;

    private Long deviceId;

    private String deviceName;

    /** 资产编号（OrderVO 里没有这个字段，扫码场景必须要，用于与手里的设备核对） */
    private String assetNo;

    private String applicantName;

    /** 计划归还时间（扫码归还时展示「本应于 X 归还」） */
    private LocalDateTime plannedEndTime;

    /** 是否可发起归还 —— 与列表页同一判定口径（本人申请 且 状态=使用中） */
    private Boolean canRequestReturn;
}
