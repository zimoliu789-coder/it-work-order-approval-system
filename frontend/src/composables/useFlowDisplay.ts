import { computed, reactive, type ComputedRef } from 'vue'
import {
  PROCESS_FIELD_OPTIONS,
  approverRuleText,
  conditionText,
  isElseBranch,
  isProcessField,
  signTypeLabel,
  type ApproverRule,
  type FlowBranch,
  type FlowFieldOption,
  type FlowNode
} from '@/types/approvalFlow'

/**
 * 流程设计器的「展示层」辅助（ ·  · W4-A3）。
 *
 * <h2>边界：这里只做「取值 → 文案」，不做任何写操作</h2>
 * 所有函数都是纯查询（除了本地姓名缓存 `localUserNames`，它只被「选完人员回填」写一次），
 * 因此可以脱离编辑链路单独使用。画布摘要、下拉候选、属性面板回显全部走这里。
 *
 * <h2>为什么 `nodeIndex` 由外部传入</h2>
 * 它属于画布模块（{@link useFlowCanvas}）的派生数据。展示模块只是消费者，
 * 自己再算一份就会出现两个「节点索引」事实源，节点改名后两边可能不一致。
 */
export interface FlowDisplayOptions {
  fieldOptions: () => FlowFieldOption[]
  roleOptions: () => Array<{ roleCode: string; roleName: string }>
  handlerDepartmentOptions: () => Array<{ id: number; deptName: string }>
  userNameMap: () => Record<number, string>
  nodeIndex: ComputedRef<Map<string, FlowNode>>
}

export interface FlowDisplayApi {
  localUserNames: Record<number, string>
  userNameOf: (id: number) => string
  fieldIndex: ComputedRef<Map<string, FlowFieldOption>>
  formFieldOptions: ComputedRef<FlowFieldOption[]>
  processFieldCandidates: ComputedRef<FlowFieldOption[]>
  userFieldOptions: ComputedRef<FlowFieldOption[]>
  conditionValueOptions: (fieldKey: string) => Array<{ value: string; label: string }>
  fieldLabelOf: (key: string) => string
  nodeNameOf: (key?: string | null) => string
  nodeTypeTag: (node: FlowNode) => 'primary' | 'success' | 'warning' | 'info'
  roleNameOf: (roleCode?: string | null) => string
  departmentNameOf: (id?: number | null) => string
  ruleSummary: (rule: ApproverRule) => string
  timeLimitSummary: (node: FlowNode) => string
  nodeSummary: (node: FlowNode) => string
  branchSummary: (branch: FlowBranch) => string
}

