import { describe, expect, it } from 'vitest'
import {
  BOTTLENECK_MODE_OPTIONS,
  EMPTY_PLACEHOLDER,
  UNATTRIBUTED_FLOW_ID,
  bottleneckOf,
  bottleneckText,
  formatMonitorHours,
  formatRate,
  nodeDisplayName,
  nodeTypeLabel,
  overdueRateHint,
  runtimeActivatedHint,
  sampleHint,
  type FlowMonitorFlow,
  type FlowMonitorNode
} from '@/types/flowMonitor'

/**
 * 流程监控展示口径单测（ · M7）
 *
 * 这里锁定的是那些「看起来只是格式化、实际会改变管理结论」的规则：
 *
 * - **null 不等于 0**。超时率算不出来（该节点没配时限）时显示「—」并给出原因，
 *   显示成 0% 会把"没人管时限"说成"流程很健康"；
 * - **瓶颈的两个口径各说各话**。切换口径后必须能在同一行上看出差别，
 *   不能因为取不到就悄悄回落到另一个口径的答案；
 * - **占位与说明要区分**。「没有工单」和「有工单但还没审批完」都会让平均值为空，
 *   但一个说明"这模板没人用"、一个说明"审批还没走完"，文案不能是同一句。
 */

function flow(partial: Partial<FlowMonitorFlow> = {}): FlowMonitorFlow {
  return {
    flowId: 1,
    flowName: '采购流程',
    unattributed: false,
    orderCount: 10,
    approvedOrderCount: 8,
    avgApprovalHours: 12.5,
    bottleneckByDuration: null,
    bottleneckByOverdue: null,
    versionLabels: 'v1',
    ...partial
  }
}

function node(partial: Partial<FlowMonitorNode> = {}): FlowMonitorNode {
  return {
    flowId: 1,
    nodeKey: 'n1',
    nodeName: '主管审批',
    nodeType: 'APPROVAL',
    sampleCount: 8,
    avgHours: 3,
    overdueCount: 2,
    withDeadlineCount: 8,
    overdueRate: 25,
    runtimeActivatedCount: 0,
    ...partial
  }
}

describe('流程监控 · 数值格式化', () => {
  it('时长：null / 非有限数一律显示占位符，不出现 NaN', () => {
    expect(formatMonitorHours(null)).toBe(EMPTY_PLACEHOLDER)
    expect(formatMonitorHours(undefined)).toBe(EMPTY_PLACEHOLDER)
    expect(formatMonitorHours(Number.NaN)).toBe(EMPTY_PLACEHOLDER)
    expect(formatMonitorHours(Number.POSITIVE_INFINITY)).toBe(EMPTY_PLACEHOLDER)
  })

  it('时长：0 是有效值，必须显示出来而不是被当成空', () => {
    expect(formatMonitorHours(0)).toBe('0 小时')
    expect(formatMonitorHours(12.5)).toBe('12.5 小时')
  })

  it('百分比：null 保持占位符 —— 它代表「无法判定」而不是 0%', () => {
    expect(formatRate(null)).toBe(EMPTY_PLACEHOLDER)
    expect(formatRate(0)).toBe('0%')
    expect(formatRate(25.5)).toBe('25.5%')
  })

  it('节点类型：CC 是抄送，APPROVAL 与历史 null 都按审批呈现', () => {
    expect(nodeTypeLabel('CC')).toBe('抄送')
    expect(nodeTypeLabel('APPROVAL')).toBe('审批')
    expect(nodeTypeLabel(null)).toBe('审批')
    expect(nodeTypeLabel(undefined)).toBe('审批')
  })

  it('节点名：定义快照里的名字优先，缺失时回落 key', () => {
    expect(nodeDisplayName(node({ nodeName: '财务复核' }))).toBe('财务复核')
    expect(nodeDisplayName(node({ nodeName: '   ' }))).toBe('n1')
    expect(nodeDisplayName(node({ nodeName: '', nodeKey: 'n2' }))).toBe('n2')
  })
})

describe('流程监控 · 瓶颈口径', () => {
  const durationNode = node({ nodeKey: 'n2', nodeName: '财务复核', avgHours: 11.5 })
  const overdueNode = node({ nodeKey: 'n3', nodeName: '归档确认', overdueRate: 60 })

  it('两个口径各取各的节点，不会互相回落到对方', () => {
    const row = flow({ bottleneckByDuration: durationNode, bottleneckByOverdue: overdueNode })
    expect(bottleneckOf(row, 'duration')?.nodeKey).toBe('n2')
    expect(bottleneckOf(row, 'overdue')?.nodeKey).toBe('n3')
  })

  it('口径选项按平均耗时在前、超时率在后，且两个都在', () => {
    expect(BOTTLENECK_MODE_OPTIONS.map((item) => item.value)).toEqual(['duration', 'overdue'])
  })

  it('按耗时的文案带上时长；按超时率的文案带上百分比', () => {
    const row = flow({ bottleneckByDuration: durationNode, bottleneckByOverdue: overdueNode })
    expect(bottleneckText(row, 'duration')).toBe('财务复核（11.5 小时）')
    expect(bottleneckText(row, 'overdue')).toBe('归档确认（60%）')
  })

  it('有工单但算不出超时率时，说明是「无节点配置时限」而不是显示占位符', () => {
    const row = flow({ bottleneckByOverdue: null, orderCount: 10 })
    expect(bottleneckText(row, 'overdue')).toBe('无节点配置时限，无法判定')
  })

  it('有工单但还没审批完时，说明「暂无已完成的审批」', () => {
    const row = flow({ bottleneckByDuration: null, orderCount: 10 })
    expect(bottleneckText(row, 'duration')).toBe('暂无已完成的审批')
  })

  it('模板上一张单都没有时退回占位符，不说"暂无已完成的审批"（那是另一回事）', () => {
    const row = flow({ bottleneckByDuration: null, orderCount: 0 })
    expect(bottleneckText(row, 'duration')).toBe(EMPTY_PLACEHOLDER)
  })
})

describe('流程监控 · 补充说明文案', () => {
  it('超时率说明里必须出现分母，否则百分比无法被质疑与解释', () => {
    expect(overdueRateHint(node({ overdueCount: 2, withDeadlineCount: 8 }))).toBe(
      '8 个有时限的审批轮次中，2 个超时'
    )
  })

  it('超时率为 null 时说明原因是「没有配置时限」', () => {
    expect(overdueRateHint(node({ overdueRate: null, withDeadlineCount: 0 }))).toContain('没有配置时限')
  })

  it('运行期激活数为 0 时不产生文案（避免每行挂一句没信息量的话）', () => {
    expect(runtimeActivatedHint(node({ runtimeActivatedCount: 0 }))).toBeNull()
    expect(runtimeActivatedHint(node({ runtimeActivatedCount: 3 }))).toContain('3 个轮次')
  })

  it('样本说明区分「没有工单」与「工单还没审批完」', () => {
    expect(sampleHint(flow({ orderCount: 0, approvedOrderCount: 0 }))).toBe('暂无工单')
    expect(sampleHint(flow({ orderCount: 10, approvedOrderCount: 8 }))).toBe('10 单中 8 单已完成审批')
  })

  it('未归属桶的 flowId 常量与后端约定一致（0）', () => {
    expect(UNATTRIBUTED_FLOW_ID).toBe(0)
  })
})
