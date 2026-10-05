import { describe, expect, it } from 'vitest'
import {
  FORCE_OPERATION_OPTIONS,
  availableForceOperations,
  forceOperationTagType,
  orderStatusTagType
} from '@/types/order'
import { messageTagType, messageTargetRoute } from '@/types/message'

/**
 * b 前端纯逻辑单测（超管强制干预 + 操作日志中文化配套）
 *
 * 为什么需要这些断言：
 * - {@link availableForceOperations} 决定「强制操作」下拉里出现哪些选项。它与后端
 *   `ForceOperationType.allowedStatuses` 是同一规则的两处实现 —— 前端多给一个选项，
 *   用户点下去会被后端 400 拒绝；少给一个，本该能用的操作入口凭空消失。
 * - {@link forceOperationTagType} 区分「驳回/终止」（危险）与「改派转交」（警告）的视觉语义。
 * - `FORCE_OPERATION_OPTIONS` 与 `ForceOperationTypeCode` 联合类型必须一一对应：
 *   新增操作类型时，若忘记补进这张表，下拉里就永远选不到该操作。
 * - `messageTagType` / `messageTargetRoute` 必须覆盖 b 引入的强制干预通知。
 *    收尾优化· 把单一 `FORCE_OPERATION` 拆成四个子类型后，
 *   本文件改为**遍历四个子类型**断言，而不是只测其中一个 —— 只测一个的话，
 *   另外三个漏配映射也不会被发现。
 */

const ALL_FORCE_OPERATIONS = ['FORCE_REJECT', 'FORCE_TERMINATE', 'FORCE_TRANSFER_APPROVAL', 'FORCE_TRANSFER_HANDLER'] as const

describe('availableForceOperations（按状态给出可用强制操作）', () => {
  it('审批中：四种强制操作全部可用', () => {
    expect(availableForceOperations('PENDING_APPROVAL')).toEqual([
      'FORCE_REJECT',
      'FORCE_TERMINATE',
      'FORCE_TRANSFER_APPROVAL',
      'FORCE_TRANSFER_HANDLER'
    ])
  })

  it('待交付 / 使用中 / 待收回：只有终止与转交执行人（其余状态无关）', () => {
    for (const status of ['PENDING_DELIVERY', 'BORROWED', 'PENDING_RETURN'] as const) {
      expect(availableForceOperations(status)).toEqual(['FORCE_TERMINATE', 'FORCE_TRANSFER_HANDLER'])
    }
  })

  it('终态（已归还 / 已驳回 / 已撤回 / 已终止）：没有任何强制操作', () => {
    for (const status of ['RETURNED', 'REJECTED', 'CANCELLED', 'TERMINATED'] as const) {
      expect(availableForceOperations(status)).toEqual([])
    }
  })

  it('「强制驳回 / 转交审批」只出现在审批中（不可越界给到其它状态）', () => {
    const approvalOnly = ['FORCE_REJECT', 'FORCE_TRANSFER_APPROVAL']
    for (const status of ['PENDING_DELIVERY', 'BORROWED', 'PENDING_RETURN', 'RETURNED', 'TERMINATED'] as const) {
      for (const op of approvalOnly) {
        expect(availableForceOperations(status)).not.toContain(op)
      }
    }
  })
})

describe('forceOperationTagType（强制操作标签色）', () => {
  it('驳回 / 终止 = danger（终结性后果）', () => {
    expect(forceOperationTagType('FORCE_REJECT')).toBe('danger')
    expect(forceOperationTagType('FORCE_TERMINATE')).toBe('danger')
  })

  it('转交审批 / 转交执行人 = warning（改派，非终结）', () => {
    expect(forceOperationTagType('FORCE_TRANSFER_APPROVAL')).toBe('warning')
    expect(forceOperationTagType('FORCE_TRANSFER_HANDLER')).toBe('warning')
  })
})

describe('FORCE_OPERATION_OPTIONS（下拉选项与类型一一对应）', () => {
  it('覆盖全部四种强制操作，且 value 无重复', () => {
    const values = FORCE_OPERATION_OPTIONS.map((o) => o.value)
    expect(values.slice().sort()).toEqual(ALL_FORCE_OPERATIONS.slice().sort())
    expect(new Set(values).size).toBe(values.length)
  })

  it('每项都有非空中文文案', () => {
    for (const opt of FORCE_OPERATION_OPTIONS) {
      expect(opt.label).toBeTruthy()
      expect(/[\u4e00-\u9fa5]/.test(opt.label)).toBe(true)
    }
  })
})

describe('TERMINATED（超管强制终止后的终态）', () => {
  it('标签色为 info（与「已撤回」一致，区别于 danger 的「已驳回」）', () => {
    expect(orderStatusTagType('TERMINATED')).toBe('info')
  })
})

describe('消息：强制干预四个子类型必须接入标签色与跳转映射（ 拆分）', () => {
  it('四种强制干预消息全部为 danger（越权级非常规动作，避免漏看）', () => {
    for (const op of ALL_FORCE_OPERATIONS) {
      expect(messageTagType(op)).toBe('danger')
    }
  })

  it('终结类（驳回 / 终止）落「我的工单」：申请人只需知悉，无需再动手', () => {
    expect(messageTargetRoute('FORCE_REJECT')).toBe('/order/mine')
    expect(messageTargetRoute('FORCE_TERMINATE')).toBe('/order/mine')
  })

  it('改派类落各自的待办队列：新审批人 / 新执行人必须能立刻办事', () => {
    expect(messageTargetRoute('FORCE_TRANSFER_APPROVAL')).toBe('/order/approval')
    expect(messageTargetRoute('FORCE_TRANSFER_HANDLER')).toBe('/order/pending')
  })

  it('旧的单一类型 FORCE_OPERATION 已下线，不再出现在映射中', () => {
    // 拆分后继续为旧编码保留映射会让「漏改」永远发现不了：
    // 若某个写入点仍在发 FORCE_OPERATION，落回默认分支即可暴露问题，
    // 而 V15 已把历史数据按标题收敛到四个子类型，不存在存量旧编码。
    expect(messageTagType('FORCE_OPERATION')).toBe('info')
  })
})
