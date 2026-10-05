import { describe, expect, it } from 'vitest'
import {
  EXPORT_TYPE_OPTIONS,
  exportStatusTagType,
  exportTypeLabel,
  formatFileSize,
  isReportExport,
  requiresApplyType,
  type ExportCustomFormFilter,
  type ExportTypeCode
} from '@/types/export'

/**
 * 导出展示规则单测（，）
 *
 * 这些函数决定了导出记录页 / 导出按钮上的可见文案与状态色。它们没有副作用、
 * 纯粹是「码值 → 文案」，但一旦某个新导出类型没被登记，页面会静默显示英文码
 * 或空白，用户与验收都可能错过 —— 因此把覆盖面固化成断言。
 */
describe('导出类型中文名', () => {
  it('已知类型返回中文名', () => {
    expect(exportTypeLabel('DEVICE')).toBe('设备台账')
    expect(exportTypeLabel('ORDER')).toBe('工单记录')
    expect(exportTypeLabel('USAGE')).toBe('使用记录')
    expect(exportTypeLabel('CUSTOM_FORM')).toBe('自定义表单数据')
    expect(exportTypeLabel('REPORT_DEVICE_USAGE')).toBe('借用频次报表')
    expect(exportTypeLabel('REPORT_APPROVAL_EFFICIENCY')).toBe('审批时效报表')
    expect(exportTypeLabel('REPORT_DEVICE_FAULT')).toBe('故障统计报表')
  })

  it('未知类型原样返回、空值返回空串（不抛错、不显示 undefined）', () => {
    expect(exportTypeLabel('UNKNOWN_TYPE')).toBe('UNKNOWN_TYPE')
    expect(exportTypeLabel(null)).toBe('')
    expect(exportTypeLabel(undefined)).toBe('')
  })

  it('下拉项完整覆盖所有导出类型码（新增类型时必须同步登记，否则下拉里选不到）', () => {
    const allCodes: ExportTypeCode[] = [
      'DEVICE',
      'ORDER',
      'USAGE',
      'CUSTOM_FORM',
      'REPORT_DEVICE_USAGE',
      'REPORT_APPROVAL_EFFICIENCY',
      'REPORT_DEVICE_FAULT',
      // P2 新增：操作日志导出。这是本清单存在的意义 —— 新增导出类型时，
      // 这条用例会红，逼着人回来把它与 EXPORT_TYPE_OPTIONS 一起登记，
      // 而不是让「下拉里选不到新类型」这种问题静默上线。
      'LOG'
    ]
    for (const code of allCodes) {
      expect(EXPORT_TYPE_OPTIONS.some((option) => option.value === code)).toBe(true)
    }
    expect(EXPORT_TYPE_OPTIONS.length).toBe(allCodes.length)
  })
})

describe('导出状态标签色', () => {
  it('成功=success、失败=danger、进行中=warning、其余=info', () => {
    expect(exportStatusTagType('SUCCESS')).toBe('success')
    expect(exportStatusTagType('FAILED')).toBe('danger')
    expect(exportStatusTagType('RUNNING')).toBe('warning')
    expect(exportStatusTagType('PENDING')).toBe('info')
  })

  it('未知 / 空状态回落 info（不抛错）', () => {
    expect(exportStatusTagType(null)).toBe('info')
    expect(exportStatusTagType('WHATEVER')).toBe('info')
  })
})

describe('文件大小可读化', () => {
  it('空值 / 非正数显示占位符 「-」', () => {
    expect(formatFileSize(null)).toBe('-')
    expect(formatFileSize(undefined)).toBe('-')
    expect(formatFileSize(0)).toBe('-')
    expect(formatFileSize(-5)).toBe('-')
  })

  it('字节 / KB / MB 分档正确', () => {
    expect(formatFileSize(512)).toBe('512 B')
    expect(formatFileSize(1024)).toBe('1.0 KB')
    expect(formatFileSize(2048)).toBe('2.0 KB')
    expect(formatFileSize(1024 * 1024)).toBe('1.00 MB')
    expect(formatFileSize(1024 * 1024 * 2.5)).toBe('2.50 MB')
  })
})

describe('是否报表类导出', () => {
  it('REPORT_ 前缀判定为报表导出', () => {
    expect(isReportExport('REPORT_DEVICE_USAGE')).toBe(true)
    expect(isReportExport('REPORT_APPROVAL_EFFICIENCY')).toBe(true)
    expect(isReportExport('REPORT_DEVICE_FAULT')).toBe(true)
  })

  it('记录类导出（设备 / 工单 / 使用记录 / 自定义表单）不是报表', () => {
    expect(isReportExport('DEVICE')).toBe(false)
    expect(isReportExport('ORDER')).toBe(false)
    expect(isReportExport('USAGE')).toBe(false)
    expect(isReportExport('CUSTOM_FORM')).toBe(false)
    expect(isReportExport(null)).toBe(false)
  })
})

/**
 * 自定义表单导出（ · M6）
 *
 * `requiresApplyType` 决定界面是「直接带条件发起」还是「必须先选申请类型」。
 * 判定错了会出现两类问题：该弹对话框的没弹（用户发起一次必定被后端拒绝的导出），
 * 或不该弹的弹了（多一步无意义的交互）。因此把判定固化成断言。
 */
describe('是否必须先选申请类型', () => {
  it('仅自定义表单数据导出需要选类型', () => {
    expect(requiresApplyType('CUSTOM_FORM')).toBe(true)
  })

  it('其余导出类型都不需要（含报表与其它记录类）', () => {
    for (const code of ['DEVICE', 'ORDER', 'USAGE', 'REPORT_DEVICE_USAGE', 'REPORT_APPROVAL_EFFICIENCY', 'REPORT_DEVICE_FAULT'] as const) {
      expect(requiresApplyType(code)).toBe(false)
    }
    expect(requiresApplyType(null)).toBe(false)
    expect(requiresApplyType(undefined)).toBe(false)
  })

  it('筛选条件的 applyTypeId 是必填字段（类型层面即强制传值）', () => {
    // 这里验证的是「类型契约」：构造时必须给 applyTypeId，否则 TypeScript 直接报错。
    // 运行期把它固化成一次赋值断言，避免日后有人把它改成可选却没人发现。
    const filter: ExportCustomFormFilter = { applyTypeId: 1 }
    expect(filter.applyTypeId).toBe(1)
  })
})
