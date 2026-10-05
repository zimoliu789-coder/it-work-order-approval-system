/**
 * 数据库备份类型（P0）
 *
 * 与后端 `module/backup` 的 DTO 一一对应：
 * - `BackupRecordVO` → {@link BackupRecordItem}
 * - `BackupOverviewVO` → {@link BackupOverview}
 */

/** 备份状态：进行中 / 成功 / 失败 */
export type BackupStatusCode = 'RUNNING' | 'SUCCESS' | 'FAILED'

/** 触发方式：定时自动 / 手动触发 */
export type BackupTriggerCode = 'SCHEDULED' | 'MANUAL'

/** 备份记录列表项 */
export interface BackupRecordItem {
  id: number
  /** 归档文件名；失败时为空串 */
  fileName: string
  /** 原始字节数；0 表示本次未产出归档 */
  fileSize: number
  /** 人类可读大小，例如 1.20 MB；失败为 '-'（服务端格式化，避免前端各写一套） */
  sizeText: string
  status: BackupStatusCode
  statusLabel: string
  triggerType: BackupTriggerCode
  triggerLabel: string
  startedAt: string
  finishedAt: string | null
  durationMs: number
  durationText: string
  /** 失败原因（服务端已截断到前 8 行）；成功为 null */
  errorMessage: string | null
  /** 手动触发的操作人姓名；定时为 null */
  operatorName: string | null
}

/** 备份概览 */
export interface BackupOverview {
  /** 是否启用应用内自动备份（false 时页面必须显式提示「不会自动跑」） */
  enabled: boolean
  /** 每天备份的时刻（0-23） */
  backupHour: number
  retentionDays: number
  /** 实际生效的备份目录 */
  dir: string
  /** 目录当前是否可写；false 时页面直接给出原因，而不是等备份失败 */
  dirWritable: boolean
  /** 最后一次成功备份的时间；从未成功过时为 null */
  lastSuccessAt: string | null
  lastSuccessFile: string | null
  lastSuccessSize: number | null
  lastSuccessSizeText: string | null
  /** 最近一次失败（成功晚于失败时为空，避免历史失败误导当前判断） */
  lastFailureAt: string | null
  lastFailureReason: string | null
  /** 当前是否有备份正在执行 */
  running: boolean
  /** 今天是否已完成过定时备份 */
  scheduledToday: boolean
  /** 「今天还会不会跑」的人话说明（服务端生成，页面直接展示） */
  scheduleHint: string
}

/** 状态标签色（与全站语义色一致：成功=success，进行中=info，失败=danger） */
export function backupStatusTagType(status: BackupStatusCode): 'success' | 'info' | 'danger' {
  switch (status) {
    case 'SUCCESS':
      return 'success'
    case 'FAILED':
      return 'danger'
    default:
      return 'info'
  }
}

/** 触发方式标签色：定时=info（无人操作），手动=warning（有人操作，需可追溯） */
export function backupTriggerTagType(trigger: BackupTriggerCode): 'info' | 'warning' {
  return trigger === 'MANUAL' ? 'warning' : 'info'
}

/** 每天备份时刻的展示文案（0-23 → 「02:00」） */
export function formatBackupHour(hour: number): string {
  return `${String(hour).padStart(2, '0')}:00`
}
