package com.enterprise.ticket.module.permission.controller;

import com.enterprise.ticket.common.api.ApiResponse;
import com.enterprise.ticket.common.log.AuditLog;
import com.enterprise.ticket.common.log.RiskLevel;
import com.enterprise.ticket.common.util.SecurityUtils;
import com.enterprise.ticket.module.permission.entity.UserPermission;
import com.enterprise.ticket.module.permission.service.PermissionApplyPolicyService;
import com.enterprise.ticket.module.permission.service.UserPermissionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 权限申请策略与「我的权限」。
 *
 * <h2>权限落点</h2>
 * <ul>
 *   <li><b>读策略 / 改策略</b>挂 {@code role:view} / {@code role:manage} ——
 *       本功能就是「角色与权限」页的一块配置区，另造一个权限码只会让
 *       「谁能配权限」这件事出现两个答案；</li>
 *   <li><b>「我的权限」</b>只要求登录 —— 它展示的是**自己**的附加权限，
 *       任何人都该能看自己有什么权限（否则申请完不知道开没开）。</li>
 * </ul>
 *
 * <h2>为什么没有「手工授予」接口</h2>
 * 用户级授权的正常来源是**审批通过自动开通**（见 {@code PermissionGrantOnApprovalService}）。
 * 另开一个「管理员直接给某人开权限」的口子会让「谁批的」这条线索断掉 ——
 * 审计上只会看到「管理员开的」，而真正该被追责的「哪张工单批的」消失了。
 * 需要人工调整时，走「改角色」这条既有路径。
 */
@RestController
@RequestMapping("/api/permission-policies")
@RequiredArgsConstructor
public class PermissionPolicyController {

    private final PermissionApplyPolicyService policyService;
    private final UserPermissionService userPermissionService;

    /**
     * 全部权限码的可申请性与风险等级（含生效值与被表覆盖的标记）。
     *
     * <p>测试路径：GET http://localhost:8080/api/permission-policies
     */
    @GetMapping
    @PreAuthorize("@perm.has('role:view')")
    public ApiResponse<List<PolicyVO>> list() {
        List<PolicyVO> result = new ArrayList<>();
        for (PermissionApplyPolicyService.PolicyView view : policyService.listAll()) {
            PolicyVO vo = new PolicyVO();
            vo.setCode(view.code());
            vo.setName(view.name());
            vo.setApplicable(view.applicable());
            vo.setRiskLevel(view.riskLevel());
            vo.setRiskLevelOverridden(view.riskLevelOverridden());
            vo.setApplicableOverridden(view.applicableOverridden());
            result.add(vo);
        }
        return ApiResponse.success(result);
    }

    /**
     * 修改一个权限码的可申请性 / 风险等级。
     *
     * <p>审计 HIGH：它决定「哪些权限能通过申请拿到」——
     * 把 `role:manage` 改成可申请，等于给所有人开了一条自我提权的路。
     *
     * <p>测试路径：PUT http://localhost:8080/api/permission-policies/device:ledger:manage
     */
    @PutMapping("/{code}")
    @PreAuthorize("@perm.has('role:manage')")
    @AuditLog(module = "ROLE", action = "PERMISSION_POLICY_UPDATE", risk = RiskLevel.HIGH,
            description = "修改权限申请策略（可申请性 / 风险等级）")
    public ApiResponse<Void> update(@PathVariable String code,
                                    @Valid @RequestBody PolicyUpdateRequest request) {
        policyService.update(code, request.getApplicable(), request.getRiskLevel());
        return ApiResponse.success("策略已保存", null);
    }

    /**
     * 可申请权限的「码 → 风险等级」映射。
     *
     * <h2>为什么单独开一个只要求登录的端点</h2>
     * 申请表单（`views/order/apply/custom.vue`）需要给高危项加提示，
     * 而**普通员工没有 `role:view`**，拿不到上面那个完整策略列表。
     * 让他们读一个「哪些码是高危」的只读映射没有信息泄露风险
     * （它本来就是申请表单里公开的选项），却能让提示出现在做选择的那一刻 ——
     * 而不是提交后才知道要多走一级。
     *
     * <p>刻意只返回**可申请**的码：不可申请的提权类连出现在这里都没必要。
     *
     * <p>测试路径：GET http://localhost:8080/api/permission-policies/applicable-risks
     */
    @GetMapping("/applicable-risks")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String, String>> applicableRisks() {
        Map<String, String> risks = new LinkedHashMap<>();
        for (PermissionApplyPolicyService.PolicyView view : policyService.listAll()) {
            if (view.applicable()) {
                risks.put(view.code(), view.riskLevel());
            }
        }
        return ApiResponse.success(risks);
    }

    /**
     * 我的附加权限（用户级授权，即「审批通过自动开通」的那些）。
     *
     * <p>测试路径：GET http://localhost:8080/api/permission-policies/mine
     */
    @GetMapping("/mine")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<MyPermissionVO>> mine() {
        Long userId = SecurityUtils.getCurrentUserId();
        List<MyPermissionVO> result = new ArrayList<>();
        for (UserPermission row : userPermissionService.listOf(userId)) {
            MyPermissionVO vo = new MyPermissionVO();
            vo.setPermCode(row.getPermCode());
            vo.setSource(row.getSource());
            vo.setOrderId(row.getOrderId());
            vo.setGrantedAt(row.getGrantedAt());
            vo.setRevokedAt(row.getRevokedAt());
            vo.setActive(row.getRevokedAt() == null);
            result.add(vo);
        }
        return ApiResponse.success(result);
    }

    /** 策略行（含「是否被表覆盖」标记，界面据此提示「已自定义」） */
    @Data
    public static class PolicyVO {
        private String code;
        private String name;
        private boolean applicable;
        /** NORMAL / HIGH */
        private String riskLevel;
        private boolean riskLevelOverridden;
        private boolean applicableOverridden;
    }

    /** 「我的权限」行（含已撤销的历史行，界面按 active 区分） */
    @Data
    public static class MyPermissionVO {
        private String permCode;
        /** APPLY 审批自动开通 / MANUAL 手工 */
        private String source;
        private Long orderId;
        private LocalDateTime grantedAt;
        private LocalDateTime revokedAt;
        /** 当前是否有效 */
        private boolean active;
    }

    /** 更新请求；两个字段都可空（只改其中一个时另一个保持原值） */
    @Data
    public static class PolicyUpdateRequest {
        private Boolean applicable;

        /**
         * 风险等级：{@code NORMAL} / {@code HIGH}。
         *
         * <p>用 {@code @NotBlank} 会挡住「只改可申请性」的调用，因此不加校验注解 ——
         * 非法值由服务层按 NORMAL 处理（保守方向由 {@code riskLevelOf} 的未知码规则兜底）。
         */
        private String riskLevel;
    }
}
