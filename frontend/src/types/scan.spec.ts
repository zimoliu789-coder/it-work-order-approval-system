import { describe, expect, it } from 'vitest'
import {
  SCAN_ACTION_BORROW,
  SCAN_ACTION_NONE,
  SCAN_ACTION_RETURN,
  SCAN_ACTION_UNAVAILABLE,
  SCAN_ACTION_VIEW,
  SCAN_MATCH_ASSET_NO,
  SCAN_MATCH_NONE,
  SCAN_MATCH_ORDER_NO,
  isScanResolved
} from '@/types/scan'

/**
 * 扫码类型（P1 扫码借还）
 *
 * 这里的断言看起来「只是在比对字符串」，但它们的价值在于：这些值是**前后端契约**，
 * 后端 `ScanAction` / `ScanMatchType` 常量与之逐字对应。任一侧改名而不改另一侧，
 * 症状是「扫码之后页面什么都不做」—— 没有报错、没有提示，极难排查。
 * 用字面量把契约钉住，改名就必须同时改两处。
 */
describe('扫码动作常量（与后端 ScanAction 对齐）', () => {
  it('动作取值与后端一致', () => {
    expect(SCAN_ACTION_BORROW).toBe('BORROW')
    expect(SCAN_ACTION_RETURN).toBe('RETURN')
    expect(SCAN_ACTION_VIEW).toBe('VIEW')
    expect(SCAN_ACTION_UNAVAILABLE).toBe('UNAVAILABLE')
    expect(SCAN_ACTION_NONE).toBe('NONE')
  })

  it('命中类型取值与后端一致', () => {
    expect(SCAN_MATCH_ASSET_NO).toBe('ASSET_NO')
    expect(SCAN_MATCH_ORDER_NO).toBe('ORDER_NO')
    expect(SCAN_MATCH_NONE).toBe('NONE')
  })
})

describe('isScanResolved', () => {
  it('借 / 还 / 查看三种动作都算「识别到了」', () => {
    expect(isScanResolved(SCAN_ACTION_BORROW)).toBe(true)
    expect(isScanResolved(SCAN_ACTION_RETURN)).toBe(true)
    expect(isScanResolved(SCAN_ACTION_VIEW)).toBe(true)
  })

  it('不可操作与未识别都不算', () => {
    expect(isScanResolved(SCAN_ACTION_UNAVAILABLE)).toBe(false)
    expect(isScanResolved(SCAN_ACTION_NONE)).toBe(false)
  })
})
