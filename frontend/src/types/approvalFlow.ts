/**
 * 审批流程模板（ + ）
 *
 * 与后端 `module/approvalflow/dto` + `common/flow` 下的 VO / POJO 一一对应。
 * 全局 `default-property-inclusion: non_null`：后端 null 字段整条省略，
 * 因此可空字段一律声明为可选。
 *
 * ## 为什么「流程定义」在前端也用 JSON 结构而不是扁平表
 * 设计器里整棵流程是**一起读、一起写**的（没有「只改某一个节点」的操作），
 * 所以后端存的是整体 JSON，前端也照着同一结构建模 —— 两端同一份形状，
 * 中间不需要任何转换层，改字段时不会出现「后端改了前端忘了」这类裂缝。
 */

// ---------------------------------------------------------------------
// 枚举与选项
// ---------------------------------------------------------------------

export type ApprovalFlowStatusCode = 'DRAFT' | 'PUBLISHED' | 'DISABLED'

/**
 * 流程业务域（M1）。
 *
 * - `CUSTOM`：自定义表单流程 —— 条件字段来自表单模板，审批人来源全部可用；
 * - `BORROW`：借用单流程 —— 条件字段来自借用内置字段清单，且**禁用**
 *   「表单人员字段」「申请人自选」（借用提交页没有表单人员字段，也没有选人器）。
 *
 * 它只承载「字段域 + 禁用规则集」这两处最小差异，其余规则（结构/图/条件求值/
 * 时限/抄送/上一节点指定）全部共用 —— 刻意不写成两套校验器。
 */
export type FlowScopeCode = 'CUSTOM' | 'BORROW'

export const FLOW_SCOPE_OPTIONS: Array<{ value: FlowScopeCode; label: string }> = [
  { value: 'CUSTOM', label: '自定义表单流程' },
  { value: 'BORROW', label: '借用单流程' }
]

/**
 * 节点类型。 新增 `CC`（抄送）。
 *
 * 抄送与审批共用同一套审批人规则解析，但语义不同：抄送是「知会」——
 * **提交时即解析、即发消息**、落库为终态，不参与「当前步骤」判定、不阻塞推进。
 * 因此它需要审批规则与 `next`，但不需要 `signType`，也不允许配分支 / 时限。
 */
export type FlowNodeTypeCode = 'APPROVAL' | 'CC' | 'CONDITION' | 'END'

/** 审批人来源（ 新增 LEADER / PREV_ASSIGN； 新增 PARENT_DEPT_APPROVERS） */
export type ApproverRuleTypeCode =
  | 'SPECIFIC_USER'
  | 'ROLE'
  | 'BIZ_GROUP_APPROVERS'
  | 'PARENT_DEPT_APPROVERS'
  | 'HANDLER_GROUP'
  | 'FORM_USER_FIELD'
  | 'LEADER'
  | 'PREV_ASSIGN'
  | 'APPLICANT_CHOOSE'

/** 「申请人自选」的可选范围 */
export type ChooseScopeCode = 'ROLE' | 'GROUP' | 'ALL'

/**
 * 「上一节点指定审批人」的可选范围（ 新增，对应规则参数 `assignScope`）。
 *
 * 不与 {@link ChooseScopeCode} 合并：`ChooseScopeCode` 表达的是「申请人在哪个人群里挑」，
 * 它的 GROUP 需要配一个具体部门 id；而这里表达的是「系统允许把工单派给谁」，
 * 其中 `IT_EXECUTOR` 是**两个来源的并集**（IT执行人角色 ∪ IT运维组成员），
 * 用 `ChooseScopeCode` 的任一取值都表达不出来。
 *
 * **缺省（未配）即 `ALL`**：存量流程定义没有这个参数，必须保持「不限制」的旧行为，
 * 否则已发布的流程会在无人改动的情况下开始拒绝指派。
 */
export type AssignScopeCode = 'ALL' | 'IT_EXECUTOR'

/** 条件运算符（与后端 FlowOperator 一一对应） */
export type FlowOperatorCode =
  | 'EQ'
  | 'NE'
  | 'GT'
  | 'GTE'
  | 'LT'
  | 'LTE'
  | 'CONTAINS'
  | 'NOT_CONTAINS'
  | 'IS_EMPTY'
  | 'NOT_EMPTY'

export type SignTypeCode = 'ANY_SIGN' | 'ALL_SIGN'

/**
 * 驳回处理动作（M2）。默认 `TERMINATE` —— 与第二期完全一致，存量定义不配就是它。
 *
 * 默认取「终止」而不是「改道」是刻意的：配错了改道比配错了终止危险得多
 * （工单会继续走，没人发现异常）。
 */
export type RejectActionCode = 'TERMINATE' | 'GOTO'

/**
 * 超时处理动作（M2）。默认 `NOTIFY` = 只提醒，与第二期行为一致。
 *
 * 注意提醒与动作**不互斥**：加签之后原审批人仍会收到提醒（加签是"提醒无效"之后的升级）。
 */
export type TimeoutActionCode = 'NOTIFY' | 'ADD_SIGN' | 'GOTO'

/** 「上一节点结果 / 已耗时」等运行期伪字段的前缀（与后端 ProcessFieldCatalog.PREFIX 一致） */
export const PROCESS_FIELD_PREFIX = 'process.'

export const REJECT_ACTION_OPTIONS: Array<{ value: RejectActionCode; label: string; desc: string }> = [
  { value: 'TERMINATE', label: '终止整单（默认）', desc: '与第二期一致：驳回即整单结束、释放设备' },
  {
    value: 'GOTO',
    label: '改道到指定节点',
    desc: '工单保持「审批中」且不释放设备，转而激活目标节点（需开启运行时条件总开关）'
  }
]

export const TIMEOUT_ACTION_OPTIONS: Array<{ value: TimeoutActionCode; label: string; desc: string }> = [
  { value: 'NOTIFY', label: '仅提醒（默认）', desc: '与第二期一致：只向审批人与申请人发提醒' },
  {
    value: 'ADD_SIGN',
    label: '加签',
    desc: '在原节点之后动态插入一个审批节点；不填加签人时继承原节点规则（超管兜底除外）'
  },
  {
    value: 'GOTO',
    label: '改道到指定节点',
    desc: '放弃当前超时节点，转而激活目标节点（需开启运行时条件总开关）'
  }
]

export const SIGN_TYPE_OPTIONS: Array<{ value: SignTypeCode; label: string; desc: string }> = [
  { value: 'ANY_SIGN', label: '或签', desc: '该节点任意一人通过即进入下一步' },
  { value: 'ALL_SIGN', label: '会签', desc: '该节点所有人全部通过才进入下一步' }
]

export const FLOW_NODE_TYPE_OPTIONS: Array<{ value: FlowNodeTypeCode; label: string }> = [
  { value: 'APPROVAL', label: '审批节点' },
  { value: 'CC', label: '抄送节点' },
  { value: 'CONDITION', label: '条件分支' },
  { value: 'END', label: '结束' }
]

/** 规则参数槽位：决定设计器属性面板显示哪组输入控件 */
export type ApproverRuleParam =
  | 'userIds'
  | 'roleCode'
  | 'handlerGroupId'
  | 'fieldKey'
  | 'choose'
  | 'assignCount'
  | 'assignScope'

export const APPROVER_RULE_TYPE_OPTIONS: Array<{
  value: ApproverRuleTypeCode
  label: string
  /** 该来源需要配置哪些参数（决定设计器里显示哪组输入控件） */
  params: ApproverRuleParam[]
  /** 一句话说明（属性面板展示，减少「这条规则到底会解析出谁」的猜测） */
  desc?: string
}> = [
  { value: 'SPECIFIC_USER', label: '指定人员', params: ['userIds'] },
  { value: 'ROLE', label: '指定角色', params: ['roleCode'] },
  {
    value: 'BIZ_GROUP_APPROVERS',
    label: '部门主管',
    params: [],
    // 枚举名保留 BIZ_GROUP_APPROVERS（写进了已发布流程定义的 JSON，改名等于让存量流程
    // 「规则无法识别」），但语义已从「业务分组配置的审批人」换成「申请人所在部门的部门主管」。
    desc: '运行时取申请人所在部门的部门主管；未设主管时由超级管理员兜底并通知维护人'
  },
  {
    value: 'PARENT_DEPT_APPROVERS',
    label: '上级部门主管',
    params: [],
    // 与 BIZ_GROUP_APPROVERS（本部门主管）只差一个层级，却独立成一种来源而不是加参数：
    // 两者语义主体不同（「我部门的负责人」vs「管我部门的那一层负责人」），
    // 混成带 offset 参数的类型会让设计器上出现「部门主管（层级 -1）」这种要懂树才能填对的控件。
    desc: '运行时取申请人所在部门的**上级**部门的部门主管；已在根部门时由超级管理员兜底'
  },
  {
    value: 'HANDLER_GROUP',
    label: '最终处理部门成员',
    params: ['handlerGroupId'],
    desc: '参数槽位名沿用存量标识，实际存的是部门 id；全系统只有一个最终处理部门（IT运维组）'
  },
  { value: 'FORM_USER_FIELD', label: '表单人员字段', params: ['fieldKey'] },
  {
    value: 'LEADER',
    label: '申请人直属领导',
    params: [],
    desc: '未配置 / 已离职 / 停用 / 就是本人时，由超级管理员兜底并通知申请人、超管与管理员'
  },
  {
    value: 'PREV_ASSIGN',
    label: '上一节点指定审批人',
    params: ['assignCount', 'assignScope'],
    desc: '提交时人未知；上一节点通过时必须指定同样人数（只支持或签，且必须是本节点唯一规则）。'
      + '「指定范围」限定可选人群，并会被服务端强制校验 —— 否则「从 IT执行人里选」只是一句界面文案'
  },
  { value: 'APPLICANT_CHOOSE', label: '申请人自选', params: ['choose'] }
]

