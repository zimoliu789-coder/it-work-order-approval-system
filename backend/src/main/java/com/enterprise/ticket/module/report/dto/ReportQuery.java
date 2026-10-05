package com.enterprise.ticket.module.report.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;

/**
 * 统计报表查询条件（「时间筛选：按年月筛选」）
 *
 * <p>只暴露「年 / 月」两个入参，服务端把它换算成左闭右开的 {@code [from, to)} 时间区间再下推给 SQL：
 * <ul>
 *   <li>只给 {@code year} → 整年；</li>
 *   <li>给 {@code year + month} → 该月；</li>
 *   <li>都不给 → 不限时间（区间取最大值，保证 SQL 条件形态唯一，无需动态 SQL）。</li>
 * </ul>
 * 用左闭右开而不是 {@code BETWEEN}：{@code BETWEEN} 对 DATETIME 是闭区间，
 * 月底最后一秒之后、次日零点之前的数据会被漏掉（例如 {@code 2026-09-30 23:59:59.500}）。
 */
@Data
public class ReportQuery {

    /** 年份，例如 2026；为空表示不限年份 */
    private Integer year;

    /** 月份 1-12；为空表示整年（必须与 year 同时给出才有意义） */
    private Integer month;

    /** 区间下界（含） */
    public LocalDateTime from() {
        if (year == null) {
            return LocalDateTime.of(1970, 1, 1, 0, 0);
        }
        return month == null
                ? LocalDateTime.of(year, 1, 1, 0, 0)
                : LocalDateTime.of(year, month, 1, 0, 0);
    }

    /** 区间上界（不含） */
    public LocalDateTime to() {
        if (year == null) {
            return LocalDateTime.of(2999, 12, 31, 23, 59, 59);
        }
        LocalDate start = month == null
                ? LocalDate.of(year, 1, 1)
                : LocalDate.of(year, month, 1);
        return month == null
                ? LocalDateTime.of(year + 1, 1, 1, 0, 0)
                : YearMonth.from(start).plusMonths(1).atDay(1).atStartOfDay();
    }
}
