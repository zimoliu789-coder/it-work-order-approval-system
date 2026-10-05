import { describe, expect, it } from 'vitest'
import {
  decoratePermissionLabel,
  riskLevelLabel,
  riskLevelTagType,
  type ApplicableRiskMap
} from '@/types/permissionPolicy'

/**
 * 权限策略展示映射单测。
 *
 * <p>这三个函数都属「写错了不会报错、只会让人做出错误决定」的类型：
 * 等级显示错 ⇒ 管理员把高危码放开申请；标记漏了 ⇒ 申请人不知道要多走一级。
 */
describe('riskLevelLabel —— 等级必须说清是几级审批', () => {
  it('HIGH 显示「高危（两级）」，其余显示「普通（一级）」', () => {
    expect(riskLevelLabel('HIGH')).toBe('高危（两级）')
    expect(riskLevelLabel('NORMAL')).toBe('普通（一级）')
  })

  it('空值 / 脏值按普通处理（不出现 undefined）', () => {
    expect(riskLevelLabel(null)).toBe('普通（一级）')
    expect(riskLevelLabel(undefined)).toBe('普通（一级）')
    expect(riskLevelLabel('WHATEVER')).toBe('普通（一级）')
  })
})

describe('riskLevelTagType —— 只有高危是红色', () => {
  it('HIGH → danger，其余 → info', () => {
    expect(riskLevelTagType('HIGH')).toBe('danger')
    expect(riskLevelTagType('NORMAL')).toBe('info')
    expect(riskLevelTagType(null)).toBe('info')
  })
})

describe('decoratePermissionLabel —— 高危标记必须出现在选项文案上', () => {
  const risks: ApplicableRiskMap = { 'device:ledger:manage': 'HIGH', 'device:ledger:view': 'NORMAL' }

  it('★ 高危码的文案带「高危 · 需两级审批」', () => {
    expect(decoratePermissionLabel('设备台账维护', 'device:ledger:manage', risks)).toContain('高危')
    expect(decoratePermissionLabel('设备台账维护', 'device:ledger:manage', risks)).toContain('两级')
  })

  it('普通码的文案原样返回（不滥用标记）', () => {
    expect(decoratePermissionLabel('设备台账查看', 'device:ledger:view', risks)).toBe('设备台账查看')
  })

  it('映射里没有的码按普通处理（不抛异常、不加标记）', () => {
    expect(decoratePermissionLabel('未知权限', 'unknown:code', risks)).toBe('未知权限')
    expect(decoratePermissionLabel('未知权限', 'unknown:code', {})).toBe('未知权限')
  })
})
