package com.enterprise.ticket.common.constant;

/**
 * 审批效率指标的共享 SQL 片段（ · M5）
 *
 * <h2>为什么这两个片段必须共享，而不是各写各的</h2>
 * <p>「平均审批时长」与「超时率」在本系统里有<b>两个消费方</b>：
 * M7 流程监控（按流程模板 / 节点看）与 M5 统计仪表盘（按时间区间整体看）。
 * 两者问的是同一个问题，只是分组维度不同 —— 如果各写一套 WHERE 与 CASE 表达式，
 * 同一批数据在监控页与工作台给出<b>两个数</b>，用户会先怀疑数据，再怀疑整套系统。
 *
 * <p>因此把口径<b>逐字抽成常量</b>：M5 直接引用；M7 的既有 SQL 保持原样不动
 * （零回归），但由 {@code ApprovalMetricSqlTest} 用「规范化空白后包含」的方式
 * <b>钉住 M7 的 SQL 文本确实与本常量同形</b>。将来任何一侧改了公式，
 * 另一侧的测试会立刻变红 —— 这正是「防止将来各改一处」要的效果。
 *
 * <h2>片段的使用前提（务必遵守）</h2>
 * <ul>
 *   <li>{@link #OVERDUE_RATE} 与 {@link #FINISHED_APPROVAL_SUBQUERY} 都假定
 *       {@code order_approval_node} 的别名是 {@code n}；调用方的 FROM/JOIN 必须给出这个别名。</li>
 *   <li>{@code OVERDUE_RATE} 是<b>聚合表达式</b>，只能出现在 {@code SELECT} 列表里，
 *       且作用域内已把节点限定为「已决策」（{@code status IN ('APPROVED','REJECTED')}
 *       且 {@code action_time IS NOT NULL}）。</li>
 *   <li>片段内一律不写模板名 / 时间区间等调用方条件 —— 那些是分组维度，属于调用方。</li>
 * </ul>
 *
 * <h2>{@code NULLIF} 不是装饰</h2>
 * <p>分母为 0 时（一张工单都没设过时限）超时率必须是 {@code NULL} 而<b>不是 0</b>：
 * 「没有任何节点设了时限」与「所有节点都按时完成」是相反的管理结论。
 * 返回 null 后由序列化层（{@code non_null}）让字段整体不出现，前端据此显示「—」。
 */
public final class ApprovalMetricSql {

    private ApprovalMetricSql() {
    }

    /**
     * 超时率表达式（百分数，保留 2 位小数；分母为 0 → {@code NULL}）。
     *
     * <p>口径：分子 = {@code deadline_at} 非空<b>且</b> {@code action_time} 晚于 {@code deadline_at}
     * 的轮次数；分母 = {@code deadline_at} 非空的轮次数。刻意只统计「设了时限」的轮次 ——
     * 没设时限的节点本来就无从判定超时，把它们放进分母会把超时率稀释成一个无意义的数字。
     */
    public static final String OVERDUE_RATE =
            "ROUND(100 * SUM(CASE WHEN n.deadline_at IS NOT NULL AND n.action_time > n.deadline_at"
                    + " THEN 1 ELSE 0 END)"
                    + " / NULLIF(SUM(CASE WHEN n.deadline_at IS NOT NULL THEN 1 ELSE 0 END), 0), 2)";

    /**
     * 「每张工单最后一个终态审批节点」子查询（按 {@code order_id} 聚合，输出 {@code finished_at}）。
     *
     * <p>三个限定条件各有其因：
     * <ul>
     *   <li>{@code status IN ('APPROVED','REJECTED')} —— 只认已决策的节点。
     *       把 {@code PENDING} 算进来会让「还没审完」变成「已审完」，
     *       把 REJECTED 排除则会让被驳回的工单在统计里凭空消失（它们同样走完了审批）；</li>
     *   <li>{@code node_type IS NULL OR node_type = 'APPROVAL'} —— 排掉抄送（CC）节点。
     *       CC 的 {@code action_time} 是「已通知」的时刻，不是审批行为，
     *       把它当成终点会让审批时长凭空缩短；{@code IS NULL} 兼容二期之前无类型的节点行；</li>
     *   <li>{@code MAX(action_time)} —— 用户感知的等待覆盖到审批<b>真正结束</b>为止，
     *       而不是第一步通过。</li>
     * </ul>
     *
     * <p>调用方需自带别名（既有用法是 {@code ... ) ap ON ap.order_id = o.id}）。
     */
    public static final String FINISHED_APPROVAL_SUBQUERY =
            "(SELECT n.order_id, MAX(n.action_time) AS finished_at"
                    + " FROM order_approval_node n"
                    + " WHERE n.status IN ('APPROVED', 'REJECTED')"
                    + " AND (n.node_type IS NULL OR n.node_type = 'APPROVAL')"
                    + " GROUP BY n.order_id)";
}
