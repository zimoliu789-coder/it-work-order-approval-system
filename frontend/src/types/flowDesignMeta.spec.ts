import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import {
  APPROVER_RULE_TYPE_OPTIONS,
  FLOW_SCOPE_OPTIONS,
  applyFlowDesignMeta,
  approverRuleParams,
  flowConditionMaxDepth,
  flowScopeForbiddenRuleTypes,
  type ApproverRuleTypeCode,
  type FlowDesignMeta,
  type FlowScopeCode
} from '@/types/approvalFlow'

/**
 * 设计器约束「共享金样例」一致性测试（前端侧）·  · W4-D / C8。
 *
 * <h2>它解决什么问题</h2>
 * W4-D 之前，「条件树深度上限」与「各业务域禁用的审批人来源」在前端各有一份硬编码副本
 * （`FLOW_CONDITION_MAX_DEPTH`、`BORROW_FORBIDDEN_RULE_TYPES`），后端另有一份内联判定。
 * 副本漂移不会报错，只会表现为「设计器里配得好好的，点发布被后端拒掉」——
 * 这正是 M4a 起就在治理的那类缝隙，C8 把它作为工程债单独列出。
 *
 * 改造后真值只有一处（后端枚举），经 `GET /api/approval-flows/design-meta` 下发；
 * 前端保留的副本**降级为「接口不可用时的兜底默认」**。本测试与后端
 * `FlowDesignMetaTest` **加载同一个文件** `test-fixtures/golden/flow-design-meta.json`，
 * 任一端漂移、或后端改了枚举而前端兜底没跟上，至少一侧立刻变红。
 *
 * <h2>为什么金样例里没有 label</h2>
 * 金样例只放「两端必须一致」的部分：深度上限、各域禁用集、各来源的参数槽位与代码集合。
 * 文案（`label`）是各端的展示用语（后端「借用单」vs 前端「借用单流程」），
 * 强行逐字对齐只会带来纯噪音的改动 —— 与既有 `flow-validation-golden.json`
 * 「断『有没有』而不逐字」的口径一致。
 */

interface GoldenScope {
  code: string
  forbiddenRuleTypes: string[]
}

interface GoldenRuleType {
  code: string
  params: string[]
}

interface GoldenDoc {
  conditionMaxDepth: number
  scopes: GoldenScope[]
  ruleTypes: GoldenRuleType[]
}

const GOLDEN_PATH = resolve(__dirname, '../../../test-fixtures/golden/flow-design-meta.json')

function golden(): GoldenDoc {
  return JSON.parse(readFileSync(GOLDEN_PATH, 'utf-8')) as GoldenDoc
}

/** 金样例里借用域的禁用集（多处断言要用，抽出来避免重复 find） */
function goldenBorrowForbidden(): string[] {
  return golden().scopes.find((scope) => scope.code === 'BORROW')?.forbiddenRuleTypes ?? []
}

/** 每个用例后把模块级覆盖层复位，避免用例间相互污染（覆盖层是模块级状态） */
afterEach(() => {
  applyFlowDesignMeta(null)
})

describe('兜底默认必须等于后端真值（接口不可用时的降级路径）', () => {
  it('深度上限的兜底默认 == 金样例', () => {
    expect(flowConditionMaxDepth()).toBe(golden().conditionMaxDepth)
  })

  it('各域禁用集的兜底默认 == 金样例（逐域逐项）', () => {
    for (const scope of golden().scopes) {
      expect(
        flowScopeForbiddenRuleTypes(scope.code as FlowScopeCode),
        `域 ${scope.code} 的兜底禁用集与后端真值不一致`
      ).toEqual(scope.forbiddenRuleTypes)
    }
  })

  it('本地业务域列表覆盖金样例全部域（不多不少）', () => {
    expect(FLOW_SCOPE_OPTIONS.map((item) => item.value).sort()).toEqual(
      golden().scopes.map((item) => item.code).sort()
    )
  })
})

describe('本地来源表的兜底默认必须等于后端真值', () => {
  it('来源代码集合与金样例一致（不多不少）', () => {
    expect(APPROVER_RULE_TYPE_OPTIONS.map((item) => item.value).sort()).toEqual(
      golden().ruleTypes.map((item) => item.code).sort()
    )
  })

  it('每种来源的参数槽位与金样例一致', () => {
    for (const item of golden().ruleTypes) {
      expect(
        approverRuleParams(item.code as ApproverRuleTypeCode),
        `来源 ${item.code} 的参数槽位与后端枚举不一致`
      ).toEqual(item.params)
    }
  })
})

describe('applyFlowDesignMeta：接口生效时覆盖、形状不合法时整体回退', () => {
  it('接口值生效后，两个读取入口都返回接口值', () => {
    applyFlowDesignMeta({
      conditionMaxDepth: 5,
      scopes: [{ code: 'BORROW', label: '借用单', forbiddenRuleTypes: ['LEADER'] }],
      ruleTypes: []
    } as FlowDesignMeta)

    expect(flowConditionMaxDepth()).toBe(5)
    expect(flowScopeForbiddenRuleTypes('BORROW')).toEqual(['LEADER'])
  })

  it('接口漏掉某个域时，该域回退到兜底默认，而不是静默变成「无禁用」', () => {
    // 接口只字未提 BORROW：若实现把「响应里没有」当成「该域无禁用」，
    // 一次接口字段缺失就会让借用域的禁用校验整体失效 —— 这是最该防的降级方向。
    applyFlowDesignMeta({ conditionMaxDepth: 3, scopes: [], ruleTypes: [] } as FlowDesignMeta)

    expect(flowScopeForbiddenRuleTypes('BORROW')).toEqual(goldenBorrowForbidden())
  })

  it('传 null 即回退到兜底默认（接口失败的降级路径）', () => {
    applyFlowDesignMeta({ conditionMaxDepth: 9, scopes: [], ruleTypes: [] } as FlowDesignMeta)
    expect(flowConditionMaxDepth()).toBe(9)

    applyFlowDesignMeta(null)

    expect(flowConditionMaxDepth()).toBe(golden().conditionMaxDepth)
    expect(flowScopeForbiddenRuleTypes('BORROW')).toEqual(goldenBorrowForbidden())
  })

  const badMetas: Array<[string, unknown]> = [
    ['深度为零', { conditionMaxDepth: 0, scopes: [], ruleTypes: [] }],
    ['深度为负数', { conditionMaxDepth: -1, scopes: [], ruleTypes: [] }],
    ['深度不是整数', { conditionMaxDepth: 2.5, scopes: [], ruleTypes: [] }],
    ['深度是字符串', { conditionMaxDepth: '3', scopes: [], ruleTypes: [] }],
    ['缺少深度字段', { scopes: [], ruleTypes: [] }]
  ]

  it.each(badMetas)('形状不合法（%s）→ 整体丢弃，不做部分采纳', (_caseName, badMeta) => {
    applyFlowDesignMeta(badMeta as FlowDesignMeta)

    expect(flowConditionMaxDepth()).toBe(golden().conditionMaxDepth)
    expect(flowScopeForbiddenRuleTypes('BORROW')).toEqual(goldenBorrowForbidden())
  })

  it('scopes 不是数组时不会抛错，只是没有覆盖值', () => {
    applyFlowDesignMeta({
      conditionMaxDepth: 4,
      scopes: null as unknown as FlowDesignMeta['scopes'],
      ruleTypes: []
    })

    expect(flowConditionMaxDepth()).toBe(4)
    expect(flowScopeForbiddenRuleTypes('BORROW')).toEqual(goldenBorrowForbidden())
  })
})
