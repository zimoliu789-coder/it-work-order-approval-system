import { beforeEach, describe, expect, it } from 'vitest'
import { validateFlowForPublish, type FlowDefinition, type FlowNode } from '@/types/approvalFlow'
import {
  buildFlowDefinition,
  coerceCondValue,
  emptyLinearRow,
  linearProblems,
  parseFlowDefinition,
  resetLinearUidSeed,
  type LinearRow
} from '@/utils/linearFlow'

/**
 * 线性流程转换内核单测（ 项目 5）
 *
 * <h2>为什么这些断言必须存在</h2>
 * 需求是「非技术人员点几下就能配流程」。越是这样，配置者越不会去看产出的 JSON ——
 * 因此「点出来的流程到底长什么样」只能靠测试来保证。
 * 这里最容易出、又最难在界面上发现的四类错：
 * ① 条件分支少一个出口（后端发布时报「必须且只能有一个默认出口」）；
 * ② 合流点接错（多加的那一级审批通过后**回不到主线**，工单卡死）；
 * ③ 条件比较值被当成字符串（「金额 > 5000」对任何金额都成立/都不成立）；
 * ④ 把读不出来的流程**静默裁短**（用户以为只改了审批人，其实抄送节点没了）。
 */

/** 造一行（默认「直属主管」，字段按需覆盖） */
function row(overrides: Partial<LinearRow> = {}): LinearRow {
  return { ...emptyLinearRow(0), ...overrides }
}

function nodeOf(definition: FlowDefinition, key: string): FlowNode | undefined {
  return (definition.nodes ?? []).find((node) => node.key === key)
}

function keysOf(definition: FlowDefinition): string[] {
  return (definition.nodes ?? []).map((node) => node.key)
}

beforeEach(() => {
  resetLinearUidSeed()
})

describe('buildFlowDefinition：线性串联', () => {
  it('一行 → n1 指向 end，且 start 是 n1', () => {
    const definition = buildFlowDefinition([row({ ruleType: 'LEADER' })])
    expect(definition.start).toBe('n1')
    expect(keysOf(definition)).toEqual(['n1', 'end'])
    expect(nodeOf(definition, 'n1')?.next).toBe('end')
    expect(nodeOf(definition, 'n1')?.type).toBe('APPROVAL')
    expect(nodeOf(definition, 'n1')?.approverRules).toEqual([{ type: 'LEADER' }])
  })

  it('三行 → n1→n2→n3→end（严格按行序，不重排）', () => {
    const definition = buildFlowDefinition([
      row({ ruleType: 'LEADER' }),
      row({ ruleType: 'BIZ_GROUP_APPROVERS' }),
      row({ ruleType: 'ROLE', roleCode: 'it_admin' })
    ])
    expect(keysOf(definition).slice(0, 3)).toEqual(['n1', 'n2', 'n3'])
    expect(nodeOf(definition, 'n1')?.next).toBe('n2')
    expect(nodeOf(definition, 'n2')?.next).toBe('n3')
    expect(nodeOf(definition, 'n3')?.next).toBe('end')
    // start 必须是链头，且每行都要保留它自己的审批人来源（映射类成对断言）
    expect(definition.start).toBe('n1')
    expect(nodeOf(definition, 'n2')?.approverRules).toEqual([{ type: 'BIZ_GROUP_APPROVERS' }])
    expect(nodeOf(definition, 'n3')?.approverRules).toEqual([{ type: 'ROLE', roleCode: 'it_admin' }])
  })

  it('空行 → 只有 end（调用方应以「至少一个审批节点」拦下，而不是提交一个无审批的流程）', () => {
    const definition = buildFlowDefinition([])
    expect(keysOf(definition)).toEqual(['end'])
    expect(definition.start).toBe('end')
  })

  it('限时写到节点上（需求：每个节点旁填 24h 超时提醒）', () => {
    const definition = buildFlowDefinition([row({ timeLimitHours: 24 }), row({ timeLimitHours: null })])
    expect(nodeOf(definition, 'n1')?.timeLimitHours).toBe(24)
    // 不填就是「不限时」—— 必须是 null，不能自作主张补一个默认值，
    // 否则界面上留空、后端却按 24h 判定超时（静默改变语义）
    expect(nodeOf(definition, 'n2')?.timeLimitHours).toBeNull()
  })

  it('指定人不留其它来源的残值（切成指定人时 roleCode 不该跟着走）', () => {
    const definition = buildFlowDefinition([row({ ruleType: 'SPECIFIC_USER', userIds: [7, 9], roleCode: 'admin' })])
    expect(nodeOf(definition, 'n1')?.approverRules).toEqual([{ type: 'SPECIFIC_USER', userIds: [7, 9] }])
  })
})

