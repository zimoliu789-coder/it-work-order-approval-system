package com.enterprise.ticket.module.report.dto.vo;

import lombok.Data;

import java.util.List;

/**
 * 设备借用频次统计（「按设备、分类统计借用次数」）
 *
 * <p>口径：区间内 {@code order_type = 'BORROW'} 的<b>借用申请单</b>数量。
 * 归还单/维修单是同一张工单的后续动作（不新增 orders 行），因此统计借用次数时
 * 必须显式限定借用申请单，否则未来上线独立归还单会把数字算重。
 *
 * <p>{@code byDevice} 与 {@code byCategory} 是同一批数据的两种切分：
 * 前者回答「哪台设备最抢手」，后者回答「哪类设备最紧缺」。
 */
@Data
public class DeviceUsageReportVO {

    /** 区间内借用申请总数 */
    private long totalOrders;

    /** 区间内被借用过的设备数（去重） */
    private long deviceCount;

    /** 按设备统计（借用次数降序） */
    private List<DeviceItem> byDevice;

    /** 按一级分类统计（借用次数降序） */
    private List<CategoryItem> byCategory;

    /** 单台设备的借用频次 */
    @Data
    public static class DeviceItem {
        private Long deviceId;
        private String deviceName;
        private String assetNo;
        private String primaryCategoryName;
        /** 借用次数 */
        private Long borrowCount;
    }

    /** 单个分类的借用频次 */
    @Data
    public static class CategoryItem {
        /** 一级分类名；设备分类被删除或数据异常时回落「未分类」 */
        private String categoryName;
        private Long borrowCount;
        /** 该分类下被借用过的设备数（去重） */
        private Long deviceCount;
    }
}
