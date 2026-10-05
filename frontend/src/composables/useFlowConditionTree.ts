import {
  FLOW_OPERATOR_OPTIONS,
  conditionDepth,
  conditionGroup,
  flowConditionMaxDepth,
  flowOperatorRequiresValue,
  isConditionGroup,
  type FlowBranch,
  type FlowCondition,
  type FlowConditionRule,
  type FlowOperatorCode
} from '@/types/approvalFlow'

/**
 * 条件树编辑（M3-A）：按「路径」寻址（ ·  · W4-A3，自 FlowDesigner.vue 抽出）。
 *
 * <h2>为什么是路径而不是下标</h2>
 * 单层条件下「第几条规则」就够用了；带上嵌套组之后，同一份树里会同时存在顶层规则、
 * 组、组内规则、组内组……下标不再能唯一定位一个节点。统一改用**路径**（从条件根出发的
 * 下标序列）：`[]` = 条件根本身（它就是一个「组」），`[0]` = 顶层第 1 项，
 * `[0,1]` = 顶层第 1 项（一个组）里的第 2 项。
 *
 * <h2>不可变改写</h2>
 * 所有编辑都走 {@link replaceNode} / {@link replaceGroup} 这一对纯函数并返回**新的**条件树，
 * 不改动没有被路径经过的兄弟节点 —— 不依赖"就地改完再指望响应式发现"的技巧。
 * 因此本模块的前半段（`conditionNodeAt` 到 `replaceGroup`）是**纯函数**，可脱离 Vue 单测。
 *
 * <h2>写回的唯一出口</h2>
 * `mutateCondition` 是唯一的写回点：它把改写后的条件树交给外部传入的 `updateBranch`
 * （组件侧最终落到 `updateNode`）。本模块不直接触碰 `FlowDefinition`，
 * 也不关心"哪个节点被选中"——那些由调用方（选中态处理器）决定。
 */
export interface FlowConditionTreeOptions {
  /** 定位某条件节点下的某分支（组件侧实现，读当前 modelValue） */
  findBranch: (condKey: string, branchKey: string) => FlowBranch | undefined
  /** 改写某分支（组件侧实现，最终提交新定义） */
  updateBranch: (condKey: string, branchKey: string, patch: Partial<FlowBranch>) => void
  /** 取分支的条件树（else 分支 = 空树） */
  branchCondition: (branch: FlowBranch) => FlowCondition
  /** 枚举类字段的比较值候选（来自展示模块） */
  conditionValueOptions: (fieldKey: string) => Array<{ value: string; label: string }>
}

/** 条件节点路径：从条件根出发的下标序列 */
export type ConditionPath = number[]

/** 条件树摊平后的一行（模板只渲染字符串，避免在模板里做类型收窄） */
export interface ConditionRowView {
  key: string
  path: ConditionPath
  /** 缩进层级：顶层 1 */
  depth: number
  group: boolean
  logic: 'AND' | 'OR'
  field: string
  op: FlowOperatorCode | null
  value: string
  valueOptions: Array<{ value: string; label: string }>
  requiresValue: boolean
  canAddGroup: boolean
  addGroupHint: string
}

export interface FlowConditionTreeApi {
  emptyRule: () => FlowConditionRule
  emptyGroup: () => FlowConditionRule
  conditionNodeAt: (root: FlowCondition, path: ConditionPath) => FlowConditionRule | null
  groupAt: (root: FlowCondition, path: ConditionPath) => FlowCondition | null
  replaceNode: (
    root: FlowCondition,
    path: ConditionPath,
    transform: (node: FlowConditionRule) => FlowConditionRule | null
  ) => FlowCondition
  replaceGroup: (root: FlowCondition, path: ConditionPath, next: FlowCondition) => FlowCondition
  mutateCondition: (
    condKey: string,
    branchKey: string,
    mutate: (root: FlowCondition) => FlowCondition
  ) => void
  appendRule: (condKey: string, branchKey: string, path: ConditionPath) => void
  appendSubGroup: (condKey: string, branchKey: string, path: ConditionPath) => void
  removeNodeAt: (condKey: string, branchKey: string, path: ConditionPath) => void
  patchRuleAt: (
    condKey: string,
    branchKey: string,
    path: ConditionPath,
    patch: Partial<FlowConditionRule>
  ) => void
  setGroupLogicAt: (
    condKey: string,
    branchKey: string,
    path: ConditionPath,
    value: unknown
  ) => void
  changeOperatorAt: (
    condKey: string,
    branchKey: string,
    path: ConditionPath,
    value: unknown
  ) => void
  canAppendSubGroup: (root: FlowCondition, path: ConditionPath) => boolean
  subGroupHint: (root: FlowCondition, path: ConditionPath) => string
  conditionRowsOf: (branch: FlowBranch) => ConditionRowView[]
}

