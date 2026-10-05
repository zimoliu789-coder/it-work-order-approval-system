import { reactive, ref, type Ref } from 'vue'
import type { KeyValueStorage } from './useColumnConfig'
import { readRememberListQuery } from './useListPreferences'

/**
 * 列表「筛选条件」偏好（-B 交付能力； · W4-E 首次接入）
 *
 * 与 `useColumnConfig` 同源（同一套「localStorage + 版本号 + 失败回落」策略），
 * 但解决的是另一个问题：**用户翻了页、点了详情、切了菜单再回来，筛选条件不该被重置**。
 *
 * 与列偏好最大的不同 —— **白名单**：
 * 只回填「当前代码里确实存在」的字段，且值类型必须与默认值相容。
 * 理由：旧版本页面残留的字段如果被无条件回填，会**悄悄改变查询语义**
 * （例如某天给 `status` 传了 `null` 之外的历史值、或某个筛选字段后来被改成了别的类型），
 * 而这种错误不会报错、只会让结果集莫名变小 —— 属于最难排查的一类问题。
 *
 * 首次访问（无偏好）时，`query` 与传入的 `defaults` 完全一致 ⇒ 行为与改造前逐字段相同。
 *
 * ---
 *
 * ##  · W4-E 接入时补齐的三处能力（决策 D4）
 *
 * ### 1. `whitelist` 是「不持久化」的正规出口
 * 会**改变结果集合语义**、或**其展示形态依赖运行时上下文**的字段不该落盘。典型两类：
 * - 排序（`sortBy` / `sortOrder`）：排序改变的是「同一批数据的呈现顺序」，
 *   但它同时决定了**分页边界内是哪几条**，属集合性参数；
 * - 远程搜索类选择器（如使用记录页的「按设备 / 按员工」`targetId`）：
 *   它的**显示标签**来自远程候选集，持久化后候选集是空的 ⇒ 标签退化成裸数字 id
 *   （与 W4-D 候选池踩过的是同一个坑）。把 id 记住却显示成数字，是「看着有值、实则读不懂」，
 *   比不记更糟 —— 因此宁可只记维度（`scope`），不记具体目标。
 * `page` / `size` 同理**一律不落盘**：上次停在第 5 页，下次进入时数据可能只剩 2 页 ⇒ 空列表。
 *
 * ### 2. `remembered` —— 让「记忆」可见（D4 第 4 条）
 * 一个 `Ref<boolean>`，表示**本页当前是否已有生效的持久化偏好**。页面据此渲染
 * 「本页已记住筛选条件」提示条与「清除记忆」按钮。
 * 没有它，筛选持久化就是一个**用户看不见也管不着**的暗箱 —— 那正是「数据好像丢了」的来源。
 *
 * ### 3. 受用户级总开关约束（`useListPreferences`）
 * 总开关关闭时，本 composable 在**读侧与写侧同时短路**：既不回填、也不覆盖。
 * 刻意**不删除**既有数据 —— 重新打开开关，之前的筛选原样回来。
 * 这样「关掉一下看看」是一个零风险动作。
 */

/** 落盘结构版本号；不匹配即整体回落默认 */
export const LIST_QUERY_VERSION = 1

export interface ListQuerySnapshot {
  version: number
  values: Record<string, unknown>
}

export interface UseListQueryOptions<T extends object> {
  /** 路由路径，用于生成隔离键（`ticket:query:<routePath>`） */
  routePath: string
  /**
   * 默认值（**同时充当白名单**：只有这里出现过的 key 才可能被回填）。
   *
   * ⚠️ **只放筛选字段，不要放 `page` / `size`**（见文件头「1. `whitelist` 是正规出口」）。
   * 默认值必须**逐字段等于该页改造前首次进入时的取值**，这是 D4 第 1 条铁律的落点。
   */
  defaults: T
  /**
   * 自定义规范化：拿到「已通过白名单 + 类型相容」的原始对象，返回最终要写入 query 的字段。
   * 用于把 `null` 与 `''`、可选枚举的合法取值等**业务语义**收口在一处。
   * 返回值中未出现的字段保持默认值。
   */
  sanitize?: (values: Record<string, unknown>) => Partial<T>
  /** 额外限定可持久化的字段；缺省 = `defaults` 的全部 key。用于排除 `targetId` 这类不可回填字段 */
  whitelist?: (keyof T & string)[]
  /** 可注入存储（单测用）；缺省用 `localStorage` */
  storage?: KeyValueStorage | null
  /**
   * 是否落盘（缺省 `true`）。
   *
   * 这里不是为了「逐页 opt-in」——**opt-in 的实质是「该页是否 import 本 composable」**
   * （T2 八页不调用，行为逐字节不变）。本选项供「想复用默认值/白名单逻辑但不要副作用」的场景，
   * 例如在弹窗内嵌的临时列表里做一次性筛选。
   */
  persist?: boolean
}

