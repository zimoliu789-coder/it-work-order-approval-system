package com.enterprise.ticket.module.order.controller;

import com.enterprise.ticket.common.api.ApiResponse;
import com.enterprise.ticket.common.log.AuditLog;
import com.enterprise.ticket.common.log.RiskLevel;
import com.enterprise.ticket.module.order.dto.OrderForceRequest;
import com.enterprise.ticket.module.order.dto.vo.OrderForceOperationVO;
import com.enterprise.ticket.module.order.service.OrderForceOperationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 超管强制干预接口（；规范 V1.1 未覆盖）
 *
 * <p><b>权限</b>：写操作仅 {@code super_admin}（{@code hasRole('SUPER_ADMIN')}）；
 * 服务层再判一次角色（，前端隐藏按钮只是体验）。
 * 时间线查询与「工单详情」同口径：申请人 / 执行人 / 审批人 / admin 以上可见。
 *
 * <p><b>审计</b>：强制操作属高风险，{@link RiskLevel#HIGH} → 同步 {@code REQUIRES_NEW} 留痕，
 * 保证「即使业务事务回滚，也留下『谁在什么时候尝试过什么』」。
 *
 * <p>测试路径：
 * <pre>
 * POST http://localhost:8080/api/orders/1/force
 *   body: {"operationType":"FORCE_TERMINATE","reason":"员工已离职，设备无法找回"}
 *   body: {"operationType":"FORCE_REJECT","reason":"材料弄虚作假"}
 *   body: {"operationType":"FORCE_TRANSFER_APPROVAL","reason":"原审批人长期休假","targetApproverId":3}
 *   body: {"operationType":"FORCE_TRANSFER_HANDLER","reason":"原执行人休假","targetHandlerId":5}
 * GET  http://localhost:8080/api/orders/1/force
 * </pre>
 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderForceOperationController {

    private final OrderForceOperationService orderForceOperationService;

    /**
     * 执行强制干预（仅 super_admin）
     */
    @PostMapping("/{id}/force")
    @PreAuthorize("@perm.has('order:force:manage')")
    @AuditLog(module = "ORDER", action = "ORDER_FORCE_OPERATION", risk = RiskLevel.HIGH,
            description = "超级管理员强制干预工单（驳回 / 终止 / 转交审批 / 转交执行人）")
    public ApiResponse<Void> force(@PathVariable Long id, @RequestBody OrderForceRequest request) {
        orderForceOperationService.force(id, request);
        return ApiResponse.success("强制操作已执行，相关方已收到通知", null);
    }

    /**
     * 某工单的强制干预时间线
     */
    @GetMapping("/{id}/force")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<OrderForceOperationVO>> list(@PathVariable Long id) {
        return ApiResponse.success(orderForceOperationService.listByOrder(id));
    }
}