export function useFlowConditionTree(
  options: FlowConditionTreeOptions
): FlowConditionTreeApi {
  /** 新建一条空白规则（缺字段会被发布预检拦下，但那是保存时的事，不是编辑中的事） */
  function emptyRule(): FlowConditionRule {
    return { field: '', op: 'EQ' }
  }

  /** 新建一个含一条空白规则的嵌套组：**刻意不建成空组**，否则会先报「嵌套条件组为空」 */
  function emptyGroup(): FlowConditionRule {
    return conditionGroup({ logic: 'AND', rules: [emptyRule()] })
  }

  /** 取路径指向的节点；`[]` 返回 null（条件根本身不是一个"节点"） */
  function conditionNodeAt(root: FlowCondition, path: ConditionPath): FlowConditionRule | null {
    let node: FlowConditionRule | null = null
    let cursor: FlowCondition | null | undefined = root
    for (const index of path) {
      const current: FlowConditionRule | undefined = (cursor?.rules ?? [])[index]
      if (!current) {
        return null
      }
      node = current
      cursor = current.condition
    }
    return node
  }

  /** 取路径指向的**组**：`[]` = 条件根；否则 = 该组信封的 condition */
  function groupAt(root: FlowCondition, path: ConditionPath): FlowCondition | null {
    if (path.length === 0) {
      return root
    }
    const node = conditionNodeAt(root, path)
    return node && isConditionGroup(node) ? node.condition ?? null : null
  }

  /** 替换 / 删除路径指向的节点（transform 返回 null 即删除），返回新的条件根 */
  function replaceNode(
    root: FlowCondition,
    path: ConditionPath,
    transform: (node: FlowConditionRule) => FlowConditionRule | null
  ): FlowCondition {
    const [head, ...rest] = path
    const nextRules: FlowConditionRule[] = []
    const rules = root.rules ?? []
    rules.forEach((rule, index) => {
      if (index !== head) {
        nextRules.push(rule)
        return
      }
      if (rest.length === 0) {
        const replaced = transform(rule)
        if (replaced) {
          nextRules.push(replaced)
        }
        return
      }
      const inner = rule.condition ?? { logic: 'AND' as const, rules: [] }
      nextRules.push({ ...rule, condition: replaceNode(inner, rest, transform) })
    })
    return { ...root, rules: nextRules }
  }

  /** 把路径指向的组整体替换为 next（`[]` = 替换条件根） */
  function replaceGroup(
    root: FlowCondition,
    path: ConditionPath,
    next: FlowCondition
  ): FlowCondition {
    if (path.length === 0) {
      return next
    }
    return replaceNode(root, path, (node) => ({ ...node, condition: next }))
  }

  /** 对某分支的条件树做一次不可变改写 */
  function mutateCondition(
    condKey: string,
    branchKey: string,
    mutate: (root: FlowCondition) => FlowCondition
  ): void {
    const branch = options.findBranch(condKey, branchKey)
    if (!branch) {
      return
    }
    options.updateBranch(condKey, branchKey, {
      condition: mutate(options.branchCondition(branch))
    })
  }

  /** 往路径指向的组里追加一条空白规则（`[]` = 条件根） */
  function appendRule(condKey: string, branchKey: string, path: ConditionPath): void {
    mutateCondition(condKey, branchKey, (root) => {
      const group = groupAt(root, path)
      return group
        ? replaceGroup(root, path, { ...group, rules: [...(group.rules ?? []), emptyRule()] })
        : root
    })
  }

  /** 往路径指向的组里追加一个嵌套组 */
  function appendSubGroup(condKey: string, branchKey: string, path: ConditionPath): void {
    mutateCondition(condKey, branchKey, (root) => {
      const group = groupAt(root, path)
      return group
        ? replaceGroup(root, path, { ...group, rules: [...(group.rules ?? []), emptyGroup()] })
        : root
    })
  }

  /** 删除路径指向的节点（`[]` 无意义：条件根本身不能删，删分支才是那个操作） */
  function removeNodeAt(condKey: string, branchKey: string, path: ConditionPath): void {
    if (path.length === 0) {
      return
    }
    mutateCondition(condKey, branchKey, (root) => replaceNode(root, path, () => null))
  }

  /** 改写路径指向的**单条规则**（组信封不接受 patch：它没有 field/op/value 可改） */
  function patchRuleAt(
    condKey: string,
    branchKey: string,
    path: ConditionPath,
    patch: Partial<FlowConditionRule>
  ): void {
    if (path.length === 0) {
      return
    }
    mutateCondition(condKey, branchKey, (root) =>
      replaceNode(root, path, (node) => (isConditionGroup(node) ? node : { ...node, ...patch }))
    )
  }

  /** 切换路径指向的组内逻辑（`[]` = 条件根） */
  function setGroupLogicAt(
    condKey: string,
    branchKey: string,
    path: ConditionPath,
    value: unknown
  ): void {
    const logic = value === 'OR' ? 'OR' : 'AND'
    mutateCondition(condKey, branchKey, (root) => {
      const group = groupAt(root, path)
      return group ? replaceGroup(root, path, { ...group, logic }) : root
    })
  }

  /** 切换运算符时清空比较值："为空/不为空"不需要值，数值与文本的值类型也不通用 */
  function changeOperatorAt(
    condKey: string,
    branchKey: string,
    path: ConditionPath,
    value: unknown
  ): void {
    const op = String(value) as FlowOperatorCode
    if (!FLOW_OPERATOR_OPTIONS.some((option) => option.value === op)) {
      return
    }
    patchRuleAt(condKey, branchKey, path, {
      op,
      value: flowOperatorRequiresValue(op) ? '' : null
    })
  }

  /**
   * 在路径指向的组下面还能不能再嵌一层组？
   *
   * 判据不是"数一下当前层号"，而是**先把组加上去、再量整棵树的深度** ——
   * 深度定义里「最外层算第 1 层」与路径长度不是同一个数量，手写换算最容易错一位，
   * 而错一位的表现是"设计器允许加、后端拒绝发布"。树只有几个节点，重建一次可忽略。
   */
  function canAppendSubGroup(root: FlowCondition, path: ConditionPath): boolean {
    const group = groupAt(root, path)
    if (!group) {
      return false
    }
    const prospective = replaceGroup(root, path, {
      ...group,
      rules: [...(group.rules ?? []), emptyGroup()]
    })
    return conditionDepth(prospective) <= flowConditionMaxDepth()
  }

  /** 「添加条件组」按钮的提示文案（上限只有一个读取入口：flowConditionMaxDepth） */
  function subGroupHint(root: FlowCondition, path: ConditionPath): string {
    const maxDepth = flowConditionMaxDepth()
    return canAppendSubGroup(root, path)
      ? `在「条件组」内再嵌一组（最多 ${maxDepth} 层）`
      : `已达嵌套上限（最多 ${maxDepth} 层）：再嵌一层将无法发布`
  }

  /** 把某分支的条件树摊平成可渲染的行；组的逻辑开关跟在**组自己的行**上 */
  function conditionRowsOf(branch: FlowBranch): ConditionRowView[] {
    const root = options.branchCondition(branch)
    const rows: ConditionRowView[] = []
    const walk = (group: FlowCondition, prefix: ConditionPath, depth: number): void => {
      const rules = group.rules ?? []
      rules.forEach((rule, index) => {
        const path = [...prefix, index]
        if (isConditionGroup(rule)) {
          rows.push({
            key: `g-${path.join('-')}`,
            path,
            depth,
            group: true,
            logic: rule.condition?.logic === 'OR' ? 'OR' : 'AND',
            field: '',
            op: null,
            value: '',
            valueOptions: [],
            requiresValue: false,
            canAddGroup: canAppendSubGroup(root, path),
            addGroupHint: subGroupHint(root, path)
          })
          if (rule.condition) {
            walk(rule.condition, path, depth + 1)
          }
          return
        }
        const op = rule.op ?? null
        const requires = op != null && flowOperatorRequiresValue(op)
        rows.push({
          key: `r-${path.join('-')}`,
          path,
          depth,
          group: false,
          logic: 'AND',
          field: rule.field ?? '',
          op,
          value: rule.value == null ? '' : String(rule.value),
          valueOptions: requires && rule.field ? options.conditionValueOptions(rule.field) : [],
          requiresValue: requires,
          canAddGroup: false,
          addGroupHint: ''
        })
      })
    }
    walk(root, [], 1)
    return rows
  }

  return {
    emptyRule,
    emptyGroup,
    conditionNodeAt,
    groupAt,
    replaceNode,
    replaceGroup,
    mutateCondition,
    appendRule,
    appendSubGroup,
    removeNodeAt,
    patchRuleAt,
    setGroupLogicAt,
    changeOperatorAt,
    canAppendSubGroup,
    subGroupHint,
    conditionRowsOf
  }
}
