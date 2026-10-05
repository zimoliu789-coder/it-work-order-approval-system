package com.enterprise.ticket.module.order.dto.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 工单列表视图（ / ）
 */
@Data
public class OrderVO {

    private Long id;

    private String orderNo;

    private Long deviceId;

    /** 设备名称（格式「设备名（资产编号）」） */
    private String deviceName;

    private Long applicantId;

    private String applicantName;

    /** 快照：提交时申请人所属部门名 */
    private String departmentName;

    /** SHORT_TERM / LONG_TERM */
    private String useType;

    private String useTypeLabel;

    /** 用途（原「借用原因」； 起改为选填） */
    private String reason;

    /** 期望归还日期：仅短期借用有值（yyyy-MM-dd） */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate expectedReturnDate;

    private String status;

    private String statusLabel;

    /** 快照：最终处理部门名 */
    private String handlerGroupName;

    private Long actualFinalHandlerId;

    /** 实际执行人姓名（待交付/使用中阶段展示） */
    private String actualFinalHandlerName;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime plannedEndTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime deliveredAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;

    /**
     * 当前待办步骤序号（仅「我需要处理的节点」有值）
     *
     * <p>用于审批待办列表把「轮到我审的第几步」直接展示出来，
     * 避免前端再根据节点列表自行推算。
     */
    private Integer currentStepOrder;

    /**
     * 当前步骤的审批人姓名（多审批人以「、」连接）
     *
     * <p>供「全部工单」全局视图直接展示「第几步 · 由谁审批」，无需前端再查节点列表。
     */
    private String currentApproverName;

    /** 当前步骤是否允许「我」操作（审批待办列表按此决定按钮可用性） */
    private Boolean actionable;

    // ------------------------------------------------------------------
    // ：归还与顺延（ /  /  / ）
    // ------------------------------------------------------------------

    /** 工单类型：BORROW 借用申请 / RETURN 归还单 / …（取值见 OrderType） */
    private String orderType;

    private String orderTypeLabel;

    // ------------------------------------------------------------------
    // ：自定义申请
    // ------------------------------------------------------------------

    /**
     * 自定义申请类型 id（仅 {@code orderType = CUSTOM} 有值）。
     *
     * <p>供前端在下拉筛选、跳转详情时使用；列表「类型」列展示的是
     * {@link #applyTypeName}（申请类型名称），而不是笼统的「自定义申请」。
     */
    private Long applyTypeId;

    /**
     * 申请类型名称（如「采购申请」）。
     *
     * <p>列表与详情都返回（只是两个字段的字符串，不构成数据量负担），
     * 让「类型」列能直接显示具体类型名。刻意<b>不</b>在列表返回表单数据：
     * 那才是真正的大字段，只由 {@code GET /api/orders/{id}/form-data} 按需提供。
     */
    private String applyTypeName;

    /** 归还触发来源：USER_INITIATED / DIMISSION / ADMIN_FORCE；未归还时为空 */
    private String returnTrigger;

    private String returnTriggerLabel;

    /** 申请人归还说明（ 第一步，可选） */
    private String returnNote;

    /** 归还检查结果：GOOD 完好 / DAMAGED 损坏 / MISSING_PARTS 缺配件 / LOST 丢失；未收回时为空 */
    private String returnCondition;

    private String returnConditionLabel;

    /** 收回备注（实际执行人填写） */
    private String returnRemark;

    /** 收回人 user_id */
    private Long returnedById;

    /** 收回人姓名（列表「实际收回人」列） */
    private String returnedByName;

    /** 实际归还时间（「记录 actual_end_time」） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime actualEndTime;

    /** 是否借用超时（标记位，不改变主状态；） */
    private Boolean borrowTimeout;

    /** 已自动顺延次数（，最多 2 次） */
    private Integer autoExtendCount;

    /** 最后一次超时告警时间（ 幂等控制） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastTimeoutAlertAt;

    /**
     * 当前登录者是否可以「发起归还」。
     *
     * <p>由服务端判定而非前端凭状态猜测：需要同时满足「我是申请人」+「工单使用中」。
     * 前端只负责渲染按钮，避免权限判断散落到多个页面。
     */
    private Boolean canRequestReturn;