export interface UseListQueryReturn<T extends object> {
  /** 双向绑定的筛选条件对象（**reactive**，与改造前页面里的 `reactive(...)` 同形） */
  query: T
  /** 本页当前是否已有生效的持久化偏好（供「已记住筛选」提示条 + 「清除记忆」按钮使用） */
  remembered: Ref<boolean>
  /** 把当前 `query` 落盘（在用户点「查询」后调用） */
  persist: () => Promise<void>
  /** 异步重读并写回 `query`（失败回落默认，不抛错） */
  load: () => Promise<void>
  /** 恢复默认值（并清除持久化偏好） */
  reset: () => Promise<void>
  /** 只清除持久化偏好，**不改变**当前 `query` */
  clear: () => Promise<void>
}

/** 列表筛选键（按页隔离） */
export function listQueryKey(routePath: string): string {
  return `ticket:query:${routePath}`
}

/**
 * 值是否与默认值「类型相容」。
 *
 * 判据刻意保守：
 * - 默认值是 `null` **或 `undefined`**（可选筛选的两种常见写法，"没有默认值"）→ 接受
 *   `string` / `number` / `boolean` / **数组** / **对象**（W4-E 修正：见下）；
 * - 默认值非 null → 只接受同类型（含 `null`，表示"清除筛选"）；
 * - 数组 / 对象默认值：只接受数组 / 对象，内容不再深校验（页面可再用 `sanitize` 收口）。
 *
 * ⚠️ **W4-E 修正的两处**（同一类缺陷的两个入口，都是「静默丢失」）：
 * 1. 「默认值 `null`」分支**只接受标量** ⇒「默认 `null`、有值时是数组」的字段永远回填不上。
 *    使用记录页的 `timeRange`（日期区间，`[string, string] | null`）正好撞上。
 * 2. 「默认值 `undefined`」分支**只接受 `null`** ⇒「声明了字段但没有默认值」的可选筛选
 *    （导出记录页的 `type: undefined`）写入成功、读取被丢弃 —— 表现为「该页记忆不生效」。
 * 现在两处都放宽为「接受任意 JSON 可表达的值」。白名单已经限定了字段名，
 * 多接受几种类型不会让「旧版本残留字段」混进来（那由白名单挡，与本函数无关）。
 */
export function isCompatibleValue(current: unknown, defaultValue: unknown): boolean {
  if (current === undefined) {
    return false
  }
  if (defaultValue === null || defaultValue === undefined) {
    // 可选筛选：`null` = 未筛选，其余 = 用户选过的值；数组 / 对象同样是合法取值。
    //
    // `undefined` 与 `null` 归为同一语义 ——「**声明了这个字段，但没有默认值**」。
    // 典型写法是 `defaults: { type: undefined as ExportTypeCode | undefined }`（导出记录页）。
    // ⚠️ 原实现把 `undefined` 单独判为「只接受 null」，后果是「默认 undefined 的可选筛选」
    // **写进去却读不回来**（值被白名单静默丢弃、不报错），页面上表现为「这页没记住筛选」。
    // （与 `timeRange` 那次是同一类缺陷的不同入口：那次是"只接受标量"卡住了数组。）
    return true
  }
  if (Array.isArray(defaultValue)) {
    return Array.isArray(current)
  }
  if (typeof defaultValue === 'object') {
    return current !== null && typeof current === 'object' && !Array.isArray(current)
  }
  if (current === null) {
    return true
  }
  return typeof current === typeof defaultValue
}

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

