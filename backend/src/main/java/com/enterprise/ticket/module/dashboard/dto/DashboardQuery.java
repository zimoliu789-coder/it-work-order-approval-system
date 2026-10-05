package com.enterprise.ticket.module.dashboard.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 统计仪表盘查询条件（ · M5）
 *
 * <h2>入参只有区间 + 粒度，且一律由服务端换算成左闭右开区间</h2>
 * <p>前端只传「用户看到的日期」（{@code yyyy-MM-dd}），不传时刻。服务端把
 * {@code [from, to]} 换算成 SQL 用的 {@code [from 00:00, (to+1) 00:00)}：
 * 这样「选到 9 月 30 日」就真的包含 9 月 30 日全天，不会像闭区间那样
 * 漏掉 {@code 23:59:59.500} 这种边界值。
 *
 * <p>换算留在服务端而不是让前端传时刻，是为了让「页面显示的区间」与
 * 「SQL 实际过滤的区间」只有一个来源（{@link #effectiveFrom()} / {@link #effectiveTo()}），
 * 并把生效区间原样回显给前端（见 {@code DashboardSummaryVO.from/to}）——
 * 避免出现「我传了 30 天，页面却显示别的区间」这种无法自证的情况。
 *
 * <h2>默认区间 = 近 30 天</h2>
 * <p>{@code from}/{@code to} 都为空时取「含今天在内的 30 天」（今天往前数 29 天到今天）。
 * 只给其一：给 {@code to} 则往前推 30 天；给 {@code from} 则从该日到今天。
 * 这与「打开工作台先看到最近一个月」的直觉一致，也避免默认全量扫描。
 */
@Data
public class DashboardQuery {

    /** 时间粒度：按天 */
    public static final String GRANULARITY_DAY = "DAY";

    /** 时间粒度：按月 */
    public static final String GRANULARITY_MONTH = "MONTH";

    /** 默认区间长度（天，含首尾） */
    public static final int DEFAULT_WINDOW_DAYS = 30;

    /** 区间下界（含），可为空 */
    private LocalDate from;

    /** 区间上界（含），可为空 */
    private LocalDate to;

    /**
     * 时间粒度，{@link #GRANULARITY_DAY} 或 {@link #GRANULARITY_MONTH}；非法值在服务层被拒。
     *
     * <p>刻意不做「超过 92 天自动切月」这类**静默改写**：用户选了「按天」就是要按天看，
     * 后端偷偷换成按月会让人对着一堆月份标签困惑。是否建议切粒度是**界面提示**的职责
     * （见前端工作台的提示文案），不是后端该替用户做的决定。
     */
    private String granularity;

    /** 生效区间下界（含） */
    public LocalDate effectiveFrom() {
        LocalDate end = effectiveTo();
        return from != null ? from : end.minusDays(DEFAULT_WINDOW_DAYS - 1L);
    }

    /** 生效区间上界（含） */
    public LocalDate effectiveTo() {
        return to != null ? to : LocalDate.now();
    }

    /** SQL 下界（左闭）：{@code from} 当天 00:00 */
    public LocalDateTime fromDateTime() {
        return effectiveFrom().atStartOfDay();
    }

    /** SQL 上界（右开）：{@code to} 次日 00:00，使 {@code to} 当天全天被包含 */
    public LocalDateTime toDateTime() {
        return effectiveTo().plusDays(1).atStartOfDay();
    }

    /** 是否按月聚合（未指定或非法值一律按天 —— 校验在服务层，这里只做取值） */
    public boolean isMonthly() {
        return GRANULARITY_MONTH.equalsIgnoreCase(granularity);
    }

    /**
     * 传给 SQL 的 {@code DATE_FORMAT} 模式串。
     *
     * <p>把模式串作为<b>普通参数</b>传下去，而不是在 SQL 里写 {@code choose/when} 动态分支：
     * 这样 Mapper 只有一条静态 SQL（注解写法更可读，也不会因为拼 SQL 而漏掉参数绑定），
     * 两个粒度各自的分组/排序语义完全一致。
     */
    public String datePattern() {
        return isMonthly() ? "%Y-%m" : "%Y-%m-%d";
    }

    /** 是否区间倒置（{@code from} 晚于 {@code to}）——由服务层拒绝，不做静默纠正 */
    public boolean isReversed() {
        return from != null && to != null && from.isAfter(to);
    }
}
