package com.enterprise.ticket.module.device.dto.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 设备台账视图（ / ），V56/V57 追加「型号字典」与「负责人」。
 *
 * <p>V56 删除了「临时锁信息」（lockedByName / lockExpiresAt）：临时锁机制整体移除，
 * 设备状态里不会再出现新的 LOCKED。
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

    /** 型号原始文本（历史/可读原文；V56 起不参与任何比对） */
    private String model;

    /** 型号字典 id（V56 / R3）；为空表示「型号待整理」 */
    private Long modelId;

    /** 型号字典名称（modelId 有值时才下发；为空时前端回退展示 {@link #model}） */
    private String modelName;

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

    /**
     * 负责人 user_id（V57 / 第 10 项）。
     *
     * <p>资产归属责任人（长期固定，资产管理员维护），<b>不是借用人</b> ——
     * 借用人见 {@link #usage} 里的 userId / userName（来自占用中的工单）。
     */
    private Long ownerId;

    private String ownerName;

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

    private String remark;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;
}
