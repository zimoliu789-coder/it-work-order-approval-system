import type { LogOption } from '@/types/system'

/**
 * 安全日志—— 与后端 `module/security` 的 SecurityEventVO / BlockVO 逐字镜像。
 */

export interface SecurityEventItem {
  id: number
  /** LOGIN_FAIL / ACCOUNT_LOCKED / IP_BLOCKED / IP_UNBLOCKED / PERM_ESCALATION_ATTEMPT / LOGIN_ANOMALY */
  eventType: string
  eventTypeLabel: string
  username: string | null
  userId: number | null
  ip: string | null
  userAgent: string | null
  detail: string | null
  occurredAt: string
}

/** 当前封禁列表行 */
export interface IpBlockItem {
  id: number
  ip: string
  reason: string | null
  source: string
  sourceLabel: string
  failCount: number | null
  blockedAt: string
  /** null 表示永久封禁（只能人工解除） */
  expireAt: string | null
  permanent: boolean
}

export interface SecurityLogQuery {
  page: number
  size: number
  eventType: string
  ip: string
  username: string
  startTime: string
  endTime: string
}

/** 概览（当前封禁 + 失败最多的 IP + 各类型计数） */
export interface SecurityOverview {
  windowHours: number
  blocks: IpBlockItem[]
  topFailIps: Array<Record<string, unknown>>
  typeCounts: Array<Record<string, unknown>>
  ipBlockEnabled: boolean
  whitelist: string
}

export interface SecurityLogOptions {
  eventTypes: LogOption[]
}

/**
 * 事件类型 → 标签色。
 *
 * <p>只有「封禁」用 danger：它是防护**已经介入**的结果，是这一页里唯一
 * 「有事发生了」的信号。登录失败用灰色 —— 它是高频噪音，染成红色会让整页变红，
 * 反而看不出哪一行才重要。
 */
export function securityTagType(eventType: string | null | undefined): 'danger' | 'warning' | 'info' {
  switch (eventType) {
    case 'IP_BLOCKED':
      return 'danger'
    // 账号锁定 / 越权尝试 / 异常登录（P2：凌晨 / 新设备 / 非常用 IP，账号可能已被他人使用）都用 warning
    case 'ACCOUNT_LOCKED':
    case 'PERM_ESCALATION_ATTEMPT':
    case 'LOGIN_ANOMALY':
      return 'warning'
    default:
      return 'info'
  }
}

/** 封禁来源 → 中文标签（后端已下发，这里兜底防脏值） */
export function blockSourceLabel(source: string | null | undefined): string {
  if (source === 'AUTO') {
    return '自动'
  }
  if (source === 'MANUAL') {
    return '人工'
  }
  return source ?? '-'
}

/**
 * 封禁剩余时间的可读文案。
 *
 * <p>永久封禁必须**显式说「永久」**：只显示一个空的到期时间，
 * 管理员会以为「可能是没设置」，而它实际上意味着「不人工解除就永远进不来」。
 */
export function blockRemainText(expireAt: string | null | undefined, now: number = Date.now()): string {
  if (!expireAt) {
    return '永久（需人工解除）'
  }
  const target = new Date(expireAt.replace(' ', 'T')).getTime()
  if (Number.isNaN(target)) {
    return '-'
  }
  const remainMs = target - now
  if (remainMs <= 0) {
    return '已到期'
  }
  const minutes = Math.ceil(remainMs / 60000)
  if (minutes < 60) {
    return `剩余约 ${minutes} 分钟`
  }
  return `剩余约 ${Math.ceil(minutes / 60)} 小时`
}
