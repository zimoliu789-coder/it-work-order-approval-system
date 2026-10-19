package com.enterprise.ticket.module.device.service;

import com.enterprise.ticket.common.api.PageResult;
import com.enterprise.ticket.module.device.dto.DeviceModelSaveRequest;
import com.enterprise.ticket.module.device.dto.DeviceModelStatusRequest;
import com.enterprise.ticket.module.device.dto.vo.DeviceModelOptionVO;
import com.enterprise.ticket.module.device.dto.vo.DeviceModelSyncResultVO;
import com.enterprise.ticket.module.device.dto.vo.DeviceModelVO;

import java.util.List;

/**
 * 设备型号字典服务（R3 / 批次 3）。
 *
 * <p>字典的作用是让「型号」从自由文本变成可比对的 id ——
 * 借用单存 {@code model_id}、IT 交付扫码时按 {@code model_id} 校验，
 * 大小写与写法差异不再影响判定。
 */
public interface DeviceModelService {

    /** 管理列表：支持名称/品牌关键字与状态筛选，按 id 倒序（新建的在最上面） */
    PageResult<DeviceModelVO> page(long page, long size, String keyword, String status);

    /**
     * 型号下拉选项（仅启用中的型号）。
     *
     * <p>供借用申请页与设备台账新增/编辑共用。员工也需要调用它 ——
     * 借用申请页要选型号，因此端点权限只能是「已登录」，
     * 由「只返回启用型号 + 不含敏感字段」本身来控制暴露面。
     */
    List<DeviceModelOptionVO> enabledOptions(String keyword, int limit);

    Long create(DeviceModelSaveRequest request);

    void update(Long id, DeviceModelSaveRequest request);

    /** 启停型号；停用后新增下拉不再出现，既有设备与历史工单关联保持不变 */
    void changeStatus(Long id, DeviceModelStatusRequest request);

    /**
     * 从台账存量文本补齐字典：把 {@code device.model} 有值但 {@code model_id} 为空的
     * 行去重纳入字典并回填。
     *
     * <p>幂等：重复执行时第二次的 {@code createdCount} 为 0、{@code boundCount} 只统计
     * 新增的待整理行。
     */
    DeviceModelSyncResultVO syncFromLedger();
}
