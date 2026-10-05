import { describe, expect, it } from 'vitest'
import {
  REASON_MAX_LENGTH,
  formatCountdown,
  isExpectedReturnBeforeToday,
  isLockExpired,
  isOrderFormValid,
  parseDateTime,
  remainingLockSeconds,
  todayString,
  validateOrderForm,
  type OrderFormState
} from './orderForm'

/**
 * 借用申请表单规则单测（ 引入 Vitest）
 *
 * 覆盖重点（都是  那类「前端静默出错」的高风险区）：
 * 1. 三项化后的必填口径 —— 设备与归还日期必填、用途选填；
 * 2. 日期不能早于今天（时区处理）；
 * 3. 临时锁倒计时的解析与边界（'yyyy-MM-dd HH:mm:ss' 在 Safari 下会解析失败）。
 *
 *  删掉的三组用例（借用类型 → 日期必填联动 / 切类型丢弃残留日期）：
 * 那两条规则随「借用类型选择器」一起退役 —— 现在没有可切换的类型，
 * 「长期领用」也不再是前端可达状态。规则本体在后端 `UseType` 与
 * `OrderServiceImpl#create` 里仍在（存量工单与旧调用方依赖它），只是前端不再有分支。
 */

function form(overrides: Partial<OrderFormState> = {}): OrderFormState {
  return {
    deviceId: 1,
    reason: '出差需要',
    expectedReturnDate: '2026-09-20',
    ...overrides
  }
}

const TODAY = '2026-09-17'

describe('todayString', () => {
  it('按本地时区输出 yyyy-MM-dd，不做 UTC 偏移', () => {
    // 2026-09-17 00:30 本地时间：若用 toISOString（UTC）在东八区会变成 09-16
    expect(todayString(new Date(2026, 8, 17, 0, 30, 0))).toBe('2026-09-17')
    expect(todayString(new Date(2026, 8, 17, 23, 59, 59))).toBe('2026-09-17')
  })
})

describe('归还日期必填（ 起一律必填，不再区分借用类型）', () => {
  it('缺归还日期时校验失败', () => {
    const errors = validateOrderForm(form({ expectedReturnDate: null }), TODAY)
    expect(errors).toContain('请选择归还日期')
  })

  it('填了过去的日期时报错', () => {
    const errors = validateOrderForm(form({ expectedReturnDate: '2026-09-16' }), TODAY)
    expect(errors).toContain('归还日期不能早于今天')
  })

  it('填了今天的日期即通过（边界，不应被判为过期）', () => {
    expect(validateOrderForm(form({ expectedReturnDate: TODAY }), TODAY)).toEqual([])
    expect(isOrderFormValid(form({ expectedReturnDate: TODAY }), TODAY)).toBe(true)
  })
})

describe('日期合法性', () => {
  it('早于今天的日期非法，今天与未来合法', () => {
    expect(isExpectedReturnBeforeToday('2026-09-16', TODAY)).toBe(true)
    expect(isExpectedReturnBeforeToday('2026-09-17', TODAY)).toBe(false)
    expect(isExpectedReturnBeforeToday('2026-09-18', TODAY)).toBe(false)
  })
})

describe('必填与长度校验', () => {
  it('设备未选时提示先选设备', () => {
    expect(validateOrderForm(form({ deviceId: null }), TODAY)).toContain('请选择要借用的设备')
  })

  it('用途为纯空白时不算错误（ 起改为选填）', () => {
    expect(validateOrderForm(form({ reason: '   ' }), TODAY)).toHaveLength(0)
  })

  it('用途超过上限时报错', () => {
    const errors = validateOrderForm(form({ reason: 'x'.repeat(REASON_MAX_LENGTH + 1) }), TODAY)
    expect(errors.some((message) => message.includes('用途长度'))).toBe(true)
  })

  it('完整表单通过校验', () => {
    expect(validateOrderForm(form(), TODAY)).toEqual([])
    expect(isOrderFormValid(form(), TODAY)).toBe(true)
  })
})

describe('临时锁倒计时', () => {
  const now = parseDateTime('2026-09-17 21:00:00')

  it('解析后端 yyyy-MM-dd HH:mm:ss 格式（Safari 兼容处理）', () => {
    expect(Number.isNaN(parseDateTime('2026-09-17 21:00:00'))).toBe(false)
    expect(parseDateTime('2026-09-17 21:00:00')).toBe(parseDateTime('2026-09-17T21:00:00'))
  })

  it('非法/缺失的到期时间按已过期处理，不倒计时到负数', () => {
    expect(remainingLockSeconds(null, now)).toBe(0)
    expect(remainingLockSeconds('not-a-date', now)).toBe(0)
    expect(isLockExpired(null, now)).toBe(true)
  })

  it('剩余秒数与倒计时文案正确', () => {
    expect(remainingLockSeconds('2026-09-17 21:05:00', now)).toBe(300)
    expect(formatCountdown(300)).toBe('5:00')
    expect(formatCountdown(299)).toBe('4:59')
    expect(formatCountdown(9)).toBe('0:09')
  })

  it('已过期时剩余为 0 且判定为失效', () => {
    expect(remainingLockSeconds('2026-09-17 20:59:59', now)).toBe(0)
    expect(isLockExpired('2026-09-17 20:59:59', now)).toBe(true)
    expect(isLockExpired('2026-09-17 21:00:01', now)).toBe(false)
  })
})
