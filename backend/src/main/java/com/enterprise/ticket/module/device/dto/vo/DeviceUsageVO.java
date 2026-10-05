package com.enterprise.ticket.module.device.dto.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDate;

/**
 * 设备当前使用信息（需求方 ）
 *
 * <p>设备详情/台账展示「当前使用人、使用类型、关联工单号、到期日（仅短期借用）」。
 * 数据来源是<b>占用中的工单</b>（{@code orders} 中状态处于审批中/待交付/使用中/待收回），
 * 而不是在设备表上冗余一份使用人信息 —— 单一事实来源，避免工单与设备两侧数据漂移。
 */
@Data
public class DeviceUsageVO {

    private Long orderId;

    private String orderNo;

    /** 当前使用人（= 工单申请人，交付后即为使用人） */
    private Long userId;

    private String userName;

    /** SHORT_TERM / LONG_TERM */
    private String useType;

    private String useTypeLabel;

    /** 期望归还日期：仅短期借用有值，长期领用不展示 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate expectedReturnDate;

    /** 关联工单状态（审批中/待交付/使用中/待收回） */
    private String orderStatus;

    private String orderStatusLabel;
}
