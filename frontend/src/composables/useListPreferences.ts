import { ref, type Ref } from 'vue'
import type { KeyValueStorage } from './useColumnConfig'

/**
 * 列表「筛选记忆」用户级开关（ · W4-E，决策 D4 第 4 条）
 *
 * 为什么需要一个**总开关**：
 * 筛选持久化对「天天只查同一批数据」的人是净收益，对「偶尔用一次、每次都想看全量」的人
 * 却会变成「我的数据好像丢了」——两种人都对，差别在**用法**而非权限，所以只能交给用户自己决定。
 *
 * 语义（三个关键约定）：
 * 1. **默认开**：键不存在 = 开。理由是改造前「筛选不保留」是一个**意外**（刷新即丢），
 *    而不是任何人刻意要的行为；默认开更符合「我以为它会记住」的直觉；
 * 2. **关掉 = 全局读侧短路**：关闭后所有已接入清单的页面一律回到「每次进入都是默认视图」，
 *    用于一次性消除疑虑。**不清除既有数据** —— 重新打开开关，之前的筛选原样回来；
 * 3. **无存储时视为「开」**：存储不可用（隐私模式 / 单测未注入）时读不到用户的关闭意图，
 *    此时保持「开」是因为它不会造成任何实际影响（没有存储，本来也无从回填）。
 *
 * 存储：`localStorage`，键 `ticket:pref:remember-list-query`，值 `'1'` / `'0'`。
 * 刻意不用 JSON：这是一个**单值**布尔开关，用纯字符串让「手改、肉眼查」都不需要解析器。
 */

/** 「记住列表筛选」用户级偏好的存储键（跨页面共用，非按页隔离） */
export const REMEMBER_LIST_QUERY_KEY = 'ticket:pref:remember-list-query'

/** 取默认存储；不可用时返回 null（调用方自行回落，见文件头第 3 条） */
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

/**
 * 读开关。
 *
 * 显式接收 `storage` 而不是内部取默认存储：让 `useListQuery` 能用**同一份**存储
 * 同时读偏好与读开关（单测注入内存实现时，放一个开关键即可覆盖两条路径）。
 */
export function readRememberListQuery(storage: KeyValueStorage | null): boolean {
  if (!storage) {
    return true
  }
  try {
    const raw = storage.getItem(REMEMBER_LIST_QUERY_KEY)
    if (raw === null) {
      return true
    }
    return raw !== '0'
  } catch {
    return true
  }
}

/** 写开关（静默失败：偏好不可用不该阻断任何页面） */
export function writeRememberListQuery(storage: KeyValueStorage | null, enabled: boolean): void {
  if (!storage) {
    return
  }
  try {
    storage.setItem(REMEMBER_LIST_QUERY_KEY, enabled ? '1' : '0')
  } catch {
    // 配额满 / 隐私模式：忽略
  }
}

export interface UseListPreferencesReturn {
  /** 是否记住列表筛选（默认开） */
  rememberQuery: Ref<boolean>
  /** 设置并落盘 */
  setRememberQuery: (value: boolean) => void
}

/**
 * 「记住列表筛选」开关（个人中心使用）。
 *
 * 注意它作用于**下次进入页面**：本 composable 与各页面的 `useListQuery` 实例之间
 * 没有响应式联动 —— 用户在当前页切换开关后，已打开的列表不会被立刻重置，
 * 需要下次进入（或手动点该页的「重置」）才生效。这是刻意的：立刻重置会让
 * 「关掉开关」这一个动作顺手清空用户当前的查询，那是一种更粗暴的行为变更。
 */
export function useListPreferences(): UseListPreferencesReturn {
  const storage = defaultStorage()
  const rememberQuery = ref(readRememberListQuery(storage))

  function setRememberQuery(value: boolean): void {
    rememberQuery.value = value
    writeRememberListQuery(storage, value)
  }

  return { rememberQuery, setRememberQuery }
}