describe('buildFlowDefinition：高级条件（多加一级审批）', () => {
  const advancedRow = row({
    name: '部门主管审批',
    ruleType: 'BIZ_GROUP_APPROVERS',
    advanced: true,
    condField: 'amount',
    condOp: 'GT',
    condValue: '5000',
    condRuleType: 'PARENT_DEPT_APPROVERS'
  })

  it('产出 条件节点 + 加签审批节点，且默认出口恰为一个', () => {
    const definition = buildFlowDefinition([advancedRow, row({ ruleType: 'LEADER' })])
    expect(keysOf(definition)).toEqual(['n1', 'c1', 'a1', 'n2', 'end'])

    const cond = nodeOf(definition, 'c1')
    expect(cond?.type).toBe('CONDITION')
    const branches = cond?.branches ?? []
    expect(branches).toHaveLength(2)
    expect(branches.filter((branch) => branch.else === true)).toHaveLength(1)
    expect(branches.filter((branch) => branch.else !== true)).toHaveLength(1)
  })

  it('★ 合流正确：命中分支经加签节点回到主线，其它分支直接回主线', () => {
    const definition = buildFlowDefinition([advancedRow, row({ ruleType: 'LEADER' })])
    const cond = nodeOf(definition, 'c1')
    const hit = (cond?.branches ?? []).find((branch) => branch.else !== true)
    const other = (cond?.branches ?? []).find((branch) => branch.else === true)
    const extra = nodeOf(definition, hit?.next as string)

    expect(hit?.next).toBe('a1')
    expect(extra?.type).toBe('APPROVAL')
    // 两个出口最终必须汇到同一个节点 —— 否则「加了一级审批」之后工单会走到别处
    expect(extra?.next).toBe(other?.next)
    expect(other?.next).toBe('n2')
  })

  it('★ 条件比较值是数字而不是字符串（否则「金额 > 5000」恒真或恒假）', () => {
    const definition = buildFlowDefinition([advancedRow])
    const condition = nodeOf(definition, 'c1')?.branches?.find((branch) => branch.else !== true)?.condition
    const rule = condition?.rules?.[0]
    expect(rule?.field).toBe('amount')
    expect(rule?.op).toBe('GT')
    expect(rule?.value).toBe(5000)
    expect(typeof rule?.value).toBe('number')
  })

  it('★ 产出的定义能过后端同构的发布预检（不是「看起来对」，是「确实合法」）', () => {
    const definition = buildFlowDefinition([
      advancedRow,
      row({ ruleType: 'SPECIFIC_USER', userIds: [3] })
    ])
    expect(validateFlowForPublish(definition)).toEqual([])
  })

  it('纯表单字段条件不受「必须有前置审批」的限制（该限制只针对 process.* 运行期字段）', () => {
    const definition = buildFlowDefinition([advancedRow])
    // 若把条件误判成运行期依赖，这里会报「前面没有审批节点」
    expect(validateFlowForPublish(definition)).not.toContain(
      expect.stringContaining('前面没有审批节点')
    )
  })
})

