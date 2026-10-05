import { describe, expect, it } from 'vitest'
import {
  COLUMN_CONFIG_VERSION,
  defaultColumnConfig,
  normalizeColumnConfig,
  useColumnConfig,
  type ColumnConfigDef,
  type KeyValueStorage
} from '@/composables/useColumnConfig'
import { LIST_QUERY_VERSION, isCompatibleValue, listQueryKey, useListQuery } from '@/composables/useListQuery'

/**
 * 列表偏好读写单测（-B）
 *
 * 这一层是「纯记账 + 容错」，它出错的**表现形式极隐蔽**：
 * - 读到坏数据不回落 → 整个列表页打不开（而不是少一列）；
 * - 把已废弃的列 key 当成有效列 → `el-table` 少渲染一列，肉眼很难立刻发现；
 * - 允许隐藏锁定列（操作列）→ 页面上所有操作按钮消失；
 * - 筛选白名单失效 → 查询结果莫名变少。
 * 因此这里针对**每一种异常输入**各写一条，而不是只测「存了能读回来」。
 */

/** 内存存储：替代 localStorage，让用例之间互不污染，也便于断言"到底写了什么" */
interface MemoryStorage extends KeyValueStorage {
  /** 读回某个键（不传则返回最后一次写入的内容），用于断言"到底写了什么" */
  read: (key?: string) => string | null
}

/**
 * 内存存储。
 *
 * `initial` 是「所有键的初始内容」——用例想模拟"存量坏数据"时不必知道页面用的是哪个键；
 * 一旦某键被写入，后续读取以写入值为准。**按 key 分区**是必须的：
 * 单槽实现会让「按页隔离」这类用例恒真（读到的是别的页写的值），属于假通过。
 */
function memoryStorage(initial?: string): MemoryStorage {
  const map = new Map<string, string>()
  let lastWritten: string | null = null
  return {
    getItem: (key: string) => (map.has(key) ? (map.get(key) as string) : (initial ?? null)),
    setItem: (key: string, next: string) => {
      map.set(key, next)
      lastWritten = next
    },
    // `localStorage` 有这个可选方法，桩件最初没实现 —— 于是 `clear()` 永远走
    // 「写一份空偏好」的退化分支，**主分支（删键）在用例里从未被走过**。
    // W4-E 补上；退化分支另有专测（`useListPreferences.spec.ts` 的 `withRemove = false`）。
    removeItem: (key: string) => {
      map.delete(key)
    },
    read: (key?: string) => {
      if (key == null) {
        return lastWritten
      }
      return map.has(key) ? (map.get(key) as string) : null
    }
  }
}

const COLUMNS: ColumnConfigDef[] = [
  { key: 'orderNo', label: '工单编号' },
  { key: 'device', label: '设备' },
  { key: 'status', label: '状态' },
  { key: 'remark', label: '备注', defaultVisible: false },
  { key: 'action', label: '操作', configurable: false }
]

