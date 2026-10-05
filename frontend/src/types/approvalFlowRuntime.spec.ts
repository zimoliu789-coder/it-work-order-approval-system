import { describe, expect, it } from 'vitest'
import {
  PROCESS_FIELD_OPTIONS,
  conditionIsRuntimeDependent,
  flowHasRuntimeFeature,
  isProcessField,
  nodeHasRuntimeAction,
  processFieldIsKnown,
  processFieldLabel,
  validateFlowForPublish,
  type FlowDefinition,
  type FlowFieldOption,
  type FlowNode
} from '@/types/approvalFlow'

/**
 *  —— 运行期条件的**前端镜像**校验
 *
 * <h2>为什么这些用例必须存在</h2>
 * M2 引入了「运行期特性」这一概念：驳回改道、超时加签、条件引用 `process.*` 字段。
 * 它有两个极易出错的地方，且都不会在编译期被发现：
 * <ol>
 *   <li><b>特性判定漂移</b>：{@link flowHasRuntimeFeature} 是"要不要走新代码路径"的
 *       唯一判据，也是发布闸门的触发条件。它一旦漏判（例如只认 GOTO、不认 ADD_SIGN），
 *       前端会认为"普通流程"，而后端拒发，用户白填一遍。</li>
 *   <li><b>`process.*` 被当成表单字段</b>：校验器原本只认表单 schema。若不先分流，
 *       `process.prevNodeResult` 会走进"表单字段"分支 —— 轻则静默通过，
 *       重则报出"字段不存在"，逼着配置者去表单里找一个永远不存在的东西。</li>
 * </ol>
 *
 * <p>与 {@link validateFlowForPublish} 的关系：这里的断言全部对齐后端
 * `FlowDefinitionValidator` 的同名分支，只断**关键子串**（两端文案措辞可能微调），
 * 不断整句 —— 见 `test-fixtures/golden/flow-validation-golden.json` 的既有约定。
 */

const FIELDS: FlowFieldOption[] = [
  { value: 'amount', label: '金额', kind: 'NUMBER' },
  { value: 'title', label: '标题', kind: 'TEXT' }
]

function problems(definition: FlowDefinition | null | undefined, fields: FlowFieldOption[] = FIELDS): string[] {
  return validateFlowForPublish(definition, fields)
}

function findProblem(list: string[], fragment: string): boolean {
  return list.some((item) => item.includes(fragment))
}

/** 节点速查（配合 `as FlowNode` 直接改属性做"逐项破坏"） */
function nodeOf(definition: FlowDefinition, key: string): FlowNode {
  return definition.nodes.find((node) => node.key === key) as FlowNode
}

/**
 * 运行期基准流程：主管审批 → 条件（读 `process.prevNodeResult`）→ 归档确认 → 结束。
 *
 * <p>刻意让引用 `process.*` 的条件**位于一个审批节点之后** —— 这既是后端图级硬约束，
 * 也是"合法的运行期流程"该有的样子，便于后续逐项破坏。
 */
function runtimeFlow(): FlowDefinition {
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
        name: '上一节点结果判断',
        branches: [
          {
            key: 'b1',
            name: '上一节点已通过',
            next: 'n2',
            condition: {
              logic: 'AND',
              rules: [{ field: 'process.prevNodeResult', op: 'EQ', value: 'APPROVED' }]
            }
          },
          { key: 'b2', name: '其它情况', else: true, next: 'end' }
        ]
      },
      {
        key: 'n2',
        type: 'APPROVAL',
        name: '归档确认',
        signType: 'ANY_SIGN',
        approverRules: [{ type: 'ROLE', roleCode: 'admin' }],
        next: 'end'
      },
      { key: 'end', type: 'END', name: '结束' }
    ]
  }
}

function clone(): FlowDefinition {
  return JSON.parse(JSON.stringify(runtimeFlow())) as FlowDefinition
}

