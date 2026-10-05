import { beforeEach, describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { h, nextTick } from 'vue'
import TablePage from '@/components/TablePage.vue'
import { ElTableStub, epStubs } from '@/test/stubs'
import type { ColumnDef } from '@/types/table'

/**
 * 公共表格层组件测试（-B）
 *
 * 这一层的价值全在「渲染出来的列是什么」上，所以断言集中在校**列集合与列顺序**，
 * 而不是断言某个单元格好不好看。用例分三组：
 * 1. 列渲染与插槽分派（PC）；
 * 2. 列配置弹窗的端到端效果（勾选 / 排序 / 恢复默认 / 取消不落盘 / 锁定列不可关 / 按页隔离）；
 * 3. 移动端：`#mobile` 插槽优先与通用卡片回落。
 *
 * 关于桩件：`epStubs` 里的 `ElTable`/`ElTableColumn` 用 provide/inject 复刻了
 * 「表格把行交给列、列逐行调用 `#default`」的协作关系，因此这里能真实断言每列的渲染结果。
 * `ElDialog` 仍是"始终渲染内容"的透传桩（改成按 modelValue 渲染会波及既有用例），
 * 所以弹窗用例的断言一律落在**保存后表格列的变化**与**是否落盘**上，
 * 而不是"弹窗此刻是否可见"——后者用透传桩测出来的是假结论。
 *
 * 插槽一律用 render 函数（不用模板字符串）：测试环境没有运行时模板编译器，
 * 传字符串模板只会得到一条警告 + 空插槽，断言会静默失效。
 */

const ROWS = [
  { id: 1, orderNo: 'WO-1', device: '戴尔笔记本', status: '审批中', remark: null },
  { id: 2, orderNo: 'WO-2', device: '', status: '已归还', remark: '需要复核' }
]

const COLUMNS: ColumnDef[] = [
  { key: 'orderNo', label: '工单编号', minWidth: 170 },
  { key: 'device', label: '设备', minWidth: 160 },
  { key: 'status', label: '状态', width: 100 },
  { key: 'remark', label: '备注', defaultVisible: false },
  { key: 'action', label: '操作', width: 200, configurable: false, fixed: 'right' }
]

let mobileMatches = false

beforeEach(() => {
  localStorage.clear()
  mobileMatches = false
  // happy-dom 的 matchMedia 恒为 false，这里按查询串给出可控结果，用于覆盖移动端分支
  window.matchMedia = ((query: string) => ({
    matches: query.includes('max-width: 767px') ? mobileMatches : false,
    media: query,
    onchange: null,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    addListener: () => undefined,
    removeListener: () => undefined,
    dispatchEvent: () => false
  })) as unknown as typeof window.matchMedia
})

type SlotFn = (scope: { row: object }) => unknown
type MountOptions = {
  props?: Record<string, unknown>
  slots?: Record<string, SlotFn>
}

/**
 * 挂载并**等一轮 DOM 更新**。
 *
 * `useResponsive` 在 `onMounted` 里用 `window.matchMedia` 同步断点，首帧渲染时
 * `isMobile` 还是初始的 `false`；不等这一轮就断言，测到的永远是 PC 分支 ——
 * 移动端用例会以「元素找不到」的形式假失败，而不是报出清楚的原因。
 */
async function mountTable(options: MountOptions = {}) {
  const wrapper = mount(TablePage, {
    props: {
      columns: COLUMNS,
      rows: ROWS,
      routePath: '/order/mine',
      page: 1,
      size: 10,
      total: 42,
      ...(options.props ?? {})
    },
    slots: options.slots,
    global: {
      components: epStubs,
      // v-loading 由 unplugin 在构建期注入；测试环境显式给一个空指令
      directives: { loading: {} }
    }
  })
  await nextTick()
  return wrapper
}

type TableWrapper = Awaited<ReturnType<typeof mountTable>>

/** 可点击宿主：VueWrapper / DOMWrapper 都满足 */
interface ClickableHost {
  findAll: (selector: string) => { text: () => string; trigger: (event: string) => Promise<unknown> }[]
}

/** 当前渲染出来的列 key（顺序即声明/偏好顺序） */
function renderedColumnKeys(wrapper: TableWrapper): string[] {
  return wrapper.findAll('.el-table-column').map((node) => node.attributes('data-col') ?? '')
}

function cellTexts(wrapper: TableWrapper, key: string): string[] {
  return wrapper.findAll(`.el-table-column[data-col="${key}"] .ts-cell`).map((node) => node.text())
}

function columnRow(wrapper: TableWrapper, key: string) {
  return wrapper.findAll('.ts-cols__item').find((node) => node.attributes('data-column-key') === key)
}

function buttonByText(host: ClickableHost, text: string) {
  const found = host.findAll('button').find((node) => node.text() === text)
  if (!found) {
    throw new Error(`未找到文案为「${text}」的按钮`)
  }
  return found
}

async function openColumnDialog(wrapper: TableWrapper): Promise<void> {
  await buttonByText(wrapper, '列设置').trigger('click')
  await flushPromises()
}

async function toggleColumn(wrapper: TableWrapper, key: string): Promise<void> {
  const row = columnRow(wrapper, key)
  if (!row) {
    throw new Error(`列设置里没有「${key}」这一行`)
  }
  await row.find('.el-checkbox').trigger('click')
}

async function saveDialog(wrapper: TableWrapper): Promise<void> {
  await buttonByText(wrapper, '保存').trigger('click')
  await flushPromises()
}

describe('TablePage · 列渲染与插槽分派', () => {
  it('无偏好时：只渲染默认可见列，顺序与列定义一致', async () => {
    const wrapper = await mountTable()
    expect(renderedColumnKeys(wrapper)).toEqual(['orderNo', 'device', 'status', 'action'])
  })

  it('锁定列（操作列）即便出现在偏好里也不会被隐藏', async () => {
    localStorage.setItem(
      'ticket:cols:/order/mine',
      JSON.stringify({ version: 1, order: ['orderNo', 'device', 'status', 'remark', 'action'], hidden: ['action'] })
    )
    const wrapper = await mountTable()
    expect(renderedColumnKeys(wrapper)).toContain('action')
  })

  it('提供 #cell-<key> 插槽时由插槽接管单元格渲染', async () => {
    const wrapper = await mountTable({
      slots: {
        'cell-orderNo': (scope) =>
          h('em', { class: 'x-no' }, String((scope.row as Record<string, unknown>).orderNo))
      }
    })
    expect(wrapper.findAll('.x-no').map((node) => node.text())).toEqual(['WO-1', 'WO-2'])
  })

  it('未提供插槽时回落为原值：空值与 null 展示「-」', async () => {
    const wrapper = await mountTable()
    expect(cellTexts(wrapper, 'device')).toEqual(['戴尔笔记本', '-'])
    expect(cellTexts(wrapper, 'remark')).toEqual([])
  })

  it('多选列不得被自定义插槽接管（否则 el-table 内置勾选框会被覆盖成「-」）', async () => {
    // 这是一个**实际发生过的既有缺陷**：TablePage 曾给每一列都套 `#default`，
    // 多选列因此被 `cellText` 接管 —— 勾选框消失、整列只剩一个「-」。
    // 后果是台账页勾不上、**消息中心既有的「批量删除」也一样点不动**。
    // 注意它测不出来在做单元测试：桩件无论有没有插槽都会渲染 default，
    // 是 P3 的浏览器取证（勾选框数为 0）才把它抓出来的；这条用例守住修复结果。
    const columns: ColumnDef[] = [
      { key: 'selection', label: '选择', width: 46, selection: true },
      { key: 'orderNo', label: '工单编号', minWidth: 170 },
      { key: 'remark', label: '备注' }
    ]
    const wrapper = await mountTable({ props: { columns } })

    const selectionColumn = wrapper.find('.el-table-column[data-type="selection"]')
    expect(selectionColumn.exists()).toBe(true)

    const selectionCells = selectionColumn.findAll('.ts-cell')
    expect(selectionCells.length).toBeGreaterThan(0)
    selectionCells.forEach((cell) => expect(cell.text()).toBe(''))

    // 同表的普通列仍要正常回落「-」：确认只是**精准放过**多选列，而不是整体关掉了插槽
    expect(cellTexts(wrapper, 'remark')).toEqual(['-', '需要复核'])
  })

  it('数据为空时使用 empty-text 渲染空态', async () => {
    const wrapper = await mountTable({ props: { rows: [], emptyText: '暂无待办' } })
    expect(wrapper.find('.el-empty').text()).toContain('暂无待办')
    expect(renderedColumnKeys(wrapper)).toEqual(['orderNo', 'device', 'status', 'action'])
  })

  it('分页器透传 total，并在翻页/改页长时向父组件发事件', async () => {
    const wrapper = await mountTable()
    expect(wrapper.find('.ts-pagination').attributes('data-total')).toBe('42')

    await wrapper.find('.ts-pagination__next').trigger('click')
    await wrapper.find('.ts-pagination__size').trigger('click')
    expect(wrapper.emitted('update:page')?.[0]).toEqual([2])
    expect(wrapper.emitted('update:size')?.[0]).toEqual([20])
  })

  it('可配置列 ≤1 时不渲染「列设置」入口（配置没有意义）', async () => {
    const wrapper = await mountTable({
      props: {
        columns: [
          { key: 'orderNo', label: '工单编号' },
          { key: 'action', label: '操作', configurable: false }
        ]
      }
    })
    expect(wrapper.findAll('button').map((node) => node.text())).not.toContain('列设置')
  })
})

describe('TablePage · 列配置弹窗端到端', () => {
  it('弹窗里列出全部列（含默认隐藏列），锁定列勾选框不可操作', async () => {
    const wrapper = await mountTable()
    await openColumnDialog(wrapper)

    const keys = wrapper.findAll('.ts-cols__item').map((node) => node.attributes('data-column-key') ?? '')
    expect(keys).toEqual(['orderNo', 'device', 'status', 'remark', 'action'])

    const actionRow = columnRow(wrapper, 'action')
    expect(actionRow?.find('.el-checkbox').attributes('data-disabled')).toBe('true')
    expect(actionRow?.find('.el-checkbox').attributes('data-checked')).toBe('true')
  })

  it('勾选被隐藏的列并保存 → 表格真的多出这一列，且偏好落盘', async () => {
    const wrapper = await mountTable()
    await openColumnDialog(wrapper)
    await toggleColumn(wrapper, 'remark')
    await saveDialog(wrapper)

    expect(renderedColumnKeys(wrapper)).toContain('remark')
    expect(JSON.parse(localStorage.getItem('ticket:cols:/order/mine') ?? '{}')).toMatchObject({ hidden: [] })
  })

  it('取消勾选后保存 → 该列从表格消失（勾选态被写进 hidden）', async () => {
    const wrapper = await mountTable()
    await openColumnDialog(wrapper)
    await toggleColumn(wrapper, 'device')
    await saveDialog(wrapper)

    expect(renderedColumnKeys(wrapper)).toEqual(['orderNo', 'status', 'action'])
  })

  it('「上移」改变顺序，保存后表格列序随之变化', async () => {
    const wrapper = await mountTable()
    await openColumnDialog(wrapper)

    const row = columnRow(wrapper, 'status')
    if (!row) {
      throw new Error('列设置里没有「status」这一行')
    }
    await buttonByText(row, '上移').trigger('click')
    await saveDialog(wrapper)

    expect(renderedColumnKeys(wrapper)).toEqual(['orderNo', 'status', 'device', 'action'])
  })

  it('「恢复默认」把勾选态与顺序都还原（默认隐藏列重新隐藏）', async () => {
    const wrapper = await mountTable()
    await openColumnDialog(wrapper)
    await toggleColumn(wrapper, 'remark')
    await toggleColumn(wrapper, 'status')
    await buttonByText(wrapper, '恢复默认').trigger('click')
    await saveDialog(wrapper)

    expect(renderedColumnKeys(wrapper)).toEqual(['orderNo', 'device', 'status', 'action'])
  })

  it('点「取消」不改动表格列，也不落盘', async () => {
    const wrapper = await mountTable()
    await openColumnDialog(wrapper)
    await toggleColumn(wrapper, 'status')
    await buttonByText(wrapper, '取消').trigger('click')
    await flushPromises()

    expect(renderedColumnKeys(wrapper)).toEqual(['orderNo', 'device', 'status', 'action'])
    expect(localStorage.getItem('ticket:cols:/order/mine')).toBeNull()
  })

  it('二次打开弹窗时草稿重置为当前生效配置（取消的改动不会残留）', async () => {
    const wrapper = await mountTable()
    await openColumnDialog(wrapper)
    await toggleColumn(wrapper, 'status')
    await buttonByText(wrapper, '取消').trigger('click')
    await flushPromises()

    await openColumnDialog(wrapper)
    expect(columnRow(wrapper, 'status')?.find('.el-checkbox').attributes('data-checked')).toBe('true')
  })

  it('列偏好按 routePath 隔离：换一个页面不继承上一个页面的配置', async () => {
    const first = await mountTable()
    await openColumnDialog(first)
    await toggleColumn(first, 'device')
    await saveDialog(first)
    expect(renderedColumnKeys(first)).toEqual(['orderNo', 'status', 'action'])

    const second = await mountTable({ props: { routePath: '/order/all' } })
    expect(renderedColumnKeys(second)).toEqual(['orderNo', 'device', 'status', 'action'])
  })
})

describe('TablePage · 移动端', () => {
  it('提供 #mobile 插槽时使用该插槽，通用卡片不参与渲染', async () => {
    mobileMatches = true
    const wrapper = await mountTable({
      slots: { mobile: () => h('div', { class: 'my-cards' }, '自定义卡片') }
    })
    expect(wrapper.find('.my-cards').text()).toBe('自定义卡片')
    expect(wrapper.find('.el-table').exists()).toBe(false)
    expect(wrapper.findAll('.ts-table-page__card')).toHaveLength(0)
  })

  it('无 #mobile 插槽时用列定义生成通用卡片：标题列 + 字段行 + 空值行隐藏', async () => {
    mobileMatches = true
    const wrapper = await mountTable({
      props: {
        columns: [
          { key: 'device', label: '设备', card: 'title' },
          { key: 'orderNo', label: '工单编号' },
          { key: 'remark', label: '备注', cardHideOnEmpty: true }
        ]
      }
    })

    const cards = wrapper.findAll('.ts-table-page__card')
    expect(cards).toHaveLength(2)
    expect(cards[0].find('.ts-table-page__card-title').text()).toBe('戴尔笔记本')
    expect(cards[1].find('.ts-table-page__card-title').text()).toBe('-')

    // 第 1 行备注为空 → 该行被隐藏；第 2 行有值 → 该行出现
    const firstRows = cards[0].findAll('.ts-table-page__card-row').map((node) => node.text())
    expect(firstRows.some((text) => text.includes('备注'))).toBe(false)
    const secondRows = cards[1].findAll('.ts-table-page__card-row').map((node) => node.text())
    expect(secondRows.some((text) => text.includes('备注') && text.includes('需要复核'))).toBe(true)
  })

  it('PC 下不渲染移动端容器，移动端下不渲染表格', async () => {
    const pc = await mountTable()
    expect(pc.find('.el-table').exists()).toBe(true)
    expect(pc.find('.ts-table-page__mobile').exists()).toBe(false)

    mobileMatches = true
    const mobile = await mountTable({ slots: { mobile: () => h('div', { class: 'm' }) } })
    expect(mobile.find('.el-table').exists()).toBe(false)
    expect(mobile.find('.ts-table-page__mobile').exists()).toBe(true)
  })
})

/**
 * W4-E 新增的三项能力（T1/T2 迁移的公共需求，全部以**可选、追加**方式实现）。
 *
 * 每一项都对应一个「不提供就没法迁移」的真实诉求，因此断言落在**能力有没有真的通**，
 * 而不是「属性有没有传下去」：
 * - 多选列：消息中心要批量已读/删除（`type="selection"` 没有 prop/label）；
 * - `clearSelection`：翻页后必须清空勾选 —— 事件能靠 `$attrs` 透传，**实例方法不能**；
 * - `showJumper` / `#pager-extra`：导出记录的「跳至 N 页」不能丢，
 *   消息中心的「本页未读 N 条」要有地方放。
 */
describe('TablePage · W4-E 新增能力', () => {
  it('多选列渲染为 type=selection（既没有 prop 也没有 label）', async () => {
    const wrapper = await mountTable({
      props: {
        columns: [
          { key: 'selection', label: '选择', width: 46, selection: true },
          { key: 'orderNo', label: '工单编号' }
        ]
      }
    })
    expect(renderedColumnKeys(wrapper)).toEqual(['', 'orderNo'])
    expect(wrapper.find('.el-table-column[data-type="selection"]').exists()).toBe(true)
  })

  it('showJumper 控制分页器是否带「跳至 N 页」；缺省不带（既有 6 页改造前就没有）', async () => {
    const without = await mountTable()
    expect(without.find('.ts-pagination').attributes('data-layout')).toBe('total, sizes, prev, pager, next')

    const withJumper = await mountTable({ props: { showJumper: true } })
    expect(withJumper.find('.ts-pagination').attributes('data-layout')).toBe(
      'total, sizes, prev, pager, next, jumper'
    )
  })

  it('移动端分页器只留「上一页/页码/下一页」（跳页与每页条数在窄屏没有位置）', async () => {
    mobileMatches = true
    const wrapper = await mountTable({ props: { showJumper: true }, slots: { mobile: () => h('div', { class: 'm' }) } })
    expect(wrapper.find('.ts-pagination').attributes('data-layout')).toBe('prev, pager, next')
  })

  it('#pager-extra 渲染在分页器左侧，并把分页行改为两端对齐', async () => {
    const wrapper = await mountTable({
      slots: { 'pager-extra': () => h('span', { class: 'my-extra' }, '本页未读 3 条') }
    })
    expect(wrapper.find('.my-extra').text()).toBe('本页未读 3 条')
    expect(wrapper.find('.ts-table-page__pager').classes()).toContain('ts-table-page__pager--with-extra')
  })

  it('未提供 #pager-extra 时不渲染附加区（零布局影响，既有 6 页逐像素不变）', async () => {
    const wrapper = await mountTable()
    expect(wrapper.find('.ts-table-page__pager-extra').exists()).toBe(false)
    expect(wrapper.find('.ts-table-page__pager').classes()).not.toContain('ts-table-page__pager--with-extra')
  })

  it('clearSelection 转发到内部表格（翻页后清空勾选全靠它）', async () => {
    const wrapper = await mountTable()
    // `defineExpose` 出来的方法不在组件实例的类型里（vue-tsc 只看公开实例类型），
    // 而 `vm` 在测试里的用途就是"探到暴露面"，因此这里显式收窄一次。
    const tableVm = wrapper.findComponent(ElTableStub).vm as unknown as { clearedCount: () => number }
    expect(tableVm.clearedCount()).toBe(0)

    const exposed = wrapper.vm as unknown as { clearSelection: () => void }
    exposed.clearSelection()
    expect(tableVm.clearedCount()).toBe(1)
  })

  it('移动端没有 el-table 时 clearSelection 不抛错（可选链要兜住两层：未挂载 / 无表格）', async () => {
    mobileMatches = true
    const wrapper = await mountTable({ slots: { mobile: () => h('div', { class: 'm' }) } })
    expect(wrapper.find('.el-table').exists()).toBe(false)

    const exposed = wrapper.vm as unknown as { clearSelection: () => void }
    expect(() => exposed.clearSelection()).not.toThrow()
  })
})
