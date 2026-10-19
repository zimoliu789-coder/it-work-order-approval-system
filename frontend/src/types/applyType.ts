import type { FlowDefinition } from '@/types/approvalFlow'
import type { FormSchema } from '@/types/form'

/**
 * 自定义申请类型类型
 *
 * 与后端 `module/applytype/dto` 下的 VO / DTO 一一对应。
 * 全局 `default-property-inclusion: non_null`：后端 null 字段整条省略，
 * 因此可空字段一律声明为可选。
 */

/** 类型状态 */
export type ApplyTypeStatusCode = 'ENABLED' | 'DISABLED'

/**
 * 审批方式：
 * - `NONE` 无审批（提交即完成）
 * - `GROUP` 走申请人所属部门的审批流（一期机制）
 * - `FLOW` 使用独立审批流程模板，此时必须绑定一个**已发布**的流程版本
 */
export type ApprovalModeCode = 'NONE' | 'GROUP' | 'FLOW'

/** 提交权限类型：ALL 全部登录用户 / ROLE 指定角色 / GROUP 指定部门 */
export type SubmitPermissionTypeCode = 'ALL' | 'ROLE' | 'GROUP'

/** 审批方式选项（含给提交者的一句预期提示） */
export const APPROVAL_MODE_OPTIONS: Array<{
  value: ApprovalModeCode
  label: string
  desc: string
}> = [
  { value: 'GROUP', label: '分组审批', desc: '按申请人所属部门的审批流逐级审批，通过后进入已完成' },
  { value: 'FLOW', label: '自定义审批流程', desc: '按绑定的审批流程模板走，支持条件分支与多种审批人来源' },
  { value: 'NONE', label: '无需审批', desc: '提交后直接进入已完成，适合登记 / 备案类申请' }
]

/** 提交权限类型选项 */
export const SUBMIT_PERMISSION_TYPE_OPTIONS: Array<{
  value: SubmitPermissionTypeCode
  label: string
  desc: string
}> = [
  { value: 'ALL', label: '全部登录用户', desc: '任何登录用户都可以提交' },
  { value: 'ROLE', label: '指定角色', desc: '仅选中的角色可以提交' },
  { value: 'GROUP', label: '指定部门', desc: '仅选中的部门成员可以提交' }
]

/** 申请类型管理列表项 / 详情（详情多一个 schema） */
export interface ApplyTypeItem {
  id: number
  typeCode: string
  typeName: string
  icon?: string | null
  description?: string | null
  sortOrder?: number | null
  status: ApplyTypeStatusCode
  statusLabel?: string | null
  /**
   * 是否系统内置类型（V48）：借用设备 / 故障报修。
   *
   * 内置 = 表单与后置动作都在代码里（借用要锁设备、报修要建故障记录），
   * 管理员能改的只有「表单说明」与「审批流程」，且**不可删除**。
   */
  builtIn?: boolean | null
  formTemplateVersionId?: number | null
  formTemplateName?: string | null
  formTemplateVersionNo?: number | null
  orderPrefix?: string | null
  approvalMode: ApprovalModeCode
  approvalModeLabel?: string | null
  /** 绑定的审批流程版本 id（仅 approvalMode = FLOW 时非空） */
  approvalFlowVersionId?: number | null
  /** 流程可读名（如「采购审批 v2」） */
  approvalFlowName?: string | null
  submitPermissionType: SubmitPermissionTypeCode
  submitPermissionTypeLabel?: string | null
  /** 提交权限值原样（ROLE = 角色码；GROUP = 分组 id 的字符串形式） */
  submitPermissionValues?: string[] | null
  /** 提交权限可读文案（管理列表展示用） */
  submitPermissionText?: string | null
  usedByOrder?: boolean | null
  orderCount?: number | null
  createdAt?: string | null
  updatedAt?: string | null
  /** 表单定义；仅详情接口返回 */
  schema?: FormSchema | null
}

/**
 * 系统内置类型的配置面板数据（V48）—— 与后端 `BuiltInConfigVO` 逐字对应。
 *
 * 一个请求拿到弹窗要的全部：说明、当前生效的流程、已绑流程的模板 id（改完要 update + publish）、
 * 以及条件分支可引用的字段清单。
 */
