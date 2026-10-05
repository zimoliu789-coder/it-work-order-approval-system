package com.enterprise.ticket.module.device.dto.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 设备台账视图（ / ， 追加「当前使用信息」与「临时锁信息」）
 *
 * <p>日期与时间统一格式化为字符串，前端无需再做格式化（ 表格/卡片直接展示）。
 */
@Data
public class DeviceVO {

    private Long id;

    private String deviceName;

    private String assetNo;

    private Long primaryCategoryId;

    private String primaryCategoryName;

    /** 二级分类可空 */
    private Long secondaryCategoryId;

    private String secondaryCategoryName;

    private String brand;

    private String model;

    private String serialNo;

    private String storageLocation;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate purchaseDate;

    /**
     * 设备金额（元），可空。
     *
     * <p>可空是语义而非缺陷：NULL = 「尚未录入」，界面据此显示占位符而不是「0.00」，
     * 借用审批条件也据此走默认分支。展示侧不要把它格式化成 0。
     */
    private BigDecimal amount;

    /** 设备状态码（；BORROWED 已按需求方约定改名 IN_USE） */
    private String status;

    /** 设备状态中文名 */
    private String statusLabel;

    /** 是否可被新建借用申请（仅 AVAILABLE 为 true） */
    private boolean applicable;

    /**
     * 当前使用信息（需求方 ）：当前使用人、使用类型、关联工单号、到期日（仅短期借用）
     *
     * <p>数据来自「占用中的工单」，非终态工单才占用设备。
     */
    private DeviceUsageVO usage;

    /** 临时锁持有人姓名（仅 LOCKED 状态有值，供管理员判断是否需要强制解锁，） */
    private String lockedByName;

    /** 临时锁到期时间（仅 LOCKED 状态有值） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lockExpiresAt;

    private String remark;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;
}
