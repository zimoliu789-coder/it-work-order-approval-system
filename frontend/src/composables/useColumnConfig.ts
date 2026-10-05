import { computed, ref, type ComputedRef, type Ref } from 'vue'

/**
 * 列表「列自定义」偏好（-B）
 *
 * 目标：让用户自行决定「哪些列显示、以什么顺序显示」，偏好按**页面**隔离并持久化。
 *
 * 存储选择：`localStorage`（键 `ticket:cols:<routePath>`）。
 * - 零后端改动、零新表；列偏好属于**展示层个人设置**，不参与任何业务判定，
 *   丢了只是回到默认列，因此不配服务端存储；
 * - 与项目既有取向一致（申请页草稿缓存同样是 localStorage）；
 * - 后续若要平滑升级到服务端存储，**只需替换本文件的读写实现** —— 对外接口已按
 *   「异步 + 失败回落」设计（`load()` / `apply()` 均返回 Promise，且永不抛错）。
 *
 * 三条硬约束（评审关注点）：
 * 1. **首次访问必须与改造前一致**：localStorage 无偏好时，默认列 = 代码里声明的列，
 *    顺序与宽度照抄，不多不少；
 * 2. **`configurable === false` 的列（如「操作」列）永不可隐藏**：用户把操作列关掉
 *    等于「页面坏了」，这类列既不出现在配置弹窗的可取消项里，也不接受被隐藏的偏好；
 * 3. **容错优先于正确性**：JSON 解析失败 / 版本不认识 / 结构不认识 / 含已废弃列 key
 *    → 一律回落默认，**绝不抛错**（一个坏掉的偏好不能让整个列表打不开）。
 */

/**
 * 偏好结构版本号。
 *
 * 结构一旦不兼容就整体回落默认 —— 这比写迁移代码便宜得多，也不会有「半新半旧」的中间态。
 * 只在**字段语义变化**时递增；纯新增列不需要（新增列会被自动补进 order）。
 */
export const COLUMN_CONFIG_VERSION = 1

/** 列定义中「偏好层」关心的最小信息（渲染相关字段由 TablePage 的 ColumnDef 扩展） */
export interface ColumnConfigDef {
  /** 稳定列标识（= 单元格插槽名后缀，见 TablePage 的 `#cell-<key>`） */
  key: string
  /** 配置弹窗里展示的名字 */
  label: string
  /**
   * 是否参与列配置。
   * - `false`：不出现在弹窗、且**永不可被隐藏**（操作列等）；
   * - 缺省 `true`：可勾选显隐、可拖拽排序。
   */
  configurable?: boolean
  /** 默认是否可见（缺省 `true`）。`configurable === false` 时忽略该字段，恒可见 */
  defaultVisible?: boolean
}

/** 落盘结构 */
export interface ColumnConfigSnapshot {
  version: number
  /** **完整**列顺序（含被隐藏的列）—— 隐藏只是「不渲染」，顺序信息必须保留 */
  order: string[]
  /** 被隐藏的列 key */
  hidden: string[]
}

/** 配置弹窗用的行数据 */
export interface ColumnConfigRow {
  key: string
  label: string
  visible: boolean
  /**
   * 锁定行：恒可见、不可取消勾选、不可拖动。
   * 「操作」列属于此类 —— 被配置弹窗隐藏掉等于页面直接坏掉。
   */
  locked: boolean
  /** 默认是否可见（弹窗内「恢复默认」的判据，避免弹窗自己再算一遍） */
  defaultVisible: boolean
}

/**
 * 存储适配（只用到这两个方法）。
 * 显式声明为可注入，便于单测注入内存实现，也便于未来替换为服务端实现。
 */
export interface KeyValueStorage {
  getItem: (key: string) => string | null
  setItem: (key: string, value: string) => void
  removeItem?: (key: string) => void
}

/** 列偏好键（按页隔离） */
export function columnConfigKey(routePath: string): string {
  return `ticket:cols:${routePath}`
}

