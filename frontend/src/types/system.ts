/**
 * 系统管理相关类型（操作日志 / 系统配置）
 */

/** 操作日志筛选项（值用 code，显示用 label） */
export interface LogOption {
  code: string
  label: string
}

/** 操作日志
 *
 * 后端已对字段做中文化兜底：
 * - moduleLabel / actionLabel / resultLabel / riskLabel 为后端给的中文标签；
 * - summary 为人类可读的详情摘要（默认展示），失败时已含失败原因、不含堆栈；
 * - details 为原始技术详情（类/方法/参数/耗时/错误），仅在「查看详情」里展开。
 */
export interface OperationLogItem {
  id: number
  operatorId: number | null
  operatorName: string | null
  operationTime: string
  module: string
  action: string
  // —— 后端中文化标签 ——
  moduleLabel: string
  actionLabel: string
  summary: string
  resultLabel: string
  riskLabel: string
  details: string | null
  result: 'SUCCESS' | 'FAILED'
  riskLevel: 'HIGH' | 'NORMAL'
  ip: string | null
  traceId: string | null
}

/** 系统配置项（ / ） */
export interface SystemConfigItem {
  id: number
  configKey: string
  configValue: string | null
  configGroup: string
  configDesc: string | null
  editable: boolean
}

/**
 * 站点品牌信息（：系统名称与 logo 可配置）
 *
 * 由 `GET /api/system/site-info` 下发，**免认证**（登录页也要显示名称与 logo）。
 *
 * logo 的两种形态由服务端判定后下发，前端只按 `logoType` 分支渲染：
 * 各渲染点自己判断「是不是 FILE: 开头」必然出现漏判，表现为某处把引用串当文字显示。
 */
export interface SiteInfo {
  /** 系统名称（服务端已回落默认值，永不为空） */
  siteName: string
  /**
   * 版权文字；**可能为空串**（）。
   *
   * 空串表示管理员没有填写 → 登录页与侧边栏底部不渲染这一行。
   * 服务端刻意不给它默认文案（见 SiteBranding.DEFAULT_COPYRIGHT），
   * 因此前端必须按空串判断，不能自己兜一句「© 20xx」。
   */
  copyright: string
  /** logo 形态：TEXT 文字图标 / IMAGE 上传的图片 */
  logoType: 'TEXT' | 'IMAGE'
  /** 文字 logo 内容（logoType=TEXT 时有值） */
  logoText: string | null
  /** 图片 logo 地址（logoType=IMAGE 时有值），可直接用作 img 的 src */
  logoUrl: string | null
}

/** 操作日志查询参数 */
export interface OperationLogQuery {
  page?: number
  size?: number
  module?: string
  action?: string
  operatorName?: string
  result?: string
  startTime?: string
  endTime?: string
}

// ------------------------------------------------------------------
// 系统参数目录（：卡片式配置页）
// ------------------------------------------------------------------

/**
 * 参数控件类型。
 *
 * 由服务端下发而不是前端按 key 猜：新增参数时前端不必同步改代码，
 * 也避免「同一个 key 在前端被判成文本框、后端当成开关」这类漂移。
 */
export type ConfigItemType = 'TOGGLE' | 'NUMBER' | 'TEXT' | 'PASSWORD' | 'SELECT'

/** 下拉选项 */
export interface ConfigOption {
  value: string
  label: string
}

/** 单个参数项（服务端已给出中文标签 / 单位 / 区间 / 说明） */
/**
 * 参数项在配置页网格里的跨度（由后端目录下发）。
 *
 * 前端只按它算 `grid-column`，**不在前端猜宽度** —— 一项该多宽取决于它的语义
 * （路径要长、开关要短），那是后端的知识。缺省时按 `MEDIUM` 兜底。
 */
export type ConfigItemLayout = 'SHORT' | 'MEDIUM' | 'FULL'

export interface ConfigCatalogItem {
  /** 配置键（提交时作为 values 的 key） */
  key: string
  /** 中文标签（页面显示的「名字」，不显示英文 key） */
  label: string
  type: ConfigItemType
  /** 单位后缀（如「分钟」「天」） */
  unit: string
  /** 当前值；密文项为掩码 `****` 或空串 */
  value: string
  /** 内置默认值（「恢复默认」回填用） */
  defaultValue: string
  /** 灰色小字说明 */
  description: string
  /** 当前登录者是否可改 */
  editable: boolean
  /** 是否「仅内置超管可改」 */
  adminOnly: boolean
  /** 是否密文项（留空/掩码提交 = 不修改） */
  secret: boolean
  /** 是否不允许留空 */
  required: boolean
  /** 特殊渲染提示：`logo` 表示该行用图片上传控件 */
  widget: string | null
  /** 网格跨度：SHORT=一行三个 / MEDIUM=一行两个 / FULL=整行 */
  layout: ConfigItemLayout
  /** 下拉选项（仅 SELECT） */
  options: ConfigOption[]
  /** 整数下界（仅 NUMBER） */
  min: number | null
  /** 整数上界（仅 NUMBER） */
  max: number | null
  /** 文本长度上限（仅 TEXT / PASSWORD） */
  maxLength: number | null
}

