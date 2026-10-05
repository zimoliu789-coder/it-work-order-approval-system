/**
 * 设备盘点类型（P2）
 *
 * 与后端 `module/inventory` 的常量 / VO 一一对应。
 * 状态与核对结果的**中文名与标签色集中在这里**：两边各写一份必然漂移，
 * 而「同一个状态在列表是绿色、在报告是灰色」这种不一致最伤信任。
 */

// ---------------------------------------------------------------------------
// 状态
// ---------------------------------------------------------------------------

export type InventoryStatusCode = 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED'

export const INVENTORY_STATUS_OPTIONS: ReadonlyArray<{
  value: InventoryStatusCode
  label: string
  tag: 'primary' | 'success' | 'info'
}> = [
  { value: 'IN_PROGRESS', label: '进行中', tag: 'primary' },
  { value: 'COMPLETED', label: '已完成', tag: 'success' },
  { value: 'CANCELLED', label: '已取消', tag: 'info' }
]

export function inventoryStatusLabel(code?: string | null): string {
  if (!code) {
    return ''
  }
  return INVENTORY_STATUS_OPTIONS.find((item) => item.value === code)?.label ?? code
}

export function inventoryStatusTagType(code?: string | null): 'primary' | 'success' | 'info' {
  return INVENTORY_STATUS_OPTIONS.find((item) => item.value === code)?.tag ?? 'info'
}

/** 只有「进行中」的任务可以核对 / 完成 / 取消（与后端 InventoryStatus 判定一致） */
export function isInventoryInProgress(code?: string | null): boolean {
  return code === 'IN_PROGRESS'
}

// ---------------------------------------------------------------------------
// 范围
// ---------------------------------------------------------------------------

export type InventoryScopeCode = 'ALL' | 'CATEGORY' | 'LOCATION'

export const INVENTORY_SCOPE_OPTIONS: ReadonlyArray<{ value: InventoryScopeCode; label: string }> = [
  { value: 'ALL', label: '全部设备' },
  { value: 'CATEGORY', label: '按设备分类' },
  { value: 'LOCATION', label: '按存放位置' }
]

export interface InventoryScopeOption {
  value: string
  label: string
}

export interface InventoryScopeOptions {
  categories: InventoryScopeOption[]
  locations: InventoryScopeOption[]
}

// ---------------------------------------------------------------------------
// 核对结果
// ---------------------------------------------------------------------------

export type InventoryCheckCode = 'IN_PLACE' | 'MISSING' | 'WRONG_LOCATION'

export const INVENTORY_CHECK_OPTIONS: ReadonlyArray<{
  value: InventoryCheckCode
  label: string
  tag: 'success' | 'danger' | 'warning'
}> = [
  { value: 'IN_PLACE', label: '在库', tag: 'success' },
  { value: 'MISSING', label: '缺失', tag: 'danger' },
  { value: 'WRONG_LOCATION', label: '位置不符', tag: 'warning' }
]

/** 中文名；`null`/空表示「尚未核对」，**不编造「待核对」之类的假值** */
export function inventoryCheckLabel(code?: string | null): string {
  if (!code) {
    return ''
  }
  return INVENTORY_CHECK_OPTIONS.find((item) => item.value === code)?.label ?? code
}

export function inventoryCheckTagType(code?: string | null): 'success' | 'danger' | 'warning' | 'info' {
  return INVENTORY_CHECK_OPTIONS.find((item) => item.value === code)?.tag ?? 'info'
}

/** 「尚未核对」筛选取值（与后端约定的特殊值，不是落库状态） */
export const INVENTORY_FILTER_UNCHECKED = 'UNCHECKED'

// ---------------------------------------------------------------------------
// 数据
// ---------------------------------------------------------------------------

export interface InventoryTaskItem {
  id: number
  taskNo: string
  taskName: string
  scopeType: string
  scopeTypeLabel: string
  scopeValue?: string | null
  scopeLabel: string
  status: string
  statusLabel: string
  totalCount: number
  checkedCount: number
  inPlaceCount: number
  missingCount: number
  wrongLocationCount: number
  progressPercent: number
  createdBy?: number | null
  createdByName?: string | null
  createdAt?: string | null
  startedAt?: string | null
  completedAt?: string | null
  remark?: string | null
}

export interface InventoryItemRow {
  id: number
  deviceId: number
  assetNo: string
  deviceName: string
  storageLocation?: string | null
  expectedStatus: string
  expectedStatusLabel: string
  checkResult?: string | null
  checkResultLabel?: string | null
  checkedBy?: number | null
  checkedByName?: string | null
  checkedAt?: string | null
  remark?: string | null
}

export interface InventoryReport {
  taskId: number
  taskNo: string
  taskName: string
  scopeLabel: string
  status: string
  statusLabel: string
  totalCount: number
  checkedCount: number
  uncheckedCount: number
  inPlaceCount: number
  missingCount: number
  wrongLocationCount: number
  progressPercent: number
  remark?: string | null
  createdAt?: string | null
  completedAt?: string | null
  missingItems: InventoryItemRow[]
  wrongLocationItems: InventoryItemRow[]
}

export interface InventoryTaskCreatePayload {
  taskName: string
  scopeType: InventoryScopeCode
  scopeValue?: string | null
  remark?: string | null
}

export interface InventoryCheckPayload {
  assetNo: string
  checkResult: InventoryCheckCode
  remark?: string | null
}
