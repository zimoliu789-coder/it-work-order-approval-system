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
 *   <li>待办与处理（GET 列表 / 接单 / 维修完成 / 无法修复）—— **仅要求已登录**，
 *       准入与数据隔离全部在服务层（V50）：管理员看全量台账，
 *       非管理员只拿得到「未完结 且（没人接单 或 由他接单）」的那些；</li>
 *   <li>报废（SCRAP）—— 仍需 {@code device:fault:manage}：那是**资产决策**，
 *       不该由一线维修人替资产管理员做。</li>
 * </ul>
 * 全部故障操作记入审计日志（ / ）。
 *
 * <h2>V50：为什么把「列表 / 维修完成」从权限码改成「已登录」</h2>
 * 这两件事的**执行人**是「处理流程」里配的维修人，而 {@code device:fault:manage}
 * 按现有角色配置只发给管理员 —— 结果是「能接单（接单接口本就是已登录即可）却交不了差」，
 * 维修人接了单看不到「维修完成」按钮，而且一点报错都没有。
 * 统一口径：**接口只要登录，授权留给服务层的 {@code requireAdminOrRepairer}** ——
 * 与接单接口已有的做法完全一致，不再出现「同一件事、三个接口两种口径」。
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
     * 故障记录分页（管理员看全量；处理人只看自己的待办，服务端强制隔离）
     *
     * <p>测试路径：GET http://localhost:8080/api/device-faults?page=1&size=10&status=PENDING_REPAIR
     *
     * <p>{@code onlyMine=true} 是「待我处理」；但对**非管理员**它恒为真 ——
     * 传不传都一样，服务端不会因为一个查询参数就交出别人的数据。
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
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
     *
     * <p>V50：准入口径与接单 /「无法修复」统一为「已登录」，授权在服务层
     * （管理员，或该报修的维修人 / 处理范围内的成员）。
     */
    @PutMapping("/{id}/repair")
    @PreAuthorize("isAuthenticated()")
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
    /**
     * 接单（V50）。
     *
     * <p>只要求登录，不挂 {@code device:fault:manage} —— 那个权限码按现有角色配置
     * 只发给管理员，而接单的是一线维修人员。真正的授权在服务层：
     * 「管理员，或落在『处理流程』通知范围内的人」，而那套范围本来就是管理员自己配的。
     */
    @PutMapping("/{id}/accept")
    @PreAuthorize("isAuthenticated()")
    @AuditLog(module = "DEVICE", action = "FAULT_ACCEPT",
            description = "接单设备故障报修", risk = RiskLevel.NORMAL)
    public ApiResponse<Void> accept(@PathVariable Long id) {
        deviceFaultService.accept(id);
        return ApiResponse.success();
    }

    /** 判定无法修复（V50）：维修结论，设备保持维修中，待资产决策 */
    @PutMapping("/{id}/unrepairable")
    @PreAuthorize("isAuthenticated()")
    @AuditLog(module = "DEVICE", action = "FAULT_UNREPAIRABLE",
            description = "判定设备故障无法修复", risk = RiskLevel.NORMAL)
    public ApiResponse<Void> markUnrepairable(@PathVariable Long id,
                                              @RequestBody(required = false) DeviceFaultHandleRequest request) {
        deviceFaultService.markUnrepairable(id, request);
        return ApiResponse.success();
    }

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
