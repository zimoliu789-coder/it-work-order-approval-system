package com.enterprise.ticket.module.role.controller;

import com.enterprise.ticket.common.api.ApiResponse;
import com.enterprise.ticket.common.log.AuditLog;
import com.enterprise.ticket.common.log.RiskLevel;
import com.enterprise.ticket.module.role.dto.RolePermissionRequest;
import com.enterprise.ticket.module.role.dto.RoleSaveRequest;
import com.enterprise.ticket.module.role.dto.vo.RoleVO;
import com.enterprise.ticket.module.role.service.RoleService;
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
import java.util.Map;

/**
 * 角色与权限接口（需求方三波·第一波·）
 *
 * <p><b>权限</b>：查看需 {@code role:view}、写操作需 {@code role:manage}，
 * 两者默认只授予 super_admin。因此本模块自身也走同一套权限码校验 ——
 * 若将来把 {@code role:manage} 授予别的角色，无需改代码即可生效。
 *
 * <p>测试路径：
 * <pre>
 * GET    http://localhost:8080/api/roles
 * GET    http://localhost:8080/api/roles/options
 * GET    http://localhost:8080/api/roles/admin
 * POST   http://localhost:8080/api/roles
 * PUT    http://localhost:8080/api/roles/admin
 * PUT    http://localhost:8080/api/roles/admin/permissions
 * DELETE http://localhost:8080/api/roles/{code}
 * </pre>
 */
@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    /** 角色列表（含使用人数 / 权限数量） */
    @GetMapping
    @PreAuthorize("@perm.has('role:view')")
    public ApiResponse<List<RoleVO>> list() {
        return ApiResponse.success(roleService.listRoles());
    }

    /**
     * 可分配角色下拉项。
     *
     * <p>与 {@link #list()} 分开是刻意的：员工管理页的角色下拉是「把员工设为某角色」，
     * 只需要启用的角色编码与名称，不需要权限明细与人数；共用一个接口会让
     * 「员工管理」页的权限要求被迫抬到 {@code role:view}。
     */
    @GetMapping("/options")
    @PreAuthorize("@perm.has('staff:view')")
    public ApiResponse<List<RoleVO>> options() {
        return ApiResponse.success(roleService.assignableRoles());
    }

    /** 角色详情（含权限明细） */
    @GetMapping("/{code}")
    @PreAuthorize("@perm.has('role:view')")
    public ApiResponse<RoleVO> detail(@PathVariable String code) {
        return ApiResponse.success(roleService.getRole(code));
    }

    /** 新建角色 */
    @PostMapping
    @PreAuthorize("@perm.has('role:manage')")
    @AuditLog(module = "ROLE", action = "ROLE_CREATE", description = "新建角色", risk = RiskLevel.HIGH)
    public ApiResponse<RoleVO> create(@Valid @RequestBody RoleSaveRequest request) {
        return ApiResponse.success("角色已创建", roleService.createRole(request));
    }

    /** 编辑角色（内置角色不可改编码；super_admin 权限不可改） */
    @PutMapping("/{code}")
    @PreAuthorize("@perm.has('role:manage')")
    @AuditLog(module = "ROLE", action = "ROLE_UPDATE", description = "编辑角色", risk = RiskLevel.HIGH)
    public ApiResponse<RoleVO> update(@PathVariable String code, @Valid @RequestBody RoleSaveRequest request) {
        return ApiResponse.success("角色已更新", roleService.updateRole(code, request));
    }

    /** 覆盖式授予权限 */
    @PutMapping("/{code}/permissions")
    @PreAuthorize("@perm.has('role:manage')")
    @AuditLog(module = "ROLE", action = "ROLE_PERMISSION_SET", description = "调整角色权限", risk = RiskLevel.HIGH)
    public ApiResponse<Map<String, Integer>> setPermissions(@PathVariable String code,
                                                           @RequestBody RolePermissionRequest request) {
        int affected = roleService.setPermissions(code, request.getPermissions());
        return ApiResponse.success("已保存 " + affected + " 项权限", Map.of("affected", affected));
    }

    /** 删除角色（内置角色 / 仍被员工使用的角色不可删除） */
    @DeleteMapping("/{code}")
    @PreAuthorize("@perm.has('role:manage')")
    @AuditLog(module = "ROLE", action = "ROLE_DELETE", description = "删除角色", risk = RiskLevel.HIGH)
    public ApiResponse<Void> delete(@PathVariable String code) {
        roleService.deleteRole(code);
        return ApiResponse.success("角色已删除", null);
    }
}
