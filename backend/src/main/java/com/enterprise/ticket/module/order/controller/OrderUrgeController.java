package com.enterprise.ticket.module.order.controller;

import com.enterprise.ticket.common.api.ApiResponse;
import com.enterprise.ticket.common.log.AuditLog;
import com.enterprise.ticket.module.order.dto.vo.OrderUrgeVO;
import com.enterprise.ticket.module.order.service.OrderUrgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 工单催办接口（；规范 V1.1 未覆盖）
 *
 * <p>审批催办与归还催办拆成两个端点而不是一个带 {@code type} 参数的端点：
 * 两者的发起人身份、适用状态、目标人都不同，拆开后每个端点的校验是一条直线，
 * 也不存在「传错 type 走到另一条分支」的可能。
 *
 * <p>催办<b>不改变工单状态</b>，因此这里没有 PATCH/PUT —— 只有 POST（留痕 + 发消息）。
 * 两类催办均按 NORMAL 异步留痕（业务动作，非高风险）。
 */
@RestController
@RequiredArgsConstructor
public class OrderUrgeController {

    private final OrderUrgeService orderUrgeService;

    /**
     * 审批催办（申请人 → 当前审批节点审批人）
     *
     * <p>测试路径：POST http://localhost:8080/api/orders/55/urge-approval
     */
    @PostMapping("/api/orders/{orderId}/urge-approval")
    @PreAuthorize("isAuthenticated()")
    @AuditLog(module = "ORDER", action = "ORDER_URGE_APPROVAL", description = "催办审批")
    public ApiResponse<Void> urgeApproval(@PathVariable Long orderId) {
        orderUrgeService.urgeApproval(orderId);
        return ApiResponse.success("已提醒当前审批人尽快处理", null);
    }

    /**
     * 归还催办（实际执行人 → 借用人）
     *
     * <p>测试路径：POST http://localhost:8080/api/orders/55/urge-return
     */
    @PostMapping("/api/orders/{orderId}/urge-return")
    @PreAuthorize("isAuthenticated()")
    @AuditLog(module = "ORDER", action = "ORDER_URGE_RETURN", description = "催办归还")
    public ApiResponse<Void> urgeReturn(@PathVariable Long orderId) {
        orderUrgeService.urgeReturn(orderId);
        return ApiResponse.success("已提醒借用人尽快归还", null);
    }

    /**
     * 某工单的催办记录（工单详情时间线）
     *
     * <p>测试路径：GET http://localhost:8080/api/orders/55/urges
     */
    @GetMapping("/api/orders/{orderId}/urges")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<OrderUrgeVO>> listByOrder(@PathVariable Long orderId) {
        return ApiResponse.success(orderUrgeService.listByOrder(orderId));
    }
}
