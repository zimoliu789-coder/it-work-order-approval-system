import {
  APPROVER_RULE_TYPE_OPTIONS,
  type ApproverRule,
  type FlowConditionRule,
  type FlowDefinition,
  type FlowNode,
  type FlowOperatorCode
} from '@/types/approvalFlow'

/**
 * 钉钉式「线性审批流程」的**转换内核**（ 项目 5）
 *
 * <h2>为什么把它从组件里抽出来</h2>
 * 「行模型 ↔ 标准 FlowDefinition」是一对**纯函数**，与 DOM 无关。
 * 留在组件里就只能靠挂载组件来测，而这类转换的 bug（少一个分支、条件值当字符串比较、
 * 合并点接错）在界面上**看不出来**，只会让工单走错分支。
 * 抽成模块后可以直接做「构造 → 解析」的往返断言。
 *
 * <h2>与后端的关系</h2>
 * 这里产出的就是标准 {@link FlowDefinition}（`start` + `nodes`），
 * 与手工在图式设计器里画的流程**同一形状**，因此提交时过的是同一道后端闸门
 * （`FlowDefinitionValidator` + `FlowGraphValidator`），不存在「简化模式的后门」。
 */

// ---------------------------------------------------------------------
// 常量
// ---------------------------------------------------------------------

/** 简化视图暴露的审批人来源（是后端 9 种来源的子集） */
export type SimpleRuleType =
  | 'LEADER'
  | 'BIZ_GROUP_APPROVERS'
  | 'PARENT_DEPT_APPROVERS'
  | 'SPECIFIC_USER'
  | 'ROLE'

export const SIMPLE_RULE_OPTIONS: Array<{ value: SimpleRuleType; label: string; hint: string }> = [
  { value: 'LEADER', label: '直属主管', hint: '提交时取申请人的直属领导' },
  { value: 'BIZ_GROUP_APPROVERS', label: '部门主管', hint: '提交时取申请人所在部门的部门主管' },
  // 「上级部门主管」是需求点名的 4 种之外的**第 5 种**，必须保留：预置的「用章申请」
  // 默认链就是「部门主管 → 上级部门主管」。少它则预置流程一进简化视图就判为不可编辑。
  { value: 'PARENT_DEPT_APPROVERS', label: '上级部门主管', hint: '提交时取上级部门的部门主管' },
  { value: 'SPECIFIC_USER', label: '指定人', hint: '固定由选中的员工审批' },
  { value: 'ROLE', label: '指定角色', hint: '由该角色下的成员审批' }
]

/** 简化视图能原样回读的来源集合（其余来源一律判为「高级配置」） */
export const SIMPLE_RULE_VALUES = new Set<string>(SIMPLE_RULE_OPTIONS.map((item) => item.value))

/** 条件可用的运算符（只给最常用的 6 个，够「金额 > 5000」这类判断） */
export const CONDITION_OPERATORS: Array<{ value: FlowOperatorCode; label: string }> = [
  { value: 'GT', label: '大于' },
  { value: 'GTE', label: '大于等于' },
  { value: 'LT', label: '小于' },
  { value: 'LTE', label: '小于等于' },
  { value: 'EQ', label: '等于' },
  { value: 'NE', label: '不等于' }
]

// ---------------------------------------------------------------------
// 行模型
// ---------------------------------------------------------------------

export interface LinearRow {
  /** 行内唯一 id（仅前端用；落库的是重建后的节点 key） */
  uid: string
  name: string
  ruleType: SimpleRuleType
  userIds: number[]
  userLabels: string[]
  roleCode: string | null
  timeLimitHours: number | null
  /** 高级条件：满足时在本节点后**多加一级**审批 */
  advanced: boolean
  condField: string | null
  condOp: FlowOperatorCode
  condValue: string
  condRuleType: SimpleRuleType
  condUserIds: number[]
  condUserLabels: string[]
  condRoleCode: string | null
}

let uidSeed = 0

/** 单调递增的行 id（只用于 `v-for :key` 与「正在为哪一行选人」的定位） */
export function nextLinearUid(): string {
  uidSeed += 1
  return `r${uidSeed}`
}

/** 仅供测试：重置 id 计数器，让断言不依赖执行顺序 */
export function resetLinearUidSeed(): void {
  uidSeed = 0
}

