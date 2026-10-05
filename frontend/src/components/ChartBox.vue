<script setup lang="ts">
/**
 * ECharts 容器（P3 管理层数据看板）
 *
 * <h2>为什么需要这层封装</h2>
 * ECharts 的生命周期有三个坑，散落在每个图表页里迟早会漏掉其中一个：
 *
 * 1. **容器尺寸为 0 时 init**：ECharts 按 0×0 建画布，之后容器变大它**不会自己恢复**
 *    —— 典型场景是图表挂在未激活的 `el-tab-pane` 里。本组件在容器拿到尺寸之前不 init，
 *    并用 `ResizeObserver` 等它出现，从根上避免「切到那个 tab 就是一片空白」。
 * 2. **不 dispose 会泄漏**：`el-tabs` 切 tab / 路由离开都会销毁 DOM，
 *    但 ECharts 实例仍挂在已脱离的 DOM 上持有 canvas 与事件监听。本组件在卸载时必定 dispose。
 * 3. **数据整体替换时旧 series 残留**：`setOption` 默认是**合并**语义，
 *    换了数据但系列数量变少时，多出来的旧系列会留在图上。因此统一用 `notMerge = true`。
 *
 * <h2>按需注册（实测有效，勿改回全量）</h2>
 * 只注册本页用到的三类图与必要组件，不做 `import * as echarts from 'echarts'`。
 * **实测对照**（同一页面构建产物）：
 * · 全量 `echarts` → 1,142.91 kB（gzip 384.82 kB）
 * · 按需（本文件写法）→ 570.85 kB（gzip 196.27 kB）—— 省 188.5 kB gzip，降幅约 49%
 * 注册是模块级副作用，多个实例共用同一张注册表，重复调用无害。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { EChartsCoreOption } from 'echarts/core'

echarts.use([
  LineChart,
  BarChart,
  PieChart,
  GridComponent,
  LegendComponent,
  TooltipComponent,
  CanvasRenderer
])

const props = withDefaults(
  defineProps<{
    /** ECharts option（整体替换语义，见上） */
    option: EChartsCoreOption
    /** 画布高度，数字按 px 处理 */
    height?: number | string
    /** 无数据时显示占位而不是空画布 */
    empty?: boolean
    emptyText?: string
  }>(),
  {
    height: 320,
    empty: false,
    emptyText: '暂无数据'
  }
)

const el = ref<HTMLDivElement | null>(null)
let chart: ReturnType<typeof echarts.init> | null = null
let observer: ResizeObserver | null = null

const heightCss = computed(() =>
  typeof props.height === 'number' ? `${props.height}px` : props.height
)

/** 容器有尺寸后才 init —— 见头部第 1 条坑 */
function ensureChart(): boolean {
  const dom = el.value
  if (!dom) {
    return false
  }
  if (chart) {
    return true
  }
  if (dom.clientWidth === 0 || dom.clientHeight === 0) {
    return false
  }
  chart = echarts.init(dom)
  return true
}

function render(): void {
  if (props.empty) {
    // 空数据时清空画布，避免停在上一次的数据上
    chart?.clear()
    return
  }
  if (!ensureChart()) {
    return
  }
  // notMerge=true：整体替换，避免旧 series 残留（见头部第 3 条坑）
  chart?.setOption(props.option, true)
}

onMounted(async () => {
  await nextTick()
  render()
  if (el.value && typeof ResizeObserver !== 'undefined') {
    observer = new ResizeObserver(() => {
      if (!chart) {
        // 首次拿到尺寸（如从隐藏的 tab 切过来）⇒ 补 init
        render()
        return
      }
      chart.resize()
    })
    observer.observe(el.value)
  }
})

watch(
  () => props.option,
  () => render(),
  { flush: 'post' }
)

watch(
  () => props.empty,
  () => render(),
  { flush: 'post' }
)

onBeforeUnmount(() => {
  observer?.disconnect()
  observer = null
  chart?.dispose()
  chart = null
})

defineExpose({
  /** 供父组件在容器尺寸突变后手动触发（例如折叠面板展开） */
  resize: () => chart?.resize()
})
</script>

<template>
  <div class="ts-chart" :style="{ height: heightCss }">
    <div ref="el" class="ts-chart__canvas"></div>
    <div v-if="empty" class="ts-chart__empty">{{ emptyText }}</div>
  </div>
</template>

<style scoped>
.ts-chart {
  position: relative;
  width: 100%;
}

.ts-chart__canvas {
  width: 100%;
  height: 100%;
}

.ts-chart__empty {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  background: var(--el-fill-color-blank);
}
</style>
