<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/store/user'
import { orderApi } from '@/api/order'
import { deviceApi } from '@/api/device'
import { messageApi } from '@/api/message'
import { dashboardApi } from '@/api/dashboard'
import authApi from '@/api/auth'
import { MOCK_ENABLED } from '@/mock'
import type { HealthInfo } from '@/types/api'
import {
  DASHBOARD_GRANULARITY_DAY,
  GRANULARITY_OPTIONS,
  approvalSampleHint,
  barWidthPercent,
  formatDashboardHours,
  formatDashboardRate,
  maxBucketCount,
  monthSuggestion,
  overdueSampleHint
} from '@/types/dashboard'
import type { DashboardGranularity, DashboardSummary } from '@/types/dashboard'
import type { OrderItem } from '@/types/order'

/**
 * 工作台（需求方三波·第二波·「工作台聚合」； 菜单结构）
 *
 * 改造前是  信息页（只有「已交付能力」文案 + 后端连通状态），对日常使用毫无帮助。
 * 现聚合为<b>真实业务概览</b>：
 * - 与我相关的待办（我的申请 / 待我审批 / 待我处理 / 未读消息），点击直达对应页面；
 * - 管理员额外看到全局指标（全部工单 / 设备状态分布）；
 * - 常用入口按权限渲染（无权限的入口不出现）。
 *
 * 取数策略：全部用「分页接口取 size=1 读 total」——复用既有接口，不新增专用聚合接口；
 * 用 Promise.allSettled 并发且互不阻塞，任一接口失败只让对应数字显示为「-」，不影响整页。
 */
const router = useRouter()
const userStore = useUserStore()

const isAdmin = computed(() => userStore.role === 'super_admin' || userStore.role === 'admin')

// 工作台的角色文案带「数据范围」说明，与列表页的短标签不同，故此处**刻意**独立维护。
// 但它必须覆盖全部内置角色：漏一个会让该角色的用户在工作台看到裸角色码。
const ROLE_LABELS: Record<string, string> = {
  super_admin: '超级管理员（全部权限）',
  admin: '管理员（设备台账、全部工单、报表、使用记录）',
  it_manager: 'IT主管（审批发设备、指定执行人）',
  it_executor: 'IT执行人（发放与回收设备）',
  dept_manager: '部门经理/组长（本部门一级审批）',
  user: '普通员工（提交申请、我的工单、待办）'
}
const DATA_SCOPE_LABELS: Record<string, string> = {
  ALL: '全部数据',
  GROUP: '本部门',
  SELF: '仅本人'
}

const roleLabel = computed(() => ROLE_LABELS[userStore.role] ?? userStore.role)
const departmentText = computed(() => userStore.user?.departmentName ?? '未分配部门')
const dataScopeText = computed(() => DATA_SCOPE_LABELS[userStore.dataScope] ?? userStore.dataScope)

const loading = ref(true)
const stat = ref<Record<string, number | null>>({
  approval: null,
  handling: null,
  borrowed: null,
  dueSoon: null,
  unread: null,
  allOrders: null,
  deviceAvailable: null,
  deviceInUse: null,
  deviceMaintenance: null
})

interface StatCard {
  key: string
  label: string
  path: string
  tip?: string
}

/**
 * 与我相关的卡片（全部角色）。
 *
 * P1 起把「我的申请」换成「我的借用 / 即将到期」：打开首页最该先看到的是
 * 「有什么要我做」和「有什么要我还」。而「我一共申请过多少单」是个纯计数 ——
 * 点进去随时能看，却不提示任何需要现在动手的事。
 */
const personalCards = computed<StatCard[]>(() => [
  { key: 'approval', label: '待我审批', path: '/order/approval' },
  { key: 'handling', label: '待我处理', path: '/order/pending' },
  { key: 'borrowed', label: '我的借用', path: '/order/mine' },
  { key: 'dueSoon', label: '即将到期', path: '/order/mine' },
  { key: 'unread', label: '未读消息', path: '/message/list' }
])

/** 管理员全局卡片 */
const adminCards = computed<StatCard[]>(() =>
  isAdmin.value
    ? [
        { key: 'allOrders', label: '全部工单', path: '/order/all' },
        { key: 'deviceAvailable', label: '可用设备', path: '/asset/ledger' },
        { key: 'deviceInUse', label: '使用中设备', path: '/asset/ledger' },
        { key: 'deviceMaintenance', label: '维修中设备', path: '/asset/ledger' }
      ]
    : []
)

