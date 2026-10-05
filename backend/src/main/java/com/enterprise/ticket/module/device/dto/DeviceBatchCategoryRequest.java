package com.enterprise.ticket.module.device.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 批量修改设备一级分类（P3 批量操作）
 *
 * <p>只改 {@code primary_category_id}，**不动**二级分类与其它字段 ——
 * 单条的 {@code update} 是「整体替换」语义（未传字段会被清空），批量场景下那等于
 * 把几十台设备的品牌/型号/存放位置一并抹掉。批量只做一件事：把主分类换成目标分类。
 */
@Data
public class DeviceBatchCategoryRequest {

    @NotEmpty(message = "请选择要修改的设备")
    private List<Long> ids;

    @NotNull(message = "请选择目标分类")
    private Long categoryId;
}
