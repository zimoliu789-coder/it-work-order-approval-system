/**
 * 使用记录类型（需求方三波·第一波·）
 *
 * 与后端 `module.usage.dto.vo.UsageRecordVO` / `dto.UsageQuery` 对应。
 * 一行 = 一笔借用工单，覆盖「谁 / 借什么 / 何时→何时」三类信息。
 */

/** 使用记录查询范围 */
export type UsageScope = 'DEVICE' | 'USER'

/** 使用记录查询条件 */
export interface UsageQuery {
  page?: number
  size?: number
  /** 查询维度：DEVICE 按设备 / USER 按员工；为空=全部 */
  scope?: UsageScope | null
  /** 目标 ID：scope=DEVICE 时为设备 ID，scope=USER 时为员工 ID */
  targetId?: number | null
  /** 关键词（工单号 / 设备名 / 资产编号 / 借用人） */
  keyword?: string | null
  /** 工单状态编码 */
  status?: string | null
  /** 借用类型 SHORT_TERM / LONG_TERM */
  useType?: string | null
  /** 提交时间区间（含） */
  startTime?: string | null
  endTime?: string | null
}

/** 使用记录条目 */
export interface UsageRecordItem {
  orderId: number
  orderNo: string
  orderType?: string
  orderTypeLabel?: string
  deviceId: number
  deviceName: string
  assetNo: string
  primaryCategoryName?: string | null
  brand?: string | null
  model?: string | null
  applicantId: number
  applicantName: string
  departmentName?: string | null
  handlerName?: string | null
  useType?: string | null
  useTypeLabel?: string | null
  status: string
  statusLabel?: string
  createdAt?: string | null
  deliveredAt?: string | null
  plannedEndTime?: string | null
  actualEndTime?: string | null
  autoExtendCount?: number | null
  borrowTimeout?: boolean | null
  returnTrigger?: string | null
  returnCondition?: string | null
}

/** 借用类型选项（借用二分：短期 / 长期） */
export const USAGE_TYPE_OPTIONS: ReadonlyArray<{ value: string; label: string }> = [
  { value: 'SHORT_TERM', label: '短期借用' },
  { value: 'LONG_TERM', label: '长期领用' }
]

/** 工单状态选项（与后端 OrderStatus 对齐，仅列出借用链路可能出现的状态） */
export const USAGE_STATUS_OPTIONS: ReadonlyArray<{ value: string; label: string }> = [
  { value: 'PENDING_APPROVAL', label: '审批中' },
  { value: 'PENDING_DELIVERY', label: '待交付' },
  { value: 'BORROWED', label: '使用中' },
  { value: 'PENDING_RETURN', label: '待收回' },
  { value: 'RETURNED', label: '已归还' },
  { value: 'REJECTED', label: '已驳回' },
  { value: 'CANCELLED', label: '已撤回' },
  { value: 'TERMINATED', label: '已终止' }
]

/** 使用类型中文名（后端已给 label 时优先用后端的，此处兜底） */
export function usageTypeLabel(code?: string | null): string {
  if (!code) {
    return '-'
  }
  return USAGE_TYPE_OPTIONS.find((item) => item.value === code)?.label ?? code
}