/**
 * 「上一节点指定审批人」的可选范围选项。
 *
 * 顺序与后端 `ApproverRuleType.AssignScope` 声明顺序一致；`ALL` 排在首位是因为
 * 它是缺省口径（未配参数时即不限制）。
 */
export const ASSIGN_SCOPE_OPTIONS: Array<{ value: AssignScopeCode; label: string }> = [
  { value: 'ALL', label: '不限制（全部在职员工）' },
  { value: 'IT_EXECUTOR', label: 'IT执行人（IT执行人角色 或 IT运维组成员）' }
]

export const CHOOSE_SCOPE_OPTIONS: Array<{ value: ChooseScopeCode; label: string }> = [
  { value: 'ALL', label: '全部员工' },
  { value: 'ROLE', label: '指定角色' },
  { value: 'GROUP', label: '指定部门' }
]

/** 条件运算符（`requiresValue=false` 的「为空/不为空」不需要填比较值） */
export const FLOW_OPERATOR_OPTIONS: Array<{
  value: FlowOperatorCode
  label: string
  requiresValue: boolean
  numeric: boolean
}> = [
  { value: 'EQ', label: '等于', requiresValue: true, numeric: false },
  { value: 'NE', label: '不等于', requiresValue: true, numeric: false },
  { value: 'GT', label: '大于', requiresValue: true, numeric: true },
  { value: 'GTE', label: '大于等于', requiresValue: true, numeric: true },
  { value: 'LT', label: '小于', requiresValue: true, numeric: true },
  { value: 'LTE', label: '小于等于', requiresValue: true, numeric: true },
  { value: 'CONTAINS', label: '包含', requiresValue: true, numeric: false },
  { value: 'NOT_CONTAINS', label: '不包含', requiresValue: true, numeric: false },
  { value: 'IS_EMPTY', label: '为空', requiresValue: false, numeric: false },
  { value: 'NOT_EMPTY', label: '不为空', requiresValue: false, numeric: false }
]

export function flowOperatorLabel(op: FlowOperatorCode): string {
  return FLOW_OPERATOR_OPTIONS.find((item) => item.value === op)?.label ?? op
}

export function flowOperatorRequiresValue(op: FlowOperatorCode): boolean {
  return FLOW_OPERATOR_OPTIONS.find((item) => item.value === op)?.requiresValue ?? true
}

export function approverRuleLabel(type: ApproverRuleTypeCode): string {
  return APPROVER_RULE_TYPE_OPTIONS.find((item) => item.value === type)?.label ?? type
}

/** 该规则类型需要配置哪些参数（供设计器属性面板决定显示哪些控件） */
export function approverRuleParams(type: ApproverRuleTypeCode): ApproverRuleParam[] {
  return APPROVER_RULE_TYPE_OPTIONS.find((item) => item.value === type)?.params ?? []
}

/**
 * 「上一节点指定审批人」范围的中文后缀。
 *
 * 缺省（null / 空串 / 未配）返回空串 —— 不限制是存量口径，摘要里多说一句
 * 反而会让老流程看起来"被改了"。范围名从 {@link ASSIGN_SCOPE_OPTIONS} 取，
 * 保证设计器下拉与节点摘要说法一致。
 */
export function assignScopeSuffix(scope: AssignScopeCode | null | undefined): string {
  if (!scope) {
    return ''
  }
  const label = ASSIGN_SCOPE_OPTIONS.find((item) => item.value === scope)?.label
  return label ? `（范围：${label}）` : ''
}

export function signTypeLabel(signType?: SignTypeCode | null): string {
  return signType === 'ALL_SIGN' ? '会签' : '或签'
}

// ---------------------------------------------------------------------
// 流程定义（与后端 common/flow 的 POJO 同构）
// ---------------------------------------------------------------------

/** 一条审批人规则；只有对应来源所需的字段有值 */
export interface ApproverRule {
  type: ApproverRuleTypeCode
  /** 指定人员 */
  userIds?: number[] | null
  /** 指定角色 */
  roleCode?: string | null
  /** 最终处理部门成员 */
  handlerGroupId?: number | null
  /** 表单人员字段 */
  fieldKey?: string | null
  /** 申请人自选：范围与人数 */
  scope?: ChooseScopeCode | null
  scopeValue?: string | null
  minCount?: number | null
  maxCount?: number | null
  /**
   * 上一节点指定审批人：需要指定的人数；缺省视为 1。
   *
   * 复用了「人数」这个直觉：配成 2 就表示上一节点通过时必须点名 2 个人，
   * 多一个少一个都会被服务端 400 拒绝（不可静默忽略）。
   */
  assignCount?: number | null
  /**
   * 上一节点指定审批人：指定者的**可选范围**；缺省视为 `ALL`（不限制）。
   *
   * 配了范围之后服务端在回填时会校验被指定人确实落在范围内 —— 否则
   * 「从 IT执行人里选」只是一句前端提示，任意 user_id 都指得进来。
   */
  assignScope?: AssignScopeCode | null
}

/** 条件节点种类（M3-A）：与后端 `ConditionRule.KIND_RULE` / `KIND_GROUP` 逐字一致 */
export type ConditionKindCode = 'RULE' | 'GROUP'

/** 缺省种类 —— 存量 JSON 里没有 `kind`，一律按单条规则解析 */
export const CONDITION_KIND_RULE: ConditionKindCode = 'RULE'
export const CONDITION_KIND_GROUP: ConditionKindCode = 'GROUP'

/**
 * 条件树最大嵌套层数的**兜底默认**（**最外层条件组算第 1 层**）。
 *
 * 它不再是事实源，而是**接口不可用时的降级值**：真值由后端 `FlowCondition.MAX_DEPTH`
 * 定义，经 `GET /api/approval-flows/design-meta` 下发并被 `applyFlowDesignMeta` 覆盖，
 * 读取统一走 `flowConditionMaxDepth`（不要再引用本常量）。
 * 保留本体的理由是设计器**必须永远可用**：接口可能因网络 / 权限失败，
 * 此时它就是「照常可用」的基准值；其与后端真值的一致性由共享金样例
 * `test-fixtures/golden/flow-design-meta.json` 钉死（两端测试各读同一个文件）。
 * 两边单边改动会造出「设计器允许加、后端拒绝发布」这种
 * 最令人困惑的组合 —— 金样例里有专门的用例把深度 4 钉死为"两端都报错"。
 */
export const FLOW_CONDITION_MAX_DEPTH_FALLBACK = 3

/** 接口下发的深度上限；`null` = 未生效（用兜底默认） */
let conditionMaxDepthOverride: number | null = null

/** 接口下发的各域禁用来源；`null` = 未生效（用兜底默认） */
let scopeForbiddenOverride: Record<string, ApproverRuleTypeCode[]> | null = null

/**
 * 各业务域禁用来源的**兜底默认**（接口不可用时使用）。
 *
 * 它是一份副本，但正确性被共享金样例钉住：前端 Vitest 与后端单测读同一个
 * `test-fixtures/golden/flow-design-meta.json`。**不要**以它为事实源去改校验逻辑 ——
 * 校验与发布的真值永远在后端。
 */
const FLOW_SCOPE_FORBIDDEN_RULE_TYPES_FALLBACK: Record<FlowScopeCode, ApproverRuleTypeCode[]> = {
  CUSTOM: [],
  BORROW: ['FORM_USER_FIELD', 'APPLICANT_CHOOSE']
}

/** 条件树最大嵌套层数的**唯一读取入口**：接口生效时用接口值，否则用兜底默认 */
export function flowConditionMaxDepth(): number {
  return conditionMaxDepthOverride ?? FLOW_CONDITION_MAX_DEPTH_FALLBACK
}

/**
 * 各业务域禁用的审批人来源（**唯一读取入口**）。
 *
 * 域无禁用集时返回空数组 —— 与后端 `forbiddenRuleTypes: []` 的语义一致。
 */
export function flowScopeForbiddenRuleTypes(scope: FlowScopeCode): ApproverRuleTypeCode[] {
  return scopeForbiddenOverride?.[scope] ?? FLOW_SCOPE_FORBIDDEN_RULE_TYPES_FALLBACK[scope] ?? []
}