/**
 * 组内分区（= 卡片内部的一小节， 新增）。
 *
 * 把参数从「按技术模块分卡」重组成「按业务域分卡」，一张卡里于是同时装了
 * 短信通道、邮箱通道、验证码三件事。这一层承载三样**不能合并**的信息：
 * 各自的小标题与引导语、各自的参考说明与角标、以及各自的开关依赖。
 */
export interface ConfigCatalogSection {
  /** 分区编码（前端取键用，不显示在界面上） */
  code: string
  /** 小标题；`null` 表示不渲染小标题（单分区分组，如「登录与安全」） */
  label: string | null
  /** 小标题下的一句引导语 */
  description: string | null
  /** 分区角标（如「暂未启用」） */
  badge: string | null
  /** 分区底部参考说明 */
  notes: string[]
  /**
   * 受哪个开关键控制；`null` = 不受控制。
   *
   * 该键值为 `0` 时，本分区**除开关自身**外所有项都要置灰。
   * 前端置灰与后端拒绝口径必须一致（前端是体验、后端是边界）——
   * 判据见 `.docs/phase19-plan.md`：提交前后只要有一刻通道是开的就允许写入。
   */
  dependsOnKey: string | null
  /** 分区内的参数项 */
  items: ConfigCatalogItem[]
}

/** 参数分组（= 页面上的一张卡片） */
export interface ConfigCatalogGroup {
  code: string
  label: string
  /** 卡片顶部的一句通俗说明 */
  description: string
  /** 角标（ 起卡片级角标已下沉到分区，恒为 null） */
  badge: string | null
  /** 卡片底部参考说明 */
  notes: string[]
  /** 是否默认折叠（高级参数） */
  collapsed: boolean
  /** 组内分区（只负责渲染；顺序即界面顺序） */
  sections: ConfigCatalogSection[]
  /**
   * 组内全部项（服务端给出的 `sections` 展平结果）。
   *
   * 配置页除渲染外还有四件事需要「把整组当成一个项的集合」：按 key 查项、
   * 遍历校验、统计改动、一键恢复默认。这些走 `items`，免得四处各写一遍展平。
   */
  items: ConfigCatalogItem[]
}

/** 系统参数目录 */
export interface ConfigCatalog {
  groups: ConfigCatalogGroup[]
}

/** 「发送测试邮件」请求（空字段 = 沿用已保存配置；password 为掩码时同样沿用） */
export interface ConfigTestMailPayload {
  to: string
  host?: string
  port?: number
  username?: string
  password?: string
  fromName?: string
  ssl?: boolean
}

/** 「发送测试邮件」成功后的实际生效参数（供页面提示核对） */
export interface ConfigTestMailResult {
  host: string
  port: number
  ssl: boolean
}

/** 「发送测试短信」请求（ · ；空字段 = 沿用已保存配置） */
export interface ConfigTestSmsPayload {
  to: string
  provider?: string
  accessKeyId?: string
  /** 明文；为掩码 `****` 时后端同样视为「沿用已保存值」 */
  accessKeySecret?: string
  signName?: string
  templateCode?: string
}

/**
 * 「发送测试短信」结果。
 *
 * **`delivered` 必须被页面如实展示**：短信网关本期尚未接入（为预留），
 * 因此它恒为 `false`、`channel` 恒为 `'LOG'`。把它当成「发送成功」渲染，
 * 管理员就会以为配置已可用，直到有用户反馈收不到验证码。
 */
export interface ConfigTestSmsResult {
  /** 本次使用的服务商编码 */
  provider: string
  /** 服务商显示名 */
  providerLabel: string
  /** 是否真的送达了用户手机；日志通道下恒为 false */
  delivered: boolean
  /** 实际投递通道：LOG（未接入） / GATEWAY（已接入） */
  channel: 'LOG' | 'GATEWAY'
  /** 给管理员看的说明；**直接展示，不要用别的文案覆盖** */
  message: string
}
