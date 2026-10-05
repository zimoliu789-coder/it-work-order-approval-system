package com.enterprise.ticket.module.order.controller;

import com.enterprise.ticket.common.api.ApiResponse;
import com.enterprise.ticket.common.log.AuditLog;
import com.enterprise.ticket.common.log.RiskLevel;
import com.enterprise.ticket.module.order.dto.OrderTransferRequest;
import com.enterprise.ticket.module.order.dto.vo.OrderTransferVO;
import com.enterprise.ticket.module.order.dto.vo.TransferCandidateVO;
import com.enterprise.ticket.module.order.service.OrderTransferService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 工单转交接口
 *
 * <p>权限：仅要求「已登录」，具体操作权在服务层判定 ——
 * 「当前实际执行人本人」或「super_admin」，且目标需满足小组 / 在职约束。
 *
 * <p>审计：转交属高风险操作，同步落审计日志（{@link RiskLevel#HIGH}）。
 */
@RestController
@RequiredArgsConstructor
public class OrderTransferController {

    private final OrderTransferService orderTransferService;

    /**
     * 转交工单
     *
     * <p>测试路径：POST http://localhost:8080/api/orders/55/transfer
     * <pre>{"newHandlerId": 4, "comment": "出差在外，交接给同事跟进"}</pre>
     */
    @PostMapping("/api/orders/{orderId}/transfer")
    @PreAuthorize("isAuthenticated()")
    @AuditLog(module = "ORDER", action = "ORDER_TRANSFER", description = "工单转交",
            risk = RiskLevel.HIGH)
    public ApiResponse<Void> transfer(@PathVariable Long orderId,
                                      @Valid @RequestBody OrderTransferRequest request) {
        orderTransferService.transfer(orderId, request);
        return ApiResponse.success("工单已转交，新的实际执行人已收到待办通知", null);
    }

    /**
     * 某工单的转交历史（工单详情时间线）
     *
     * <p>测试路径：GET http://localhost:8080/api/orders/55/transfers
     */
    @GetMapping("/api/orders/{orderId}/transfers")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<OrderTransferVO>> listByOrder(@PathVariable Long orderId) {
        return ApiResponse.success(orderTransferService.listByOrder(orderId));
    }

    /**
     * 转交候选对象（转交弹窗下拉）
     *
     * <p>普通执行人 → 同最终处理部门的在职成员；super_admin → 全部在职启用员工。
     * 均排除「申请人本人」与「当前执行人」。
     *
     * <p>测试路径：GET http://localhost:8080/api/orders/55/transfer-candidates
     */
    @GetMapping("/api/orders/{orderId}/transfer-candidates")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<TransferCandidateVO>> candidates(@PathVariable Long orderId) {
        return ApiResponse.success(orderTransferService.candidates(orderId));
    }
}
