/**
 * 统计仪表盘类型与展示口径（ · M5，后端 module/dashboard）
 *
 * 与后端 `DashboardSummaryVO` 一一对应。展示口径一律放**纯函数**，由单测直接覆盖 ——
 * 与 `types/flowMonitor.ts` / `types/report.ts` 同一取向：页面只负责摆位置。
 *
 * 关键约定：`avgApprovalHours` / `overdueRate` 在**没有样本**时后端不返回该字段
 * （序列化开了 non_null），前端据此显示「—」。这两个 null 与「0 小时」「0%」是相反结论，
 * 见 `formatDashboardHours` / `formatDashboardRate` 的注释。
 */

/** 按天 */
export const DASHBOARD_GRANULARITY_DAY = 'DAY' as const

/** 按月 */
export const DASHBOARD_GRANULARITY_MONTH = 'MONTH' as const

/** 时间粒度 */
export type DashboardGranularity = typeof DASHBOARD_GRANULARITY_DAY | typeof DASHBOARD_GRANULARITY_MONTH

/** 粒度选项（界面下拉） */
export const GRANULARITY_OPTIONS: ReadonlyArray<{ value: DashboardGranularity; label: string }> = [
  { value: DASHBOARD_GRANULARITY_DAY, label: '按天' },
  { value: DASHBOARD_GRANULARITY_MONTH, label: '按月' }
]

/**
 * 建议切换为「按月」的区间长度阈值（天，含首尾）。
 *
 * 超过这个长度仍按天聚合时，时间分布会变成几十上百根细条、可读性显著下降，
 * 因此界面给一句**提示**（而不是替用户改粒度 —— 用户选了按天就是要按天）。
 */
export const MONTH_SUGGESTION_DAYS = 92

/** 空值占位符：统一「—」，与流程监控保持同一个符号 */
export const DASHBOARD_EMPTY_PLACEHOLDER = '—'

/** 通用分布桶（类型 / 状态） */
export interface DashboardBucket {
  /** 原始编码，如 `BORROW` / `PENDING_APPROVAL` */
  code: string
  /** 中文名（后端按枚举补全） */
  label: string
  count: number
}

/** 时间分布桶 */
export interface DashboardTimeBucket {
  /** 桶标签：按天 `yyyy-MM-dd`，按月 `yyyy-MM` */
  bucket: string
  count: number
}

/** 部门分布桶 */
export interface DashboardGroupBucket {
  /** 部门 id；0 = 未分组 */
  groupId: number
  groupName: string
  count: number
}

/** 仪表盘聚合摘要 */
export interface DashboardSummary {
  /** 实际生效的区间下界（含），`yyyy-MM-dd` */
  from: string
  /** 实际生效的区间上界（含） */
  to: string
  granularity: DashboardGranularity
  totalOrders: number
  byType: DashboardBucket[]
  byStatus: DashboardBucket[]
  byTime: DashboardTimeBucket[]
  byGroup: DashboardGroupBucket[]
  /** 平均审批时长（小时）；**无样本时后端不返回该字段** */
  avgApprovalHours?: number | null
  /** 平均值的样本数（审批已完成的工单数） */
  approvedOrderCount: number
  /** 超时率（%）；**没有设时限的轮次时后端不返回该字段** */
  overdueRate?: number | null
  /** 超时轮次数（分子） */
  overdueNodeCount: number
  /** 有时限的轮次数（分母） */
  withDeadlineNodeCount: number
}

/** 查询参数 */
export interface DashboardQuery {
  from?: string
  to?: string
  granularity?: DashboardGranularity
}

// ------------------------------------------------------------------
// 格式化
// ------------------------------------------------------------------

/**
 * 时长格式化（小时）。
 *
 * 名字与 `types/flowMonitor.ts` 的 `formatMonitorHours` 不同，是刻意的：
 * 那个的 null 是「该流程没有可用测量样本」，这里的 null 是「整个区间都没有审批完成的单」，
 * 虽然结果都显示「—」，但两处数据来源与解释不同，同名会让人误以为可以合并改动。
 */