/** 默认配置：列顺序 = 定义顺序；`defaultVisible === false` 且可配置的列默认隐藏 */
export function defaultColumnConfig(defs: ColumnConfigDef[]): ColumnConfigSnapshot {
  return {
    version: COLUMN_CONFIG_VERSION,
    order: defs.map((def) => def.key),
    hidden: defs
      .filter((def) => def.configurable !== false && def.defaultVisible === false)
      .map((def) => def.key)
  }
}

/**
 * 把「读到的任意值」规范化为可用配置。
 *
 * 这是本模块唯一的容错入口，所有异常输入都在这里被吃掉：
 * - 不是对象 / `version` 不匹配 / `order`、`hidden` 不是数组 → 整体回落默认；
 * - `order` 里的未知 key、重复 key、非字符串 → 丢弃；
 * - 代码里**新增的列**（偏好里没有）→ 按定义顺序补到末尾，否则新列永远不出现；
 * - `hidden` 里的未知 key → 丢弃；`configurable === false` 的列 → 强制移出 hidden。
 */
export function normalizeColumnConfig(raw: unknown, defs: ColumnConfigDef[]): ColumnConfigSnapshot {
  const fallback = defaultColumnConfig(defs)
  if (raw === null || typeof raw !== 'object') {
    return fallback
  }
  const snapshot = raw as Partial<ColumnConfigSnapshot>
  if (snapshot.version !== COLUMN_CONFIG_VERSION) {
    return fallback
  }
  if (!Array.isArray(snapshot.order) || !Array.isArray(snapshot.hidden)) {
    return fallback
  }
  const known = new Set(defs.map((def) => def.key))
  const locked = new Set(defs.filter((def) => def.configurable === false).map((def) => def.key))

  const seen = new Set<string>()
  const order: string[] = []
  for (const key of snapshot.order) {
    if (typeof key !== 'string' || !known.has(key) || seen.has(key)) {
      continue
    }
    seen.add(key)
    order.push(key)
  }
  // 代码新增、偏好里还没有的列：按定义顺序补到末尾
  for (const def of defs) {
    if (!seen.has(def.key)) {
      order.push(def.key)
    }
  }

  const hiddenSeen = new Set<string>()
  const hidden: string[] = []
  for (const key of snapshot.hidden) {
    if (typeof key !== 'string' || !known.has(key) || locked.has(key) || hiddenSeen.has(key)) {
      continue
    }
    hiddenSeen.add(key)
    hidden.push(key)
  }

  return { version: COLUMN_CONFIG_VERSION, order, hidden }
}

/** 取默认存储；不可用时（SSR / 隐私模式 / 单测未注入）返回 null，调用方自行回落 */
function defaultStorage(): KeyValueStorage | null {
  try {
    if (typeof window === 'undefined' || !window.localStorage) {
      return null
    }
    return window.localStorage
  } catch {
    return null
  }
}

export interface UseColumnConfigOptions {
  /** 路由路径，用于生成隔离键（`ticket:cols:<routePath>`） */
  routePath: string
  /**
   * 列定义（**代码为事实源**）。用函数而非数组，是为了让「列集合随权限/业务变化」
   * 的页面（如员工管理按角色增减列）在渲染时总能拿到最新定义。
   */
  columns: () => ColumnConfigDef[]
  /** 可注入存储（单测用）；缺省用 `localStorage` */
  storage?: KeyValueStorage | null
}

export interface UseColumnConfigReturn {
  /** 完整列顺序（含隐藏列） */
  order: Ref<string[]>
  /** 隐藏列 key 集合 */
  hidden: Ref<string[]>
  /** 可配置列（弹窗数据源），顺序与 `order` 一致 */
  configRows: ComputedRef<ColumnConfigRow[]>
  /** 当前应渲染的列 key，顺序与 `order` 一致 */
  visibleKeys: ComputedRef<string[]>
  isVisible: (key: string) => boolean
  /** 应用一次配置（弹窗确认时调用）：先规范化（防止弹窗传回非法值），再落盘 */
  apply: (next: { order: string[]; hidden: string[] }) => Promise<void>
  /** 恢复默认并落盘 */
  reset: () => Promise<void>
  /** 异步重读（挂载后可调用；失败回落默认，不抛错） */
  load: () => Promise<void>
  /** 是否与默认配置有差异（用于界面上标一个「已自定义」小点） */
  customized: ComputedRef<boolean>
}

