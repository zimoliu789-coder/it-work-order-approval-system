import { describe, expect, it } from 'vitest'
import {
  CONDITION_KIND_GROUP,
  CONDITION_KIND_RULE,
  conditionDepth,
  conditionGroup,
  conditionIsRuntimeDependent,
  conditionKindOf,
  conditionText,
  flowConditionMaxDepth,
  flowHasRuntimeFeature,
  isConditionGroup,
  isConditionRule,
  validateFlowForPublish,
  type FlowCondition,
  type FlowConditionRule,
  type FlowDefinition,
  type FlowFieldOption
} from '@/types/approvalFlow'

/**
 * M3-A 条件多层 AND/OR 嵌套专项测试（ · ）—— 前端侧。
 *
 * <h2>与后端 `ConditionNestingTest` 的分工</h2>
 * 后端那套测的是**求值**（走哪条分支）；前端没有求值器（分支由后端在提交/推进时算），
 * 因此这里测的是前端真正承担的三件事：
 * <ol>
 *   <li><b>本地预检</b>：深度上限、空组、组上残留脏值、非法 kind —— 与后端同构，</li>
 *   <li><b>运行期依赖判定</b>：漏了递归会让"该 INACTIVE 的节点"被判成可下结论（静默走错分支），</li>
 *   <li><b>摘要文案</b>：括号表达结合顺序，「A 且 B 或 C」与「A 且 (B 或 C)」不可压平。</li>
 * </ol>
 *
 * <h2>为什么这些用例值得独立成文件</h2>
 * 与后端同理：本批的改动不是"新增一个功能"，而是**把四处遍历从单层改成递归**。
 * 漏一处不是编译错误，而是三种不同形态的静默失效，因此每处都单独钉一条。
 */

// ---------------------------------------------------------------------
// 夹具：与设计器/演示数据走同一套工厂，避免测试自造第三种形状
// ---------------------------------------------------------------------

const FIELDS: FlowFieldOption[] = [
  { value: 'amount', label: '金额', kind: 'NUMBER' },
  { value: 'title', label: '标题', kind: 'TEXT' },
  { value: 'remark', label: '备注', kind: 'OPTION_MULTI' }
]

function rule(field: string, op: FlowConditionRule['op'], value?: unknown): FlowConditionRule {
  return { field, op, value }
}

function cond(logic: 'AND' | 'OR', ...rules: FlowConditionRule[]): FlowCondition {
  return { logic, rules }
}

/**
 * 三层嵌套：AND（金额>5000 且 OR（标题含「紧急」 或 AND（备注含「加急」）））。
 *
 * 形状刻意选成 "AND 套 OR 套 AND"：若实现里某一层的 logic 被串了（典型写法错误是
 * 递归时传错 logic 或复用外层变量），全 AND 的形状恰好也能通过，而这个形状不会。
 */
function threeLevel(): FlowCondition {
  return cond(
    'AND',
    rule('amount', 'GT', 5000),
    conditionGroup(
      cond('OR', rule('title', 'CONTAINS', '紧急'), conditionGroup(cond('AND', rule('remark', 'CONTAINS', '加急'))))
    )
  )
}

/** 把条件装进一个结构完整的流程，便于走本地预检（唯一 else 出口 + 至少一个审批节点） */
function flowWith(condition: FlowCondition): FlowDefinition {
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
        name: '条件判断',
        branches: [
          { key: 'b1', name: '命中', next: 'n2', condition },
          { key: 'b2', name: '其它情况', else: true, next: 'end' }
        ]
      },
      {
        key: 'n2',
        type: 'APPROVAL',
        name: '复核',
        signType: 'ANY_SIGN',
        approverRules: [{ type: 'ROLE', roleCode: 'admin' }],
        next: 'end'
      },
      { key: 'end', type: 'END', name: '结束' }
    ]
  }
}

function withKind(kind: string | null): FlowConditionRule {
  return { kind: kind as FlowConditionRule['kind'], field: '', op: 'EQ' }
}

// =====================================================================
// ① 模型：kind 归一 / 组工厂 / 深度
// =====================================================================