describe('parseFlowDefinition：读回线性行', () => {
  it('★ 往返一致：build → parse 得到同样的节点数与审批人来源', () => {
    const rows = [
      row({ ruleType: 'LEADER' }),
      row({ ruleType: 'PARENT_DEPT_APPROVERS' }),
      row({ ruleType: 'SPECIFIC_USER', userIds: [11, 12] })
    ]
    const parsed = parseFlowDefinition(buildFlowDefinition(rows, (key) => key))
    expect(parsed.unsupported).toBeNull()
    expect(parsed.rows).toHaveLength(3)
    expect(parsed.rows.map((item) => item.ruleType)).toEqual([
      'LEADER',
      'PARENT_DEPT_APPROVERS',
      'SPECIFIC_USER'
    ])
    expect(parsed.rows[2].userIds).toEqual([11, 12])
  })

  it('★ 往返一致：高级条件也能原样读回', () => {
    const source = row({
      ruleType: 'BIZ_GROUP_APPROVERS',
      advanced: true,
      condField: 'amount',
      condOp: 'GTE',
      condValue: '1000',
      condRuleType: 'ROLE',
      condRoleCode: 'it_admin'
    })
    const parsed = parseFlowDefinition(buildFlowDefinition([source]))
    expect(parsed.unsupported).toBeNull()
    expect(parsed.rows).toHaveLength(1)
    const back = parsed.rows[0]
    expect(back.advanced).toBe(true)
    expect(back.condField).toBe('amount')
    expect(back.condOp).toBe('GTE')
    expect(back.condValue).toBe('1000')
    expect(back.condRuleType).toBe('ROLE')
    expect(back.condRoleCode).toBe('it_admin')
  })

  it('空 / null 定义 → 空行列表且不报不可简化', () => {
    expect(parseFlowDefinition(null)).toEqual({ rows: [], unsupported: null })
    expect(parseFlowDefinition({ start: 'end', nodes: [{ key: 'end', type: 'END', name: '结束' }] })).toEqual({
      rows: [],
      unsupported: null
    })
  })
})

describe('parseFlowDefinition：读不出来时明确拒绝，绝不静默裁短', () => {
  const approval = (rules: unknown[]): FlowNode => ({
    key: 'n1',
    type: 'APPROVAL',
    name: '审批',
    next: 'end',
    approverRules: rules as FlowNode['approverRules']
  })
  const end: FlowNode = { key: 'end', type: 'END', name: '结束' }

  it('抄送节点 → unsupported（且不把前面的节点当整条流程）', () => {
    const result = parseFlowDefinition({
      start: 'n1',
      nodes: [
        { key: 'n1', type: 'APPROVAL', name: '审批', next: 'c1', approverRules: [{ type: 'LEADER' }] },
        { key: 'c1', type: 'CC', name: '知会', next: 'end', approverRules: [{ type: 'LEADER' }] },
        end
      ]
    })
    expect(result.unsupported).toContain('抄送')
  })

  it('★ 存在线性链没经过的节点 → unsupported，而不是静默把它丢掉', () => {
    const result = parseFlowDefinition({
      start: 'n1',
      nodes: [
        { key: 'n1', type: 'APPROVAL', name: '审批', next: 'end', approverRules: [{ type: 'LEADER' }] },
        { key: 'orphan', type: 'APPROVAL', name: '孤岛审批', next: 'end', approverRules: [{ type: 'LEADER' }] },
        end
      ]
    })
    expect(result.unsupported).toContain('无法展示的节点')
    expect(result.unsupported).toContain('孤岛审批')
  })

  it('多条审批人规则（会签）→ unsupported', () => {
    const result = parseFlowDefinition({
      start: 'n1',
      nodes: [approval([{ type: 'LEADER' }, { type: 'ROLE', roleCode: 'admin' }]), end]
    })
    expect(result.unsupported).toContain('多条审批人规则')
  })

  it('简化视图不支持的来源（上一节点指定）→ unsupported 且带上来源中文名', () => {
    const result = parseFlowDefinition({
      start: 'n1',
      nodes: [approval([{ type: 'PREV_ASSIGN', assignCount: 1 }]), end]
    })
    expect(result.unsupported).toContain('上一节点指定审批人')
  })

  it('没有审批人的节点 → unsupported（不能当成「一级空审批」放过去）', () => {
    const result = parseFlowDefinition({ start: 'n1', nodes: [approval([]), end] })
    expect(result.unsupported).toContain('没有配置审批人')
  })

  it('三分支条件（简化视图只认两分支）→ unsupported', () => {
    const result = parseFlowDefinition({
      start: 'n1',
      nodes: [
        {
          key: 'n1',
          type: 'APPROVAL',
          name: '审批',
          next: 'c1',
          approverRules: [{ type: 'LEADER' }]
        },
        {
          key: 'c1',
          type: 'CONDITION',
          name: '三路',
          branches: [
            { key: 'b1', name: 'a', condition: { logic: 'AND', rules: [{ field: 'x', op: 'EQ', value: '1' }] }, next: 'end' },
            { key: 'b2', name: 'b', condition: { logic: 'AND', rules: [{ field: 'x', op: 'EQ', value: '2' }] }, next: 'end' },
            { key: 'b3', name: '其他', else: true, next: 'end' }
          ]
        },
        end
      ]
    })
    expect(result.unsupported).toContain('条件分支结构较复杂')
  })
})