/**
 * 设计器元数据（ · W4-D / C8）——与后端 `FlowDesignMetaVO` 一一对应。
 *
 * 三个字段都是**会在后端产生硬拒绝**的约束（深度超限 / 该域禁用该来源 / 参数槽位不匹配），
 * 任一不对都会让「设计器里看着没问题、点发布被拒」。因此由后端下发而非前端自行维护。
 */
export interface FlowDesignMeta {
  /** 条件树最大嵌套层数（最外层算第 1 层） */
  conditionMaxDepth: number
  /** 各业务域及其禁用来源 */
  scopes: FlowDesignScopeMeta[]
  /** 各审批人来源及其参数槽位 */
  ruleTypes: FlowDesignRuleTypeMeta[]
}

export interface FlowDesignScopeMeta {
  code: FlowScopeCode
  label: string
  /** 本域下不可用的来源（权威值；本地兜底见 FLOW_SCOPE_FORBIDDEN_RULE_TYPES_FALLBACK） */
  forbiddenRuleTypes: ApproverRuleTypeCode[]
}

export interface FlowDesignRuleTypeMeta {
  code: ApproverRuleTypeCode
  label: string
  /** 参数槽位名（槽位名即 `ApproverRuleParam` 的取值） */
  params: string[]
}

/**
 * 应用接口下发的设计器元数据；传 `null`（或形状不合法）即**回退到兜底默认**。
 *
 * 为什么把「回退」也做进来：接口可能因网络 / 权限失败而返回垃圾，调用方若忘了区分，
 * 就会把一个坏值（如 0 层深度）写进状态 —— 那比「用回退值」糟得多。
 * 因此这里把入参当**不可信外部输入**校验：形状不对就整体丢弃，不做部分采纳。
 */
export function applyFlowDesignMeta(meta: FlowDesignMeta | null | undefined): void {
  if (!meta || !Number.isInteger(meta.conditionMaxDepth) || meta.conditionMaxDepth <= 0) {
    conditionMaxDepthOverride = null
    scopeForbiddenOverride = null
    return
  }
  conditionMaxDepthOverride = meta.conditionMaxDepth

  const map: Record<string, ApproverRuleTypeCode[]> = {}
  if (Array.isArray(meta.scopes)) {
    for (const scope of meta.scopes) {
      if (scope && typeof scope.code === 'string' && Array.isArray(scope.forbiddenRuleTypes)) {
        map[scope.code] = scope.forbiddenRuleTypes
      }
    }
  }
  scopeForbiddenOverride = map
}

/**
 * 条件树的单一节点信封：既可以是「单条规则」也可以是「嵌套条件组」（M3-A）。
 *
 * 与后端 `ConditionRule` 同构：`kind` 决定另外几个字段是否有意义。
 * `kind=GROUP` 时 `field` / `op` / `value` **必须为空** —— 后端会报
 * 「是嵌套条件组，不应携带字段 / 运算符 / 比较值」，所以设计器切换类型时要真的删掉旧值，
 * 而不是留着"以后可能用得上"：`nested-group-with-stale-rule-fields` 那条金样例钉的就是它。
 */
export interface FlowConditionRule {
  /** 节点种类；缺省 = RULE（存量 JSON 没有这个字段，字段初始值即 RULE） */
  kind?: ConditionKindCode | null
  /** 表单字段 key（或 `process.*` 运行期伪字段）；仅 kind=RULE 有意义 */
  field?: string | null
  /** 运算符；仅 kind=RULE 有意义 */
  op?: FlowOperatorCode | null
  /** 比较值；仅 kind=RULE 有意义 */
  value?: unknown
  /** 子条件；仅 kind=GROUP 有意义 */
  condition?: FlowCondition | null
}

/** 归一后的种类原值（未知取值**原样返回**，留给校验器报错，求值/渲染不抛） */
export function conditionKindOf(rule?: FlowConditionRule | null): string {
  return String(rule?.kind ?? '').trim().toUpperCase()
}

/**
 * 是否嵌套条件组。
 *
 * 归一规则与后端 `ConditionRule.isGroup()` 逐字同构：null / 空白 / 未知取值一律判
 * **不是组**（退化成最保守的旧语义），大小写不敏感，两侧空白裁掉。
 * 「不认识的 kind 当规则用」这一点两端刻意一致 —— 真正写得离谱的 kind 由校验器报出来，
 * 求值器不抛异常。
 */
export function isConditionGroup(rule?: FlowConditionRule | null): boolean {
  return conditionKindOf(rule) === CONDITION_KIND_GROUP
}

/** 是否单条规则；与 `isConditionGroup` 恒互补，不存在两者都 false 的第三态 */
export function isConditionRule(rule?: FlowConditionRule | null): boolean {
  return !isConditionGroup(rule)
}

/** 构造嵌套条件组信封（供设计器使用）。刻意**不写** field/op/value，避免造出脏值。 */
export function conditionGroup(condition: FlowCondition): FlowConditionRule {
  return { kind: CONDITION_KIND_GROUP, condition }
}

/**
 * 条件树深度（null = 0；只有规则的组 = 1）—— 与后端 `FlowPathResolver.depthOf` 同构。
 *
 * 混排时取**最深的那条链**：一层浅链与一层深链并存时，"还能不能再加组"取决于深的那条。
 */
export function conditionDepth(condition?: FlowCondition | null): number {
  if (!condition) {
    return 0
  }
  let deepest = 0
  for (const rule of condition.rules ?? []) {
    if (rule && isConditionGroup(rule)) {
      deepest = Math.max(deepest, conditionDepth(rule.condition))
    }
  }
  return deepest + 1
}

export interface FlowCondition {
  logic: 'AND' | 'OR'
  rules: FlowConditionRule[]
}

export interface FlowBranch {
  key: string
  name: string
  /** 是否默认出口（全流程每个条件节点必须且只能有一个） */
  else?: boolean | null
  condition?: FlowCondition | null
  next?: string | null
}

/**
 * 驳回处理（M2，仅 APPROVAL 节点有意义）。
 *
 * <p>`action` 缺省 = `TERMINATE`；`target` 仅在 `GOTO` 时有意义。
 * 后端对"不认识的 action"会报错而不是当作默认值 —— 把 `action` 拼错若被静默吞掉，
 * 配置者会以为改道生效了，而实际每笔驳回都在终止整单。
 */
export interface RejectAction {
  action?: RejectActionCode | null
  /** 改道目标节点 key（GOTO 必填，且须是审批 / 抄送节点） */
  target?: string | null
}

/**
 * 超时处理（M2，仅 APPROVAL 节点有意义）。
 *
 * <p>`afterHours` 与节点 `timeLimitHours` 的分工：后者决定 `deadline_at` 快照与"何时开始提醒"，
 * 本值决定"超多久才升级动作"。为 null 时回落 `timeLimitHours`，再没有则用全局阈值。
 */
export interface TimeoutAction {
  action?: TimeoutActionCode | null
  /** 超时阈值（小时，必须为正） */
  afterHours?: number | null
  /** 改道目标节点 key（GOTO 必填） */
  target?: string | null
  /**
   * 加签人；**为空时继承原节点的审批人规则**（不是"没人加签"）。
   * 填了则完全替换原规则（而不是并集）—— 显式覆盖必须是确定语义。
   */
  approvers?: ApproverRule[] | null
}

export interface FlowNode {
  key: string
  type: FlowNodeTypeCode
  name: string
  /** APPROVAL：签署方式 */
  signType?: SignTypeCode | null
  /** APPROVAL / CC：审批人来源（多条取并集） */
  approverRules?: ApproverRule[] | null
  /** APPROVAL / CC：下一节点 */
  next?: string | null
  /**
   * APPROVAL：审批时限（小时，）；空 = 不限时。
   *
   * 提交时算成 `deadline_at` 快照到节点上，超时提醒据此判定；
   * CC / CONDITION / END 配了会被发布校验拒绝（只有审批步骤才有「限时」的含义）。
   */
  timeLimitHours?: number | null
  /** CONDITION：分支出口 */
  branches?: FlowBranch[] | null
  /**
   * APPROVAL：驳回处理（M2）。缺省 = 终止整单，与第二期一致。
   *
   * 配置 GOTO 会让本定义携带「运行期特性」—— 因而**必须在「系统配置」里开启
   * 运行时条件引擎总开关**才能发布，否则发布接口会返回 `FLOW_RUNTIME_CONDITION_DISABLED`。
   */
  onReject?: RejectAction | null
  /** APPROVAL：超时处理（M2）。缺省 = 仅提醒，与第二期一致。配 ADD_SIGN / GOTO 同样受总开关约束。 */
  onTimeout?: TimeoutAction | null
}

export interface FlowDefinition {
  start: string
  nodes: FlowNode[]
}

/**
 * 服务端校验结果（M4a）。
 *
 * 后端 `POST /approval-flows/validate` 返回：校验"未通过"是**正常业务结果**，
 * 因此走 `success` 通道而非错误码，前端一次性拿到全部问题。
 */
export interface FlowValidateResult {
  /** 是否通过（problems 为空即通过） */
  valid: boolean
  /** 全部问题（保持发现顺序；为空数组代表通过） */
  problems: string[]
}