/**
 * 与第二期完全一致的普通流程（无任何运行期特征），用于反向断言。
 *
 * <p>反向断言**必须**基于它而不是 {@link runtimeFlow} 的克隆 —— 后者含 `process.*` 条件，
 * 无论怎么改 `onReject` 都恒为"含运行期特性"，那样的断言是假绿。
 */
function plainFlow(): FlowDefinition {
  return {
    start: 'n1',
    nodes: [
      {
        key: 'n1',
        type: 'APPROVAL',
        name: '主管审批',
        signType: 'ANY_SIGN',
        approverRules: [{ type: 'ROLE', roleCode: 'admin' }],
        next: 'n2'
      },
      {
        key: 'n2',
        type: 'APPROVAL',
        name: '归档确认',
        signType: 'ANY_SIGN',
        approverRules: [{ type: 'ROLE', roleCode: 'admin' }],
        next: 'end'
      },
      { key: 'end', type: 'END', name: '结束' }
    ]
  }
}

function plainClone(): FlowDefinition {
  return JSON.parse(JSON.stringify(plainFlow())) as FlowDefinition
}

describe('M2 运行期字段（process.* 白名单）', () => {
  it('白名单内 6 个字段齐全，且与后端 ProcessFieldCatalog 同构', () => {
    expect(PROCESS_FIELD_OPTIONS.map((item) => item.value)).toEqual([
      'process.prevNodeResult',
      'process.prevNodeHours',
      'process.elapsedHours',
      'process.anyRejected',
      'process.rejectCount',
      'process.activatedCount'
    ])
  })

  it('isProcessField 只看前缀；processFieldIsKnown 认白名单', () => {
    expect(isProcessField('process.prevNodeResult')).toBe(true)
    expect(isProcessField('process.foo')).toBe(true)
    expect(isProcessField('amount')).toBe(false)
    expect(isProcessField(null)).toBe(false)
    // 前缀对但拼错 → 必须被拦下（后端同样严格；静默放行会变成"运行期永不命中"的鬼故事）
    expect(processFieldIsKnown('process.foo')).toBe(false)
    expect(processFieldIsKnown('process.prevNodeResult')).toBe(true)
  })

  it('processFieldLabel：已知取标签，未知原样返回（不要凭空造名字）', () => {
    expect(processFieldLabel('process.prevNodeResult')).toBe('上一节点结果')
    expect(processFieldLabel('process.elapsedHours')).toBe('工单已耗时(小时)')
    expect(processFieldLabel('process.unknown')).toBe('process.unknown')
    expect(processFieldLabel(null)).toBe('')
  })

  it('枚举类运行期字段带内建可选值（否则配置者只能靠手打，极易静默错配）', () => {
    const prevResult = PROCESS_FIELD_OPTIONS.find((item) => item.value === 'process.prevNodeResult')
    expect(prevResult?.options?.map((item) => item.value)).toEqual(['APPROVED', 'REJECTED'])
    const anyRejected = PROCESS_FIELD_OPTIONS.find((item) => item.value === 'process.anyRejected')
    expect(anyRejected?.options?.map((item) => item.value)).toEqual(['true', 'false'])
    // 数值类字段不该有枚举值
    const hours = PROCESS_FIELD_OPTIONS.find((item) => item.value === 'process.prevNodeHours')
    expect(hours?.options).toBeUndefined()
  })

  it('conditionIsRuntimeDependent：只要有一条规则引用 process.* 即为真', () => {
    expect(
      conditionIsRuntimeDependent({ logic: 'AND', rules: [{ field: 'process.elapsedHours', op: 'GT', value: '24' }] })
    ).toBe(true)
    expect(conditionIsRuntimeDependent({ logic: 'AND', rules: [{ field: 'amount', op: 'GT', value: '1' }] })).toBe(false)
    expect(conditionIsRuntimeDependent(null)).toBe(false)
    expect(conditionIsRuntimeDependent({ logic: 'AND', rules: [] })).toBe(false)
  })
})

