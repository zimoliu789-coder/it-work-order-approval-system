import { describe, expect, it } from 'vitest'
import { useFlowConditionTree } from '@/composables/useFlowConditionTree'
import { conditionGroup, type FlowBranch, type FlowCondition } from '@/types/approvalFlow'

/**
 * 条件树「按路径寻址」单测（ ·  · W4-A3）。
 *
 * <p>这是设计器里最容易写错的一块：路径 `[0,1]` 指的是"顶层第 1 项（一个组）里的第 2 项"，
 * 与「第 1 层第 2 个」不是一回事。抽出 {@link useFlowConditionTree} 之后，
 * 可以直接对着"改写后的条件树"断言，而不必挂载组件、也不必渲染 DOM。
 *
 * <h2>三条关键不变量</h2>
 * <ol>
 *   <li><b>不可变</b>：没被路径经过的兄弟节点保持**同一个对象引用**（不是"值相等"）——
 *       "就地改完再指望响应式发现"的写法会被这条断言拦下；</li>
 *   <li><b>组的信封不接受字段补丁</b>：`patchRuleAt` 落到组上必须是空操作 ——
 *       否则 `kind=GROUP` 会带上 field/op/value，被后端直接拒绝；</li>
 *   <li><b>空路径 = 条件根</b>：需要根的场合（追加规则 / 换逻辑 / 量深度）都靠它表达，
 *       而不需要"根本身不能删"这类特例散落到调用方。</li>
 * </ol>
 */
interface Harness {
  api: ReturnType<typeof useFlowConditionTree>
  updates: Array<{ condKey: string; branchKey: string; patch: Partial<FlowBranch> }>
}

/** 用"只有一个条件节点、一个分支"的最小场景驱动：写回被记录而不是落到组件上 */
function harness(initial: FlowCondition): Harness {
  const branch: FlowBranch = { key: 'b1', name: '满足条件', condition: initial, next: null }
  const updates: Harness['updates'] = []
  const api = useFlowConditionTree({
    findBranch: (_condKey, branchKey) => (branchKey === branch.key ? branch : undefined),
    updateBranch: (condKey, branchKey, patch) => updates.push({ condKey, branchKey, patch }),
    branchCondition: (target) => target.condition ?? { logic: 'AND', rules: [] },
    conditionValueOptions: (fieldKey) =>
      fieldKey === 'process.prevNodeResult'
        ? [
            { value: 'APPROVED', label: '已通过' },
            { value: 'REJECTED', label: '已驳回' }
          ]
        : []
  })
  return { api, updates }
}

/** 取最后一次写回的条件树 */
function lastCondition(h: Harness): FlowCondition {
  const last = h.updates[h.updates.length - 1]
  expect(last).toBeDefined()
  return last.patch.condition ?? { logic: 'AND', rules: [] }
}

const RULE = (field: string, op: 'EQ' | 'GT' | 'LT' = 'EQ') => ({ field, op, value: '' })

