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

    // ------------------------------------------------------------------
    // V59 / 批次 3.5：报修表单放开后的自定义字段
    // ------------------------------------------------------------------

    /** 填写时使用的表单模板版本 id（快照指针；为空表示这条记录没有自定义字段） */
    private Long formTemplateVersionId;

    /** 自定义字段值：{字段key: 值}。系统字段（设备 / 时间 / 描述 / 分部）在专用字段上 */
    private java.util.Map<String, Object> formData;

    /**
     * 当初那一版表单的 schema（用于把 key 还原成显示名）。
     *
     * <p>**按版本快照下发**，不读申请类型当前绑定的版本 —— 否则管理员换绑表单后，
     * 历史记录的回显会跟着变（与工单详情同一套快照语义）。
     */
    private com.enterprise.ticket.common.form.FormSchema formSchema;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
