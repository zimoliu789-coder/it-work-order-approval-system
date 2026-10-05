import { describe, expect, it } from 'vitest'
import {
  ASSIGN_SCOPE_OPTIONS,
  approverRuleParams,
  approverRuleText,
  approverRulesText,
  assignScopeSuffix,
  conditionText,
  flowOperatorRequiresValue,
  isElseBranch,
  nodeTypeLabel,
  outgoingKeys,
  validateFlowForPublish,
  type FlowDefinition,
  type FlowFieldOption,
  type FlowNode
} from '@/types/approvalFlow'

/**
 * 审批流程发布前本地预检 + 展示辅助（ · ）
 *
 * <h2>为什么这些用例值得写</h2>
 * {@link validateFlowForPublish} 是后端 `FlowDefinitionValidator` 的**同构前端镜像**：
 * 它决定「发布」按钮是否可点、以及给配置者的第一句提示。它一旦与后端漂移，
 * 就会出现两种恶心的情况 —— 前端放过后端拒绝（用户白填一遍），
 * 或前端拒绝而流程其实合法（功能被前端挡住，且无处申诉）。
 * 因此这里逐条覆盖后端的每一个校验分支，用**同一份流程定义**做基准再逐项破坏。
 *
 * 基准流程刻意与接口回归 `_p15-regression.sh` 使用的定义同构：
 * 条件分支两条出口**汇合**到同一个后续节点 —— 这正是 DAG 相对树的关键差别，
 * 本地预检必须能正确处理"一个节点被两条边指向"。
 */
const FIELDS: FlowFieldOption[] = [
  { value: 'amount', label: '金额', kind: 'NUMBER' },
  { value: 'title', label: '标题', kind: 'TEXT' },
  { value: 'receiver', label: '接收人', kind: 'USER_REF' }
]

/** 结构完整、可通过校验的流程（对照接口回归里的 REG 定义） */
function validFlow(): FlowDefinition {
  return {
    start: 'n1',
    nodes: [
      {
        key: 'n1',
        type: 'APPROVAL',
        name: '主管审批',
        signType: 'ANY_SIGN',
        approverRules: [{ type: 'ROLE', roleCode: 'admin' }],
        next: 'c1'
      },
      {
        key: 'c1',
        type: 'CONDITION',
        name: '金额判断',
        branches: [
          {
            key: 'b1',
            name: '金额大于5000',
            next: 'n2',
            condition: { logic: 'AND', rules: [{ field: 'amount', op: 'GT', value: '5000' }] }
          },
          { key: 'b2', name: '其它情况', else: true, next: 'n3' }
        ]
      },
      {
        key: 'n2',
        type: 'APPROVAL',
        name: '财务复核',
        signType: 'ALL_SIGN',
        approverRules: [{ type: 'APPLICANT_CHOOSE', scope: 'ALL', minCount: 1, maxCount: 2 }],
        next: 'n3'
      },
      {
        key: 'n3',
        type: 'APPROVAL',
        name: '归档确认',
        signType: 'ANY_SIGN',
        approverRules: [{ type: 'FORM_USER_FIELD', fieldKey: 'receiver' }],
        next: 'end'
      },
      { key: 'end', type: 'END', name: '结束' }
    ]
  }
}

function clone(): FlowDefinition {
  return JSON.parse(JSON.stringify(validFlow())) as FlowDefinition
}

function problems(definition: FlowDefinition | null | undefined, fields?: FlowFieldOption[]): string[] {
  return validateFlowForPublish(definition, fields)
}

function findProblem(list: string[], fragment: string): boolean {
  return list.some((item) => item.includes(fragment))
}

