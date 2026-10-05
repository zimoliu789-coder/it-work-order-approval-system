import { describe, expect, it } from 'vitest'
import {
  backupStatusTagType,
  backupTriggerTagType,
  formatBackupHour,
  type BackupRecordItem
} from '@/types/backup'

/**
 * 备份类型与展示映射（P0）
 *
 * <p>这里钉的是「状态 → 语义色」与「时刻格式化」两件小事，但它们坏起来都很安静：
 * 把 FAILED 映射成 success，页面会把一次失败的备份渲染成绿色 —— 而
 * 「备份到底有没有在跑」正是本功能的唯一健康判据，颜色错了比没有更糟。
 */
describe('备份状态语义色', () => {
  it('成功=success / 失败=danger / 进行中=info', () => {
    expect(backupStatusTagType('SUCCESS')).toBe('success')
    expect(backupStatusTagType('FAILED')).toBe('danger')
    expect(backupStatusTagType('RUNNING')).toBe('info')
  })

  it('★ 失败绝不能是绿色（健康判据被染色是最坏的展示缺陷）', () => {
    expect(backupStatusTagType('FAILED')).not.toBe('success')
  })

  it('手动触发=warning（有人操作、需可追溯），定时=info', () => {
    expect(backupTriggerTagType('MANUAL')).toBe('warning')
    expect(backupTriggerTagType('SCHEDULED')).toBe('info')
  })
})

describe('备份时刻格式化', () => {
  it('补零到两位：0 → 00:00，2 → 02:00，23 → 23:00', () => {
    expect(formatBackupHour(0)).toBe('00:00')
    expect(formatBackupHour(2)).toBe('02:00')
    expect(formatBackupHour(23)).toBe('23:00')
  })
})

describe('备份记录结构约定', () => {
  it('失败记录必须带 errorMessage（页面靠它说明原因）', () => {
    const failed: BackupRecordItem = {
      id: 1,
      fileName: '',
      fileSize: 0,
      sizeText: '-',
      status: 'FAILED',
      statusLabel: '失败',
      triggerType: 'SCHEDULED',
      triggerLabel: '定时自动',
      startedAt: '2026-10-03 02:00:00',
      finishedAt: '2026-10-03 02:00:01',
      durationMs: 900,
      durationText: '900 ms',
      errorMessage: '备份目录不可写：Z:/nas/backup',
      operatorName: null
    }
    expect(failed.status).toBe('FAILED')
    expect(failed.errorMessage).toBeTruthy()
    // 失败时没有产物 ⇒ sizeText 必须是「-」而不是「0 B」
    expect(failed.sizeText).toBe('-')
    // 定时备份没有操作人
    expect(failed.operatorName).toBeNull()
  })
})