describe('① 模型：kind 归一、组信封、深度', () => {
  it('kind 为 null / 空白 / 未知值一律判「不是组」—— 无法识别的取值退化成最保守的旧语义', () => {
    expect(isConditionGroup({ field: 'amount', op: 'EQ' })).toBe(false)
    expect(isConditionGroup(withKind(null))).toBe(false)
    expect(isConditionGroup(withKind(''))).toBe(false)
    expect(isConditionGroup(withKind('   '))).toBe(false)
    expect(isConditionGroup(withKind('XOR'))).toBe(false)
    expect(isConditionGroup(withKind('GROUP'))).toBe(true)
    expect(isConditionGroup(withKind('group'))).toBe(true)
    expect(isConditionGroup(withKind('  GROUP  '))).toBe(true)
    expect(isConditionGroup(withKind('RULE'))).toBe(false)
    expect(isConditionGroup(null)).toBe(false)
    expect(isConditionGroup(undefined)).toBe(false)
  })

  it('isConditionRule 与 isConditionGroup 恒互补 —— 不存在两者都 false 的第三态', () => {
    const kinds: Array<string | null> = [null, '', '  ', 'RULE', 'rule', 'GROUP', 'Group', 'XOR']
    for (const kind of kinds) {
      const candidate = withKind(kind)
      expect(
        isConditionGroup(candidate) !== isConditionRule(candidate),
        `kind=${String(kind)} 时出现同真或同假`
      ).toBe(true)
    }
  })

  it('conditionKindOf 归一后升序大写；未知取值原样返回（留给校验器报错，不抛）', () => {
    expect(conditionKindOf(withKind('  group '))).toBe(CONDITION_KIND_GROUP)
    expect(conditionKindOf(withKind(null))).toBe('')
    expect(conditionKindOf(withKind('xor'))).toBe('XOR')
  })

  it('conditionGroup 工厂只写 kind 与 condition，不制造 field/op/value 脏值', () => {
    const envelope = conditionGroup(cond('AND', rule('amount', 'GT', 1)))
    expect(envelope.kind).toBe(CONDITION_KIND_GROUP)
    expect(envelope.condition).toBeTruthy()
    expect('field' in envelope).toBe(false)
    expect('op' in envelope).toBe(false)
    expect('value' in envelope).toBe(false)
    expect(validateGroupEnvelopeIsClean(envelope)).toBe(true)
  })

  it('深度：null=0、只有规则的组=1、逐层 +1，混排时取最深的那条链', () => {
    expect(conditionDepth(null)).toBe(0)
    expect(conditionDepth(cond('AND', rule('amount', 'GT', 1)))).toBe(1)
    expect(conditionDepth(cond('AND'))).toBe(1)
    expect(conditionDepth(cond('AND', conditionGroup(cond('OR', rule('amount', 'GT', 1)))))).toBe(2)
    expect(conditionDepth(threeLevel())).toBe(3)
    expect(
      conditionDepth(
        cond(
          'AND',
          rule('amount', 'GT', 1),
          conditionGroup(cond('OR', conditionGroup(cond('AND', rule('title', 'EQ', 'x')))))
        )
      )
    ).toBe(3)
  })

  it('嵌套上限兜底默认为 3 —— 后端发布校验与前端"添加条件组"按钮共用同一读取入口', () => {
    // W4-D 起：真值由后端 /approval-flows/design-meta 下发（applyFlowDesignMeta 覆盖）。
    // 本断言锁的是**接口不可用时的降级值**；它与后端真值的一致性由共享金样例
    // test-fixtures/golden/flow-design-meta.json 钉死（后端单测读同一个文件）。
    expect(flowConditionMaxDepth()).toBe(3)
  })

  /** 组信封上不该有任何 field/op/value —— 与校验器的判据同源，单独抽出来便于两处复用 */
  function validateGroupEnvelopeIsClean(envelope: FlowConditionRule): boolean {
    return envelope.field === undefined && envelope.op === undefined && envelope.value === undefined
  }
})

// =====================================================================
// ② 运行期依赖判定（本批静默失效风险最高的一处）
// =====================================================================