// ---------------------------------------------------------------------
// 接口出入参
// ---------------------------------------------------------------------

/** 流程列表项 */
export interface ApprovalFlowItem {
  id: number
  flowCode: string
  flowName: string
  description?: string | null
  status: ApprovalFlowStatusCode
  statusLabel?: string | null
  /** 最新已发布版本号（无已发布版本时为空） */
  latestPublishedVersionNo?: number | null
  latestPublishedVersionId?: number | null
  hasDraft?: boolean | null
  nodeCount?: number | null
  /** 被多少个申请类型引用（决定能否删除） */
  usedByTypeCount?: number | null
  createdAt?: string | null
  updatedAt?: string | null
}

/** 流程版本 */
export interface ApprovalFlowVersionItem {
  id: number
  flowId: number
  versionNo: number
  /** true = 草稿（未发布，不可被申请类型引用） */
  draft?: boolean | null
  nodeCount?: number | null
  publishedAt?: string | null
  definition?: FlowDefinition | null
}

/** 流程详情（含定义与版本列表） */
export interface ApprovalFlowDetail {
  id: number
  flowCode: string
  flowName: string
  description?: string | null
  status: ApprovalFlowStatusCode
  statusLabel?: string | null
  /** 当前草稿版本 id（无草稿时为空） */
  draftVersionId?: number | null
  publishedVersionNo?: number | null
  /** 有草稿给草稿，无草稿给最新已发布版本 */
  definition?: FlowDefinition | null
  versions?: ApprovalFlowVersionItem[] | null
  createdAt?: string | null
  updatedAt?: string | null
}

/** 新建 / 保存草稿请求 */
export interface ApprovalFlowPayload {
  flowCode: string
  flowName: string
  description?: string | null
  definition: FlowDefinition
}

/**
 * 「另存为 / 复制」请求（ · W4-B）。
 *
 * 复制的内容由后端从**源模板**取（草稿优先，无草稿取最新已发布版本），
 * 因此这里不需要、也不该携带 `definition` —— 带上它就不是"另存为"，而是"用别人的名字新建"。
 */
export interface ApprovalFlowDuplicatePayload {
  /** 新流程名称（必填） */
  flowName: string
  /**
   * 新流程编码（选填）。
   *
   * 留空由后端按源编码派生（`{源编码}_COPY`，冲突则继续追加序号）。
   * 之所以仍让前端能传：编码在创建后**没有修改入口**（设计器里只读），
   * 复制是用户唯一一次能决定它的机会。
   */
  flowCode?: string | null
  /** 新流程说明（选填）；留空沿用源模板说明 */
  description?: string | null
}

// ---------------------------------------------------------------------
// 流程预览（提交页用）
// ---------------------------------------------------------------------

/** 预览结果里的一个节点（含被跳过的） */
export interface FlowPreviewNode {
  nodeKey: string
  nodeName: string
  /** ：节点类型，用于预览里区分「审批」与「抄送」 */
  nodeType?: FlowNodeTypeCode | null
  nodeTypeLabel?: string | null
  signType?: SignTypeCode | null
  signTypeLabel?: string | null
  stepOrder: number
  /** false = 条件分支未命中，已被跳过 */
  onPath: boolean
  /** 分支说明：为何走到 / 为何跳过 */
  conditionDesc?: string | null
  /** ：该审批节点的时限（小时），空 = 不限时 */
  timeLimitHours?: number | null
}

/** 可选人员 */
export interface FlowPreviewCandidate {
  id: number
  name: string
}

/** 命中路径上「申请人自选审批人」的节点约束 */
export interface FlowChooseRequirement {
  nodeKey: string
  nodeName: string
  signType?: SignTypeCode | null
  signTypeLabel?: string | null
  stepOrder: number
  scope: ChooseScopeCode
  scopeValue?: string | null
  scopeLabel?: string | null
  minCount: number
  maxCount: number
  /**
   * 该范围内的可选人员 —— **首屏子集**，不是全量（ · W4-D）。
   *
   * <p>范围被限定（按角色 / 分组）时它就是全部候选人，行为与改造前一致；
   * 但范围 = 「全部员工」时它会随组织规模线性膨胀，因此后端只预装首屏若干条，
   * 完整列表走 `GET /apply-types/{id}/choose-candidates` 分页搜索（见 `useChooseCandidates`）。
   */
  candidates: FlowPreviewCandidate[]
  /** 该范围可选人员的**总数**（不受 {@link candidates} 截断影响）；老后端不返回时按首屏长度兜底 */
  candidateTotal?: number
  /** `candidates` 是否被截断（true = 只是首屏子集，应提示用户键入关键字搜索更多） */
  candidatesTruncated?: boolean
}

export interface FlowPreview {
  /** false = 该类型不使用独立审批流程（提交页隐藏整块） */
  flowUsed: boolean
  approvalFlowName?: string | null
  nodes: FlowPreviewNode[]
  chooseRequirements: FlowChooseRequirement[]
}

// ---------------------------------------------------------------------
// 展示辅助
// ---------------------------------------------------------------------

export function flowStatusTagType(status: ApprovalFlowStatusCode): 'success' | 'info' | 'warning' {
  if (status === 'PUBLISHED') return 'success'
  if (status === 'DISABLED') return 'warning'
  return 'info'
}

/**
 * 节点的「人话」摘要（列表 / 画布上都用）。
 *
 * 刻意放在 types 层而不是组件里：设计器画布、申请类型绑定下拉、详情抽屉三处都要显示，
 * 各写一份必然漂移。
 */
export function approverRuleText(rule: ApproverRule, roleName?: string, groupName?: string): string {
  switch (rule.type) {
    case 'SPECIFIC_USER':
      return `指定人员 ${rule.userIds?.length ?? 0} 人`
    case 'ROLE':
      return `角色：${roleName ?? rule.roleCode ?? '未选择'}`
    case 'BIZ_GROUP_APPROVERS':
      return '申请人所属部门的审批人'
    case 'PARENT_DEPT_APPROVERS':
      return '申请人上级部门的审批人'
    case 'HANDLER_GROUP':
      return `最终处理部门${groupName ? `：${groupName}` : ''}`
    case 'FORM_USER_FIELD':
      return `表单字段：${rule.fieldKey ?? '未选择'}`
    case 'LEADER':
      return '申请人直属领导'
    case 'PREV_ASSIGN':
      // 带上范围：节点摘要上「指定 1 人」与「从 IT执行人里指定 1 人」是完全不同的信息，
      // 少后半句会让设计者在监控页排查「怎么派给了这个人」时找不到线索。
      return `上一节点指定 ${rule.assignCount ?? 1} 人${
        assignScopeSuffix(rule.assignScope)
      }`
    case 'APPLICANT_CHOOSE':
      return `申请人自选（${rule.minCount ?? 1}-${rule.maxCount ?? rule.minCount ?? 1} 人）`
    default:
      return rule.type
  }
}

/** 把一条规则压成一句话（多规则取并集时用「、」连接） */
export function approverRulesText(rules?: ApproverRule[] | null): string {
  if (!rules || rules.length === 0) {
    return '未配置审批人'
  }
  return rules.map((rule) => approverRuleText(rule)).join('、')
}

export function nodeTypeLabel(type: FlowNodeTypeCode): string {
  return FLOW_NODE_TYPE_OPTIONS.find((item) => item.value === type)?.label ?? type
}

/** 是否默认出口（后端 JSON 的 `else`，此处用 `else===true` 判断而非"非条件即默认"） */
export function isElseBranch(branch: FlowBranch): boolean {
  return branch.else === true
}

/**
 * 出边集合（与后端 `FlowDefinitionValidator#outgoing` 同义）：
 * APPROVAL 与 **CC** 取 `next`，CONDITION 取各分支的 `next`。
 *
 * 设计器画布、孤岛检测、本地发布预检三处都要用，因此放在 types 层做单一来源。
 *
 * ⚠️ CC 必须算进出边：漏掉它会让抄送节点在「所有节点从 start 可达」这条校验里
 * 被判成不可达 —— 一个完全合法、能正常工作的抄送节点会被前端预检拦下发布。
 */
export function outgoingKeys(node: FlowNode | null | undefined): string[] {
  const result: string[] = []
  if (!node) {
    return result
  }
  if ((node.type === 'APPROVAL' || node.type === 'CC') && node.next) {
    result.push(node.next)
  }
  if (node.type === 'CONDITION') {
    for (const branch of node.branches ?? []) {
      if (branch?.next) {
        result.push(branch.next)
      }
    }
  }
  return result
}

// ---------------------------------------------------------------------
// 发布前本地预检（与后端 FlowDefinitionValidator 对齐的"能提前发现的错"）
// ---------------------------------------------------------------------

/** 节点 key 规则（与后端 KEY_PATTERN 逐字一致） */
export const FLOW_NODE_KEY_PATTERN = /^[A-Za-z][A-Za-z0-9_]{0,63}$/
export const FLOW_MAX_NODES = 50
export const FLOW_MAX_NAME_LENGTH = 64
/** 审批时限上限（小时）= 30 天；与后端 MAX_TIME_LIMIT_HOURS 一致 */
export const FLOW_MAX_TIME_LIMIT_HOURS = 720

