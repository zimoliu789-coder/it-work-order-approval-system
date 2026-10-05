/**
 * 员工相关类型（ /  各类「选择员工」场景）
 */

import type { AccountAuthType } from '@/types/ad'
import type { RoleCode } from '@/types/api'

/** 员工下拉选项 */
export interface UserOption {
  id: number
  username: string
  displayName: string
  role: RoleCode
  /** 当前所属部门ID，未分配为 null */
  departmentId: number | null
  /** 当前所属部门名称 */
  departmentName: string | null
  dimission: boolean
  enabled: boolean
  /** 是否可作为审批人/执行人（在职且启用） */
  available: boolean
}

// ------------------------------------------------------------------
// ：轻量版员工管理（列表 + 离职标记 / 恢复在职，）
// ------------------------------------------------------------------

/** 员工账号列表项 */
export interface UserAccount {
  id: number
  /** 真实姓名（新增字段，列表「姓名」列使用，与 displayName 展示名区分） */
  realName?: string | null
  displayName: string
  username: string
  departmentId: number | null
  departmentName: string | null
  role: RoleCode
  roleLabel: string
  /**
   * 该账号角色是否被锁定（2026-09-20 ）。
   *
   * `true` 仅出现在内置超级管理员账号（登录名固定 `administrator`）：其角色恒为
   * `super_admin`，不可被任何人降级。前端据此把编辑弹窗里的角色下拉置灰 ——
   * 与后端 `USER_SUPER_ADMIN_PROTECTED` 护栏一一对应，后端才是拒绝的事实源。
   */
  roleLocked?: boolean
  enabled: boolean
  dimission: boolean
  dimissionAt?: string | null
  /** 名下处于「使用中」的设备数量（= 待回收数量） */
  heldDeviceCount: number
  /** 名下仍待其审批的在途节点数量（离职后不会自动改派，需人工处理） */
  pendingApprovalCount: number
  /**
   * 直属领导 user_id（；`null` = 未配置）。
   *
   * 它同时是「申请人直属领导」审批规则的数据来源：配了 LEADER 规则的流程节点，
   * 提交时会去解析申请人的这一项。未配置 / 领导已离职 / 停用 / 就是本人时，
   * 该节点由超级管理员兜底并通知申请人、超管与管理员去维护。
   */
  leaderId?: number | null
  /**
   * 直属领导显示名。
   *
   * 与 {@link leaderId} 组合表达三种状态，前端展示时必须区分：
   * `leaderId` 为空 → 未配置；`leaderId` 有值但此处为空 → 领导账号已不存在；
   * 两者都有值 → 正常。
   */
  leaderName?: string | null
  /**
   * 账号来源（ ）：`LOCAL` 本地 / `LDAP` AD 域账号。
   *
   * 同时是一处**行为开关**：`LDAP` 时「重置密码」必须置灰并提示
   * 「域账号请在 AD 域控中修改密码」—— 域账号的本地 password_hash 是随机占位串，
   * 本地重置出来的口令不会被用作登录凭据，放行只会造成「以为重置了其实没用」。
   */
  authType: AccountAuthType
  /** 账号来源中文标签（本地 / AD），由后端下发，前端不再维护一份映射 */
  authTypeLabel: string
  /**
   * 手机号（V26）。
   *
   * 与 {@link email} 一起构成「找回密码」的投递通道，也是首次登录绑定引导的判据：
   * 两者都为空、且系统至少启用一个验证渠道时，后端会下发 `requireContactBinding=true`。
   * 全局唯一（数据库唯一索引 `uk_users_phone`）。
   */
  phone?: string | null
  /** 邮箱（AD 同步写入或本地填写）。V26 起补了唯一索引 `uk_users_email` —— 全局唯一 */
  email?: string | null
  /** 部门（AD 同步写入或本地填写） */
  department?: string | null
  /** 最近一次从 AD 同步确认该账号存在的时间（仅 AD 账号有值） */
  adSyncedAt?: string | null
  createdAt: string
}

/** 新增员工请求体（仅 super_admin） */
export interface UserCreatePayload {
  realName: string
  username: string
  password: string
  departmentId: number | null
  role: RoleCode
  displayName: string
  /** 直属领导 user_id（，选填）；服务端校验：必须是在职启用用户且不能是本人 */
  leaderId?: number | null
  /**
   * 手机号 / 邮箱（V26，选填）。
   *
   * 两者都填也可以，都留空则该员工首次登录时会被引导自行绑定。
   * 格式、全局唯一与「一个号码只能绑一个账号」都由服务端校验。
   */
  phone?: string | null
  email?: string | null
}

/**
 * 编辑员工请求体（仅 super_admin）
 *
 * 注意：登录名(username)后端 DTO 不含该字段，故此处不接收也不允许改登录名，
 * 前端编辑时登录名只读展示。
 */
