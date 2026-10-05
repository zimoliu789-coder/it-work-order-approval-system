package com.enterprise.ticket.module.user.controller;

import com.enterprise.ticket.common.api.ApiResponse;
import com.enterprise.ticket.common.api.BatchResultVO;
import com.enterprise.ticket.common.api.PageResult;
import com.enterprise.ticket.common.log.AuditLog;
import com.enterprise.ticket.common.log.RiskLevel;
import com.enterprise.ticket.module.user.dto.UserBatchDepartmentRequest;
import com.enterprise.ticket.module.user.dto.UserCreateRequest;
import com.enterprise.ticket.module.user.dto.UserOptionVO;
import com.enterprise.ticket.module.user.dto.UserPageQuery;
import com.enterprise.ticket.module.user.dto.UserUpdateRequest;
import com.enterprise.ticket.module.user.dto.vo.DimissionResultVO;
import com.enterprise.ticket.module.user.dto.vo.UserAccountVO;
import com.enterprise.ticket.module.user.dto.vo.UserPasswordResetVO;
import com.enterprise.ticket.module.user.service.UserService;
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
 * 员工接口（ 用户与角色 /  选择员工 /  离职员工数据管控）
 *
 * <p><b>权限矩阵</b>（「仅 super_admin 能写操作，admin 只查看」）：
 * <table border="1">
 *   <caption>员工接口权限</caption>
 *   <tr><th>端点</th><th>super_admin</th><th>admin</th><th>user</th></tr>
 *   <tr><td>GET /options（选择员工）</td><td>✓</td><td>✓</td><td>✗ 403</td></tr>
 *   <tr><td>GET /page（员工列表）</td><td>✓</td><td>✓ 只读</td><td>✗ 403</td></tr>
 *   <tr><td>POST / 新增、PUT /{id} 编辑、/{id}/password 重置密码、/{id}/enable|disable</td>
 *       <td>✓</td><td>✗ 403</td><td>✗ 403</td></tr>
 *   <tr><td>POST /{id}/dimission|reinstate（离职 / 恢复在职）</td><td>✓</td><td>✗ 403</td><td>✗ 403</td></tr>
 * </table>
 * 前端菜单隐藏只是 UI 体验，越权直调接口一律 403。
 *
 * <p>所有返回值都不含 {@code password_hash}、{@code ldap_dn} 等敏感列（ / ）；
 * 「批量导入」的预览响应会带回用户填写的初始密码（否则确认导入拿不到），
 * 但仅上传人本人可见，且绝不进入审计日志。
 *
 * <p>测试路径：
 * <pre>
 * GET  http://localhost:8080/api/users/page?page=1&amp;size=10&amp;dimission=false
 * POST http://localhost:8080/api/users            （新增：realName/username/password/departmentId/role/displayName）
 * PUT  http://localhost:8080/api/users/4          （编辑：realName/departmentId/role/displayName）
 * POST http://localhost:8080/api/users/4/password （重置密码：password）
 * POST http://localhost:8080/api/users/4/disable  （禁用）
 * POST http://localhost:8080/api/users/4/enable   （启用）
 * POST http://localhost:8080/api/users/4/dimission
 * POST http://localhost:8080/api/users/4/reinstate
 * </pre>
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * 员工下拉选项
     *
     * @param keyword    姓名或登录名模糊匹配，可为空
     * @param departmentId 仅返回该分组的员工，可为空
     */
    @GetMapping("/options")
    @PreAuthorize("@perm.has('staff:view')")
    public ApiResponse<List<UserOptionVO>> options(@RequestParam(required = false) String keyword,
                                                   @RequestParam(required = false) Long departmentId) {
        return ApiResponse.success(userService.listOptions(keyword, departmentId));
    }

    /**
     * 员工账号分页（员工管理列表）
     *
     * <p>b 起 admin 也可查看（「admin 只查看」）：员工列表是管理端的只读视图，
     * 写操作全部由下方端点单独管控。列表同时返回「名下使用中设备数」与「在途审批数」，
     * 供「标记离职」二次确认弹窗直接引用。
     */
    @GetMapping("/page")
    @PreAuthorize("@perm.has('staff:view')")
    public ApiResponse<PageResult<UserAccountVO>> page(UserPageQuery query) {
        return ApiResponse.success(userService.pageAccounts(query));
    }

    /**
     * 查询单个员工账号（编辑弹窗回填用）
     *
     * <p>测试路径：GET http://localhost:8080/api/users/4
     */
    @GetMapping("/{id}")
    @PreAuthorize("@perm.has('staff:view')")
    public ApiResponse<UserAccountVO> detail(@PathVariable Long id) {
        return ApiResponse.success(userService.getAccount(id));
    }

    /**
     * 新增员工（）
     *
     * <p>姓名唯一 + 登录名唯一（双重校验）；初始密码落库后即置「首登强制改密」。
     */
    @PostMapping
    @PreAuthorize("@perm.has('staff:manage')")
    @AuditLog(module = "USER", action = "USER_CREATE", risk = RiskLevel.HIGH,
            description = "新增员工（含角色分配）")
    public ApiResponse<UserAccountVO> create(@Valid @RequestBody UserCreateRequest request) {
        return ApiResponse.success("员工已创建", userService.createUser(request));
    }

    /**
     * 编辑员工（）
     *
     * <p>可改姓名、角色、部门、显示名称；<b>登录名不可改</b> ——
     * {@link UserUpdateRequest} 不含该字段，属于结构性限制。
     * 姓名修改同样过唯一性校验（排除自己）。
     */
    @PutMapping("/{id}")
    @PreAuthorize("@perm.has('staff:manage')")
    @AuditLog(module = "USER", action = "USER_UPDATE", risk = RiskLevel.HIGH,
            description = "编辑员工（含角色 / 分组变更）")
    public ApiResponse<UserAccountVO> update(@PathVariable Long id,
                                             @Valid @RequestBody UserUpdateRequest request) {
        return ApiResponse.success("员工信息已更新", userService.updateUser(id, request));
    }

    /**
     * 重置员工密码（；2026-09-20  改造）
     *
     * <p><b>不再接收请求体</b>：临时口令改由服务端生成 —— 改造前由前端传一个写死的
     * 写死的默认口令，管理员点完按钮后根本不知道新口令是什么，属典型的「静默失效」体验。
     * 现在改为生成并把口令回传，由页面弹出展示、供管理员复制后转交。
     *
     * <p>副作用：该员工下次登录必须改密，且其<b>全部既有会话立即失效</b>。
     * 超管口令不可被重置；AD 域账号的密码只能在域控修改（{@code AD_PASSWORD_MANAGED_BY_AD}）。
     *
     * <p><b>审计安全</b>：{@code @AuditLog} 只序列化<b>入参</b>（ 的既定约定），
     * 而本端点的入参只有路径变量 {@code id}，临时口令位于返回值 —— 因此口令不会落进审计日志。
     */
    @PostMapping("/{id}/password")
    @PreAuthorize("@perm.has('staff:manage')")
    @AuditLog(module = "USER", action = "USER_RESET_PASSWORD", risk = RiskLevel.HIGH,
            description = "重置员工密码（ 高风险操作）")
    public ApiResponse<UserPasswordResetVO> resetPassword(@PathVariable Long id) {
        String temporaryPassword = userService.resetPassword(id);
        return ApiResponse.success("密码已重置，请将临时密码转交该员工（他下次登录需修改）",
                new UserPasswordResetVO(temporaryPassword));
    }

    /**
     * 启用账号（）
     *
     * <p>与「恢复在职」是两件事：本端点只开账号开关，不改人事状态。
     * 离职账号请走 {@code /{id}/reinstate}（否则会出现「已离职却能登录」的越权口子）。
     */
    @PostMapping("/{id}/enable")
    @PreAuthorize("@perm.has('staff:manage')")
    @AuditLog(module = "USER", action = "USER_ENABLE", risk = RiskLevel.HIGH,
            description = "启用员工账号")
    public ApiResponse<UserAccountVO> enable(@PathVariable Long id) {
        return ApiResponse.success("账号已启用，该员工现在可以登录", userService.setEnabled(id, true));
    }

    /**
     * 禁用账号（）
     *
     * <p>禁用后不能登录，但<b>不等于离职</b>：不触发工单回收、不自动转交执行人、
     * 也不改 {@code is_dimission}。需要人事口径的离职请用 {@code /{id}/dimission}。
     */
    @PostMapping("/{id}/disable")
    @PreAuthorize("@perm.has('staff:manage')")
    @AuditLog(module = "USER", action = "USER_DISABLE", risk = RiskLevel.HIGH,
            description = "禁用员工账号")
    public ApiResponse<UserAccountVO> disable(@PathVariable Long id) {
        return ApiResponse.success("账号已禁用，该员工将无法登录", userService.setEnabled(id, false));
    }

    /** 批量调整员工部门（P3） */
    @PostMapping("/batch/department")
    @PreAuthorize("@perm.has('staff:manage')")
    @AuditLog(module = "USER", action = "USER_BATCH_DEPARTMENT",
            description = "批量调整员工部门", risk = RiskLevel.NORMAL)
    public ApiResponse<BatchResultVO> batchDepartment(
            @Valid @RequestBody UserBatchDepartmentRequest request) {
        return ApiResponse.success(userService.batchChangeDepartment(request));
    }

    /**
     * 标记员工离职（；需求方  ）
     *
     * <p>副作用：账号禁用 + 名下「使用中」工单自动转入「待收回」并通知实际执行人回收
     * + 名下在办工单自动转交同组在职成员。
     * 风险级别 HIGH（账号停用 + 批量推进工单，属于需要可靠同步留痕的管理操作）。
     */
    @PostMapping("/{id}/dimission")
    @PreAuthorize("@perm.has('staff:manage')")
    @AuditLog(module = "USER", action = "USER_DIMISSION", risk = RiskLevel.HIGH,
            description = "标记员工离职并联动回收其名下设备")
    public ApiResponse<DimissionResultVO> markDimission(@PathVariable Long id) {
        DimissionResultVO result = userService.markDimission(id);
        return ApiResponse.success(result.getMessage(), result);
    }

    /**
     * 恢复在职（需求方  轻量版员工管理）
     *
     * <p>仅恢复账号状态，不自动恢复设备（设备需重新走借用流程）。
     */
    @PostMapping("/{id}/reinstate")
    @PreAuthorize("@perm.has('staff:manage')")
    @AuditLog(module = "USER", action = "USER_REINSTATE", risk = RiskLevel.HIGH,
            description = "恢复员工在职状态")
    public ApiResponse<DimissionResultVO> reinstate(@PathVariable Long id) {
        DimissionResultVO result = userService.reinstate(id);
        return ApiResponse.success(result.getMessage(), result);
    }
}
