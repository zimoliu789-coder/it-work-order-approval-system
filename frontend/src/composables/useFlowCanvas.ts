import { computed, ref, type ComputedRef, type Ref } from 'vue'
import type { FlowBranch, FlowDefinition, FlowNode } from '@/types/approvalFlow'

/**
 * 画布渲染（DFS 展开）+ 选中态（ ·  · W4-A3）。
 *
 * <h2>为什么画布按 DFS 展开而不是平铺所有节点</h2>
 * 流程是 DAG：条件分支会**汇合**（回归用例里 `n2.next` 与 `b2.next` 都指向 `n3`）。
 * 平铺无法表达"从哪来、到哪去"；DFS 展开成树则一眼看清路径，而**汇合点第二次出现时
 * 渲染成「汇合到 …」引用块**，既不重复展示，也诚实标出这是一次汇合。
 *
 * <h2>纯派生：本模块不写 modelValue</h2>
 * 三个 computed 只读 {@link useFlowCanvas} 收到的 `modelValue()`，不产生任何写入 ——
 * 因此它可以在任何"只想预览流程结构"的地方复用（例如只读查看），而不会被拖进编辑链路。
 */

/** 一个"挂载点"：起始 / 某审批节点的 next / 某条件节点的某分支 next */
export type NodeRef =
  | { kind: 'start' }
  | { kind: 'node'; nodeKey: string }
  | { kind: 'branch'; nodeKey: string; branchKey: string }

export interface RenderCard {
  kind: 'node'
  depth: number
  key: string
  node: FlowNode
}
export interface RenderBranchItem {
  kind: 'branch'
  depth: number
  nodeKey: string
  branch: FlowBranch
  branchIndex: number
}
export interface RenderJumpItem {
  kind: 'jump'
  depth: number
  name: string
}
export interface RenderAddItem {
  kind: 'add'
  depth: number
  ref: NodeRef
}
export interface RenderMissingItem {
  kind: 'missing'
  depth: number
  text: string
}
export type RenderItem =
  | RenderCard
  | RenderBranchItem
  | RenderJumpItem
  | RenderAddItem
  | RenderMissingItem

export interface FlowCanvasApi {
  nodeList: ComputedRef<FlowNode[]>
  nodeIndex: ComputedRef<Map<string, FlowNode>>
  canvas: ComputedRef<{ items: RenderItem[]; orphans: FlowNode[] }>
  selectedKey: Ref<string | null>
  selectedBranchKey: Ref<string | null>
  selectedNode: ComputedRef<FlowNode | null>
  /** 选中某节点（条件节点可附带选中的分支）；不负责切面板 —— 那属于视图层策略 */
  select: (nodeKey: string, branchKey?: string) => void
}

/**
 * @param modelValue 当前流程定义（用 getter 传入，保持响应式且不复制数据）
 */
export function useFlowCanvas(modelValue: () => FlowDefinition): FlowCanvasApi {
  const nodeList = computed<FlowNode[]>(() => modelValue().nodes ?? [])

  const nodeIndex = computed<Map<string, FlowNode>>(
    () => new Map(nodeList.value.map((node) => [node.key, node]))
  )

  /**
   * 画布渲染项 + 孤岛节点。
   *
   * 孤岛 = 从 start 出发 DFS 走不到的节点。编辑过程中若把条件节点整个删掉，
   * 它的支链会变成孤岛 —— 这里如实列出来（而不是让它们从画布上"静默消失"），
   * 因为发布校验会因"不可达"直接拒绝，配置者必须有机会看到并处理它们。
   */
  const canvas = computed<{ items: RenderItem[]; orphans: FlowNode[] }>(() => {
    const nodes = nodeList.value
    const items: RenderItem[] = []
    const visited = new Set<string>()

    const walk = (from: string | null | undefined, depth: number): void => {
      let current = from ?? null
      while (current) {
        const node = nodeIndex.value.get(current)
        if (!node) {
          items.push({ kind: 'missing', depth, text: current })
          return
        }
        if (visited.has(current)) {
          items.push({ kind: 'jump', depth, name: node.name || current })
          return
        }
        visited.add(current)
        const nodeKey = current
        items.push({ kind: 'node', depth, key: nodeKey, node })

        if (node.type === 'CONDITION') {
          ;(node.branches ?? []).forEach((branch, branchIndex) => {
            items.push({ kind: 'branch', depth: depth + 1, nodeKey, branch, branchIndex })
            if (branch.next) {
              walk(branch.next, depth + 2)
            } else {
              items.push({
                kind: 'add',
                depth: depth + 2,
                ref: { kind: 'branch', nodeKey, branchKey: branch.key }
              })
            }
          })
          return
        }
        if (node.type === 'END') {
          return
        }
        // APPROVAL：继续沿 next 前进
        if (node.next) {
          current = node.next
        } else {
          items.push({ kind: 'add', depth: depth + 1, ref: { kind: 'node', nodeKey } })
          return
        }
      }
      // 走到这里代表 from 为空（尚未设置起始节点）
      items.push({ kind: 'add', depth, ref: { kind: 'start' } })
    }

    walk(modelValue().start, 0)
    const orphans = nodes.filter((node) => !visited.has(node.key))
    return { items, orphans }
  })

  // ----------------------------------------------------------------------
  // 选中态
  // ----------------------------------------------------------------------

  const selectedKey = ref<string | null>(null)
  const selectedBranchKey = ref<string | null>(null)

  const selectedNode = computed<FlowNode | null>(() =>
    selectedKey.value == null ? null : (nodeIndex.value.get(selectedKey.value) ?? null)
  )

  function select(nodeKey: string, branchKey?: string): void {
    selectedKey.value = nodeKey
    selectedBranchKey.value = branchKey ?? null
  }

  return { nodeList, nodeIndex, canvas, selectedKey, selectedBranchKey, selectedNode, select }
}
