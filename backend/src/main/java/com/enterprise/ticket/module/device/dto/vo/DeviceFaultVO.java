package com.enterprise.ticket.module.device.dto.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 设备故障记录视图
 */
@Data
public class DeviceFaultVO {

    private Long id;

    private Long deviceId;

    private String deviceName;

    private String assetNo;

    /** 关联工单 id，为空表示无工单直接登记 */
    private Long orderId;

    private String orderNo;

    private Long reporterId;

    private String reporterName;

    private String faultDescription;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime occurredAt;

    /** PENDING_REPAIR / REPAIRED / SCRAPPED */
    private String status;

    private String statusLabel;

    /** 当前设备状态（便于列表直接判断设备能否再用） */
    private String deviceStatus;

    private String deviceStatusLabel;

    private Long handledBy;

    private String handledByName;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime handledAt;

    private String handleRemark;

    // ------------------------------------------------------------------
    // P2 追加：维修过程记录
    // ------------------------------------------------------------------

    private Long repairerId;

    /** 维修人展示名（批量装配时由 userNameMap 一次解析，避免 N+1） */
    private String repairerName;

    private BigDecimal repairCost;

    private String replacedParts;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