/**
 * 条件字段 / 表单人员字段的候选来源。
 *
 * 流程模板是**独立**的（可被多个申请类型复用），设计时并不知道最终绑给哪个表单，
 * 因此这里只是"参考表单"带来的提示项；未提供时输入框退化为手填 key。
 * 字段是否真实存在，最终由后端在**绑定申请类型**时强校验（见 ApplyTypeServiceImpl）。
 */
export interface FlowFieldOption {
  /** 字段 key */
  value: string
  /** 字段显示名 */
  label: string
  /** types/form.ts 的 valueKind（TEXT / NUMBER / DATE / OPTION / USER_REF ...） */
  kind: string
}

/** 数值比较（GT/GTE/LT/LTE）只对这几类字段有意义 —— 与后端校验一致 */
const NUMERIC_KINDS = ['NUMBER', 'DATE', 'DATETIME']
/** 包含 / 不包含只对这几类字段有意义 */
const CONTAINS_KINDS = ['TEXT', 'OPTION', 'OPTION_MULTI']
/** 抄送节点上语义矛盾的两种规则（对象必须在提交那一刻就能算出来） */
const CC_FORBIDDEN_RULES: ApproverRuleTypeCode[] = ['APPLICANT_CHOOSE', 'PREV_ASSIGN']

function validateRule(rule: ApproverRule, ruleIndex: number, where: string): string[] {
  const problems: string[] = []
  const at = `${where}的第 ${ruleIndex + 1} 条审批人规则`
  switch (rule.type) {
    case 'SPECIFIC_USER':
      if (!rule.userIds || rule.userIds.length === 0) {
        problems.push(`${at}未选择人员`)
      }
      break
    case 'ROLE':
      if (!rule.roleCode) {
        problems.push(`${at}未选择角色`)
      }
      break
    case 'BIZ_GROUP_APPROVERS':
      // 无参数：运行时按申请人所属分组解析
      break
    case 'PARENT_DEPT_APPROVERS':
      // 无参数：运行时按申请人所在部门的**上级**部门解析。
      // 必须显式列出而不是落到 default —— 那个分支会把新类型判成「来源类型不合法」，
      // 于是设计器里选得出、却一保存就报错。
      break
    case 'HANDLER_GROUP':
      if (rule.handlerGroupId == null) {
        problems.push(`${at}未选择处理小组`)
      }
      break
    case 'FORM_USER_FIELD':
      if (!rule.fieldKey) {
        problems.push(`${at}未选择表单字段`)
      }
      break
    case 'LEADER':
      // 无参数：提交时取申请人的 users.leader_id 解析
      break
    case 'PREV_ASSIGN': {
      const count = rule.assignCount ?? 1
      if (count < 1) {
        problems.push(`${at}的指定人数必须大于等于 1`)
      }
      break
    }
    case 'APPLICANT_CHOOSE': {
      if (!rule.scope) {
        problems.push(`${at}未设置可选范围`)
      }
      const min = rule.minCount ?? 1
      const max = rule.maxCount ?? min
      if (min < 1) {
        problems.push(`${at}最少人数不能小于 1`)
      }
      if (max < min) {
        problems.push(`${at}最多人数不能小于最少人数`)
      }
      break
    }
    default:
      problems.push(`${at}的来源类型不合法：${rule.type}`)
  }
  return problems
}

/**
 * 「上一节点指定」的节点内硬约束（与后端 `validatePrevAssign` 同构）。
 *
 * 三条：① 必须是该节点**唯一**规则（人员在提交时未知，与其它规则取并集没有意义）；
 * ② **不得与会签组合**（语义是"指定若干人任一人审"，会签要求确定的一群人）；
 * ③ 「前面必须有审批节点」属于图性质，放在下面的 `prevAssignWithoutPrecedingApproval` 里判。
 */
function validatePrevAssign(node: FlowNode, where: string): string[] {
  const problems: string[] = []
  const rules = node.approverRules ?? []
  const prevAssignCount = rules.filter((rule) => rule.type === 'PREV_ASSIGN').length
  if (prevAssignCount === 0) {
    return problems
  }
  if (prevAssignCount > 1) {
    problems.push(`${where}配置了 ${prevAssignCount} 条「上一节点指定审批人」规则，最多只能有 1 条`)
  }
  if (rules.length > 1) {
    problems.push(
      `${where}使用了「上一节点指定审批人」，它必须是该节点唯一的审批人规则（人员提交时未知，与其他规则取并集没有意义）`
    )
  }
  if (node.signType === 'ALL_SIGN') {
    problems.push(`${where}使用了「上一节点指定审批人」，只支持或签，不能与会签组合`)
  }
  return problems
}

/**
 * 「上一节点指定」必须位于至少一个审批节点之后（与后端
 * `assertPrevAssignHasPrecedingApproval` 同构）。
 *
 * 实现上用「审批节点作屏障」的 BFS：从 start 出发，遇到审批节点就不往外扩散 ——
 * 于是「不经过任何审批节点即可到达的节点集合」里的审批节点，就是没有前置审批的节点。
 *
 * 为什么不用「入边里有没有 APPROVAL」这种更朴素的判断：那样会被
 * 「条件分支绕回」的形态骗过（分支本身不是审批节点，却能构成一条合法的前置路径）。
 */
function prevAssignWithoutPrecedingApproval(start: string, index: Map<string, FlowNode>): string[] {
  const reachable = new Set<string>([start])
  const queue: string[] = [start]
  while (queue.length > 0) {
    const key = queue.shift() as string
    const node = index.get(key)
    if (!node) {
      continue
    }
    // 审批节点是屏障：到达它就已经"经过了一个审批步骤"，不再往外扩散
    if (node.type === 'APPROVAL') {
      continue
    }
    for (const next of outgoingKeys(node)) {
      if (!reachable.has(next)) {
        reachable.add(next)
        queue.push(next)
      }
    }
  }
  const result: string[] = []
  for (const key of reachable) {
    const node = index.get(key)
    if (!node || node.type !== 'APPROVAL') {
      continue
    }
    if ((node.approverRules ?? []).some((rule) => rule.type === 'PREV_ASSIGN')) {
      result.push(key)
    }
  }
  return result
}

/**
 * 条件树校验（M3-A）：递归 + 深度上限 + 组结构约束。
 *
 * 与后端 `FlowDefinitionValidator#validateCondition` 同构。三条硬约束：
 * ① **深度上限**：最外层算第 1 层，超过 `flowConditionMaxDepth()` 报一条并**立即返回** ——
 * 继续递归会让一个 4 层嵌套报出 3 条同样的话，而真正要告诉配置者的只有一条
 * （"收集全部问题"是为了看到**全部不同**的问题，不是把同一个问题按层数复述）；
 * ② **组不得携带 field/op/value**；③ **组不能为空**。
 *
 * `kind` 取不认识的值时**必须报错而不是静默当 RULE 用**：若静默降级，一个本想配成组的项
 * 会因为 field 为空被报成"未选择字段"，配置者会去改字段选择，而真正的问题在 kind 上。
 */
function validateConditionTree(
  condition: FlowCondition,
  where: string,
  depth: number,
  fieldIndex: Map<string, FlowFieldOption>
): string[] {
  const problems: string[] = []
  const maxDepth = flowConditionMaxDepth()
  if (depth > maxDepth) {
    problems.push(
      `${where}的条件嵌套层数超过上限（最多 ${maxDepth} 层，当前至少 ${depth} 层）`
    )
    return problems
  }
  if (condition.logic !== 'AND' && condition.logic !== 'OR') {
    problems.push(`${where}的组合逻辑不合法：${condition.logic}`)
  }
  const rules = condition.rules ?? []
  if (rules.length === 0) {
    problems.push(`${where}的条件列表为空`)
  }
  rules.forEach((rule, ruleIndex) => {
    const ruleWhere = `${where}的第 ${ruleIndex + 1} 条条件`
    if (!rule) {
      problems.push(`${ruleWhere}为空`)
      return
    }
    if (isConditionGroup(rule)) {
      problems.push(...validateConditionGroup(rule, ruleWhere, depth, fieldIndex))
      return
    }
    const kind = conditionKindOf(rule)
    if (kind !== '' && kind !== CONDITION_KIND_RULE) {
      problems.push(`${ruleWhere}的种类不合法：${rule.kind}（只能是 RULE 或 GROUP）`)
      return
    }
    problems.push(...validatePlainRule(rule, ruleWhere, fieldIndex))
  })
  return problems
}

/** 嵌套条件组自身的约束（与后端 `validateGroup` 同构） */
function validateConditionGroup(
  rule: FlowConditionRule,
  where: string,
  depth: number,
  fieldIndex: Map<string, FlowFieldOption>
): string[] {
  const problems: string[] = []
  if ((rule.field ?? '').trim() !== '' || (rule.op ?? '') !== '' || rule.value != null) {
    problems.push(`${where}是嵌套条件组，不应携带字段 / 运算符 / 比较值`)
  }
  const inner = rule.condition
  if (!inner) {
    problems.push(`${where}是空的嵌套条件组（未配置任何子条件）`)
    return problems
  }
  if ((inner.rules ?? []).length === 0) {
    problems.push(`${where}的嵌套条件组为空`)
    return problems
  }
  problems.push(...validateConditionTree(inner, `${where}的嵌套条件组`, depth + 1, fieldIndex))
  return problems
}

