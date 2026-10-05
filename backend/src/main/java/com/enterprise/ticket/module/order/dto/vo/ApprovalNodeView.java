package com.enterprise.ticket.module.order.dto.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审批快照节点视图（ / 「工单详情页展示审批链路」）
 */
@Data
public class ApprovalNodeView {

    private Long id;

    private Integer stepOrder;

    /** 流程节点稳定标识（仅 FLOW 模式有值，） */
    private String nodeKey;

    /** 流程节点名（仅 FLOW 模式有值；前端据此把「第 1 步」显示为「主管审批」） */
    private String nodeName;

    /**
     * 流程节点类型（仅 FLOW 模式有值，）：APPROVAL 审批 / CC 抄送。
     *
     * <p>前端据此区分「审批节点」与「抄送节点」的展示样式（抄送节点无审批动作）。
     */
    private String nodeType;

    /** 节点类型中文名（审批 / 抄送） */
    private String nodeTypeLabel;

    /** 分支说明：为何走到 / 为何跳过本节点（仅 FLOW 模式有值） */
    private String conditionDesc;

    private Long approverId;

    private String approverName;

    /** ALL_SIGN / ANY_SIGN */
    private String signType;

    private String signTypeLabel;

    /** PENDING / APPROVED / REJECTED / SKIPPED / CANCELLED / CC_NOTIFIED / INACTIVE */
    private String status;

    private String statusLabel;

    private LocalDateTime actionTime;

    private String actionComment;

    /** 是否 super_admin 兜底审批节点 */
    private boolean superBackup;

    /** 是否因原审批人离职/禁用替换为兜底 */
    private boolean fallback;

    // ------------------------------------------------------------------
    // ：审批时限 + 待指派
    // ------------------------------------------------------------------

    /** 审批时限截止时间快照（提交时算；null = 不限时） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime deadlineAt;

    /**
     * 是否已超过审批时限（仅 PENDING 节点有意义）。
     *
     * <p>由服务端计算而非前端拿 {@code deadlineAt} 与本地时间比较：本地时钟可能不准，
     * 而「是否超时」是一个业务判定，应在服务端有唯一定论。
     */
    private Boolean overdue;

    /** 已超时小时数（未超时为 null）；供前端展示「已超时 3 小时」 */
    private Long overdueHours;

    /**
     * 该节点是否「待上一节点指定审批人」：
     * 状态为 PENDING 且 {@code approver_id} 为空（ {@code PREV_ASSIGN} 的占位节点）。
     */
    private Boolean pendingAssign;

    /** 该节点审批人由谁指定（指定者的展示名；仅 {@code pendingAssign=false} 且有待指派时上游可填） */
    private String assignedByName;

    /**
     * 「待指派」时的可选范围：{@code ALL} / {@code IT_EXECUTOR}；非待指派节点为 null。
     *
     * <p>前端据此把选择器的候选换成「只能选 IT执行人」（走 {@code assign-candidates} 接口），
     * 并在文案上说清"为什么只有这几个人" —— 服务端在提交时会再校验一次，这里不是唯一的拦截点。
     */
    private String assignScope;

    /** 可选范围的中文名（如「IT执行人（IT执行人角色 或 IT运维组成员）」）；无范围时为 null */
    private String assignScopeLabel;

    /**
     * 该节点是否为「由上一节点指定审批人」产生的节点。
     *
     * <p>为 true 时前端展示「下一节点审批人由上一节点 XXX 指定：YYY」。
     */
    private Boolean assignedByPrev;

    // ------------------------------------------------------------------
    // ：运行期激活（激活史）
    // ------------------------------------------------------------------

    /**
     * 该节点被激活（由 INACTIVE 收敛）的时刻。
     *
     * <p>注意语义边界：**提交时就已物化的节点也会写这个字段**（服务端把
     * 「提交即确定」与「运行期激活」统一走 {@code FlowNodeMaterializer}），
     * 因此它近似于 {@code created_at}，不能当作「确实经历过 INACTIVE」的判据。
     * 真正表示「曾经未激活」的是 {@code status == INACTIVE} 与 {@link #runtimeReason}。
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime activatedAt;

    /**
     * 运行期激活 / 未激活的原因说明（M2）。
     *
     * <p>典型取值：{@code 前置节点通过，按条件激活} / {@code 等待前置节点结果}。
     * 它是前端「激活史」展示的核心文案，也是排查「节点为什么没轮到我」的唯一线索。
     */
    private String runtimeReason;
}
