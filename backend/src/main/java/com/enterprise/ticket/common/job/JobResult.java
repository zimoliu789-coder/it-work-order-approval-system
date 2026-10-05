package com.enterprise.ticket.common.job;

/**
 * 定时任务执行结果（「记录执行日志，便于运维排查」）
 *
 * @param jobName  任务名（与分布式锁 key、手动触发接口的 job 参数一致）
 * @param affected 本次任务实际处理的工单数
 * @param notified 本次任务实际发出的站内消息条数
 * @param detail   人类可读的补充说明（如「顺延 2 单，其中 1 单已标记超时」）
 */
public record JobResult(String jobName, int affected, int notified, String detail) {

    /** 分布式锁未获取到（其他实例正在执行）时的结果 */
    public static JobResult skipped(String jobName) {
        return new JobResult(jobName, 0, 0, "未获取到分布式锁，本实例跳过本次执行");
    }

    public static JobResult of(String jobName, int affected, int notified, String detail) {
        return new JobResult(jobName, affected, notified, detail);
    }
}
