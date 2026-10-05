/**
 * 在线升级类型定义
 *
 * <h2>为什么状态用联合类型而不是 enum</h2>
 * <p>后端 `UpgradeStatus` 的取值是<b>跨进程契约</b>：它同时出现在
 * 数据库列、REST 响应、外部编排脚本（`deploy/ha/scripts/upgrade-apply.sh` 的结果回执）
 * 三处。这里用字符串联合类型，是为了让「后端加了一个状态、前端忘了处理」这件事
 * 在 TS 的 `switch` 穷尽性检查里直接报错，而不是静默落进 default 分支。
 */

/** 与后端 `com.enterprise.ticket.common.constant.UpgradeStatus` 一一对应 */
export type UpgradeStatusCode =
  | 'PENDING'
  | 'VALIDATING'
  | 'BACKING_UP'
  | 'STAGING'
  | 'READY_TO_APPLY'
  | 'APPLYING'
  | 'SUCCESS'
  | 'FAILED'
  | 'ROLLED_BACK'

/** 活跃（进行中）状态 —— 与后端 `UpgradeStatus#isActive()` 及 V30 的 active_flag 生成列三处一致 */
export const UPGRADE_ACTIVE_STATUSES: readonly UpgradeStatusCode[] = [
  'PENDING',
  'VALIDATING',
  'BACKING_UP',
  'STAGING',
  'READY_TO_APPLY',
  'APPLYING'
]

/** 状态 → Element Plus 标签类型（用于 el-tag 的 type 属性） */
export type UpgradeTagType = 'info' | 'primary' | 'success' | 'warning' | 'danger'

export function isUpgradeActive(status?: UpgradeStatusCode): boolean {
  return status != null && UPGRADE_ACTIVE_STATUSES.includes(status)
}

/**
 * 状态 → 标签样式。
 *
 * `READY_TO_APPLY` 用 `primary`（蓝）而不是 success：它是「等你动手」，
 * 不是「已经好了」。用绿色会让管理员以为升级已经完成 —— 而此刻系统跑的仍是旧版本。
 */
export function upgradeTagType(status?: UpgradeStatusCode): UpgradeTagType {
  switch (status) {
    case 'SUCCESS':
      return 'success'
    case 'FAILED':
      return 'danger'
    case 'ROLLED_BACK':
      return 'warning'
    case 'READY_TO_APPLY':
    case 'APPLYING':
      return 'primary'
    default:
      return 'info'
  }
}

/**
 * 状态 → 一句话说明「现在该做什么」。
 *
 * 这比单纯一个状态标签有用得多：升级页的使用者往往是把系统交付给业务方的人，
 * 他看到「已就绪待应用」时真正需要知道的是**下一步该点哪里**。
 */
export function upgradeHint(task: UpgradeTaskItem, applyConfigured: boolean): string {
  switch (task.status) {
    case 'READY_TO_APPLY':
      return applyConfigured
        ? '产物已就绪，可点击「立即应用」由编排脚本完成替换与重启'
        : '产物已就绪。本环境未配置外部应用命令，需由运维手动应用 staging 目录中的产物'
    case 'APPLYING':
      return '正在应用中。后端可能随时被替换重启，页面会自动重试查询结果'
    case 'SUCCESS':
      return '升级成功，新版本已生效'
    case 'ROLLED_BACK':
      return '已回滚到升级前的版本。还原的是磁盘文件，需重启后端 / 重建容器后生效'
    case 'FAILED':
      return '升级失败，请查看失败原因。原样产物未被替换，当前系统仍运行旧版本'
    default:
      return '正在处理，请稍候…'
  }
}

/** 升级任务（对应后端 `UpgradeTaskVO`） */
export interface UpgradeTaskItem {
  taskNo: string
  packageName: string
  /** 后端 Jackson 配置为 non_null，null 字段不会出现在响应里 ⇒ 这里必须可选 */
  packageSize?: number
  packageSha256?: string
  sourceVersion?: string
  targetVersion: string
  status: UpgradeStatusCode
  statusLabel: string
  step?: string
  progress?: number
  message?: string
  operatorName?: string
  startedAt?: string
  finishedAt?: string
  createdAt?: string
  /** 部署配置是否允许回滚 */
  rollbackEnabled: boolean
  /** 本任务此刻是否可回滚（状态 + 配置两者都满足） */
  rollbackable: boolean
}

/** 升级能力总览（对应后端 `UpgradeOverviewVO`） */
export interface UpgradeOverview {
  /** 总开关（生产默认关闭） */
  enabled: boolean
  /** 当前已应用版本（读 state/current.json，首次部署为空） */
  currentVersion?: string
  /** 是否配置了外部应用命令 */
  applyCommandConfigured: boolean
  rollbackEnabled: boolean
  packageMaxSizeMb: number
  /** 进行中的任务号（无则为空） */
  activeTaskNo?: string
}

/** 升级包文件名后缀（只做提示，真正的类型判定由后端按 zip 结构完成） */
export const UPGRADE_PACKAGE_EXTENSIONS = ['.zip']

export function formatPackageSize(bytes?: number): string {
  if (bytes == null || bytes <= 0) {
    return '—'
  }
  if (bytes >= 1024 * 1024) {
    return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  }
  if (bytes >= 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`
  }
  return `${bytes} B`
}

/** 哈希展示：只显示前 12 位（完整值对排查没帮助，但会让表格无法阅读） */
export function shortSha(sha?: string): string {
  if (!sha) {
    return '—'
  }
  return sha.length <= 12 ? sha : `${sha.slice(0, 12)}…`
}
