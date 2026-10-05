package com.enterprise.ticket.module.order.service;

import com.enterprise.ticket.common.job.JobResult;

import java.util.List;

/**
 * 借用到期 / 顺延 / 超时 定时任务（ /  / ，需求方  /3/4）
 *
 * <p>三个任务彼此独立又构成一条时序链，因此调度顺序被刻意固定为
 * <b>到期预警 → 自动顺延 → 超时告警</b>（见 {@code BorrowJobScheduler}）：
 * <ul>
 *   <li><b>到期预警</b>必须在顺延之前跑。「到期当天再提醒一次」的依据是
 *       {@code planned_end_time} 的日期部分，而顺延会把该时间整体后移一天 ——
 *       若先顺延，当晚的「到期当天提醒」就永远不会触发；</li>
 *   <li><b>超时告警</b>必须在顺延之后跑。顺延任务会把 {@code auto_extend_count} 推到上限，
 *       超时任务据此判定「2 次顺延后仍未归还」（ 最后一段）。</li>
 * </ul>
 *
 * <p><b>幂等性设计</b>：每个方法都可能被重复触发（定时、运维手动、多实例并发），
 * 因此统一采取两层保护：
 * <ol>
 *   <li>数据层的幂等位/计数器做「是否已处理」判定（{@code remind_before_sent_at} /
 *       {@code due_reminded_at} / {@code last_timeout_alert_at} / {@code auto_extend_count}）；</li>
 *   <li>状态推进全部使用带前置条件的条件 UPDATE，并在更新 0 行时放弃通知 ——
 *       保证「通知」严格发生在「状态已真正推进」之后，不会出现重复通知。</li>
 * </ol>
 *
 * <p>本接口方法内部已包含分布式锁（{@code JobLockService}），
 * 因此无论是调度器触发还是运维手动触发，都不会出现两个实例同时处理同一批工单。
 */
public interface BorrowJobService {

    String JOB_EXPIRE_WARNING = "borrow-expire-warning";
    String JOB_AUTO_EXTEND = "borrow-auto-extend";
    String JOB_TIMEOUT_ALERT = "borrow-timeout-alert";

    /**
     * 到期预警（「距离 planned_end_time ≤ N 天，推送到期预警」，需求方  ）
     *
     * <p>两段互斥的通知，各自只发一次：
     * <ul>
     *   <li>到期前 N 天（N = {@code borrow_expire_warning_days}，默认 1）→ 提醒申请人 + 实际执行人；</li>
     *   <li>到期当天 → 再提醒一次（申请人 + 实际执行人）。</li>
     * </ul>
     * 已逾期（到期日早于今天）的工单不再发预警，交由自动顺延/超时任务处理，避免每天刷屏。
     */
    JobResult warnExpiringBorrows();

    /**
     * 自动顺延（：到期未归还，{@code planned_end_time += 1 天}，最多 2 次）
     *
     * <p>一次运行内按规范逐次顺延，直到「结束时间越过当前时间」或「达到最大次数」为止 ——
     * 这样即使任务因故停摆数日，重启后也能一次性补齐顺延次数并进入超时判定，
     * 不会让一笔早已严重逾期的工单继续停留在「还能顺延」的状态。
     *
     * <p>长期领用（{@code planned_end_time} 为空）天然被排除，不参与顺延与超时（需求方  ）。
     */
    JobResult autoExtendOverdueBorrows();

    /**
     * 超时标记与告警（：2 次顺延后仍未归还 → {@code borrow_timeout = true} + 告警）
     *
     * <p>告警按 {@code timeout_alert_interval_hours}（默认 24 小时）重复推送，
     * 用 {@code last_timeout_alert_at} 做幂等控制。
     * 若实际执行人已离职/禁用，告警自动转发 super_admin 并写审计日志（ / ）。
     *
     * <p>注意：超时只置标记位，<b>不改变工单主状态、不改变设备状态、不自动归还</b>
     * （ / 「到期不自动归还、不自动释放设备」）。
     */
    JobResult alertTimeoutBorrows();

    /** 依次执行三个任务，返回各自结果（供运维手动触发与验收使用） */
    List<JobResult> runAll();
}
