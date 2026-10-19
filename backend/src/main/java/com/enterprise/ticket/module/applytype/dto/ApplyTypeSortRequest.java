package com.enterprise.ticket.module.applytype.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 申请类型排序请求（内置类型与自建类型统一拖拽排序，不涉及表结构变更）。
 *
 * <p>与设备分类的 {@code DeviceCategorySortRequest} 不同，申请类型是**一维**的
 * （没有父子层级），因此只需整组提交 {@code orderedIds}：
 * 服务端按数组下标重写 {@code sort_order}（1、2、3……）。
 *
 * <p>为什么必须整组：只提交部分 ID 时，未提交的那些会停留在旧排序值上，
 * 与新写的 1..n 交错 —— 表现为「拖对了，页面顺序却不对」，
 * 而且这种错位不会报错，只会让人反复重拖。
 */
@Data
public class ApplyTypeSortRequest {

    /** 全部申请类型的 ID，按目标顺序排列（含内置类型，含已停用类型） */
    @NotEmpty(message = "排序ID列表不能为空")
    @Size(max = 200, message = "单次最多排序 200 个申请类型")
    private List<Long> orderedIds;
}
