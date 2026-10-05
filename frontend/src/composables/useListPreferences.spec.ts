import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { KeyValueStorage } from '@/composables/useColumnConfig'
import {
  REMEMBER_LIST_QUERY_KEY,
  readRememberListQuery,
  useListPreferences,
  writeRememberListQuery
} from '@/composables/useListPreferences'
import { LIST_QUERY_VERSION, useListQuery } from '@/composables/useListQuery'

/**
 * 「记住列表筛选」用户级总开关（ · W4-E，决策 D4 第 4 条）
 *
 * 这个开关有两处「反了也照样跑」的判断，因此必须逐条钉住：
 *
 * 1. **默认值必须是「开」**。若默认写成「关」，所有已接入页面在升级后会静默失去
 *    「记住筛选」这个能力 —— 没有报错、没有提示，用户只会觉得"这功能是不是没做"。
 *    所以第一条用例专门钉默认值，而不是只测「写 false 读回 false」。
 * 2. **关闭时不得删除既有数据**。这是"零风险关一下试试"这个承诺的**全部依据**；
 *    若关闭顺手清库，用户重新打开开关后筛选不会回来，那就是数据丢失。
 *
 * 另外还要钉住「显式清除不受开关约束」：开关关着时，用户仍应能清掉旧偏好
 * （否则旧数据会在他看不见的地方一直躺着）。
 *
 * 集成侧（开关 ↔ 各页 `useListQuery` 的耦合）也在这里测：`useListQuery` 读的
 * 与这里写的是**同一份存储**，两者之间没有任何响应式联动，只有键的约定 ——
 * 这种"靠约定衔接"的地方最容易在重构时断掉，所以用一个真实往返来固定它。
 */

interface MemoryStorage extends KeyValueStorage {
  read: (key: string) => string | null
  keys: () => string[]
}

/**
 * 内存存储。
 *
 * `withRemove = false` 用来覆盖「适配器没提供 `removeItem`」那条退化分支
 * （真实环境里它是可选方法，隐私模式 / 自定义适配器都可能没有）。
 */
function memoryStorage(initial: Record<string, string> = {}, withRemove = true): MemoryStorage {
  const map = new Map<string, string>(Object.entries(initial))
  const storage: MemoryStorage = {
    getItem: (key) => (map.has(key) ? (map.get(key) as string) : null),
    setItem: (key, value) => {
      map.set(key, value)
    },
    read: (key) => (map.has(key) ? (map.get(key) as string) : null),
    keys: () => Array.from(map.keys())
  }
  if (withRemove) {
    storage.removeItem = (key) => {
      map.delete(key)
    }
  }
  return storage
}

describe('readRememberListQuery · 默认值与容错', () => {
  it('键不存在时视为「开」（改造前"刷新即丢"是意外而不是任何人要的行为）', () => {
    expect(readRememberListQuery(memoryStorage())).toBe(true)
  })

  it('无存储时视为「开」（读不到关闭意图，且没有存储本来也无从回填）', () => {
    expect(readRememberListQuery(null)).toBe(true)
  })

  it("值 '0' → 关；'1' → 开（刻意不用 JSON：单值开关让肉眼查看不需要解析器）", () => {
    expect(readRememberListQuery(memoryStorage({ [REMEMBER_LIST_QUERY_KEY]: '0' }))).toBe(false)
    expect(readRememberListQuery(memoryStorage({ [REMEMBER_LIST_QUERY_KEY]: '1' }))).toBe(true)
  })

  it('读取抛异常时回落「开」，不把存储故障升级成页面故障', () => {
    const broken: KeyValueStorage = {
      getItem: () => {
        throw new Error('SecurityError')
      },
      setItem: () => undefined
    }
    expect(readRememberListQuery(broken)).toBe(true)
  })
})

describe('writeRememberListQuery', () => {
  it("写入 '0' / '1'，并能被 read 读回（同一个键、同一个含义）", () => {
    const storage = memoryStorage()
    writeRememberListQuery(storage, false)
    expect(storage.read(REMEMBER_LIST_QUERY_KEY)).toBe('0')
    expect(readRememberListQuery(storage)).toBe(false)

    writeRememberListQuery(storage, true)
    expect(storage.read(REMEMBER_LIST_QUERY_KEY)).toBe('1')
    expect(readRememberListQuery(storage)).toBe(true)
  })

  it('无存储 / 写入抛异常时静默失败（偏好不可用不该阻断任何页面）', () => {
    expect(() => writeRememberListQuery(null, false)).not.toThrow()
    const broken: KeyValueStorage = {
      getItem: () => null,
      setItem: () => {
        throw new Error('QuotaExceededError')
      }
    }
    expect(() => writeRememberListQuery(broken, false)).not.toThrow()
  })
})

