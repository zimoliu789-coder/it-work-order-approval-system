import { describe, expect, it } from 'vitest'
import {
  REASON_MAX_LENGTH,
  isExpectedReturnBeforeToday,
  isOrderFormValid,
  todayString,
  validateOrderForm,
  type OrderFormState
} from './orderForm'

/**
 * 借用申请表单规则单测（ 引入 Vitest）
 *
 * 覆盖重点（都是「前端静默出错」的高风险区）：
 * 1. 三项化后的必填口径 —— 设备类别与归还日期必填、用途选填；
 * 2. 日期不能早于今天（时区处理）。
 *
 * <h2>V56（第 9 项）改了什么</h2>
 * <ul>
 *   <li>夹具里的 {@code deviceId} 换成 {@code primaryCategoryId} —— 申请人不再挑具体资产，
 *       改为挑「借哪一类」，设备绑定延后到 IT 交付扫码；</li>
 *   <li>整组「临时锁倒计时」用例删除：锁机制（前端倒计时 + 后端三列 +
 *       超时释放任务）在 V56 一并移除，那几个纯函数已从 orderForm.ts 里删掉，
 *       留着测试只会变成「测试一个不存在的函数」。</li>
 * </ul>
 *
 * <p> 删掉的两组用例（借用类型 → 日期必填联动 / 切类型丢弃残留日期）：
 * 那两条规则随「借用类型选择器」一起退役 —— 现在没有可切换的类型，
 * 「长期领用」也不再是前端可达状态。规则本体在后端 `UseType` 与
 * `OrderServiceImpl#create` 里仍在（存量工单与旧调用方依赖它），只是前端不再有分支。
 */

function form(overrides: Partial<OrderFormState> = {}): OrderFormState {
  return {
    primaryCategoryId: 1,
    secondaryCategoryId: null,
    modelId: null,
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
  it('未选设备类别时提示先选类别', () => {
    expect(validateOrderForm(form({ primaryCategoryId: null }), TODAY)).toContain('请选择要借用的设备类别')
  })

  it('二级分类与型号留空是合法的（表示同类别内哪台都行）', () => {
    expect(
      validateOrderForm(form({ secondaryCategoryId: null, modelId: null }), TODAY)
    ).toEqual([])
  })

  it('二级分类与型号填了也不影响前端校验（归属由服务端查库判定）', () => {
    expect(validateOrderForm(form({ secondaryCategoryId: 7, modelId: 9 }), TODAY)).toEqual([])
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