describe('validateFlowForPublish —— 结构校验（与后端逐条对齐）', () => {
  it('结构完整的流程没有任何问题', () => {
    expect(problems(validFlow(), FIELDS)).toEqual([])
  })

  it('空定义 / 无节点被直接拒绝', () => {
    expect(problems(null)).toEqual(['流程定义为空'])
    expect(problems(undefined)).toEqual(['流程定义为空'])
    expect(problems({ start: '', nodes: [] })).toEqual(['流程至少要有一个节点'])
  })

  it('节点标识必须合法且唯一', () => {
    const bad = clone()
    bad.nodes[0].key = '1bad'
    expect(findProblem(problems(bad), '节点标识不合法')).toBe(true)

    const dup = clone()
    dup.nodes[1].key = 'n1'
    expect(findProblem(problems(dup), '节点标识重复')).toBe(true)
  })

  it('起始节点必须存在', () => {
    const missing = clone()
    missing.start = 'nope'
    expect(findProblem(problems(missing), '起始节点不存在')).toBe(true)

    const empty = clone()
    empty.start = ''
    expect(findProblem(problems(empty), '未设置起始节点')).toBe(true)
  })

  it('审批节点：必须有审批人、必须有下一节点、不能配分支', () => {
    const noRule = clone()
    noRule.nodes[0].approverRules = []
    expect(findProblem(problems(noRule), '未配置任何审批人')).toBe(true)

    const noNext = clone()
    noNext.nodes[0].next = null
    expect(findProblem(problems(noNext), '未指定下一节点')).toBe(true)

    const withBranch = clone()
    withBranch.nodes[0].branches = [{ key: 'x', name: 'x', next: 'end' }]
    expect(findProblem(problems(withBranch), '是审批节点，不应配置分支')).toBe(true)
  })

  it('「申请人自选」最多一条（与服务端一致：多于一条无法按 nodeKey 归属）', () => {
    const flow = clone()
    flow.nodes[2].approverRules = [
      { type: 'APPLICANT_CHOOSE', scope: 'ALL', minCount: 1, maxCount: 1 },
      { type: 'APPLICANT_CHOOSE', scope: 'ALL', minCount: 1, maxCount: 1 }
    ]
    expect(findProblem(problems(flow), '最多只能有 1 条')).toBe(true)
  })

  it('「申请人自选」人数区间必须自洽', () => {
    const flow = clone()
    flow.nodes[2].approverRules = [{ type: 'APPLICANT_CHOOSE', scope: 'ALL', minCount: 3, maxCount: 2 }]
    expect(findProblem(problems(flow), '最多人数不能小于最少人数')).toBe(true)
  })

  it('条件节点：必须恰有一个默认出口', () => {
    const none = clone()
    none.nodes[1].branches = [{ key: 'b1', name: '有值', next: 'end', condition: { logic: 'AND', rules: [{ field: 'title', op: 'NOT_EMPTY' }] } }]
    expect(findProblem(problems(none), '必须且只能有一个默认出口')).toBe(true)

    const two = clone()
    two.nodes[1].branches = [
      { key: 'b1', name: 'a', else: true, next: 'end' },
      { key: 'b2', name: 'b', else: true, next: 'end' }
    ]
    expect(findProblem(problems(two), '必须且只能有一个默认出口')).toBe(true)
  })

  it('条件节点：分支必须有名称与去向，非默认出口必须有条件', () => {
    const flow = clone()
    flow.nodes[1].branches = [
      { key: 'b1', name: '', next: 'n2' },
      { key: 'b2', name: '其它', else: true, next: 'n3' }
    ]
    expect(findProblem(problems(flow), '未设置名称')).toBe(true)
    expect(findProblem(problems(flow), '未配置条件')).toBe(true)

    const noTarget = clone()
    noTarget.nodes[1].branches = [
      { key: 'b1', name: 'a', next: null, condition: { logic: 'AND', rules: [{ field: 'title', op: 'NOT_EMPTY' }] } },
      { key: 'b2', name: 'b', else: true, next: null }
    ]
    expect(findProblem(problems(noTarget), '未指定去向节点')).toBe(true)
  })

  it('条件节点不应配 next / 审批人', () => {
    const withNext = clone()
    withNext.nodes[1].next = 'end'
    expect(findProblem(problems(withNext), '出口写在分支里')).toBe(true)

    const withRules = clone()
    withRules.nodes[1].approverRules = [{ type: 'ROLE', roleCode: 'admin' }]
    expect(findProblem(problems(withRules), '不应配置审批人')).toBe(true)
  })

  it('结束节点不应配 next / 分支 / 审批人', () => {
    const flow = clone()
    flow.nodes[4].next = 'n1'
    expect(findProblem(problems(flow), '结束节点，不应配置 next')).toBe(true)
  })

  it('检测环', () => {
    const flow = clone()
    flow.nodes[0].next = 'n1'
    expect(findProblem(problems(flow), '存在环')).toBe(true)
  })

  it('检测不可达节点（孤岛）', () => {
    const flow = clone()
    flow.nodes.push({
      key: 'orphan',
      type: 'APPROVAL',
      name: '孤儿',
      signType: 'ANY_SIGN',
      approverRules: [{ type: 'ROLE', roleCode: 'user' }],
      next: 'end'
    })
    expect(findProblem(problems(flow), '不可达的节点')).toBe(true)
  })

  it('检测无法到达结束节点的死路', () => {
    const flow = clone()
    // 让归档确认指向一个"指向空"的分支：断开到 end 的链路
    flow.nodes[3].next = null
    const list = problems(flow)
    expect(findProblem(list, '未指定下一节点')).toBe(true)
    expect(findProblem(list, '无法到达结束节点')).toBe(true)
  })

  it('至少要有一个审批节点（全是条件 / 结束没有意义）', () => {
    const flow: FlowDefinition = {
      start: 'c1',
      nodes: [
        { key: 'c1', type: 'CONDITION', name: '判断', branches: [{ key: 'b1', name: 'x', else: true, next: 'end' }] },
        { key: 'end', type: 'END', name: '结束' }
      ]
    }
    expect(findProblem(problems(flow), '至少要有一个审批节点')).toBe(true)
  })

  it('审批人来源缺参数会分别报错', () => {
    const specific = clone()
    specific.nodes[0].approverRules = [{ type: 'SPECIFIC_USER', userIds: [] }]
    expect(findProblem(problems(specific), '未选择人员')).toBe(true)

    const role = clone()
    role.nodes[0].approverRules = [{ type: 'ROLE' }]
    expect(findProblem(problems(role), '未选择角色')).toBe(true)

    const group = clone()
    group.nodes[0].approverRules = [{ type: 'HANDLER_GROUP' }]
    expect(findProblem(problems(group), '未选择处理小组')).toBe(true)

    const field = clone()
    field.nodes[0].approverRules = [{ type: 'FORM_USER_FIELD' }]
    expect(findProblem(problems(field), '未选择表单字段')).toBe(true)
  })

  it('「部门审批人」无需参数，不会报错', () => {
    const flow = clone()
    flow.nodes[0].approverRules = [{ type: 'BIZ_GROUP_APPROVERS' }]
    expect(problems(flow, FIELDS)).toEqual([])
  })
})

