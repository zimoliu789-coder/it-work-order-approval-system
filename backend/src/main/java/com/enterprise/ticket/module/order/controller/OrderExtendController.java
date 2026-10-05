package com.enterprise.ticket.module.order.controller;

import com.enterprise.ticket.common.api.ApiResponse;
import com.enterprise.ticket.common.api.PageResult;
import com.enterprise.ticket.common.log.AuditLog;
import com.enterprise.ticket.module.order.dto.OrderExtendApproveRequest;
import com.enterprise.ticket.module.order.dto.OrderExtendRequest;
import com.enterprise.ticket.module.order.dto.vo.OrderExtendVO;
import com.enterprise.ticket.module.order.service.OrderExtendService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 借用延期子工单接口
 *
 * <p>权限：全部端点仅要求「已登录」，数据可见性与操作权在服务层按
 * 「主单申请人 / 延期的审批人 / super_admin / admin」逐条判定。
 *
 * <p>审计：延期发起与延期审批均按 NORMAL 异步留痕。
 */
@RestController
@RequiredArgsConstructor
public class OrderExtendController {

    private final OrderExtendService orderExtendService;

    /**
     * 申请人发起延期
     *
     * <p>测试路径：POST http://localhost:8080/api/orders/55/extends
     */
    @PostMapping("/api/orders/{orderId}/extends")
    @PreAuthorize("isAuthenticated()")
    @AuditLog(module = "ORDER", action = "ORDER_EXTEND_REQUEST", description = "申请人发起借用延期")
    public ApiResponse<Long> request(@PathVariable Long orderId,
                                     @Valid @RequestBody OrderExtendRequest request) {
        return ApiResponse.success("延期申请已提交，等待审批", orderExtendService.request(orderId, request));
    }

    /**
     * 某主工单的延期记录（工单详情时间线）
     *
     * <p>测试路径：GET http://localhost:8080/api/orders/55/extends
     */
    @GetMapping("/api/orders/{orderId}/extends")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<OrderExtendVO>> listByOrder(@PathVariable Long orderId) {
        return ApiResponse.success(orderExtendService.listByOrder(orderId));
    }

    /**
     * 延期审批待办（当前轮到我审批的延期单）
     *
     * <p>测试路径：GET http://localhost:8080/api/order-extends/todo/approval?page=1&size=10
     */
    @GetMapping("/api/order-extends/todo/approval")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<PageResult<OrderExtendVO>> myApproval(@RequestParam(defaultValue = "1") long page,
                                                             @RequestParam(defaultValue = "10") long size) {
        return ApiResponse.success(orderExtendService.pageMyApproval(page, size));
    }

    /**
     * 审批延期（通过 / 驳回）
     *
     * <p>测试路径：PUT http://localhost:8080/api/order-extends/1/approve
     */
    @PutMapping("/api/order-extends/{id}/approve")
    @PreAuthorize("isAuthenticated()")
    @AuditLog(module = "ORDER", action = "ORDER_EXTEND_APPROVE", description = "审批借用延期申请")
    public ApiResponse<Void> approve(@PathVariable Long id,
                                     @Valid @RequestBody OrderExtendApproveRequest request) {
        orderExtendService.approve(id, request);
        return ApiResponse.success(request.getApproved() ? "延期申请已通过" : "延期申请已驳回", null);
    }
}