export function useColumnConfig(options: UseColumnConfigOptions): UseColumnConfigReturn {
  const storage = options.storage === undefined ? defaultStorage() : options.storage
  const key = columnConfigKey(options.routePath)

  /**
   * 初始化时**同步**读一次。
   *
   * 为什么不在 `onMounted` 里异步读：异步读会让首帧先渲染默认列、再跳到用户列，
   * 出现肉眼可见的列闪烁。localStorage 是同步 API，同步读的成本可以忽略；
   * 对外的 `load()` 仍保留异步签名，以便未来替换为服务端实现时不改调用方。
   */
  const initial = readSnapshot()

  const order = ref<string[]>(initial.order)
  const hidden = ref<string[]>(initial.hidden)

  function defsOf(): ColumnConfigDef[] {
    return options.columns()
  }

  function readSnapshot(): ColumnConfigSnapshot {
    if (!storage) {
      return defaultColumnConfig(defsOf())
    }
    try {
      const text = storage.getItem(key)
      if (!text) {
        return defaultColumnConfig(defsOf())
      }
      return normalizeColumnConfig(JSON.parse(text) as unknown, defsOf())
    } catch {
      // 坏掉的偏好（非法 JSON 等）绝不能阻断列表渲染
      return defaultColumnConfig(defsOf())
    }
  }

  async function persist(): Promise<void> {
    if (!storage) {
      return
    }
    try {
      const snapshot: ColumnConfigSnapshot = {
        version: COLUMN_CONFIG_VERSION,
        order: order.value,
        hidden: hidden.value
      }
      storage.setItem(key, JSON.stringify(snapshot))
    } catch {
      // 配额满 / 隐私模式：静默失败，本次会话内的配置依然生效
    }
    await Promise.resolve()
  }

  const configRows = computed<ColumnConfigRow[]>(() => {
    const defs = defsOf()
    const byKey = new Map(defs.map((def) => [def.key, def]))
    const hiddenSet = new Set(hidden.value)
    const rows: ColumnConfigRow[] = []
    const push = (def: ColumnConfigDef): void => {
      const locked = def.configurable === false
      rows.push({
        key: def.key,
        label: def.label,
        visible: locked || !hiddenSet.has(def.key),
        locked,
        defaultVisible: locked || def.defaultVisible !== false
      })
    }
    for (const columnKey of order.value) {
      const def = byKey.get(columnKey)
      if (!def) {
        continue
      }
      byKey.delete(columnKey)
      push(def)
    }
    // 代码新增、（当前偏好里尚未记录）的列：追加到末尾，保证新列一定会出现
    for (const def of byKey.values()) {
      push(def)
    }
    return rows
  })

  const visibleKeys = computed<string[]>(() =>
    configRows.value.filter((row) => row.visible).map((row) => row.key)
  )

  function isVisible(columnKey: string): boolean {
    const row = configRows.value.find((item) => item.key === columnKey)
    return row ? row.visible : true
  }

  async function apply(next: { order: string[]; hidden: string[] }): Promise<void> {
    const normalized = normalizeColumnConfig({ ...next, version: COLUMN_CONFIG_VERSION }, defsOf())
    order.value = normalized.order
    hidden.value = normalized.hidden
    await persist()
  }

  async function reset(): Promise<void> {
    const fallback = defaultColumnConfig(defsOf())
    order.value = fallback.order
    hidden.value = fallback.hidden
    await persist()
  }

  async function load(): Promise<void> {
    const snapshot = readSnapshot()
    order.value = snapshot.order
    hidden.value = snapshot.hidden
    await Promise.resolve()
  }

  const customized = computed<boolean>(() => {
    const fallback = defaultColumnConfig(defsOf())
    return (
      fallback.order.join('|') !== order.value.join('|') ||
      fallback.hidden.slice().sort().join('|') !== hidden.value.slice().sort().join('|')
    )
  })

  return { order, hidden, configRows, visibleKeys, isVisible, apply, reset, load, customized }
}
