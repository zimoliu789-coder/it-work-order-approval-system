/**
 * 主备双机热备类型定义（， ）
 *
 * <h2>三个状态码都用字符串联合类型，而不是 enum</h2>
 * <p>它们都是<b>跨进程契约</b>：同时出现在数据库列、REST 响应、以及跑在目标机器上的
 * 运维脚本（`deploy/ha/scripts/*.sh` 上报的心跳与同步状态）三处。
 * 用联合类型可以让「后端加了一个状态、前端忘了处理」这件事在 TS 的
 * `switch` 穷尽性检查里直接报错，而不是静默落进 default 分支 ——
 * 主备页面上的静默错色不会报任何错，只会让维护人员判断错机器状态。
 *
 * <h2>⚠️ null ≠ 0：「从未上报」与「延迟 0 秒」是两件事</h2>
 * <p>后端 Jackson 配置为 `non_null`，因此缺省字段<b>根本不会出现在响应里</b>。
 * 同步延迟 / 最后同步时间在「从未上报过」时是 undefined，此时界面必须显示
 * 「—」或「从未同步」，<b>绝不能</b>用 `0` 兜底 ——
 * 那会让「复制早就断了」看起来像「刚刚同步、延迟为零」。
 */

/** 与后端 `com.enterprise.ticket.common.constant.HaRole` 一一对应 */
export type HaRoleCode = 'MASTER' | 'STANDBY'

/** 与后端 `HaNodeStatus` 一一对应 */
export type HaNodeStatusCode = 'RUNNING' | 'STANDBY' | 'ABNORMAL' | 'UNKNOWN'

/** 与后端 `HaSyncState` 一一对应 */
export type HaSyncStateCode = 'IN_SYNC' | 'LAGGING' | 'FAILED' | 'UNKNOWN'

/** Element Plus 标签类型（el-tag 的 type 属性） */
export type HaTagType = 'info' | 'primary' | 'success' | 'warning' | 'danger'

/**
 * 节点状态 → 标签样式。
 *
 * `ABNORMAL` 用 danger（红）是需求 [186] 的明确要求：「页面上节点状态变红」。
 * `UNKNOWN` 刻意用 info（灰）而不是 warning（橙）：它不是「可能有问题」，
 * 而是「刚登记、还没收到过一次心跳」—— 用橙色会让维护人员一添加备节点
 * 就看到一片告警色，而那时握手本来就还没完成。
 */
export function haNodeStatusTag(status?: HaNodeStatusCode): HaTagType {
  switch (status) {
    case 'RUNNING':
      return 'success'
    case 'STANDBY':
      return 'primary'
    case 'ABNORMAL':
      return 'danger'
    default:
      return 'info'
  }
}

/**
 * 同步状态 → 标签样式。
 *
 * `LAGGING` 用 warning（橙）而不是 danger：主从复制短暂落后是常态，
 * 把它标红会让页面每分钟红几百次，最终被当作背景噪音忽略 ——
 * 那时真正的 `FAILED` 也就没人看了。
 */
export function haSyncStateTag(state?: HaSyncStateCode): HaTagType {
  switch (state) {
    case 'IN_SYNC':
      return 'success'
    case 'LAGGING':
      return 'warning'
    case 'FAILED':
      return 'danger'
    default:
      return 'info'
  }
}

/** 同步状态 → 一句人话（页面上的「数据是否一致」） */
export function haSyncStateHint(state?: HaSyncStateCode): string {
  switch (state) {
    case 'IN_SYNC':
      return '两台机器数据一致，复制正常'
    case 'LAGGING':
      return '复制仍在进行，但延迟超出容忍范围。批量写入 / 备份窗口期间短暂落后属正常'
    case 'FAILED':
      return '复制已中断，两台机器的数据可能开始不一致，需要立即处理'
    default:
      return '尚未收到同步状态上报。若已部署心跳脚本仍长期如此，请检查 INTERNAL_ALERT_TOKEN 是否两台机器一致'
  }
}

/** 主备节点（对应后端 `HaNodeVO`） */
export interface HaNodeItem {
  id: number
  /** 展示名可能为空 —— 展示层应回落到 IP */
  nodeName?: string
  nodeIp: string
  nodeRole: HaRoleCode
  nodeRoleLabel: string
  nodeStatus: HaNodeStatusCode
  nodeStatusLabel: string
  isLocal: boolean
  /** 最后心跳时间；缺省 = 尚未收到过心跳 */
  lastHeartbeatAt?: string
  /** 距最后一次心跳的秒数（服务端算好下发，避免客户端时钟不一致）；缺省 = 从未上报 */
  heartbeatAgeSeconds?: number
  remark?: string
  /** 本机节点不可移除（否则「当前节点角色」会永久失去依据） */
  removable: boolean
}

/** 主备配置明细（对应后端 `HaConfigVO`） */
export interface HaConfigDetail {
  enabled: boolean
  nodeName?: string
  nodeIp?: string
  /** 员工统一访问的域名；为空表示直连虚拟 IP */
  domain?: string
  vipWeb?: string
  vipDb?: string
  vrrpIface?: string
  heartbeatTimeoutSeconds?: number
  syncState?: HaSyncStateCode
  syncStateLabel?: string
  /** 复制延迟（秒）；缺省 = 从未上报（**不是** 0） */
  syncDelaySeconds?: number
  /** 最后同步时间；缺省 = 从未同步 */
  lastSyncAt?: string
  /** 最后切换时间；缺省 = 从未切换 */
  lastSwitchAt?: string
  /** 员工实际访问地址（域名优先）；缺省 = 尚未配置 */
  displayAddress?: string
}

