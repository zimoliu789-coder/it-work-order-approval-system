package com.enterprise.ticket.module.device.dto.vo;

import lombok.Data;

/**
 * 设备分类下拉选项（V59：借用单表单放开后，普通员工也要能选分类）。
 *
 * <p>刻意与 {@link DeviceCategoryVO} 分开：后者带 {@code deviceCount}（台账统计）
 * 与 {@code remark}，属于管理面信息；本 VO 只给提交页用，四项字段，
 * 不含任何「这个分类下有多少设备」这类内部统计。
 */
@Data
public class DeviceCategoryOptionVO {

    private Long id;

    /** 分类名称 */
    private String categoryName;

    /** 父分类ID；0 表示一级分类 */
    private Long parentId;

    /** 层级：1 一级 / 2 二级 */
    private Integer level;
}
