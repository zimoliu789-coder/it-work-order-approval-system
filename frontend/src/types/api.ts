/**
 * 后端接口数据类型（与后端 com.enterprise.ticket.common.api 保持一致）
 *  统一响应格式
 */

/** 统一响应体 */
export interface ApiResponse<T = unknown> {
  code: string
  message: string
  data: T
  traceId: string
}

/** 统一分页结构 */
export interface PageResult<T> {
  records: T[]
  total: number
  current: number
  size: number
  pages: number
}

/**
 * 批量操作的逐条结果（P3 批量操作，对应后端 `BatchResultVO`）
 *
 * 为什么不是只有「成功 / 失败」两个字段：批量改 100 台设备时，用户要知道的是
 * **哪几台失败、为什么** —— 否则只能一台台去试。`failures` 里的 id 还用于
 * **保留失败项勾选**，让用户只重试那几条。
 */
export interface BatchResult {
  /** 去重后的总条目数 */
  total: number
  succeeded: number
  failed: number
  failures: BatchFailure[]
}

export interface BatchFailure {
  id: number
  /** 展示名：设备用资产编号，员工用「姓名（登录名）」；记录已不存在时回落 `id=<n>` */
  name: string
  reason: string
}

/** 角色编码 */
/**
 * 内置角色码（ 由 3 个扩到 6 个）。
 *
 * 与服务端 `common/constant/RoleCode` 逐字对应，新增角色必须两边同步 ——
 * 前端少一个，该角色的用户会在「角色筛选」「角色下拉」里彻底消失；
 * 多一个则出现后端根本不认识、选了必然报错的选项。
 *
 * - `super_admin` 超级管理员：全量权限（后端短路）；
 * - `admin` 管理员：日常运维与全局查看；
 * - `it_manager` IT主管：审批「发设备」环节并指定具体执行人；
 * - `it_executor` IT执行人：实际发放 / 回收设备；
 * - `dept_manager` 部门经理 / 组长：本部门员工的一级审批人；
 * - `user` 普通员工：提交申请、查看自己的工单。
 */
export type RoleCode = 'super_admin' | 'admin' | 'it_manager' | 'it_executor' | 'dept_manager' | 'user'

/** 认证来源 */
export type AuthType = 'LOCAL' | 'LDAP'

/** 当前登录用户信息 */
export interface LoginUserInfo {
  id: number
  username: string
  displayName: string
  role: RoleCode
  authType: AuthType
  departmentId: number | null
  departmentName: string | null
  forceChangePassword: boolean
  /** 角色是否为 super_admin */
  superAdmin: boolean
  /**
   * 是否为「内置超级管理员」（登录名 = 配置的 app.super-admin.username，默认 administrator）。
   *
   * 与 superAdmin 的区别：其他超管（如演示账号张伟）superAdmin=true 而 builtInAdmin=false。
   * 员工管理页「重置其他超管口令」与系统参数页「站点品牌可改」都依赖这个区分 ——
   * 只看 superAdmin 会让其他超管看到本不该有的入口。
   */
  builtInAdmin: boolean
  adminOrAbove: boolean
  /**
   * 手机号（V26）。`null` / 空 = 未绑定。
   *
   * 登录响应即带上它，是为了让「首次绑定引导」与「个人中心」直接回填当前值，
   * 不必再发一次请求（也就不会出现两处显示不一致的中间态）。
   */
  phone?: string | null
  /** 邮箱（V26）。语义同 phone */
  email?: string | null
  /**
   * 是否需要强制绑定联系方式（）。
   *
   * 为 `true` 表示手机号与邮箱**都为空**、且系统至少启用了一个验证渠道 ——
   * 此时前端必须弹绑定引导页，不绑定不能进入系统。
   * 两个验证开关都关时后端会返回 `false`（用户根本无法完成绑定，否则会被堵在门外）。
   */
  requireContactBinding?: boolean
  /**
   * 当前用户被授予的权限码列表（需求方三波·第一波· 角色与权限管理）。
   *
   * 由后端 `/auth/me` 下发（super_admin 为全量码）。前端据此动态渲染菜单与按钮；
   * 后端仍会对每个接口独立校验（：前端隐藏只是 UI 体验）。
   * 字段可能为空数组（老会话/后端降级），此时前端回退到「按角色」渲染。
   */
  permissions?: string[]
  /** 数据权限范围：ALL 全部 / GROUP 本部门 / SELF 仅本人 */
  dataScope?: 'ALL' | 'GROUP' | 'SELF'
}

/** 登录请求。`username` 现在同时接受「5 位以上纯数字登录名」与「中文姓名」 */
export interface LoginRequest {
  username: string
  password: string
}