describe('coerceCondValue：数字运算符才转数字', () => {
  it('GT + "5000" → 数字 5000', () => {
    expect(coerceCondValue('GT', '5000')).toBe(5000)
  })

  it('GT + 非数字文本 → 原样返回（不硬转成 NaN，交给后端报错）', () => {
    expect(coerceCondValue('GT', '五千')).toBe('五千')
  })

  it('EQ + "5000" → 保持字符串（等值比较要按字段类型，不能擅自改类型）', () => {
    expect(coerceCondValue('EQ', '5000')).toBe('5000')
  })

  it('两端空白被裁掉', () => {
    expect(coerceCondValue('GT', '  5000  ')).toBe(5000)
  })
})

describe('linearProblems：保存前预检', () => {
  it('一行都不配 → 提示至少一个审批节点', () => {
    expect(linearProblems([], 720)).toEqual(['请至少配置一个审批节点'])
  })

  it('指定人没选人 / 指定角色没选角色 → 各报一条（成对：两种来源都要拦）', () => {
    const problems = linearProblems(
      [row({ ruleType: 'SPECIFIC_USER', userIds: [] }), row({ ruleType: 'ROLE', roleCode: null })],
      720
    )
    expect(problems.some((item) => item.includes('「指定人」但未选人'))).toBe(true)
    expect(problems.some((item) => item.includes('「指定角色」但未选角色'))).toBe(true)
  })

  it('高级条件缺字段 / 缺比较值 → 各报一条', () => {
    const problems = linearProblems(
      [row({ advanced: true, condField: null, condValue: '  ', condRuleType: 'BIZ_GROUP_APPROVERS' })],
      720
    )
    expect(problems.some((item) => item.includes('未选择判断字段'))).toBe(true)
    expect(problems.some((item) => item.includes('未填写比较值'))).toBe(true)
  })

  it('限时越界 → 报错；恰好在边界内 → 通过（成对断言，避免「怎么填都报错」）', () => {
    expect(linearProblems([row({ timeLimitHours: 0 })], 720).some((item) => item.includes('限时必须在'))).toBe(true)
    expect(linearProblems([row({ timeLimitHours: 721 })], 720).some((item) => item.includes('限时必须在'))).toBe(true)
    expect(linearProblems([row({ timeLimitHours: 720 })], 720)).toEqual([])
    expect(linearProblems([row({ timeLimitHours: 1 })], 720)).toEqual([])
  })

  it('配全的一行不报任何问题', () => {
    expect(linearProblems([row({ timeLimitHours: 24 })], 720)).toEqual([])
  })
})