describe('useListPreferences · 默认存储上的读写往返', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('默认「开」，且不改动存储（没点过就不该留下痕迹）', () => {
    const { rememberQuery } = useListPreferences()
    expect(rememberQuery.value).toBe(true)
    expect(localStorage.getItem(REMEMBER_LIST_QUERY_KEY)).toBeNull()
  })

  it('setRememberQuery(false) 落盘后，新实例读回 false（跨会话生效）', () => {
    useListPreferences().setRememberQuery(false)
    expect(localStorage.getItem(REMEMBER_LIST_QUERY_KEY)).toBe('0')
    expect(useListPreferences().rememberQuery.value).toBe(false)
  })
})

describe('总开关与 useListQuery 的耦合', () => {
  const DEFAULTS = { keyword: '', status: null as string | null }
  const KEY = 'ticket:query:/order/all'

  function storedPreference(): string {
    return JSON.stringify({ version: LIST_QUERY_VERSION, values: { keyword: '打印机' } })
  }

  it('开关关闭 → 有偏好也不回填（读侧短路）', () => {
    const storage = memoryStorage({ [KEY]: storedPreference(), [REMEMBER_LIST_QUERY_KEY]: '0' })
    const { query, remembered } = useListQuery({ routePath: '/order/all', defaults: DEFAULTS, storage })
    expect(query.keyword).toBe('')
    expect(remembered.value).toBe(false)
  })

  it('开关关闭 → persist 不覆盖存储（写侧短路），既有偏好原样留着', () => {
    const storage = memoryStorage({ [KEY]: storedPreference(), [REMEMBER_LIST_QUERY_KEY]: '0' })
    const { query, persist, load } = useListQuery({ routePath: '/order/all', defaults: DEFAULTS, storage })
    query.keyword = '试图写入'
    void persist()

    // 关键：数据没被删、也没被改写 —— 重新打开开关就能原样回来
    expect(storage.read(KEY)).toBe(storedPreference())

    // 打开开关后同一条数据立刻可用
    writeRememberListQuery(storage, true)
    void load()
    expect(query.keyword).toBe('打印机')
  })

  it('开关关闭时 clear() 仍生效（清除是显式用户动作，不该被开关挡住）', async () => {
    const storage = memoryStorage({ [KEY]: storedPreference(), [REMEMBER_LIST_QUERY_KEY]: '0' })
    const { clear, remembered } = useListQuery({ routePath: '/order/all', defaults: DEFAULTS, storage })
    await clear()
    expect(storage.read(KEY)).toBeNull()
    expect(remembered.value).toBe(false)
  })

  it('存储适配器没有 removeItem 时，clear() 退化为写一份空偏好（效果等价于无记忆）', async () => {
    const storage = memoryStorage({ [KEY]: storedPreference() }, false)
    const { clear } = useListQuery({ routePath: '/order/all', defaults: DEFAULTS, storage })
    await clear()

    expect(storage.read(KEY)).not.toBeNull()
    expect(JSON.parse(storage.read(KEY) as string)).toEqual({ version: LIST_QUERY_VERSION, values: {} })
    // 换实例读回：空偏好 = 无记忆，回落默认
    const fresh = useListQuery({ routePath: '/order/all', defaults: DEFAULTS, storage })
    expect(fresh.query.keyword).toBe('')
    expect(fresh.remembered.value).toBe(false)
  })

  it('开关默认开时，读侧不受影响（对照组，防止上面的用例靠"恒不回填"假通过）', () => {
    const storage = memoryStorage({ [KEY]: storedPreference() })
    const { query, remembered } = useListQuery({ routePath: '/order/all', defaults: DEFAULTS, storage })
    expect(query.keyword).toBe('打印机')
    expect(remembered.value).toBe(true)
  })

  it('存储读取抛异常时开关仍为「开」，也不冒泡异常', () => {
    const spy = vi.fn(() => {
      throw new Error('boom')
    })
    const broken: KeyValueStorage = { getItem: spy, setItem: () => undefined }
    expect(() => useListQuery({ routePath: '/order/all', defaults: DEFAULTS, storage: broken })).not.toThrow()
  })
})
