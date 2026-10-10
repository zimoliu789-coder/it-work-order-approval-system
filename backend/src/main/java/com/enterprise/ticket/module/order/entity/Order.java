package com.enterprise.ticket.module.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 借用工单主表（ /  / ， 交付「借用申请 + 基础审批 + 交付」，
 *  追加「两步归还 + 自动顺延 + 超时标记」）
 *
 * <p><b>快照语义</b>（ 的核心精神：配置变更不影响历史工单）：
 * <ul>
 *   <li>{@code departmentId} —— 提交时申请人所属部门，固化后不随调岗变化；</li>
 *   <li>{@code handlerDepartmentId} —— 提交时分组绑定的最终处理部门；</li>
 *   <li>审批人快照见 {@link OrderApprovalNode}。</li>
 * </ul>
 *
 * <p><b>两种借用类型</b>（需求方 ）：
 * {@code SHORT_TERM} 短期借用（{@code expectedReturnDate} 必填，到期参与预警/顺延）与
 * {@code LONG_TERM} 长期领用（无固定归还日期，不催还）。两者交付后设备状态一律为「使用中」。
 *
 * <p><b> 归还与顺延</b>（ /  / ）：
 * 状态主链为 {@code BORROWED → PENDING_RETURN → RETURNED}；
 * {@code borrowTimeout} 是<b>标记位而非状态</b>，不改变主状态（「仅 BORROWED/PENDING_RETURN
 * 状态下有效」）。三个时间戳 {@code remindBeforeSentAt} / {@code dueRemindedAt} /
 * {@code lastTimeoutAlertAt} 是定时任务的<b>幂等位</b>，保证每天运行的任务不重复通知
 * （「所有定时任务必须幂等」）。
 */
@Data
@TableName("borrow_order")
public class Order {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 工单编号（业务可读，全局唯一） */
    private String orderNo;

    /**
     * 工单类型，取值见 {@link com.enterprise.ticket.common.constant.OrderType}
     *
     * <p> 新增，数据库默认 {@code BORROW}； 存量行由 V6 兜底回填。
     */
    private String orderType;

    /**
     * 自定义申请类型 apply_type.id
     *
     * <p>仅当 {@code orderType = CUSTOM} 时有值；现有三种类型（借用 / 归还 / 维修 / 换货）
     * 恒为 {@code null} —— 用 null 表达「非自定义申请」，让「按申请类型筛选」天然只命中
     * 自定义工单，不必再加一个「是否为自定义」的布尔列。
     *
     * <p>本列<b>不做外键</b>：申请类型允许被删除（未被工单使用时），而删除后历史工单
     * 仍需可读。真要做外键就得把「删除」改成「禁止删除」，而需求明确要求
     * 「未被使用时可以删除」。
     */
    private Long applyTypeId;

    /**
     * 提交时冻结的审批流程定义 JSON（，仅 {@code approvalMode = FLOW} 有值）。
     *
     * <h2>为什么已经有 {@code order_approval_node} 了还要存定义</h2>
     * <p>节点表固化的是<b>执行结果</b>（谁审了、什么时候审的），而定义固化的是
     * <b>规则本身</b>（当时这套流程长什么样、条件是什么）。两者缺一不可：
     * 只有结果时，事后无法回答「为什么这笔单没走财务节点」——
     * 因为流程模板随时可能被改成 v2，而 v1 的规则已无处可查。
     * 与 {@code order_form_data} 固化「当初那一版表单」是同一思路（ 快照语义）。
     */
    private String approvalFlowJson;