describe('useColumnConfig · 默认与规范化', () => {
  it('默认配置：顺序 = 定义顺序；仅 defaultVisible=false 且可配置的列默认隐藏', () => {
    expect(defaultColumnConfig(COLUMNS)).toEqual({
      version: COLUMN_CONFIG_VERSION,
      order: ['orderNo', 'device', 'status', 'remark', 'action'],
      hidden: ['remark']
    })
  })

  it('锁定列（configurable=false）即便声明 defaultVisible=false 也默认可见', () => {
    const config = defaultColumnConfig([{ key: 'action', label: '操作', configurable: false, defaultVisible: false }])
    expect(config.hidden).toEqual([])
  })

  it('非对象输入 → 整体回落默认（不是一个字段一个字段地猜）', () => {
    for (const bad of [null, undefined, 42, 'oops', []]) {
      expect(normalizeColumnConfig(bad, COLUMNS)).toEqual(defaultColumnConfig(COLUMNS))
    }
  })

  it('版本号不匹配 → 回落默认（旧结构不做就地迁移）', () => {
    const raw = { version: COLUMN_CONFIG_VERSION + 1, order: ['device', 'orderNo'], hidden: [] }
    expect(normalizeColumnConfig(raw, COLUMNS)).toEqual(defaultColumnConfig(COLUMNS))
  })

  it('order / hidden 不是数组 → 回落默认', () => {
    expect(normalizeColumnConfig({ version: COLUMN_CONFIG_VERSION, order: 'x', hidden: [] }, COLUMNS)).toEqual(
      defaultColumnConfig(COLUMNS)
    )
    expect(normalizeColumnConfig({ version: COLUMN_CONFIG_VERSION, order: [], hidden: {} }, COLUMNS)).toEqual(
      defaultColumnConfig(COLUMNS)
    )
  })

  it('order 里的未知 key / 重复 key / 非字符串 → 逐个丢弃，其余保留', () => {
    const raw = {
      version: COLUMN_CONFIG_VERSION,
      order: ['status', 'ghost', 'status', 7, 'orderNo'],
      hidden: []
    }
    const config = normalizeColumnConfig(raw, COLUMNS)
    // 未知与重复被剔除；代码里新增（此处为 device/remark/action）按定义顺序补齐到末尾
    expect(config.order).toEqual(['status', 'orderNo', 'device', 'remark', 'action'])
  })

  it('代码新增的列必须被补进 order，否则新列永远不会出现', () => {
    const raw = { version: COLUMN_CONFIG_VERSION, order: ['orderNo'], hidden: [] }
    const config = normalizeColumnConfig(raw, COLUMNS)
    expect(config.order).toContain('status')
    expect(config.order).toContain('action')
  })

  it('hidden 里的未知 key 被丢弃', () => {
    const raw = { version: COLUMN_CONFIG_VERSION, order: COLUMNS.map((c) => c.key), hidden: ['ghost', 'status'] }
    expect(normalizeColumnConfig(raw, COLUMNS).hidden).toEqual(['status'])
  })

  it('试图隐藏锁定列 → 被强制移出 hidden（用户关掉操作列 = 页面坏掉）', () => {
    const raw = { version: COLUMN_CONFIG_VERSION, order: COLUMNS.map((c) => c.key), hidden: ['action', 'status'] }
    expect(normalizeColumnConfig(raw, COLUMNS).hidden).toEqual(['status'])
  })
})

