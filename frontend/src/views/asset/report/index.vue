<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import type { EChartsCoreOption } from 'echarts/core'
import { reportApi } from '@/api/report'
import ChartBox from '@/components/ChartBox.vue'
import TablePage from '@/components/TablePage.vue'
import { useExport } from '@/composables/useExport'
import {
  REPORT_EXPORT_TYPE,
  REPORT_TABS,
  formatHours,
  monthOptions,
  type ApprovalEfficiencyReport,
  type DashboardOverview,
  type DeviceFaultReport,
  type DeviceUsageReport,
  type ReportKind,
  type ReportQuery
} from '@/types/report'
import type { ColumnDef } from '@/types/table'

/**
 * 统计报表（，仅 super_admin / admin）
 *
 * 三类报表：设备借用频次 / 工单审批时效 / 设备故障统计。
 * 三者共用「年 / 月」时间筛选，切换标签页按当前条件重新查询（各页数据口径独立）。
 *
 * 为什么不做前端聚合：报表口径（借用频次按 order_type='BORROW' 去重、审批耗时按节点
 * 平均、故障按月分组）全部由后端 SQL 计算，前端只渲染结果。把口径放到前端意味着
 * 「页面看到的数」与「导出的 Excel 里的数」可能由两套逻辑算出而不一致 —— 这是报表最忌讳的。
 * 导出同样走后端统一导出入口（REPORT_* 类型），与页面共用同一份聚合 SQL，天然对齐。
 *
 * 图表：**按需引入 ECharts**（P3 管理层数据看板）—— 只注册折线 / 柱状 / 环形三类图与必要组件
 * （`echarts/core` + `use`），**不做** `import * as echarts from 'echarts'` 的全量引入。
 * 顶部「管理概览」用图表呈现；下方三类明细报表保持「统计卡 + 明细表」—— 明细要的是可读、可导出、
 * 可逐行核对，不是画得好看。容器封装见 `components/ChartBox.vue`（含 0 尺寸 init / dispose / resize）。
 *
 * ---
 *  · W4-E：**T2 档** —— 只迁 `TablePage` 骨架，**不接筛选持久化**
 * （年/月筛选是「每次看不同的月份」，记住它只会让人忘记自己已经缩过范围）。
 *
 * 本页有 **5 个内嵌小表**（借用频次 2 个 + 审批明细 1 个 + 故障 2 个），全部走同一个骨架：
 * 不分页（`:show-pagination="false"`，报表一次给全）、不启用列设置（列由后端聚合口径决定）。
 *
 * 两点刻意的处理：
 * 1. **`loading` 挂在外层 div 上，不传给 TablePage**。报表页是「一次筛选、五张表同时刷新」，
 *    外层一层遮罩最直观；若每张表各挂一层，会同时出现五个互相独立的 loading 层叠。
 * 2. **移动端走通用卡片**（改造前在窄屏是横向滚动表格， 要求转卡片）。
 *    5 张表的卡片标题列分别是 设备名 / 分类名 / 工单号 / 设备名 / 月份。
 *    `routePath` 逐个区分，避免 5 张表的列偏好键互相覆盖（本页不启用列设置，但键仍需唯一）。
 * 3. **模板内不写对象类型字面量断言**。全库其它页面在模板里用的是 `row as XxxItem`（具名类型，
 *    解析器认），但 `row as { key?: T | null }` 这种**内联对象类型**会让模板表达式解析器直接报
 *    `TS1109 Expression expected` —— 本轮就是这么撞上的。
 *    `primaryCategoryName` / `applicantName` / `deviceName` / `submittedAt` / `approvedAt`
 *    这 5 列的插槽体是 `|| '-'`，与 `TablePage` 的回落展示**逐字等价**，故直接不写插槽；
 *    只有 `categoryName`（空值显示「未分类」）、`hours`（`formatHours` 格式化）、
 *    `overtime`（标签）三列需要 `#cell-<key>`，且都用**直接属性访问**（这些 key 在
 *    `types/report.ts` 里都声明过，断言纯属多余）。
 */

const { loading: exporting, runExport } = useExport()

const activeTab = ref<ReportKind>('device-usage')

const filter = reactive<{ year: number | null; month: number | null }>({
  year: new Date().getFullYear(),
  month: null
})

/** 年份下拉：近 6 年（含当年），足够覆盖常规查询范围 */
const yearOptions = computed(() => {
  const current = new Date().getFullYear()
  return Array.from({ length: 6 }, (_, index) => current - index)
})

