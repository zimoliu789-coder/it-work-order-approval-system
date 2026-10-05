package com.enterprise.ticket.module.device.dto.vo;

import lombok.Data;

import java.util.List;

/**
 * 设备分类视图
 *
 * <p>以树形返回：一级分类对象携带 {@code children}（其二级分类）。
 */
@Data
public class DeviceCategoryVO {

    private Long id;

    private String categoryName;

    /** 父分类ID；0 表示一级分类 */
    private Long parentId;

    /** 1 一级 / 2 二级 */
    private Integer level;

    private Integer sortOrder;

    private String remark;

    /**
     * 该分类下的有效设备数（不含已软删除设备）。
     * 一级分类统计「设备一级分类 = 本分类」的数量；二级分类统计「设备二级分类 = 本分类」的数量。
     */
    private long deviceCount;

    /** 二级分类列表（仅一级分类返回，二级分类为 null） */
    private List<DeviceCategoryVO> children;
}