    /**
     * 当前登录者是否可以「确认收回」。
     *
     * <p>满足其一：① 我是实际执行人且工单处于待收回；② 我是实际执行人且超时可直接收回；
     * ③ 我是 super_admin（执行人离职时的兜底收回， / ）。
     */
    private Boolean canConfirmReturn;

    // ------------------------------------------------------------------
    // ：借用延期
    // ------------------------------------------------------------------

    /** 已发起的延期次数（不含被驳回的；「延期最多 2 次可配」） */
    private Integer extendUsedCount;

    /** 延期次数上限（system_config.extend_max_count，默认 2） */
    private Integer extendMaxCount;

    /** 是否存在审批中的延期申请（列表可据此打「延期审批中」标签） */
    private Boolean extendPending;

    /**
     * 当前登录者是否可以「申请延期」。
     *
     * <p>满足全部：① 我是主单申请人；② 工单使用中；③ 短期借用（有计划结束时间）；
     * ④ 未超过延期上限；⑤ 当前没有审批中的延期。由服务端判定，前端只渲染按钮。
     */
    private Boolean canRequestExtend;

    // ------------------------------------------------------------------
    // ：工单转交与催办（ + 需求方 ）
    // ------------------------------------------------------------------

    /**
     * 是否发生过转交（列表「已转交」标记）
     *
     * <p>「全部工单」的「已转交」筛选同样基于该事实，见 {@code OrderAllQuery#transferred}。
     */
    private Boolean transferred;

    /** 累计转交次数（含离职自动转交） */
    private Integer transferCount;

    /**
     * 当前登录者是否可以「转交」本工单。
     *
     * <p>满足全部：① 工单处于「待交付 / 使用中 / 待收回」；② 我是当前实际执行人
     * 或 super_admin。与 {@code OrderTransferService#transfer} 的校验条件保持同一套判定，
     * 避免「按钮可点但接口拒绝」的错位。
     */
    private Boolean canTransfer;

    /**
     * 当前登录者是否可以「催办审批」。
     *
     * <p>满足全部：① 我是申请人；② 工单审批中；③ 存在待处理审批节点；④ 当前节点不在冷却期内。
     */
    private Boolean canUrgeApproval;

    /** 审批催办冷却剩余秒数（0＝可立即催办）；前端据此置灰并显示倒计时 */
    private Long approvalUrgeCooldownSeconds;

    /**
     * 当前登录者是否可以「催还」。
     *
     * <p>满足全部：① 我是实际执行人（或 super_admin）；② 工单使用中；③ 已到期或已超时；
     * ④ 不在冷却期内。
     */
    private Boolean canUrgeReturn;

    /** 归还催办冷却剩余秒数（0＝可立即催办） */
    private Long returnUrgeCooldownSeconds;

    // ------------------------------------------------------------------
    // ：超管强制干预（规范 V1.1 未覆盖）
    // ------------------------------------------------------------------

    /**
     * 当前登录者是否可以「强制操作」本工单。
     *
     * <p>满足全部：① 我是 super_admin；② 工单处于非终态。
     * 具体可执行哪几种强制操作由前端依据状态与 {@code ForceOperationType} 的适用范围渲染
     * （「审批中」才有驳回 / 转交审批等）。服务端在 {@code force} 内再做一次状态校验，二者同口径。
     */
    private Boolean canForceOperate;

    // ------------------------------------------------------------------
    // ：审批时限超时标记
    // ------------------------------------------------------------------

    /**
     * 当前待审批节点是否已超过约定审批时限（列表红色「已超时」标记）。
     *
     * <p>仅 FLOW 流程里配了 {@code timeLimitHours} 的节点才可能为 true；
     * 借用单 / GROUP 单无 deadline 快照，此值恒为 false（零回归）。
     * 超时<b>不改变工单状态</b>（仍「审批中」），只是一个提示标记。
     */
    private Boolean approvalOverdue;

    /** 当前节点已超时小时数（未超时为 null）；前端展示「已超时 N 小时」 */
    private Long approvalOverdueHours;
}
