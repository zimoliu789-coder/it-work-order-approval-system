package com.enterprise.ticket.module.dashboard.mapper;

import com.enterprise.ticket.common.constant.ApprovalMetricSql;
import com.enterprise.ticket.module.dashboard.dto.vo.DashboardSummaryVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 统计仪表盘聚合查询（ · M5）
 *
 * <h2>为什么是「若干条小聚合」而不是一条大 SQL</h2>
 * <p>七个维度取自三张表的不同联接形态，硬凑成一条会导致笛卡尔积放大 ——
 * 例如把「按类型计数」与「按节点计时」放进同一 FROM，节点行会把工单数乘出 N 倍。
 * 因此拆成互不干扰的独立聚合，每条各自走自己的索引。
 *
 * <p>数据量级也支持这个选择：每条查询返回的都是「分布桶」（类型数 / 状态数 / 天数 /
 * 部门数，几十行量级），不存在把明细读进内存再算的问题 —— 与 {@code ReportMapper}
 * 面对的全量明细场景不同，因此不需要在那里担心的内存放大。
 *
 * <h2>区间一律左闭右开 {@code [from, to)}</h2>
 * <p>由 {@code DashboardQuery} 把用户选的「{@code to} 当天」换算成次日零点，
 * SQL 只做比较、不对列用函数（保证 {@code created_at} 上的索引可用），
 * 也不用 {@code BETWEEN}（闭区间会漏掉月底最后一秒之后的数据）。
 *
 * <h2>两个效率指标与 M7 共享口径</h2>
 * <p>{@link #overdueStats} 与 {@link #approvalStats} 的表达式取自
 * {@link ApprovalMetricSql}，与 {@code FlowMonitorMapper} 逐字一致 ——
 * 详见该类注释与 {@code ApprovalMetricSqlTest}。
 */
@Mapper
public interface DashboardMapper {

    // ==================================================================
    // 一、总量与三个分布
    // ==================================================================

    /** 区间内工单总量 */
    @Select("""
            SELECT COUNT(*)
              FROM orders o
             WHERE o.created_at >= #{from} AND o.created_at < #{to}
            """)
    Long countOrders(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * 按工单类型分布（计数降序）。
     *
     * <p>只回编码与计数，<b>中文名由服务层按 {@code OrderType} 补</b> ——
     * 让「枚举 → 展示名」的映射只有一处（枚举本身），SQL 里不写第二份中文。
     * 排序里带 {@code code} 次级键，保证完全并列时顺序稳定（可断言）。
     */
    @Select("""
            SELECT o.order_type AS code,
                   COUNT(*)     AS `count`
              FROM orders o
             WHERE o.created_at >= #{from} AND o.created_at < #{to}
             GROUP BY o.order_type
             ORDER BY `count` DESC, code ASC
            """)
    List<DashboardSummaryVO.Bucket> countByType(@Param("from") LocalDateTime from,
                                               @Param("to") LocalDateTime to);

    /** 按工单状态分布（计数降序，中文名同样在服务层补） */
    @Select("""
            SELECT o.status  AS code,
                   COUNT(*)  AS `count`
              FROM orders o
             WHERE o.created_at >= #{from} AND o.created_at < #{to}
             GROUP BY o.status
             ORDER BY `count` DESC, code ASC
            """)
    List<DashboardSummaryVO.Bucket> countByStatus(@Param("from") LocalDateTime from,
                                                 @Param("to") LocalDateTime to);

    /**
     * 按时间分布。
     *
     * <p>{@code pattern} 由服务层按粒度给出（{@code %Y-%m-%d} 或 {@code %Y-%m}）并以
     * <b>普通参数</b>传入：这样只有一条静态 SQL，也不需要在注解里拼 {@code choose/when}。
     * {@code GROUP BY} 与 {@code SELECT} 用同一个 {@code DATE_FORMAT} 表达式（MySQL 允许按别名分组，
     * 但这里刻意写全表达式 —— 别名在部分 SQL 模式下不被 GROUP BY 识别，写全最稳）。
     */
    @Select("""
            SELECT DATE_FORMAT(o.created_at, #{pattern}) AS bucket,
                   COUNT(*)                              AS `count`
              FROM orders o
             WHERE o.created_at >= #{from} AND o.created_at < #{to}
             GROUP BY DATE_FORMAT(o.created_at, #{pattern})
             ORDER BY bucket ASC
            """)
    List<DashboardSummaryVO.TimeBucket> countByTime(@Param("from") LocalDateTime from,
                                                    @Param("to") LocalDateTime to,
                                                    @Param("pattern") String pattern);

    /**
     * 按部门（部门）分布（计数降序）。
     *
     * <p>{@code COALESCE(o.department_id, 0)} 把「未分组」工单归入零号桶 ——
     * 与 M7 的「未归属」桶同一手法（自增主键不会产出 0，作哨兵是安全的）。
     * <b>分组名由服务层补</b>（「未分组」/「未知分组」），SQL 里不写中文文案，
     * 保持「展示文案只有一处」。
     *
     * <p>按 {@code department_id} 而不是分组名分组：分组被改名时历史工单仍归到同一 id，
     * 不会因为名字变化裂成两行。
     */
    @Select("""
            SELECT COALESCE(o.department_id, 0) AS groupId,
                   g.dept_name                 AS groupName,
                   COUNT(*)                    AS `count`
              FROM orders o
              LEFT JOIN departments g ON g.id = o.department_id
             WHERE o.created_at >= #{from} AND o.created_at < #{to}
             GROUP BY COALESCE(o.department_id, 0), g.dept_name
             ORDER BY `count` DESC, groupId ASC
            """)
    List<DashboardSummaryVO.GroupBucket> countByGroup(@Param("from") LocalDateTime from,
                                                     @Param("to") LocalDateTime to);

    // ==================================================================
    // 二、效率指标（口径与 M7 流程监控共享，见类注释）
    // ==================================================================

    /**
     * 平均审批时长（小时）与其样本数。
     *
     * <p>写法说明：本方法的 SQL 用<b>显式字符串拼接</b>而非文本块，因为要在 FROM 子句里
     * 内联 {@link ApprovalMetricSql#FINISHED_APPROVAL_SUBQUERY}（文本块的尾随空白会被裁掉，
     * 拼在中间容易出现 {@code JOINap} 这种缺空格的静默错误）。拼写代价换来确定性。
     *
     * <p>无样本时该聚合仍返回一行：{@code COUNT(*) = 0}、{@code AVG(...) = NULL}，
     * 由服务层归一成「0 个样本 + 无均值」。
     */
    @Select("SELECT COUNT(*) AS approvedOrderCount,"
            + " ROUND(AVG(TIMESTAMPDIFF(SECOND, o.created_at, ap.finished_at)) / 3600, 2) AS avgApprovalHours"
            + " FROM orders o"
            + " JOIN " + ApprovalMetricSql.FINISHED_APPROVAL_SUBQUERY
            + " ap ON ap.order_id = o.id"
            + " WHERE o.created_at >= #{from} AND o.created_at < #{to}")
    DashboardSummaryVO.ApprovalStats approvalStats(@Param("from") LocalDateTime from,
                                                   @Param("to") LocalDateTime to);

    /**
     * 超时率（百分数）与其分子 / 分母。
     *
     * <p>节点范围与 M7 完全一致：只统计<b>已决策</b>（APPROVED / REJECTED、{@code action_time}
     * 非空）且<b>非抄送</b>（{@code node_type IS NULL OR 'APPROVAL'}）的轮次。
     * 抄送节点的 {@code action_time} 是「已通知」时刻，纳入会把超时率算歪。
     *
     * <p>分母为 0 时 {@code overdueRate} 为 {@code NULL}（见 {@link ApprovalMetricSql#OVERDUE_RATE}），
     * 而两个计数为 0 —— 「没有设时限的轮次」与「没超时」是两件事，界面据此显示「—」而非 0%。
     */
    @Select("SELECT COUNT(CASE WHEN n.deadline_at IS NOT NULL THEN 1 END) AS withDeadlineNodeCount,"
            + " COUNT(CASE WHEN n.deadline_at IS NOT NULL AND n.action_time > n.deadline_at THEN 1 END) AS overdueNodeCount,"
            + " " + ApprovalMetricSql.OVERDUE_RATE + " AS overdueRate"
            + " FROM order_approval_nodes n"
            + " JOIN orders o ON o.id = n.order_id"
            + " WHERE o.created_at >= #{from} AND o.created_at < #{to}"
            + "   AND n.action_time IS NOT NULL"
            + "   AND n.status IN ('APPROVED', 'REJECTED')"
            + "   AND (n.node_type IS NULL OR n.node_type = 'APPROVAL')")
    DashboardSummaryVO.OverdueStats overdueStats(@Param("from") LocalDateTime from,
                                                 @Param("to") LocalDateTime to);
}
