package com.enterprise.ticket.module.order.service;

import com.enterprise.ticket.common.api.PageResult;
import com.enterprise.ticket.module.order.dto.CustomOrderRequest;
import com.enterprise.ticket.module.order.dto.OrderAllQuery;
import com.enterprise.ticket.module.order.dto.OrderApproveRequest;
import com.enterprise.ticket.module.order.dto.OrderConfirmReturnRequest;
import com.enterprise.ticket.module.order.dto.OrderCreateRequest;
import com.enterprise.ticket.module.order.dto.OrderReturnRequest;
import com.enterprise.ticket.module.order.dto.vo.AssignCandidateVO;
import com.enterprise.ticket.module.order.dto.vo.BorrowFlowPreviewVO;
import com.enterprise.ticket.module.order.dto.vo.DeviceOptionVO;
import com.enterprise.ticket.module.order.dto.vo.OrderDetailVO;
import com.enterprise.ticket.module.order.dto.vo.OrderFormDataVO;
import com.enterprise.ticket.module.order.dto.vo.OrderVO;
import com.enterprise.ticket.module.order.dto.vo.ScanLookupVO;

import java.util.List;

/**
 * 借用工单服务（ /  /  /  /  / ）
 *
 * <p> 交付范围：借用申请提交 + 审批快照生成 + 基础审批流转（逐级 / 会签 / 或签 / 通过 / 驳回 /
 * 自审批跳过 / super_admin 兜底）+ 交付确认。
 *  追加：两步归还（发起 / 确认收回）。
 * 审批转交、加签、催办、工单转交、主动延期、故障上报留在 –8。
 */
public interface OrderService {

    // ------------------------------------------------------------------
    // 申请页辅助
    // ------------------------------------------------------------------

    /**
     * 可申请设备选项（仅 AVAILABLE，以及锁已超时可直接接管的设备）
     *
     * <p> 最小权限：普通 user 不应访问设备台账接口，故单独提供轻量选项接口。
     */
    List<DeviceOptionVO> listSelectableDevices(String keyword, int limit);

    /**
     * 扫码查询（P1 扫码借还）。
     *
     * <p>一次请求回答「这个码是什么 + 我现在能对它做什么 + 该跳哪」，前端只按返回的
     * {@code action} 做三个分支的跳转，不再自己拼条件。
     *
     * <p>支持的扫码内容：资产编号、工单号；纯编号或带前缀（{@code ASSET:xxx} / {@code ORDER=xxx}）
     * 或 URL 形态（{@code .../scan?assetNo=xxx}）均可，解析规则见
     * {@link com.enterprise.ticket.module.order.support.ScanCodeParser}。
     *
     * <p>权限：与「可申请设备」一致，**仅要求已登录**。只读、不落库、不产生任何写操作 ——
     * 「扫工单号」只回答能不能看，绝不顺手触发归还。
     *
     * @param code 扫码原始内容（摄像头/扫码枪读到的字符串）
     */
    ScanLookupVO scanLookup(String code);

    /**
     * 借用单审批路径预览（ / M1）
     *
     * <p>借用单的审批路径取决于「申请人所属部门是否绑定了流程模板」，而申请人自己
     * 并不知道这一点 —— 因此由服务端按**当前登录用户**反查其部门后计算。
     * 若分组未绑定流程，返回 {@code bound=false}，前端回退展示固定审批人列表。
     *
     * <p>只读、不落库、不产生任何数据；仅供借用申请页在提交前展示"这笔单会经过哪些节点"。
     *
     * @param useType            借用类型（SHORT_TERM / LONG_TERM），参与条件求值
     * @param expectedReturnDate 期望归还日期，用于算出 {@code borrow.expectedDays}；可空
     * @param deviceId           设备 id，用于取设备分类参与条件求值；可空
     */
    BorrowFlowPreviewVO previewBorrowFlow(String useType, java.time.LocalDate expectedReturnDate, Long deviceId);

    /**
     * 「上一节点指定审批人」的候选名单与原因说明。
     *
     * <p>审批人在详情弹窗里点「通过」时调用：若本单当前步骤的**下一步**是
     * {@code PREV_ASSIGN} 节点，必须先点名指定执行人才能通过。本方法回答
     * 「要指定几个人、能从哪些人里指、为什么只有这些人」。
     *
     * <p>权限与 {@link #getDetail(Long)} 同口径；真正的写入校验在
     * {@code applyNextStepAssignment}（服务端权威），本方法只是把同一份规则
     * 提前告诉前端，让用户不必"选完才被拒"。
     *
     * <p>没有任何待指派节点时返回 {@code requiredCount = 0} 的空结果（而不是抛错）：
     * 那是这条路最常见的正常情形（预置三级流程里只有第 3 级用到本能力）。
     */
    AssignCandidateVO assignCandidates(Long orderId);

    // ------------------------------------------------------------------
    // 借用申请
    // ------------------------------------------------------------------

    /**
     * 提交借用申请（ /  / ）
     *
     * <p>事务内完成：部门校验 → 最终处理部门校验 → 临时锁校验 → 生成审批快照 →
     * 落库工单 → 设备 LOCKED→IN_APPROVAL 并清空临时锁字段 →（若无需审批）随机分配执行人。
     *
     * @return 工单ID
     */
    Long create(OrderCreateRequest request);