export function emptyLinearRow(index: number): LinearRow {
  return {
    uid: nextLinearUid(),
    name: `第${index + 1}级审批`,
    ruleType: 'LEADER',
    userIds: [],
    userLabels: [],
    roleCode: null,
    // 默认 24 小时：说明「限时（24h 超时提醒）填在每个节点旁边」
    timeLimitHours: 24,
    advanced: false,
    condField: null,
    condOp: 'GT',
    condValue: '',
    condRuleType: 'BIZ_GROUP_APPROVERS',
    condUserIds: [],
    condUserLabels: [],
    condRoleCode: null
  }
}

// ---------------------------------------------------------------------
// 行 → FlowDefinition
// ---------------------------------------------------------------------

/** 由行内配置构造一条 `ApproverRule`（只带该来源需要的字段，不留其它来源的残值） */
export function buildApproverRule(row: LinearRow, useCond = false): ApproverRule {
  const type = useCond ? row.condRuleType : row.ruleType
  const rule: ApproverRule = { type }
  if (type === 'SPECIFIC_USER') {
    rule.userIds = useCond ? row.condUserIds : row.userIds
  } else if (type === 'ROLE') {
    rule.roleCode = useCond ? row.condRoleCode : row.roleCode
  }
  return rule
}

/**
 * 条件比较值：数字型运算符把输入串转成数字。
 *
 * 不转换的后果很隐蔽：后端拿到字符串 `"5000"` 做数值比较时，不同分支的解析结果不一致，
 * 「金额 > 5000」可能对任意金额都成立或都不成立，而界面上一切正常。
 */
export function coerceCondValue(op: FlowOperatorCode, raw: string): unknown {
  const trimmed = raw.trim()
  const numeric = op === 'GT' || op === 'GTE' || op === 'LT' || op === 'LTE'
  if (numeric && trimmed !== '' && !Number.isNaN(Number(trimmed))) {
    return Number(trimmed)
  }
  return trimmed
}

function condNodeName(row: LinearRow, labelOf?: (key: string) => string): string {
  const field = labelOf?.(row.condField ?? '') ?? row.condField ?? '条件'
  const op = CONDITION_OPERATORS.find((item) => item.value === row.condOp)?.label ?? row.condOp
  return row.condValue.trim() === '' ? `${field} ${op}` : `${field} ${op} ${row.condValue}`
}

function condRuleLabel(type: SimpleRuleType): string {
  return SIMPLE_RULE_OPTIONS.find((item) => item.value === type)?.label ?? type
}

/**
 * 行模型 → 标准 FlowDefinition。
 *
 * 结构（`advanced` 打开的行，i 从 1 起）：
 * ```
 * ni ──► ci(条件) ──命中──► ai(多加的一级) ──► 下一节点
 *              └──其它──► 下一节点
 * ```
 * 未打开高级条件就是 `ni ──► 下一节点`；最后一个节点指向 `end`。
 *
 * @param labelOf 字段 key → 中文名（用于条件节点名；不传则用 key）
 */
export function buildFlowDefinition(rows: LinearRow[], labelOf?: (key: string) => string): FlowDefinition {
  const nodes: FlowNode[] = []
  rows.forEach((row, index) => {
    const key = `n${index + 1}`
    const nextKey = index + 1 < rows.length ? `n${index + 2}` : 'end'
    nodes.push({
      key,
      type: 'APPROVAL',
      name: row.name.trim() || `第${index + 1}级审批`,
      signType: 'ANY_SIGN',
      approverRules: [buildApproverRule(row)],
      next: row.advanced ? `c${index + 1}` : nextKey,
      timeLimitHours: row.timeLimitHours ?? null
    })

    if (row.advanced) {
      const cKey = `c${index + 1}`
      const aKey = `a${index + 1}`
      const condition: FlowConditionRule = {
        kind: 'RULE',
        field: row.condField,
        op: row.condOp,
        value: coerceCondValue(row.condOp, row.condValue)
      }
      nodes.push({
        key: cKey,
        type: 'CONDITION',
        name: condNodeName(row, labelOf),
        branches: [
          { key: `${cKey}_hit`, name: '满足条件', condition: { logic: 'AND', rules: [condition] }, next: aKey },
          { key: `${cKey}_else`, name: '其他情况', else: true, next: nextKey }
        ]
      })
      nodes.push({
        key: aKey,
        type: 'APPROVAL',
        name: `${condRuleLabel(row.condRuleType)}（加签）`,
        signType: 'ANY_SIGN',
        approverRules: [buildApproverRule(row, true)],
        next: nextKey,
        timeLimitHours: row.timeLimitHours ?? null
      })
    }
  })

  nodes.push({ key: 'end', type: 'END', name: '结束' })
  return { start: rows.length > 0 ? 'n1' : 'end', nodes }
}

