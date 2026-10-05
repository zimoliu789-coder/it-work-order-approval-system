package com.enterprise.ticket.module.report.dto.vo;

import lombok.Data;

import java.util.List;

/**
 * 管理概览（P3 管理层数据看板）
 *
 * <p>与既有三张明细报表的区别：它们是「清单」（按设备/按分类/按月的明细行），
 * 本 VO 是<b>概览</b>——用最少的数字回答管理层「现在什么情况」。
 *
 * <h2>两类指标的时间语义刻意不同</h2>
 * <ul>
 *   <li><b>随区间变化的</b>（{@code monthlyBorrowCount} / {@code departmentRanking} / {@code trend}）：
 *       区间由 {@code ReportQuery} 的 year/month 决定，<b>未指定时默认为当前自然月</b>
 *       —— 因为这一栏的文案就是「本月借出」，默认看到「全部历史」会与文案不符；</li>
 *   <li><b>当前状态的</b>（逾期两个口径 / {@code utilizationRate}）：
 *       <b>不受区间影响</b>。「现在有多少台设备在用」问的不是「某个月有多少台在用」，
 *       给它套时间区间是语义错误。页面会把这两张卡标注为「当前」。</li>
 * </ul>
 *
 * <h2>逾期为什么给两个数</h2>
 * <p>两者语义不同，缺一个都会误导（演示库实测：主 14 / 副 15）：
 * <ul>
 *   <li>{@code overdueTimeoutCount}（主）：{@code borrow_timeout = 1} ——  的权威口径，
 *       由 {@code BorrowJobService} 在「2 次顺延后仍未归还」时置位，与「全部工单」页的「超时」
 *       筛选同源。改一处两处一致，不会出现「看板说 14、列表筛出 15」的尴尬；</li>
 *   <li>{@code overdueGraceCount}（副）：仍在借且 {@code planned_end_time} 已过 ——
 *       <b>含顺延宽限期内</b>的工单，回答「当下有多少该还没还」。它总是 ≥ 主口径。</li>
 * </ul>
 *
 * <h2>利用率必须能自解释</h2>
 * <p>分子分母一并返回（{@code utilizationNumerator} / {@code utilizationDenominator}），
 * 页面据此标注口径。只给一个百分比，读者无法判断「36%」是「在用的比例」还是「含审批中」，
 * 也无法解释为什么它与「在用台数 / 总台数」对不上。
 */
@Data
public class DashboardOverviewVO {

    // ------------------------------------------------------------------
    // 区间回显（页面据此显示「本月」还是「2026-09」）
    // ------------------------------------------------------------------

    /** 区间下界（{@code yyyy-MM-dd}） */
    private String from;

    /** 区间上界（不含，{@code yyyy-MM-dd}） */
    private String to;

    /** 区间文案：{@code 2026-10} / {@code 2026} / {@code 全部时间} */
    private String rangeLabel;

    // ------------------------------------------------------------------
    // ① 借出（随区间）
    // ------------------------------------------------------------------

    /** 区间内借用申请数（默认区间为当前月，即「本月借出」） */
    private long monthlyBorrowCount;

    /** 区间内被借用过的设备数（去重）——「借出」是次数，这里是「涉及多少台」 */
    private long monthlyBorrowDeviceCount;

    // ------------------------------------------------------------------
    // ② 逾期（当前状态，不受区间影响）
    // ------------------------------------------------------------------

    /** 主口径：系统超时标记（{@code borrow_timeout = 1}）且仍在借 */
    private long overdueTimeoutCount;

    /** 副口径：仍在借且计划归还时间已过（含顺延宽限期内） */
    private long overdueGraceCount;

    // ------------------------------------------------------------------
    // ③ 设备利用率（当前状态，不受区间影响）
    // ------------------------------------------------------------------

    /** 设备总数（未逻辑删除） */
    private long deviceTotal;

    /** 各状态台数 —— 环形图数据源，也让利用率可核对 */
    private long deviceAvailableCount;
    private long deviceInUseCount;
    private long deviceInApprovalCount;
    private long deviceMaintenanceCount;
    private long deviceLostCount;
    private long deviceScrappedCount;

    /** 分子：在用 + 审批中（审批中的设备已被占用，管理视角就是「在被用」） */
    private long utilizationNumerator;

    /** 分母：总数 − 报废（报废设备不可调度，计入会虚低利用率） */
    private long utilizationDenominator;

    /** 利用率百分比（保留 1 位小数）；分母为 0 时为 0 */
    private double utilizationRate;

    // ------------------------------------------------------------------
    // ④ 部门借用排行（随区间）
    // ------------------------------------------------------------------

    /** 按申请人所属部门聚合的借用次数（降序，Top N） */
    private List<DepartmentItem> departmentRanking;

    /** 借用趋势（按天或按月分桶，取决于区间跨度） */
    private List<TrendItem> trend;

    /** 单个部门的借用次数 */
    @Data
    public static class DepartmentItem {
        /** 申请人所属部门 id；账号未分配部门时为 {@code null} */
        private Long departmentId;
        /** 部门名；未分配时为「未分配」 */
        private String departmentName;
        private Long borrowCount;
    }

    /** 趋势分桶 */
    @Data
    public static class TrendItem {
        /** 桶标签：按月为 {@code 2026-10}，按天为 {@code 2026-10-04} */
        private String bucket;
        private Long count;
    }

    /**
     * 设备状态分布的一次性聚合结果（供 Mapper 直接映射，避免 6 次 COUNT 扫表）
     *
     * <p>{@code lost} 是 P0 新增的设备状态（归还检查判「丢失」时置位），
     * 这里一并统计 —— 管理层关心「丢了几台」，而它不该被混进「报废」里。
     */
    @Data
    public static class DeviceStatusCount {
        private long total;
        private long available;
        private long inUse;
        private long inApproval;
        private long maintenance;
        private long lost;
        private long scrapped;
    }
}