export function useFlowDisplay(options: FlowDisplayOptions): FlowDisplayApi {
  // ----------------------------------------------------------------------
  // 本地用户姓名缓存（选择人员后回显用）
  // ----------------------------------------------------------------------

  const localUserNames = reactive<Record<number, string>>({})

  function userNameOf(id: number): string {
    return localUserNames[id] ?? options.userNameMap()[id] ?? `#${id}`
  }

  // ----------------------------------------------------------------------
  // 字段名辅助
  // ----------------------------------------------------------------------

  const fieldIndex = computed<Map<string, FlowFieldOption>>(
    () => new Map((options.fieldOptions() ?? []).map((field) => [field.value, field]))
  )

  /** 表单字段候选（后端已在 fieldOptions 里剔除了布局元素） */
  const formFieldOptions = computed(() => options.fieldOptions() ?? [])

  /**
   * 运行期字段候选（M2）：把 `process.*` 伪字段映射成与表单字段同构的候选项。
   *
   * <p>把两者放进同一个下拉（分两组展示）而不是另开一个入口，是因为对配置者来说
   * "用哪个字段做条件"是同一个决策 —— 分开反而要他在两个控件之间来回找。
   * 但分组是必要的：运行期字段在提交时**还没有值**，配了它下游节点会先落 `INACTIVE`，
   * 这是与普通表单条件完全不同的行为，必须一眼能分辨。
   */
  const processFieldCandidates = computed<FlowFieldOption[]>(() =>
    PROCESS_FIELD_OPTIONS.map((field) => ({
      value: field.value,
      label: field.label,
      kind: field.kind
    }))
  )

  /** 表单人员字段候选：仅 USER 类型 */
  const userFieldOptions = computed(() =>
    (options.fieldOptions() ?? []).filter((field) => field.kind === 'USER_REF')
  )

  /**
   * 枚举类字段的比较值候选。
   *
   * <p>目前只有运行期字段带内建枚举（`prevNodeResult` / `anyRejected`）。有了它，
   * 比较值就从"手填字符串"变成"选一个" —— 否则把 `APPROVED` 打成 `APPROVE`
   * 不会报错，只会在运行期静默永不命中，这类问题极难排查。
   */
  function conditionValueOptions(fieldKey: string): Array<{ value: string; label: string }> {
    if (!isProcessField(fieldKey)) {
      // 表单字段的取值域来自用户填写的数据，前端拿不到枚举，仍由用户手填
      return []
    }
    return PROCESS_FIELD_OPTIONS.find((item) => item.value === fieldKey)?.options ?? []
  }

  function fieldLabelOf(key: string): string {
    return fieldIndex.value.get(key)?.label ?? key
  }

  function nodeNameOf(key?: string | null): string {
    if (!key) {
      return ''
    }
    return options.nodeIndex.value.get(key)?.name ?? key
  }

  function nodeTypeTag(node: FlowNode): 'primary' | 'success' | 'warning' | 'info' {
    if (node.type === 'APPROVAL') {
      return 'primary'
    }
    if (node.type === 'CC') {
      // 抄送用 success 而不是 info：它不是"配角"，而是一个独立且常被忽略的配置项，
      // 用与「结束」相同的灰色会让它在画布上一眼看不出区别
      return 'success'
    }
    return node.type === 'CONDITION' ? 'warning' : 'info'
  }

  function roleNameOf(roleCode?: string | null): string {
    if (!roleCode) {
      return ''
    }
    return options.roleOptions().find((role) => role.roleCode === roleCode)?.roleName ?? roleCode
  }

  function departmentNameOf(id?: number | null): string {
    if (id == null) {
      return ''
    }
    // 参数槽位仍叫 handlerGroupId（流程定义 JSON 里的存量标识，改名会让旧流程「规则无法识别」），
    // 但它现在指向的是**部门 id** —— 全系统只有一个「最终处理部门」。
    return options.handlerDepartmentOptions().find((dept) => dept.id === id)?.deptName ?? `#${id}`
  }

  function ruleSummary(rule: ApproverRule): string {
    return approverRuleText(rule, roleNameOf(rule.roleCode), departmentNameOf(rule.handlerGroupId))
  }

  /** 审批时限的展示（空 = 不限时） */
  function timeLimitSummary(node: FlowNode): string {
    return node.timeLimitHours != null && node.timeLimitHours > 0
      ? `限时 ${node.timeLimitHours} 小时`
      : '不限时'
  }

  function nodeSummary(node: FlowNode): string {
    if (node.type === 'APPROVAL') {
      const rules = (node.approverRules ?? []).map((rule) => ruleSummary(rule)).join('、')
      return `${signTypeLabel(node.signType)} · ${rules || '未配置审批人'} · ${timeLimitSummary(node)}`
    }
    if (node.type === 'CC') {
      // 抄送没有或签/会签的概念，因此不展示签署方式 —— 展示它只会让人以为抄送也要审批
      const rules = (node.approverRules ?? []).map((rule) => ruleSummary(rule)).join('、')
      return `抄送：${rules || '未配置抄送对象'}`
    }
    if (node.type === 'CONDITION') {
      return `${(node.branches ?? []).length} 个分支`
    }
    return '流程终点'
  }

  function branchSummary(branch: FlowBranch): string {
    if (isElseBranch(branch)) {
      return '所有条件都不满足时走这里'
    }
    return conditionText(branch.condition, fieldLabelOf)
  }

  return {
    localUserNames,
    userNameOf,
    fieldIndex,
    formFieldOptions,
    processFieldCandidates,
    userFieldOptions,
    conditionValueOptions,
    fieldLabelOf,
    nodeNameOf,
    nodeTypeTag,
    roleNameOf,
    departmentNameOf,
    ruleSummary,
    timeLimitSummary,
    nodeSummary,
    branchSummary
  }
}