describe('② 运行期依赖判定：必须递归到任意深度', () => {
  it('直接子级引用 process.* → 依赖（第二期行为必须原样保持）', () => {
    expect(
      conditionIsRuntimeDependent(
        cond('AND', rule('amount', 'GT', 1), rule('process.prevNodeResult', 'EQ', 'REJECTED'))
      )
    ).toBe(true)
    expect(conditionIsRuntimeDependent(cond('AND', rule('amount', 'GT', 1), rule('title', 'EQ', 'x')))).toBe(
      false
    )
  })

  it('只有嵌套组里引用 process.* → 依然必须判为依赖（漏这条会让工单静默走错分支）', () => {
    const condition = cond(
      'AND',
      rule('amount', 'GT', 5000),
      conditionGroup(
        cond('OR', rule('process.prevNodeResult', 'EQ', 'REJECTED'), rule('title', 'CONTAINS', '紧急'))
      )
    )
    expect(
      conditionIsRuntimeDependent(condition),
      '判不出来的话，提交时就会给这条分支定态，而不是留到运行期再算'
    ).toBe(true)
  })

  it('三层深处才有 process.* → 同样必须判为依赖（递归不能只走一层）', () => {
    const condition = cond(
      'AND',
      rule('amount', 'GT', 5000),
      conditionGroup(
        cond(
          'OR',
          rule('title', 'CONTAINS', '紧急'),
          conditionGroup(cond('AND', rule('process.elapsedHours', 'GT', 48)))
        )
      )
    )
    expect(conditionIsRuntimeDependent(condition)).toBe(true)
  })

  it('嵌套组里全是表单字段 / 空组 / 空条件 → 不依赖', () => {
    expect(conditionIsRuntimeDependent(threeLevel())).toBe(false)
    expect(conditionIsRuntimeDependent(cond('AND', conditionGroup(cond('OR'))))).toBe(false)
    expect(conditionIsRuntimeDependent(null)).toBe(false)
    expect(conditionIsRuntimeDependent(cond('AND'))).toBe(false)
  })

  it('端到端：嵌套组里的 process.* 必须让整个流程被识别为运行期流程', () => {
    const runtime = flowWith(
      cond(
        'AND',
        rule('amount', 'GT', 5000),
        conditionGroup(cond('OR', rule('process.anyRejected', 'EQ', 'true')))
      )
    )
    expect(
      flowHasRuntimeFeature(runtime),
      'flowHasRuntimeFeature 是"走不走新代码路径"的唯一判据；漏了嵌套组就会用第二期的路径处理运行期条件'
    ).toBe(true)

    expect(flowHasRuntimeFeature(flowWith(cond('AND', rule('amount', 'GT', 5000))))).toBe(false)
  })
})

// =====================================================================
// ③ 摘要文案：括号与顺序
// =====================================================================

describe('③ 摘要文案：括号表达结合顺序', () => {
  it('三层嵌套文案逐字钉死：组内必须加括号，层级不能被压平', () => {
    expect(conditionText(threeLevel(), (key) => key)).toBe(
      'amount 大于 5000 且 （title 包含 紧急 或 （remark 包含 加急））'
    )
  })

  it('单条规则的组也会加括号 —— 括号表达的是"这是一组"，不是"里面有几条"', () => {
    expect(conditionText(cond('AND', conditionGroup(cond('OR', rule('amount', 'GT', 5000)))), (k) => k)).toBe(
      '（amount 大于 5000）'
    )
  })

  it('不传 labelOf 时回显字段 key；组内 condition 缺失时给出「未配置条件」而不是空白', () => {
    expect(conditionText(cond('AND', rule('amount', 'GT', 5000)))).toBe('amount 大于 5000')
    const brokenGroup: FlowConditionRule = { kind: CONDITION_KIND_GROUP, condition: null }
    expect(conditionText(cond('AND', brokenGroup))).toBe('（未配置条件）')
  })
})

// =====================================================================
// ④ 本地预检：与后端同构的递归校验
// =====================================================================

