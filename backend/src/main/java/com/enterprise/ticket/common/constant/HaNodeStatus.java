package com.enterprise.ticket.common.constant;

/**
 * 主备节点运行状态（，  [164] [186]）
 *
 * <h2>为什么是这四档</h2>
 * <p>说明 [164] 行给了三档展示口径：「运行中 / 待命 / 异常」。本枚举额外补了
 * {@link #UNKNOWN}，原因是「三档」覆盖不了「刚登记、还没收到过一次心跳」这一瞬时状态 ——
 * 若把它硬塞进 {@link #ABNORMAL}，管理员一添加备节点就会立刻看到红色「异常」，
 * 而那其实只是握手尚未完成。用一个显式的未知态承接它，界面才能区分
 * 「还不知道」与「确实坏了」。
 *
 * <h2>为什么不用布尔 {@code healthy}</h2>
 * <p>「运行中」与「待命」都是健康的，但它们在界面上的含义不同：
 * {@link #RUNNING} 是「这台正在对外提供服务」，{@link #STANDBY} 是
 * 「这台随时能接管」。需求 [166] 行要求员工「不用管哪台是主」，
 * 恰恰意味着<b>维护人员必须能一眼分清</b> —— 用一个布尔把两者压平会丢掉这个信息。
 * 健康判定收敛到 {@link #isHealthy()} 一处即可。
 */
public enum HaNodeStatus {

    /** 运行中：该节点正在对外提供服务（通常是持有虚拟 IP 的主节点） */
    RUNNING("运行中"),

    /** 待命：进程正常、心跳正常，但未持有虚拟 IP，随时可接管 */
    STANDBY("待命"),

    /**
     * 异常：心跳超时 / 同步失败 / 脚本执行失败。
     *
     * <p>界面据此把该行变红（需求 [186]「页面上节点状态变红，明显提示异常」）。
     */
    ABNORMAL("异常"),

    /** 未知：刚登记、尚未收到过任何一次心跳 */
    UNKNOWN("未知");

    private final String label;

    HaNodeStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 是否健康。
     *
     * <p>{@link #UNKNOWN} 刻意<b>不算健康</b>：它不是「一切正常」，
     * 而是「还没拿到证据」。健康度的聚合（例如「整体是否可用」）只应以
     * RUNNING / STANDBY 为分母，否则一台从未连上的备机会让整体看起来是好的。
     */
    public boolean isHealthy() {
        return this == RUNNING || this == STANDBY;
    }

    /** 是否需要在界面上标红 */
    public boolean isAbnormal() {
        return this == ABNORMAL;
    }

    public static HaNodeStatus of(String value) {
        if (value == null) {
            return null;
        }
        for (HaNodeStatus status : values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        return null;
    }

    public static String labelOf(String value) {
        HaNodeStatus status = of(value);
        return status == null ? value : status.getLabel();
    }
}
