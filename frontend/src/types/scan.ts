import type { DeviceOption } from '@/types/order'

/**
 * 扫码借还类型（P1 扫码借还）
 *
 * 「扫到什么」与「能做什么」全部由服务端判定后下发（`ScanLookupResult`），
 * 前端只按 `action` 做分支跳转 —— 不做本地状态推断，避免列表数据过期导致的误判。
 */

// ---------------------------------------------------------------------------
// 命中类型
// ---------------------------------------------------------------------------

export const SCAN_MATCH_ASSET_NO = 'ASSET_NO' as const
export const SCAN_MATCH_ORDER_NO = 'ORDER_NO' as const
export const SCAN_MATCH_NONE = 'NONE' as const

export type ScanMatchType =
  | typeof SCAN_MATCH_ASSET_NO
  | typeof SCAN_MATCH_ORDER_NO
  | typeof SCAN_MATCH_NONE

// ---------------------------------------------------------------------------
// 建议动作
// ---------------------------------------------------------------------------

export const SCAN_ACTION_BORROW = 'BORROW' as const
export const SCAN_ACTION_RETURN = 'RETURN' as const
export const SCAN_ACTION_VIEW = 'VIEW' as const
export const SCAN_ACTION_UNAVAILABLE = 'UNAVAILABLE' as const
export const SCAN_ACTION_NONE = 'NONE' as const

export type ScanAction =
  | typeof SCAN_ACTION_BORROW
  | typeof SCAN_ACTION_RETURN
  | typeof SCAN_ACTION_VIEW
  | typeof SCAN_ACTION_UNAVAILABLE
  | typeof SCAN_ACTION_NONE

// ---------------------------------------------------------------------------
// 结果结构
// ---------------------------------------------------------------------------

/** 工单摘要（扫码场景） */
export interface ScanOrderBrief {
  id: number
  orderNo: string
  status: string
  statusLabel: string
  deviceId?: number | null
  deviceName?: string | null
  assetNo?: string | null
  applicantName?: string | null
  plannedEndTime?: string | null
  canRequestReturn?: boolean
}

/**
 * 扫码查询结果。
 *
 * ⚠️ 后端 Jackson 序列化配置为 `non_null`：值为 null 的字段**根本不会出现**在报文里，
 * 因此这里全部声明为可选，判定「未识别」请用 `matched === false`，不要断言字段等于 null
 * （项目里已为此踩过坑：断言 `"lastSuccessAt":null` 恒为假）。
 */
export interface ScanLookupResult {
  matched: boolean
  /** 原始扫码内容回显 */
  code?: string | null
  matchType: ScanMatchType
  action: ScanAction
  /** 中文说明，直接展示（不让前端二次拼文案） */
  actionLabel: string
  /** 不可操作 / 未识别的原因 */
  reason?: string | null
  /** 命中的设备 */
  device?: DeviceOption | null
  /** 命中的工单（归还时=我的在借工单；查看时=被扫工单） */
  order?: ScanOrderBrief | null
}

/** 该动作是否意味着「识别到了目标」 */
export function isScanResolved(action: ScanAction): boolean {
  return action === SCAN_ACTION_BORROW || action === SCAN_ACTION_RETURN || action === SCAN_ACTION_VIEW
}
