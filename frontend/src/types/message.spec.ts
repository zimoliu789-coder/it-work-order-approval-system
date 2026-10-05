import { describe, expect, it } from 'vitest'
import { MESSAGE_TYPE_OPTIONS, messageTagType, messageTargetRoute, messageTypeLabel } from '@/types/message'

/**
 * 站内消息展示规则单测（ 补齐， / ）
 *
 * 为什么单独建文件：`orderDisplay.spec.ts` 已覆盖「消息类型标签色」的既有类型，
 * 这里只补  新增能力 —— 催办类型（URGE_APPROVAL / URGE_RETURN）的标签色，
 * 以及「点击消息跳转目标」这张映射表（原先内联在 `MessageBell.vue` 里，无测试覆盖）。
 *
 * 跳错页面不会让任何测试变红，也不会报错，只会把用户静默带到无关页面 ——
 * 属于典型的「必须有断言才能发现」的缺陷面，故把映射抽成纯函数并在此固化。
 */
describe('消息标签色（：催办提醒属「要动手」类）', () => {
  it('审批催办 / 归还催办 = warning（与审批待办、到期预警同类）', () => {
    expect(messageTagType('URGE_APPROVAL')).toBe('warning')
    expect(messageTagType('URGE_RETURN')).toBe('warning')
  })

  it('转交相关消息不误判为 danger（转交是正常流转，不是异常）', () => {
    expect(messageTagType('ORDER_TRANSFERRED')).toBe('info')
  })
})

describe('消息点击跳转目标（ 消息铃铛）', () => {
  it('审批待办与审批催办都去「工单审批」页', () => {
    expect(messageTargetRoute('APPROVAL_TODO')).toBe('/order/approval')
    expect(messageTargetRoute('URGE_APPROVAL')).toBe('/order/approval')
  })

  it('执行人相关（待交付 / 待收回 / 超时 / 离职回收 / 被转交）都去「我的待处理」', () => {
    expect(messageTargetRoute('DELIVERY_TODO')).toBe('/order/pending')
    expect(messageTargetRoute('RETURN_REQUESTED')).toBe('/order/pending')
    expect(messageTargetRoute('BORROW_TIMEOUT')).toBe('/order/pending')
    expect(messageTargetRoute('DIMISSION_RETURN')).toBe('/order/pending')
    // ：被转交的新执行人需要去「我的待处理」接手，而不是「我的工单」
    expect(messageTargetRoute('ORDER_TRANSFERRED')).toBe('/order/pending')
  })

  it('归还催办提醒借用人 → 回落「我的工单」（借用人从那里发起归还）', () => {
    expect(messageTargetRoute('URGE_RETURN')).toBe('/order/mine')
  })

  it('审批结果类回落「我的工单」', () => {
    expect(messageTargetRoute('APPROVAL_PASSED')).toBe('/order/mine')
    expect(messageTargetRoute('APPROVAL_REJECTED')).toBe('/order/mine')
    expect(messageTargetRoute('EXTEND_RESULT')).toBe('/order/mine')
  })

  it('未知 / 空类型回落「我的工单」（不抛错）', () => {
    expect(messageTargetRoute(undefined)).toBe('/order/mine')
    expect(messageTargetRoute(null)).toBe('/order/mine')
    expect(messageTargetRoute('SOMETHING_NEW')).toBe('/order/mine')
  })

  it('密码变更（ 新增 PASSWORD_RESET）→「个人中心」（与工单无关，不应跳工单列表）', () => {
    expect(messageTargetRoute('PASSWORD_RESET')).toBe('/profile')
  })
})

describe('消息类型下拉与标签（ 消息中心）', () => {
  it('下拉项覆盖 PASSWORD_RESET，且中文名兜底可取到（不回落英文码）', () => {
    expect(MESSAGE_TYPE_OPTIONS.some((o) => o.value === 'PASSWORD_RESET')).toBe(true)
    expect(messageTypeLabel('PASSWORD_RESET')).toBe('密码变更')
  })

  it('未知类型回落原值 / 空值回落「消息」', () => {
    expect(messageTypeLabel('NOT_A_TYPE')).toBe('NOT_A_TYPE')
    expect(messageTypeLabel(null)).toBe('消息')
  })
})

describe('导出完成消息（，）', () => {
  it('导出完成属「完成类」通知 → success（用户只需去下载，不需再动手处理业务）', () => {
    expect(messageTagType('EXPORT_READY')).toBe('success')
  })

  it('点击导出完成消息 → 跳「导出记录」页下载（不能落回工单列表）', () => {
    expect(messageTargetRoute('EXPORT_READY')).toBe('/export/records')
  })

  it('下拉项覆盖 EXPORT_READY，且中文名可取到', () => {
    expect(MESSAGE_TYPE_OPTIONS.some((o) => o.value === 'EXPORT_READY')).toBe(true)
    expect(messageTypeLabel('EXPORT_READY')).toBe('导出完成')
  })
})

describe('备份失败告警消息（Docker 部署 + 限流加固，）', () => {
  it('备份失败属「异常」类 → danger（数据安全网破损，不能被当成普通通知略过）', () => {
    expect(messageTagType('OPS_BACKUP_ALERT')).toBe('danger')
  })

  it('点击备份失败告警 → 跳「系统参数设置」（保留天数与清理策略参数就在该页）', () => {
    expect(messageTargetRoute('OPS_BACKUP_ALERT')).toBe('/system/config')
  })

  it('下拉项覆盖 OPS_BACKUP_ALERT，且中文名可取到（不回落英文码）', () => {
    expect(MESSAGE_TYPE_OPTIONS.some((o) => o.value === 'OPS_BACKUP_ALERT')).toBe(true)
    expect(messageTypeLabel('OPS_BACKUP_ALERT')).toBe('备份失败告警')
  })
})