describe('useColumnConfig · 读写与容错', () => {
  it('无偏好时：可见列 = 默认列，顺序与定义一致（= 改造前行为）', () => {
    const { visibleKeys, customized } = useColumnConfig({
      routePath: '/order/mine',
      columns: () => COLUMNS,
      storage: memoryStorage()
    })
    expect(visibleKeys.value).toEqual(['orderNo', 'device', 'status', 'action'])
    expect(customized.value).toBe(false)
  })

  it('apply 会落盘，并让 visibleKeys 立刻反映新顺序（隐藏列只是不渲染，顺序信息保留）', async () => {
    const storage = memoryStorage()
    const { visibleKeys, order, apply, customized } = useColumnConfig({
      routePath: '/order/mine',
      columns: () => COLUMNS,
      storage
    })

    await apply({ order: ['status', 'orderNo', 'device', 'remark', 'action'], hidden: ['device'] })

    expect(visibleKeys.value).toEqual(['status', 'orderNo', 'remark', 'action'])
    // 隐藏列的相对位置仍保留在 order 里，重新勾选时能回到原位
    expect(order.value).toEqual(['status', 'orderNo', 'device', 'remark', 'action'])
    expect(customized.value).toBe(true)
    expect(JSON.parse(storage.read() ?? '{}')).toMatchObject({
      version: COLUMN_CONFIG_VERSION,
      order: ['status', 'orderNo', 'device', 'remark', 'action'],
      hidden: ['device']
    })
  })

  it('apply 传入非法值（未知 key / 试图隐藏锁定列）会被规范化后再落盘', async () => {
    const storage = memoryStorage()
    const config = useColumnConfig({ routePath: '/order/all', columns: () => COLUMNS, storage })

    await config.apply({ order: ['ghost', 'orderNo'], hidden: ['action'] })

    expect(config.order.value).toEqual(['orderNo', 'device', 'status', 'remark', 'action'])
    expect(config.hidden.value).toEqual([])
    expect(config.visibleKeys.value).toContain('action')
    // 落盘的同样是规范化后的结果（否则下次进来又会把操作列藏掉）
    expect(JSON.parse(storage.read() ?? '{}').hidden).toEqual([])
  })

  it('reset 回到默认（含把默认隐藏的列重新隐藏）', async () => {
    const storage = memoryStorage()
    const first = useColumnConfig({ routePath: '/asset/ledger', columns: () => COLUMNS, storage })
    await first.apply({ order: ['status', 'orderNo', 'device', 'remark', 'action'], hidden: [] })
    expect(first.visibleKeys.value).toEqual(['status', 'orderNo', 'device', 'remark', 'action'])

    await first.reset()
    expect(first.visibleKeys.value).toEqual(['orderNo', 'device', 'status', 'action'])
    expect(first.customized.value).toBe(false)
  })

  it('存量坏数据（非法 JSON）→ 回落默认且不抛错', () => {
    const storage = memoryStorage('{ this is not json')
    const { visibleKeys } = useColumnConfig({ routePath: '/order/pending', columns: () => COLUMNS, storage })
    expect(visibleKeys.value).toEqual(['orderNo', 'device', 'status', 'action'])
  })

  it('存量结构不认识（version 缺失）→ 回落默认', () => {
    const storage = memoryStorage(JSON.stringify({ order: ['status'], hidden: [] }))
    const { visibleKeys } = useColumnConfig({ routePath: '/order/pending', columns: () => COLUMNS, storage })
    expect(visibleKeys.value).toEqual(['orderNo', 'device', 'status', 'action'])
  })

  it('存储不可用（storage=null）→ 不抛错，配置仍在本次会话内生效', async () => {
    const { visibleKeys, apply, reset } = useColumnConfig({
      routePath: '/order/pending',
      columns: () => COLUMNS,
      storage: null
    })
    // hidden 是"权威列表"：只传 ['status'] 表示其余列都显示（含默认隐藏的 remark）
    await apply({ order: ['device', 'orderNo', 'status', 'remark', 'action'], hidden: ['status'] })
    expect(visibleKeys.value).toEqual(['device', 'orderNo', 'remark', 'action'])
    await reset()
    expect(visibleKeys.value).toEqual(['orderNo', 'device', 'status', 'action'])
  })

  it('load() 会重新读盘并覆盖内存中的顺序', async () => {
    const storage = memoryStorage()
    const { order, apply, load } = useColumnConfig({ routePath: '/order/all', columns: () => COLUMNS, storage })
    await apply({ order: ['status', 'orderNo', 'device', 'remark', 'action'], hidden: [] })
    expect(order.value[0]).toBe('status')

    // 模拟"另一个标签页改了偏好"：直接改存储再 load
    storage.setItem(
      'ticket:cols:/order/all',
      JSON.stringify({ version: COLUMN_CONFIG_VERSION, order: ['device', 'orderNo', 'status', 'remark', 'action'], hidden: [] })
    )
    await load()
    expect(order.value[0]).toBe('device')
  })

  it('configRows 中锁定列恒为可见且 locked=true（弹窗里勾不掉）', () => {
    const storage = memoryStorage()
    const { configRows } = useColumnConfig({ routePath: '/order/all', columns: () => COLUMNS, storage })
    const locked = configRows.value.find((row) => row.key === 'action')
    expect(locked).toMatchObject({ locked: true, visible: true, defaultVisible: true })
  })

  it('列定义在运行期变化（新增列）时，新增列自动出现在可见列表末尾', () => {
    const storage = memoryStorage()
    const extra: ColumnConfigDef[] = [...COLUMNS, { key: 'createdAt', label: '提交时间' }]
    const first = useColumnConfig({ routePath: '/order/mine', columns: () => COLUMNS, storage })
    expect(first.visibleKeys.value).not.toContain('createdAt')

    const second = useColumnConfig({ routePath: '/order/mine', columns: () => extra, storage })
    expect(second.visibleKeys.value).toContain('createdAt')
  })

  it('isVisible 对未知 key 保守返回 true（宁可多显示也不要静默少一列）', () => {
    const { isVisible } = useColumnConfig({
      routePath: '/order/mine',
      columns: () => COLUMNS,
      storage: memoryStorage()
    })
    expect(isVisible('not-a-column')).toBe(true)
    expect(isVisible('remark')).toBe(false)
  })
})

