/**
 * 统计报表视图类型（，与后端 report 模块的 VO 一一对应）
 */

/** 设备借用频次统计 */
export interface DeviceUsageReport {
  totalOrders: number
  deviceCount: number
  byDevice: DeviceUsageItem[]
  byCategory: CategoryUsageItem[]
}

export interface DeviceUsageItem {
  deviceId: number
  deviceName: string
  assetNo: string
  primaryCategoryName: string
  borrowCount: number
}

export interface CategoryUsageItem {
  categoryName: string
  borrowCount: number
  deviceCount: number
}

/** 工单审批时效统计 */
export interface ApprovalEfficiencyReport {
  submittedOrders: number
  approvedOrders: number
  avgApprovalHours: number
  overtimeOrders: number
  pendingNodes: number
  pendingOverdueNodes: number
  timeoutThresholdHours: number
  items: ApprovalEfficiencyItem[]
}

export interface ApprovalEfficiencyItem {
  orderId: number
  orderNo: string
  applicantName: string
  deviceName: string
  submittedAt?: string
  approvedAt?: string
  /** 审批耗时（小时）；未完成审批时为 null */
  hours?: number | null
  overtime: boolean
}

/** 设备故障统计 */
export interface DeviceFaultReport {
  totalFaults: number
  pendingRepair: number
  repaired: number
  scrapped: number
  byDevice: FaultDeviceItem[]
  byMonth: FaultMonthItem[]
}

export interface FaultDeviceItem {
  deviceId: number
  deviceName: string
  assetNo: string
  primaryCategoryName: string
  faultCount: number
  pendingCount: number
}

export interface FaultMonthItem {
  month: string
  faultCount: number
}

/**
 * 管理概览（P3 管理层数据看板）
 *
 * 与三张明细报表的区别：它们是「清单」，本类型是「概览」—— 用最少的数字回答「现在什么情况」。
 *
 * ⚠️ **两类字段的时间语义不同**（页面必须据此标注「当前」）：
 * · 随区间变化的：{@code monthlyBorrowCount} / {@code departmentRanking} / {@code trend}
 * · 当前状态的：逾期两个口径 / 利用率 —— 它们**不受筛选影响**
 */
export interface DashboardOverview {
  /** 区间下界（yyyy-MM-dd） */
  from: string
  /** 区间上界（不含，yyyy-MM-dd） */
  to: string
  /** 区间文案：2026-10 / 2026 */
  rangeLabel: string

  /** 区间内借用申请数（未选月份时区间为当前月 ⇒ 即「本月借出」） */
  monthlyBorrowCount: number
  /** 区间内被借用过的设备数（去重） */
  monthlyBorrowDeviceCount: number

  /** 逾期主口径：系统超时标记（borrow_timeout）且仍在借 */
  overdueTimeoutCount: number
  /** 逾期副口径：仍在借且计划归还已过（含顺延宽限期内），恒 ≥ 主口径 */
  overdueGraceCount: number

  /** 设备总数（未逻辑删除） */
  deviceTotal: number
  deviceAvailableCount: number
  deviceInUseCount: number
  deviceInApprovalCount: number
  deviceMaintenanceCount: number
  deviceLostCount: number
  deviceScrappedCount: number

  /** 利用率分子：在用 + 审批中（审批中的设备已被占用） */
  utilizationNumerator: number
  /** 利用率分母：总数 − 报废（报废不可调度） */
  utilizationDenominator: number
  /** 利用率百分比（后端已保留 1 位小数） */
  utilizationRate: number

  /** 部门借用排行（按申请人所属部门，降序 Top 10；无部门归「未分配」） */
  departmentRanking: OverviewDepartmentItem[]
  /** 借用趋势（区间落到月时按天、只给年时按月） */
  trend: OverviewTrendItem[]
}

export interface OverviewDepartmentItem {
  /** 未分配部门时为 null */
  departmentId?: number | null
  departmentName: string
  borrowCount: number
}

export interface OverviewTrendItem {
  /** 桶标签：按月为 2026-10，按天为 2026-10-04 */
  bucket: string
  count: number
}

/** 报表时间筛选（按年月，） */
export interface ReportQuery {
  year?: number
  month?: number
}

/** 报表类型（决定导出时使用的 ExportType） */
export type ReportKind = 'device-usage' | 'approval-efficiency' | 'device-fault'

/** 报表 → 导出类型映射（导出走统一导出入口；收窄为字面量类型，确保可直接作为 ExportTypeCode 使用） */
export const REPORT_EXPORT_TYPE: Record<
  ReportKind,
  'REPORT_DEVICE_USAGE' | 'REPORT_APPROVAL_EFFICIENCY' | 'REPORT_DEVICE_FAULT'
> = {
  'device-usage': 'REPORT_DEVICE_USAGE',
  'approval-efficiency': 'REPORT_APPROVAL_EFFICIENCY',
  'device-fault': 'REPORT_DEVICE_FAULT'
}

/** 报表标签页定义（页面与导出共用同一份文案） */
export const REPORT_TABS: ReadonlyArray<{ name: ReportKind; label: string }> = [
  { name: 'device-usage', label: '设备借用频次' },
  { name: 'approval-efficiency', label: '工单审批时效' },
  { name: 'device-fault', label: '设备故障统计' }
]

/** 月份下拉项（1-12 月） */
export function monthOptions(): Array<{ value: number; label: string }> {
  return Array.from({ length: 12 }, (_, index) => ({
    value: index + 1,
    label: `${index + 1} 月`
  }))
}

/** 审批耗时展示：未完成审批显示「审批中」而不是 0（0 会被误读成瞬间通过） */
export function formatHours(hours?: number | null): string {
  if (hours == null) {
    return '审批中'
  }
  return `${hours} 小时`
}