/** 常用入口：按权限码过滤，无权限的入口不出现 */
const allShortcuts = [
  // 扫码借还（P1）：按项目既有约定**不挂左侧菜单**（站在设备前才会用，挂在菜单里只是噪音），
  // 所以首页快捷入口就是它的主要入口 —— 必须显眼、默认可见（perm 为空 = 人人可见）。
  { title: '扫码借还', path: '/scan', perm: '' },
  { title: '提交申请', path: '/order/apply', perm: 'order:apply' },
  { title: '我发起的申请', path: '/order/mine', perm: 'order:mine' },
  { title: '待我审批', path: '/order/approval', perm: 'order:approval' },
  { title: '全部工单', path: '/order/all', perm: 'order:all:view' },
  { title: '设备台账', path: '/asset/ledger', perm: 'device:ledger:view' },
  { title: '设备故障记录', path: '/asset/fault', perm: 'device:fault:view' },
  { title: '统计报表', path: '/asset/report', perm: 'asset:report:view' },
  // 流程监控（ · M7）：与报表同属"看数据"的入口，放在统计报表之后
  { title: '流程监控', path: '/system/flow-monitor', perm: 'flow_monitor:view' },
  { title: '使用记录', path: '/usage/records', perm: 'usage:view' },
  { title: '组织与人员', path: '/staff/organization', perm: 'staff:view' },
  { title: '系统参数', path: '/system/config', perm: 'config:view' },
  { title: '角色与权限', path: '/system/role', perm: 'role:view' },
  { title: '操作日志', path: '/system/logs', perm: 'log:view' },
  { title: '导出记录', path: '/export/records', perm: '' }
]
const shortcuts = computed(() => allShortcuts.filter((item) => !item.perm || userStore.hasPerm(item.perm)))

async function safeCount(fn: () => Promise<number>): Promise<number | null> {
  try {
    return await fn()
  } catch {
    return null
  }
}

/**
 * 待我审批在首页直接展示的条数。
 *
 * 5 是「一眼扫得完」与「不用跳页就能清掉大部分待办」之间的折中：
 * 再多首页就变成第二个列表页，再少则几乎每次都要跳页。
 */
const APPROVAL_INLINE_LIMIT = 5

/** 首页内联展示的待审批工单（前 APPROVAL_INLINE_LIMIT 条） */
const approvalOrders = ref<OrderItem[]>([])
const approvalTotal = ref(0)
/** 正在提交的工单 id：既做按钮 loading，也用来挡住其它行的重复点击 */
const approving = ref<number | null>(null)

/** 我在借的工单：同时支撑「我的借用」计数与「即将到期」判定 */
const borrowedOrders = ref<OrderItem[]>([])

/** 「即将到期」的判定窗口（天） */
const RETURN_SOON_DAYS = 7

/**
 * 即将到期：计划归还时间落在「未来 {@link RETURN_SOON_DAYS} 天内」。
 *
 * 已超期的（diff < 0）**刻意不算在内** —— 那是另一类问题（该催还了）。
 * 混在一起会让这个数字既包含「还有 3 天」也包含「已经超了 10 天」，
 * 用户反而无法从数字上判断紧迫程度。
 */
const dueSoonOrders = computed(() =>
  borrowedOrders.value.filter((order) => {
    if (!order.plannedEndTime) {
      return false
    }
    const at = new Date(order.plannedEndTime).getTime()
    if (Number.isNaN(at)) {
      return false
    }
    const diff = at - Date.now()
    return diff >= 0 && diff <= RETURN_SOON_DAYS * 24 * 60 * 60 * 1000
  })
)

async function loadStats(): Promise<void> {
  loading.value = true
  const tasks: Array<Promise<void>> = [
    loadApprovalTodo(),
    loadBorrowed(),
    safeCount(() => orderApi.myHandling(1, 1).then((r) => r.total)).then((v) => {
      stat.value.handling = v
    }),
    safeCount(() => messageApi.unreadCount()).then((v) => {
      stat.value.unread = v
    })
  ]
  if (isAdmin.value) {
    tasks.push(
      safeCount(() => orderApi.allOrders({ page: 1, size: 1 }).then((r) => r.total)).then((v) => {
        stat.value.allOrders = v
      }),
      safeCount(() => deviceApi.page({ status: 'AVAILABLE', page: 1, size: 1 }).then((r) => r.total)).then((v) => {
        stat.value.deviceAvailable = v
      }),
      safeCount(() => deviceApi.page({ status: 'IN_USE', page: 1, size: 1 }).then((r) => r.total)).then((v) => {
        stat.value.deviceInUse = v
      }),
      safeCount(() => deviceApi.page({ status: 'MAINTENANCE', page: 1, size: 1 }).then((r) => r.total)).then((v) => {
        stat.value.deviceMaintenance = v
      })
    )
  }
  await Promise.allSettled(tasks)
  loading.value = false
}