const monthList = monthOptions()

const loading = ref(false)
const deviceUsage = ref<DeviceUsageReport | null>(null)
const approvalEfficiency = ref<ApprovalEfficiencyReport | null>(null)
const deviceFault = ref<DeviceFaultReport | null>(null)
const overview = ref<DashboardOverview | null>(null)
const overviewLoading = ref(false)

// --- 5 张内嵌小表的列定义（逐列照抄改造前 el-table-column） ---
const usageByDeviceColumns: ColumnDef[] = [
  { key: 'deviceName', label: '设备名称', minWidth: 160, showOverflowTooltip: true, card: 'title' },
  { key: 'assetNo', label: '资产编号', minWidth: 130 },
  { key: 'primaryCategoryName', label: '一级分类', minWidth: 120 },
  { key: 'borrowCount', label: '借用次数', minWidth: 100, align: 'right', sortable: true }
]

const usageByCategoryColumns: ColumnDef[] = [
  { key: 'categoryName', label: '一级分类', minWidth: 160, card: 'title' },
  { key: 'borrowCount', label: '借用次数', minWidth: 120, align: 'right', sortable: true },
  { key: 'deviceCount', label: '涉及设备数', minWidth: 120, align: 'right' }
]

const approvalItemColumns: ColumnDef[] = [
  { key: 'orderNo', label: '工单编号', minWidth: 170, showOverflowTooltip: true, card: 'title' },
  { key: 'applicantName', label: '申请人', minWidth: 110 },
  { key: 'deviceName', label: '设备', minWidth: 150, showOverflowTooltip: true },
  { key: 'submittedAt', label: '提交时间', minWidth: 170 },
  { key: 'approvedAt', label: '审批完成时间', minWidth: 170 },
  { key: 'hours', label: '审批耗时', minWidth: 120, align: 'right' },
  { key: 'overtime', label: '是否超时', minWidth: 110, align: 'center' }
]

const faultByDeviceColumns: ColumnDef[] = [
  { key: 'deviceName', label: '设备名称', minWidth: 160, showOverflowTooltip: true, card: 'title' },
  { key: 'assetNo', label: '资产编号', minWidth: 130 },
  { key: 'primaryCategoryName', label: '一级分类', minWidth: 120 },
  { key: 'faultCount', label: '故障次数', minWidth: 100, align: 'right', sortable: true },
  { key: 'pendingCount', label: '待维修', minWidth: 100, align: 'right' }
]

const faultByMonthColumns: ColumnDef[] = [
  { key: 'month', label: '月份', minWidth: 120, card: 'title' },
  { key: 'faultCount', label: '故障次数', minWidth: 120, align: 'right', sortable: true }
]

/** 月份未选年份时按当年处理，避免出现「只选月份」这种后端无法定位区间的组合 */
function currentQuery(): ReportQuery {
  const query: ReportQuery = {}
  if (filter.month != null) {
    query.year = filter.year ?? new Date().getFullYear()
    query.month = filter.month
  } else if (filter.year != null) {
    query.year = filter.year
  }
  return query
}

/**
 * 概览的区间：**未显式选月份时按当前月**。
 *
 * 与明细报表的「不选月份 = 整年」刻意不同：概览第一张卡的文案是「本月借出」，
 * 若跟随明细的整年口径，就会出现「卡片写着本月、数字却是全年」的自相矛盾。
 * 用户显式选了月份则完全按所选（年份也一并遵循）。
 */
function overviewQuery(): ReportQuery {
  if (filter.year != null && filter.month != null) {
    return { year: filter.year, month: filter.month }
  }
  const now = new Date()
  return { year: now.getFullYear(), month: now.getMonth() + 1 }
}

async function loadCurrent(): Promise<void> {
  loading.value = true
  const query = currentQuery()
  try {
    switch (activeTab.value) {
      case 'device-usage':
        deviceUsage.value = await reportApi.deviceUsage(query)
        break
      case 'approval-efficiency':
        approvalEfficiency.value = await reportApi.approvalEfficiency(query)
        break
      case 'device-fault':
        deviceFault.value = await reportApi.deviceFault(query)
        break
    }
  } catch {
    // 请求层已统一提示；保留上一次结果，避免筛选失败时页面清空造成误判
  } finally {
    loading.value = false
  }
}

/**
 * 管理概览（P3）：只在**筛选变化**时刷新，不随 tab 切换重复请求 ——
 * 概览的「逾期 / 设备利用率」是当前状态、与 tab 无关，切 tab 重拉只会让顶部图表白闪一下。
 */
