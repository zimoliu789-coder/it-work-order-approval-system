package com.enterprise.ticket.module.device.controller;

import com.enterprise.ticket.common.api.ApiResponse;
import com.enterprise.ticket.common.api.PageResult;
import com.enterprise.ticket.common.log.AuditLog;
import com.enterprise.ticket.common.log.RiskLevel;
import com.enterprise.ticket.module.device.dto.DeviceFaultHandleRequest;
import com.enterprise.ticket.module.device.dto.DeviceFaultQuery;
import com.enterprise.ticket.module.device.dto.DeviceFaultRequest;
import com.enterprise.ticket.module.device.dto.vo.DeviceFaultVO;
import com.enterprise.ticket.module.device.dto.vo.FaultDeviceOptionVO;
import com.enterprise.ticket.module.device.service.DeviceFaultService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 设备故障上报接口
 *
 * <p>权限模型：
 * <ul>
 *   <li>上报（POST）—— 仅要求已登录：借用人只能报自己使用中的设备（服务层校验），
 *       管理员 / 最终处理人可做台账直接登记（服务层校验）；</li>
 *   <li>查询与处理（GET / 维修完成 / 报废）—— 仅 super_admin / admin。</li>
 * </ul>
 * 全部故障操作记入审计日志（ / ）。
 */
@RestController
@RequestMapping("/api/device-faults")
@RequiredArgsConstructor
public class DeviceFaultController {

    private final DeviceFaultService deviceFaultService;

    /**
     * 上报设备故障
     *
     * <p>测试路径：POST http://localhost:8080/api/device-faults
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    @AuditLog(module = "DEVICE", action = "DEVICE_FAULT_REPORT", description = "上报设备故障")
    public ApiResponse<Long> report(@Valid @RequestBody DeviceFaultRequest request) {
        return ApiResponse.success("故障已登记", deviceFaultService.report(request));
    }

    /**
     * 故障上报「可选设备」列表（按角色取数）
     *
     * <p>普通用户返回本人使用中工单的设备；管理员 / 超管额外返回全部可用设备。
     * 每项的 {@code orderId} 是否为空，决定提交时走工单上报还是台账直接登记。
     *
     * <p>测试路径：GET http://localhost:8080/api/device-faults/selectable-devices
     */
    @GetMapping("/selectable-devices")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<FaultDeviceOptionVO>> selectableDevices() {
        return ApiResponse.success(deviceFaultService.selectableDevices());
    }

    /**
     * 故障记录分页（仅 super_admin / admin）
     *
     * <p>测试路径：GET http://localhost:8080/api/device-faults?page=1&size=10&status=PENDING_REPAIR
     */
    @GetMapping
    @PreAuthorize("@perm.has('device:fault:view')")
    public ApiResponse<PageResult<DeviceFaultVO>> page(DeviceFaultQuery query) {
        return ApiResponse.success(deviceFaultService.page(query));
    }

    /**
     * 某设备的故障历史（设备台账展开查看）
     *
     * <p>测试路径：GET http://localhost:8080/api/device-faults/device/11
     */
    @GetMapping("/device/{deviceId}")
    @PreAuthorize("@perm.has('device:fault:view')")
    public ApiResponse<List<DeviceFaultVO>> listByDevice(@PathVariable Long deviceId) {
        return ApiResponse.success(deviceFaultService.listByDevice(deviceId));
    }

    /**
     * 维修完成（设备 维修中 → 可用）
     *
     * <p>测试路径：PUT http://localhost:8080/api/device-faults/1/repair
     */
    @PutMapping("/{id}/repair")
    @PreAuthorize("@perm.has('device:fault:manage')")
    @AuditLog(module = "DEVICE", action = "DEVICE_FAULT_REPAIR", description = "登记设备维修完成")
    public ApiResponse<Void> repair(@PathVariable Long id,
                                    @RequestBody(required = false) DeviceFaultHandleRequest request) {
        deviceFaultService.markRepaired(id, request);
        return ApiResponse.success("已登记维修完成，设备回到可用", null);
    }

    /**
     * 故障标记报废（设备 → 已报废；使用中禁止）
     *
     * <p>测试路径：PUT http://localhost:8080/api/device-faults/1/scrap
     */
    @PutMapping("/{id}/scrap")
    @PreAuthorize("@perm.has('device:fault:manage')")
    @AuditLog(module = "DEVICE", action = "DEVICE_FAULT_SCRAP", risk = RiskLevel.HIGH,
            description = "故障设备标记报废")
    public ApiResponse<Void> scrap(@PathVariable Long id,
                                   @RequestBody(required = false) DeviceFaultHandleRequest request) {
        deviceFaultService.scrap(id, request);
        return ApiResponse.success("设备已标记报废", null);
    }
}
