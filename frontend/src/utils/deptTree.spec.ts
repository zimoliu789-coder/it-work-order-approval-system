import { describe, expect, it } from 'vitest'
import {
  canDragDeptNode,
  canDropDeptNode,
  deptNameMatches,
  isSelfOrDescendant,
  normalizeNodeDropType,
  resolveDropParentId,
  type DeptTreeLike
} from '@/utils/deptTree'

/**
 * 部门树拖拽内核单测
 *
 * <h2>为什么这些断言必须存在</h2>
 * 拖拽在浏览器里「能拖」很容易做到 —— 难的是**拒绝不该发生的拖拽**。
 * 而拒绝发生在 `allow-drop` 回调里，单测驱动不了真实拖拽 ⇒ 一旦规则写错，
 * 表现是「拖了一下，界面看着对，刷新后层级又回去了」（后端把非法移动拒了），
 * 用户只会觉得「这功能时灵时不灵」。所以三条后端硬约束必须在这里逐条钉住。
 *
 * 夹具树（单根，与真实数据同形：只有一个「公司」根节点）：
 * ```
 * 公司(1, parent=null)
 * ├─ 研发部(10, parent=1)
 * │  ├─ 前端组(100, parent=10)
 * │  └─ 后端组(101, parent=10)
 * └─ 财务部(20, parent=1)
 *    └─ 会计组(200, parent=20)
 * ```
 */
const front: DeptTreeLike = { id: 100, parentId: 10 }
const back: DeptTreeLike = { id: 101, parentId: 10 }
const rd: DeptTreeLike = { id: 10, parentId: 1, children: [front, back] }
const acc: DeptTreeLike = { id: 200, parentId: 20 }
const finance: DeptTreeLike = { id: 20, parentId: 1, children: [acc] }
const root: DeptTreeLike = { id: 1, parentId: null, children: [rd, finance] }

describe('isSelfOrDescendant', () => {
  it('自己算自己', () => {
    expect(isSelfOrDescendant(rd, 10)).toBe(true)
  })

  it('直接子节点算子孙', () => {
    expect(isSelfOrDescendant(rd, 100)).toBe(true)
  })

  it('孙节点也算子孙', () => {
    expect(isSelfOrDescendant(root, 200)).toBe(true)
  })

  it('兄弟节点不算', () => {
    expect(isSelfOrDescendant(rd, 20)).toBe(false)
    expect(isSelfOrDescendant(front, 101)).toBe(false)
  })

  it('祖先不算子孙（方向不能反）', () => {
    expect(isSelfOrDescendant(front, 10)).toBe(false)
    expect(isSelfOrDescendant(front, 1)).toBe(false)
  })
})

describe('canDragDeptNode —— 根节点不可拖', () => {
  it('根（parentId=null）不可拖', () => {
    expect(canDragDeptNode(root)).toBe(false)
  })

  it('非根节点都可拖（层级不限）', () => {
    expect(canDragDeptNode(rd)).toBe(true)
    expect(canDragDeptNode(front)).toBe(true)
    expect(canDragDeptNode(acc)).toBe(true)
  })
})

describe('resolveDropParentId —— 落点语义', () => {
  it('inner：新父 = 落点本身', () => {
    expect(resolveDropParentId(rd, finance, 'inner')).toBe(20)
    expect(resolveDropParentId(front, acc, 'inner')).toBe(200)
  })

  it('prev/next：新父 = 落点的父部门（同级）', () => {
    expect(resolveDropParentId(front, back, 'next')).toBe(10)
    expect(resolveDropParentId(rd, finance, 'prev')).toBe(1)
  })

  it('落到根内部合法（成为根的直接子部门）', () => {
    expect(resolveDropParentId(finance, root, 'inner')).toBe(1)
  })
})

describe('resolveDropParentId —— 必须拒绝的三类', () => {
  it('落到自己身上（三种落点都拒）', () => {
    expect(resolveDropParentId(rd, rd, 'inner')).toBeUndefined()
    expect(resolveDropParentId(rd, rd, 'prev')).toBeUndefined()
    expect(resolveDropParentId(rd, rd, 'next')).toBeUndefined()
  })

  it('落到自己的子孙下会成环 ⇒ 拒绝', () => {
    expect(resolveDropParentId(rd, front, 'inner')).toBeUndefined()
    expect(resolveDropParentId(rd, front, 'prev')).toBeUndefined()
    expect(resolveDropParentId(rd, front, 'next')).toBeUndefined()
    expect(resolveDropParentId(root, acc, 'inner')).toBeUndefined()
  })

  it('落到根的前/后（= 变成根的同级）⇒ 拒绝（数据模型只有单根）', () => {
    expect(resolveDropParentId(rd, root, 'prev')).toBeUndefined()
    expect(resolveDropParentId(rd, root, 'next')).toBeUndefined()
  })
})

describe('canDropDeptNode 与 resolveDropParentId 同源', () => {
  it('合法落点返回 true、非法返回 false', () => {
    expect(canDropDeptNode(rd, finance, 'inner')).toBe(true)
    expect(canDropDeptNode(rd, front, 'inner')).toBe(false)
    expect(canDropDeptNode(rd, root, 'next')).toBe(false)
  })
})

describe('normalizeNodeDropType —— 两套词汇的翻译', () => {
  it('inner 保持 inner', () => {
    expect(normalizeNodeDropType('inner')).toBe('inner')
  })

  it('before → prev（落到目标之前 = 同级）', () => {
    expect(normalizeNodeDropType('before')).toBe('prev')
  })

  it('after → next（落到目标之后 = 同级）', () => {
    expect(normalizeNodeDropType('after')).toBe('next')
  })

  it('翻译后与 allow-drop 的判定同源：拖进内部不会退化成同级', () => {
    // 若把 node-drop 的 'inner' 误当同级处理，这里会得到 1（根）而不是 20（财务部）
    expect(resolveDropParentId(rd, finance, normalizeNodeDropType('inner'))).toBe(20)
    expect(resolveDropParentId(rd, finance, normalizeNodeDropType('before'))).toBe(1)
    expect(resolveDropParentId(rd, finance, normalizeNodeDropType('after'))).toBe(1)
  })
})

describe('deptNameMatches —— 搜索过滤', () => {
  it('空关键词（含纯空白）恒命中', () => {
    expect(deptNameMatches('研发部', '')).toBe(true)
    expect(deptNameMatches('研发部', '   ')).toBe(true)
  })

  it('子串匹配 + 忽略大小写 + 忽略首尾空白', () => {
    expect(deptNameMatches('研发部', '研发')).toBe(true)
    expect(deptNameMatches('IT运维组', 'it')).toBe(true)
    expect(deptNameMatches('IT运维组', ' IT ')).toBe(true)
  })

  it('不命中返回 false', () => {
    expect(deptNameMatches('研发部', '财务')).toBe(false)
  })

  it('null / undefined 名称不炸', () => {
    expect(deptNameMatches(null, '研发')).toBe(false)
    expect(deptNameMatches(undefined, '')).toBe(true)
  })
})
