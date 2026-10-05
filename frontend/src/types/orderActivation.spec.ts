import { describe, expect, it } from 'vitest'
import {
  approvalNodeTagType,
  countInactiveNodes,
  isInactiveNode,
  visibleApprovalNodes,
  type ApprovalNodeStatusCode
} from '@/types/order'

/**
 *  —— 激活史展示层（未激活节点的可见性）
 *
 * <h2>为什么不只是"少画几行"</h2>
 * 引入了 `INACTIVE`（未激活）这个状态后，快照里会多出一批**尚未发生**的节点。
 * 直接原样渲染会造成两类真实的误读：
 * <ol>
 *   <li>申请人看到「未激活」的后续节点，以为自己的单子已经排到了那里、正在等某人处理；
 *       实际连"要不要走这一步"都还没判定。</li>
 *   <li>`INACTIVE` 与 `SKIPPED` 视觉上混同 —— 但前者是"还没走到"，后者是"走过、条件没命中"，
 *       是**历史事实**与**未来可能**之别。二者若都弱化成同一种灰，激活史就失去了信息量。</li>
 * </ol>
 * 因此默认隐藏 `INACTIVE`，仅管理角色可展开完整骨架；灰度的**强度**也刻意区分
 * （`INACTIVE` 最弱 + 虚线框，`SKIPPED` 次弱）。
 */

function node(status: string, extra: Record<string, unknown> = {}) {
  return { id: Math.random(), status: status as ApprovalNodeStatusCode, ...extra }
}

describe('M2 激活史：未激活节点判定', () => {
  it('isInactiveNode 只认 INACTIVE', () => {
    expect(isInactiveNode(node('INACTIVE'))).toBe(true)
    expect(isInactiveNode(node('PENDING'))).toBe(false)
    expect(isInactiveNode(node('SKIPPED'))).toBe(false)
  })

  it('未激活节点用灰色待定样式，不能与 PENDING(警告色) 混同', () => {
    // 若这里返回 warning，用户会把"还没轮到"读成"轮到我但我没处理"
    expect(approvalNodeTagType('INACTIVE')).toBe('info')
    expect(approvalNodeTagType('PENDING')).toBe('warning')
    expect(approvalNodeTagType('SKIPPED')).toBe('info')
  })

  it('抄送终态用成功色（已送达），而不是与"已通过"同义的危险/警告色', () => {
    expect(approvalNodeTagType('CC_NOTIFIED')).toBe('success')
  })
})

describe('M2 激活史：默认隐藏 / 展开骨架', () => {
  const nodes = [
    node('APPROVED', { id: 1 }),
    node('PENDING', { id: 2 }),
    node('INACTIVE', { id: 3 }),
    node('INACTIVE', { id: 4 }),
    node('SKIPPED', { id: 5 })
  ]

  it('默认视图滤掉全部 INACTIVE，保留历史事实（含 SKIPPED）', () => {
    const visible = visibleApprovalNodes(nodes, false)
    expect(visible.map((item) => item.id)).toEqual([1, 2, 5])
  })

  it('展开骨架时保留原顺序（服务端返回顺序即展示顺序，不得被过滤打乱）', () => {
    const visible = visibleApprovalNodes(nodes, true)
    expect(visible.map((item) => item.id)).toEqual([1, 2, 3, 4, 5])
  })

  it('countInactiveNodes 给出开关文案里的数量', () => {
    expect(countInactiveNodes(nodes)).toBe(2)
  })

  it('空 / null 快照不抛异常（详情未加载完成时即会被调用）', () => {
    expect(visibleApprovalNodes(null, false)).toEqual([])
    expect(visibleApprovalNodes(undefined, true)).toEqual([])
    expect(countInactiveNodes(null)).toBe(0)
  })

  it('过滤不修改入参（快照是响应式数据，就地改动会污染后续渲染）', () => {
    const raw = [node('INACTIVE', { id: 9 }), node('PENDING', { id: 10 })]
    visibleApprovalNodes(raw, false)
    expect(raw.length).toBe(2)
    expect(countInactiveNodes(raw)).toBe(1)
  })
})
