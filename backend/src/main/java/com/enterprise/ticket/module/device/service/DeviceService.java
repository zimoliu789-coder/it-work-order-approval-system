package com.enterprise.ticket.module.device.service;

import com.enterprise.ticket.common.api.BatchResultVO;
import com.enterprise.ticket.common.api.PageResult;
import com.enterprise.ticket.module.device.dto.DeviceBatchCategoryRequest;
import com.enterprise.ticket.module.device.dto.DeviceBatchStatusRequest;
import com.enterprise.ticket.module.device.dto.DeviceSaveRequest;
import com.enterprise.ticket.module.device.dto.DeviceStatusRequest;
import com.enterprise.ticket.module.device.dto.vo.DeviceLockVO;
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
    // 临时锁（， 交付）
    // ------------------------------------------------------------------

    /**
     * 申请人选定设备后加临时锁：AVAILABLE → LOCKED，写入 locked_by / locked_at / lock_token。
     *
     * <p>幂等：本人重复锁定同一设备返回原令牌并顺延计时，不报错。
     * 他人持有效锁时返回 {@code DEVICE_UNAVAILABLE}；他人锁已超时则直接接管（ 超时释放）。
     */
    DeviceLockVO lock(Long deviceId);

    /** 释放本人持有的临时锁（令牌必须匹配，） */
    void unlock(Long deviceId, String lockToken);

    /** 管理员强制解除临时锁（；高风险操作，由控制层按 HIGH 同步留痕） */
    void forceUnlock(Long deviceId);

    /**
     * 释放全部超时的临时锁（ / 「临时锁超时释放，每 5 分钟」）
     *
     * @return 实际释放的设备数量
     */
    int releaseExpiredLocks();

    /**
     * 批量变更设备状态（P3）
     *
     * <p>逐条复用单条变更的全部校验（存在性 / 取值 / 使用中禁报废 / 手工流转白名单）；
     * 单条失败不影响其它条目，结果里逐条给出失败原因。
     */
    BatchResultVO batchChangeStatus(DeviceBatchStatusRequest request);

    /** 批量修改一级分类（P3）：只改主分类，不动其它字段 */
    BatchResultVO batchChangeCategory(DeviceBatchCategoryRequest request);
}
