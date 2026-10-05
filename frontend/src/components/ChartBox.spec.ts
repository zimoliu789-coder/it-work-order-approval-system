import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import type { EChartsCoreOption } from 'echarts/core'

/**
 * ChartBox 组件级测试（P3 管理层数据看板）
 *
 * <h2>为什么这几条必须单测</h2>
 * ECharts 的三类问题都**只在组件生命周期里才暴露**，纯函数测不到，而且它们在浏览器里
 * 表现为「图上什么都没有」这种**不会报错**的形态，靠人工看很容易当成「数据没查到」：
 *
 * 1. **容器尺寸为 0 时 init** —— ECharts 按 0×0 建画布，之后容器变大**它不会自己恢复**。
 *    图表挂在未激活的 `el-tab-pane` 里就是这个场景。
 * 2. **卸载不 dispose** —— 实例仍挂在已脱离的 DOM 上持有 canvas 与监听，切换 tab/路由会持续泄漏。
 * 3. **`setOption` 默认是合并语义** —— 换数据后系列变少时旧系列会留在图上，
 *    所以必须传 `notMerge = true`（本组件统一如此，用测试钉住）。
 *
 * ECharts 本体被 mock：这里要验的是**我们的生命周期管理**，不是 ECharts 自己画得对不对。
 */
const mocks = vi.hoisted(() => {
  const setOption = vi.fn()
  const resize = vi.fn()
  const dispose = vi.fn()
  const clear = vi.fn()
  const init = vi.fn(() => ({ setOption, resize, dispose, clear }))
  return { setOption, resize, dispose, clear, init }
})

vi.mock('echarts/core', () => ({ use: vi.fn(), init: mocks.init }))
vi.mock('echarts/charts', () => ({ BarChart: {}, LineChart: {}, PieChart: {} }))
vi.mock('echarts/components', () => ({ GridComponent: {}, LegendComponent: {}, TooltipComponent: {} }))
vi.mock('echarts/renderers', () => ({ CanvasRenderer: {} }))

import ChartBox from '@/components/ChartBox.vue'

/** happy-dom 下元素没有布局，clientWidth/Height 恒为 0 ⇒ 手动给定尺寸来驱动「有尺寸才 init」的分支 */
function setContainerSize(width: number, height: number): void {
  Object.defineProperty(HTMLElement.prototype, 'clientWidth', {
    configurable: true,
    get: () => width
  })
  Object.defineProperty(HTMLElement.prototype, 'clientHeight', {
    configurable: true,
    get: () => height
  })
}

function optionOf(value: unknown): EChartsCoreOption {
  return value as EChartsCoreOption
}

describe('ChartBox（ECharts 容器）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setContainerSize(400, 300)
  })

  it('容器有尺寸时初始化图表，并以「整体替换」语义应用 option', async () => {
    mount(ChartBox, { props: { option: optionOf({ series: [] }) } })
    await flushPromises()

    expect(mocks.init).toHaveBeenCalledTimes(1)
    // 第二个参数 true = notMerge，防止换数据后旧 series 残留
    expect(mocks.setOption).toHaveBeenCalledWith({ series: [] }, true)
  })

  it('容器尺寸为 0 时不初始化（避免 0×0 画布：切到该 tab 会一片空白且不会自愈）', async () => {
    setContainerSize(0, 0)

    mount(ChartBox, { props: { option: optionOf({}) } })
    await flushPromises()

    expect(mocks.init).not.toHaveBeenCalled()
  })

  it('卸载时必须 dispose（否则实例挂在脱离的 DOM 上，切 tab / 离开路由会持续泄漏）', async () => {
    const wrapper = mount(ChartBox, { props: { option: optionOf({}) } })
    await flushPromises()

    wrapper.unmount()

    expect(mocks.dispose).toHaveBeenCalledTimes(1)
  })

  it('option 变化时重新应用（同一次挂载内不重复 init）', async () => {
    const wrapper = mount(ChartBox, { props: { option: optionOf({ a: 1 }) } })
    await flushPromises()
    mocks.setOption.mockClear()

    await wrapper.setProps({ option: optionOf({ a: 2 }) })
    await nextTick()
    await nextTick()

    expect(mocks.setOption).toHaveBeenCalledWith({ a: 2 }, true)
    expect(mocks.init).toHaveBeenCalledTimes(1)
  })

  it('empty=true 时清空画布而不是画上一次的数据', async () => {
    const wrapper = mount(ChartBox, { props: { option: optionOf({ series: [1] }) } })
    await flushPromises()
    mocks.setOption.mockClear()

    await wrapper.setProps({ empty: true })
    await nextTick()
    await nextTick()

    expect(mocks.clear).toHaveBeenCalled()
    expect(mocks.setOption).not.toHaveBeenCalled()
    // 占位文案要真的渲染出来，否则用户看到的是一个空白盒子
    expect(wrapper.text()).toContain('暂无数据')
  })

  it('emptyText 可自定义', async () => {
    const wrapper = mount(ChartBox, {
      props: { option: optionOf({}), empty: true, emptyText: '该区间没有借出记录' }
    })
    await flushPromises()

    expect(wrapper.text()).toContain('该区间没有借出记录')
  })
})
