package com.enterprise.ticket.module.inventory.dto.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 盘点任务（列表 / 详情，P2）
 *
 * <p>计数与进度由服务层算好放进来，**不在前端二次计算** ——
 * 「进度百分比」这种派生值若两边各算一次，除零兜底与取整方式的差异迟早会让
 * 列表显示 99%、详情显示 100%，而用户会认为有设备漏盘了。
 */
@Data
public class InventoryTaskVO {

    private Long id;

    private String taskNo;

    private String taskName;

    private String scopeType;

    private String scopeTypeLabel;

    private String scopeValue;

    private String scopeLabel;

    private String status;

    private String statusLabel;

    private Integer totalCount;

    private Integer checkedCount;

    private Integer inPlaceCount;

    private Integer missingCount;

    private Integer wrongLocationCount;

    /** 已核对台数 / 总台数 × 100（整数，四舍五入；总数为 0 时为 0） */
    private Integer progressPercent;

    private Long createdBy;

    private String createdByName;

    private LocalDateTime createdAt;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    private String remark;
}
