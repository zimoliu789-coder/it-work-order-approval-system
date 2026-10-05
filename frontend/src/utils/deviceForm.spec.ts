import { describe, expect, it } from 'vitest'
import {
  childrenOf,
  normalizeSecondaryForEdit,
  resolveSecondaryAfterPrimaryChange,
  type CategoryNodeLike
} from './deviceForm'

/**
 * 设备台账分类联动的回归保护（ C1 缺陷同族规则）
 *
 *  的 Critical 缺陷是「一级分类变化 → 二级分类被静默清空」，
 * 且发生在编辑回填场景（回填的值被 watch 微任务重置）。此处把规则抽成纯函数并锁死行为：
 * - 二级仍属新一级 → 保留；
 * - 二级不属于新一级 → 必须清空（否则会提交一级/二级矛盾的脏数据）；
 * - 编辑回填 → 按 parentId 判定一致性，不依赖分类树是否恰好带 children。
 */

const CATEGORIES: CategoryNodeLike[] = [
  {
    id: 1,
    parentId: 0,
    level: 1,
    children: [
      { id: 4, parentId: 1, level: 2 },
      { id: 5, parentId: 1, level: 2 }
    ]
  },
  { id: 2, parentId: 0, level: 1, children: [{ id: 6, parentId: 2, level: 2 }] },
  { id: 3, parentId: 0, level: 1, children: [] }
]

describe('childrenOf', () => {
  it('返回指定一级分类下的二级分类', () => {
    expect(childrenOf(1, CATEGORIES).map((item) => item.id)).toEqual([4, 5])
    expect(childrenOf(2, CATEGORIES).map((item) => item.id)).toEqual([6])
  })

  it('一级分类为空或不存在时返回空数组', () => {
    expect(childrenOf(null, CATEGORIES)).toEqual([])
    expect(childrenOf(999, CATEGORIES)).toEqual([])
  })
})

describe('resolveSecondaryAfterPrimaryChange', () => {
  it('二级分类仍属于新的一级分类时保留', () => {
    expect(resolveSecondaryAfterPrimaryChange(4, 1, CATEGORIES)).toBe(4)
  })

  it('二级分类不属于新的一级分类时清空', () => {
    expect(resolveSecondaryAfterPrimaryChange(4, 2, CATEGORIES)).toBeNull()
  })

  it('新一级分类下没有二级分类时清空（避免提交矛盾组合）', () => {
    expect(resolveSecondaryAfterPrimaryChange(4, 3, CATEGORIES)).toBeNull()
  })

  it('一级或二级为空时结果为空', () => {
    expect(resolveSecondaryAfterPrimaryChange(null, 1, CATEGORIES)).toBeNull()
    expect(resolveSecondaryAfterPrimaryChange(4, null, CATEGORIES)).toBeNull()
  })
})

describe('normalizeSecondaryForEdit（编辑回填校正）', () => {
  it('二级分类的 parentId 与一级分类一致时保留', () => {
    expect(normalizeSecondaryForEdit(1, 4, CATEGORIES)).toBe(4)
  })

  it('二级分类的 parentId 与一级分类不一致时清空', () => {
    expect(normalizeSecondaryForEdit(2, 4, CATEGORIES)).toBeNull()
  })

  it('分类列表中查不到该二级分类时保持原值（交由后端校验，不静默丢数据）', () => {
    expect(normalizeSecondaryForEdit(1, 999, CATEGORIES)).toBe(999)
  })

  it('一级或二级为空时返回 null', () => {
    expect(normalizeSecondaryForEdit(null, 4, CATEGORIES)).toBeNull()
    expect(normalizeSecondaryForEdit(1, null, CATEGORIES)).toBeNull()
  })

  it('分类树未携带 children 时依然能按 parentId 正确判定', () => {
    const flat: CategoryNodeLike[] = [
      { id: 1, parentId: 0, level: 1 },
      { id: 4, parentId: 1, level: 2 }
    ]
    expect(normalizeSecondaryForEdit(1, 4, flat)).toBe(4)
    expect(normalizeSecondaryForEdit(2, 4, flat)).toBeNull()
  })
})