describe('validateFlowForPublish —— 字段级校验（仅在提供参考表单时生效）', () => {
  it('未提供参考表单时跳过字段校验（与后端"发布时 schema 可能为空"一致）', () => {
    const flow = clone()
    flow.nodes[1].branches![0].condition = { logic: 'AND', rules: [{ field: 'noSuchField', op: 'NOT_EMPTY' }] }
    expect(problems(flow)).toEqual([])
  })

  it('提供参考表单时，未知字段不报错但已知字段的运算符类型会校验', () => {
    const unknown = clone()
    unknown.nodes[1].branches![0].condition = { logic: 'AND', rules: [{ field: 'noSuchField', op: 'NOT_EMPTY' }] }
    // 前端与后端一致：字段是否存在的强校验发生在"绑定申请类型"时，发布时只做类型匹配
    expect(problems(unknown, FIELDS)).toEqual([])

    const numericOnText = clone()
    numericOnText.nodes[1].branches![0].condition = { logic: 'AND', rules: [{ field: 'title', op: 'GT', value: '5' }] }
    expect(findProblem(problems(numericOnText, FIELDS), '只能用于数字或日期字段')).toBe(true)

    const containsOnNumber = clone()
    containsOnNumber.nodes[1].branches![0].condition = { logic: 'AND', rules: [{ field: 'amount', op: 'CONTAINS', value: '5' }] }
    expect(findProblem(problems(containsOnNumber, FIELDS), '只能用于文本或选项字段')).toBe(true)
  })

  it('需要比较值的运算符未填值时报错；「为空/不为空」不需要值', () => {
    const empty = clone()
    empty.nodes[1].branches![0].condition = { logic: 'AND', rules: [{ field: 'amount', op: 'GT', value: '' }] }
    expect(findProblem(problems(empty, FIELDS), '未填写比较值')).toBe(true)

    const noValueNeeded = clone()
    noValueNeeded.nodes[1].branches![0].condition = { logic: 'AND', rules: [{ field: 'receiver', op: 'NOT_EMPTY' }] }
    expect(problems(noValueNeeded, FIELDS)).toEqual([])
  })
})

