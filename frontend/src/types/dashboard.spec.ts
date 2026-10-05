import { describe, expect, it } from 'vitest'
import {
  DASHBOARD_EMPTY_PLACEHOLDER,
  DASHBOARD_GRANULARITY_DAY,
  DASHBOARD_GRANULARITY_MONTH,
  MONTH_SUGGESTION_DAYS,
  approvalSampleHint,
  barWidthPercent,
  formatDashboardHours,
  formatDashboardRate,
  inclusiveDays,
  maxBucketCount,
  monthSuggestion,
  overdueSampleHint
} from '@/types/dashboard'
import type { DashboardSummary } from '@/types/dashboard'

/**
 * 统计仪表盘展示口径单测（ · M5）
 *
 * 这里锁住的是「拿到数据之后怎么显示」，重点是三类容易出错的地方：
 * ① **null ≠ 0**（没有样本时必须显示「—」，不能显示 0 小时 / 0%）；
 * ② **条宽归一**（组内归一，且没有数据时不产生 NaN 宽度）；
 * ③ **粒度提示只在需要时出现**（按天 + 超 92 天才提示，按月的用户不该被打扰）。
 */

function summary(patch: Partial<DashboardSummary> = {}): DashboardSummary {
  return {
    from: '2026-09-01',
    to: '2026-09-30',
    granularity: DASHBOARD_GRANULARITY_DAY,
    totalOrders: 0,
    byType: [],
    byStatus: [],
    byTime: [],
    byGroup: [],
    approvedOrderCount: 0,
    overdueNodeCount: 0,
    withDeadlineNodeCount: 0,
    ...patch
  }
}

describe('formatDashboardHours', () => {
  it('正常值带单位', () => {
    expect(formatDashboardHours(6.35)).toBe('6.35 小时')
    expect(formatDashboardHours(0)).toBe('0 小时')
  })

  it('无样本（null / undefined / 非有限数）一律占位符，绝不显示 NaN', () => {
    expect(formatDashboardHours(null)).toBe(DASHBOARD_EMPTY_PLACEHOLDER)
    expect(formatDashboardHours(undefined)).toBe(DASHBOARD_EMPTY_PLACEHOLDER)
    expect(formatDashboardHours(Number.NaN)).toBe(DASHBOARD_EMPTY_PLACEHOLDER)
    expect(formatDashboardHours(Number.POSITIVE_INFINITY)).toBe(DASHBOARD_EMPTY_PLACEHOLDER)
  })
})

describe('formatDashboardRate', () => {
  it('正常值带百分号', () => {
    expect(formatDashboardRate(13.33)).toBe('13.33%')
    expect(formatDashboardRate(0)).toBe('0%')
  })

  it('无法判定时占位符 —— 与「0%」是相反结论，不能混淆', () => {
    expect(formatDashboardRate(null)).toBe(DASHBOARD_EMPTY_PLACEHOLDER)
    expect(formatDashboardRate(undefined)).toBe(DASHBOARD_EMPTY_PLACEHOLDER)
    expect(formatDashboardRate(Number.NaN)).toBe(DASHBOARD_EMPTY_PLACEHOLDER)
  })
})

describe('样本说明（让每个「—」都有据可查）', () => {
  it('平均值：无样本时说明原因，有样本时给出分母', () => {
    expect(approvalSampleHint(summary())).toContain('无法计算')
    expect(approvalSampleHint(summary({ totalOrders: 42, approvedOrderCount: 18 }))).toBe(
      '42 单中 18 单已完成审批'
    )
  })

  it('超时率：无时限轮次时说明原因，有时给出分子 / 分母', () => {
    expect(overdueSampleHint(summary())).toContain('无法判定')
    expect(overdueSampleHint(summary({ withDeadlineNodeCount: 15, overdueNodeCount: 2 }))).toBe(
      '15 个有时限的审批轮次中，2 个超时'
    )
  })
})

describe('maxBucketCount', () => {
  it('空集合返回 0', () => {
    expect(maxBucketCount([])).toBe(0)
  })

  it('取最大值，忽略非有限数', () => {
    expect(maxBucketCount([{ count: 3 }, { count: 12 }, { count: 7 }])).toBe(12)
    expect(maxBucketCount([{ count: Number.NaN }, { count: 5 }])).toBe(5)
    expect(maxBucketCount([{ count: Number.POSITIVE_INFINITY }])).toBe(0)
  })
})

describe('barWidthPercent', () => {
  it('组内归一，四舍五入', () => {
    expect(barWidthPercent(10, 10)).toBe(100)
    expect(barWidthPercent(5, 10)).toBe(50)
    expect(barWidthPercent(1, 3)).toBe(33)
  })

  it('计数为 0 / 最大值为 0（无数据）时返回 0，不产生 NaN 宽度', () => {
    expect(barWidthPercent(0, 10)).toBe(0)
    expect(barWidthPercent(3, 0)).toBe(0)
    expect(barWidthPercent(Number.NaN, 10)).toBe(0)
  })

  it('异常放大被截到 100%，避免画出溢出色条', () => {
    expect(barWidthPercent(20, 10)).toBe(100)
  })
})

describe('inclusiveDays', () => {
  it('含首尾计数', () => {
    expect(inclusiveDays('2026-09-30', '2026-09-30')).toBe(1)
    expect(inclusiveDays('2026-09-01', '2026-09-30')).toBe(30)
  })

  it('非法或倒置返回 0，不抛异常', () => {
    expect(inclusiveDays('not-a-date', '2026-09-30')).toBe(0)
    expect(inclusiveDays('2026-09-30', '2026-09-01')).toBe(0)
  })
})

describe('monthSuggestion', () => {
  it('按天 + 超过 92 天才提示，并带上实际天数', () => {
    // 2026-07-01 → 2026-10-01 = 93 天
    const hint = monthSuggestion(DASHBOARD_GRANULARITY_DAY, '2026-07-01', '2026-10-01')
    expect(hint).toContain('93 天')
    expect(hint).toContain('按月')
  })

  it('刚好 92 天不提示（阈值是「超过」）', () => {
    expect(inclusiveDays('2026-07-01', '2026-09-30')).toBe(MONTH_SUGGESTION_DAYS)
    expect(monthSuggestion(DASHBOARD_GRANULARITY_DAY, '2026-07-01', '2026-09-30')).toBeNull()
  })

  it('已按月则不提示 —— 不该打扰已经选对的用户', () => {
    expect(monthSuggestion(DASHBOARD_GRANULARITY_MONTH, '2026-01-01', '2026-12-31')).toBeNull()
  })

  it('区间非法（inclusiveDays = 0）时不提示', () => {
    expect(monthSuggestion(DASHBOARD_GRANULARITY_DAY, 'bad', 'worse')).toBeNull()
  })
})
