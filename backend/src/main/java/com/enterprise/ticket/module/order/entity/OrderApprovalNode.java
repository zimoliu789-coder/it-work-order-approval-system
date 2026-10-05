package com.enterprise.ticket.module.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工单审批快照节点
 *
 * <p>一行 = 一个「步骤 × 审批人」。提交工单瞬间由审批规则（部门主管 / 角色 / 指定人员等，
 * 见 {@code ApproverRuleType}）解析生成，此后<b>历史工单永远读取本表</b>，
 * 不实时回查部门当前配置。
 *
 * <p>关于「一步多人」：快照按「同一步骤可多人」建模，
 * 因此会签/或签在运行期按 {@code stepOrder} 分组判定：
 * ALL_SIGN 需全组通过，ANY_SIGN 任一通过后其余节点标记 SKIPPED。
 *
 * <p>字段命名说明：数据库列 {@code is_super_backup} / {@code is_fallback} 对应的 Java 字段
 * 命名为 {@code superBackup} / {@code fallback} 并显式声明 {@code @TableField}，
 * 避免 Lombok 对 {@code isXxx} 前缀字段生成 {@code getIsXxx()} 造成属性名解析歧义
 * （与 {@code User.dimission} 的处理保持一致）。
 */
@Data
@TableName("order_approval_nodes")
public class OrderApprovalNode {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 工单ID */
    private Long orderId;

    /** 审批步骤序号（同工单内按此升序执行，） */
    private Integer stepOrder;

    /**
     * 流程节点稳定标识（，仅 FLOW 模式有值）。
     *
     * <p>与 {@code step_order} 的关系：{@code node_key} 是「流程定义里的哪个节点」，
     * {@code step_order} 是「提交时求值后落在第几步」。同一个 node_key 只会有一个 step_order，
     * 但一个节点解析出多个审批人时会展开成多行、共享同一个 step_order ——
     * 这正是既有 ANY_SIGN / ALL_SIGN 的语义，因此引擎无需任何改动。
     */
    private String nodeKey;

    /** 流程节点名（仅 FLOW 模式有值；借用单与 GROUP 模式恒为 NULL） */
    private String nodeName;

    /**
     * 流程节点类型：{@code APPROVAL} 审批 / {@code CC} 抄送。
     *
     * <p>仅 FLOW 模式有值；借用单与 GROUP 模式恒为 NULL（零回归）。
     * 抄送行的 {@code approverId} 即抄送对象、状态为 {@code CC_NOTIFIED}，
     * 因 {@code isFinished()} 为 true 而不进入「当前步骤」判定。
     */
    private String nodeType;

    /**
     * 分支说明：为何走到 / 为何跳过本节点（仅 FLOW 模式有值）。
     *
     * <p>例：「走『金额大于 5000』分支（金额 = 8000）」，或
     * 「未命中『金额大于 5000』分支（金额 = 300），本节点已跳过」。
     * 它把「条件分支」这件在界面上最难解释的事，变成一行可直接展示的事实。
     */
    private String conditionDesc;

    /**
     * 审批时限截止时间快照（；NULL = 不限时）。
     *
     * <p>提交时 = 提交时刻 + 节点 {@code timeLimitHours}，随快照冻结；
     * 超时提醒按它判定（无值则回落既有的「提交后 N 小时」全局阈值）。
     */
    private LocalDateTime deadlineAt;

    /**
     * 被激活为 PENDING 的时刻。
     *
     * <p>两种来源：① 提交即物化为 PENDING → 等于提交时刻；
     * ② 运行期由 {@code FlowActivationService#recompute} 激活 → 等于激活那一刻。
     * 状态为 INACTIVE 时恒为 NULL。
     *
     * <p>它的存在是为了让<b>动态节点的耗时也能被统计</b>：M7 流程监控按 node_key 聚合
     * 「从轮到它到处理完」的耗时，若拿 {@code created_at} 当起点，
     * 运行期才激活的节点会算出「提交到现在」这种严重偏大的时长 —— 瓶颈节点直接认错。
     */
    private LocalDateTime activatedAt;

    /**
     * 运行期决策原因（，人类可读）。
     *
     * <p>例：「上一节点审批通过、耗时 30 小时已超阈值 24 小时，自动加签」；
     * 「前序节点已驳回，本节点不会再被激活」。
     *
     * <p>与 {@link #conditionDesc} 的分工：{@code conditionDesc} 回答"为什么走这条分支"
     * （提交时确定），本字段回答"为什么现在激活 / 为什么永远不会激活"（运行期确定）。
     */
    private String runtimeReason;

    /** 审批人 user_id（快照固化） */
    private Long approverId;

    /** 会签 ALL_SIGN / 或签 ANY_SIGN（快照固化，见 {@code SignType}） */
    private String signType;

    /** 节点状态，取值见 {@link com.enterprise.ticket.common.constant.ApprovalNodeStatus} */
    private String status;

    /** 操作时间 */
    private LocalDateTime actionTime;

    /** 审批意见（驳回时必填，） */
    private String actionComment;

    /** 是否 super_admin 兜底审批节点 */
    @TableField("is_super_backup")
    private Boolean superBackup;

    /** 是否因原审批人离职/禁用自动替换为兜底 */
    @TableField("is_fallback")
    private Boolean fallback;

    /**
     * 审批超时提醒最近发送时间（需求方三波·第二波·）
     *
     * <p>定时任务每小时扫描「长时间未处理」的审批节点，若没有这个幂等位，
     * 同一位审批人每小时都会收到一条重复提醒（一天 24 条），消息中心很快被刷爆。
     * 有它之后，只处理「从未提醒过」或「距上次提醒已超过阈值」的节点。
     */
    private LocalDateTime lastRemindAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
