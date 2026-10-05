import { describe, expect, it } from 'vitest'
import { blockRemainText, blockSourceLabel, securityTagType } from '@/types/securityLog'

/**
 * 安全日志的展示映射单测。
 *
 * <p>三个函数都属「写错了不会报错、只会让人误判」的类型：
 * 颜色写错会让「防护已介入」的事件看起来像噪音；剩余时间写错会让人以为快解封了。
 */
describe('securityTagType —— 只有「封禁」是红色', () => {
  it('IP 封禁用 danger（这是防护已介入的信号）', () => {
    expect(securityTagType('IP_BLOCKED')).toBe('danger')
  })

  it('账号锁定与越权尝试用 warning', () => {
    expect(securityTagType('ACCOUNT_LOCKED')).toBe('warning')
    expect(securityTagType('PERM_ESCALATION_ATTEMPT')).toBe('warning')
  })

  it('异常登录（P2）用 warning —— 账号可能已被他人使用，需关注', () => {
    expect(securityTagType('LOGIN_ANOMALY')).toBe('warning')
  })

  it('★ 登录失败与未知类型都是灰色 —— 它是高频噪音，染红会让整页变红', () => {
    expect(securityTagType('LOGIN_FAIL')).toBe('info')
    expect(securityTagType('IP_UNBLOCKED')).toBe('info')
    expect(securityTagType(null)).toBe('info')
    expect(securityTagType('WHATEVER')).toBe('info')
  })
})

describe('blockSourceLabel', () => {
  it('AUTO / MANUAL 翻译成中文', () => {
    expect(blockSourceLabel('AUTO')).toBe('自动')
    expect(blockSourceLabel('MANUAL')).toBe('人工')
  })

  it('未知值原样返回，空值回落占位符', () => {
    expect(blockSourceLabel('OTHER')).toBe('OTHER')
    expect(blockSourceLabel(null)).toBe('-')
  })
})

describe('blockRemainText —— 永久封禁必须说清「永久」', () => {
  const now = new Date('2026-10-04T10:00:00').getTime()

  it('★ expireAt 为空 = 永久，必须显式说明需人工解除', () => {
    const text = blockRemainText(null, now)
    expect(text).toContain('永久')
    expect(text).toContain('人工')
  })

  it('未到期显示剩余分钟', () => {
    expect(blockRemainText('2026-10-04 10:30:00', now)).toBe('剩余约 30 分钟')
  })

  it('超过一小时显示剩余小时', () => {
    expect(blockRemainText('2026-10-04 12:00:00', now)).toBe('剩余约 2 小时')
  })

  it('已过期显示「已到期」（而不是负数剩余）', () => {
    expect(blockRemainText('2026-10-04 09:00:00', now)).toBe('已到期')
  })

  it('时间格式非法时返回占位符，不抛异常', () => {
    expect(blockRemainText('not-a-date', now)).toBe('-')
  })
})