describe('展示辅助函数', () => {
  it('outgoingKeys：审批取 next，条件取各分支 next，结束为空', () => {
    const flow = validFlow()
    expect(outgoingKeys(flow.nodes[0])).toEqual(['c1'])
    expect(outgoingKeys(flow.nodes[1])).toEqual(['n2', 'n3'])
    expect(outgoingKeys(flow.nodes[4])).toEqual([])
    expect(outgoingKeys(null)).toEqual([])
  })

  it('isElseBranch 只认 else === true（而不是"没有条件就算默认"）', () => {
    expect(isElseBranch({ key: 'b', name: 'b' })).toBe(false)
    expect(isElseBranch({ key: 'b', name: 'b', else: true })).toBe(true)
  })

  it('conditionText：多条用 AND / OR 连接，并支持字段名映射', () => {
    const labelOf = (key: string): string => FIELDS.find((field) => field.value === key)?.label ?? key
    expect(
      conditionText({ logic: 'AND', rules: [{ field: 'amount', op: 'GT', value: 5000 }] }, labelOf)
    ).toBe('金额 大于 5000')
    expect(
      conditionText(
        { logic: 'OR', rules: [{ field: 'title', op: 'NOT_EMPTY' }, { field: 'amount', op: 'LT', value: 10 }] },
        labelOf
      )
    ).toBe('标题 不为空 或 金额 小于 10')
    expect(conditionText(null)).toBe('未配置条件')
  })

  it('flowOperatorRequiresValue：为空 / 不为空不需要比较值', () => {
    expect(flowOperatorRequiresValue('IS_EMPTY')).toBe(false)
    expect(flowOperatorRequiresValue('NOT_EMPTY')).toBe(false)
    expect(flowOperatorRequiresValue('EQ')).toBe(true)
  })

  it('approverRulesText：多来源取并集，用「、」连接', () => {
    expect(approverRulesText(null)).toBe('未配置审批人')
    expect(approverRulesText([])).toBe('未配置审批人')
    expect(approverRulesText([{ type: 'SPECIFIC_USER', userIds: [1, 2] }])).toBe('指定人员 2 人')
    expect(approverRulesText([{ type: 'ROLE', roleCode: 'admin' }, { type: 'BIZ_GROUP_APPROVERS' }])).toBe(
      '角色：admin、申请人所属部门的审批人'
    )
  })
})