describe('useFlowConditionTree', () => {
  it('appendRule：[] 追加到条件根；[0] 指向规则时静默不改；[1] 落到嵌套组内且兄弟引用不变', () => {
    const outerA = { field: 'amount', op: 'GT' as const, value: '5000' }
    const innerGroup = conditionGroup({ logic: 'AND', rules: [RULE('days', 'LT')] })
    const sibling = RULE('x')
    const root: FlowCondition = { logic: 'AND', rules: [outerA, innerGroup, sibling] }

    // —— 追加到条件根：原三项原样保留（引用相等，不是"值相等"）
    const atRoot = harness(root)
    atRoot.api.appendRule('c1', 'b1', [])
    const nextRoot = lastCondition(atRoot)
    expect(nextRoot.rules).toHaveLength(4)
    expect(nextRoot.rules?.[0]).toBe(outerA)
    expect(nextRoot.rules?.[1]).toBe(innerGroup)
    expect(nextRoot.rules?.[2]).toBe(sibling)
    expect(nextRoot.rules?.[3]).toEqual({ field: '', op: 'EQ' })

    // —— [0] 指向的是一条规则而不是组 → 找不到组，整棵树原样返回
    const atRulePath = harness(root)
    atRulePath.api.appendRule('c1', 'b1', [0])
    expect(lastCondition(atRulePath)).toBe(root)

    // —— [1] 真正落到嵌套组里；根的第 2 项引用不变
    const atGroup = harness(root)
    atGroup.api.appendRule('c1', 'b1', [1])
    const withInner = lastCondition(atGroup)
    expect(withInner.rules?.[0]).toBe(outerA)
    expect(withInner.rules?.[2]).toBe(sibling)
    const inner = withInner.rules?.[1]
    expect(inner?.condition?.rules).toHaveLength(2)
    // 组内原有那条规则引用不变
    expect(inner?.condition?.rules?.[0]).toBe(innerGroup.condition?.rules?.[0])
  })

  it('appendSubGroup：新建的是「含一条空白规则」的组，而不是空组', () => {
    const h = harness({ logic: 'AND', rules: [RULE('amount', 'GT')] })
    h.api.appendSubGroup('c1', 'b1', [])
    const added = lastCondition(h).rules?.[1]
    expect(added?.kind).toBe('GROUP')
    // 空组会立刻触发"嵌套条件组为空"，因此必须预置一条规则
    expect(added?.condition?.rules).toHaveLength(1)
  })

  it('removeNodeAt：[] 是空操作（条件根本身不能删）；有路径时删除该节点且其它引用不变', () => {
    const a = RULE('a')
    const b = RULE('b')
    const root: FlowCondition = { logic: 'AND', rules: [a, b] }

    const atRoot = harness(root)
    atRoot.api.removeNodeAt('c1', 'b1', [])
    // 根本没走到写回：条件根不能整体删除，那是"删分支"的操作
    expect(atRoot.updates).toHaveLength(0)

    const atIndex = harness(root)
    atIndex.api.removeNodeAt('c1', 'b1', [0])
    const removed = lastCondition(atIndex)
    expect(removed.rules).toHaveLength(1)
    expect(removed.rules?.[0]).toBe(b)
  })

  it('patchRuleAt：改单条规则；落到组信封上必须是空操作（组不能带 field/op/value）', () => {
    const group = conditionGroup({ logic: 'AND', rules: [RULE('inner')] })
    const root: FlowCondition = { logic: 'AND', rules: [RULE('amount', 'GT'), group] }

    const onRule = harness(root)
    onRule.api.patchRuleAt('c1', 'b1', [0], { field: 'total' })
    expect(lastCondition(onRule).rules?.[0]).toMatchObject({ field: 'total', op: 'GT' })

    const onGroup = harness(root)
    onGroup.api.patchRuleAt('c1', 'b1', [1], { field: 'total' })
    // 组信封原样返回（同一个引用），不会产生"带字段的组"
    expect(lastCondition(onGroup).rules?.[1]).toBe(group)
  })

  it('changeOperatorAt：白名单外的运算符不改；切到「为空」清成 null，切回有值运算符补空串', () => {
    const root: FlowCondition = { logic: 'AND', rules: [RULE('a')] }

    const invalid = harness(root)
    invalid.api.changeOperatorAt('c1', 'b1', [0], 'NOT_AN_OPERATOR')
    expect(invalid.updates).toHaveLength(0)

    const toEmpty = harness(root)
    toEmpty.api.changeOperatorAt('c1', 'b1', [0], 'IS_EMPTY')
    expect(lastCondition(toEmpty).rules?.[0]).toMatchObject({ op: 'IS_EMPTY', value: null })

    const fromEmpty = harness({
      logic: 'AND',
      rules: [{ field: 'a', op: 'IS_EMPTY' as const, value: null }]
    })
    fromEmpty.api.changeOperatorAt('c1', 'b1', [0], 'EQ')
    expect(lastCondition(fromEmpty).rules?.[0]).toMatchObject({ op: 'EQ', value: '' })
  })

  it('canAppendSubGroup：先"加上去"再量整棵树的深度，而不是数路径长度', () => {
    const api = harness({ logic: 'AND', rules: [] }).api

    // 三层树：根(1) → 组(2) → 组(3)
    const threeLevel: FlowCondition = {
      logic: 'AND',
      rules: [
        conditionGroup({
          logic: 'AND',
          rules: [conditionGroup({ logic: 'AND', rules: [RULE('x')] })]
        })
      ]
    }
    // 已在第 3 层的组里再嵌一层 → 第 4 层，超限
    expect(api.canAppendSubGroup(threeLevel, [0, 0])).toBe(false)
    expect(api.subGroupHint(threeLevel, [0, 0])).toContain('上限')

    // 与最深链并排再加一个「叶片组」不会加深最深链（结果仍是 3 层）→ 允许。
    // 这正是"先加上去再量深度"的价值：凭空数层号会误判成超限。
    expect(api.canAppendSubGroup(threeLevel, [])).toBe(true)
    expect(api.canAppendSubGroup(threeLevel, [0])).toBe(true)

    // 两层树（根 1 → 组 2）：根下与组内都还能再加一层
    const twoLevel: FlowCondition = {
      logic: 'AND',
      rules: [conditionGroup({ logic: 'AND', rules: [RULE('x')] })]
    }
    expect(api.canAppendSubGroup(twoLevel, [])).toBe(true)
    expect(api.canAppendSubGroup(twoLevel, [0])).toBe(true)
    expect(api.subGroupHint(twoLevel, [0])).toContain('最多 3 层')

    // 路径指向的是一条规则（不是组）→ 没地方嵌组
    expect(api.canAppendSubGroup({ logic: 'AND', rules: [RULE('x')] }, [0])).toBe(false)
    // 路径不存在 → 同样为 false，而不是抛错
    expect(api.canAppendSubGroup(twoLevel, [9])).toBe(false)
  })

  it('conditionRowsOf：摊平出的行带缩进层级与路径；组的行不承载字段/比较值', () => {
    const root: FlowCondition = {
      logic: 'AND',
      rules: [
        { field: 'amount', op: 'GT' as const, value: '5000' },
        conditionGroup({
          logic: 'OR',
          rules: [{ field: 'process.prevNodeResult', op: 'EQ' as const, value: 'APPROVED' }]
        })
      ]
    }
    const h = harness(root)
    const rows = h.api.conditionRowsOf({ key: 'b1', name: '满足条件', condition: root, next: null })

    expect(rows.map((row) => row.path)).toEqual([[0], [1], [1, 0]])
    expect(rows.map((row) => row.depth)).toEqual([1, 1, 2])
    expect(rows.map((row) => row.group)).toEqual([false, true, false])

    // 组行：只有逻辑开关，没有字段/运算符/比较值
    expect(rows[1]).toMatchObject({ group: true, logic: 'OR', field: '', op: null, value: '' })

    // 规则行：普通表单字段拿不到枚举（仍由用户手填）；运行期伪字段带出比较值候选
    expect(rows[0].requiresValue).toBe(true)
    expect(rows[0].valueOptions).toEqual([])
    expect(rows[2].valueOptions).toEqual([
      { value: 'APPROVED', label: '已通过' },
      { value: 'REJECTED', label: '已驳回' }
    ])
  })
})