/** 单条规则：字段 / 运算符 / 比较值 / 类型可比性 */
function validatePlainRule(
  rule: FlowConditionRule,
  where: string,
  fieldIndex: Map<string, FlowFieldOption>
): string[] {
  const problems: string[] = []
  if (!rule.field) {
    problems.push(`${where}未选择字段`)
    return problems
  }
  const operator = FLOW_OPERATOR_OPTIONS.find((item) => item.value === rule.op)
  if (!operator) {
    problems.push(`${where}的运算符不合法：${rule.op ?? ''}`)
    return problems
  }
  if (
    operator.requiresValue &&
    (rule.value === null || rule.value === undefined || rule.value === '')
  ) {
    problems.push(`${where}的「${operator.label}」未填写比较值`)
  }
  // 运行期伪字段（process.*）：走内置白名单，**不能**落到表单 schema 查找 ——
  // 否则 `process.prevNodeResult` 会被误判成"引用的表单字段不存在"（后端同样先分流）。
  if (isProcessField(rule.field)) {
    if (!processFieldIsKnown(rule.field)) {
      problems.push(`${where}引用的运行期字段「${rule.field}」不存在`)
      return problems
    }
    const processField = PROCESS_FIELD_OPTIONS.find((item) => item.value === rule.field)
    if (processField && operator.numeric && !NUMERIC_KINDS.includes(processField.kind)) {
      problems.push(
        `${where}的「${operator.label}」只能用于数字或日期字段，而「${processField.label}」不是`
      )
    }
    if (
      processField &&
      (rule.op === 'CONTAINS' || rule.op === 'NOT_CONTAINS') &&
      !CONTAINS_KINDS.includes(processField.kind)
    ) {
      problems.push(
        `${where}的「${operator.label}」只能用于文本或选项字段，而「${processField.label}」不是`
      )
    }
    return problems
  }
  const field = fieldIndex.get(rule.field)
  if (!field) {
    return problems
  }
  if (operator.numeric && !NUMERIC_KINDS.includes(field.kind)) {
    problems.push(`${where}的「${operator.label}」只能用于数字或日期字段，而「${field.label}」不是`)
  }
  if (
    (rule.op === 'CONTAINS' || rule.op === 'NOT_CONTAINS') &&
    !CONTAINS_KINDS.includes(field.kind)
  ) {
    problems.push(`${where}的「${operator.label}」只能用于文本或选项字段，而「${field.label}」不是`)
  }
  return problems
}

// ---------------------------------------------------------------------
// 运行期字段（M2）：process.* 伪字段清单 + 运行期特性判定
// ---------------------------------------------------------------------

/**
 * 运行期字段候选（与后端 `ProcessFieldCatalog.schema()` 同构）。
 *
 * <p>这些值**在提交那一刻还不存在**：它们描述的是"审批过程中才产生"的数据。
 * 因此引用它们的条件在提交时判不了，其下游节点会先物化为 `INACTIVE`（未激活），
 * 等前置节点完成后再由重算收敛为「走」或「不走」。
 *
 * <p>`kind` 与表单字段用同一套 valueKind 取值，于是运算符可比性判断可以直接复用 ——
 * 前端不需要为运行期字段单开一套规则。
 */
export const PROCESS_FIELD_OPTIONS: Array<{
  value: string
  label: string
  kind: string
  /** 枚举类字段的可选值（配置器据此给下拉而不是让人猜） */
  options?: Array<{ value: string; label: string }>
}> = [
  {
    value: 'process.prevNodeResult',
    label: '上一节点结果',
    kind: 'OPTION',
    options: [
      { value: 'APPROVED', label: '已通过' },
      { value: 'REJECTED', label: '已驳回' }
    ]
  },
  { value: 'process.prevNodeHours', label: '上一节点耗时(小时)', kind: 'NUMBER' },
  { value: 'process.elapsedHours', label: '工单已耗时(小时)', kind: 'NUMBER' },
  {
    value: 'process.anyRejected',
    label: '是否发生过驳回',
    kind: 'OPTION',
    options: [
      { value: 'true', label: '是' },
      { value: 'false', label: '否' }
    ]
  },
  { value: 'process.rejectCount', label: '驳回次数', kind: 'NUMBER' },
  { value: 'process.activatedCount', label: '已激活节点数', kind: 'NUMBER' }
]

/** 是否为运行期伪字段（只看前缀） */
export function isProcessField(field?: string | null): boolean {
  return !!field && field.startsWith(PROCESS_FIELD_PREFIX)
}

/** 前缀是 `process.` 且在白名单里；`process.foo` 这类拼错必须被拦下（后端同样严格） */
export function processFieldIsKnown(field?: string | null): boolean {
  return PROCESS_FIELD_OPTIONS.some((item) => item.value === field)
}

/** 运行期字段的可读名（条件说明文案用；未知值原样返回） */
export function processFieldLabel(field?: string | null): string {
  if (!field) {
    return ''
  }
  return PROCESS_FIELD_OPTIONS.find((item) => item.value === field)?.label ?? field
}

/** 本节点是否携带运行期动作（驳回改道 / 超时加签或改道） */
export function nodeHasRuntimeAction(node: FlowNode): boolean {
  const rejectGoto = node.onReject?.action === 'GOTO'
  const timeoutUpgrade = node.onTimeout?.action === 'ADD_SIGN' || node.onTimeout?.action === 'GOTO'
  return rejectGoto || timeoutUpgrade
}

/**
 * 本定义是否携带「运行期特性」—— 与后端 `FlowDefinition.hasRuntimeFeature()` 逐条同构。
 *
 * <p>三种来源：驳回改道、超时升级、条件引用 `process.*` 字段。
 * 它是**"要不要走新代码路径"的唯一判据**，也是发布闸门的触发条件：
 * 一旦为 true，就必须先开启运行时条件引擎总开关，否则后端拒绝发布。
 */
export function flowHasRuntimeFeature(definition: FlowDefinition | null | undefined): boolean {
  for (const node of definition?.nodes ?? []) {
    if (!node) {
      continue
    }
    if (nodeHasRuntimeAction(node)) {
      return true
    }
    for (const branch of node.branches ?? []) {
      if (branch && conditionIsRuntimeDependent(branch.condition)) {
        return true
      }
    }
  }
  return false
}

/**
 * 条件里是否引用了运行期字段（`process.*`）—— **必须递归到任意深度**。
 *
 * <p>这是 M3-A 静默失效风险最高的一处：漏了递归，嵌套组里的 `process.*` 会被判成
 * "提交时就能下结论"，本该留到运行期再算的分支直接定了态 —— 不报错、不进日志，
 * 只让工单走错分支。因此它与后端 `ProcessFieldCatalog.isRuntimeDependent` 同构，
 * 并由共享金样例的 `nested-process-field-runtime-dependent` 用例两端同时钉住。
 */
export function conditionIsRuntimeDependent(condition?: FlowCondition | null): boolean {
  for (const rule of condition?.rules ?? []) {
    if (!rule) {
      continue
    }
    if (isConditionGroup(rule)) {
      if (conditionIsRuntimeDependent(rule.condition)) {
        return true
      }
      continue
    }
    if (isProcessField(rule.field)) {
      return true
    }
  }
  return false
}

/**
 * 引用 `process.*` 的条件必须位于**至少一个审批节点之后**（与后端
 * `assertRuntimeConditionHasPrecedingApproval` 同构）。
 *
 * <p>它挡住的是一笔死单：提交时这类条件的下游落 `INACTIVE`，而"激活"的触发点是
 * **前一个节点完成**。若它排在第一个审批节点之前，提交后工单是"待审批"却没有任何
 * PENDING 节点 —— 没有待办能推动它，也没有任何操作能救活它。
 *
 * <p>实现与 PREV_ASSIGN 那条对称：从 start 出发、以 APPROVAL 为屏障做 BFS，
 * 收集"未经审批即可到达"的节点。
 */
function runtimeConditionWithoutPrecedingApproval(
  start: string,
  index: Map<string, FlowNode>
): string[] {
  const reachable = new Set<string>([start])
  const queue: string[] = [start]
  while (queue.length > 0) {
    const key = queue.shift() as string
    const node = index.get(key)
    if (!node) {
      continue
    }
    // 审批节点是屏障：到达它就已经"经过了一个审批步骤"
    if (node.type === 'APPROVAL') {
      continue
    }
    for (const next of outgoingKeys(node)) {
      if (!reachable.has(next)) {
        reachable.add(next)
        queue.push(next)
      }
    }
  }
  const result: string[] = []
  for (const key of reachable) {
    const node = index.get(key)
    if (!node || node.type !== 'CONDITION') {
      continue
    }
    if ((node.branches ?? []).some((branch) => branch && conditionIsRuntimeDependent(branch.condition))) {
      result.push(key)
    }
  }
  return result
}

