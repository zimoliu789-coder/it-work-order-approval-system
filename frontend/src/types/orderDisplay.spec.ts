import { describe, expect, it } from 'vitest'
import {
  extendStatusTagType,
  ORDER_STATUS_OPTIONS,
  isTimeout,
  orderStatusTagType,
  returnConditionPlaceholder,
  returnConditionRequiresRemark,
  RETURN_CONDITION_OPTIONS
} from '@/types/order'
import { FAULT_STATUS_OPTIONS, faultStatusTagType } from '@/types/device'
import { messageTagType } from '@/types/message'
import { ROLE_OPTIONS, roleLabel } from '@/types/user'

/**
 *  前端纯逻辑单测（ /  /  /  / ）
 *
 * 只覆盖「会被多个页面共用的展示规则」——它们一旦漂移，问题会同时出现在
 * 我的工单 / 我的待处理 / 全部工单 / 消息铃铛四处，属于典型的「一处改错、多处出错」。
 * 按项目约定（vitest.config.ts），此处只测纯函数，不挂载组件。
 */
describe('工单状态展示', () => {
  it('使用中=success、待收回=warning、已归还=info（归还完成不再是告警色）', () => {
    expect(orderStatusTagType('BORROWED')).toBe('success')
    expect(orderStatusTagType('PENDING_RETURN')).toBe('warning')
    expect(orderStatusTagType('RETURNED')).toBe('info')
  })

  it('已驳回=danger，已撤回=info', () => {
    expect(orderStatusTagType('REJECTED')).toBe('danger')
    expect(orderStatusTagType('CANCELLED')).toBe('info')
  })

  it('状态筛选选项包含「待收回」「已归还」（ ）', () => {
    const values = ORDER_STATUS_OPTIONS.map((item) => item.value)
    expect(values).toContain('PENDING_RETURN')
    expect(values).toContain('RETURNED')
  })
})

describe('超时标记（：超时是标记位而非状态）', () => {
  it('borrowTimeout=true 才算超时', () => {
    expect(isTimeout({ borrowTimeout: true })).toBe(true)
  })

  it('未标记 / 字段缺省 / false 都不算超时', () => {
    expect(isTimeout({ borrowTimeout: false })).toBe(false)
    expect(isTimeout({ borrowTimeout: null })).toBe(false)
    expect(isTimeout({})).toBe(false)
  })
})

describe('归还检查选项（，P0 四值）', () => {
  it('★ 完好与缺配件都回可用；只有损坏进维修中', () => {
    const byValue = Object.fromEntries(RETURN_CONDITION_OPTIONS.map((item) => [item.value, item.deviceEffect]))
    // 「缺配件回可用」是本批最反直觉的一条：设备主体是好的，
    // 因为少一个配件就把整台电脑锁进维修会让可用资产凭空减少。
    expect(byValue.GOOD).toContain('可用')
    expect(byValue.MISSING_PARTS).toContain('可用')
    expect(byValue.MISSING_PARTS).not.toContain('维修中')
    expect(byValue.DAMAGED).toContain('维修中')
    expect(byValue.LOST).toContain('已丢失')
  })

  it('四个选项且取值白名单固定（旧值 MINOR_DAMAGE / FAULT 已下线）', () => {
    expect(RETURN_CONDITION_OPTIONS.map((item) => item.value)).toEqual([
      'GOOD',
      'DAMAGED',
      'MISSING_PARTS',
      'LOST'
    ])
  })

  it('说明必填恰好是「损坏 / 缺配件 / 丢失」三个，且各自有专属提示语', () => {
    const required = RETURN_CONDITION_OPTIONS.filter((item) => item.remarkRequired).map((item) => item.value)
    expect(required).toEqual(['DAMAGED', 'MISSING_PARTS', 'LOST'])
    expect(returnConditionRequiresRemark('GOOD')).toBe(false)
    // 必填项的提示语必须写清「写什么」，而不是一句「必填」
    expect(returnConditionPlaceholder('MISSING_PARTS')).toContain('配件')
    expect(returnConditionPlaceholder('LOST')).toContain('丢失')
  })
})

