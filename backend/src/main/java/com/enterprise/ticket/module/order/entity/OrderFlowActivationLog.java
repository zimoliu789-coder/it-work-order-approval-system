package com.enterprise.ticket.module.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 流程节点激活决策日志。
 *
 * <h2>它为什么必须存在</h2>
 * <p>第二期最大的优点不是"能配条件"，而是<b>确定可复现</b>：条件只引用表单字段，
 * 所以任何一笔工单的审批路径，只要重放当时的表单数据就能逐字还原 ——
 * 出分歧时查因的成本极低。
 *
 * <p>M2 引入运行期条件后这个性质被打破了：路径在审批过程中才逐步确定，
 * 而"当时的 {@code process.elapsedHours} 是多少"已经无从考证。
 * 本表就是对这一点的<b>直接补偿</b>：虽然不再一次性定格，但每一步
 * 「谁在什么上下文下把哪个节点从什么状态改成了什么状态、为什么」都留了痕。
 * 出分歧时按 {@code order_id} + {@code created_at} 顺序重放，比一次性定格更可读 ——
 * 因为它把"为什么"记录成了事件序列，而不是一个终态。
 *
 * <h2>什么时候写</h2>
 * <p>只在**真正发生状态变更**时写，不写"算了一遍但结论没变"的空转记录：
 * {@code recompute} 是幂等的、可能被高频调用（每次审批动作、每次超时扫描），
 * 若把每次调用都记一行，日志表会迅速被"什么都没变"的记录淹没，
 * 真正有价值的那两条反而被埋掉。
 */
@Data
@TableName("order_flow_activation_log")
public class OrderFlowActivationLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 工单ID */
    private Long orderId;

    /** 流程节点稳定标识 */
    private String nodeKey;

    /** 决策前状态（INACTIVE / PENDING / SKIPPED …） */
    private String fromStatus;

    /** 决策后状态 */
    private String toStatus;

    /** 人类可读的决策原因 */
    private String reason;

    /** 决策时的运行期上下文快照（process.* 取值的 JSON），供回放 */
    private String ctxJson;

    private LocalDateTime createdAt;
}
