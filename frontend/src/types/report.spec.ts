import { describe, expect, it } from 'vitest'
import { REPORT_EXPORT_TYPE, REPORT_TABS, formatHours, monthOptions } from '@/types/report'

/**
 * 统计报表展示规则单测（，）
 *
 * 重点覆盖两处「看起来是零」的坑：
 * 1. 审批耗时未完成时后端给 null，若直接 `${hours} 小时` 会显示「null 小时」，
 *    若用 0 兜底又会把「审批中」误读成「瞬间通过」—— 故 formatHours 必须把 null 显式转为「审批中」。
 * 2. 报表 → 导出类型映射必须与后端 ExportType 字面量完全一致（多一个字符导出就会 400）。
 */
describe('审批耗时展示', () => {
  it('未完成审批（null / undefined）显示「审批中」而非 0', () => {
    expect(formatHours(null)).toBe('审批中')
    expect(formatHours(undefined)).toBe('审批中')
  })

  it('已完成审批显示「N 小时」，0 是合法值（确实瞬间完成），不能被当成「审批中」', () => {
    expect(formatHours(0)).toBe('0 小时')
    expect(formatHours(3)).toBe('3 小时')
    expect(formatHours(3.5)).toBe('3.5 小时')
  })
})

describe('月份下拉项', () => {
  it('返回 1-12 共 12 项，取值与文案一一对应', () => {
    const options = monthOptions()
    expect(options).toHaveLength(12)
    expect(options[0]).toEqual({ value: 1, label: '1 月' })
    expect(options[11]).toEqual({ value: 12, label: '12 月' })
    // 月份值必须连续递增，避免出现「跳号」导致某月永远选不到
    options.forEach((option, index) => {
      expect(option.value).toBe(index + 1)
    })
  })
})

describe('报表标签页与导出类型映射', () => {
  it('三个标签页的 name 与 REPORT_EXPORT_TYPE 的键完全对齐（否则导出按钮取不到类型）', () => {
    for (const tab of REPORT_TABS) {
      expect(REPORT_EXPORT_TYPE[tab.name]).toBeTruthy()
    }
    expect(Object.keys(REPORT_EXPORT_TYPE)).toHaveLength(REPORT_TABS.length)
  })

  it('报表导出类型映射到后端约定的 REPORT_* 码值', () => {
    expect(REPORT_EXPORT_TYPE['device-usage']).toBe('REPORT_DEVICE_USAGE')
    expect(REPORT_EXPORT_TYPE['approval-efficiency']).toBe('REPORT_APPROVAL_EFFICIENCY')
    expect(REPORT_EXPORT_TYPE['device-fault']).toBe('REPORT_DEVICE_FAULT')
  })
})
