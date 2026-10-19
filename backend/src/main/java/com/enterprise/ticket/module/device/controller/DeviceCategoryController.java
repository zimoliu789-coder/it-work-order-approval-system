package com.enterprise.ticket.module.device.controller;

import com.enterprise.ticket.common.api.ApiResponse;
import com.enterprise.ticket.common.log.AuditLog;
import com.enterprise.ticket.common.log.RiskLevel;
import com.enterprise.ticket.module.device.dto.DeviceCategorySaveRequest;
import com.enterprise.ticket.module.device.dto.DeviceCategorySortRequest;
import com.enterprise.ticket.module.device.dto.vo.DeviceCategoryOptionVO;
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

    /**
     * 分类下拉选项（一级 + 二级扁平列表，仅启用中的分类）。
     *
     * <h2>为什么这个端点只要求登录</h2>
     * 借用单放开表单自定义（V59 / D3）后，「设备分类 / 二级分类」是 schema 里的
     * <b>系统字段</b>，由<b>普通员工</b>在提交页选择。而上面的分类树挂在
     * {@code device:category:manage} 上（只有资产管理员有）—— 复用树接口会让
     * 普通员工的下拉恒为空。
     *
     * <p>这不是新问题：改造前的提交页就在调树接口，前端 catch 吞掉 403 之后
     * 下拉是空的、且没有任何提示 —— 「借用单没法提交但看不到原因」。
     * 本端点一并修掉它。
     *
     * <p>只返回 id / 名称 / 层级 / 父级四项，不含设备数与备注（管理面信息）。
     *
     * <p>路径段 {@code /options} 是字面量，与 {@code /{id}} 不冲突。
     */
    @GetMapping("/options")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<DeviceCategoryOptionVO>> options() {
        return ApiResponse.success(deviceCategoryService.enabledOptions());
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