describe('④ 本地预检：深度上限 / 空组 / 脏值 / 非法 kind / 向后兼容', () => {
  it('合法的三层嵌套零问题', () => {
    expect(validateFlowForPublish(flowWith(threeLevel()), FIELDS)).toEqual([])
  })

  it('第 3 层合法、第 4 层报错，且只报一条（不按层数复述同一个问题）', () => {
    const four = cond(
      'AND',
      rule('amount', 'GT', 5000),
      conditionGroup(
        cond(
          'AND',
          rule('title', 'CONTAINS', '紧急'),
          conditionGroup(
            cond('AND', rule('remark', 'CONTAINS', '加急'), conditionGroup(cond('AND', rule('amount', 'GT', 10000))))
          )
        )
      )
    )
    expect(conditionDepth(threeLevel())).toBe(3)
    expect(conditionDepth(four)).toBe(4)

    const problems = validateFlowForPublish(flowWith(four), FIELDS)
    expect(problems, problems.join(' | ')).toHaveLength(1)
    expect(problems[0]).toContain('条件嵌套层数超过上限')
    expect(problems[0]).toContain(`最多 ${flowConditionMaxDepth()} 层`)
  })

  it('空组：rules 为空 → 「嵌套条件组为空」；condition 为 null → 「是空的嵌套条件组」', () => {
    const emptyRules = validateFlowForPublish(
      flowWith(cond('AND', rule('amount', 'GT', 5000), conditionGroup(cond('AND')))),
      FIELDS
    )
    expect(emptyRules, emptyRules.join(' | ')).toHaveLength(1)
    expect(emptyRules[0]).toContain('嵌套条件组为空')

    const nullInner: FlowConditionRule = { kind: CONDITION_KIND_GROUP, condition: null }
    const nullProblems = validateFlowForPublish(flowWith(cond('AND', nullInner)), FIELDS)
    expect(nullProblems, nullProblems.join(' | ')).toHaveLength(1)
    expect(nullProblems[0]).toContain('是空的嵌套条件组')
  })

  it('组信封上残留 field/op/value → 报错（设计器切换类型写出的半残对象不许进库）', () => {
    const dirty: FlowConditionRule = {
      kind: CONDITION_KIND_GROUP,
      field: 'amount',
      op: 'GT',
      value: 5000,
      condition: cond('AND', rule('title', 'CONTAINS', '紧急'))
    }
    const problems = validateFlowForPublish(flowWith(cond('AND', dirty)), FIELDS)
    expect(problems, problems.join(' | ')).toHaveLength(1)
    expect(problems[0]).toContain('不应携带字段')
  })

  it('不认识的 kind → 报错而不是静默当 RULE 用（否则会被误报成「未选择字段」）', () => {
    const weird: FlowConditionRule = { kind: 'XOR' as FlowConditionRule['kind'], field: 'amount', op: 'GT', value: 5000 }
    const problems = validateFlowForPublish(flowWith(cond('AND', weird)), FIELDS)
    expect(problems, problems.join(' | ')).toHaveLength(1)
    expect(problems[0]).toContain('的种类不合法')
    expect(problems[0]).toContain('XOR')
  })

  it('递归把类型可比性校验带进嵌套组（「大于」用在文本字段上）', () => {
    const problems = validateFlowForPublish(
      flowWith(cond('AND', rule('amount', 'GT', 5000), conditionGroup(cond('OR', rule('title', 'GT', 1))))),
      FIELDS
    )
    expect(problems, problems.join(' | ')).toHaveLength(1)
    expect(problems[0]).toContain('只能用于数字或日期字段')
  })

  it('嵌套组里的 process.*：校验通过（运行期字段是合法字段），但流程被识别为运行期流程', () => {
    const definition = flowWith(
      cond(
        'AND',
        rule('amount', 'GT', 5000),
        conditionGroup(cond('OR', rule('process.prevNodeResult', 'EQ', 'REJECTED')))
      )
    )
    expect(validateFlowForPublish(definition, FIELDS)).toEqual([])
    expect(flowHasRuntimeFeature(definition)).toBe(true)
  })

  it('向后兼容：没写 kind 的存量条件与显式 kind=RULE 逐项等价', () => {
    const legacy = cond('AND', { field: 'amount', op: 'GT', value: 5000 })
    const explicit = cond('AND', {
      kind: CONDITION_KIND_RULE,
      field: 'amount',
      op: 'GT',
      value: 5000
    })
    expect(validateFlowForPublish(flowWith(legacy), FIELDS)).toEqual([])
    expect(validateFlowForPublish(flowWith(explicit), FIELDS)).toEqual([])
    expect(conditionText(legacy)).toBe(conditionText(explicit))
    expect(conditionDepth(legacy)).toBe(conditionDepth(explicit))
  })
})
