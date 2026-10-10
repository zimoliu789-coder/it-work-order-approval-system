package com.enterprise.ticket.module.order.controller;

import com.enterprise.ticket.common.api.ApiResponse;
import com.enterprise.ticket.common.api.PageResult;
import com.enterprise.ticket.common.log.AuditLog;
import com.enterprise.ticket.common.log.RiskLevel;
import com.enterprise.ticket.module.order.dto.vo.AssignCandidateVO;
import com.enterprise.ticket.module.order.dto.vo.BorrowFlowPreviewVO;
import com.enterprise.ticket.module.order.dto.CustomOrderRequest;
import com.enterprise.ticket.module.order.dto.OrderAllQuery;
import com.enterprise.ticket.module.order.dto.OrderApproveRequest;
import com.enterprise.ticket.module.order.dto.OrderCancelRequest;
import com.enterprise.ticket.module.order.dto.OrderConfirmReturnRequest;
import com.enterprise.ticket.module.order.dto.OrderCreateRequest;
import com.enterprise.ticket.module.order.dto.OrderReturnRequest;
import com.enterprise.ticket.module.order.dto.vo.DeviceOptionVO;
import com.enterprise.ticket.module.order.dto.vo.OrderDetailVO;
import com.enterprise.ticket.module.order.dto.vo.OrderFormDataVO;
import com.enterprise.ticket.module.order.dto.vo.OrderVO;
import com.enterprise.ticket.module.order.dto.vo.ScanLookupVO;
import com.enterprise.ticket.module.order.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 借用工单接口（ 菜单结构 /  申请 /  状态机 /  审批与交付）
 *
 * <p>权限：全部端点仅要求「已登录」。数据可见性与操作权在服务层按
 * 「申请人 / 审批人 / 实际执行人 / super_admin / admin」逐条判定（：
 * 前端菜单隐藏只是体验，后端 API 必须强制校验，越权返回 403）。
 *
 * <p>审计：申请提交、审批、撤回、交付均按 NORMAL 异步留痕 ——
 * 这些是普通业务操作，且审批决定本身已作为业务事实固化在
 * {@code order_approval_node.action_comment / action_time}，不依赖日志表追溯。
 *
 * <p>本阶段不含：工单转交、审批转交/加签/催办、归还与顺延（–7）。
 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    /** 可选设备一次返回的条数上限 */
    private static final int DEFAULT_DEVICE_OPTION_LIMIT = 200;

    private final OrderService orderService;

    /**
     * 可申请设备选项（申请页「选择设备」下拉）
     *
     * <p>测试路径：GET http://localhost:8080/api/orders/selectable-devices?keyword=ThinkPad
     */
    @GetMapping("/selectable-devices")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<DeviceOptionVO>> selectableDevices(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.success(orderService.listSelectableDevices(keyword,
                limit == null ? DEFAULT_DEVICE_OPTION_LIMIT : limit));
    }

    /**
     * 扫码查询（P1 扫码借还）。
     *
     * <p>测试路径：GET http://localhost:8080/api/orders/scan-lookup?code=IT-2024-0001
     *
     * <p>仅要求已登录（与「可申请设备」同级别）：扫码借用/归还都是普通员工的日常动作，
     * 不应因为「没有设备台账权限」而被拦。返回内容也只包含该用户有权看到的工单。
     */
    @GetMapping("/scan-lookup")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<ScanLookupVO> scanLookup(@RequestParam String code) {
        return ApiResponse.success(orderService.scanLookup(code));
    }

    /**
     * 借用单审批路径预览（ / M1）
     *
     * <p>借用申请页在提交前用它展示"这笔单会经过哪些节点"。分组由服务端按**当前登录用户**
     * 反查 —— 申请人不需要（也不应该）知道自己所属分组的 id。
     *
     * <p>未绑定流程或无分组时返回 {@code bound=false}，前端回退展示固定审批人列表。
     *
     * <p>测试路径：GET http://localhost:8080/api/orders/borrow-flow-preview?useType=SHORT_TERM
     */
    @GetMapping("/borrow-flow-preview")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<BorrowFlowPreviewVO> borrowFlowPreview(
            @RequestParam(required = false) String useType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expectedReturnDate,
            @RequestParam(required = false) Long deviceId) {
        return ApiResponse.success(orderService.previewBorrowFlow(useType, expectedReturnDate, deviceId));
    }

    /**
     * 「上一次节点指定审批人」的候选名单
     *
     * <p>审批人点「通过」时，若下一步骤是「由上一节点指定」的节点，页面必须先让他点名。
     * 本接口一次返回：需要指定的人数 / 候选人员 / 可选范围的中文说明。
     *
     * <p>不同于 {@code GET /api/users/options}（挂 {@code staff:view}，普通审批人 403），
     * 本接口与工单详情同权限口径 —— 候选名单的上限本身就是流程定义限定的池子。
     *
     * <p>测试路径：GET http://localhost:8080/api/orders/1/assign-candidates
     */
    @GetMapping("/{orderId}/assign-candidates")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<AssignCandidateVO> assignCandidates(@PathVariable Long orderId) {
        return ApiResponse.success(orderService.assignCandidates(orderId));
    }

    /**
     * 提交借用申请
     *
     * <p>前置条件：设备必须已由本人临时锁定（{@code POST /api/devices/{id}/lock}）。
     *
     * <p>测试路径：POST http://localhost:8080/api/orders
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    @AuditLog(module = "ORDER", action = "ORDER_CREATE", description = "提交设备借用申请")
    public ApiResponse<Long> create(@Valid @RequestBody OrderCreateRequest request) {
        return ApiResponse.success("申请已提交，等待审批", orderService.create(request));
    }

    /**
     * 提交自定义申请工单（ 自定义申请类型）
     *
     * <p>前置条件：申请类型已启用，且当前用户具备该类型的提交权限（提交权限在服务层强制判定，
     * 越权返回 409 {@code APPLY_TYPE_SUBMIT_FORBIDDEN}）。表单数据按该类型绑定的已发布版本 schema 校验。
     *
     * <p>测试路径：POST http://localhost:8080/api/orders/custom
     * body: {"applyTypeId":1,"formData":{"title":"办公用品采购","amount":1200}}
     */
    @PostMapping("/custom")
    @PreAuthorize("isAuthenticated()")
    @AuditLog(module = "ORDER", action = "ORDER_CREATE", description = "提交自定义申请工单")
    public ApiResponse<Long> createCustom(@Valid @RequestBody CustomOrderRequest request) {
        return ApiResponse.success("申请已提交", orderService.submitCustomOrder(request));
    }

    /**
     * 我的工单（我提交的申请，）
     *
     * <p>测试路径：GET http://localhost:8080/api/orders/mine?page=1&size=10&status=PENDING_APPROVAL
     */
    @GetMapping("/mine")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<PageResult<OrderVO>> myOrders(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.success(orderService.pageMyOrders(page, size, status, keyword));
    }

    /**
     * 审批待办（当前轮到我审批的工单，）
     *
     * <p>测试路径：GET http://localhost:8080/api/orders/todo/approval?page=1&size=10
     */
    @GetMapping("/todo/approval")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<PageResult<OrderVO>> pendingApproval(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        return ApiResponse.success(orderService.pagePendingApproval(page, size));
    }

    /**
     * 我的待处理（我是实际执行人：待交付 / 使用中，）
     *
     * <p>超时置顶与转交按钮随 /7 补充。
     *
     * <p>测试路径：GET http://localhost:8080/api/orders/todo/handling?page=1&size=10
     */
    @GetMapping("/todo/handling")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<PageResult<OrderVO>> myHandling(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        return ApiResponse.success(orderService.pageMyHandling(page, size));
    }

    /**
     * 「抄送我的」工单列表
     *
     * <p>当前用户被抄送的工单。抄送人可打开只读详情（含表单与附件），但不能执行任何写操作。
     * 归在 {@code /todo} 家族下（与 {@code /todo/approval}、{@code /todo/handling} 一致），
     * 避免与 {@code /{id}} 通配路径产生歧义。
     *
     * <p>测试路径：GET http://localhost:8080/api/orders/todo/cc?page=1&size=10
     */
    @GetMapping("/todo/cc")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<PageResult<OrderVO>> ccOrders(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        return ApiResponse.success(orderService.pageCcOrders(page, size));
    }

    /**
     * 全部工单（全局视图，仅 super_admin / admin， 工单管理）
     *
     * <p>数据可见性在服务层再判一次角色（前端菜单隐藏只是体验，直调接口必须 403）。
     * 默认按提交时间倒序；排序字段与服务层白名单校验，禁止前端值直接进 SQL。
     *
     * <p>测试路径：GET http://localhost:8080/api/orders/all?page=1&size=10&status=BORROWED
     */
    @GetMapping("/all")
    @PreAuthorize("@perm.has('order:all:view')")
    public ApiResponse<PageResult<OrderVO>> allOrders(OrderAllQuery query) {
        return ApiResponse.success(orderService.pageAllOrders(query));
    }

    /** 工单详情（含审批快照链路，） */
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<OrderDetailVO> detail(@PathVariable Long id) {
        return ApiResponse.success(orderService.getDetail(id));
    }

    /**
     * 获取自定义工单的表单数据
     *
     * <p>可见性判定与工单详情一致（申请人 / 审批人 / 执行人 / super_admin / admin），越权统一 404；
     * 非自定义工单返回 409 {@code ORDER_NOT_CUSTOM}。
     *
     * <p>测试路径：GET http://localhost:8080/api/orders/1/form-data
     */
    @GetMapping("/{id}/form-data")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<OrderFormDataVO> formData(@PathVariable Long id) {
        return ApiResponse.success(orderService.getFormData(id));
    }

    /**
     * 审批通过 / 驳回（ / ）
     *
     * <p>测试路径：PUT http://localhost:8080/api/orders/1/approve
     * body: {"approved": true} 或 {"approved": false, "comment": "设备型号不符"}
     */
    @PutMapping("/{id}/approve")
    @PreAuthorize("isAuthenticated()")
    @AuditLog(module = "ORDER", action = "ORDER_APPROVE", description = "审批借用工单")
    public ApiResponse<Void> approve(@PathVariable Long id, @Valid @RequestBody OrderApproveRequest request) {
        orderService.approve(id, request);
        return ApiResponse.success(request.getApproved() ? "已通过审批" : "已驳回该工单", null);
    }

    /**
     * 申请人撤回
     *
     * <p>测试路径：PUT http://localhost:8080/api/orders/1/cancel  body: {"reason":"不需要了"}
     */
    @PutMapping("/{id}/cancel")
    @PreAuthorize("isAuthenticated()")
    @AuditLog(module = "ORDER", action = "ORDER_CANCEL", description = "申请人撤回工单")
    public ApiResponse<Void> cancel(@PathVariable Long id,
                                    @RequestBody(required = false) OrderCancelRequest request) {
        orderService.cancel(id, request == null ? null : request.getReason());
        return ApiResponse.success("工单已撤回，设备已释放", null);
    }

    /**
     * 实际执行人确认交付（：工单→使用中，设备→使用中）
     *
     * <p>测试路径：PUT http://localhost:8080/api/orders/1/deliver
     */
    @PutMapping("/{id}/deliver")
    @PreAuthorize("isAuthenticated()")
    @AuditLog(module = "ORDER", action = "ORDER_DELIVER", description = "确认交付设备")
    public ApiResponse<Void> deliver(@PathVariable Long id) {
        orderService.deliver(id);
        return ApiResponse.success("已确认交付，设备进入使用中", null);
    }

    /**
     * 申请人发起归还（ 第一步，需求方  ）
     *
     * <p>仅申请人本人可操作；工单 → 待收回，<b>设备状态保持不变</b>（仍使用中，防止归还途中被他人申请）。
     *
     * <p>测试路径：PUT http://localhost:8080/api/orders/1/return
     * body: {"returnNote":"设备外观完好，电源线已一并归还"}
     */
    @PutMapping("/{id}/return")
    @PreAuthorize("isAuthenticated()")
    @AuditLog(module = "ORDER", action = "ORDER_RETURN_REQUEST", description = "申请人发起归还设备")
    public ApiResponse<Void> requestReturn(@PathVariable Long id,
                                           @RequestBody(required = false) OrderReturnRequest request) {
        orderService.requestReturn(id, request == null ? new OrderReturnRequest() : request);
        return ApiResponse.success("已发起归还，等待实际执行人确认收回", null);
    }

    /**
     * 实际执行人确认收回（ 第二步，需求方  /4）
     *
     * <p>仅实际执行人可操作（super_admin 可代为收回，用于执行人已离职的兜底）。
     * 收回时登记设备状态：完好 / 轻微损坏 → 设备回到可用；故障 → 设备进入维修中。
     *
     * <p>风险级别 HIGH（ 关键修订第 33 条：归还属于高风险操作，需同步落审计日志）。
     *
     * <p>测试路径：PUT http://localhost:8080/api/orders/1/confirm-return
     * body: {"condition":"GOOD","remark":"外观无损"}
     */
    @PutMapping("/{id}/confirm-return")
    @PreAuthorize("isAuthenticated()")
    @AuditLog(module = "ORDER", action = "ORDER_RETURN_CONFIRM", risk = RiskLevel.HIGH,
            description = "实际执行人确认收回设备")
    public ApiResponse<Void> confirmReturn(@PathVariable Long id,
                                           @Valid @RequestBody OrderConfirmReturnRequest request) {
        orderService.confirmReturn(id, request);
        return ApiResponse.success("已确认收回，设备已归还", null);
    }
}
