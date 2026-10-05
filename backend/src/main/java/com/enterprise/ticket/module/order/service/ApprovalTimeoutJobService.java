package com.enterprise.ticket.module.order.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ticket.common.constant.MessageType;
import com.enterprise.ticket.common.job.JobLockService;
import com.enterprise.ticket.common.job.JobResult;
import com.enterprise.ticket.module.message.service.MessageService;
import com.enterprise.ticket.module.order.dto.OverdueApprovalNode;
import com.enterprise.ticket.module.order.entity.Order;
import com.enterprise.ticket.module.order.entity.OrderApprovalNode;
import com.enterprise.ticket.module.order.mapper.OrderApprovalNodeMapper;
import com.enterprise.ticket.module.order.mapper.OrderMapper;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 审批超时提醒任务（需求方三波·第二波·； 扩展； 再扩展）
 *
 * <p>把「长期未有人审批」的当前节点提醒给<b>当前审批人 + 申请人</b>（ 起双接收人），
 * 并在同一轮扫描里按节点的 {@code onTimeout} 规则额外执行<b>加签 / 改道</b>。
 *
 * <h2>两种时限口径</h2>
 * <ul>
 *   <li>节点有 {@code deadline_at}（FLOW 流程里配了审批时限）：以「已过截止时间」为准，
 *       文案「已超过约定审批时限 X 小时」；</li>
 *   <li>节点无 {@code deadline_at}（借用单 / GROUP 单）：回落既有口径
 *       {@code orders.created_at + approval_timeout_remind_hours}（默认 24），文案不变。</li>
 * </ul>
 *
 * <h2>频率</h2>
 * <p>调度改为<b>每天凌晨一次</b>（ 需求）。为了让「每天一次」在语义上成立，
 * 有 deadline 的节点去重窗口固定为 24 小时；无 deadline 的节点沿用 {@code hours}
 * （默认同样是 24），因此两种口径在实际运行中都表现为「一天最多一条」。
 *
 * <h2>三层幂等保护</h2>
 * <ol>
 *   <li><b>分布式锁</b>（{@link JobLockService}）—— 多实例部署时只有一个实例真正执行；</li>
 *   <li><b>条件 UPDATE 抢占</b>—— 把 {@code last_remind_at} 的写入做成
 *       「仅当仍满足幂等条件时才更新」，返回 1 才发消息。
 *       即便锁因 Redis 抖动失效（fail-open 时确实可能），并发也不会发出重复通知；</li>
 *   <li><b>扫描上限</b>—— 单次最多处理 {@value #MAX_BATCH} 条，
 *       避免某天积压上千条时一次性打出上千条消息与上百次 UPDATE。</li>
 * </ol>
 *
 * <p><b>消息发送失败不回滚 last_remind_at</b>：消息服务本身是 best-effort 语义，
 * 若为了「保证送达」而回滚幂等位，会造成「一次投递失败 → 下次扫描重试 → 最终成功但期间重复」
 * 的雪崩式重试。宁可极低概率漏一条提醒，也不制造重复轰炸。
 *
 * <h2>：提醒与流程动作共用一次扫描（需求方 D）</h2>
 * <p>不新起 job。每轮对一条超时节点：<b>先提醒（审批人 + 申请人），再按 {@code onTimeout} 决定是否
 * 加签 / 改道</b>。二者<b>不互斥</b> —— 提醒的对象是"人"，动作的对象是"流程"；把提醒关掉只会让
 * 原审批人更晚发现自己这单还在手上。共用同一把锁与同一个 {@code last_remind_at} 幂等位，
 * 保证两个动作永远成对出现（不会"一个已提醒、另一个还没提醒"）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalTimeoutJobService {

    /** 单次扫描上限 */
    private static final int MAX_BATCH = 200;

    /**
     * 有 deadline 节点的去重窗口（小时）：固定 24 小时 = 「每天一次」。
     * 与调度频率（每天凌晨一次）配套；写死而非做成配置项，避免两处配置互相打架。
     */
    private static final int DEADLINE_REMIND_WINDOW_HOURS = 24;

    private static final String JOB_NAME = "approval-timeout-remind";

    private final OrderApprovalNodeMapper nodeMapper;
    private final OrderMapper orderMapper;
    private final MessageService messageService;
    private final SystemConfigService systemConfigService;
    private final JobLockService jobLockService;

    /**
     * 流程节点激活服务（M2）：仅用于「按 onTimeout 规则执行加签 / 改道」。
     * 方向单向（它不反向依赖本 job），不构成循环依赖。
     */
    private final FlowActivationService flowActivationService;

    /** 调度入口（由 {@code ApprovalTimeoutRemindJob} 每天凌晨触发） */
    public JobResult remindOverdueApprovals() {
        return jobLockService.runLocked(JOB_NAME, Duration.ofMinutes(50),
                this::doRemind, () -> JobResult.skipped(JOB_NAME));
    }

    private JobResult doRemind() {
        int hours = systemConfigService.approvalTimeoutRemindHours();
        List<OverdueApprovalNode> nodes =
                nodeMapper.findOverdueNodes(hours, DEADLINE_REMIND_WINDOW_HOURS, MAX_BATCH);
        if (nodes.isEmpty()) {
            return JobResult.of(JOB_NAME, 0, 0, "没有超时未处理的审批节点");
        }

        LocalDateTime now = LocalDateTime.now();
        int notified = 0;
        int escalated = 0;
        for (OverdueApprovalNode node : nodes) {
            // 有 deadline 的节点按 24h 去重（每天一次）；无 deadline 的沿用原阈值窗口
            int window = node.getDeadlineAt() != null ? DEADLINE_REMIND_WINDOW_HOURS : hours;
            if (!claimRemind(node.getNodeId(), now, window)) {
                // 已被另一个执行者提醒过，跳过发送
                continue;
            }
            // 先礼后兵：提醒无条件发（需求方 D），动作是额外叠加的
            notifyApprover(node, hours, now);
            notifyApplicant(node, hours, now);
            if (applyTimeoutAction(node)) {
                escalated++;
            }
            notified++;
        }
        log.info("审批超时提醒任务完成：扫描 {} 条，发送 {} 条，触发流程动作 {} 条",
                nodes.size(), notified, escalated);
        String detail = "超时未处理的审批节点 " + nodes.size() + " 条，已提醒 " + notified + " 条"
                + (escalated > 0 ? "，触发加签/改道 " + escalated + " 条" : "");
        return JobResult.of(JOB_NAME, nodes.size(), notified, detail);
    }

    /**
     * 按节点超时节点的 {@code onTimeout} 规则执行流程动作（加签 / 改道）。
     *
     * <p>单独包一层方法而不是直接调服务，是为了让 job 对"是否真的动了流程"这事有据可查
     * （写进 JobResult 的明细），且把所有异常都挡在这里 —— <b>动作失败绝不能让整轮提醒失败</b>：
     * 提醒是每天都该发生的事，不能因为某个工单的配置写脏了就整天不再提醒。
     *
     * @return true 表示确实改变了流程
     */
    private boolean applyTimeoutAction(OverdueApprovalNode node) {
        try {
            Order order = orderMapper.selectById(node.getOrderId());
            if (order == null) {
                return false;
            }
            return flowActivationService.applyTimeoutAction(order, node.getNodeId());
        } catch (RuntimeException e) {
            log.warn("工单 {} 节点 {} 的超时流程动作执行失败，已忽略（提醒不受影响）",
                    node.getOrderNo(), node.getNodeId(), e);
            return false;
        }
    }

    /** 发给当前审批人：请尽快处理 */
    private void notifyApprover(OverdueApprovalNode node, int hours, LocalDateTime now) {
        if (node.getApproverId() == null) {
            // 无审批人（理论上已被 SQL 排除；此处为纵深防御）：跳过发送但保留幂等位，
            // 否则这一条会被反复捞出来空转
            log.warn("审批节点 {} 没有审批人，跳过超时提醒", node.getNodeId());
            return;
        }
        messageService.send(node.getApproverId(), MessageType.APPROVAL_TIMEOUT_REMIND,
                "审批超时提醒", approverBody(node, hours, now), node.getOrderId());
    }

    /**
     * 发给申请人：知道自己的单卡在谁那里、卡了多久。
     *
     * <p>与审批人的是同一类型消息，但文案不同 —— 审批人需要「去做」，
     * 申请人需要「知道」。
     */
    private void notifyApplicant(OverdueApprovalNode node, int hours, LocalDateTime now) {
        if (node.getApplicantId() == null || node.getApplicantId().equals(node.getApproverId())) {
            // 申请人 = 审批人时不重复发（同一个人没必要收两条）
            return;
        }
        messageService.send(node.getApplicantId(), MessageType.APPROVAL_TIMEOUT_REMIND,
                "你的工单审批已超时",
                "你提交的工单 " + node.getOrderNo() + "（设备：" + safe(node.getDeviceName())
                        + "）当前审批节点" + overduePhrase(node, hours, now) + "，仍在等待审批人处理。",
                node.getOrderId());
    }

    /** 审批人文案：区分「有 deadline」与「回落全局阈值」两版 */
    private String approverBody(OverdueApprovalNode node, int hours, LocalDateTime now) {
        String device = "（设备：" + safe(node.getDeviceName()) + "）";
        if (node.getDeadlineAt() != null) {
            long overdueHours = overdueHours(node.getDeadlineAt(), now);
            return "工单 " + node.getOrderNo() + device + "由 " + safe(node.getApplicantName())
                    + " 提交，当前审批节点已超过约定审批时限 " + overdueHours + " 小时，请尽快处理。";
        }
        return "工单 " + node.getOrderNo() + device + "由 " + safe(node.getApplicantName())
                + " 提交后已超过 " + hours + " 小时未审批，请尽快处理。";
    }

    private String overduePhrase(OverdueApprovalNode node, int hours, LocalDateTime now) {
        if (node.getDeadlineAt() != null) {
            return "已超过约定审批时限 " + overdueHours(node.getDeadlineAt(), now) + " 小时";
        }
        return "已超过 " + hours + " 小时未处理";
    }

    private long overdueHours(LocalDateTime deadline, LocalDateTime now) {
        long minutes = ChronoUnit.MINUTES.between(deadline, now);
        long hours = minutes / 60;
        // 不足 1 小时按 1 小时展示，避免出现「已超过 0 小时」这种读起来像出错的文案
        return Math.max(1, hours);
    }

    /**
     * 抢占提醒权：仅当该节点仍处于「从未提醒 / 距上次提醒超过窗口」时才写入时间戳。
     *
     * @return true 表示本次由我提醒（调用方应发送消息）
     */
    private boolean claimRemind(Long nodeId, LocalDateTime now, int windowHours) {
        LocalDateTime threshold = now.minusHours(windowHours);
        int rows = nodeMapper.update(null, Wrappers.<OrderApprovalNode>lambdaUpdate()
                .eq(OrderApprovalNode::getId, nodeId)
                .and(w -> w.isNull(OrderApprovalNode::getLastRemindAt)
                        .or().lt(OrderApprovalNode::getLastRemindAt, threshold))
                .set(OrderApprovalNode::getLastRemindAt, now));
        return rows > 0;
    }

    private String safe(String value) {
        return value == null ? "-" : value;
    }
}