describe('useListQuery · 筛选持久化与白名单', () => {
  const DEFAULTS = { keyword: '', status: null as string | null, pageSize: 10 }

  it('无偏好时 query 等于默认值（= 改造前首帧行为）', () => {
    const { query } = useListQuery({ routePath: '/order/all', defaults: DEFAULTS, storage: memoryStorage() })
    expect(query).toEqual(DEFAULTS)
  })

  it('persist 后换一个实例能回填（跨会话记忆）', async () => {
    const storage = memoryStorage()
    const first = useListQuery({ routePath: '/order/all', defaults: DEFAULTS, storage })
    first.query.keyword = '打印机'
    first.query.status = 'BORROWED'
    await first.persist()

    const second = useListQuery({ routePath: '/order/all', defaults: DEFAULTS, storage })
    expect(second.query.keyword).toBe('打印机')
    expect(second.query.status).toBe('BORROWED')
  })

  it('白名单：存量里多出来的字段不会被回填（否则会悄悄改变查询语义）', () => {
    const storage = memoryStorage(
      JSON.stringify({
        version: LIST_QUERY_VERSION,
        values: { keyword: 'x', legacyField: '应被忽略', status: 'RETURNED' }
      })
    )
    const { query } = useListQuery({ routePath: '/order/all', defaults: DEFAULTS, storage })
    expect(query.keyword).toBe('x')
    expect(query.status).toBe('RETURNED')
    expect(Object.keys(query)).not.toContain('legacyField')
  })

  it('类型不相容的值被忽略（如把 string 塞进数字字段）', () => {
    const storage = memoryStorage(
      JSON.stringify({ version: LIST_QUERY_VERSION, values: { pageSize: '十', keyword: 123 } })
    )
    const { query } = useListQuery({ routePath: '/order/all', defaults: DEFAULTS, storage })
    expect(query.pageSize).toBe(10)
    expect(query.keyword).toBe('')
  })

  it('默认值为 null 的可选字段接受 null（用于"清除筛选"）', () => {
    const storage = memoryStorage(JSON.stringify({ version: LIST_QUERY_VERSION, values: { status: null } }))
    const { query } = useListQuery({ routePath: '/order/all', defaults: DEFAULTS, storage })
    expect(query.status).toBeNull()
  })

  it('sanitize 可把业务语义收口在一处（如把非法枚举回落默认）', () => {
    const storage = memoryStorage(
      JSON.stringify({ version: LIST_QUERY_VERSION, values: { status: 'NOT_A_STATUS', keyword: 'ok' } })
    )
    const { query } = useListQuery({
      routePath: '/order/all',
      defaults: DEFAULTS,
      sanitize: (values) => ({
        keyword: typeof values.keyword === 'string' ? values.keyword : '',
        status: values.status === 'BORROWED' || values.status === 'RETURNED' ? (values.status as string) : null
      }),
      storage
    })
    expect(query.keyword).toBe('ok')
    expect(query.status).toBeNull()
  })

  it('版本不匹配 / 非法 JSON → 回落默认且不抛错', () => {
    for (const bad of ['{oops', JSON.stringify({ version: 99, values: { keyword: 'x' } })]) {
      const { query } = useListQuery({ routePath: '/order/all', defaults: DEFAULTS, storage: memoryStorage(bad) })
      expect(query).toEqual(DEFAULTS)
    }
  })

  it('reset 恢复默认并落盘（下次进入不会再沿用旧筛选）', async () => {
    const storage = memoryStorage()
    const { query, persist, reset } = useListQuery({ routePath: '/order/all', defaults: DEFAULTS, storage })
    query.keyword = 'abc'
    await persist()
    await reset()
    expect(query.keyword).toBe('')

    const fresh = useListQuery({ routePath: '/order/all', defaults: DEFAULTS, storage })
    expect(fresh.query.keyword).toBe('')
  })

  it('存储不可用时不抛错，且 load() 回落默认', async () => {
    const { query, load, persist } = useListQuery({ routePath: '/order/all', defaults: DEFAULTS, storage: null })
    query.keyword = 'x'
    await persist()
    await load()
    expect(query.keyword).toBe('')
  })

  it('键按页隔离：不同 routePath 互不影响', async () => {
    const storage = memoryStorage()
    const mine = useListQuery({ routePath: '/order/mine', defaults: DEFAULTS, storage })
    mine.query.keyword = 'mine-only'
    await mine.persist()

    const all = useListQuery({ routePath: '/order/all', defaults: DEFAULTS, storage })
    expect(all.query.keyword).toBe('')
    expect(listQueryKey('/order/mine')).toBe('ticket:query:/order/mine')
  })
})

