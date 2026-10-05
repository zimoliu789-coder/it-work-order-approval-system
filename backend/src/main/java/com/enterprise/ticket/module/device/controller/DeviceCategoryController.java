package com.enterprise.ticket.module.device.controller;

import com.enterprise.ticket.common.api.ApiResponse;
import com.enterprise.ticket.common.log.AuditLog;
import com.enterprise.ticket.common.log.RiskLevel;
import com.enterprise.ticket.module.device.dto.DeviceCategorySaveRequest;
import com.enterprise.ticket.module.device.dto.DeviceCategorySortRequest;
import com.enterprise.ticket.module.device.dto.vo.DeviceCategoryVO;
import com.enterprise.ticket.module.device.service.DeviceCategoryService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 设备分类管理
 *
 * <p>权限：admin 具备「设备台账管理」权限，因此设备分类的读写均对
 * super_admin / admin 开放（与  审批配置「写仅 super_admin」不同 —— 那是配置类变更）。
 *
 * <p>审计：「设备删除」属明列的高风险同步操作，因此分类<b>删除</b>按 HIGH 记录；
 * 新增 / 修改 / 排序属常规维护，按 NORMAL 异步落库。
 */
@RestController
@RequestMapping("/api/device-categories")
@RequiredArgsConstructor
public class DeviceCategoryController {

    private final DeviceCategoryService deviceCategoryService;

    /** 分类树（一级分类含 children 二级分类） */
    @GetMapping
    @PreAuthorize("@perm.has('device:category:manage')")
    public ApiResponse<List<DeviceCategoryVO>> list() {
        return ApiResponse.success(deviceCategoryService.listTree());
    }

    /** 新增分类：parentId 为空或 0 表示一级分类 */
    @PostMapping
    @PreAuthorize("@perm.has('device:category:manage')")
    @AuditLog(module = "DEVICE", action = "DEVICE_CATEGORY_CREATE", description = "新增设备分类")
    public ApiResponse<Long> create(@Valid @RequestBody DeviceCategorySaveRequest request) {
        return ApiResponse.success("分类已创建", deviceCategoryService.create(request));
    }

    /** 修改分类名称与备注（不支持调整层级） */
    @PutMapping("/{id}")
    @PreAuthorize("@perm.has('device:category:manage')")
    @AuditLog(module = "DEVICE", action = "DEVICE_CATEGORY_UPDATE", description = "修改设备分类")
    public ApiResponse<Void> update(@PathVariable Long id, @Valid @RequestBody DeviceCategorySaveRequest request) {
        deviceCategoryService.update(id, request);
        return ApiResponse.success("分类已更新", null);
    }

    /** 同级分类排序（整组提交，） */
    @PutMapping("/sort")
    @PreAuthorize("@perm.has('device:category:manage')")
    @AuditLog(module = "DEVICE", action = "DEVICE_CATEGORY_SORT", description = "调整设备分类显示顺序")
    public ApiResponse<Void> sort(@Valid @RequestBody DeviceCategorySortRequest request) {
        deviceCategoryService.sort(request);
        return ApiResponse.success("分类顺序已更新", null);
    }

    /** 删除分类（存在子分类或仍被设备引用时拒绝） */
    @DeleteMapping("/{id}")
    @PreAuthorize("@perm.has('device:category:manage')")
    @AuditLog(module = "DEVICE", action = "DEVICE_CATEGORY_DELETE",
            description = "删除设备分类", risk = RiskLevel.HIGH)
    public ApiResponse<Void> delete(@PathVariable Long id) {
        deviceCategoryService.delete(id);
        return ApiResponse.success("分类已删除", null);
    }
}