/** 修改密码请求 */
export interface ChangePasswordRequest {
  oldPassword: string
  newPassword: string
  confirmPassword: string
}

/** 登录页展示的服务端返回的 CSRF 请求头规范 */
export interface CsrfHeaderInfo {
  headerName: string
  headerValue: string
}

/** 健康检查返回 */
export interface HealthInfo {
  status: string
  application: string
  profile: string
  time: string
  traceId: string
}

// ------------------------------------------------------------------
// 找回密码 / 联系方式绑定（上线前需求 二 / 三 / 五）
// ------------------------------------------------------------------

/** 验证渠道编码（与后端 ContactRecovery.CONTACT_* 逐字对应） */
export type ContactType = 'SMS' | 'EMAIL'

/**
 * 找回密码功能元信息（免鉴权，登录页据此决定是否显示「无法登录？」入口）。
 *
 * `enabled=false` 表示手机与邮箱两个验证开关都被关掉 ——
 * 此时没有任何渠道能下发验证码，找回密码整体不可用，入口必须隐藏。
 */
export interface ForgotPasswordMeta {
  enabled: boolean
  /** 当前**真正可用**的渠道（已按「管理员开关 + 发送链路是否就绪」双重过滤） */
  channels: ContactType[]
  /** 验证码位数（管理员可配，默认 6） */
  codeLength: number
  /**
   * 不可用渠道 → 原因（键为渠道编码 `SMS` / `EMAIL`，值为服务端生成的中文原因）。
   *
   * 只包含**不可用**的渠道，可用渠道不出现在本对象里。
   * 三种成因（管理员关闭 / 短信网关未接入 / SMTP 未配置完成）的文案全部由服务端给出，
   * 前端只负责展示 —— 这样后端将来新增第四种成因时界面会自动说对话，
   * 而不是继续显示一句已经过时的旧文案。
   */
  channelDisabledReasons: Record<string, string>
}

/**
 * 某账号可用的接收渠道（第一步之后返回）。
 *
 * `channels` 已经同时过滤掉「渠道被管理员关闭」与「账号未绑定该联系方式」两种不可用，
 * 前端直接渲染即可 —— 不会出现「页面给了手机选项、后端却拒绝发送」的自相矛盾。
 */
export interface ForgotPasswordChannels {
  channels: ContactType[]
  /** 打码后的手机号，如 138****8888（未绑定时为空） */
  maskedPhone?: string | null
  /** 打码后的邮箱，如 a***@qq.com（未绑定时为空） */
  maskedEmail?: string | null
  /** 账号显示名（用于回显「正在为谁重置」） */
  displayName?: string | null
  codeLength: number
  codeExpireMinutes: number
}

/** 发送验证码请求 */
export interface ForgotPasswordSendCodeRequest {
  /** 登录名（数字账号）/ 姓名 / 手机号 / 邮箱，由后端自动识别 */
  account: string
  contactType: ContactType
}

/** 发送验证码结果 */
export interface ForgotPasswordSendCodeResult {
  contactType: ContactType
  /** 打码后的投递目标 */
  maskedTarget: string
  expireMinutes: number
  /**
   * 仅开发环境返回（方便联调）：生产环境该字段不存在。
   * 后端判据是 Spring profile，而不是可热改的系统参数 —— 后者等于
   * 「拿到配置权限就能把全站变成无需验证码即可改密」。
   */
  devCode?: string | null
}

/** 重置密码请求 */
export interface ForgotPasswordResetRequest {
  account: string
  code: string
  newPassword: string
}

/**
 * 绑定 / 修改自己的联系方式（ / 四.3）。
 *
 * 两个字段都可选，但至少填一个；「传空 = 保持原值」（不提供清空语义）。
 */
export interface BindContactRequest {
  phone?: string | null
  email?: string | null
  /**
   * 手机渠道验证码。仅当 `phone` 相对库中值发生变化时必填 ——
   * 把已绑手机号原样回传不应被要求再验证一次。
   */
  phoneCode?: string | null
  /** 邮箱渠道验证码；语义同 `phoneCode` */
  emailCode?: string | null
}

/**
 * 发送绑定验证码请求。
 *
 * `target` 是「**想绑定**的那个号码」，不是「已绑定的号码」——
 * 验证码发到新号码上，用于证明该号码在当前用户手上。
 */
export interface BindContactSendCodeRequest {
  contactType: ContactType
  target: string
}

/** 发送绑定验证码结果（结构与找回密码一致） */
export interface BindContactSendCodeResult {
  contactType: ContactType
  /** 打码后的投递目标，如 138****1111 */
  maskedTarget: string
  expireMinutes: number
  /** 仅开发环境返回；生产环境该字段不存在 */
  devCode?: string | null
}