    /**
     * 提交自定义申请工单（ 自定义申请类型）
     *
     * <p>事务内完成：申请类型可提交校验（存在 / 启用 / 提交权限）→ 取该类型绑定的<b>已发布</b>表单版本
     * schema → 用户提交数据逐字段校验（{@code FormDataValidator}，含引用类查库）→
     * 生成审批快照（{@link com.enterprise.ticket.common.constant.ApprovalMode#GROUP} 走分组审批，
     * {@link com.enterprise.ticket.common.constant.ApprovalMode#NONE} 直接进入已完成）→
     * 落库工单（无设备）→ 保存表单数据（{@code order_form_data}）→ 通知当前审批人。
     *
     * @param request 申请类型 id + 表单数据（字段 key → 值）
     * @return 工单ID
     */
    Long submitCustomOrder(CustomOrderRequest request);

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /** 我的工单（我提交的申请） */
    PageResult<OrderVO> pageMyOrders(long page, long size, String status, String keyword);

    /** 审批待办（当前轮到我审批的工单，） */
    PageResult<OrderVO> pagePendingApproval(long page, long size);

    /** 我的待处理（我是实际执行人：待交付 / 使用中，） */
    PageResult<OrderVO> pageMyHandling(long page, long size);

    /**
     * 「抄送我的」工单列表。
     *
     * <p>返回「当前用户被抄送」的工单（{@code order_approval_nodes} 中存在
     * {@code node_type='CC'} 且 {@code approver_id=当前用户} 的行）。
     *
     * <p>与其它列表的差别：抄送人<b>只读</b>——能查看完整详情与附件，
     * 但不能审批 / 转交 / 催办 / 撤回（写操作按既有鉴权自然拒绝，因为抄送人不在审批人集合内）。
     */
    PageResult<OrderVO> pageCcOrders(long page, long size);

    /**
     * 全部工单（全局视图， 工单管理 /  权限）
     *
     * <p><b>仅 super_admin / admin</b>：调用方必须已通过角色校验，服务层再做一次角色判定
     * （前端菜单隐藏只是体验，后端必须强制）；
     * 支持状态 / 申请人 / 设备 / 借用类型 / 提交时间范围 / 部门 筛选，
     * 默认按提交时间倒序，排序字段与方向走白名单。
     */
    PageResult<OrderVO> pageAllOrders(OrderAllQuery query);

    /** 工单详情（含审批快照链路，） */
    OrderDetailVO getDetail(Long orderId);

    /**
     * 获取自定义工单的表单数据
     *
     * <p>复用与 {@link #getDetail(Long)} 相同的可见性判定（申请人 / 审批人 / 执行人 / super_admin / admin，
     * 越权统一 404）；非自定义工单抛 {@code ORDER_NOT_CUSTOM}。
     *
     * <p>返回「当初那版」表单 schema（工单引用的具体版本，不受模板后续改版影响）与用户提交值，
     * 供前端动态渲染详情。
     */
    OrderFormDataVO getFormData(Long orderId);

    // ------------------------------------------------------------------
    // 流转操作
    // ------------------------------------------------------------------

    /** 审批通过 / 驳回（ / ） */
    void approve(Long orderId, OrderApproveRequest request);

    /** 申请人撤回（：PENDING_APPROVAL / PENDING_DELIVERY 可撤回，释放设备） */
    void cancel(Long orderId, String reason);

    /** 实际执行人确认交付（：工单→使用中，设备→IN_USE） */
    void deliver(Long orderId);

    /**
     * 发起归还（ 第一步，需求方  ）
     *
     * <p>仅<b>申请人本人</b>可操作，且工单必须处于「使用中」：
     * 工单 {@code BORROWED → PENDING_RETURN}，<b>设备状态保持不变</b>
     * （仍为 IN_USE —— 归还途中若先把设备放回可用，会被他人重新申请，造成同一台设备两笔在办工单）。
     * 同时向实际执行人推送「请确认收回」站内消息。
     *
     * @param request 归还说明（可选）
     */
    void requestReturn(Long orderId, OrderReturnRequest request);

    /**
     * 确认收回（ 第二步，需求方  /4）
     *
     * <p>仅<b>实际执行人</b>可操作（{@code super_admin} 作为执行人离职时的兜底亦可， / ）：
     * 工单 {@code PENDING_RETURN → RETURNED} 并记录实际归还时间、收回人、设备状态登记；
     * 设备按登记结果回到 {@code AVAILABLE}（完好 / 轻微损坏）或 {@code MAINTENANCE}（故障）。
     *
     * <p>允许的入口状态有两种：
     * <ul>
     *   <li>{@code PENDING_RETURN} —— 正常两步归还；</li>
     *   <li>{@code BORROWED} 且 {@code borrow_timeout = true} —— 「最终处理人可直接收回设备
     *       （不需要申请人发起归还）」，需求方  。</li>
     * </ul>
     *
     * @param request 收回登记（设备状态必填 + 备注可选）
     */
    void confirmReturn(Long orderId, OrderConfirmReturnRequest request);
}