export interface BuiltInApplyConfig {
  id: number
  /** BORROW / FAULT_REPORT —— 前端据此决定「内置表单说明」文案 */
  typeCode: string
  typeName: string
  description?: string | null
  builtIn: boolean
  /** 审批流程是否可编辑；故障报修为 false（它不产生工单，域模型里没有审批环节） */
  flowEditable: boolean
  /**
   * 是否有「处理流程」（V50）：报修为 true。
   *
   * 与 `flowEditable` 并列而不是互相推导 —— 它们回答两个问题：
   * 「能改审批流吗」与「有处理流吗」。故障报修恰好是 `false / true` 这个组合，
   * 页面必须同时知道这两件事，才能既隐藏审批流编辑器、又渲染出处理流程配置区。
   */
  hasHandler: boolean
  /** 处理流程配置（只有 `hasHandler` 为 true 时下发，其余为 null） */
  handler?: BuiltInHandlerConfig | null
  /** 「为什么表单不可改 / 流程不可改」的一句话说明，直接展示给管理员 */
  formNote?: string | null
  /** 是否已绑定自定义流程；false 时 flowDefinition 是该内置类型的**默认流程** */
  flowBound: boolean
  approvalFlowVersionId?: number | null
  /** 已绑流程对应的模板 id（保存时在模板上「改草稿 → 发布新版本」需要它） */
  flowTemplateId?: number | null
  flowCode?: string | null
  flowName?: string | null
  /** 流程设计器的初始定义：已绑定时为当前版本定义，未绑定时为默认流程 */
  flowDefinition?: FlowDefinition | null
  /** 条件分支可引用的字段清单（借用域字段目录） */
  fieldSchema?: FormSchema | null
  /**
   * 内置类型当前绑定的表单模板版本 id（V59 / D3「表单完全放开」）。
   *
   * <p>改造前内置类型没有表单模板，这一项为 null，面板上也没有表单编辑区。
   */
  formTemplateVersionId?: number | null
  /** 已绑定表单版本的字段清单（快照：读的是绑定的那一版，不是模板最新草稿） */
  formSchema?: FormSchema | null
  /** 表单模板 id（改表单要走「模板改草稿 → 发布新版本」，需要它） */
  formTemplateId?: number | null
}

/**
 * 故障报修「处理流程」配置（V50）—— 与后端 `FaultRepairHandlerConfigVO` 逐字对应。
 *
 * 三个下拉的候选都由后端算好下发（部门 / 角色），页面不再自己去拉 ——
 * 「部门下拉里该出现哪些部门」这种口径散到前端，迟早和员工管理页对不上。
 *
 * 注意**没有人员候选**：「指定人员」这一档刻意不下发整份名册（本系统按规格书要支撑 500 人），
 * 页面改用带搜索与分页的 `UserSelectDialog`，这里只回传当前选中人的姓名供回显。
 */
export interface BuiltInHandlerConfig {
  /** 当前通知方式：DEPARTMENT / ROLE / USER */
  assigneeType: FaultAssigneeTypeCode
  /** 当前**配置**的目标值原样（按部门且未指定时为空串）—— 回显用 */
  assigneeValue: string
  /** 实际生效的目标值（按部门未指定时 = 最终处理部门的 id） */
  effectiveValue?: string | null
  /** 实际生效目标的显示名 */
  effectiveLabel?: string | null
  /** 生效目标是否来自「默认」（未指定 → 跟随最终处理部门） */
  usingDefault: boolean
  /** 按当前配置**实际会通知到的人数**；为 0 说明这条配置等于没人负责 */
  resolvedCount: number
  /** 「指定人员」档位下当前选中人的姓名 */
  userDisplayName?: string | null
  typeOptions: BuiltInHandlerOption[]
  departmentOptions: BuiltInHandlerOption[]
  roleOptions: BuiltInHandlerOption[]
}

/** 处理流程的下拉项 */
export interface BuiltInHandlerOption {
  value: string
  label: string
  /** 一句解释（通知方式档位用；部门 / 角色为 null） */
  description?: string | null
}

