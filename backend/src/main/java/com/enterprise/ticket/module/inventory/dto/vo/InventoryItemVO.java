package com.enterprise.ticket.module.inventory.dto.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 盘点明细项（P2）
 *
 * <p>`expectedStatus` 与 `checkResult` **同时给出**：前者是台账快照，
 * 后者是现场结果。合并展示成一句「状态」会丢掉「一台使用中的设备被盘到在库是正常结果」
 * 这一层判断依据。
 */
@Data
public class InventoryItemVO {

    private Long id;

    private Long deviceId;

    private String assetNo;

    private String deviceName;

    private String storageLocation;

    /** 创建任务时的设备状态（快照） */
    private String expectedStatus;

    private String expectedStatusLabel;

    /** 核对结果；<b>null = 尚未核对</b>（不返回 PENDING 之类的占位值） */
    private String checkResult;

    private String checkResultLabel;

    private Long checkedBy;

    private String checkedByName;

    private LocalDateTime checkedAt;

    private String remark;
}