describe('流程发布预检 · （抄送 / 审批时限 / 直属领导 / 上一节点指定）', () => {
  /**  全要素流程：部门负责人审批(限时24h) → 抄送管理员 → 金额判断 →（>5000）直属领导审批(限时48h) → 上一节点指定2人 → 结束 */
  function wave2(): FlowDefinition {
    return {
      start: 'n1',
      nodes: [
        {
          key: 'n1',
          type: 'APPROVAL',
          name: '部门负责人审批',
          signType: 'ANY_SIGN',
          approverRules: [{ type: 'ROLE', roleCode: 'admin' }],
          timeLimitHours: 24,
          next: 'cc1'
        },
        {
          key: 'cc1',
          type: 'CC',
          name: '抄送管理员',
          approverRules: [{ type: 'ROLE', roleCode: 'admin' }],
          next: 'c1'
        },
        {
          key: 'c1',
          type: 'CONDITION',
          name: '金额判断',
          branches: [
            {
              key: 'b1',
              name: '金额大于5000',
              next: 'n2',
              condition: { logic: 'AND', rules: [{ field: 'amount', op: 'GT', value: '5000' }] }
            },
            { key: 'b2', name: '其它情况', else: true, next: 'n3' }
          ]
        },
        {
          key: 'n2',
          type: 'APPROVAL',
          name: '直属领导审批',
          signType: 'ANY_SIGN',
          approverRules: [{ type: 'LEADER' }],
          timeLimitHours: 48,
          next: 'n3'
        },
        {
          key: 'n3',
          type: 'APPROVAL',
          name: '上一节点指定的审批人',
          signType: 'ANY_SIGN',
          approverRules: [{ type: 'PREV_ASSIGN', assignCount: 2 }],
          next: 'end'
        },
        { key: 'end', type: 'END', name: '结束' }
      ]
    }
  }

  function wave2Clone(): FlowDefinition {
    return JSON.parse(JSON.stringify(wave2())) as FlowDefinition
  }

  /** 抄送节点 + 一个后续审批节点（避免「至少一个审批节点」先报错，掩盖真正要测的规则） */
  function ccOnly(cc: FlowNode): FlowDefinition {
    return {
      start: cc.key,
      nodes: [
        { ...cc, next: 'n1' },
        {
          key: 'n1',
          type: 'APPROVAL',
          name: '后续审批',
          signType: 'ANY_SIGN',
          approverRules: [{ type: 'ROLE', roleCode: 'admin' }],
          next: 'end'
        },
        { key: 'end', type: 'END', name: '结束' }
      ]
    }
  }

  it(' 全要素流程通过预检（与后端 FlowDefinitionValidator 同构）', () => {
    expect(problems(wave2(), FIELDS)).toEqual([])
  })

  it('抄送节点：计数进「出边」，否则会被判成不可达而拦下合法流程', () => {
    expect(outgoingKeys({ key: 'cc1', type: 'CC', name: '抄送', next: 'c1' })).toEqual(['c1'])
    expect(outgoingKeys({ key: 'cc1', type: 'CC', name: '抄送', next: null })).toEqual([])
  })

  it('抄送节点：禁止配置签署方式 / 分支 / 审批时限', () => {
    expect(
      findProblem(
        problems(
          ccOnly({ key: 'cc1', type: 'CC', name: '抄送', signType: 'ANY_SIGN', approverRules: [{ type: 'ROLE', roleCode: 'admin' }] }),
          FIELDS
        ),
        '抄送节点，不应配置签署方式'
      )
    ).toBe(true)

    expect(
      findProblem(
        problems(
          ccOnly({
            key: 'cc1',
            type: 'CC',
            name: '抄送',
            approverRules: [{ type: 'ROLE', roleCode: 'admin' }],
            branches: [{ key: 'b1', name: '默认', else: true, next: 'n1' }]
          }),
          FIELDS
        ),
        '抄送节点，不应配置分支'
      )
    ).toBe(true)

    expect(
      findProblem(
        problems(
          ccOnly({ key: 'cc1', type: 'CC', name: '抄送', timeLimitHours: 12, approverRules: [{ type: 'ROLE', roleCode: 'admin' }] }),
          FIELDS
        ),
        '抄送节点，不应配置审批时限'
      )
    ).toBe(true)
  })

  it('抄送节点：禁用「申请人自选」与「上一节点指定」（对象须在提交时即可确定）', () => {
    expect(
      findProblem(
        problems(
          ccOnly({
            key: 'cc1',
            type: 'CC',
            name: '抄送',
            approverRules: [{ type: 'APPLICANT_CHOOSE', scope: 'ALL', minCount: 1, maxCount: 1 }]
          }),
          FIELDS
        ),
        '抄送节点，不支持'
      )
    ).toBe(true)

    expect(
      findProblem(
        problems(ccOnly({ key: 'cc1', type: 'CC', name: '抄送', approverRules: [{ type: 'PREV_ASSIGN', assignCount: 1 }] }), FIELDS),
        '抄送节点，不支持'
      )
    ).toBe(true)
  })

  it('抄送节点：至少要有一个抄送对象', () => {
    expect(findProblem(problems(ccOnly({ key: 'cc1', type: 'CC', name: '抄送' }), FIELDS), '未配置任何抄送对象')).toBe(true)
  })

  it('上一节点指定：最多一条 / 必须是唯一规则 / 只支持或签', () => {
    const multi = wave2Clone()
    const n3 = multi.nodes.find((node) => node.key === 'n3') as FlowNode
    n3.approverRules = [
      { type: 'PREV_ASSIGN', assignCount: 1 },
      { type: 'PREV_ASSIGN', assignCount: 1 }
    ]
    expect(findProblem(problems(multi, FIELDS), '最多只能有 1 条')).toBe(true)

    const mixed = wave2Clone()
    const mixedNode = mixed.nodes.find((node) => node.key === 'n3') as FlowNode
    mixedNode.approverRules = [{ type: 'PREV_ASSIGN', assignCount: 1 }, { type: 'ROLE', roleCode: 'admin' }]
    expect(findProblem(problems(mixed, FIELDS), '必须是该节点唯一的审批人规则')).toBe(true)

    const allSign = wave2Clone()
    ;(allSign.nodes.find((node) => node.key === 'n3') as FlowNode).signType = 'ALL_SIGN'
    expect(findProblem(problems(allSign, FIELDS), '只支持或签')).toBe(true)
  })

  it('上一节点指定：前面必须有审批节点，抄送不构成「上一节点」', () => {
    const noPreceding: FlowDefinition = {
      start: 'cc1',
      nodes: [
        { key: 'cc1', type: 'CC', name: '抄送', approverRules: [{ type: 'ROLE', roleCode: 'admin' }], next: 'n1' },
        { key: 'n1', type: 'APPROVAL', name: '待指定', signType: 'ANY_SIGN', approverRules: [{ type: 'PREV_ASSIGN', assignCount: 1 }], next: 'end' },
        { key: 'end', type: 'END', name: '结束' }
      ]
    }
    expect(findProblem(problems(noPreceding, FIELDS), '前面没有审批节点')).toBe(true)
  })

  it('审批时限：0 / 721 被拒，1 / 720 通过', () => {
    for (const bad of [0, -1, 721]) {
      const definition = wave2Clone()
      ;(definition.nodes.find((node) => node.key === 'n1') as FlowNode).timeLimitHours = bad
      expect(findProblem(problems(definition, FIELDS), '审批时限必须在 1~720 小时之间')).toBe(true)
    }
    for (const ok of [1, 720]) {
      const definition = wave2Clone()
      ;(definition.nodes.find((node) => node.key === 'n1') as FlowNode).timeLimitHours = ok
      expect(problems(definition, FIELDS)).toEqual([])
    }
  })

  it('nodeTypeLabel：节点类型有中文标签（详情/画布共用）', () => {
    expect(nodeTypeLabel('APPROVAL')).toBe('审批节点')
    expect(nodeTypeLabel('CC')).toBe('抄送节点')
    expect(nodeTypeLabel('CONDITION')).toBe('条件分支')
    expect(nodeTypeLabel('END')).toBe('结束')
  })
})

