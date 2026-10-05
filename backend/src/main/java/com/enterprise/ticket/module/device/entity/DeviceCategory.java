package com.enterprise.ticket.module.device.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 设备分类
 *
 * <p>支持一级 + 二级两级分类，用自引用 {@code parentId} 表达层级：
 * 根分类 {@code parentId = 0}（而非 NULL —— MySQL 唯一索引不约束 NULL，
 * 用 0 才能让 {@code (parent_id, category_name)} 同级唯一约束在一级分类上同样生效）。
 * 因此 {@code parent_id} 不建外键，父分类存在性与层级由 Service 校验。
 */
@Data
@TableName("device_category")
public class DeviceCategory {

    /** 根分类的 parentId 约定值（非 NULL，见类注释） */
    public static final long ROOT_PARENT_ID = 0L;

    /** 一级分类 */
    public static final int LEVEL_PRIMARY = 1;

    /** 二级分类 */
    public static final int LEVEL_SECONDARY = 2;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 分类名称，同一父分类下不可重名 */
    private String categoryName;

    /** 父分类ID；0 表示一级分类 */
    private Long parentId;

    /** 层级：1 一级 / 2 二级 */
    private Integer level;

    /** 同级显示顺序 */
    private Integer sortOrder;

    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