/** 部署资产探针（对应后端 `HaDeploymentVO`） */
export interface HaDeploymentInfo {
  /** 是否配置了 app.ha.deploy-dir */
  haDirConfigured: boolean
  haDir?: string
  deployDirExists: boolean
  switchoverScriptPresent: boolean
  setupReplicationScriptPresent: boolean
  keepalivedConfPresent: boolean
  /** 演练模式：只返回将要执行的命令，不真正执行 */
  dryRunEnabled: boolean
  /** 服务端给的一句人话说明（为什么现在不能真执行） */
  hint: string
}

/** 页面总览（对应后端 `HaOverviewVO`，一次请求拿到同一时刻的同一份现实） */
export interface HaOverview {
  enabled: boolean
  config: HaConfigDetail
  /** 本机角色；缺省 = 尚未登记本机节点 */
  localRole?: HaRoleCode
  localRoleLabel?: string
  localStatus: HaNodeStatusCode
  localStatusLabel: string
  nodes: HaNodeItem[]
  deployment: HaDeploymentInfo
}

/** 运维动作结果（对应后端 `HaActionResultVO`） */
export interface HaActionResult {
  /** 动作是否成功；dry-run 且命令组装成功时为 true，此时须看 dryRun */
  success: boolean
  /** 是否为演练模式（未真正执行脚本）—— 界面必须明确写出来 */
  dryRun: boolean
  action: string
  /** 实际执行（或将要执行）的命令行 */
  command: string
  exitCode?: number
  output?: string
  message: string
}

/** 「三步走」指引（对应后端 `HaDeployGuideVO`） */
export interface HaDeployGuide {
  steps: string[]
  /** 可直接抄进 deploy/ha/.env.ha 的内容（口令一律 __CHANGE_ME__ 占位符） */
  envSnippet: string
  /** keepalived 配置；缺省 = 服务器上没有模板文件 */
  keepalivedConf?: string
  deployDirPresent: boolean
  note: string
}

/** 保存配置（第 1 步）—— 文本字段留空即清空 */
export interface HaConfigPayload {
  enabled: boolean
  nodeName?: string
  domain?: string
  vipWeb?: string
  vipDb?: string
  vrrpIface?: string
  heartbeatTimeoutSeconds?: number
}

/**
 * 添加备节点（第 2 步）。
 *
 * `adminPassword` 是需求 [171] 的交互字段，但后端<b>接收后立即丢弃</b>
 * （不落库 / 不转发 / 不写日志）—— 真正的握手由目标机上的
 * `setup-replication.sh` 以 root 执行完成。界面必须把这一点写清楚，
 * 不能让维护人员以为系统真的会替他登录备机。
 */
export interface HaNodeCreatePayload {
  nodeName?: string
  nodeIp: string
  adminPassword?: string
  remark?: string
}

/** 修改节点（只允许改展示名与备注；IP 与角色不可改） */
export interface HaNodeUpdatePayload {
  nodeName?: string
  remark?: string
}

/** 切换动作白名单 —— 与后端 `HaConfigServiceImpl.SWITCHOVER_ACTIONS` 逐字一致 */
export type HaSwitchoverAction = 'to-peer' | 'back' | 'status'

export const HA_SWITCHOVER_ACTIONS: readonly HaSwitchoverAction[] = ['to-peer', 'back', 'status']

export function switchoverActionLabel(action: HaSwitchoverAction): string {
  switch (action) {
    case 'to-peer':
      return '本机让出（由对端接管）'
    case 'back':
      return '切回本机'
    default:
      return '查询维护标记状态'
  }
}

/** 心跳超时默认值 / 区间 —— 与后端 `HaConfigValidator` 一致（需求文档 [174] 默认 10 秒） */
export const HA_HEARTBEAT_DEFAULT = 10
export const HA_HEARTBEAT_MIN = 3
export const HA_HEARTBEAT_MAX = 600

/**
 * 距最后心跳的秒数 → 人话。
 *
 * 服务端算好秒数再下发（而不是让前端拿时间戳自减）：前端时钟与服务端
 * 不一定一致，用本地时间做减法会把「服务器 3 分钟前的心跳」算成「59 分钟后」。
 */
export function formatHeartbeatAge(seconds?: number): string {
  if (seconds == null || seconds < 0) {
    return '从未上报'
  }
  if (seconds < 60) {
    return `${seconds} 秒前`
  }
  if (seconds < 3600) {
    return `${Math.floor(seconds / 60)} 分钟前`
  }
  if (seconds < 86400) {
    return `${Math.floor(seconds / 3600)} 小时前`
  }
  return `${Math.floor(seconds / 86400)} 天前`
}

/** 复制延迟 → 人话；缺省显示「—」而不是 0 秒 */
export function formatSyncDelay(seconds?: number): string {
  if (seconds == null || seconds < 0) {
    return '—'
  }
  if (seconds < 60) {
    return `${seconds} 秒`
  }
  return `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒`
}

/** 时间戳 → 展示文本；缺省用调用方给的兜底文案（默认「—」） */
export function formatMoment(value?: string, fallback = '—'): string {
  return value && value.trim() !== '' ? value : fallback
}
