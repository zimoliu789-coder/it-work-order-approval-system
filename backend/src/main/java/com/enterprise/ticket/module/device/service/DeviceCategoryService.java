package com.enterprise.ticket.module.device.service;

import com.enterprise.ticket.module.device.dto.DeviceCategorySaveRequest;
import com.enterprise.ticket.module.device.dto.DeviceCategorySortRequest;
import com.enterprise.ticket.module.device.dto.vo.DeviceCategoryVO;

import java.util.List;

/**
 * 设备分类服务
 */
public interface DeviceCategoryService {

    /** 分类树：一级分类（含 children 二级分类） */
    List<DeviceCategoryVO> listTree();

    /** 新增分类（parentId 为空或 0 表示一级分类） */
    Long create(DeviceCategorySaveRequest request);

    /** 修改分类名称与备注（不支持调整层级） */
    void update(Long id, DeviceCategorySaveRequest request);

    /** 删除分类（有子分类或仍被设备引用时拒绝） */
    void delete(Long id);

    /** 同级分类排序（整组提交） */
    void sort(DeviceCategorySortRequest request);
}