export function formatDashboardHours(hours: number | null | undefined): string {
  if (hours == null || !Number.isFinite(hours)) {
    return DASHBOARD_EMPTY_PLACEHOLDER
  }
  return `${hours} 小时`
}

/** 百分比格式化；null / 非有限数一律占位符，绝不显示 NaN */
export function formatDashboardRate(rate: number | null | undefined): string {
  if (rate == null || !Number.isFinite(rate)) {
    return DASHBOARD_EMPTY_PLACEHOLDER
  }
  return `${rate}%`
}

/** 平均审批时长的补充说明：让分母可见（样本 1 与样本 50 的可信度完全不同） */
export function approvalSampleHint(summary: DashboardSummary): string {
  if (summary.approvedOrderCount <= 0) {
    return '区间内没有审批完成的工单，无法计算平均值'
  }
  return `${summary.totalOrders} 单中 ${summary.approvedOrderCount} 单已完成审批`
}

/** 超时率的补充说明：显式暴露「分子 / 分母」 */
export function overdueSampleHint(summary: DashboardSummary): string {
  if (summary.withDeadlineNodeCount <= 0) {
    return '区间内没有配置时限的审批轮次，无法判定超时率'
  }
  return `${summary.withDeadlineNodeCount} 个有时限的审批轮次中，${summary.overdueNodeCount} 个超时`
}

// ------------------------------------------------------------------
// 分布条（无图表库：指标卡 + CSS 条形，沿用项目取向）
// ------------------------------------------------------------------

/** 一组分布桶里的最大计数；空集合返回 0 */
export function maxBucketCount(rows: ReadonlyArray<{ count: number }>): number {
  let max = 0
  for (const row of rows) {
    if (Number.isFinite(row.count) && row.count > max) {
      max = row.count
    }
  }
  return max
}

/**
 * 条形宽度百分比（0–100）。
 *
 * 以**该组内的最大计数**为 100%，而不是以总量为 100%：
 * 四个维度的量纲不同（类型 3 桶 vs 时间 30 桶），用总量归一会让天数条全部短得看不出来。
 * 最大值为 0（没有数据）时返回 0，避免除零产生 NaN 宽度；
 * 结果截到 100 是防御性的 —— 正常情况下 `count ≤ max` 恒成立，但一旦上游换了数据源
 * （例如以后改成「两个序列叠加」），没有截断就会画出溢出色条。
 */
export function barWidthPercent(count: number, max: number): number {
  if (!Number.isFinite(count) || count <= 0 || !Number.isFinite(max) || max <= 0) {
    return 0
  }
  return Math.min(100, Math.round((count / max) * 100))
}

// ------------------------------------------------------------------
// 区间与粒度提示
// ------------------------------------------------------------------

/** 两个 `yyyy-MM-dd` 之间的天数（含首尾）；非法输入返回 0 */
export function inclusiveDays(from: string, to: string): number {
  const start = Date.parse(`${from}T00:00:00Z`)
  const end = Date.parse(`${to}T00:00:00Z`)
  if (Number.isNaN(start) || Number.isNaN(end) || end < start) {
    return 0
  }
  return Math.round((end - start) / 86400000) + 1
}

/**
 * 「是否建议切换为按月」的提示文案；不需要建议时返回 null。
 *
 * 只提示、不修改用户选择：粒度是用户显式选的，后端也不会替他改写
 * （见后端 `DashboardQuery` 的同名说明）。返回 null 而不是空串，
 * 让模板的 `v-if` 用最直白的判断。
 */
export function monthSuggestion(
  granularity: DashboardGranularity,
  from: string,
  to: string
): string | null {
  if (granularity !== DASHBOARD_GRANULARITY_DAY) {
    return null
  }
  const days = inclusiveDays(from, to)
  if (days <= MONTH_SUGGESTION_DAYS) {
    return null
  }
  return `区间共 ${days} 天，时间分布按天会比较密，建议切换为按月查看`
}
