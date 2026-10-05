import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  validateFlowForPublish,
  type FlowDefinition,
  type FlowFieldOption
} from '@/types/approvalFlow'

/**
 * M4a 前后端流程校验器「共享金样例」一致性测试（前端侧）。
 *
 * <h2>与后端测试的分工</h2>
 * 后端 `FlowGoldenSampleTest` 与本案**加载同一个文件**
 * `test-fixtures/golden/flow-validation-golden.json`，各自调用本侧校验器，对同一张表断言。
 * 任何一端改了规则而另一端没跟上，两端测试中的至少一个立刻变红 ——
 * 这就是"共享金样例"相对"各写一份 fixture"的价值。
 *
 * <h2>匹配语义</h2>
 * 期望项是「应当出现的关键子串」（contains），不是逐字文案。两端的文案本就允许存在
 * 无害差异（如后端 `1..720 小时` vs 前端 `1~720 小时`），强行逐字对齐只会带来
 * 纯噪音的改动；要钉死的是「同一个问题两端是否都报了」。
 *
 * <h2>与 knownDivergences 的关系</h2>
 * 金样例文件里显式列出「已识别、刻意保留」的两端差异。本测试**不**断言这些差异必须消失，
 * 但会断言它们被记录在案 —— 若哪天有人悄悄把差异修掉或扩大了，会回来复核这一节。
 */

// ---------------------------------------------------------------------
// 装载金样例
// ---------------------------------------------------------------------

interface GoldenSample {
  id: string
  desc: string
  expectExactCount?: boolean
  expectedProblems: string[]
  definition: unknown
}

interface GoldenDoc {
  formFields: Array<{ key: string; label: string; type: string }>
  samples: GoldenSample[]
  knownDivergences: {
    items: Array<{
      id: string
      backend?: string
      frontend?: string
      harmless?: boolean
      sampleDefinition?: unknown
    }>
  }
}

const GOLDEN_PATH = resolve(__dirname, '../../../test-fixtures/golden/flow-validation-golden.json')

function loadGolden(): GoldenDoc {
  const raw = readFileSync(GOLDEN_PATH, 'utf-8')
  return JSON.parse(raw) as GoldenDoc
}

/**
 * 后端字段类型名 → 前端 ValueKind 的映射。
 *
 * 金样例的 `formFields.type` 写的是**后端** {@code FormFieldType} 名（唯一事实源），
 * 前端 `FlowFieldOption.kind` 用的是 {@code ValueKind} 取值，两者不是同一套枚举：
 * 例如后端 `USER` 对应前端 `USER_REF`、后端 `MULTI_SELECT` 对应前端 `OPTION_MULTI`。
 * 这张表把金样例的字段翻译成本地预检能吃的形态。
 */
const KIND_BY_FIELD_TYPE: Record<string, string> = {
  TEXT: 'TEXT',
  TEXTAREA: 'TEXT',
  NUMBER: 'NUMBER',
  DATE: 'DATE',
  DATETIME: 'DATETIME',
  SELECT: 'OPTION',
  RADIO: 'OPTION',
  MULTI_SELECT: 'OPTION_MULTI',
  CHECKBOX: 'OPTION_MULTI',
  FILE: 'FILE',
  IMAGE: 'FILE',
  USER: 'USER_REF',
  DEVICE: 'DEVICE_REF',
  BIZ_GROUP: 'GROUP_REF',
  DESCRIPTION: 'NONE',
  DIVIDER: 'NONE'
}

function toFieldOptions(doc: GoldenDoc): FlowFieldOption[] {
  return doc.formFields.map((field) => ({
    value: field.key,
    label: field.label,
    kind: KIND_BY_FIELD_TYPE[field.type] ?? field.type
  }))
}

/** 每条金样例跑一遍：失败信息里带上用例 id，便于定位 */
function cases(): Array<[GoldenSample, FlowFieldOption[]]> {
  const doc = loadGolden()
  return doc.samples.map((sample) => [sample, toFieldOptions(doc)])
}

// ---------------------------------------------------------------------
// 测试
// ---------------------------------------------------------------------

describe('M4a 金样例：前端校验器对每条共享用例的结论与金样例一致', () => {
  const all = cases()

  it('金样例文件可读且用例充足', () => {
    expect(all.length).toBeGreaterThanOrEqual(25)
  })

  it.each(all)('用例 $0.id —— $0.desc', (sample, fields) => {
    const definition = (sample.definition ?? null) as FlowDefinition | null
    const problems = validateFlowForPublish(definition, fields)

    // ① 每条期望的关键片段都必须出现在某个问题里
    for (const keyword of sample.expectedProblems) {
      expect(
        problems.some((problem) => problem.includes(keyword)),
        `用例 [${sample.id}] 期望出现包含「${keyword}」的问题，实际：${JSON.stringify(problems)}`
      ).toBe(true)
    }

    // ② 通过 / 不通过的大方向必须一致
    expect(problems.length === 0, `用例 [${sample.id}] 通过性判断不一致，实际：${JSON.stringify(problems)}`).toBe(
      sample.expectedProblems.length === 0
    )

    // ③ 标记了精确条数的用例，额外对齐「全量收集」语义
    if (sample.expectExactCount === true) {
      expect(problems.length, `用例 [${sample.id}] 问题条数与金样例不符，实际：${JSON.stringify(problems)}`).toBe(
        sample.expectedProblems.length
      )
    }
  })
})

describe('M4a 金样例：合法流程在本地预检里必须零问题', () => {
  const doc = loadGolden()
  const fields = toFieldOptions(doc)
  const validSamples = doc.samples.filter((sample) => sample.expectedProblems.length === 0)

  it('至少存在两个合法基准用例', () => {
    expect(validSamples.length).toBeGreaterThanOrEqual(2)
  })

  it.each(validSamples)('$id 应当零问题', (sample) => {
    const problems = validateFlowForPublish((sample.definition ?? null) as FlowDefinition | null, fields)
    expect(problems).toEqual([])
  })
})

describe('M4a：两侧已识别的差异被显式记录（防止无声扩大或悄悄修掉）', () => {
  const doc = loadGolden()

  it('knownDivergences 非空且每项写明两端表现', () => {
    expect(doc.knownDivergences.items.length).toBeGreaterThan(0)
    for (const item of doc.knownDivergences.items) {
      expect(item.id, '差异项缺少 id').toBeTruthy()
      expect(item, `差异项 ${item.id} 缺少 backend 字段`).toHaveProperty('backend')
      expect(item, `差异项 ${item.id} 缺少 frontend 字段`).toHaveProperty('frontend')
      expect(item, `差异项 ${item.id} 未标注 harmless`).toHaveProperty('harmless')
    }
  })

  it('「条件字段不存在」在本地预检里确实不报 —— 差异被如实记录，且由后端端点兜底', () => {
    const doc2 = loadGolden()
    const fields = toFieldOptions(doc2)
    const divergence = doc2.knownDivergences.items.find((item) => item.id === 'condition-field-missing-check')
    expect(divergence, '差异清单缺少 condition-field-missing-check').toBeTruthy()
    const definition = divergence?.sampleDefinition as FlowDefinition | undefined
    expect(definition, '该差异项缺少可复现的 sampleDefinition').toBeTruthy()
    const problems = validateFlowForPublish(definition ?? null, fields)
    // 这是 knownDivergences 里的一条：前端本地不报，但发布时以后端 /validate 结果为准
    expect(problems.some((problem) => problem.includes('不存在'))).toBe(false)
  })
})
