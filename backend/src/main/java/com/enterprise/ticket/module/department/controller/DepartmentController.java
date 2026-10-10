package com.enterprise.ticket.module.department.controller;

import com.enterprise.ticket.common.api.ApiResponse;
import com.enterprise.ticket.common.log.AuditLog;
import com.enterprise.ticket.common.log.RiskLevel;
import com.enterprise.ticket.module.department.dto.DepartmentManagerRequest;
import com.enterprise.ticket.module.department.dto.DepartmentMoveRequest;
import com.enterprise.ticket.module.department.dto.DepartmentSaveRequest;
import com.enterprise.ticket.module.department.dto.vo.DepartmentDeleteResultVO;
import com.enterprise.ticket.module.department.dto.vo.DepartmentNodeVO;
import com.enterprise.ticket.module.department.dto.vo.DepartmentOptionVO;
import com.enterprise.ticket.module.department.service.DepartmentService;
import com.enterprise.ticket.module.user.dto.UserOptionVO;
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
 * 组织与人员 —— 部门接口（，需求文档 二）。
 *
 * <h2>读与写的权限分界</h2>
 * <p>读（树 / 选项 / 成员）挂 {@code staff:view}，写（增删改 / 移动 / 设主管）挂
 * {@code department:manage}。刻意<b>不</b>复用 {@code staff:manage}：部门的组织调整
 * 与「改某个员工的资料」是两件事，把它们绑在一个码上，日后想「让部门助理能建部门但不能改员工资料」
 * 就得动权限模型。
 *
 * <h2>成员列表为什么单独一个端点</h2>
 * <p>{@code GET /api/users/page?departmentId=} 只按 {@code employee.department_id}（主部门）过滤，
 * 而本页要显示的是**主部门 ∪ 兼职部门**的并集（否则「IT运维组」永远是空列表）。
 * 两者口径不同就不该硬塞进同一个端点 —— 那样会让「员工管理页的部门筛选」跟着变形。
 *
 * <p>测试路径：
 * <pre>
 * GET    http://localhost:8080/api/departments/tree
 * GET    http://localhost:8080/api/departments/options
 * POST   http://localhost:8080/api/departments            （deptName/parentId/sortOrder/remark）
 * PUT    http://localhost:8080/api/departments/10         （改名 / 排序 / 绑流程 / 备注）
 * PUT    http://localhost:8080/api/departments/10/parent  （移动：parentId）
 * PUT    http://localhost:8080/api/departments/10/managers（设主管：userIds）
 * GET    http://localhost:8080/api/departments/10/members
 * DELETE http://localhost:8080/api/departments/10
 * </pre>
 */
@RestController
@RequestMapping("/api/departments")
@RequiredArgsConstructor
public class DepartmentController {

    private final DepartmentService departmentService;

    /** 完整部门树（含每部门人数与主管），供左侧部门树一次渲染 */
    @GetMapping("/tree")
    @PreAuthorize("@perm.has('staff:view')")
    public ApiResponse<List<DepartmentNodeVO>> tree() {
        return ApiResponse.success(departmentService.tree());
    }

    /** 部门扁平选项（带层级缩进展示名），供各处下拉使用 */
    @GetMapping("/options")
    @PreAuthorize("@perm.has('staff:view')")
    public ApiResponse<List<DepartmentOptionVO>> options() {
        return ApiResponse.success(departmentService.options());
    }

    /**
     * 某部门的成员（主部门 ∪ 兼职部门并集）。
     *
     * <p>返回 {@link UserOptionVO} 而不是 {@code UserAccountVO}：这一列是「选中哪个部门看谁」，
     * 不是账号管理页，不需要「名下设备数 / 在途审批数」这类重字段。
     */
    @GetMapping("/{id}/members")
    @PreAuthorize("@perm.has('staff:view')")
    public ApiResponse<List<UserOptionVO>> members(@PathVariable Long id) {
        return ApiResponse.success(departmentService.membersOf(id));
    }

    @PostMapping
    @PreAuthorize("@perm.has('department:manage')")
    @AuditLog(module = "ORG", action = "DEPT_CREATE", risk = RiskLevel.HIGH,
            description = "新增部门")
    public ApiResponse<Long> create(@Valid @RequestBody DepartmentSaveRequest request) {
        return ApiResponse.success("部门已创建", departmentService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@perm.has('department:manage')")
    @AuditLog(module = "ORG", action = "DEPT_UPDATE", risk = RiskLevel.HIGH,
            description = "编辑部门")
    public ApiResponse<Void> update(@PathVariable Long id,
                                    @Valid @RequestBody DepartmentSaveRequest request) {
        departmentService.update(id, request);
        return ApiResponse.success("部门已保存", null);
    }

    /** 移动部门到新的上级（会整体重写子树的 path / depth） */
    @PutMapping("/{id}/parent")
    @PreAuthorize("@perm.has('department:manage')")
    @AuditLog(module = "ORG", action = "DEPT_MOVE", risk = RiskLevel.HIGH,
            description = "移动部门（调整组织层级）")
    public ApiResponse<Void> move(@PathVariable Long id,
                                  @RequestBody DepartmentMoveRequest request) {
        departmentService.move(id, request == null ? null : request.getParentId());
        return ApiResponse.success("部门已移动", null);
    }

    /** 整体替换部门主管（未手工覆盖的成员直属主管会同步跟随） */
    @PutMapping("/{id}/managers")
    @PreAuthorize("@perm.has('department:manage')")
    @AuditLog(module = "ORG", action = "DEPT_MANAGER_SET", risk = RiskLevel.HIGH,
            description = "设置部门主管（决定审批上级）")
    public ApiResponse<Void> setManagers(@PathVariable Long id,
                                         @Valid @RequestBody DepartmentManagerRequest request) {
        departmentService.setManagers(id, request.getUserIds());
        return ApiResponse.success("部门主管已更新", null);
    }

    /** 删除部门：成员上移到父部门、主管关系清除；有子部门时拒绝 */
    @DeleteMapping("/{id}")
    @PreAuthorize("@perm.has('department:manage')")
    @AuditLog(module = "ORG", action = "DEPT_DELETE", risk = RiskLevel.HIGH,
            description = "删除部门（成员上移至上级）")
    public ApiResponse<DepartmentDeleteResultVO> delete(@PathVariable Long id) {
        DepartmentDeleteResultVO result = departmentService.delete(id);
        return ApiResponse.success(
                "部门已删除，" + result.getMovedMemberCount() + " 名成员已移至「"
                        + result.getMovedToDepartmentName() + "」", result);
    }
}