export interface UserUpdatePayload {
  realName: string
  departmentId: number | null
  role: RoleCode
  displayName: string
  /** 直属领导 user_id（，选填）。传 null 表示清空 */
  leaderId?: number | null
  /**
   * 手机号 / 邮箱（V26，选填）。
   *
   * **仅内置超级管理员（administrator）提交时才会生效**：其他角色即使传了值，
   * 服务端也会静默忽略（）。前端把输入框置灰，是为了让「改不了」
   * 在界面上就看得见，而不是保存之后才发现没生效。
   * 传空 = 保持原值（不提供「清空」语义）。
   */
  phone?: string | null
  email?: string | null
}

/** 员工账号查询条件 */
export interface UserAccountQuery {
  page?: number
  size?: number
  /** 姓名 / 登录名 模糊匹配 */
  keyword?: string
  /** null=全部、false=在职、true=离职 */
  dimission?: boolean | null
  departmentId?: number | null
  role?: RoleCode | null
  /** 账号来源筛选（ ）：null / undefined = 全部 */
  authType?: AccountAuthType | null
}

/** 标记离职 / 恢复在职的处理结果 */
export interface DimissionResult {
  userId: number
  displayName: string
  /** 因离职被自动转入「待收回」的工单数量 */
  orderCount: number
  /** 被回收的设备数量 */
  deviceCount: number
  /** 被转入「待收回」的工单编号 */
  orderNos: string[]
  /** 仍待其审批的在途节点数量 */
  pendingApprovalCount: number
  /** 一句话结果说明 */
  message: string
}

/** 角色标签（列表展示用；与后端 roleLabel 保持一致） */
export function roleLabel(role: string): string {
  // 未登记的角色码原样回显，而不是回落成「普通员工」——
  // 回落会把「前后端角色清单不同步」这个真 bug 伪装成正常显示。
  return ROLE_LABELS[role as RoleCode] ?? role
}

/**
 * 角色码 → 中文名（ 由 3 个扩到 6 个）。
 *
 * 做成一张表而不是一长串 if：角色会继续增加，if 链漏一个分支的后果是
 * **静默**回落到「普通员工」——界面上看不出错，但筛选与展示全错位。
 * 表结构下漏配会由类型检查拦住（`Record<RoleCode, string>`）。
 */
export const ROLE_LABELS: Record<RoleCode, string> = {
  super_admin: '超级管理员',
  admin: '管理员',
  it_manager: 'IT主管',
  it_executor: 'IT执行人',
  dept_manager: '部门经理/组长',
  user: '普通员工'
}

/**
 * 角色筛选 / 下拉选项（顺序即界面展示顺序：平台管理 → 业务角色 → 普通员工）。
 *
 * 由 {@link ROLE_LABELS} 派生而不是再抄一份：两份清单一定会漂移，
 * 而漂移的表现是「筛选下拉里少一个角色」——不报错，只是永远筛不出人。
 */
export const ROLE_OPTIONS: Array<{ value: RoleCode; label: string }> = (
  ['super_admin', 'admin', 'it_manager', 'it_executor', 'dept_manager', 'user'] as RoleCode[]
).map((value) => ({ value, label: ROLE_LABELS[value] }))

// ------------------------------------------------------------------
// 员工批量导入（三步向导， 完整员工管理）
// ------------------------------------------------------------------

/** 导入执行的一行（仅含写入字段） */
export interface UserImportRow {
  rowNo: number
  realName: string
  username: string
  password: string
  departmentName: string
  role: RoleCode
  displayName: string
  /**
   * 直属领导姓名（，选填）。
   *
   * 与「部门名称」同样的约定：**填名称按名匹配**，避免让用户去查 user_id。
   * 匹配不到（不存在 / 已离职 / 已停用 / 就是本人）时该行校验失败并给出明确原因，
   * 不会静默丢弃 —— 静默丢弃会让人以为领导已配好，直到工单审批时才发现落到了超管兜底。
   */
  leaderName?: string | null
}

/** 导入预览的一行（在 {@link UserImportRow} 基础上附带校验结论） */
export interface UserImportRowVO extends UserImportRow {
  /** 该行是否通过校验（可导入） */
  valid: boolean
  /** 失败原因（valid=false 时有值），如「姓名已存在：张三」 */
  reason?: string | null
}

/** 导入预览结果（上传解析后、写入前返回） */
export interface UserImportPreview {
  fileName: string
  totalCount: number
  successCount: number
  failCount: number
  rows: UserImportRowVO[]
}

/** 导入执行结果（仅导入通过行，失败行单独返回供下钻） */
export interface UserImportResult {
  importedCount: number
  failedCount: number
  failures: UserImportRowVO[]
}

/** 确认导入请求体 */
export interface UserImportExecutePayload {
  fileName: string
  rows: UserImportRow[]
}

/** 下载失败明细请求体 */
export interface UserImportFailureReportPayload {
  fileName: string
  rows: UserImportRowVO[]
}

/**
 * 管理员重置密码的结果（2026-09-20 ）
 *
 * `temporaryPassword` 由服务端生成，**只在这一次响应里出现**：管理员必须在
 * 弹出的结果框里把它复制走并转交该员工（旧口令已失效、新口令无人知晓 = 把人锁在门外）。
 * 事后没有任何接口能再查到它，数据库里存的只是 Argon2id 哈希。
 */
export interface UserPasswordResetVO {
  temporaryPassword: string
}
