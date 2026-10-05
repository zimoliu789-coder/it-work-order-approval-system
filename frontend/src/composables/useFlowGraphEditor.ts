import { computed, ref, type ComputedRef, type Ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type {
  FlowBranch,
  FlowDefinition,
  FlowNode,
  FlowNodeTypeCode
} from '@/types/approvalFlow'
import type { NodeRef } from '@/composables/useFlowCanvas'

/**
 * 流程结构编辑（ ·  · W4-A3，自 {@code FlowDesigner.vue} 抽出）。
 *
 * <h2>唯一写入口：`commit`</h2>
 * 所有结构变更都必须通过 {@link FlowGraphEditorApi.commit} 产出新的
 * {@link FlowDefinition} 并向外 emit —— 本模块<b>不做任何就地修改</b>，
 * 每次都是"读旧对象 → 造新对象 → 提交"。这样父组件拿到的一定是新引用，
 * `v-model` 的下游（脏标记 / 保存按钮可用性）不会因为"改了同一份对象"而漏判。
 *
 * <h2>为什么 `readonly` 要逐个方法判、而不是在外层统一拦</h2>
 * 只读模式（仅 `approval_flow:view` 的角色）下前端**不能**靠隐藏按钮保底：
 * 事件处理器仍可能被程序化触发。因此每个真正改写节点的入口
 * （`addNodeAt` / `insertNodeAfter` / `removeNode` / `confirmRemoveNode` / `updateNode`）
 * 首行 self-check，与后端 `approval_flow:manage` 的拦截形成两道门。
 *
 * <p><b>已知的未闭合点</b>：`chooseExisting` 沿用原实现，没有这层 self-check
 * （它只可能从"指向已有节点"对话框的确定按钮触发，而该入口在只读模式下不渲染）。
 * 属于纵深防御的缺口而非可达路径，随  收口一并评估。
 *
 * <h2>「指向已有节点」为什么算结构编辑</h2>
 * 它做的是 `linkRef(nodes, ref, 已有节点key)` —— 与"新增节点"是同一件事
 * （把某个挂载点接到某节点上），只是目标不是新建的。因此两者同属本模块，
 * 共用同一套挂载点语义，避免"新增会连线、指向已有不会"这类分裂。
 */
export interface FlowGraphEditorOptions {
  /** 只读模式（仅查看权限） */
  readonly: () => boolean
  /** 当前流程定义（getter，保持响应式） */
  modelValue: () => FlowDefinition
  nodeList: ComputedRef<FlowNode[]>
  nodeIndex: ComputedRef<Map<string, FlowNode>>
  /** 当前选中节点 key（删除被选中节点时需清空） */
  selectedKey: Ref<string | null>
  /** 选中某节点（新增后自动选中，便于立刻配置） */
  select: (nodeKey: string) => void
  /** 提交新的流程定义（组件侧通常是 emit('update:modelValue', def)） */
  commitUpdate: (definition: FlowDefinition) => void
}

export interface FlowGraphEditorApi {
  commit: (nodes: FlowNode[], start?: string) => void
  updateNode: (nodeKey: string, patch: Partial<FlowNode>) => void
  addNodeAt: (ref: NodeRef, type: FlowNodeTypeCode) => void
  insertNodeAfter: (nodeKey: string, type: FlowNodeTypeCode) => void
  handleAddCommand: (ref: NodeRef, command: string) => void
  confirmRemoveNode: (nodeKey: string) => Promise<void>
  uniqueBranchKey: (node: FlowNode) => string
  existingDialogVisible: Ref<boolean>
  existingTarget: Ref<NodeRef | null>
  existingPick: Ref<string | null>
  existingCandidates: ComputedRef<Array<{ key: string; name: string; type: FlowNodeTypeCode }>>
  chooseExisting: () => void
}

export function useFlowGraphEditor(options: FlowGraphEditorOptions): FlowGraphEditorApi {
  const { nodeList, nodeIndex, selectedKey } = options

  function commit(nodes: FlowNode[], start?: string): void {
    options.commitUpdate({
      start: start === undefined ? (options.modelValue().start ?? '') : start,
      nodes
    })
  }

  function uniqueNodeKey(): string {
    const used = new Set(nodeList.value.map((node) => node.key))
    let seq = 1
    let key = `n${seq}`
    while (used.has(key)) {
      seq += 1
      key = `n${seq}`
    }
    return key
  }

  function uniqueBranchKey(node: FlowNode): string {
    const used = new Set((node.branches ?? []).map((branch) => branch.key))
    let seq = 1
    let key = `b${seq}`
    while (used.has(key)) {
      seq += 1
      key = `b${seq}`
    }
    return key
  }

  /** 新建条件节点时给两个出口：一条带条件、一条默认出口（保证"恰好一个 else"） */
  function defaultBranches(): FlowBranch[] {
    return [
      {
        key: 'b1',
        name: '满足条件',
        else: false,
        condition: { logic: 'AND', rules: [{ field: '', op: 'EQ' }] },
        next: null
      },
      { key: 'b2', name: '其它情况', else: true, next: null }
    ]
  }

  function createNode(type: FlowNodeTypeCode): FlowNode {
    const key = uniqueNodeKey()
    if (type === 'APPROVAL') {
      const approvalCount = nodeList.value.filter((node) => node.type === 'APPROVAL').length
      return {
        key,
        type,
        name: `审批节点${approvalCount + 1}`,
        signType: 'ANY_SIGN',
        approverRules: [{ type: 'SPECIFIC_USER', userIds: [] }],
        // 时限刻意留空（= 不限时）：新节点默认不产生超时提醒，需要限时的人再去配，
        // 比默认给一个 24 小时、然后每个流程都要记得删掉更安全
        timeLimitHours: null,
        next: null
      }
    }
    if (type === 'CC') {
      const ccCount = nodeList.value.filter((node) => node.type === 'CC').length
      return {
        key,
        type,
        name: `抄送节点${ccCount + 1}`,
        // 不带 signType：抄送没有或签/会签的概念，带了会被发布校验拒绝
        approverRules: [{ type: 'ROLE', roleCode: '' }],
        next: null
      }
    }
    if (type === 'CONDITION') {
      return { key, type, name: '条件分支', branches: defaultBranches() }
    }
    return { key, type, name: '结束', next: null }
  }

  /** 把某个挂载点指向目标节点（null = 断开） */
  function linkRef(nodes: FlowNode[], ref: NodeRef, target: string | null): FlowNode[] {
    if (ref.kind === 'start') {
      // start 由 commit 的第二个参数处理；此处不涉及 nodes
      return nodes
    }
    return nodes.map((node) => {
      if (node.key !== ref.nodeKey) {
        return node
      }
      if (ref.kind === 'node') {
        return { ...node, next: target }
      }
      return {
        ...node,
        branches: (node.branches ?? []).map((branch) =>
          branch.key === ref.branchKey ? { ...branch, next: target } : branch
        )
      }
    })
  }

  /** 在挂载点放一个新节点 */
  function addNodeAt(ref: NodeRef, type: FlowNodeTypeCode): void {
    if (options.readonly()) {
      return
    }
    const node = createNode(type)
    const nodes = [...nodeList.value, node]
    if (ref.kind === 'start') {
      commit(nodes, node.key)
    } else {
      commit(linkRef(nodes, ref, node.key))
    }
    options.select(node.key)
  }

  /** 在既有节点下方插入（新节点接管原 next，原节点改指向新节点） */
  function insertNodeAfter(nodeKey: string, type: FlowNodeTypeCode): void {
    if (options.readonly()) {
      return
    }
    const target = nodeIndex.value.get(nodeKey)
    if (!target) {
      return
    }
    const node = createNode(type)
    // END 没有 next；其余类型（含 CC）接管原节点的 next
    node.next = type === 'END' ? null : (target.next ?? null)
    const nodes = nodeList.value.map((item) =>
      item.key === nodeKey ? { ...item, next: node.key } : item
    )
    nodes.push(node)
    commit(nodes)
    options.select(node.key)
  }

  // ----------------------------------------------------------------------
  // "指向已有节点"（支持分支汇合）
  // ----------------------------------------------------------------------

  const existingDialogVisible = ref(false)
  const existingTarget = ref<NodeRef | null>(null)
  const existingPick = ref<string | null>(null)

  const existingCandidates = computed(() =>
    nodeList.value.map((node) => ({ key: node.key, name: node.name, type: node.type }))
  )

  function handleAddCommand(ref: NodeRef, command: string): void {
    if (command === 'EXISTING') {
      existingTarget.value = ref
      existingDialogVisible.value = true
      return
    }
    if (command === 'APPROVAL' || command === 'CC' || command === 'CONDITION' || command === 'END') {
      addNodeAt(ref, command)
    }
  }

  function chooseExisting(): void {
    const ref = existingTarget.value
    if (!ref || !existingPick.value) {
      ElMessage.warning('请选择一个节点')
      return
    }
    commit(linkRef(nodeList.value, ref, existingPick.value))
    existingDialogVisible.value = false
    existingPick.value = null
    existingTarget.value = null
  }

  // ----------------------------------------------------------------------
  // 删除
  // ----------------------------------------------------------------------

  function removeNode(nodeKey: string): void {
    if (options.readonly()) {
      return
    }
    const target = nodeIndex.value.get(nodeKey)
    if (!target) {
      return
    }
    const successor = target.next ?? null
    const nodes = nodeList.value
      .filter((node) => node.key !== nodeKey)
      .map((node) => {
        let result = node
        if (result.next === nodeKey) {
          result = { ...result, next: successor }
        }
        if (result.type === 'CONDITION') {
          result = {
            ...result,
            branches: (result.branches ?? []).map((branch) =>
              branch.next === nodeKey ? { ...branch, next: successor } : branch
            )
          }
        }
        return result
      })
    const start = options.modelValue().start === nodeKey ? (successor ?? '') : undefined
    commit(nodes, start)
    if (selectedKey.value === nodeKey) {
      selectedKey.value = null
    }
  }

  async function confirmRemoveNode(nodeKey: string): Promise<void> {
    if (options.readonly()) {
      return
    }
    const target = nodeIndex.value.get(nodeKey)
    const label = target?.name || nodeKey
    try {
      await ElMessageBox.confirm(
        `确认删除节点「${label}」？其下游节点可能因此变为"未接入流程"。`,
        '删除节点',
        { type: 'warning' }
      )
    } catch {
      return
    }
    removeNode(nodeKey)
  }

  // ----------------------------------------------------------------------
  // 节点属性
  // ----------------------------------------------------------------------

  function updateNode(nodeKey: string, patch: Partial<FlowNode>): void {
    if (options.readonly()) {
      return
    }
    commit(nodeList.value.map((node) => (node.key === nodeKey ? { ...node, ...patch } : node)))
  }

  return {
    commit,
    updateNode,
    addNodeAt,
    insertNodeAfter,
    handleAddCommand,
    confirmRemoveNode,
    uniqueBranchKey,
    existingDialogVisible,
    existingTarget,
    existingPick,
    existingCandidates,
    chooseExisting
  }
}
