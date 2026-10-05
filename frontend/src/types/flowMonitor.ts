/**
 * 流程监控类型与展示口径（ · M7，后端 module/flowmonitor）
 *
 * 设计与后端 `FlowMonitorFlowVO` / `FlowMonitorNodeVO` 一一对应。这里的函数都是**纯函数**，
 * 因此可以被单测直接覆盖 —— 与项目的既定做法一致（见 types/report.ts、types/order.ts）：
 * 展示口径放进纯函数，页面只负责摆位置，这样「同一份数据在两处显示不一致」这类问题
 * 在类型层就被堵住。
 */

/** 「未归属」桶的伪 flowId，与后端 `FlowMonitorService.UNATTRIBUTED_FLOW_ID` 必须一致 */
export const UNATTRIBUTED_FLOW_ID = 0

/** 空值占位符：统一用「—」，避免界面里混用 '-' / 'N/A' / 空白 */
export const EMPTY_PLACEHOLDER = '—'

/** 模板维度汇总行 */
export interface FlowMonitorFlow {
  flowId: number
  flowName: string
  /** 是否为「未归属」桶（flowId === 0） */
  unattributed: boolean
  /** 走该模板的工单量 */
  orderCount: number
  /** 已完成审批的工单数（= 平均时长的样本数） */
  approvedOrderCount: number
  /** 平均审批时长（小时）；无样本时为 null */
  avgApprovalHours: number | null
  /** 瓶颈（按平均耗时）；无可判定节点时为 null */
  bottleneckByDuration: FlowMonitorNode | null
  /** 瓶颈（按超时率）；所有节点都没设时限时为 null */
  bottleneckByOverdue: FlowMonitorNode | null
  /** 版本标签，形如 "v1, v2"；未归属桶为 null */
  versionLabels: string | null
}

/** 节点维度明细行 */
export interface FlowMonitorNode {
  flowId: number
  nodeKey: string
  nodeName: string
  /** APPROVAL / CC；历史行（传统分组审批路径）为 null */
  nodeType: string | null
  sampleCount: number
  avgHours: number | null
  overdueCount: number
  /** 超时率分母：有时限的已决策轮次数 */
  withDeadlineCount: number
  /** 超时率（%）；分母为 0 时为 null，表示「无法判定」而不是 0% */
  overdueRate: number | null
  /** 运行期才被激活的轮次数（M2 联动） */
  runtimeActivatedCount: number
}

/** 瓶颈口径 */
export type BottleneckMode = 'duration' | 'overdue'

/**
 * 瓶颈口径可选项。
 *
 * 两个口径经常指向**不同**的节点，所以不能替用户默认一个就完事：
 * 一个节点可能单次都很快但偶尔严重超时（超时率高、平均不高），
 * 另一个可能每次都要等半天但从不越线（平均高、超时率为 0）。
 */
export const BOTTLENECK_MODE_OPTIONS: ReadonlyArray<{ value: BottleneckMode; label: string }> = [
  { value: 'duration', label: '按平均耗时' },
  { value: 'overdue', label: '按超时率' }
]

/** 取指定口径下的瓶颈节点；无则返回 null */
export function bottleneckOf(flow: FlowMonitorFlow, mode: BottleneckMode): FlowMonitorNode | null {
  return mode === 'overdue' ? flow.bottleneckByOverdue : flow.bottleneckByDuration
}

/**
 * 瓶颈节点的展示文案：`节点名（指标）`。
 *
 * 没有可判定节点时返回一句**说明原因**的话而不是「—」——
 * 「超时率算不出来」和「没有节点」在管理上是完全不同的两件事，
 * 前者往往意味着"没人给节点设时限"，那本身就是个待办事项。
 */
export function bottleneckText(flow: FlowMonitorFlow, mode: BottleneckMode): string {
  const node = bottleneckOf(flow, mode)
  if (!node) {
    if (mode === 'overdue') {
      return flow.orderCount > 0 ? '无节点配置时限，无法判定' : EMPTY_PLACEHOLDER
    }
    return flow.orderCount > 0 ? '暂无已完成的审批' : EMPTY_PLACEHOLDER
  }
  const name = nodeDisplayName(node)
  return mode === 'overdue'
    ? `${name}（${formatRate(node.overdueRate)}）`
    : `${name}（${formatMonitorHours(node.avgHours)}）`
}

/** 节点展示名：优先用定义快照里的名字，缺失时回落 key */
export function nodeDisplayName(node: FlowMonitorNode): string {
  return node.nodeName?.trim() ? node.nodeName : node.nodeKey || EMPTY_PLACEHOLDER
}

/** 节点类型中文标签；null 表示历史行（传统分组审批路径），按「审批」呈现 */
export function nodeTypeLabel(nodeType: string | null | undefined): string {
  switch (nodeType) {
    case 'CC':
      return '抄送'
    case 'APPROVAL':
    case null:
    case undefined:
      return '审批'
    default:
      return nodeType
  }
}

/**
 * 时长格式化（小时）。null / 非有限数一律显示占位符，绝不显示 NaN。
 *
 * 名字刻意与 `types/report.ts` 的 `formatHours` 区分开：那个函数的 null 是「审批中」
 * （工单还没有终点），而这里的 null 是「没有可用的测量样本」。两者含义相反 ——
 * 一个表示"还在跑"，一个表示"没跑过"。同名会让改其中一处的人以为两处口径一致。
 */
export function formatMonitorHours(hours: number | null | undefined): string {
  if (hours == null || !Number.isFinite(hours)) {
    return EMPTY_PLACEHOLDER
  }
  return `${hours} 小时`
}

/** 百分比格式化。null 保持占位符 —— 见 overdueRateHint 对「无法判定」的解释 */
export function formatRate(rate: number | null | undefined): string {
  if (rate == null || !Number.isFinite(rate)) {
    return EMPTY_PLACEHOLDER
  }
  return `${rate}%`
}

/**
 * 超时率单元格的说明文案（作为 tooltip / 次要说明）。
 *
 * 存在的意义是让「12 / 15」这个分母可见：只给一个百分比，看的人无法判断
 * 它是在 3 个样本上算的还是在 300 个样本上算的，而这两者的管理含义天差地别。
 */
export function overdueRateHint(node: FlowMonitorNode): string {
  if (node.overdueRate == null) {
    return '该节点在统计范围内没有配置时限，无法判定超时率'
  }
  return `${node.withDeadlineCount} 个有时限的审批轮次中，${node.overdueCount} 个超时`
}

/** 运行期激活提示：为 0 时不展示，避免每行都挂一句没信息量的话 */
export function runtimeActivatedHint(node: FlowMonitorNode): string | null {
  if (node.runtimeActivatedCount <= 0) {
    return null
  }
  return `${node.runtimeActivatedCount} 个轮次由运行期条件激活（耗时从激活时刻起算）`
}

/** 工单量的「样本」补充说明：让平均值可被解读 */
export function sampleHint(flow: FlowMonitorFlow): string {
  if (flow.orderCount === 0) {
    return '暂无工单'
  }
  return `${flow.orderCount} 单中 ${flow.approvedOrderCount} 单已完成审批`
}