async function loadOverview(): Promise<void> {
  overviewLoading.value = true
  try {
    overview.value = await reportApi.overview(overviewQuery())
  } catch {
    // 请求层已统一提示；保留上一次概览，避免筛选失败时整页数字清空造成误判
  } finally {
    overviewLoading.value = false
  }
}

function handleTabChange(): void {
  void loadCurrent()
}

function handleQuery(): void {
  void Promise.all([loadOverview(), loadCurrent()])
}

function handleReset(): void {
  filter.year = new Date().getFullYear()
  filter.month = null
  void Promise.all([loadOverview(), loadCurrent()])
}

/** 导出当前报表（与页面同口径：同一份 ReportQuery + 对应 REPORT_* 导出类型） */
async function handleExport(): Promise<void> {
  const type = REPORT_EXPORT_TYPE[activeTab.value]
  const label = REPORT_TABS.find((tab) => tab.name === activeTab.value)?.label ?? '统计报表'
  await runExport({ type, ...currentQuery() }, `${label}.xlsx`)
}

/** 审批时效超时占比（0-100 的整数），用于统计卡副文案 */
const overtimeRate = computed(() => {
  const report = approvalEfficiency.value
  if (!report || report.approvedOrders <= 0) {
    return 0
  }
  return Math.round((report.overtimeOrders / report.approvedOrders) * 100)
})

// ------------------------------------------------------------------
// 管理概览的图表 option（由 ChartBox 消费）
// ------------------------------------------------------------------

/** 借出趋势：折线（区间落到月时按天、只给年时按月，分桶由后端决定） */
const trendOption = computed<EChartsCoreOption>(() => ({
  grid: { left: 8, right: 16, top: 20, bottom: 4, containLabel: true },
  tooltip: { trigger: 'axis' },
  xAxis: {
    type: 'category',
    data: (overview.value?.trend ?? []).map((item) => item.bucket),
    axisLabel: { fontSize: 11, hideOverlap: true }
  },
  yAxis: { type: 'value', minInterval: 1 },
  series: [
    {
      name: '借出',
      type: 'line',
      smooth: true,
      symbolSize: 6,
      areaStyle: { opacity: 0.12 },
      data: (overview.value?.trend ?? []).map((item) => item.count)
    }
  ]
}))

/** 设备状态分布：环形图。value 为 0 的状态不画（否则图例里会出现一串 0% 的干扰项） */
const statusOption = computed<EChartsCoreOption>(() => {
  const data = [
    { name: '可用', value: overview.value?.deviceAvailableCount ?? 0 },
    { name: '使用中', value: overview.value?.deviceInUseCount ?? 0 },
    { name: '审批中', value: overview.value?.deviceInApprovalCount ?? 0 },
    { name: '维修中', value: overview.value?.deviceMaintenanceCount ?? 0 },
    { name: '已丢失', value: overview.value?.deviceLostCount ?? 0 },
    { name: '已报废', value: overview.value?.deviceScrappedCount ?? 0 }
  ].filter((item) => item.value > 0)
  return {
    tooltip: { trigger: 'item', formatter: '{b}：{c} 台（{d}%）' },
    legend: { bottom: 0, itemWidth: 10, itemHeight: 10, textStyle: { fontSize: 11 } },
    series: [
      {
        type: 'pie',
        radius: ['42%', '64%'],
        center: ['50%', '42%'],
        avoidLabelOverlap: true,
        label: { show: false },
        data
      }
    ]
  }
})

/** 部门借用排行：横向柱状。反转让借用最多的排在最上面（横向柱状图的视觉直觉是自上而下递减） */
const deptOption = computed<EChartsCoreOption>(() => {
  const items = [...(overview.value?.departmentRanking ?? [])].reverse()
  return {
    grid: { left: 8, right: 28, top: 12, bottom: 4, containLabel: true },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    xAxis: { type: 'value', minInterval: 1 },
    yAxis: {
      type: 'category',
      data: items.map((item) => item.departmentName),
      axisLabel: { fontSize: 11 }
    },
    series: [{ type: 'bar', barMaxWidth: 18, data: items.map((item) => item.borrowCount) }]
  }
})

/** 三个图的空态判据（分别判断，避免「有部门但没趋势」时整块被当成空） */
const trendEmpty = computed(() => (overview.value?.trend ?? []).length === 0)
const statusEmpty = computed(() => (overview.value?.deviceTotal ?? 0) === 0)
const deptEmpty = computed(() => (overview.value?.departmentRanking ?? []).length === 0)