// ---------------------------------------------------------------------
// FlowDefinition → 行
// ---------------------------------------------------------------------

export interface ParseResult {
  rows: LinearRow[]
  /** 非空 = 该定义包含简化视图表达不了的结构，此时**禁用保存** */
  unsupported: string | null
}

function rowFromNode(node: FlowNode, rule: ApproverRule | undefined): LinearRow {
  const type = String(rule?.type ?? 'LEADER') as SimpleRuleType
  const userIds = Array.isArray(rule?.userIds) ? (rule?.userIds as number[]) : []
  return {
    uid: nextLinearUid(),
    name: node.name ?? '',
    ruleType: type,
    userIds,
    userLabels: userIds.map((id) => `#${id}`),
    roleCode: rule?.roleCode ?? null,
    timeLimitHours: node.timeLimitHours ?? null,
    advanced: false,
    condField: null,
    condOp: 'GT',
    condValue: '',
    condRuleType: 'BIZ_GROUP_APPROVERS',
    condUserIds: [],
    condUserLabels: [],
    condRoleCode: null
  }
}

/**
 * 解析「高级条件」结构；解析不出来返回 `null`（调用方据此判为不可简化）。
 *
 * 认可的唯一形态：条件节点恰好两个分支，其中一个是默认出口（else），
 * 另一个带**单条规则**且指向一个审批节点；而该审批节点的去向与默认出口相同（即「合流」）。
 * 少任何一条都不认 —— 宁可提示「请用完整设计器」，也不猜用户画的图想表达什么。
 */
function parseAdvanced(
  condNode: FlowNode,
  index: Map<string, FlowNode>
): { patch: Partial<LinearRow>; after: FlowNode | null; consumed: string[] } | null {
  const branches = condNode.branches ?? []
  if (branches.length !== 2) {
    return null
  }
  const elseBranch = branches.find((branch) => branch.else === true)
  const hitBranch = branches.find((branch) => branch.else !== true)
  if (!elseBranch || !hitBranch || !hitBranch.condition) {
    return null
  }
  const rules = hitBranch.condition.rules ?? []
  if (rules.length !== 1 || rules[0].kind === 'GROUP') {
    return null
  }
  const extra = hitBranch.next ? index.get(hitBranch.next) : undefined
  if (!extra || extra.type !== 'APPROVAL') {
    return null
  }
  if ((extra.next ?? null) !== (elseBranch.next ?? null)) {
    return null
  }
  const extraRules = extra.approverRules ?? []
  if (extraRules.length !== 1 || !SIMPLE_RULE_VALUES.has(extraRules[0].type)) {
    return null
  }
  const extraRule = extraRules[0]
  const extraIds = Array.isArray(extraRule.userIds) ? (extraRule.userIds as number[]) : []
  const pick = rules[0]
  return {
    patch: {
      advanced: true,
      condField: pick.field ?? null,
      condOp: (pick.op as FlowOperatorCode) ?? 'GT',
      condValue: pick.value == null ? '' : String(pick.value),
      condRuleType: extraRules[0].type as SimpleRuleType,
      condUserIds: extraIds,
      condUserLabels: extraIds.map((id) => `#${id}`),
      condRoleCode: extraRule.roleCode ?? null
    },
    after: extra.next ? index.get(extra.next) ?? null : null,
    consumed: [condNode.key, extra.key]
  }
}

/**
 * FlowDefinition → 行模型。
 *
 * <b>读不出来的一律报「不可简化」而不是最接近的猜测</b>：静默降级会把一个能用的流程裁成残的，
 * 而这种丢失在界面上看不出来 —— 用户以为只是改了个审批人，实际抄送节点已经没了。
 */