describe('M2 运行期特性判定（flowHasRuntimeFeature = 发布闸门的触发条件）', () => {
  it('普通流程（第二期口径）不含运行期特性', () => {
    expect(flowHasRuntimeFeature(plainFlow())).toBe(false)
    expect(flowHasRuntimeFeature(null)).toBe(false)
  })

  it('条件引用 process.* → 含运行期特性', () => {
    expect(flowHasRuntimeFeature(runtimeFlow())).toBe(true)
  })

  it('驳回改道 GOTO → 含；驳回终止 TERMINATE / 不配 → 不含', () => {
    const goto = clone()
    nodeOf(goto, 'n1').onReject = { action: 'GOTO', target: 'n2' }
    expect(flowHasRuntimeFeature(goto)).toBe(true)
    expect(nodeHasRuntimeAction(nodeOf(goto, 'n1'))).toBe(true)

    const terminate = plainClone()
    nodeOf(terminate, 'n1').onReject = { action: 'TERMINATE' }
    expect(flowHasRuntimeFeature(terminate)).toBe(false)
    expect(nodeHasRuntimeAction(nodeOf(terminate, 'n1'))).toBe(false)

    // 不配 onReject（第二期口径：驳回即终止整单）同样不该被判为运行期流程
    expect(flowHasRuntimeFeature(plainFlow())).toBe(false)
  })

  it('超时加签 ADD_SIGN / 超时改道 GOTO → 含；仅提醒 NOTIFY → 不含', () => {
    const addSign = clone()
    nodeOf(addSign, 'n1').onTimeout = { action: 'ADD_SIGN', afterHours: 24 }
    expect(flowHasRuntimeFeature(addSign)).toBe(true)

    const timeoutGoto = clone()
    nodeOf(timeoutGoto, 'n1').onTimeout = { action: 'GOTO', afterHours: 24, target: 'n2' }
    expect(flowHasRuntimeFeature(timeoutGoto)).toBe(true)

    const notify = plainClone()
    nodeOf(notify, 'n1').onTimeout = { action: 'NOTIFY', afterHours: 24 }
    expect(flowHasRuntimeFeature(notify)).toBe(false)
    expect(nodeHasRuntimeAction(nodeOf(notify, 'n1'))).toBe(false)
  })
})