describe(' · 上级部门主管 / 指定范围（与后端 ApproverRuleType 同步）', () => {
  it('「上级部门主管」无需参数，不会报错', () => {
    const flow = clone()
    flow.nodes[0].approverRules = [{ type: 'PARENT_DEPT_APPROVERS' }]
    expect(problems(flow, FIELDS)).toEqual([])
  })

  it('「上级部门主管」与「部门审批人」是两种来源，文案必须可区分', () => {
    // 两者只差一个层级，但摘要若写一样，配置者在监控页排查「这级到底是谁批的」时无从判断。
    expect(approverRuleText({ type: 'BIZ_GROUP_APPROVERS' })).toContain('所属部门')
    expect(approverRuleText({ type: 'PARENT_DEPT_APPROVERS' })).toContain('上级部门')
    expect(approverRuleText({ type: 'PARENT_DEPT_APPROVERS' })).not.toBe(
      approverRuleText({ type: 'BIZ_GROUP_APPROVERS' })
    )
  })

  it('「上一节点指定」的参数槽位含 assignScope（设计器据此渲染「指定范围」下拉）', () => {
    expect(approverRuleParams('PREV_ASSIGN')).toContain('assignCount')
    expect(approverRuleParams('PREV_ASSIGN')).toContain('assignScope')
    // 新增来源同样必须登记槽位：params 为空数组表示「无需参数」，未登记会让属性面板整块不渲染。
    expect(approverRuleParams('PARENT_DEPT_APPROVERS')).toEqual([])
  })

  it('assignScopeSuffix：缺省为空串（存量流程不该看起来"被改了"），显式范围才有后缀', () => {
    expect(assignScopeSuffix(null)).toBe('')
    expect(assignScopeSuffix(undefined)).toBe('')
    for (const option of ASSIGN_SCOPE_OPTIONS) {
      expect(assignScopeSuffix(option.value)).toContain(option.label)
    }
  })

  it('「上一节点指定」摘要带上范围：不限制与限 IT执行人必须能区分', () => {
    const unrestricted = approverRuleText({ type: 'PREV_ASSIGN', assignCount: 1 })
    const restricted = approverRuleText({
      type: 'PREV_ASSIGN',
      assignCount: 1,
      assignScope: 'IT_EXECUTOR'
    })
    expect(unrestricted).toBe('上一节点指定 1 人')
    expect(restricted).not.toBe(unrestricted)
    expect(restricted).toContain('IT执行人')
  })

  it('范围代码与后端枚举字面量一致（改写会让存量流程的 assignScope 静默失效）', () => {
    expect(ASSIGN_SCOPE_OPTIONS.map((item) => item.value)).toEqual(['ALL', 'IT_EXECUTOR'])
  })
})
