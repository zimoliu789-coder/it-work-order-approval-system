package com.enterprise.ticket.module.order.controller;

import com.enterprise.ticket.common.api.ApiResponse;
import com.enterprise.ticket.common.log.AuditLog;
import com.enterprise.ticket.common.log.RiskLevel;
import com.enterprise.ticket.module.order.dto.TransferRequestCreateRequest;
import com.enterprise.ticket.module.order.dto.TransferRequestDecideRequest;
import com.enterprise.ticket.module.order.dto.vo.TransferCandidateVO;
import com.enterprise.ticket.module.order.dto.vo.TransferGroupOptionVO;
import com.enterprise.ticket.module.order.dto.vo.TransferRequestVO;
import com.enterprise.ticket.module.order.service.OrderTransferRequestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 转单申请接口（V55 / D5.7 同组 / 跨组转单）。
 *
 * <p>权限：仅要求「已登录」，具体准入在服务层判定 ——
 * <ul>
 *   <li>同组：当前执行人可发起；本组管理员审批；</li>
 *   <li>跨组：仅本组管理员可发起；目标组管理员接收并分配 / 拒绝；</li>
 *   <li>super_admin 两侧都放行（离职、组织调整等兜底场景）。</li>
 * </ul>
 * 前端隐藏按钮只是体验，直调接口同样会被拒（403）。
 *
 * <p>审计：转单意味着**责任移交**，发起与处理都按 {@link RiskLevel#HIGH} 同步留痕。
 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderTransferRequestController {

    private final OrderTransferRequestService transferRequestService;

    /**
     * 发起转单申请
     *
     * <p>测试路径：POST http://localhost:8080/api/orders/55/transfer-requests
     * <pre>同组：{"scope":"SAME_GROUP","targetHandlerId":4,"reason":"出差交接"}
     * 跨组：{"scope":"CROSS_GROUP","targetDepartmentId":12,"reason":"该组更熟悉此设备"}</pre>
     */
    @PostMapping("/{orderId}/transfer-requests")
    @PreAuthorize("isAuthenticated()")
    @AuditLog(module = "ORDER", action = "ORDER_TRANSFER_REQUEST_CREATE",
            description = "发起转单申请（同组 / 跨组）", risk = RiskLevel.HIGH)
    public ApiResponse<Long> create(@PathVariable Long orderId,
                                    @Valid @RequestBody TransferRequestCreateRequest request) {
        Long id = transferRequestService.create(orderId, request);
        return ApiResponse.success("转单申请已提交", id);
    }

    /**
     * 处理转单申请（同组审批 / 跨组接收并分配、拒绝）
     *
     * <p>测试路径：PUT http://localhost:8080/api/orders/transfer-requests/7/decide
     * <pre>同意（同组）：{"accepted":true,"comment":"同意"}
     * 接收（跨组）：{"accepted":true,"targetHandlerId":9,"comment":"已安排张三跟进"}
     * 拒绝：{"accepted":false,"comment":"该设备不在本组职责范围"}</pre>
     */
    @PutMapping("/transfer-requests/{id}/decide")
    @PreAuthorize("isAuthenticated()")
    @AuditLog(module = "ORDER", action = "ORDER_TRANSFER_REQUEST_DECIDE",
            description = "处理转单申请（审批 / 接收 / 拒绝）", risk = RiskLevel.HIGH)
    public ApiResponse<Void> decide(@PathVariable Long id,
                                    @Valid @RequestBody TransferRequestDecideRequest request) {
        transferRequestService.decide(id, request);
        return ApiResponse.success(
                Boolean.TRUE.equals(request.getAccepted()) ? "转单已生效" : "转单已拒绝，工单留在原组", null);
    }

    /**
     * 某工单的转单申请记录（工单详情时间线，含被拒绝的历史）
     *
     * <p>测试路径：GET http://localhost:8080/api/orders/55/transfer-requests
     */
    @GetMapping("/{orderId}/transfer-requests")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<TransferRequestVO>> listByOrder(@PathVariable Long orderId) {
        return ApiResponse.success(transferRequestService.listByOrder(orderId));
    }

    /**
     * 待我处理的转单申请（我是审批人 / 接收人，且仍待处理）
     *
     * <p>测试路径：GET http://localhost:8080/api/orders/transfer-requests/todo
     */
    @GetMapping("/transfer-requests/todo")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<TransferRequestVO>> myTodo() {
        return ApiResponse.success(transferRequestService.myTodo());
    }

    /**
     * 跨组转单可选的目标小组（含各自管理员，未配置管理员的小组标记为不可选）
     *
     * <p>测试路径：GET http://localhost:8080/api/orders/transfer-requests/groups
     */
    @GetMapping("/transfer-requests/groups")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<TransferGroupOptionVO>> transferGroups() {
        return ApiResponse.success(transferRequestService.transferGroups());
    }

    /**
     * 某处理小组的在职成员（跨组「接收并分配」时的执行人下拉）
     *
     * <p>测试路径：GET http://localhost:8080/api/orders/transfer-requests/groups/12/members
     */
    @GetMapping("/transfer-requests/groups/{departmentId}/members")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<TransferCandidateVO>> groupMembers(@PathVariable Long departmentId) {
        return ApiResponse.success(transferRequestService.groupMembers(departmentId));
    }
}