/**
 * 待我审批：**一次请求**同时拿到总数与前几条明细。
 *
 * 刻意不写成「size=1 读 total」再单独拉列表 —— 那会对同一份数据发两次请求，
 * 而两次之间数据可能已经变了，界面上就会出现「数字写着 3、列表只有 2 条」
 * 这种无法向用户解释的矛盾。
 */
async function loadApprovalTodo(): Promise<void> {
  try {
    const res = await orderApi.pendingApproval(1, APPROVAL_INLINE_LIMIT)
    approvalOrders.value = res.records
    approvalTotal.value = res.total
    stat.value.approval = res.total
  } catch {
    approvalOrders.value = []
    approvalTotal.value = 0
    stat.value.approval = null
  }
}

/**
 * 我在借的工单。
 *
 * 「即将到期」本可以再开一个后端接口，这里直接复用同一份列表在本地算 ——
 * 在借工单天然是小集合（一个人手上不会同时借几十台），多一个接口就多一处
 * 需要和列表口径保持一致的地方，而一致性正是这类统计最容易出错的地方。
 */
async function loadBorrowed(): Promise<void> {
  try {
    const res = await orderApi.mine({ page: 1, size: 50, status: 'BORROWED' })
    borrowedOrders.value = res.records
    stat.value.borrowed = res.total
    stat.value.dueSoon = dueSoonOrders.value.length
  } catch {
    borrowedOrders.value = []
    stat.value.borrowed = null
    stat.value.dueSoon = null
  }
}

/**
 * 首页内联审批（通过 / 驳回）—— 不跳页。
 *
 * <h2>「通过」之前为什么必须先问一次指派候选</h2>
 * 当本单的下一步是「上一节点指定审批人」时，通过的人<b>必须同时点名指定执行人</b>，
 * 而首页没有选择器。若不问就提交，服务端会以 400 拒绝（缺 nextApproverIds），
 * 用户只会收到一句看不懂的报错。所以先问，需要指派就引导去审批中心 ——
 * 那条路径本来就有选择器，不需要在首页再实现一遍。
 */
async function approveInline(order: OrderItem, approved: boolean): Promise<void> {
  let comment: string | null = null
  if (!approved) {
    try {
      const res = await ElMessageBox.prompt('请填写驳回原因（必填）', '驳回工单', {
        inputPlaceholder: '例如：申请理由不充分、设备用途不符',
        inputValidator: (value: string) => (value && value.trim() ? true : '驳回必须填写原因')
      })
      comment = (res.value ?? '').trim()
    } catch {
      return
    }
  }

  approving.value = order.id
  try {
    if (approved) {
      const candidate = await orderApi.assignCandidates(order.id)
      if (candidate.requiredCount > 0) {
        ElMessage.warning('本单通过后需要指定下一步执行人，请到审批中心处理')
        void router.push('/order/approval')
        return
      }
    }
    await orderApi.approve(order.id, approved, comment)
    ElMessage.success(approved ? '已通过' : '已驳回')
    // 只刷这一块：整页 loadStats 会把统计卡与看板全部重拉，而它们与本次审批无关
    await loadApprovalTodo()
  } catch {
    // 状态已变化等非法操作由请求层统一提示
  } finally {
    approving.value = null
  }
}

function statText(key: string): string {
  const value = stat.value[key]
  return value == null ? '-' : String(value)
}

// ------------------------------------------------------------------
// 后端连通状态（保留原有的自检能力）
// ------------------------------------------------------------------
const health = ref<HealthInfo | null>(null)
const healthFailed = ref(false)
const checking = ref(false)

async function checkHealth(): Promise<void> {
  checking.value = true
  healthFailed.value = false
  try {
    health.value = await authApi.health()
  } catch {
    health.value = null
    healthFailed.value = true
  } finally {
    checking.value = false
  }
}

// ------------------------------------------------------------------
// 统计仪表盘聚合区块（ · M5）
//
// 整块由权限码 dashboard:view 控制：无权限的用户**看不到这个区块，也不会发出任何请求**
// （`loadDashboard` 首行即短路），页面与改造前逐像素相同。
// 取数不再是「分页接口 size=1 读 total」那种拼凑，而是一次专用聚合接口 ——
// 七个维度里「按时间分布」「按部门分布」本来就没有对应的列表接口可复用。
// ------------------------------------------------------------------
const canViewDashboard = computed(() => userStore.hasPerm('dashboard:view'))

