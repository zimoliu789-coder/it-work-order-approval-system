import { describe, expect, it } from 'vitest'
import {
  HA_HEARTBEAT_DEFAULT,
  HA_HEARTBEAT_MAX,
  HA_HEARTBEAT_MIN,
  HA_SWITCHOVER_ACTIONS,
  formatHeartbeatAge,
  formatMoment,
  formatSyncDelay,
  haNodeStatusTag,
  haSyncStateHint,
  haSyncStateTag,
  switchoverActionLabel,
  type HaNodeStatusCode,
  type HaSyncStateCode
} from '@/types/ha'

/**
 * 主备页面展示口径单测
 *
 * 锁住三类容易出错、且出错后**不会报任何错**的地方：
 *
 * ① **切换动作白名单必须与后端逐字一致** —— 它是跨进程契约，
 *    前端多一个（或拼错一个）动作名，点击后得到的是脚本「未知子命令」的长篇报错，
 *    而不是一句能看懂的话；
 * ② **`ABNORMAL` 必须红、`LAGGING` 不能红** —— 前者是需求 [186] 的硬要求，
 *    后者若标红会让页面每分钟红几百次，最终把真故障一起埋掉；
 * ③ **`null ≠ 0`** —— 「从未上报延迟」显示 `0 秒`，
 *    会把一个真实的复制中断伪装成健康状态，是这类页面最危险的一种假象。
 */

describe('状态标签样式', () => {
  it('节点状态：运行中绿 / 待命蓝 / 异常红 / 未知灰', () => {
    expect(haNodeStatusTag('RUNNING')).toBe('success')
    expect(haNodeStatusTag('STANDBY')).toBe('primary')
    expect(haNodeStatusTag('ABNORMAL')).toBe('danger')
    expect(haNodeStatusTag('UNKNOWN')).toBe('info')
    expect(haNodeStatusTag(undefined)).toBe('info')
  })

  it('★ 未知态用灰而不是橙：刚登记的备机不是「可能坏了」', () => {
    expect(haNodeStatusTag('UNKNOWN')).not.toBe('warning')
  })

  it('同步状态：一致绿 / 落后橙 / 失败红 / 未知灰', () => {
    expect(haSyncStateTag('IN_SYNC')).toBe('success')
    expect(haSyncStateTag('LAGGING')).toBe('warning')
    expect(haSyncStateTag('FAILED')).toBe('danger')
    expect(haSyncStateTag('UNKNOWN')).toBe('info')
    expect(haSyncStateTag(undefined)).toBe('info')
  })

  it('★ 只有 FAILED 标红 —— LAGGING 标红会让真故障被噪音埋掉', () => {
    const states: HaSyncStateCode[] = ['IN_SYNC', 'LAGGING', 'UNKNOWN']
    for (const state of states) {
      expect(haSyncStateTag(state)).not.toBe('danger')
    }
  })

  it('所有状态码都有确定的样式，不出现默认落空', () => {
    const statuses: HaNodeStatusCode[] = ['RUNNING', 'STANDBY', 'ABNORMAL', 'UNKNOWN']
    for (const status of statuses) {
      expect(haNodeStatusTag(status)).toBeTruthy()
    }
  })
})

describe('同步状态说明', () => {
  it('落后：明确告诉维护人员「短暂落后属正常」', () => {
    expect(haSyncStateHint('LAGGING')).toContain('正常')
  })

  it('失败：说明数据可能已开始不一致，需要立即处理', () => {
    const hint = haSyncStateHint('FAILED')
    expect(hint).toContain('不一致')
    expect(hint).toContain('处理')
  })

  it('未知：指向最可能的排查点（心跳脚本未接通 / 令牌不一致）', () => {
    expect(haSyncStateHint(undefined)).toContain('INTERNAL_ALERT_TOKEN')
  })
})

describe('展示格式', () => {
  it('★ 心跳年龄缺省显示「从未上报」，而不是 0 秒前', () => {
    expect(formatHeartbeatAge(undefined)).toBe('从未上报')
    expect(formatHeartbeatAge(-1)).toBe('从未上报')
  })

  it('心跳年龄按秒 / 分 / 时 / 天分档', () => {
    expect(formatHeartbeatAge(0)).toBe('0 秒前')
    expect(formatHeartbeatAge(59)).toBe('59 秒前')
    expect(formatHeartbeatAge(60)).toBe('1 分钟前')
    expect(formatHeartbeatAge(3599)).toBe('59 分钟前')
    expect(formatHeartbeatAge(3600)).toBe('1 小时前')
    expect(formatHeartbeatAge(86400)).toBe('1 天前')
  })

  it('★ 复制延迟缺省显示「—」而不是 0 秒（否则故障会被伪装成健康）', () => {
    expect(formatSyncDelay(undefined)).toBe('—')
    expect(formatSyncDelay(-5)).toBe('—')
  })

  it('复制延迟：小于一分钟显示秒，超过则显示分秒', () => {
    expect(formatSyncDelay(0)).toBe('0 秒')
    expect(formatSyncDelay(45)).toBe('45 秒')
    expect(formatSyncDelay(60)).toBe('1 分 0 秒')
    expect(formatSyncDelay(90)).toBe('1 分 30 秒')
  })

  it('时间戳缺省用兜底文案，空白串也算缺省', () => {
    expect(formatMoment(undefined)).toBe('—')
    expect(formatMoment('')).toBe('—')
    expect(formatMoment('   ')).toBe('—')
    expect(formatMoment('2026-10-02 10:00:00')).toBe('2026-10-02 10:00:00')
    expect(formatMoment(undefined, '从未同步')).toBe('从未同步')
  })
})

describe('跨进程契约', () => {
  it('★ 切换动作白名单与后端 SWITCHOVER_ACTIONS 逐字一致（含顺序）', () => {
    expect([...HA_SWITCHOVER_ACTIONS]).toEqual(['to-peer', 'back', 'status'])
  })

  it('每个动作都有中文说明', () => {
    expect(switchoverActionLabel('to-peer')).toContain('让出')
    expect(switchoverActionLabel('back')).toContain('切回')
    expect(switchoverActionLabel('status')).toContain('查询')
  })

  it('★ 心跳阈值常量与后端 HaConfigValidator 一致（默认 10，区间 3-600）', () => {
    expect(HA_HEARTBEAT_DEFAULT).toBe(10)
    expect(HA_HEARTBEAT_MIN).toBe(3)
    expect(HA_HEARTBEAT_MAX).toBe(600)
  })
})
