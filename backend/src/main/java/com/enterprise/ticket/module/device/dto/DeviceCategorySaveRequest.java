package com.enterprise.ticket.module.device.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 设备分类新增/修改请求
 *
 * <p>不支持通过本请求调整层级（改父分类）。层级变更会让已挂接的设备归属错乱，
 * 正确做法是删除后重建，或调整设备的分类挂接。
 */
@Data
public class DeviceCategorySaveRequest {

    @NotBlank(message = "分类名称不能为空")
    @Size(max = 64, message = "分类名称长度不能超过 64 个字符")
    private String categoryName;

    /** 父分类ID；为空或 0 表示新增一级分类，否则必须是一级分类的ID（新增二级分类） */
    private Long parentId;

    @Size(max = 255, message = "备注长度不能超过 255 个字符")
    private String remark;
}