onMounted(() => {
  void Promise.all([loadOverview(), loadCurrent()])
})
</script>

<template>
  <div class="ts-page">
    <section class="ts-card">
      <div class="ts-flex-between ts-rpt__head">
        <div>
          <h3 class="ts-rpt__title">统计报表</h3>
          <p class="ts-text-secondary ts-rpt__desc">
            借用频次 / 审批时效 / 故障统计三类报表，数据由后端聚合计算，导出与页面同口径。
          </p>
        </div>
        <el-button type="primary" :loading="exporting" @click="handleExport">导出当前报表</el-button>
      </div>

      <!-- 时间筛选（三类报表共用） -->
      <div class="ts-rpt__filters ts-mt-16">
        <el-select v-model="filter.year" class="ts-rpt__filter-item" placeholder="年份" clearable @change="handleQuery">
          <el-option v-for="year in yearOptions" :key="year" :label="`${year} 年`" :value="year" />
        </el-select>
        <el-select v-model="filter.month" class="ts-rpt__filter-item" placeholder="全部月份" clearable @change="handleQuery">
          <el-option v-for="item in monthList" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <div class="ts-rpt__filter-actions">
          <el-button type="primary" :loading="loading" @click="handleQuery">查询</el-button>
          <el-button @click="handleReset">重置</el-button>
        </div>
        <span class="ts-text-hint">不选月份统计整年；都不选统计全部历史数据</span>
      </div>

      <!-- 管理概览（P3）：4 个 KPI + 三类图。逾期与利用率标注「当前」以免被误读成区间值 -->
      <section v-loading="overviewLoading" class="ts-rpt__overview ts-mt-16">
        <div class="ts-rpt__overview-head">
          <h4 class="ts-rpt__overview-title">管理概览</h4>
          <span class="ts-text-hint">
            「借出 / 部门排行」随上方筛选（未选月份时按<b>当前月</b>）；「逾期 / 设备利用率」是<b>当前</b>状态，不受筛选影响
          </span>
        </div>

        <!-- KPI 卡沿用本页既有结构（ts-text-hint 标签 + ts-rpt__stat-value 值），保证与下方明细页视觉一致 -->
        <div class="ts-rpt__stats">
          <div class="ts-rpt__stat">
            <span class="ts-text-hint">{{ overview?.rangeLabel ?? '—' }} 借出</span>
            <strong class="ts-rpt__stat-value">{{ overview?.monthlyBorrowCount ?? '—' }}</strong>
            <span class="ts-rpt__stat-sub">
              涉及 {{ overview?.monthlyBorrowDeviceCount ?? 0 }} 台设备
            </span>
          </div>
          <div class="ts-rpt__stat">
            <span class="ts-text-hint">逾期（当前）</span>
            <strong class="ts-rpt__stat-value ts-rpt__stat-danger">
              {{ overview?.overdueTimeoutCount ?? '—' }}
            </strong>
            <span class="ts-rpt__stat-sub">
              计划已过 {{ overview?.overdueGraceCount ?? 0 }} 单（含顺延宽限期内）
            </span>
          </div>
          <div class="ts-rpt__stat">
            <span class="ts-text-hint">设备利用率（当前）</span>
            <strong class="ts-rpt__stat-value">
              {{ overview ? overview.utilizationRate + '%' : '—' }}
            </strong>
            <span class="ts-rpt__stat-sub">
              在用 + 审批中 {{ overview?.utilizationNumerator ?? 0 }} / 可调度
              {{ overview?.utilizationDenominator ?? 0 }}
            </span>
          </div>
          <div class="ts-rpt__stat">
            <span class="ts-text-hint">设备总数</span>
            <strong class="ts-rpt__stat-value">{{ overview?.deviceTotal ?? '—' }}</strong>
            <span class="ts-rpt__stat-sub">
              维修中 {{ overview?.deviceMaintenanceCount ?? 0 }} · 已丢失
              {{ overview?.deviceLostCount ?? 0 }}
            </span>
          </div>
        </div>

        <div class="ts-rpt__charts">
          <div class="ts-rpt__chart ts-rpt__chart--wide">
            <div class="ts-rpt__chart-title">借出趋势（{{ overview?.rangeLabel ?? '—' }}）</div>
            <ChartBox :option="trendOption" :height="240" :empty="trendEmpty" />
          </div>
          <div class="ts-rpt__chart">
            <div class="ts-rpt__chart-title">设备状态分布</div>
            <ChartBox :option="statusOption" :height="280" :empty="statusEmpty" />
          </div>
          <div class="ts-rpt__chart">
            <div class="ts-rpt__chart-title">部门借用排行 Top 10</div>
            <ChartBox :option="deptOption" :height="280" :empty="deptEmpty" />
          </div>
        </div>
      </section>

      <el-tabs v-model="activeTab" class="ts-mt-16" @tab-change="handleTabChange">
        <!-- 设备借用频次 -->
        <el-tab-pane :label="REPORT_TABS[0].label" :name="REPORT_TABS[0].name">
          <div v-loading="loading">
            <div class="ts-rpt__stats">
              <div class="ts-rpt__stat">
                <span class="ts-text-hint">借用工单总数</span>
                <strong class="ts-rpt__stat-value">{{ deviceUsage?.totalOrders ?? '-' }}</strong>
              </div>
              <div class="ts-rpt__stat">
                <span class="ts-text-hint">涉及设备数</span>
                <strong class="ts-rpt__stat-value">{{ deviceUsage?.deviceCount ?? '-' }}</strong>
              </div>
            </div>

            <h4 class="ts-rpt__section">按设备</h4>
            <TablePage
              :columns="usageByDeviceColumns"
              :rows="deviceUsage?.byDevice ?? []"
              route-path="/asset/report/usage-by-device"
              :show-pagination="false"
              :show-column-config="false"
              empty-text="暂无借用记录"
            />

            <h4 class="ts-rpt__section">按分类</h4>
            <TablePage
              :columns="usageByCategoryColumns"
              :rows="deviceUsage?.byCategory ?? []"
              route-path="/asset/report/usage-by-category"
              :show-pagination="false"
              :show-column-config="false"
              empty-text="暂无借用记录"
            >
              <template #cell-categoryName="{ row }">
                {{ row.categoryName || '未分类' }}
              </template>
            </TablePage>
          </div>
        </el-tab-pane>

        <!-- 工单审批时效 -->
        <el-tab-pane :label="REPORT_TABS[1].label" :name="REPORT_TABS[1].name">
          <div v-loading="loading">
            <div class="ts-rpt__stats">
              <div class="ts-rpt__stat">
                <span class="ts-text-hint">提交工单数</span>
                <strong class="ts-rpt__stat-value">{{ approvalEfficiency?.submittedOrders ?? '-' }}</strong>
              </div>
              <div class="ts-rpt__stat">
                <span class="ts-text-hint">已审批完成</span>
                <strong class="ts-rpt__stat-value">{{ approvalEfficiency?.approvedOrders ?? '-' }}</strong>
              </div>
              <div class="ts-rpt__stat">
                <span class="ts-text-hint">平均审批耗时</span>
                <strong class="ts-rpt__stat-value">
                  {{ approvalEfficiency ? formatHours(approvalEfficiency.avgApprovalHours) : '-' }}
                </strong>
              </div>
              <div class="ts-rpt__stat">
                <span class="ts-text-hint">超时审批工单</span>
                <strong class="ts-rpt__stat-value">
                  {{ approvalEfficiency?.overtimeOrders ?? '-' }}
                  <span class="ts-text-hint">（{{ overtimeRate }}%）</span>
                </strong>
              </div>
              <div class="ts-rpt__stat">
                <span class="ts-text-hint">当前待审批节点</span>
                <strong class="ts-rpt__stat-value">
                  {{ approvalEfficiency?.pendingNodes ?? '-' }}
                  <span
                    v-if="approvalEfficiency && approvalEfficiency.pendingOverdueNodes > 0"
                    class="ts-rpt__stat-danger"
                  >
                    （其中 {{ approvalEfficiency.pendingOverdueNodes }} 个已超时）
                  </span>
                </strong>
              </div>
            </div>
            <p class="ts-text-hint ts-rpt__note">
              超时口径：审批耗时超过 {{ approvalEfficiency?.timeoutThresholdHours ?? '-' }} 小时（取自系统超时参数）。
            </p>

            <h4 class="ts-rpt__section">审批明细</h4>
            <TablePage
              :columns="approvalItemColumns"
              :rows="approvalEfficiency?.items ?? []"
              route-path="/asset/report/approval-items"
              :show-pagination="false"
              :show-column-config="false"
              empty-text="暂无审批记录"
            >
              <template #cell-hours="{ row }">{{ formatHours(row.hours) }}</template>
              <template #cell-overtime="{ row }">
                <el-tag v-if="row.overtime" type="danger" size="small" effect="plain">超时</el-tag>
                <el-tag v-else-if="row.hours != null" type="success" size="small" effect="plain">正常</el-tag>
                <span v-else class="ts-text-hint">-</span>
              </template>
            </TablePage>
          </div>
        </el-tab-pane>

        <!-- 设备故障统计 -->
        <el-tab-pane :label="REPORT_TABS[2].label" :name="REPORT_TABS[2].name">
          <div v-loading="loading">
            <div class="ts-rpt__stats">
              <div class="ts-rpt__stat">
                <span class="ts-text-hint">故障总数</span>
                <strong class="ts-rpt__stat-value">{{ deviceFault?.totalFaults ?? '-' }}</strong>
              </div>
              <div class="ts-rpt__stat">
                <span class="ts-text-hint">待维修</span>
                <strong class="ts-rpt__stat-value">{{ deviceFault?.pendingRepair ?? '-' }}</strong>
              </div>
              <div class="ts-rpt__stat">
                <span class="ts-text-hint">已修复</span>
                <strong class="ts-rpt__stat-value">{{ deviceFault?.repaired ?? '-' }}</strong>
              </div>
              <div class="ts-rpt__stat">
                <span class="ts-text-hint">已报废</span>
                <strong class="ts-rpt__stat-value">{{ deviceFault?.scrapped ?? '-' }}</strong>
              </div>
            </div>

            <h4 class="ts-rpt__section">按设备</h4>
            <TablePage
              :columns="faultByDeviceColumns"
              :rows="deviceFault?.byDevice ?? []"
              route-path="/asset/report/fault-by-device"
              :show-pagination="false"
              :show-column-config="false"
              empty-text="暂无故障记录"
            />

            <h4 class="ts-rpt__section">按月分布</h4>
            <TablePage
              :columns="faultByMonthColumns"
              :rows="deviceFault?.byMonth ?? []"
              route-path="/asset/report/fault-by-month"
              :show-pagination="false"
              :show-column-config="false"
              empty-text="暂无故障记录"
            />
          </div>
        </el-tab-pane>
      </el-tabs>
    </section>
  </div>
