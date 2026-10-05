import { describe, expect, it } from 'vitest'
import { ref } from 'vue'
import { useFlowCanvas, type RenderCard } from '@/composables/useFlowCanvas'
import type { FlowDefinition } from '@/types/approvalFlow'

/**
 * 画布 DFS 展开单测（ ·  · W4-A3）。
 *
 * <p>这些行为原先只能"挂载整个 FlowDesigner 再断言 DOM"才能覆盖。抽出
 * {@link useFlowCanvas} 之后可以直接对着渲染项列表断言 —— 尤其是下面这几条
 * **DAG 相对树最容易写错**的分支：
 * <ul>
 *   <li>汇合点第一次全量渲染、第二次渲染成「汇合到 …」引用（不是重复渲染、也不是丢节点）；</li>
 *   <li>next 指向不存在的 key → 断链提示，而不是静默截断；</li>
 *   <li>从 start 走不到的节点 → 孤岛，必须如实列出（发布校验会因"不可达"拒绝）；</li>
 *   <li>挂载点：主干尾部 / 分支尾部 / 空流程各产生一个「+」入口。</li>
 * </ul>
 */
function flow(): FlowDefinition {
  return {
    start: 'n1',
    nodes: [
      { key: 'n1', type: 'APPROVAL', name: '主管审批', next: 'c1' },
      {
        key: 'c1',
        type: 'CONDITION',
        name: '金额判断',
        branches: [
          { key: 'b1', name: '大于 5000', next: 'n2' },
          { key: 'b2', name: '其它情况', else: true, next: 'n3' }
        ]
      },
      { key: 'n2', type: 'APPROVAL', name: '财务复核', next: 'n3' },
      { key: 'n3', type: 'APPROVAL', name: '归档确认', next: 'end' },
      { key: 'end', type: 'END', name: '结束' }
    ]
  }
}

/** 只取节点渲染项（判别联合需要显式守卫，否则拿不到 `key`） */
function nodeKeys(items: ReturnType<typeof useFlowCanvas>['canvas']['value']['items']): string[] {
  return items.filter((item): item is RenderCard => item.kind === 'node').map((item) => item.key)
}

describe('useFlowCanvas', () => {
  it('按 DFS 展开主干与两个分支，汇合点第二次出现时渲染为「汇合到」引用', () => {
    const { canvas } = useFlowCanvas(flow)
    const items = canvas.value.items

    expect(nodeKeys(items)).toEqual(['n1', 'c1', 'n2', 'n3', 'end'])

    // n3 由 b1 支链首次到达，b2 支链到达时已是第二次 → jump
    const jumps = items.filter((item) => item.kind === 'jump')
    expect(jumps).toHaveLength(1)
    expect(jumps[0]).toMatchObject({ kind: 'jump', name: '归档确认' })

    // 汇合块必须出现在首次展示之后：否则读起来像"又有一条新路径"
    const firstN3 = items.findIndex((item) => item.kind === 'node' && item.key === 'n3')
    const jumpIndex = items.findIndex((item) => item.kind === 'jump')
    expect(firstN3).toBeGreaterThanOrEqual(0)
    expect(jumpIndex).toBeGreaterThan(firstN3)
  })

  it('两个分支都已接线时不产生额外挂载点；主干尾部断开会得到一个挂载点', () => {
    const { canvas } = useFlowCanvas(flow)
    expect(canvas.value.items.filter((item) => item.kind === 'add')).toHaveLength(0)

    const loose = flow()
    loose.nodes = loose.nodes
      .filter((node) => node.key !== 'end')
      .map((node) => (node.key === 'n3' ? { ...node, next: null } : node))
    const { canvas: looseCanvas } = useFlowCanvas(() => loose)
    const adds = looseCanvas.value.items.filter((item) => item.kind === 'add')
    expect(adds).toHaveLength(1)
    expect(adds[0]).toMatchObject({ kind: 'add', ref: { kind: 'node', nodeKey: 'n3' } })
  })

  it('空流程只产出一个「从起始节点开始」的挂载点', () => {
    const { canvas } = useFlowCanvas(() => ({ start: '', nodes: [] }))
    expect(canvas.value.items).toHaveLength(1)
    expect(canvas.value.items[0]).toMatchObject({ kind: 'add', ref: { kind: 'start' } })
    expect(canvas.value.orphans).toEqual([])
  })

  it('next 指向不存在的节点时给出断链提示，而不是静默截断', () => {
    const broken: FlowDefinition = {
      start: 'n1',
      nodes: [{ key: 'n1', type: 'APPROVAL', name: '主管审批', next: 'ghost' }]
    }
    const { canvas } = useFlowCanvas(() => broken)
    const missing = canvas.value.items.filter((item) => item.kind === 'missing')
    expect(missing).toHaveLength(1)
    expect(missing[0]).toMatchObject({ kind: 'missing', text: 'ghost' })
  })

  it('从 start 走不到的节点被列为孤岛（如实暴露，不静默消失）', () => {
    const withOrphan = flow()
    withOrphan.nodes = [
      ...withOrphan.nodes,
      { key: 'n9', type: 'APPROVAL', name: '游离节点', next: null }
    ]
    const { canvas } = useFlowCanvas(() => withOrphan)
    expect(canvas.value.orphans.map((node) => node.key)).toEqual(['n9'])
  })

  it('选中态：select 同时记录节点与分支；取消选中后 selectedNode 为 null', () => {
    const { selectedKey, selectedBranchKey, selectedNode, select } = useFlowCanvas(flow)

    expect(selectedNode.value).toBeNull()

    select('c1', 'b2')
    expect(selectedKey.value).toBe('c1')
    expect(selectedBranchKey.value).toBe('b2')
    expect(selectedNode.value?.name).toBe('金额判断')

    // 切换到别的节点时不带分支参数 → 旧的分支选中必须被清掉，
    // 否则条件面板会停留在上一个条件节点的分支上
    select('n1')
    expect(selectedBranchKey.value).toBeNull()

    select('ghost')
    expect(selectedNode.value).toBeNull()
  })

  it('画布随传入的 modelValue 变化重算（getter 的响应式不被切断）', () => {
    const current = ref<FlowDefinition>(flow())
    const { canvas } = useFlowCanvas(() => current.value)
    expect(nodeKeys(canvas.value.items)).toHaveLength(5)

    current.value = { start: 'n1', nodes: [{ key: 'n1', type: 'END', name: '结束' }] }
    expect(nodeKeys(canvas.value.items)).toEqual(['n1'])
  })
})
