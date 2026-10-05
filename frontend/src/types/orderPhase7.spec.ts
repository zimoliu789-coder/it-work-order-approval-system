import { describe, expect, it } from 'vitest'
import { isDueOrTimeout, transferTypeTagType } from '@/types/order'

/**
 *  前端纯逻辑单测（工单转交 + 催办）
 *
 * 覆盖两处「前端自己判断、且判断错了会直接误导用户」的逻辑：
 * 1. {@link isDueOrTimeout} —— 决定「催还」按钮是否出现。后端 canUrgeReturn 在冷却期会变成 false，
 *    因此按钮可见性不能只看 canUrgeReturn，必须由前端复刻「已到期/超时」判定；一旦与后端不一致，
 *    用户要么看不到本该有的催还入口，要么点了报错。
 * 2. {@link transferTypeTagType} —— 区分「人工转交」与「离职自动转交」的视觉语义（责任归属提示）。
 */
describe('isDueOrTimeout（催还适用条件）', () => {
  // 固定基准时刻，避免依赖真实时钟：2026-09-19 12:00:00（本地时区）
  const now = new Date(2026, 8, 19, 12, 0, 0).getTime()

  it('超时标记为真 → 视为已到期（与 planningEndTime 无关）', () => {
    expect(isDueOrTimeout({ borrowTimeout: true, plannedEndTime: null }, now)).toBe(true)
    expect(isDueOrTimeout({ borrowTimeout: true, plannedEndTime: '2030-01-01 00:00:00' }, now)).toBe(true)
  })

  it('无超时标记且无计划归还时间 → 未到期（长期领用不催还）', () => {
    expect(isDueOrTimeout({ borrowTimeout: false, plannedEndTime: null }, now)).toBe(false)
  })

  it('计划归还时间已过 → 已到期', () => {
    expect(isDueOrTimeout({ borrowTimeout: false, plannedEndTime: '2026-09-19 11:00:00' }, now)).toBe(true)
    expect(isDueOrTimeout({ borrowTimeout: false, plannedEndTime: '2026-09-18 23:59:59' }, now)).toBe(true)
  })

  it('计划归还时间晚于当前 → 未到期', () => {
    expect(isDueOrTimeout({ borrowTimeout: false, plannedEndTime: '2026-09-19 13:00:00' }, now)).toBe(false)
  })

  it('计划归还时间恰为当前 → 视为到期（含等于，与后端 <= 判定一致）', () => {
    expect(isDueOrTimeout({ borrowTimeout: false, plannedEndTime: '2026-09-19 12:00:00' }, now)).toBe(true)
  })

  it('时间格式非法 → 不误判为到期', () => {
    expect(isDueOrTimeout({ borrowTimeout: false, plannedEndTime: 'not-a-date' }, now)).toBe(false)
  })

  it('缺省 now 时使用当前时刻（不抛异常）', () => {
    expect(isDueOrTimeout({ borrowTimeout: false, plannedEndTime: '2000-01-01 00:00:00' })).toBe(true)
    expect(isDueOrTimeout({ borrowTimeout: false, plannedEndTime: '2999-01-01 00:00:00' })).toBe(false)
  })
})

describe('transferTypeTagType（转交类型视觉语义）', () => {
  it('离职自动转交 → 警示色（提示这不是本人主动交接）', () => {
    expect(transferTypeTagType('AUTO_DIMISSION')).toBe('warning')
  })

  it('人工转交 → 中性色', () => {
    expect(transferTypeTagType('MANUAL')).toBe('info')
  })

  it('缺省/未知取值 → 中性色，不出现 undefined', () => {
    expect(transferTypeTagType(null)).toBe('info')
    expect(transferTypeTagType(undefined)).toBe('info')
  })
})