describe('M2 发布预检：运行期动作（驳回改道 / 超时升级）', () => {
  it('基准运行期流程通过预检（前置审批 + process.* 条件，与后端同构）', () => {
    expect(problems(runtimeFlow())).toEqual([])
  })

  it('驳回改道：GOTO 缺目标 / action 拼错 都被拦住（不得静默降级为终止）', () => {
    const noTarget = clone()
    nodeOf(noTarget, 'n1').onReject = { action: 'GOTO' }
    expect(findProblem(problems(noTarget), '「驳回处理」配置不完整')).toBe(true)

    const typo = clone()
    // 故意把 GOTO 打成 GOT：若被当成默认 TERMINATE，配置者会以为改道生效，实际每笔驳回都在终止整单
    nodeOf(typo, 'n1').onReject = { action: 'GOT' as never, target: 'n2' }
    expect(findProblem(problems(typo), '「驳回处理」配置不完整')).toBe(true)
  })

  it('驳回改道：目标必须存在且是审批 / 抄送节点', () => {
    const notExist = clone()
    nodeOf(notExist, 'n1').onReject = { action: 'GOTO', target: 'nope' }
    expect(findProblem(problems(notExist), '「驳回改道」的目标节点不存在：nope')).toBe(true)

    const toCondition = clone()
    nodeOf(toCondition, 'n1').onReject = { action: 'GOTO', target: 'c1' }
    expect(findProblem(problems(toCondition), '「驳回改道」的目标必须是审批或抄送节点')).toBe(true)

    const toEnd = clone()
    nodeOf(toEnd, 'n1').onReject = { action: 'GOTO', target: 'end' }
    expect(findProblem(problems(toEnd), '「驳回改道」的目标必须是审批或抄送节点')).toBe(true)
  })

  it('驳回改道到合法审批节点 → 通过', () => {
    const ok = clone()
    nodeOf(ok, 'n1').onReject = { action: 'GOTO', target: 'n2' }
    expect(problems(ok)).toEqual([])
  })

  it('超时加签：未设阈值 / 阈值超上限 分别报错', () => {
    const noHours = clone()
    nodeOf(noHours, 'n1').onTimeout = { action: 'ADD_SIGN' }
    expect(findProblem(problems(noHours), '「超时加签」未设置超时阈值')).toBe(true)

    const tooBig = clone()
    nodeOf(tooBig, 'n1').onTimeout = { action: 'ADD_SIGN', afterHours: 721 }
    expect(findProblem(problems(tooBig), '「超时加签」阈值超出上限 720 小时')).toBe(true)

    const boundary = clone()
    nodeOf(boundary, 'n1').onTimeout = { action: 'ADD_SIGN', afterHours: 720 }
    expect(problems(boundary)).toEqual([])
  })

  it('超时加签：显式加签人时逐条校验规则本身；不填＝继承原节点规则（合法）', () => {
    const badApprover = clone()
    nodeOf(badApprover, 'n1').onTimeout = {
      action: 'ADD_SIGN',
      afterHours: 24,
      // ROLE 缺 roleCode：validateRule 必须报出来（不能因为"是加签人"就放过）
      approvers: [{ type: 'ROLE' }]
    }
    expect(problems(badApprover).length).toBeGreaterThan(0)

    const inherit = clone()
    nodeOf(inherit, 'n1').onTimeout = { action: 'ADD_SIGN', afterHours: 24, approvers: null }
    expect(problems(inherit)).toEqual([])
  })

  it('超时改道：GOTO 缺目标 / action 拼错 被拦住；阈值非正也被拦住', () => {
    const noTarget = clone()
    nodeOf(noTarget, 'n1').onTimeout = { action: 'GOTO', afterHours: 24 }
    expect(findProblem(problems(noTarget), '「超时处理」配置不合法')).toBe(true)

    const typo = clone()
    nodeOf(typo, 'n1').onTimeout = { action: 'ADD_SING' as never, afterHours: 24 }
    expect(findProblem(problems(typo), '「超时处理」配置不合法')).toBe(true)

    const zeroHours = clone()
    nodeOf(zeroHours, 'n1').onTimeout = { action: 'NOTIFY', afterHours: 0 }
    expect(findProblem(problems(zeroHours), '「超时处理」配置不合法')).toBe(true)
  })

  it('超时仅提醒 NOTIFY：不设阈值也合法（= 第二期行为，不该被新规则误伤）', () => {
    const notify = clone()
    nodeOf(notify, 'n1').onTimeout = { action: 'NOTIFY' }
    expect(problems(notify)).toEqual([])
  })
})

