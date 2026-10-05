package com.enterprise.ticket.module.report.dto.vo;

import lombok.Data;

import java.util.List;

/**
 * 设备故障统计（「故障数量、故障设备分布」）
 *
 * <p>区间以 {@code device_fault.occurred_at}（故障发生时间）为准，而不是创建时间：
 * 补录上周发生的故障应当计入上周，「什么时候录入的」不是分析关心的维度。
 *
 * <p>{@code byDevice} 回答「哪台设备反复出问题」（可能该报废），
 * {@code byMonth} 用来观察故障量随时间的变化趋势。
 */
@Data
public class DeviceFaultReportVO {

    /** 故障总数 */
    private long totalFaults;

    /** 待维修 */
    private long pendingRepair;

    /** 维修完成 */
    private long repaired;

    /** 已报废 */
    private long scrapped;

    /** 按设备分布（故障次数降序） */
    private List<DeviceItem> byDevice;

    /** 按月份分布（形如 {@code 2026-09}） */
    private List<MonthItem> byMonth;

    /** 单台设备的故障分布 */
    @Data
    public static class DeviceItem {
        private Long deviceId;
        private String deviceName;
        private String assetNo;
        private String primaryCategoryName;
        private Long faultCount;
        /** 其中仍待维修的数量（提示需要跟进） */
        private Long pendingCount;
    }

    /** 单月故障分布 */
    @Data
    public static class MonthItem {
        /** 形如 2026-09 */
        private String month;
        private Long faultCount;
    }
}
