import { describe, expect, it } from 'vitest'
import {
  borrowNodeTypeLabel,
  borrowNodeTypeTagType,
  flowVersionOptions,
  isFlowBindingOrphaned,
  shouldShowBorrowPath,
  type BorrowFlowPreview
} from '@/types/department'

/**
 * 借用单接入自定义流程的前端纯逻辑单测（原 `types/group.spec.ts`）。
 *
 *  把「业务分组」并入「部门」后，本文件的被测对象**没有变化** ——
 * 它们本就是与「组织」无关的展示层规则，只是随文件一起从 `types/group.ts`
 * 搬到了 `types/department.ts`。故用例全部保留，仅改 import 路径。
 *
 * 只覆盖**会被两处以上复用的规则**：
 * - `flowVersionOptions`：部门配置与申请类型页共用同一条筛选口径；
 * - `isFlowBindingOrphaned`：识别「绑定的版本已不在可选列表」这一异常态；
 * - `shouldShowBorrowPath` / `borrowNodeType*`：借用申请页的路径块渲染条件与节点标签。
 *
 * 按项目约定（vitest.config.ts）此处只测纯函数，不挂载组件 ——
 * 组件的模板正确性由 type-check 与实机遍历保证。
 */
describe('借用流程版本选项（M1：仅列出最新已发布版本）', () => {
  it('无已发布版本的流程被过滤掉（草稿不能被绑定）', () => {
    const options = flowVersionOptions([
      { flowName: '采购流程', latestPublishedVersionId: 11, latestPublishedVersionNo: 2 },
      { flowName: '草稿中流程', latestPublishedVersionId: null, latestPublishedVersionNo: null }
    ])
    expect(options).toHaveLength(1)
    expect(options[0]).toEqual({ value: 11, label: '采购流程 · v2' })
  })

  it('版本号缺失时用 - 占位（不显示 undefined）', () => {
    const options = flowVersionOptions([
      { flowName: '历史流程', latestPublishedVersionId: 5, latestPublishedVersionNo: null }
    ])
    expect(options[0].label).toBe('历史流程 · v-')
  })

  it('已停用流程仍列出并标注（可能已有部门绑定它的版本）', () => {
    const options = flowVersionOptions([
      { flowName: '旧流程', status: 'DISABLED', latestPublishedVersionId: 9, latestPublishedVersionNo: 1 }
    ])
    expect(options).toHaveLength(1)
    expect(options[0].label).toBe('旧流程 · v1（已停用）')
  })

  it('空列表安全', () => {
    expect(flowVersionOptions([])).toEqual([])
  })
})

describe('借用流程绑定异常态（M1）', () => {
  const options = [{ value: 11, label: '采购流程 · v2' }]

  it('绑定 id 在可选列表里 → 不是异常态', () => {
    expect(isFlowBindingOrphaned(11, options)).toBe(false)
  })

  it('绑定 id 不在可选列表里 → 异常态（流程被删 / 版本回滚）', () => {
    expect(isFlowBindingOrphaned(99, options)).toBe(true)
  })

  it('未绑定（null / undefined）永远不算异常态', () => {
    expect(isFlowBindingOrphaned(null, options)).toBe(false)
    expect(isFlowBindingOrphaned(undefined, options)).toBe(false)
  })

  it('可选列表为空但已绑定 → 异常态', () => {
    expect(isFlowBindingOrphaned(11, [])).toBe(true)
  })
})

describe('借用路径块展示条件（M1）', () => {
  const node = {
    nodeKey: 'n1',
    nodeName: '主管审批',
    nodeType: 'APPROVAL',
    approverNames: ['刘洋'],
    conditionDesc: null,
    timeLimitHours: null
  }

  function preview(partial: Partial<BorrowFlowPreview>): BorrowFlowPreview {
    return { bound: true, flowVersionLabel: '采购流程 · v2', nodes: [], skippedNodes: [], ...partial }
  }

  it('未绑定流程 → 不展示（回退展示固定审批人列表）', () => {
    expect(shouldShowBorrowPath(preview({ bound: false, nodes: [node] }))).toBe(false)
  })

  it('绑定且有命中节点 → 展示', () => {
    expect(shouldShowBorrowPath(preview({ nodes: [node] }))).toBe(true)
  })

  it('绑定但全部节点被跳过 → 仍展示（让用户看到「本单不会经过哪些」）', () => {
    expect(shouldShowBorrowPath(preview({ nodes: [], skippedNodes: [node] }))).toBe(true)
  })

  it('绑定但两边都为空 → 不展示空壳', () => {
    expect(shouldShowBorrowPath(preview({}))).toBe(false)
  })

  it('preview 为 null / undefined 时安全', () => {
    expect(shouldShowBorrowPath(null)).toBe(false)
    expect(shouldShowBorrowPath(undefined)).toBe(false)
  })
})

describe('路径节点类型标签（M1）', () => {
  it('APPROVAL → 审批 / primary', () => {
    expect(borrowNodeTypeLabel('APPROVAL')).toBe('审批')
    expect(borrowNodeTypeTagType('APPROVAL')).toBe('primary')
  })

  it('CC → 抄送 / info', () => {
    expect(borrowNodeTypeLabel('CC')).toBe('抄送')
    expect(borrowNodeTypeTagType('CC')).toBe('info')
  })

  it('未知类型原样回显（不吞掉、不显示 undefined）', () => {
    expect(borrowNodeTypeLabel('UNKNOWN')).toBe('UNKNOWN')
  })
})
