/**
 * AD 域控对接类型（； / 一.2 / 一.4；同步与手机号映射并入 ）
 *
 * 与后端 `com.enterprise.ticket.module.ad.dto` 下的 DTO 一一对应。
 *
 *  的两处变化（需求文档 五·「系统自动处理」）：
 *  - `baseDn` / `bindDn` 不再要求维护人员填写：主表单只填「域服务器地址 + 域管理员账号」，
 *    由后端 `AdDnResolver` 推导；`derive-dn` 端点提供实时预览；
 *  - `syncEnabled` / `syncHour` 从系统参数搬到这里，`attrPhone` 是新增的属性映射。
 */
/** 账号来源：本地 / AD 域账号 */
export type AccountAuthType = 'LOCAL' | 'LDAP'

/** AD 配置视图（GET /api/ad/config） */
export interface AdConfigVO {
  enabled: boolean
  serverUrls: string
  serverPort: number
  useSsl: boolean
  strictCert: boolean
  baseDn: string | null
  bindDn: string | null
  /** 恒为 `****`（未配置时为空串）；提交时留空表示保持原值 */
  bindPassword: string
  bindPasswordConfigured: boolean
  userFilter: string
  attrLogin: string
  attrName: string
  attrEmail: string
  /** 手机号属性映射；空串表示不同步手机号 */
  attrPhone: string | null
  attrDept: string
  attrStatus: string
  defaultRole: string
  connectTimeoutSeconds: number
  /** 是否启用每日自动同步（；原系统参数 ad_sync_enabled） */
  syncEnabled: boolean
  /** 每日自动同步时刻 0-23（；原系统参数 ad_sync_hour），默认 2 */
  syncHour: number
  lastTestAt?: string | null
  lastTestResult?: string | null
  lastSyncAt?: string | null
  lastSyncResult?: string | null
  /** 关闭证书校验时的醒目警告；严格模式为 null */
  securityWarning?: string | null
}

/** AD 配置保存请求（PUT /api/ad/config） */
export interface AdConfigPayload {
  enabled: boolean
  serverUrls: string
  serverPort: number
  useSsl: boolean
  strictCert: boolean
  /**
   * 基础 DN。**主表单流程请留空**（交给后端从域地址推导）；
   * 只有在「高级选项 → 自定义基础 DN」里显式填写时才传值 —— 填了就不会被推导覆盖。
   */
  baseDn?: string | null
  /**
   * 绑定账号。可以填三种写法，后端统一归一成完整 DN：
   * 完整 DN / 下行式（`company\query`）/ UPN（`query@company.com`）。
   */
  bindDn?: string | null
  /** 留空 = 保持原密码不变 */
  bindPassword?: string
  userFilter: string
  attrLogin: string
  attrName: string
  attrEmail: string
  attrPhone?: string | null
  attrDept: string
  attrStatus: string
  defaultRole: string
  connectTimeoutSeconds: number
  syncEnabled: boolean
  syncHour: number
}

/** DN 推导预览请求（POST /api/ad/derive-dn） */
export interface AdDnPreviewPayload {
  serverUrls: string
  baseDn?: string | null
  bindDn?: string | null
}

/** DN 推导预览结果（POST /api/ad/derive-dn）—— 与保存流程共用同一份规则 */
export interface AdDnPreviewVO {
  /** 推导后的基础 DN；为 null 表示推不出来（IP 域地址），需到高级选项手工填 */
  baseDn?: string | null
  /** 归一后的绑定身份 */
  bindDn?: string | null
  /** 基础 DN 是否来自自动推导 */
  baseDnAuto: boolean
  /** 绑定账号是否被改写过 */
  bindDnAuto: boolean
  /** 面向维护人员的一句话说明，直接展示即可 */
  hint: string
}

/** 测试连接结果（POST /api/ad/test-connection） */
export interface AdTestResultVO {
  ok: boolean
  message: string
  host?: string | null
  userCount?: number | null
  elapsedMs?: number | null
  /** 是否处于「跳过证书校验」的不安全模式 */
  insecure: boolean
}

/** 同步结果（POST /api/ad/sync） */
export interface AdSyncResultVO {
  total: number
  created: number
  updated: number
  disabled: number
  unchanged: number
  failed: number
  startedAt?: string | null
  finishedAt?: string | null
  message: string
  /** 失败明细（最多 20 条） */
  failures: string[]
  /** 是否真的执行了同步（false 表示被开关 / 幂等判据跳过） */
  executed: boolean
}

/** 账号来源互转结果（POST /api/ad/accounts/{id}/convert-to-*） */
export interface AdAccountConvertVO {
  userId: number
  username: string
  displayName: string
  authType: AccountAuthType
  authTypeLabel: string
  /** 仅「AD → 本地」时非空，且只返回这一次 */
  temporaryPassword?: string | null
  message: string
}

/** 账号来源筛选下拉选项 */
export const AUTH_TYPE_OPTIONS: ReadonlyArray<{ value: AccountAuthType; label: string }> = [
  { value: 'LOCAL', label: '本地账号' },
  { value: 'LDAP', label: 'AD 域账号' }
]

/** 每日同步时刻下拉选项（0-23 整点） */
export const SYNC_HOUR_OPTIONS: ReadonlyArray<{ value: number; label: string }> = Array.from(
  { length: 24 },
  (_, hour) => ({ value: hour, label: `${String(hour).padStart(2, '0')}:00` })
)

/** 主表单默认值 —— 「系统自动处理」的那些项（端口 / SSL / 属性映射）都用这里的常量 */
export const AD_DEFAULTS = {
  port: 389,
  ldapsPort: 636,
  timeoutSeconds: 5,
  syncHour: 2,
  attrLogin: 'sAMAccountName',
  attrName: 'displayName',
  attrEmail: 'mail',
  attrPhone: 'telephoneNumber',
  attrDept: 'department',
  attrStatus: 'userAccountControl'
} as const
