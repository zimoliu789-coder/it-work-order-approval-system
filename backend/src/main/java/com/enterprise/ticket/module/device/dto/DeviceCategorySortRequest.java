package com.enterprise.ticket.module.device.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 设备分类排序请求（：同级分类可调整顺序）
 *
 * <p>按父分类整组提交：{@code orderedIds} 必须是该父分类下<b>全部</b>子分类的ID，
 * 服务端按数组下标重排 {@code sort_order}。整组提交可避免「只提交部分ID」导致
 * 未提交分类的排序值出现空洞或重复。
 */
@Data
public class DeviceCategorySortRequest {

    /** 父分类ID；0 表示对一级分类排序 */
    @NotNull(message = "父分类ID不能为空")
    private Long parentId;

    @NotNull(message = "排序ID列表不能为空")
    @Size(max = 200, message = "单次最多排序 200 个分类")
    private List<Long> orderedIds;
}
