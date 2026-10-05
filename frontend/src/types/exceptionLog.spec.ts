import { describe, expect, it } from 'vitest'
import {
  alertStateHint,
  alertStateLabel,
  severityTagType
} from '@/types/exceptionLog'

/**
 * 异常日志的展示映射单测。
 *
 * <p>这三个函数都属「写错了不会报错、只会让人误判」的类型：
 * 分级色写错会让最严重的行看起来最不起眼；「未告警」不给原因会让人怀疑漏发。
 */
describe('severityTagType —— 分级配色', () => {
  it('P0 用 danger（最醒目），P1 用 warning', () => {
    expect(severityTagType('P0')).toBe('danger')
    expect(severityTagType('P1')).toBe('warning')
  })

  it('★ P2 与未知值都不占用 danger（红色必须稀缺，否则到处都是红的）', () => {
    expect(severityTagType('P2')).toBe('info')
    expect(severityTagType(null)).toBe('info')
    expect(severityTagType(undefined)).toBe('info')
    expect(severityTagType('P9')).toBe('info')
  })
})

describe('alertStateLabel —— 状态中文标签', () => {
  it('三种状态各有中文名', () => {
    expect(alertStateLabel('PENDING')).toBe('待告警')
    expect(alertStateLabel('SENT')).toBe('已告警')
    expect(alertStateLabel('SUPPRESSED')).toBe('未告警')
  })

  it('未知值原样返回，空值回落到占位符（不出现 undefined）', () => {
    expect(alertStateLabel('SOMETHING')).toBe('SOMETHING')
    expect(alertStateLabel(null)).toBe('-')
  })
})

describe('alertStateHint —— 「未告警」必须给得出原因', () => {
  it('三种状态都有可读说明', () => {
    expect(alertStateHint('PENDING')).toContain('汇总')
    expect(alertStateHint('SENT')).toContain('通知')
    expect(alertStateHint('SUPPRESSED')).toContain('策略')
  })

  it('未知值返回空串（不编造说明）', () => {
    expect(alertStateHint('SOMETHING')).toBe('')
    expect(alertStateHint(null)).toBe('')
  })
})