/**
 * 校验节点的 `onReject` / `onTimeout`（与后端 `validateRuntimeActions` 同构）。
 *
 * @param index 节点索引（用于校验改道目标是否存在）
 */
function validateRuntimeActions(node: FlowNode, index: Map<string, FlowNode>, where: string): string[] {
  const problems: string[] = []

  const reject = node.onReject
  if (reject) {
    // 「不认识的 action 必须报错」而不是当默认值：action 写成 "GOT" 若被静默当作 TERMINATE，
    // 配置者会以为改道生效了，而实际每笔驳回都在终止整单。
    const rejectInvalid = !rejectIsValid(reject)
    if (rejectInvalid) {
      problems.push(`${where}的「驳回处理」配置不完整：改道（GOTO）必须指定已存在的目标节点`)
    } else if (reject.action === 'GOTO' && reject.target) {
      problems.push(...checkActionTarget(reject.target, index, `${where}的「驳回改道」`))
    }
  }

  const timeout = node.onTimeout
  if (timeout) {
    // 注意顺序：isValid 不通过时**跳过**目标校验（与后端 if/else-if 一致），
    // 但下面的「加签阈值」是一个独立的 if —— 即使配置不完整也要把阈值问题一起报出来。
    if (!timeoutIsValid(timeout)) {
      problems.push(`${where}的「超时处理」配置不合法：加签/改道需有效目标，超时阈值须为正数`)
    } else if (timeout.action === 'GOTO' && timeout.target) {
      problems.push(...checkActionTarget(timeout.target, index, `${where}的「超时改道」`))
    }
    if (timeout.action === 'ADD_SIGN') {
      if (timeout.afterHours == null) {
        problems.push(`${where}的「超时加签」未设置超时阈值（小时）`)
      } else if (timeout.afterHours > FLOW_MAX_TIME_LIMIT_HOURS) {
        problems.push(`${where}的「超时加签」阈值超出上限 ${FLOW_MAX_TIME_LIMIT_HOURS} 小时`)
      }
      // 加签人不填是合法的（继承原节点规则），只在填了的时候校验每条规则本身
      ;(timeout.approvers ?? []).forEach((rule, ruleIndex) =>
        problems.push(...validateRule(rule, ruleIndex, `${where}的「超时加签」`))
      )
    }
  }
  return problems
}

/** 驳回动作配置是否完整（与后端 `RejectAction.isValid()` 同构） */
function rejectIsValid(reject: RejectAction): boolean {
  const action = reject.action
  if (action != null && action !== 'TERMINATE' && action !== 'GOTO') {
    return false
  }
  return action !== 'GOTO' || !!reject.target
}

/** 超时动作配置是否合法（与后端 `TimeoutAction.isValid()` 同构：未知 action / GOTO 缺目标 / 阈值非正） */
function timeoutIsValid(timeout: TimeoutAction): boolean {
  const action = timeout.action
  if (action != null && action !== 'NOTIFY' && action !== 'ADD_SIGN' && action !== 'GOTO') {
    return false
  }
  if (action === 'GOTO' && !timeout.target) {
    return false
  }
  return timeout.afterHours == null || timeout.afterHours > 0
}

/**
 * 改道目标必须是**存在且可推进入口**的节点。
 *
 * <p>指向 END 或 CONDITION 会让工单在改道后立刻终止或再次分叉，都不是"改道"的语义，
 * 因此这里只接受审批 / 抄送节点。
 */
function checkActionTarget(target: string, index: Map<string, FlowNode>, at: string): string[] {
  const node = index.get(target)
  if (!node) {
    return [`${at}的目标节点不存在：${target}`]
  }
  if (node.type !== 'APPROVAL' && node.type !== 'CC') {
    const label = FLOW_NODE_TYPE_OPTIONS.find((item) => item.value === node.type)?.label ?? '未知类型'
    return [`${at}的目标必须是审批或抄送节点，而「${target}」是${label}`]
  }
  return []
}

/**
 * 发布前本地预检。
 *
 * <p>返回全部问题（而不是首个），让配置者在一次操作里看完所有要改的地方。
 * **它只是体验**：真正的准入以后端 `FlowDefinitionValidator` 为准 ——
 * 前端预检放过了、后端拦下的，仍会由请求层提示；反之亦然（两边规则刻意保持同构）。
 *
 * @param fields 参考表单字段（可选）。提供时才做"字段是否存在 / 运算符与字段类型是否匹配"检查 ——
 *               与后端一致：流程发布时 schema 可能为空，字段级校验延后到绑定申请类型。
 */
