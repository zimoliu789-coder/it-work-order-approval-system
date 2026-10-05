package com.enterprise.ticket.common.flow;

import lombok.Data;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 超时处理动作。
 *
 * <h2>它与既有「超时提醒」的关系</h2>
 * <p>第二期已有的超时能力是<b>提醒</b>（{@code ApprovalTimeoutJobService}：按 deadline 或全局阈值
 * 扫描当前待审节点，向审批人与申请人各发一条消息，每天一次）。本期<b>不另起一个 job</b>，
 * 而是在同一个 job、同一把 {@code JobLockService} 锁、同一个 {@code last_remind_at} 幂等位
 * 之上，先提醒、再按本动作额外做一件事。
 *
 * <p>这样做而不是新起 job 的理由：两个 job 各自扫描同一批节点，必然出现
 * 「一个已提醒、另一个还没提醒」的中间态，重复消息与漏提醒都会出现；
 * 而且加签往往正是"提醒无效"之后的升级动作，二者共用一次扫描才能保证顺序（先礼后兵）。
 *
 * <h2>提醒与动作是否互斥</h2>
 * <p><b>不互斥</b>（需求方 D 已确认）：提醒照发，动作是额外叠加的。
 * 理由：提醒的对象是"人"（审批人 + 申请人），动作的对象是"流程"；
 * 加签之后原审批人仍然需要知道这单在他手上 —— 把提醒关掉只会让他更晚发现。
 *
 * @see RejectAction
 */
@Data
public class TimeoutAction {

    /** 仅提醒（默认，与第二期行为一致 —— 什么都不额外做） */
    public static final String NOTIFY = "NOTIFY";
    /** 加签：动态插入一个审批节点作为补充审批人 */
    public static final String ADD_SIGN = "ADD_SIGN";
    /** 改道：放弃当前节点，转而激活 target 指向的节点 */
    public static final String GOTO = "GOTO";

    private String action = NOTIFY;

    /**
     * 超时判定阈值（小时）。
     *
     * <p>与节点的 {@code timeLimitHours} 的分工：{@code timeLimitHours} 决定
     * {@code deadline_at} 快照与"什么时候开始提醒"；本值决定"超多久才升级动作"。
     * 前者通常更小（提醒要早），后者更大（升级要慎重）。
     * 为 null 时回落 {@code timeLimitHours}，再没有则用全局阈值。
     */
    private Integer afterHours;

    /** 改道目标节点 key（仅 {@link #GOTO}） */
    private String target;

    /**
     * 加签人（仅 {@link #ADD_SIGN}）。
     *
     * <p><b>为空时继承原节点的审批人规则</b>（需求方 Q2 已确认）——
     * 最常见的诉求正是"原审批人超时了，再加一个同级别的人一起看"，
     * 让配置者什么都不填就得到合理默认，比强制他重配一遍规则更不容易出错。
     * 填了则<b>完全替换</b>原规则（而不是并集）：显式覆盖必须是确定的语义，
     * 否则"我明明指定了 A，为什么 B 也收到了"是无法从界面解释的。
     */
    private List<ApproverRule> approvers = new ArrayList<>();

    public List<ApproverRule> getApprovers() {
        return approvers == null ? new ArrayList<>() : approvers;
    }

    public boolean isAddSign() {
        return ADD_SIGN.equalsIgnoreCase(action);
    }

    public boolean isGoto() {
        return GOTO.equalsIgnoreCase(action);
    }

    public boolean isNotifyOnly() {
        return NOTIFY.equalsIgnoreCase(action) || !StringUtils.hasText(action);
    }

    /** 配置是否合法（未知 action / GOTO 缺目标 / afterHours 非正 都算不合法） */
    public boolean isValid() {
        if (!StringUtils.hasText(action)) {
            return true;
        }
        boolean known = NOTIFY.equalsIgnoreCase(action)
                || ADD_SIGN.equalsIgnoreCase(action)
                || GOTO.equalsIgnoreCase(action);
        if (!known) {
            return false;
        }
        if (isGoto() && !StringUtils.hasText(target)) {
            return false;
        }
        return afterHours == null || afterHours > 0;
    }
}
