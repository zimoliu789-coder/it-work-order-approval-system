package com.enterprise.ticket.module.device.dto.vo;

import lombok.Data;

/**
 * 对账异常条目（需求方三波·第二波·）
 */
@Data
public class ReconcileIssueVO {

    /** 异常类型编码 */
    private String issueType;

    /** 异常类型中文名（由服务层按类型补齐） */
    private String issueLabel;

    private Long deviceId;

    private String deviceName;

    private String assetNo;

    /** 设备当前状态编码 */
    private String deviceStatus;

    private Long orderId;

    private String orderNo;

    /** 工单当前状态编码 */
    private String orderStatus;

    /** 人类可读的差异描述 */
    private String detail;
}
