package com.enterprise.ticket.module.device.service;

import com.enterprise.ticket.common.api.BatchResultVO;
import com.enterprise.ticket.common.api.PageResult;
import com.enterprise.ticket.module.device.dto.DeviceBatchCategoryRequest;
import com.enterprise.ticket.module.device.dto.DeviceBatchOwnerRequest;
import com.enterprise.ticket.module.device.dto.DeviceBatchStatusRequest;
import com.enterprise.ticket.module.device.dto.DeviceSaveRequest;
import com.enterprise.ticket.module.device.dto.DeviceStatusRequest;
import com.enterprise.ticket.module.device.dto.vo.DeviceVO;

/**
 * 设备台账服务（ /  / ）
 */
public interface DeviceService {

    /**
     * 分页查询设备台账
     *
     * @param keyword            设备名称 / 资产编号 / 序列号 模糊匹配
     * @param primaryCategoryId  一级分类过滤
     * @param secondaryCategoryId 二级分类过滤
     * @param status             设备状态过滤
     */
    PageResult<DeviceVO> page(long page, long size, String keyword,
                              Long primaryCategoryId, Long secondaryCategoryId, String status);

    DeviceVO getDetail(Long id);

    /** 新增设备（状态固定从 AVAILABLE 开始） */
    Long create(DeviceSaveRequest request);

    /**
     * 校验「新增设备」的业务规则，但不落库。
     *
     * <p>抽取此方法是让设备批量导入能<b>复用单条新增的同一套规则</b>，
     * 而不是在导入侧复制一份判断逻辑 —— 一旦规则演进（例如资产编号规则收紧），
     * 单条新增与批量导入必然同时生效，不会出现「单条拦住、批量放过」的漏洞。
     *
     * <p>{@link #create(DeviceSaveRequest)} 内部同样调用本方法，两者规则严格一致。
     * 校验失败抛 {@code BusinessException}，其 message 即为可直接展示给用户的失败原因。
     */
    void validateNewDevice(DeviceSaveRequest request);

    /** 修改设备台账信息（不含状态） */
    void update(Long id, DeviceSaveRequest request);

    /** 软删除设备（：历史设备不允许物理删除） */
    void softDelete(Long id);

    /** 管理员手动状态操作：报废 / 维修完成 */
    void changeStatus(Long id, DeviceStatusRequest request);

    // ------------------------------------------------------------------
    // 批量操作（P3）
    // ------------------------------------------------------------------

    /**
     * 批量变更设备状态（P3）
     *
     * <p>逐条复用单条变更的全部校验（存在性 / 取值 / 使用中禁报废 / 手工流转白名单）；
     * 单条失败不影响其它条目，结果里逐条给出失败原因。
     */
    BatchResultVO batchChangeStatus(DeviceBatchStatusRequest request);

    /** 批量修改一级分类（P3）：只改主分类，不动其它字段 */
    BatchResultVO batchChangeCategory(DeviceBatchCategoryRequest request);

    /**
     * 批量设置负责人（V57 / 第 10 项）：只改 {@code owner_id}，不动其它字段。
     *
     * <p>负责人是报修页「我名下的设备」的唯一数据来源。存量台账全部为空，
     * 必须能批量补齐，否则那个入口对绝大多数人永远是空的。
     * {@code ownerId} 传 null 表示清空（转为公共设备，只能扫码报修）。
     */
    BatchResultVO batchAssignOwner(DeviceBatchOwnerRequest request);
}