export function validateFlowForPublish(
  definition: FlowDefinition | null | undefined,
  fields?: FlowFieldOption[] | null
): string[] {
  if (!definition) {
    return ['流程定义为空']
  }
  const nodes = definition.nodes ?? []
  if (nodes.length === 0) {
    return ['流程至少要有一个节点']
  }
  const problems: string[] = []
  if (nodes.length > FLOW_MAX_NODES) {
    problems.push(`节点数量（${nodes.length}）超过上限 ${FLOW_MAX_NODES}`)
  }

  const index = new Map<string, FlowNode>()
  for (const node of nodes) {
    const key = (node.key ?? '').trim()
    if (!key) {
      problems.push('存在未设置标识（key）的节点')
      continue
    }
    if (!FLOW_NODE_KEY_PATTERN.test(key)) {
      problems.push(`节点标识不合法：${key}（字母开头，仅字母/数字/下划线，最长 64）`)
    }
    if (index.has(key)) {
      problems.push(`节点标识重复：${key}`)
    } else {
      index.set(key, node)
    }
  }

  if (!definition.start) {
    problems.push('未设置起始节点')
  } else if (!index.has(definition.start)) {
    problems.push(`起始节点不存在：${definition.start}`)
  }

  const fieldIndex = new Map((fields ?? []).map((field) => [field.value, field]))

  // 本定义是否携带运行期特性（M2）。它决定若干条"严格规则"是否生效 ——
  // 注意**不含**总开关判断：开关是环境状态，由后端 validate 端点返回，前端不做本地镜像
  //（否则会出现"前端认为能发、后端拒绝"的第二套真相）。
  const runtimeFlow = flowHasRuntimeFeature(definition)

  for (const node of nodes) {
    const where = `节点「${node.key || '?'}」`
    const name = (node.name ?? '').trim()
    if (!name) {
      problems.push(`${where}未设置名称`)
    } else if (name.length > FLOW_MAX_NAME_LENGTH) {
      problems.push(`${where}名称过长（最多 ${FLOW_MAX_NAME_LENGTH} 字）`)
    }

    if (node.type === 'APPROVAL') {
      if ((node.branches ?? []).length > 0) {
        problems.push(`${where}是审批节点，不应配置分支`)
      }
      const rules = node.approverRules ?? []
      if (rules.length === 0) {
        problems.push(`${where}未配置任何审批人`)
      }
      const chooseCount = rules.filter((rule) => rule.type === 'APPLICANT_CHOOSE').length
      if (chooseCount > 1) {
        problems.push(`${where}配置了 ${chooseCount} 条「申请人自选」规则，最多只能有 1 条`)
      }
      problems.push(...validatePrevAssign(node, where))
      rules.forEach((rule, ruleIndex) => problems.push(...validateRule(rule, ruleIndex, where)))
      if (!node.next) {
        problems.push(`${where}未指定下一节点`)
      } else if (!index.has(node.next)) {
        problems.push(`${where}的下一节点不存在：${node.next}`)
      }
      if (node.signType && node.signType !== 'ANY_SIGN' && node.signType !== 'ALL_SIGN') {
        problems.push(`${where}的签署方式不合法：${node.signType}`)
      }
      if (
        node.timeLimitHours != null &&
        (node.timeLimitHours < 1 || node.timeLimitHours > FLOW_MAX_TIME_LIMIT_HOURS)
      ) {
        problems.push(
          `${where}的审批时限必须在 1~${FLOW_MAX_TIME_LIMIT_HOURS} 小时之间（当前 ${node.timeLimitHours}）`
        )
      }
      if (runtimeFlow && chooseCount > 0) {
        // 含运行期条件的流程里节点可能在审批过程中才被激活（提交时是 INACTIVE），
        // 而「申请人自选」的结果**不随工单保存** —— 运行期激活时无从知道申请人选了谁。
        // 与其留一个运行期才炸的坑，不如在发布期就拦住（与后端同源）。
        problems.push(
          `${where}配置了「申请人自选」——含运行期条件的流程不支持它：自选结果不随工单保存，运行期激活该节点时无法复现申请人的选择`
        )
      }
      problems.push(...validateRuntimeActions(node, index, where))
    } else if (node.type === 'CC') {
      // 抄送节点：语义上比审批节点更"轻"，因此配置面也更窄
      const rules = node.approverRules ?? []
      if (rules.length === 0) {
        problems.push(`${where}未配置任何抄送对象`)
      }
      for (const rule of rules) {
        if (CC_FORBIDDEN_RULES.includes(rule.type)) {
          problems.push(
            `${where}是抄送节点，不支持「${approverRuleLabel(rule.type)}」——抄送对象必须在提交时即可确定`
          )
        }
      }
      if ((node.branches ?? []).length > 0) {
        problems.push(`${where}是抄送节点，不应配置分支`)
      }
      if (node.signType) {
        problems.push(`${where}是抄送节点，不应配置签署方式`)
      }
      if (node.timeLimitHours != null) {
        problems.push(`${where}是抄送节点，不应配置审批时限`)
      }
      rules.forEach((rule, ruleIndex) => problems.push(...validateRule(rule, ruleIndex, where)))
      if (!node.next) {
        problems.push(`${where}未指定下一节点`)
      } else if (!index.has(node.next)) {
        problems.push(`${where}的下一节点不存在：${node.next}`)
      }
    } else if (node.type === 'CONDITION') {
      if (node.next) {
        problems.push(`${where}是条件分支节点，出口写在分支里，不应额外配置 next`)
      }
      if ((node.approverRules ?? []).length > 0) {
        problems.push(`${where}是条件分支节点，不应配置审批人`)
      }
      const branches = node.branches ?? []
      if (branches.length === 0) {
        problems.push(`${where}未配置任何分支`)
      }
      const elseCount = branches.filter((branch) => branch.else === true).length
      if (elseCount !== 1) {
        problems.push(`${where}必须且只能有一个默认出口（当前 ${elseCount} 个）`)
      }
      const branchKeys = new Set<string>()
      branches.forEach((branch, branchIndex) => {
        const branchWhere = `${where}的第 ${branchIndex + 1} 个分支`
        if (!branch.key) {
          problems.push(`${branchWhere}未设置标识（key）`)
        } else if (branchKeys.has(branch.key)) {
          // 注意：JS 的 Set.prototype.add 返回 Set 自身（不是布尔），
          // 这里若照搬 Java 的「!set.add(x)」写法会永远为 false，检查形同虚设。
          problems.push(`${branchWhere}标识重复：${branch.key}`)
        } else {
          branchKeys.add(branch.key)
        }
        if (!(branch.name ?? '').trim()) {
          problems.push(`${branchWhere}未设置名称`)
        }
        if (!branch.next) {
          problems.push(`${branchWhere}未指定去向节点`)
        } else if (!index.has(branch.next)) {
          problems.push(`${branchWhere}指向的节点不存在：${branch.next}`)
        }
        if (branch.else === true) {
          return
        }
        const condition = branch.condition
        if (!condition) {
          problems.push(`${branchWhere}未配置条件`)
          return
        }
        // M3-A：条件校验整块收敛到 `validateConditionTree`（递归 + 深度上限 + 组约束）。
        // 它同时也被校验端点/后端使用同构规则，两端由共享金样例对齐。
        problems.push(...validateConditionTree(condition, branchWhere, 1, fieldIndex))
      })
    } else if (node.type === 'END') {
      if (node.next) {
        problems.push(`${where}是结束节点，不应配置 next`)
      }
      if ((node.branches ?? []).length > 0) {
        problems.push(`${where}是结束节点，不应配置分支`)
      }
      if ((node.approverRules ?? []).length > 0) {
        problems.push(`${where}是结束节点，不应配置审批人`)
      }
      if (node.timeLimitHours != null) {
        problems.push(`${where}是结束节点，不应配置审批时限`)
      }
    } else {
      problems.push(`${where}的类型不合法：${node.type}`)
    }
  }

  // 无环（DFS 三色标记，与后端 detectCycle 同构）
  const state = new Map<string, number>()
  const path: string[] = []
  let cycleFound = false
  const dfs = (key: string): void => {
    if (cycleFound) {
      return
    }
    const current = state.get(key) ?? 0
    if (current === 2) {
      return
    }
    if (current === 1) {
      problems.push(`流程存在环：${[...path, key].join(' → ')}`)
      cycleFound = true
      return
    }
    state.set(key, 1)
    path.push(key)
    for (const next of outgoingKeys(index.get(key))) {
      dfs(next)
    }
    path.pop()
    state.set(key, 2)
  }
  if (definition.start && index.has(definition.start)) {
    dfs(definition.start)
  }

  // 从 start 出发全部可达
  if (definition.start && index.has(definition.start)) {
    const seen = new Set<string>([definition.start])
    const queue: string[] = [definition.start]
    while (queue.length > 0) {
      const key = queue.shift() as string
      for (const next of outgoingKeys(index.get(key))) {
        if (!seen.has(next)) {
          seen.add(next)
          queue.push(next)
        }
      }
    }
    const unreachable = [...index.keys()].filter((key) => !seen.has(key))
    if (unreachable.length > 0) {
      problems.push(`存在从起始节点不可达的节点：${unreachable.join('、')}`)
    }
  }

  // 所有节点都能到达 END（无死路）
  {
    const incoming = new Map<string, string[]>()
    index.forEach((_, key) => incoming.set(key, []))
    index.forEach((node, key) => {
      for (const next of outgoingKeys(node)) {
        // next 可能指向不存在的节点；只记录已登记节点的反向边
        if (incoming.has(next)) {
          ;(incoming.get(next) as string[]).push(key)
        }
      }
    })
    const reachesEnd = new Set<string>()
    const queue: string[] = []
    index.forEach((node, key) => {
      if (node.type === 'END') {
        reachesEnd.add(key)
        queue.push(key)
      }
    })
    while (queue.length > 0) {
      const key = queue.shift() as string
      for (const prev of incoming.get(key) ?? []) {
        if (!reachesEnd.has(prev)) {
          reachesEnd.add(prev)
          queue.push(prev)
        }
      }
    }
    const deadEnds = [...index.keys()].filter((key) => !reachesEnd.has(key))
    if (deadEnds.length > 0) {
      problems.push(
        `存在无法到达结束节点的${deadEnds.length > 1 ? '死路' : '节点'}：${deadEnds.join('、')}`
      )
    }
  }

  // 「上一节点指定」必须有前置审批节点
  if (definition.start && index.has(definition.start)) {
    const missing = prevAssignWithoutPrecedingApproval(definition.start, index)
    for (const key of missing) {
      problems.push(
        `节点「${key}」使用了「上一节点指定审批人」，但它前面没有审批节点，没有上一节点可供指定（请把它放在至少一个审批节点之后）`
      )
    }

    // 运行期条件同样必须有前置审批节点 —— 它挡住的是一笔死单：
    // 提交时这类条件判不了、下游落 INACTIVE，而"激活"的触发点是前一个节点完成。
    // 若它排在第一个审批节点之前，提交后工单是"待审批"却没有任何 PENDING 节点能推动它。
    const missingRuntime = runtimeConditionWithoutPrecedingApproval(definition.start, index)
    for (const key of missingRuntime) {
      problems.push(
        `条件节点「${key}」引用了运行期字段（上一节点结果 / 已耗时等），但它前面没有审批节点，提交后将没有任何待办能推动它（请把它放在至少一个审批节点之后）`
      )
    }
  }

  const approvalCount = nodes.filter((node) => node.type === 'APPROVAL').length
  if (approvalCount === 0) {
    problems.push('流程中至少要有一个审批节点')
  }

  return problems
}

/**
 * 条件的一句话摘要（设计器画布 / 属性面板共用）。
 *
 * M3-A 起支持嵌套组，规则与后端 `FlowPathResolver.describeRule` 逐条同构：
 * 嵌套组递归出组内文案后**加全角括号**。括号不是装饰 —— 「A 且 B 或 C」与
 * 「A 且 (B 或 C)」是两份完全不同的配置，而摘要文案是配置者核对
 * "我配的到底是不是我想的"的唯一入口，压平层级等于让人看不出自己配错了。
 */
export function conditionRuleText(
  rule: FlowConditionRule,
  fieldLabel?: string,
  labelOf?: (key: string) => string
): string {
  if (isConditionGroup(rule)) {
    return `（${conditionText(rule.condition, labelOf)}）`
  }
  const name = fieldLabel ?? rule.field ?? '未选字段'
  const op = rule.op
  if (!op) {
    // 运算符缺失（半残对象）时只回显字段名：与后端 `describeRule` 的
    // 「operator == null → 只给字段名」一致，不编一个"等于"出来。
    return name
  }
  const operator = flowOperatorLabel(op)
  if (!flowOperatorRequiresValue(op)) {
    return `${name} ${operator}`
  }
  return `${name} ${operator} ${rule.value === null || rule.value === undefined ? '' : String(rule.value)}`
}

/** 条件组摘要：多条用 AND/OR 连接（嵌套组递归展开，见 conditionRuleText） */
export function conditionText(condition: FlowCondition | null | undefined, labelOf?: (key: string) => string): string {
  if (!condition || (condition.rules ?? []).length === 0) {
    return '未配置条件'
  }
  const joiner = condition.logic === 'OR' ? ' 或 ' : ' 且 '
  return (condition.rules ?? [])
    .map((rule) => (rule ? conditionRuleText(rule, rule.field ? labelOf?.(rule.field) : undefined, labelOf) : ''))
    .join(joiner)
}