/** 相对今天偏移 n 天的 `yyyy-MM-dd`（n=0 即今天） */
function isoDaysAgo(days: number): string {
  const d = new Date()
  d.setDate(d.getDate() - days)
  const month = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${month}-${day}`
}

/** 默认区间：含今天在内的近 30 天（与后端 DashboardQuery 的默认窗口一致） */
const dashRange = ref<[string, string]>([isoDaysAgo(29), isoDaysAgo(0)])
const dashGranularity = ref<DashboardGranularity>(DASHBOARD_GRANULARITY_DAY)
const dash = ref<DashboardSummary | null>(null)
const dashLoading = ref(false)
const dashFailed = ref(false)

/**
 * 区间文案以**后端回显**为准。
 *
 * 页面上写死「近 30 天」而后端按别的区间算，是这类页面最常见的信任崩塌点；
 * 把生效区间显示出来，用户一眼就能核对。
 */
const dashRangeText = computed(() =>
  dash.value ? `${dash.value.from} ~ ${dash.value.to}` : `${dashRange.value[0]} ~ ${dashRange.value[1]}`
)

const dashMonthHint = computed(() =>
  monthSuggestion(dashGranularity.value, dashRange.value[0], dashRange.value[1])
)

interface BarRow {
  key: string
  label: string
  count: number
  width: number
}

interface DistributionBlock {
  key: string
  title: string
  hint: string | null
  rows: BarRow[]
}

/**
 * 把一组分布桶换算成「标签 + 计数 + 条宽」。
 *
 * 条宽在**本组内**归一（最大值为 100%），不是以总量归一：四个维度的量纲不同
 * （类型 3 桶 vs 时间 30 桶），用总量归一会让天数条全部短到看不出来。
 * 换算放进 computed 而不是模板里算，是因为模板里不能写 TS 断言，
 * 且这类「摆位置之外的逻辑」放进纯函数才好单测。
 */
function barRows(items: ReadonlyArray<{ key: string; label: string; count: number }>): BarRow[] {
  const max = maxBucketCount(items)
  return items.map((item) => ({
    key: item.key,
    label: item.label,
    count: item.count,
    width: barWidthPercent(item.count, max)
  }))
}

const dashBlocks = computed<DistributionBlock[]>(() => {
  const data = dash.value
  return [
    {
      key: 'type',
      title: '按工单类型',
      hint: null,
      rows: barRows((data?.byType ?? []).map((b) => ({ key: b.code, label: b.label, count: b.count })))
    },
    {
      key: 'status',
      title: '按工单状态',
      hint: null,
      rows: barRows((data?.byStatus ?? []).map((b) => ({ key: b.code, label: b.label, count: b.count })))
    },
    {
      key: 'time',
      title: '时间分布',
      hint: dashMonthHint.value,
      rows: barRows((data?.byTime ?? []).map((b) => ({ key: b.bucket, label: b.bucket, count: b.count })))
    }
  ]
})

/**
 * 部门借用统计（项目·工作台布局）
 *
 * 说明：「部门借用统计图表移到常用入口下面」。
 * 原先它是 `dashBlocks` 的第四块，与「按类型 / 按状态 / 时间分布」挤在同一段里，
 * 而整段的位置又在「常用入口」**之后** ⇒ 管理岗要滑到底才看得到部门数字。
 *
 * 这里**把它从 `dashBlocks` 摘出来单独成卡**，而不是让同一个块渲染两次：
 * 数据仍取自同一次 `loadDashboard()` 的 `byGroup`，
 * 因此改区间时它与下方统计仪表盘**同源同步**，不会出现两处数字不一致。
 */
const departmentBlock = computed<DistributionBlock>(() => {
  const data = dash.value
  return {
    key: 'group',
    title: '部门借用统计',
    hint: null,
    rows: barRows((data?.byGroup ?? []).map((b) => ({ key: String(b.groupId), label: b.groupName, count: b.count })))
  }
})

async function loadDashboard(): Promise<void> {
  // 无权限时连请求都不发：区块不渲染，也就没有「加载失败」这种噪音
  if (!canViewDashboard.value) {
    return
  }
  dashLoading.value = true
  dashFailed.value = false
  try {
    dash.value = await dashboardApi.summary({
      from: dashRange.value[0],
      to: dashRange.value[1],
      granularity: dashGranularity.value
    })
  } catch {
    dash.value = null
    dashFailed.value = true
  } finally {
    dashLoading.value = false
  }
}

/**
 * 交互用 `@change` 而不是 `watch`。
 *
 * 项目既定教训：同一个值既可能被用户改、也可能被程序赋值时，
 * `watch` 会在程序化赋值（如回填）时也触发，导致「刚回填就被清空」这类静默丢数据。
 * 这里虽然只有用户会改，仍统一用 `@change` 保持一处风格。
 */
function handleDashRangeChange(): void {
  void loadDashboard()
}

function handleDashGranularityChange(): void {
  void loadDashboard()
}

onMounted(() => {
  void loadStats()
  void checkHealth()
  void loadDashboard()
})
</script>

<template>
  <div class="ts-page ts-dashboard">
    <section class="ts-card ts-dashboard__welcome">
      <div class="ts-dashboard__welcome-main">
        <h2 class="ts-dashboard__hello">{{ userStore.displayName }}，你好</h2>
        <p class="ts-text-secondary ts-dashboard__role">{{ roleLabel }}</p>
      </div>
      <div class="ts-dashboard__meta">
        <div class="ts-dashboard__meta-item">
          <span class="ts-text-hint">登录账号</span>
          <strong>{{ userStore.user?.username }}</strong>
        </div>
        <div class="ts-dashboard__meta-item">
          <span class="ts-text-hint">部门</span>
          <strong>{{ departmentText }}</strong>
        </div>
        <div class="ts-dashboard__meta-item">
          <span class="ts-text-hint">数据范围</span>
          <strong>{{ dataScopeText }}</strong>
        </div>
        <div class="ts-dashboard__meta-item">
          <span class="ts-text-hint">认证方式</span>
          <strong>{{ userStore.user?.authType === 'LDAP' ? 'AD 域控' : '本地账户' }}</strong>
        </div>
      </div>
    </section>

    <!--
      常用入口（项目·工作台布局）
      说明：「常用入口快捷按钮移到页面最顶部」「员工打开首页第一眼看到按钮，不用往下滑」。
      这里原先排在页面**最末**（与「后端连通状态」并排在那两列网格里），
      与组件自己那句注释「必须显眼、默认可见」直接矛盾 —— 因此上提到欢迎卡之后的第一个位置。
    -->
    <section class="ts-card ts-dashboard__entry">
      <h3 class="ts-dashboard__card-title">常用入口</h3>
      <div class="ts-dashboard__shortcuts">
        <button
          v-for="item in shortcuts"
          :key="item.path"
          class="ts-dashboard__shortcut"
          type="button"
          @click="router.push(item.path)"
        >
          {{ item.title }}
        </button>
      </div>
    </section>

    <!--
      部门借用统计（项目·工作台布局）
      说明：「部门借用统计图表移到常用入口下面」。
      仅 `dashboard:view` 持有者可见（与下方统计仪表盘同一判据）；
      普通员工看不到这一卡，但**不影响他第一眼就看到常用入口**。
    -->
    <section v-if="canViewDashboard" v-loading="dashLoading" class="ts-card ts-dashboard__dept">
      <div class="ts-flex-between ts-dashboard__card-head">
        <div>
          <h3 class="ts-dashboard__card-title">部门借用统计</h3>
          <p class="ts-text-hint">{{ dashRangeText }}</p>
        </div>
        <el-button link type="primary" @click="router.push('/asset/report')">查看统计报表</el-button>
      </div>
      <div v-if="dashFailed" class="ts-dash__failed">
        <el-tag type="danger" effect="plain">统计加载失败</el-tag>
        <span class="ts-text-hint">下方统计仪表盘的「刷新」可重试。</span>
      </div>
      <div v-else-if="departmentBlock.rows.length" class="ts-dash__bars">
        <div v-for="row in departmentBlock.rows" :key="row.key" class="ts-dash__bar-row">
          <span class="ts-dash__bar-label" :title="row.label">{{ row.label }}</span>
          <span class="ts-dash__bar-track">
            <i class="ts-dash__bar-fill" :style="{ width: row.width + '%' }"></i>
          </span>
          <span class="ts-dash__bar-count">{{ row.count }}</span>
        </div>
      </div>
      <p v-else class="ts-text-hint">区间内暂无借用记录</p>
    </section>

    <!-- 与我相关的待办 -->
    <section v-loading="loading" class="ts-dashboard__stats">
      <button
        v-for="card in personalCards"
        :key="card.key"
        type="button"
        class="ts-card ts-dashboard__stat"
        @click="router.push(card.path)"
      >
        <span class="ts-dashboard__stat-label">{{ card.label }}</span>
        <span class="ts-dashboard__stat-value">{{ statText(card.key) }}</span>
      </button>
    </section>

    <!-- 待我审批：前几条就地可审（P1 工作台优化） -->
    <section v-if="approvalOrders.length" class="ts-card ts-dashboard__todo">
      <div class="ts-flex-between ts-dashboard__card-head">
        <h3 class="ts-dashboard__card-title">待我审批</h3>
        <el-button link type="primary" @click="router.push('/order/approval')">
          查看全部（{{ approvalTotal }} 条）
        </el-button>
      </div>
      <ul class="ts-dashboard__todo-list">
        <li v-for="order in approvalOrders" :key="order.id" class="ts-dashboard__todo-item">
          <div class="ts-dashboard__todo-main">
            <span class="ts-dashboard__todo-title">{{ order.deviceName ?? '（未指定设备）' }}</span>
            <span class="ts-text-hint">
              {{ order.orderNo }} · 申请人 {{ order.applicantName ?? '-' }} ·
              {{ order.useTypeLabel ?? order.statusLabel }}
            </span>
          </div>
          <div class="ts-dashboard__todo-actions">
            <el-button
              size="small"
              type="primary"
              :loading="approving === order.id"
              :disabled="approving !== null && approving !== order.id"
              @click="approveInline(order, true)"
            >
              同意
            </el-button>
            <el-button
              size="small"
              :loading="approving === order.id"
              :disabled="approving !== null && approving !== order.id"
              @click="approveInline(order, false)"
            >
              驳回
            </el-button>
          </div>
        </li>
      </ul>
    </section>

    <!-- 即将到期提醒（P1 工作台优化） -->
    <section v-if="dueSoonOrders.length" class="ts-card ts-dashboard__due">
      <div class="ts-flex-between ts-dashboard__card-head">
        <h3 class="ts-dashboard__card-title">即将到期（{{ RETURN_SOON_DAYS }} 天内）</h3>
        <el-button link type="primary" @click="router.push('/order/mine')">去归还</el-button>
      </div>
      <ul class="ts-dashboard__due-list">
        <li v-for="order in dueSoonOrders" :key="order.id" class="ts-dashboard__due-item">
          <span class="ts-dashboard__due-name">{{ order.deviceName ?? '-' }}</span>
          <span class="ts-text-hint">应还 {{ order.plannedEndTime }}</span>
        </li>
      </ul>
    </section>

    <!-- 管理员：全局概览 -->
    <section v-if="isAdmin" v-loading="loading" class="ts-dashboard__stats">
      <button
        v-for="card in adminCards"
        :key="card.key"
        type="button"
        class="ts-card ts-dashboard__stat is-admin"
        @click="router.push(card.path)"
      >
        <span class="ts-dashboard__stat-label">{{ card.label }}</span>
        <span class="ts-dashboard__stat-value">{{ statText(card.key) }}</span>
      </button>
    </section>

    <!-- 统计仪表盘（ · M5）：仅 dashboard:view 持有者可见 -->
    <section v-if="canViewDashboard" v-loading="dashLoading" class="ts-dash">
      <div class="ts-flex-between ts-dash__head">
        <div>
          <h3 class="ts-dashboard__card-title">统计仪表盘</h3>
          <p class="ts-text-hint">{{ dashRangeText }}</p>
        </div>
        <div class="ts-dash__controls">
          <el-date-picker
            v-model="dashRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            :clearable="false"
            unlink-panels
            @change="handleDashRangeChange"
          />
          <el-radio-group v-model="dashGranularity" @change="handleDashGranularityChange">
            <el-radio-button v-for="option in GRANULARITY_OPTIONS" :key="option.value" :value="option.value">
              {{ option.label }}
            </el-radio-button>
          </el-radio-group>
          <el-button link type="primary" @click="loadDashboard">刷新</el-button>
        </div>
      </div>

      <p v-if="dashMonthHint" class="ts-dash__hint">{{ dashMonthHint }}</p>

      <div v-if="dashFailed" class="ts-dash__failed">
        <el-tag type="danger" effect="plain">统计加载失败</el-tag>
        <span class="ts-text-hint">可点「刷新」重试；页面其余内容不受影响。</span>
      </div>

      <template v-else-if="dash">
        <div class="ts-dash__metrics">
          <div class="ts-card ts-dash__metric">
            <span class="ts-dashboard__stat-label">区间内工单</span>
            <span class="ts-dashboard__stat-value">{{ dash.totalOrders }}</span>
          </div>
          <div class="ts-card ts-dash__metric">
            <span class="ts-dashboard__stat-label">平均审批时长</span>
            <span class="ts-dashboard__stat-value">{{ formatDashboardHours(dash.avgApprovalHours) }}</span>
            <span class="ts-text-hint">{{ approvalSampleHint(dash) }}</span>
          </div>
          <div class="ts-card ts-dash__metric">
            <span class="ts-dashboard__stat-label">超时率</span>
            <span class="ts-dashboard__stat-value">{{ formatDashboardRate(dash.overdueRate) }}</span>
            <span class="ts-text-hint">{{ overdueSampleHint(dash) }}</span>
          </div>
        </div>

        <div class="ts-dash__grid">
          <div v-for="block in dashBlocks" :key="block.key" class="ts-card ts-dash__block">
            <h4 class="ts-dash__block-title">{{ block.title }}</h4>
            <p v-if="block.hint" class="ts-dash__hint">{{ block.hint }}</p>
            <div v-if="block.rows.length" class="ts-dash__bars">
              <div v-for="row in block.rows" :key="row.key" class="ts-dash__bar-row">
                <span class="ts-dash__bar-label" :title="row.label">{{ row.label }}</span>
                <span class="ts-dash__bar-track">
                  <i class="ts-dash__bar-fill" :style="{ width: row.width + '%' }"></i>
                </span>
                <span class="ts-dash__bar-count">{{ row.count }}</span>
              </div>
            </div>
            <p v-else class="ts-text-hint">区间内暂无工单</p>
          </div>
        </div>
      </template>
    </section>

    <!--
      后端连通状态（运维排障用）：常用入口上提后，这一卡改为独占一行。
      原先是两列网格的右格，撤掉左格后若仍留在网格里会只占半幅、右侧留白。
    -->
    <section class="ts-card ts-dashboard__health">
      <div class="ts-flex-between ts-dashboard__card-head">
        <h3 class="ts-dashboard__card-title">后端连通状态</h3>
        <el-button link type="primary" :loading="checking" @click="checkHealth">重新检测</el-button>
      </div>
      <div v-if="MOCK_ENABLED" class="ts-dashboard__status">
        <el-tag type="warning" effect="plain">Mock 模式</el-tag>
        <p class="ts-text-secondary">当前为前端 Mock 数据，未连接后端。</p>
      </div>
      <div v-else-if="health" class="ts-dashboard__status">
        <el-tag type="success" effect="plain">{{ health.status }}</el-tag>
        <p class="ts-text-secondary">服务：{{ health.application }}（profile: {{ health.profile }}）</p>
        <p class="ts-text-hint">traceId：{{ health.traceId }}</p>
      </div>
      <div v-else-if="healthFailed" class="ts-dashboard__status">
        <el-tag type="danger" effect="plain">连接失败</el-tag>
        <p class="ts-text-secondary">未能连接后端接口，请确认后端已在 8080 端口启动。</p>
      </div>
      <div v-else class="ts-dashboard__status">
        <el-tag effect="plain">检测中…</el-tag>
      </div>
    </section>
  </div>
</template>

<style scoped>
.ts-dashboard {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.ts-dashboard__welcome {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  align-items: center;
  justify-content: space-between;
}

.ts-dashboard__hello {
  margin: 0 0 4px;
  font-size: 18px;
  font-weight: 500;
}

.ts-dashboard__role {
  margin: 0;
  font-size: 13px;
}

.ts-dashboard__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 24px;
}

.ts-dashboard__meta-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 13px;
}

/*
 * 自适应列数：卡片数量会随角色变化（普通角色 5 张、管理员再多 4 张），
 * 写死 4 列会让最后一行只剩一两张卡孤零零挂着。
 * minmax(180px) 保证每张卡不被压到读不出数字，同时让 5 张在 PC 上正好一行。
 */
.ts-dashboard__stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 16px;
}

.ts-dashboard__stat {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 16px;
  text-align: left;
  cursor: pointer;
  border: 1px solid var(--ts-border);
  border-radius: 10px;
  background: var(--ts-surface);
  transition: border-color 0.15s, box-shadow 0.15s;
}

.ts-dashboard__stat:hover {
  border-color: var(--el-color-primary);
  box-shadow: 0 2px 12px rgba(64, 158, 255, 0.12);
}

.ts-dashboard__stat.is-admin {
  background: #f6f9ff;
}

.ts-dashboard__stat-label {
  font-size: 13px;
  color: var(--ts-text-secondary);
}

.ts-dashboard__stat-value {
  font-size: 26px;
  font-weight: 600;
  line-height: 1.1;
  color: var(--ts-primary);
}

/* ---------------- P1：首页内联待办 / 即将到期 ---------------- */

.ts-dashboard__todo-list,
.ts-dashboard__due-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.ts-dashboard__todo-item,
.ts-dashboard__due-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid var(--ts-border);
  border-radius: 8px;
  background: var(--ts-surface);
}

.ts-dashboard__todo-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.ts-dashboard__todo-title,
.ts-dashboard__due-name {
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ts-dashboard__todo-actions {
  display: flex;
  flex-shrink: 0;
  gap: 8px;
}

/* 部门借用统计（项目·工作台布局）：紧随「常用入口」，条形列表限高滚动 */
.ts-dashboard__dept .ts-dash__bars {
  max-height: 260px;
}

.ts-dashboard__card-head {
  margin-bottom: 12px;
}

.ts-dashboard__card-title {
  margin: 0 0 12px;
  font-size: 15px;
  font-weight: 500;
}

.ts-dashboard__card-head .ts-dashboard__card-title {
  margin: 0;
}

.ts-dashboard__status p {
  margin: 8px 0 0;
  font-size: 13px;
}

.ts-dashboard__shortcuts {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.ts-dashboard__shortcut {
  padding: 8px 14px;
  min-height: 40px;
  font-size: 13px;
  color: var(--ts-primary);
  background: #eef3f9;
  border: 1px solid var(--ts-border);
  border-radius: 8px;
  cursor: pointer;
}

.ts-dashboard__shortcut:hover {
  background: #e2ebf6;
}

/* ------------------------------------------------------------------
   统计仪表盘（ · M5）
   无图表库：四个分布用「指标卡 + CSS 条形」呈现，与项目取向一致。
   ------------------------------------------------------------------ */
.ts-dash {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.ts-dash__head {
  flex-wrap: wrap;
  gap: 12px;
}

.ts-dash__controls {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
}

.ts-dash__hint {
  margin: 0;
  font-size: 12px;
  color: var(--el-color-warning);
}

.ts-dash__block .ts-dash__hint {
  margin: 0 0 8px;
}

.ts-dash__failed {
  display: flex;
  gap: 8px;
  align-items: center;
}

.ts-dash__metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
}

.ts-dash__metric {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.ts-dash__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.ts-dash__block-title {
  margin: 0 0 12px;
  font-size: 14px;
  font-weight: 500;
}

.ts-dash__bars {
  display: flex;
  flex-direction: column;
  gap: 10px;
  /* 时间分布可能几十行，限高滚动，避免把整页拉得极长 */
  max-height: 320px;
  overflow-y: auto;
}

.ts-dash__bar-row {
  display: grid;
  grid-template-columns: 96px minmax(0, 1fr) 48px;
  gap: 8px;
  align-items: center;
  font-size: 12px;
}

.ts-dash__bar-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--ts-text-secondary);
}

.ts-dash__bar-track {
  height: 10px;
  overflow: hidden;
  background: #eef3f9;
  border-radius: 5px;
}

.ts-dash__bar-fill {
  display: block;
  height: 100%;
  min-width: 2px;
  background: var(--ts-primary);
  border-radius: 5px;
}

.ts-dash__bar-count {
  text-align: right;
  font-variant-numeric: tabular-nums;
  color: var(--ts-text-secondary);
}

@media (max-width: 1199px) {
  .ts-dash__metrics {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .ts-dash__grid {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 767px) {
  .ts-dash__metrics {
    grid-template-columns: repeat(1, minmax(0, 1fr));
  }

  .ts-dash__controls {
    width: 100%;
  }

  .ts-dash__bar-row {
    grid-template-columns: 72px minmax(0, 1fr) 40px;
  }
}

@media (max-width: 767px) {
  .ts-dashboard__hello {
    font-size: 16px;
  }

  /* 内联待办在手机上改上下排列：左右排会把设备名挤成省略号，而「是哪个设备」
     恰恰是审批人要看的第一个信息 */
  .ts-dashboard__todo-item {
    flex-direction: column;
    align-items: stretch;
  }

  .ts-dashboard__todo-actions {
    justify-content: flex-end;
  }

  .ts-dashboard__todo-actions :deep(.el-button) {
    flex: 1;
  }

  .ts-dashboard__meta {
    gap: 12px 24px;
  }

  .ts-dashboard__shortcut {
    flex: 1 1 calc(50% - 5px);
    text-align: center;
  }
}
</style>