    /**
     * 提交时所用的已发布审批流程版本 {@code approval_flow_version.id}（ · M7）。
     *
     * <h2>为什么快照之外还要一个指针</h2>
     * <p>{@link #approvalFlowJson} 能回答「这笔单当时按什么规则走的」，却<b>回答不了</b>
     * 「这笔单属于哪个流程模板」—— 快照里只有 {@code start} 与 {@code nodes}，没有模板/版本 id。
     * 而流程监控要按模板聚合（工单量 / 平均审批时长 / 瓶颈节点），缺了这个指针只能靠
     * 「快照 ≡ 已发布版本定义」反查；实测该反查在真实数据上命中率为 0
     * （模板删除会连版本行一起物理删除，且快照是求值后形态、与定义并非逐字节相同），
     * 详见 V22 迁移的说明。因此把归属在提交时落成一个**稳定的外键式指针**。
     *
     * <p>为 NULL 的三种正常情形：走分组固定审批人表的单、走借用单内置流程的单（未绑定模板）、
     * 以及本期上线前的存量工单（明确不回填，监控页归入「未归属」分组）。
     *
     * <p>刻意<b>不加数据库外键</b>：模板/版本是物理删除的，加外键会让「删模板」
     * 反过来被历史工单挡住。悬空由 {@link #approvalFlowName} 快照兜底显示。
     */
    private Long approvalFlowVersionId;

    /**
     * 提交时的流程模板名快照（ · M7）。
     *
     * <p>存在的唯一理由是应对「模板与版本会被物理删除」这个事实：删完之后
     * {@link #approvalFlowVersionId} 就反查不到名字，监控页会只剩一个孤零零的数字 id。
     * 存一份名字，让历史统计**始终可读**，与节点行冗余 {@code node_name} 是同一取向。
     */
    private String approvalFlowName;

    /** 申请设备 device.id */
    private Long deviceId;

    /** 申请人 user_id（取自登录态，「申请人从当前登录 Session 自动读取，只读」） */
    private Long applicantId;

    /** 快照：提交时申请人所属部门ID */
    private Long departmentId;

    /** 借用类型，取值见 {@link com.enterprise.ticket.common.constant.UseType} */
    private String useType;

    /** 用途（原「借用原因」； 起改为选填） */
    private String reason;

    /** 期望归还日期：短期借用必填，长期领用为空 */
    private LocalDate expectedReturnDate;

    /** 工单状态，取值见 {@link com.enterprise.ticket.common.constant.OrderStatus} */
    private String status;

    /** 快照：提交时绑定的最终处理部门ID */
    private Long handlerDepartmentId;

    /** 实际执行人 user_id：全部审批通过时按「加权随机」分配 */
    private Long actualFinalHandlerId;

    /** 是否借用超时（：2 次自动顺延后仍未归还置位； 落地） */
    private Boolean borrowTimeout;

    /** 自动顺延已用次数，最多 2 次（； 落地） */
    private Integer autoExtendCount;

    /** 计划借用结束时间：交付确认时按期望归还日期落库，顺延时整体后移 */
    private LocalDateTime plannedEndTime;

    /** 实际归还时间（； 落地） */
    private LocalDateTime actualEndTime;

    /** 归还触发来源，取值见 {@link com.enterprise.ticket.common.constant.ReturnTrigger} */
    private String returnTrigger;

    /** 申请人归还说明（，可选） */
    private String returnNote;

    /** 收回时登记的设备状态，取值见 {@link com.enterprise.ticket.common.constant.ReturnCondition} */
    private String returnCondition;

    /** 收回备注（实际执行人填写，如损坏情况说明） */
    private String returnRemark;

    /** 收回人 user_id（实际执行人；管理员强制收回时为该管理员） */
    private Long returnedBy;

    /** 最后一次超时告警时间（幂等：按 {@code timeout_alert_interval_hours} 间隔重复推送，） */
    private LocalDateTime lastTimeoutAlertAt;

    /** 到期前预警已发送时间（幂等：只发一次，） */
    private LocalDateTime remindBeforeSentAt;

    /** 到期当天提醒已发送时间（幂等：只发一次，需求方 ） */
    private LocalDateTime dueRemindedAt;

    /** 交付确认时间 */
    private LocalDateTime deliveredAt;

    /** 交付确认人 user_id（应为 actualFinalHandlerId） */
    private Long deliveredBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
