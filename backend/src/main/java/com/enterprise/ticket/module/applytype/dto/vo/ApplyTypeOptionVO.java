package com.enterprise.ticket.module.applytype.dto.vo;

import lombok.Data;

/**
 * 可提交申请类型卡片
 *
 * <p>供「提交申请」页的自定义申请区域渲染卡片。刻意保持极轻：
 * 只有卡片要展示的四个字段 + 审批方式（用于给用户一句预期提示：
 * 「提交后需审批」还是「提交即完成」）。
 *
 * <p>字段定义（schema）不在这里下发：用户点进某个类型时才需要它，
 * 提前把 5 个类型的 schema 一起拉回来，纯属浪费带宽。
 */
@Data
public class ApplyTypeOptionVO {

    private Long id;

    private String typeCode;

    private String typeName;

    private String icon;

    private String description;

    /** NONE / GROUP */
    private String approvalMode;

    private String approvalModeLabel;
}
