package com.enterprise.ticket.module.device.controller;

import com.enterprise.ticket.common.api.ApiResponse;
import com.enterprise.ticket.common.api.BatchResultVO;
import com.enterprise.ticket.common.api.PageResult;
import com.enterprise.ticket.common.log.AuditLog;
import com.enterprise.ticket.common.log.RiskLevel;
import com.enterprise.ticket.module.device.dto.DeviceBatchCategoryRequest;
import com.enterprise.ticket.module.device.dto.DeviceBatchStatusRequest;
import com.enterprise.ticket.module.device.dto.DeviceSaveRequest;
import com.enterprise.ticket.module.device.dto.DeviceStatusRequest;
import com.enterprise.ticket.module.device.dto.DeviceUnlockRequest;
import com.enterprise.ticket.module.device.dto.vo.DeviceLockVO;
import com.enterprise.ticket.module.device.dto.vo.DeviceVO;
import com.enterprise.ticket.module.device.service.DeviceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备台账管理（ /  / ）
 *
 * <p>权限（「admin：设备台账管理」）：
 * <ul>
 *   <li>台账读写（列表/详情/增改删/状态操作）—— super_admin / admin；</li>
 *   <li><b>临时锁的加锁与释放</b>是「申请人正在填表」的动作，必须对普通 user 开放，
 *       否则员工无法提交借用申请；释放时以 lockToken 比对作为授权依据；</li>
 *   <li><b>强制解除锁</b>属管理员兜底手段，仅 super_admin / admin。</li>
 * </ul>
 *
 * <p>审计：
 * <ul>
 *   <li><b>设备删除、报废、强制解除临时锁</b>属明列的高风险同步操作 —— 对应端点按 HIGH 记录；</li>
 *   <li>状态变更接口同时承载「报废」与「维修完成」，为避免漏审计，整条端点统一按 HIGH 同步落库
 *       （多记不漏记，符合 「高风险必须可靠、同步写入」的口径）；</li>
 *   <li>新增 / 修改属常规台账维护，按 NORMAL 异步落库；</li>
 *   <li>加锁 / 释放锁是高频的普通交互，不逐次留痕（失败与越权仍会被全局异常处理与安全日志捕获）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;

    /**
     * 分页查询设备台账
     *
     * <p>测试路径：GET http://localhost:8080/api/devices?page=1&size=10&status=AVAILABLE
     */
    @GetMapping
    @PreAuthorize("@perm.has('device:ledger:view')")
    public ApiResponse<PageResult<DeviceVO>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long primaryCategoryId,
            @RequestParam(required = false) Long secondaryCategoryId,
            @RequestParam(required = false) String status) {
        return ApiResponse.success(deviceService.page(page, size, keyword, primaryCategoryId, secondaryCategoryId, status));
    }

    /** 设备详情 */
    @GetMapping("/{id}")
    @PreAuthorize("@perm.has('device:ledger:view')")
    public ApiResponse<DeviceVO> detail(@PathVariable Long id) {
        return ApiResponse.success(deviceService.getDetail(id));
    }

    /** 新增设备（状态固定从「可用」开始，） */
    @PostMapping
    @PreAuthorize("@perm.has('device:ledger:manage')")
    @AuditLog(module = "DEVICE", action = "DEVICE_CREATE", description = "新增设备台账")
    public ApiResponse<Long> create(@Valid @RequestBody DeviceSaveRequest request) {
        return ApiResponse.success("设备已创建", deviceService.create(request));
    }

    /** 修改设备台账信息（不含状态） */
    @PutMapping("/{id}")
    @PreAuthorize("@perm.has('device:ledger:manage')")
    @AuditLog(module = "DEVICE", action = "DEVICE_UPDATE", description = "修改设备台账")
    public ApiResponse<Void> update(@PathVariable Long id, @Valid @RequestBody DeviceSaveRequest request) {
        deviceService.update(id, request);
        return ApiResponse.success("设备信息已更新", null);
    }

    /** 状态操作：报废 / 维修完成 */
    @PutMapping("/{id}/status")
    @PreAuthorize("@perm.has('device:ledger:manage')")
    @AuditLog(module = "DEVICE", action = "DEVICE_STATUS_CHANGE",
            description = "变更设备状态（报废 / 维修完成）", risk = RiskLevel.HIGH)
    public ApiResponse<Void> changeStatus(@PathVariable Long id, @Valid @RequestBody DeviceStatusRequest request) {
        deviceService.changeStatus(id, request);
        return ApiResponse.success("设备状态已更新", null);
    }

    /** 批量变更状态（P3）：逐条复用单条校验，返回逐条结果 */
    @PostMapping("/batch/status")
    @PreAuthorize("@perm.has('device:ledger:manage')")
    @AuditLog(module = "DEVICE", action = "DEVICE_BATCH_STATUS",
            description = "批量变更设备状态", risk = RiskLevel.HIGH)
    public ApiResponse<BatchResultVO> batchChangeStatus(
            @Valid @RequestBody DeviceBatchStatusRequest request) {
        return ApiResponse.success(deviceService.batchChangeStatus(request));
    }

    /** 批量修改分类（P3） */
    @PostMapping("/batch/category")
    @PreAuthorize("@perm.has('device:ledger:manage')")
    @AuditLog(module = "DEVICE", action = "DEVICE_BATCH_CATEGORY",
            description = "批量修改设备分类", risk = RiskLevel.NORMAL)
    public ApiResponse<BatchResultVO> batchChangeCategory(
            @Valid @RequestBody DeviceBatchCategoryRequest request) {
        return ApiResponse.success(deviceService.batchChangeCategory(request));
    }


    @DeleteMapping("/{id}")
    @PreAuthorize("@perm.has('device:ledger:manage')")
    @AuditLog(module = "DEVICE", action = "DEVICE_DELETE",
            description = "删除（软删除）设备台账", risk = RiskLevel.HIGH)
    public ApiResponse<Void> delete(@PathVariable Long id) {
        deviceService.softDelete(id);
        return ApiResponse.success("设备已删除（记录保留，资产编号不可复用）", null);
    }

    // ------------------------------------------------------------------
    // 临时锁
    // ------------------------------------------------------------------

    /**
     * 临时锁定设备（申请人在申请页选定设备时调用）
     *
     * <p>测试路径：POST http://localhost:8080/api/devices/1/lock
     */
    @PostMapping("/{id}/lock")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<DeviceLockVO> lock(@PathVariable Long id) {
        return ApiResponse.success("设备已临时锁定，请在有效期内提交申请", deviceService.lock(id));
    }

    /**
     * 释放本人持有的临时锁（切换设备 / 取消填表 / 页面卸载时调用）
     *
     * <p>测试路径：POST http://localhost:8080/api/devices/1/unlock  body: {"lockToken":"..."}
     */
    @PostMapping("/{id}/unlock")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Void> unlock(@PathVariable Long id, @Valid @RequestBody DeviceUnlockRequest request) {
        deviceService.unlock(id, request.getLockToken());
        return ApiResponse.success("临时锁已释放", null);
    }

    /**
     * 管理员强制解除临时锁（；高风险操作，同步留痕）
     *
     * <p>测试路径：POST http://localhost:8080/api/devices/1/force-unlock
     */
    @PostMapping("/{id}/force-unlock")
    @PreAuthorize("@perm.has('device:ledger:manage')")
    @AuditLog(module = "DEVICE", action = "DEVICE_FORCE_UNLOCK",
            description = "强制解除设备临时锁", risk = RiskLevel.HIGH)
    public ApiResponse<Void> forceUnlock(@PathVariable Long id) {
        deviceService.forceUnlock(id);
        return ApiResponse.success("临时锁已强制解除，设备已恢复可用", null);
    }
}