/**
 * W4-E 接入时补齐的三处能力（决策 D4）。
 *
 * 这三条都对应**真实接入时才会暴露**的问题，而不是为了凑覆盖率：
 * - `whitelist`：使用记录页的「按设备 / 按员工」是远程选择器，把 id 记住但候选集是空的
 *   ⇒ 标签退化成裸数字 id（与 W4-D 候选池同一个坑）；
 * - `remembered`：没有它，筛选持久化就是用户看不见也管不着的暗箱；
 * - `isCompatibleValue` 的原实现有个**静默失效**的洞：默认 `null` 的字段只接受标量，
 *   于是「默认 null、有值时是数组」的筛选（日期区间）永远回填不上 —— 值被白名单悄悄丢掉，
 *   不报错、也不提示，用户只会觉得"筛选没记住"。使用记录页的 `timeRange` 正好撞上。
 */
describe('useListQuery · W4-E 补齐的三处能力', () => {
  const DEFAULTS = {
    keyword: '',
    timeRange: null as [string, string] | null,
    targetId: null as number | null
  }
  const KEY = listQueryKey('/usage/records')

  it('whitelist 排除的字段既不落盘也不回填（远程选择器只记维度、不记具体目标）', async () => {
    const storage = memoryStorage()
    const { query, persist } = useListQuery({
      routePath: '/usage/records',
      defaults: DEFAULTS,
      whitelist: ['keyword', 'timeRange'],
      storage
    })
    query.keyword = '笔记本'
    query.targetId = 42
    await persist()

    // 落盘的字段集合就是白名单本身：targetId 一个字节都没写进去
    expect(Object.keys(JSON.parse(storage.read(KEY) as string).values)).toEqual(['keyword', 'timeRange'])

    const fresh = useListQuery({
      routePath: '/usage/records',
      defaults: DEFAULTS,
      whitelist: ['keyword', 'timeRange'],
      storage
    })
    expect(fresh.query.keyword).toBe('笔记本')
    expect(fresh.query.targetId).toBeNull()
  })

  it('默认 null 的字段接受数组（修正前：日期区间这类筛选永远回填不上）', async () => {
    const storage = memoryStorage()
    const first = useListQuery({
      routePath: '/usage/records',
      defaults: DEFAULTS,
      whitelist: ['timeRange'],
      storage
    })
    first.query.timeRange = ['2026-01-01', '2026-01-31']
    await first.persist()

    const second = useListQuery({
      routePath: '/usage/records',
      defaults: DEFAULTS,
      whitelist: ['timeRange'],
      storage
    })
    expect(second.query.timeRange).toEqual(['2026-01-01', '2026-01-31'])
  })

  it('isCompatibleValue 的边界：默认 null / undefined 都放宽到任意 JSON 值，其余分支不变（放宽不会让旧字段混进来）', () => {
    // 默认 null：可选筛选，数组 / 对象同样是用户选过的合法值
    expect(isCompatibleValue([1, 2], null)).toBe(true)
    expect(isCompatibleValue({ a: 1 }, null)).toBe(true)
    expect(isCompatibleValue('x', null)).toBe(true)
    expect(isCompatibleValue(false, null)).toBe(true)

    // 但 undefined 一律拒绝：存量里没有这个 key 时不该产生半截回填
    expect(isCompatibleValue(undefined, null)).toBe(false)
    expect(isCompatibleValue(undefined, '')).toBe(false)
    expect(isCompatibleValue(undefined, undefined)).toBe(false)

    // 默认 undefined（=「声明了字段、但没有默认值」）与 null 同语义。
    // 这正是导出记录页 `defaults: { type: undefined as ExportTypeCode | undefined }` 的形态 ——
    // 原实现只接受 null，于是「导出类型」这条筛选**写进去却读不回来**（记忆功能静默失效）。
    expect(isCompatibleValue('DEVICE', undefined)).toBe(true)
    expect(isCompatibleValue(['a'], undefined)).toBe(true)

    // 默认非 null：仍只接受同类型（null 例外，表示"清除筛选"）
    expect(isCompatibleValue('x', '')).toBe(true)
    expect(isCompatibleValue(null, '')).toBe(true)
    expect(isCompatibleValue(1, '')).toBe(false)
    expect(isCompatibleValue('十', 10)).toBe(false)

    // 数组 / 对象默认值：只接受同类，不深校验（页面可用 sanitize 收口）
    expect(isCompatibleValue(['a'], [])).toBe(true)
    expect(isCompatibleValue('a', [])).toBe(false)
    expect(isCompatibleValue({ a: 1 }, {})).toBe(true)
    expect(isCompatibleValue([1], {})).toBe(false)
  })

  it('remembered：无偏好 false → 落盘 true → clear 后 false，且 clear 不动当前视图', async () => {
    const storage = memoryStorage()
    const { query, remembered, persist, clear } = useListQuery({
      routePath: '/usage/records',
      defaults: DEFAULTS,
      storage
    })
    expect(remembered.value).toBe(false)

    query.keyword = 'A'
    await persist()
    expect(remembered.value).toBe(true)
    expect(storage.read(KEY)).not.toBeNull()

    await clear()
    expect(remembered.value).toBe(false)
    expect(storage.read(KEY)).toBeNull()
    // 「清除记忆」不该顺手把用户正在看的筛选也清掉 —— 那会变成一次意外查询
    expect(query.keyword).toBe('A')
  })

  it('与默认值无差异不落盘、也不算「已记住」，并把过期的旧偏好清掉', async () => {
    const storage = memoryStorage()
    const { query, remembered, persist } = useListQuery({
      routePath: '/usage/records',
      defaults: DEFAULTS,
      storage
    })

    // 用户只是顺手点了一次「查询」（条件全是默认值）→ 什么都不该留下
    await persist()
    expect(storage.read(KEY)).toBeNull()
    expect(remembered.value).toBe(false)

    query.keyword = 'X'
    await persist()
    expect(remembered.value).toBe(true)

    // 又改回默认值再查一次 → 旧偏好被清掉，而不是留一份"看似有记忆"的空壳
    query.keyword = ''
    await persist()
    expect(storage.read(KEY)).toBeNull()
    expect(remembered.value).toBe(false)
  })

  it('undefined 与 null 视为同一件事（选择器清空给 null、初始默认值可能写 undefined）', async () => {
    const storage = memoryStorage()
    const { remembered, persist } = useListQuery({
      routePath: '/usage/records',
      defaults: { keyword: '', picked: undefined as string | undefined },
      storage
    })
    await persist()
    expect(remembered.value).toBe(false)
    expect(storage.read(KEY)).toBeNull()
  })

  it('persist: false → 既不读也不写（复用默认值/白名单逻辑但不要副作用）', async () => {
    // 注意该桩件的契约：`initial` 是「任意键的回落值」（不是按 key 分区的初值）
    const existing = JSON.stringify({ version: LIST_QUERY_VERSION, values: { keyword: '历史' } })
    const storage = memoryStorage(existing)
    const { query, remembered, persist } = useListQuery({
      routePath: '/usage/records',
      defaults: DEFAULTS,
      persist: false,
      storage
    })
    // 读侧短路：存量偏好明明在，但不回填
    expect(storage.read(KEY)).toBeNull()
    expect(query.keyword).toBe('')
    expect(remembered.value).toBe(false)

    // 写侧短路：无参 read() = 最后一次写入；为 null 即"一个字节都没写"
    query.keyword = '本次'
    await persist()
    expect(storage.read()).toBeNull()
  })
})