describe('消息类型标签色', () => {
  it('待办 / 预警类=warning', () => {
    expect(messageTagType('APPROVAL_TODO')).toBe('warning')
    expect(messageTagType('RETURN_REQUESTED')).toBe('warning')
    expect(messageTagType('BORROW_DUE_REMINDER')).toBe('warning')
  })

  it('完成类=success', () => {
    expect(messageTagType('RETURN_CONFIRMED')).toBe('success')
    expect(messageTagType('APPROVAL_PASSED')).toBe('success')
  })

  it('驳回 / 超时 / 离职回收=danger', () => {
    expect(messageTagType('APPROVAL_REJECTED')).toBe('danger')
    expect(messageTagType('BORROW_TIMEOUT')).toBe('danger')
    expect(messageTagType('DIMISSION_RETURN')).toBe('danger')
  })

  it('未知或空类型回落到 info（不抛错）', () => {
    expect(messageTagType(undefined)).toBe('info')
    expect(messageTagType(null)).toBe('info')
    expect(messageTagType('SOMETHING_NEW')).toBe('info')
  })
})

describe('角色标签（ / ）', () => {
  it('六种内置角色中文名一致（ 由 3 个扩到 6 个）', () => {
    expect(roleLabel('super_admin')).toBe('超级管理员')
    expect(roleLabel('admin')).toBe('管理员')
    expect(roleLabel('it_manager')).toBe('IT主管')
    expect(roleLabel('it_executor')).toBe('IT执行人')
    expect(roleLabel('dept_manager')).toBe('部门经理/组长')
    expect(roleLabel('user')).toBe('普通员工')
  })

  it('未登记的角色码原样回显（不伪装成普通员工）', () => {
    expect(roleLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW')
  })

  it('筛选选项覆盖六种内置角色', () => {
    expect(ROLE_OPTIONS.map((item) => item.value).sort()).toEqual([
      'admin',
      'dept_manager',
      'it_executor',
      'it_manager',
      'super_admin',
      'user'
    ])
  })
})

describe('延期状态标签色（，）', () => {
  it('通过=success、驳回=danger、审批中=warning', () => {
    expect(extendStatusTagType('APPROVED')).toBe('success')
    expect(extendStatusTagType('REJECTED')).toBe('danger')
    expect(extendStatusTagType('PENDING_APPROVAL')).toBe('warning')
  })

  it('未知状态回落到 info（不抛错）', () => {
    expect(extendStatusTagType('SOMETHING_NEW' as never)).toBe('info')
  })
})

describe('故障状态展示（，）', () => {
  it('维修完成=success、待维修=warning、已报废=danger', () => {
    expect(faultStatusTagType('REPAIRED')).toBe('success')
    expect(faultStatusTagType('PENDING_REPAIR')).toBe('warning')
    expect(faultStatusTagType('SCRAPPED')).toBe('danger')
  })

  it('故障状态筛选选项固定为三态', () => {
    expect(FAULT_STATUS_OPTIONS.map((item) => item.value)).toEqual(['PENDING_REPAIR', 'REPAIRED', 'SCRAPPED'])
  })

  it('未知状态回落到 info（不抛错）', () => {
    expect(faultStatusTagType('SOMETHING_NEW' as never)).toBe('info')
  })
})

describe('归还检查与故障建档的耦合（，；P0 改名为「损坏」）', () => {
  it('★「损坏」的选项说明必须同时提示「进维修中」与「生成故障记录」', () => {
    const damaged = RETURN_CONDITION_OPTIONS.find((item) => item.value === 'DAMAGED')
    expect(damaged?.deviceEffect).toContain('维修中')
    expect(damaged?.deviceEffect).toContain('故障记录')
  })

  it('★「缺配件」不得提示生成故障记录（否则用户会以为少个配件也算设备故障）', () => {
    const missing = RETURN_CONDITION_OPTIONS.find((item) => item.value === 'MISSING_PARTS')
    expect(missing?.deviceEffect).not.toContain('故障记录')
  })
})