/** 报修通知方式：按部门 / 按角色 / 指定人员 */
export type FaultAssigneeTypeCode = 'DEPARTMENT' | 'ROLE' | 'USER'

/** 保存内置类型配置：说明 + 待绑定的已发布流程版本 + 报修处理流程（V50） */
export interface BuiltInApplyConfigPayload {
  description?: string | null
  approvalFlowVersionId?: number | null
  /**
   * 报修通知方式（V50）。**不传 = 本次不改动处理流程** ——
   * 同一个接口也服务「借用设备」，它的保存请求里永远不会出现这两项。
   */
  assigneeType?: FaultAssigneeTypeCode | null
  /** 报修通知目标值：部门 id / 角色码 / 用户 id（含义随 `assigneeType` 变） */
  assigneeValue?: string | null
}

/** 提交页卡片（极轻，仅展示所需字段） */
export interface ApplyTypeOption {
  id: number
  typeCode: string
  typeName: string
  icon?: string | null
  description?: string | null
  approvalMode: ApprovalModeCode
  approvalModeLabel?: string | null
}

/** 新建 / 修改申请类型请求 */
export interface ApplyTypePayload {
  typeCode: string
  typeName: string
  icon?: string | null
  description?: string | null
  sortOrder?: number | null
  formTemplateVersionId: number
  orderPrefix?: string | null
  approvalMode: ApprovalModeCode
  /** approvalMode = FLOW 时必填：绑定的已发布审批流程版本 id */
  approvalFlowVersionId?: number | null
  submitPermissionType: SubmitPermissionTypeCode
  /** ROLE = 角色码列表；GROUP = 分组 id 字符串列表；ALL 忽略 */
  submitPermissionValues?: string[] | null
}

/** 状态标签样式 */
export function applyTypeStatusTagType(status: ApplyTypeStatusCode): 'success' | 'info' {
  return status === 'ENABLED' ? 'success' : 'info'
}

/** 审批方式标签样式：需审批=警示（提示用户还要等人批），免审批=信息 */
export function approvalModeTagType(mode: ApprovalModeCode): 'warning' | 'info' {
  return mode === 'NONE' ? 'info' : 'warning'
}

// ---------------------------------------------------------------------
// 一步创建
// ---------------------------------------------------------------------

/**
 * 「新建申请」三步向导提交体 —— 与后端 `ApplyConfigCreateRequest` 逐字对应。
 *
 * <h2>为什么一次提交整份「表单 + 流程」</h2>
 * 改造前要新建一个申请类型得走三段（建表单模板 → 发布版本 → 画流程 → 发布版本 → 再建类型）。
 * 说明：「不用先建表单版本再关联，一步到位」。服务端在**一个事务**里建齐
 * 「表单模板 + 首发版本 + 流程 + 首发版本 + 类型」，中途失败整体回滚。
 *
 * <h2>刻意不出现的字段</h2>
 * `typeCode`（编码）与各类版本号都**不在界面上暴露**（需求：「编码、版本号这些技术细节藏起来」）。
 * `typeCode` 保留在类型里只是为了将来可能需要指定稳定编码的场景（预置播种在服务端做，不经此接口）。
 */
export interface ApplyConfigCreatePayload {
  /** 申请名称（第一步填；表单与流程的名称由它派生） */
  typeName: string
  /** 图标名（Element Plus 图标组件名）；可空，界面用默认图标 */
  icon?: string | null
  /** 说明（展示在提交页卡片上） */
  description?: string | null
  /** 排序号（决定提交页卡片顺序）；为空按 100 处理 */
  sortOrder?: number | null
  /** 工单编号前缀；留空用系统默认规则（属「技术细节」，界面上不暴露） */
  orderPrefix?: string | null
  /** 谁能提交：ROLE / GROUP / ALL；为空按 ALL */
  submitPermissionType?: SubmitPermissionTypeCode | null
  /** 提交权限值：ROLE = 角色码列表；GROUP = 部门 id 的字符串列表；ALL 忽略 */
  submitPermissionValues?: string[] | null
  /** 表单字段（第二步配的） */
  formSchema: FormSchema
  /** 审批流程定义（第三步配的；由钉钉式线性编辑器产出标准 FlowDefinition） */
  flow: FlowDefinition
}
