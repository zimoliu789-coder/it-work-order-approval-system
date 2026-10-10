package com.enterprise.ticket.module.dashboard.dto.vo;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 统计仪表盘聚合结果（ · M5）
 *
 * <h2>它回答的问题</h2>
 * <p>工作台要给业务管理员一个「这段时间系统里在发生什么」的快照，共七类信息：
 * 区间内工单总量、按类型 / 状态 / 时间 / 部门的四个分布、平均审批时长、超时率。
 * 前五者落在 {@code borrow_order}，后两者落在 {@code order_approval_node}。
 *
 * <h2>{@code null} ≠ {@code 0}（本 VO 最重要的一条约定）</h2>
 * <p>{@link #avgApprovalHours} 与 {@link #overdueRate} 在<b>没有样本</b>时是 {@code null}：
 * <ul>
 *   <li>区间内一张审批完成的单都没有 → 平均时长无法计算；</li>
 *   <li>区间内没有一个节点设过时限 → 超时率无法判定。</li>
 * </ul>
 * 这两件事与「平均 0 小时」「超时率 0%」是<b>相反</b>的结论：前者是「没数据」，
 * 后者是「很健康」。把它们混为一谈会让管理员对着 0% 以为一切正常。
 * 序列化层开启了 {@code non_null}，所以这两个字段在无样本时<b>整个不出现</b>，
 * 前端据此显示「—」；同时 {@link #approvedOrderCount} / {@link #withDeadlineNodeCount}
 * 一并返回，让界面上每个「—」都有据可查（能显示「0 / 15 有时限」）。
 *
 * <h2>与 M7 流程监控的口径关系</h2>
 * <p>这两个指标的 SQL 口径由 {@code ApprovalMetricSql} 与 M7 <b>逐字共享</b>，
 * 由单测钉住两者不会各改一处。区别只在分组维度：M7 按流程模板 / 节点分组，
 * 本 VO 是区间内整体。
 */
@Data
public class DashboardSummaryVO {

    /** 实际生效的区间下界（含）—— 回显给前端，避免「我传了 30 天但页面显示别的」 */
    private LocalDate from;

    /** 实际生效的区间上界（含） */
    private LocalDate to;

    /** 实际生效的粒度（{@code DAY} / {@code MONTH}） */
    private String granularity;

    /** 区间内工单总量（按 {@code borrow_order.created_at}） */
    private long totalOrders;

    /** 按工单类型的分布（{@code code} = {@code order_type}，{@code label} = 中文名） */
    private List<Bucket> byType;

    /** 按工单状态的分布（{@code code} = {@code status}，{@code label} = 中文名） */
    private List<Bucket> byStatus;

    /**
     * 按时间分布。{@code bucket} 形如 {@code 2026-09-30}（按天）或 {@code 2026-09}（按月）。
     *
     * <p><b>只返回有数据的桶</b>（不补零）：与既有 {@code ReportMapper} 的月份分布同一取向 ——
     * 补零需要服务端先把整个区间展开成日期序列，而「哪天没有工单」本身不需要一行 0 来表达，
     * 前端按桶标签渲染条形即可。
     */
    private List<TimeBucket> byTime;

    /** 按部门分布（部门 = {@code department}；{@code department_id} 为空归入「未分组」桶 {@code groupId = 0}） */
    private List<GroupBucket> byGroup;

    /**
     * 平均审批时长（小时，2 位小数）；区间内无「审批完成」的工单时为 {@code null}。
     *
     * <p>口径与 M7 的模板级平均完全一致：起点 = 工单提交时刻，终点 = 该工单
     * <b>最后一个终态审批节点</b>的操作时间（APPROVED / REJECTED 都算，CC 不算）。
     */
    private Double avgApprovalHours;

    /** 上述平均值的样本数 = 区间内「审批已完成」的工单数（让界面能显示分母） */
    private long approvedOrderCount;

    /**
     * 超时率（百分数，2 位小数）；区间内没有任何「设了时限的已决策轮次」时为 {@code null}。
     *
     * <p>分母只算 {@code deadline_at IS NOT NULL} 的轮次：没设时限的节点本来就无从判定超时。
     */
    private Double overdueRate;

    /** 上述超时率的分子 = 超时的已决策轮次数 */
    private long overdueNodeCount;

    /** 上述超时率的分母 = 设了时限的已决策轮次数 */
    private long withDeadlineNodeCount;

    // ==================================================================
    // 以下为内部聚合结构与 Mapper 的映射载体
    // ==================================================================

    /** 通用分布桶：代码 + 展示名 + 计数 */
    @Data
    public static class Bucket {
        /** 分布键的原始编码（如 {@code BORROW} / {@code PENDING_APPROVAL}） */
        private String code;
        /** 展示名（由服务层按枚举补全，避免前端各写一份映射） */
        private String label;
        /** 计数 */
        private Long count;
    }

    /** 时间分布桶 */
    @Data
    public static class TimeBucket {
        /** 桶标签：按天 {@code yyyy-MM-dd}，按月 {@code yyyy-MM} */
        private String bucket;
        /** 计数 */
        private Long count;
    }

    /** 部门分布桶 */
    @Data
    public static class GroupBucket {
        /** 部门 id；{@code 0} = 未分组（与 M7 的未归属桶同一哨兵手法，避免路径里出现 null） */
        private Long groupId;
        /** 分组名；由服务层补「未分组」/「未知分组」兜底文案 */
        private String groupName;
        /** 计数 */
        private Long count;
    }

    /** Mapper 载体：平均审批时长的原始聚合值 */
    @Data
    public static class ApprovalStats {
        /** 样本数（审批已完成的工单数） */
        private Long approvedOrderCount;
        /** 平均小时数；无样本时为 {@code null} */
        private Double avgApprovalHours;
    }

    /** Mapper 载体：超时率的原始聚合值（分子 / 分母 / 比值三者都回，便于界面显示与自证） */
    @Data
    public static class OverdueStats {
        /** 分子：超时的已决策轮次数 */
        private Long overdueNodeCount;
        /** 分母：设了时限的已决策轮次数 */
        private Long withDeadlineNodeCount;
        /** 比值（百分数）；分母为 0 时为 {@code null} */
        private Double overdueRate;
    }
}