describe('M2 发布预检：process.* 必须先分流，不能落进表单字段查找', () => {
  it('未在白名单的运行期字段被拒（而不是"字段不存在"式误报）', () => {
    const definition = clone()
    const branch = nodeOf(definition, 'c1').branches?.[0]
    if (branch?.condition) {
      branch.condition.rules = [{ field: 'process.foo', op: 'EQ', value: 'x' }]
    }
    const list = problems(definition)
    expect(findProblem(list, '引用的运行期字段「process.foo」不存在')).toBe(true)
  })

  it('数值运算符用于非数值的运行期字段 → 报出该运行期字段的可读名（证明走的是 process 分支）', () => {
    const definition = clone()
    const branch = nodeOf(definition, 'c1').branches?.[0]
    if (branch?.condition) {
      branch.condition.rules = [{ field: 'process.prevNodeResult', op: 'GT', value: '1' }]
    }
    expect(
      findProblem(problems(definition), '只能用于数字或日期字段，而「上一节点结果」不是')
    ).toBe(true)
  })

  it('包含运算符用于数值型运行期字段 → 同样被拒', () => {
    const definition = clone()
    const branch = nodeOf(definition, 'c1').branches?.[0]
    if (branch?.condition) {
      branch.condition.rules = [{ field: 'process.prevNodeHours', op: 'CONTAINS', value: '1' }]
    }
    expect(
      findProblem(problems(definition), '只能用于文本或选项字段，而「上一节点耗时(小时)」不是')
    ).toBe(true)
  })

  it('合法运行期字段 + 匹配的运算符 → 通过（不被误伤）', () => {
    const definition = clone()
    const branch = nodeOf(definition, 'c1').branches?.[0]
    if (branch?.condition) {
      branch.condition.rules = [{ field: 'process.elapsedHours', op: 'GT', value: '24' }]
    }
    expect(problems(definition)).toEqual([])
  })

  it('引用 process.* 的条件位于首个审批节点之前 → 拦住（否则提交后无人能推动＝死单）', () => {
    // 条件当起点：c1 的两条分支一条进审批、一条直接结束；process.* 条件此时"还没有前置审批"
    const definition: FlowDefinition = {
      start: 'c1',
      nodes: [
        {
          key: 'c1',
          type: 'CONDITION',
          name: '运行期条件（位置错误）',
          branches: [
            {
              key: 'b1',
              name: '已耗时大于24小时',
              next: 'n1',
              condition: { logic: 'AND', rules: [{ field: 'process.elapsedHours', op: 'GT', value: '24' }] }
            },
            { key: 'b2', name: '其它情况', else: true, next: 'end' }
          ]
        },
        {
          key: 'n1',
          type: 'APPROVAL',
          name: '主管审批',
          signType: 'ANY_SIGN',
          approverRules: [{ type: 'ROLE', roleCode: 'admin' }],
          next: 'end'
        },
        { key: 'end', type: 'END', name: '结束' }
      ]
    }
    expect(findProblem(problems(definition), '前面没有审批节点')).toBe(true)
  })

  it('同样位置但条件不引用 process.* → 不触发该规则（避免误伤普通前置条件）', () => {
    const definition: FlowDefinition = {
      start: 'c1',
      nodes: [
        {
          key: 'c1',
          type: 'CONDITION',
          name: '普通前置条件',
          branches: [
            {
              key: 'b1',
              name: '金额大于5000',
              next: 'n1',
              condition: { logic: 'AND', rules: [{ field: 'amount', op: 'GT', value: '5000' }] }
            },
            { key: 'b2', name: '其它情况', else: true, next: 'end' }
          ]
        },
        {
          key: 'n1',
          type: 'APPROVAL',
          name: '主管审批',
          signType: 'ANY_SIGN',
          approverRules: [{ type: 'ROLE', roleCode: 'admin' }],
          next: 'end'
        },
        { key: 'end', type: 'END', name: '结束' }
      ]
    }
    expect(problems(definition)).toEqual([])
  })
})

describe('M2 发布预检：运行期流程禁用「申请人自选」', () => {
  it('含运行期特性的流程里配 APPLICANT_CHOOSE → 拦住（自选结果不随工单保存）', () => {
    const definition = clone()
    nodeOf(definition, 'n2').approverRules = [
      { type: 'APPLICANT_CHOOSE', scope: 'ALL', minCount: 1, maxCount: 1 }
    ]
    expect(findProblem(problems(definition), '含运行期条件的流程不支持它')).toBe(true)
  })

  it('普通流程里配 APPLICANT_CHOOSE → 合法（这条严格规则只对运行期流程生效）', () => {
    const definition = plainFlow()
    nodeOf(definition, 'n1').approverRules = [
      { type: 'APPLICANT_CHOOSE', scope: 'ALL', minCount: 1, maxCount: 2 }
    ]
    expect(problems(definition)).toEqual([])
    expect(flowHasRuntimeFeature(definition)).toBe(false)
  })
})
