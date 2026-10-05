package com.enterprise.ticket.module.usage.dto.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 使用记录条目（需求方三波·第一波·）
 *
 * <p>一行 = 一笔借用工单。字段刻意覆盖「：形成完整使用记录」所要求的三类信息：
 * <b>谁</b>（借用人）、<b>借什么</b>（设备 + 资产编号 + 分类）、<b>何时→何时</b>
 * （提交 / 交付 / 计划归还 / 实际归还），并附带顺延次数与超时标记，
 * 让「这台设备历史上有没有被拖到超时」在列表里一眼可见。
 */
@Data
public class UsageRecordVO {

    private Long orderId;

    private String orderNo;

    /** 工单类型（BORROW 借用） */
    private String orderType;

    private String orderTypeLabel;

    private Long deviceId;

    private String deviceName;

    private String assetNo;

    /** 一级分类名称 */
    private String primaryCategoryName;

    private String brand;

    private String model;

    private Long applicantId;

    /** 借用人（登录名 = 员工姓名） */
    private String applicantName;

    /** 借用人所属部门（快照） */
    private String departmentName;

    /** 实际执行人（交付 / 收回设备的处理人） */
    private String handlerName;

    /** 借用类型 SHORT_TERM / LONG_TERM */
    private String useType;

    private String useTypeLabel;

    /** 工单状态编码 */
    private String status;

    private String statusLabel;

    /** 提交时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    /** 交付时间（未交付为空） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime deliveredAt;

    /** 计划归还时间（长期领用为空） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime plannedEndTime;

    /** 实际归还时间（未归还为空） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime actualEndTime;

    /** 自动顺延已用次数 */
    private Integer autoExtendCount;

    /** 是否已标记超时 */
    private Boolean borrowTimeout;

    /** 归还触发来源（APPLICANT / TIMEOUT / DIMISSION） */
    private String returnTrigger;

    /** 收回时登记的设备状态 */
    private String returnCondition;
}
