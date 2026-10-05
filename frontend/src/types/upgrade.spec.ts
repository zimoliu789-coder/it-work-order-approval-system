import { describe, expect, it } from 'vitest'
import {
  UPGRADE_ACTIVE_STATUSES,
  formatPackageSize,
  isUpgradeActive,
  shortSha,
  upgradeHint,
  upgradeTagType,
  type UpgradeStatusCode,
  type UpgradeTaskItem
} from '@/types/upgrade'

/**
 * 在线升级展示口径单测
 *
 * 锁住三类容易出错的地方：
 * ① **活跃状态集合必须与后端逐字一致** —— 它同时出现在后端 `UpgradeStatus#isActive()`、
 *    V30 的 `active_flag` 生成列、以及本文件三处。任何一处漏改都会造成
 *    「界面说没有进行中的任务，但插库被唯一键拦下」这种自相矛盾的现象；
 * ② **`READY_TO_APPLY` 不是成功** —— 它必须显示为「等你动手」（primary），
 *    用绿色会让管理员以为升级已经完成，而系统跑的仍是旧版本；
 * ③ **null ≠ 0 / 空串** —— 后端 Jackson 配置为 `non_null`，缺省字段在这里必须
 *    显示为「—」，而不是 `undefined` 或 `0 B`。
 */

function task(patch: Partial<UpgradeTaskItem> = {}): UpgradeTaskItem {
  return {
    taskNo: '20261001010101-abcdef01',
    packageName: 'ticket-1.5.0.zip',
    targetVersion: '1.5.0',
    status: 'READY_TO_APPLY',
    statusLabel: '已就绪待应用',
    rollbackEnabled: true,
    rollbackable: true,
    ...patch
  }
}

describe('升级状态判定', () => {
  it('活跃状态集合与后端 UpgradeStatus.isActive() 逐字一致（含顺序）', () => {
    expect([...UPGRADE_ACTIVE_STATUSES]).toEqual([
      'PENDING',
      'VALIDATING',
      'BACKING_UP',
      'STAGING',
      'READY_TO_APPLY',
      'APPLYING'
    ])
  })

  it('活跃状态：进行中但未达终态', () => {
    const active: UpgradeStatusCode[] = [
      'PENDING',
      'VALIDATING',
      'BACKING_UP',
      'STAGING',
      'READY_TO_APPLY',
      'APPLYING'
    ]
    for (const status of active) {
      expect(isUpgradeActive(status)).toBe(true)
    }
  })

  it('终态：SUCCESS / FAILED / ROLLED_BACK 都不算活跃（轮询必须停下）', () => {
    const terminal: UpgradeStatusCode[] = ['SUCCESS', 'FAILED', 'ROLLED_BACK']
    for (const status of terminal) {
      expect(isUpgradeActive(status)).toBe(false)
    }
  })

  it('状态缺失：视为非活跃，不抛异常', () => {
    expect(isUpgradeActive(undefined)).toBe(false)
  })
})

describe('状态标签样式', () => {
  it('READY_TO_APPLY / APPLYING 用 primary（等你动手，不是「已经好了」）', () => {
    expect(upgradeTagType('READY_TO_APPLY')).toBe('primary')
    expect(upgradeTagType('APPLYING')).toBe('primary')
  })

  it('SUCCESS 绿 / FAILED 红 / ROLLED_BACK 橙 / 处理中灰', () => {
    expect(upgradeTagType('SUCCESS')).toBe('success')
    expect(upgradeTagType('FAILED')).toBe('danger')
    expect(upgradeTagType('ROLLED_BACK')).toBe('warning')
    expect(upgradeTagType('VALIDATING')).toBe('info')
    expect(upgradeTagType('PENDING')).toBe('info')
    expect(upgradeTagType(undefined)).toBe('info')
  })
})

describe('下一步提示', () => {
  it('已就绪 + 已配置外部命令：提示可以点「立即应用」', () => {
    expect(upgradeHint(task(), true)).toContain('立即应用')
  })

  it('已就绪 + 未配置外部命令：提示需运维手动应用（不能让人一直找一个不存在的按钮）', () => {
    const hint = upgradeHint(task(), false)
    expect(hint).toContain('未配置外部应用命令')
    expect(hint).toContain('手动应用')
  })

  it('应用中：说明后端可能随时重启（避免用户以为页面卡死）', () => {
    expect(upgradeHint(task({ status: 'APPLYING' }), true)).toContain('重启')
  })

  it('回滚后：必须说清「仍需重启才生效」这一事实', () => {
    expect(upgradeHint(task({ status: 'ROLLED_BACK' }), true)).toContain('重启')
  })

  it('失败：说明产物未被替换、当前仍跑旧版本（这是用户最关心的一句）', () => {
    const hint = upgradeHint(task({ status: 'FAILED' }), true)
    expect(hint).toContain('失败')
    expect(hint).toContain('旧版本')
  })

  it('成功：明确告知新版本已生效', () => {
    expect(upgradeHint(task({ status: 'SUCCESS' }), true)).toContain('成功')
  })
})

describe('展示格式', () => {
  it('包大小：null / 0 显示「—」而不是 0 B', () => {
    expect(formatPackageSize(undefined)).toBe('—')
    expect(formatPackageSize(0)).toBe('—')
    expect(formatPackageSize(-1)).toBe('—')
  })

  it('包大小：按 KB / MB 分档', () => {
    expect(formatPackageSize(512)).toBe('512 B')
    expect(formatPackageSize(2048)).toBe('2.0 KB')
    expect(formatPackageSize(5 * 1024 * 1024)).toBe('5.0 MB')
  })

  it('哈希：只显示前 12 位并带省略号（完整值会让表格无法阅读）', () => {
    const sha = 'a'.repeat(64)
    expect(shortSha(sha)).toBe('aaaaaaaaaaaa…')
    expect(shortSha('short')).toBe('short')
    expect(shortSha(undefined)).toBe('—')
  })
})
