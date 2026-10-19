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

    /**
     * 分类下拉选项（扁平：一级 + 二级，仅<b>启用中</b>的分类）。
     *
     * <h2>为什么单独开一个全员可用的端点</h2>
     * 借用单放开表单自定义（V59 / D3）后，「一级分类 / 二级分类」是 schema 里的
     * <b>系统字段</b>，由普通员工在提交页选择。而 {@link #listTree} 挂在
     * {@code device:category:manage} 上（只有资产管理员有）—— 复用它会让
     * 普通员工的下拉恒为空，且前端 catch 吞掉 403 后**看不出任何异常**，
     * 表现为「借用单没法提交，但没有任何报错」。
     *
     * <p>这正是改造前就存在的缺陷（提交页一直在调 tree 接口），本端点一并修掉。
     * 只暴露 id / 名称 / 层级 / 父级四项，不含设备数与备注 —— 暴露面可控。
     */
    List<DeviceCategoryOptionVO> enabledOptions();

    /** 新增分类（parentId 为空或 0 表示一级分类） */
    Long create(DeviceCategorySaveRequest request);

    /** 修改分类名称与备注（不支持调整层级） */
    void update(Long id, DeviceCategorySaveRequest request);

    /** 删除分类（有子分类或仍被设备引用时拒绝） */
    void delete(Long id);

    /** 同级分类排序（整组提交） */
    void sort(DeviceCategorySortRequest request);
}