export function parseFlowDefinition(definition: FlowDefinition | null | undefined): ParseResult {
  if (!definition) {
    return { rows: [], unsupported: null }
  }
  const nodes = definition.nodes ?? []
  if (nodes.length === 0) {
    return { rows: [], unsupported: null }
  }
  const index = new Map<string, FlowNode>()
  for (const node of nodes) {
    index.set(node.key, node)
  }
  const rows: LinearRow[] = []
  const visited = new Set<string>()
  /** 被「高级条件」解析刻意消费掉的节点（条件节点与加签节点）—— 它们不算「未展示」 */
  const consumed = new Set<string>()
  let current: FlowNode | undefined = index.get(definition.start)

  while (current && !visited.has(current.key)) {
    visited.add(current.key)
    if (current.type === 'END') {
      break
    }
    if (current.type === 'CC') {
      return { rows, unsupported: '该流程包含「抄送节点」，简化视图无法编辑' }
    }
    if (current.type === 'CONDITION') {
      return { rows, unsupported: '该流程在审批节点之外还有「条件分支」，简化视图无法完整表达' }
    }
    const rules = current.approverRules ?? []
    if (rules.length === 0) {
      return { rows, unsupported: `节点「${current.name}」没有配置审批人` }
    }
    if (rules.length > 1) {
      return {
        rows,
        unsupported: `节点「${current.name}」配置了多条审批人规则（会签/或签混用），简化视图无法编辑`
      }
    }
    if (!SIMPLE_RULE_VALUES.has(rules[0].type)) {
      const label =
        APPROVER_RULE_TYPE_OPTIONS.find((item) => item.value === rules[0].type)?.label ?? rules[0].type
      return {
        rows,
        unsupported: `节点「${current.name}」使用了「${label}」这一审批人来源，简化视图不支持`
      }
    }

    const row = rowFromNode(current, rules[0])

    const nextNode = current.next ? index.get(current.next) : undefined
    if (nextNode && nextNode.type === 'CONDITION') {
      const parsed = parseAdvanced(nextNode, index)
      if (!parsed) {
        rows.push(row)
        return { rows, unsupported: '该流程的条件分支结构较复杂，简化视图无法完整表达' }
      }
      Object.assign(row, parsed.patch)
      parsed.consumed.forEach((key) => consumed.add(key))
      rows.push(row)
      current = parsed.after ?? undefined
      continue
    }
    rows.push(row)
    current = nextNode
  }

  // ★ 收尾检查：线性链没经过、也不是「高级条件」消费掉的节点，一律报「不可简化」。
  // 不检查的后果是**静默丢节点** —— 例如一张画了抄送但线没接对的流程，
  // 在线性视图里看起来就是「少了一级」，而用户点保存就把抄送永久删掉了。
  const leftovers = (definition.nodes ?? []).filter(
    (node) => !visited.has(node.key) && !consumed.has(node.key)
  )
  if (leftovers.length > 0) {
    return {
      rows,
      unsupported: `该流程包含简化视图无法展示的节点：${leftovers
        .map((node) => node.name || node.key)
        .join('、')}`
    }
  }

  return { rows, unsupported: null }
}

// ---------------------------------------------------------------------
// 本地预检（保存前）
// ---------------------------------------------------------------------

/** 一行当前缺什么（返回空数组表示这一行配全了） */
export function linearRowProblems(row: LinearRow, index: number, maxHours: number): string[] {
  const problems: string[] = []
  const at = `第 ${index + 1} 个节点`
  if (!row.name.trim()) {
    problems.push(`${at}未填写节点名称`)
  }
  if (row.ruleType === 'SPECIFIC_USER' && row.userIds.length === 0) {
    problems.push(`${at}选择了「指定人」但未选人`)
  }
  if (row.ruleType === 'ROLE' && !row.roleCode) {
    problems.push(`${at}选择了「指定角色」但未选角色`)
  }
  if (row.timeLimitHours != null && (row.timeLimitHours < 1 || row.timeLimitHours > maxHours)) {
    problems.push(`${at}的限时必须在 1~${maxHours} 小时之间`)
  }
  if (row.advanced) {
    if (!row.condField) {
      problems.push(`${at}的高级条件未选择判断字段`)
    }
    if (row.condValue.trim() === '') {
      problems.push(`${at}的高级条件未填写比较值`)
    }
    if (row.condRuleType === 'SPECIFIC_USER' && row.condUserIds.length === 0) {
      problems.push(`${at}的高级条件选择了「指定人」但未选人`)
    }
    if (row.condRuleType === 'ROLE' && !row.condRoleCode) {
      problems.push(`${at}的高级条件选择了「指定角色」但未选角色`)
    }
  }
  return problems
}

/** 整份线性配置的预检（保存前调用） */
export function linearProblems(rows: LinearRow[], maxHours: number): string[] {
  const problems: string[] = []
  if (rows.length === 0) {
    problems.push('请至少配置一个审批节点')
    return problems
  }
  rows.forEach((row, index) => {
    problems.push(...linearRowProblems(row, index, maxHours))
  })
  return problems
}