</template>

<style scoped>
/* 管理概览（P3）：KPI 卡沿用本页既有 .ts-rpt__stat 样式，这里只补标题与图表网格 */
.ts-rpt__overview-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 10px;
}

.ts-rpt__overview-title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}

.ts-rpt__charts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
  margin-top: 4px;
}

/* 趋势图占满整行：它是唯一有横轴刻度密集问题的图，给足宽度才不至于标签互相压住 */
.ts-rpt__chart--wide {
  grid-column: 1 / -1;
}

.ts-rpt__chart-title {
  margin-bottom: 6px;
  font-size: 13px;
  color: var(--el-text-color-regular);
}

@media (max-width: 767px) {
  .ts-rpt__charts {
    grid-template-columns: minmax(0, 1fr);
  }
}
.ts-rpt__head {
  gap: 16px;
  align-items: flex-start;
}

.ts-rpt__title {
  margin: 0;
  font-size: 16px;
  font-weight: 500;
}

.ts-rpt__desc {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.6;
}

.ts-rpt__filters {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.ts-rpt__filter-item {
  width: 150px;
}

.ts-rpt__filter-actions {
  display: flex;
  gap: 8px;
}

.ts-rpt__stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.ts-rpt__stat {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px 14px;
  background: #f7f9fc;
  border-radius: 8px;
}

.ts-rpt__stat-sub {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.4;
}

.ts-rpt__stat-value {
  font-size: 20px;
  font-weight: 600;
  color: var(--ts-text-primary, #303133);
}

.ts-rpt__stat-danger {
  font-size: 12px;
  font-weight: 400;
  color: var(--el-color-danger);
}

.ts-rpt__section {
  margin: 16px 0 8px;
  font-size: 14px;
  font-weight: 500;
}

.ts-rpt__note {
  margin: 0;
  font-size: 12px;
}

@media (max-width: 767px) {
  .ts-rpt__head {
    flex-direction: column;
  }

  .ts-rpt__head .el-button {
    width: 100%;
  }

  .ts-rpt__filter-item {
    width: 100%;
  }

  .ts-rpt__filter-actions .el-button {
    flex: 1 1 0;
  }
}
</style>
