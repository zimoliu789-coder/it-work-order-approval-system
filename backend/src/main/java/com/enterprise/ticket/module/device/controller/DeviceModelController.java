package com.enterprise.ticket.module.device.controller;

import com.enterprise.ticket.common.api.ApiResponse;
import com.enterprise.ticket.common.api.PageResult;
import com.enterprise.ticket.common.log.AuditLog;
import com.enterprise.ticket.common.log.RiskLevel;
import com.enterprise.ticket.module.device.dto.DeviceModelSaveRequest;
import com.enterprise.ticket.module.device.dto.DeviceModelStatusRequest;
import com.enterprise.ticket.module.device.dto.vo.DeviceModelOptionVO;
import com.enterprise.ticket.module.device.dto.vo.DeviceModelSyncResultVO;
import com.enterprise.ticket.module.device.dto.vo.DeviceModelVO;
import com.enterprise.ticket.module.device.service.DeviceModelService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 设备型号字典管理（R3 / 批次 3）。
 *
 * <p><b>权限为什么这样分</b>：
 * <ul>
 *   <li>管理列表 / 增改 / 启停 / 从台账补齐 —— {@code device:ledger:manage}
 *       （与「只有资产管理员可修改台账字段」同一权限码，R1 裁决 6）；</li>
 *   <li><b>下拉选项</b> —— 仅要求已登录。借用申请页要选型号，而申请人通常是普通员工，
 *       用台账权限会把整个借用流程挡在门外。该端点只返回 id / 名称 / 品牌三项，
 *       不含状态、备注、设备数，暴露面可控。</li>
 * </ul>
 *
 * <p><b>没有删除端点</b>：型号被台账与历史工单引用，删除会留下悬空 {@code model_id}。
 * 要「清理」录错的型号，正确做法是停用后新建一条正确的 —— 原始文本仍保留在
 * {@code device.model} 里可回溯。
 */
@RestController
@RequestMapping("/api/device-models")
@RequiredArgsConstructor
public class DeviceModelController {

    private final DeviceModelService deviceModelService;

    /** 型号管理列表（型号名 / 品牌关键字 + 状态筛选） */
    @GetMapping
    @PreAuthorize("@perm.has('device:ledger:view')")
    public ApiResponse<PageResult<DeviceModelVO>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        return ApiResponse.success(deviceModelService.page(page, size, keyword, status));
    }

    /**
     * 型号下拉选项（仅启用中的型号）
     *
     * <p>测试路径：GET http://localhost:8080/api/device-models/options?limit=500
     */
    @GetMapping("/options")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<DeviceModelOptionVO>> options(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "500") int limit) {
        return ApiResponse.success(deviceModelService.enabledOptions(keyword, limit));
    }

    @PostMapping
    @PreAuthorize("@perm.has('device:ledger:manage')")
    @AuditLog(module = "DEVICE", action = "DEVICE_MODEL_CREATE", description = "新增设备型号")
    public ApiResponse<Long> create(@Valid @RequestBody DeviceModelSaveRequest request) {
        return ApiResponse.success("型号已新增", deviceModelService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@perm.has('device:ledger:manage')")
    @AuditLog(module = "DEVICE", action = "DEVICE_MODEL_UPDATE", description = "修改设备型号")
    public ApiResponse<Void> update(@PathVariable Long id, @Valid @RequestBody DeviceModelSaveRequest request) {
        deviceModelService.update(id, request);
        return ApiResponse.success("型号已更新", null);
    }

    /**
     * 启停型号
     *
     * <p>按 HIGH 记录：停用会立刻让该型号从所有借用申请页的下拉里消失，
     * 属于影响面较大的配置动作，必须可靠留痕。
     */
    @PutMapping("/{id}/status")
    @PreAuthorize("@perm.has('device:ledger:manage')")
    @AuditLog(module = "DEVICE", action = "DEVICE_MODEL_STATUS", description = "启停设备型号", risk = RiskLevel.HIGH)
    public ApiResponse<Void> changeStatus(@PathVariable Long id, @Valid @RequestBody DeviceModelStatusRequest request) {
        deviceModelService.changeStatus(id, request);
        return ApiResponse.success("型号状态已更新", null);
    }

    /**
     * 从台账存量文本补齐字典（把 {@code model_id IS NULL} 的行去重纳入并回填）
     *
     * <p>这是台账「型号待整理」的收敛入口，可反复执行。
     */
    @PostMapping("/sync-from-ledger")
    @PreAuthorize("@perm.has('device:ledger:manage')")
    @AuditLog(module = "DEVICE", action = "DEVICE_MODEL_SYNC", description = "从台账补齐设备型号字典")
    public ApiResponse<DeviceModelSyncResultVO> syncFromLedger() {
        return ApiResponse.success("已从台账补齐型号", deviceModelService.syncFromLedger());
    }
}
