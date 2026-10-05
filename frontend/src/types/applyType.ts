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