export function useListQuery<T extends object>(options: UseListQueryOptions<T>): UseListQueryReturn<T> {
  const storage = options.storage === undefined ? defaultStorage() : options.storage
  const enabled = options.persist !== false
  const key = listQueryKey(options.routePath)
  const defaults = options.defaults as Record<string, unknown>
  const whitelist = options.whitelist ?? (Object.keys(defaults) as (keyof T & string)[])
  const query = reactive({ ...defaults }) as T
  const remembered = ref(false)

  /**
   * 「当前可用的存储」—— 偏好落盘与回填的**唯一闸门**。
   *
   * 返回 `null` 的三种情形（都表现为「本页无记忆」，且**不触碰既有数据**）：
   * - 未开启持久化（`persist: false`）；
   * - 环境无存储（隐私模式 / SSR / 单测未注入）；
   * - 用户级总开关已关闭。
   */
  function available(): KeyValueStorage | null {
    if (!enabled || !storage) {
      return null
    }
    return readRememberListQuery(storage) ? storage : null
  }

  /** 白名单 + 类型相容过滤；返回值只含被接受的字段 */
  function accept(raw: unknown): Record<string, unknown> {
    const result: Record<string, unknown> = {}
    if (raw === null || typeof raw !== 'object') {
      return result
    }
    const source = raw as Record<string, unknown>
    for (const field of whitelist) {
      if (!Object.prototype.hasOwnProperty.call(source, field)) {
        continue
      }
      const value = source[field]
      if (!isCompatibleValue(value, defaults[field])) {
        continue
      }
      result[field] = value
    }
    return result
  }

  function applyValues(values: Record<string, unknown>): void {
    const accepted = options.sanitize ? options.sanitize(values) : (values as Partial<T>)
    const target = query as Record<string, unknown>
    // 先整体回默认，再覆盖被接受的字段 —— 保证不会出现"上一页残留的字段"
    for (const field of whitelist) {
      target[field] = defaults[field]
    }
    for (const field of Object.keys(accepted ?? {})) {
      if (whitelist.includes(field as keyof T & string)) {
        target[field] = (accepted as Record<string, unknown>)[field]
      }
    }
  }

  function read(): Record<string, unknown> {
    const active = available()
    if (!active) {
      return {}
    }
    try {
      const text = active.getItem(key)
      if (!text) {
        return {}
      }
      const snapshot = JSON.parse(text) as Partial<ListQuerySnapshot>
      if (snapshot.version !== LIST_QUERY_VERSION || snapshot.values == null) {
        return {}
      }
      return accept(snapshot.values)
    } catch {
      return {}
    }
  }

  /**
   * 值是否**全等于默认值**。
   *
   * 用于区分「用户存过一份偏好」与「用户存过一份**没有信息量的**偏好」：
   * 用户随手点了一次「查询」（筛选都是默认值）也会触发 `persist`，
   * 若不加区分，此后每次进入都会弹出「本页已记住筛选条件」——
   * 而它其实什么都没记住，只会让提示条变成一个被无视的噪音。
   *
   * 比较用 `JSON.stringify` 做**浅层深比**：取值全是 JSON 值（标量 / 数组 / 对象），
   * 且 `undefined` 与 `null` 统一归一（`el-date-picker` 清空时给 `null`、
   * 初始默认值可能写 `undefined`，二者是同一件事）。
   */
  function isDefaultValues(values: Record<string, unknown>): boolean {
    for (const field of whitelist) {
      const current = values[field] ?? null
      const fallback = defaults[field] ?? null
      if (JSON.stringify(current) !== JSON.stringify(fallback)) {
        return false
      }
    }
    return true
  }

  // 初始化即同步回填：避免"首帧默认筛选 → 再跳成用户筛选"的请求抖动（会白打一次接口）
  const restored = read()
  if (Object.keys(restored).length > 0 && !isDefaultValues(restored)) {
    applyValues(restored)
    remembered.value = true
  }

  async function persist(): Promise<void> {
    const active = available()
    if (!active) {
      await Promise.resolve()
      return
    }
    try {
      const target = query as Record<string, unknown>
      const values: Record<string, unknown> = {}
      for (const field of whitelist) {
        values[field] = target[field]
      }
      // 与默认值无差异 = 没有值得记住的东西（用户只是点了一次「查询」而已）
      if (isDefaultValues(values)) {
        await clear()
        return
      }
      const snapshot: ListQuerySnapshot = { version: LIST_QUERY_VERSION, values }
      active.setItem(key, JSON.stringify(snapshot))
      remembered.value = true
    } catch {
      // 静默失败：偏好不可用不影响列表功能本身
    }
    await Promise.resolve()
  }

  async function clear(): Promise<void> {
    // 清除是**显式用户动作**，因此不受总开关约束（关着开关也该能清掉旧偏好）
    remembered.value = false
    if (!storage) {
      await Promise.resolve()
      return
    }
    try {
      if (typeof storage.removeItem === 'function') {
        storage.removeItem(key)
      } else {
        // 存储适配器没提供 removeItem 时退化为「写一份空偏好」，效果等价于无记忆
        storage.setItem(key, JSON.stringify({ version: LIST_QUERY_VERSION, values: {} }))
      }
    } catch {
      // 静默失败
    }
    await Promise.resolve()
  }

  async function load(): Promise<void> {
    const restoredNow = read()
    const hasPreference = Object.keys(restoredNow).length > 0 && !isDefaultValues(restoredNow)
    if (hasPreference) {
      applyValues(restoredNow)
    } else {
      applyValues(defaults as Record<string, unknown>)
    }
    remembered.value = hasPreference
    await Promise.resolve()
  }

  async function reset(): Promise<void> {
    applyValues({})
    await clear()
  }

  return { query, remembered, persist, load, reset, clear }
}
