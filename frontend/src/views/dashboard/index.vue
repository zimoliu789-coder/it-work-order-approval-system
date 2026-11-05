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

/** 「待我交付 / 待收回」的前几条：右栏直接列出来，不让人再多点一次 */
const handlingOrders = ref<OrderItem[]>([])
const HANDLING_INLINE_LIMIT = 5

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
    // 一次调用同时拿计数与前几条：右栏「待我交付」直接列出来，失败时都置空
    orderApi
      .myHandling(1, HANDLING_INLINE_LIMIT)
      .then((r) => {
        stat.value.handling = r.total
        handlingOrders.value = r.records
      })
      .catch(() => {
        stat.value.handling = 0
        handlingOrders.value = []
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
  <div class="ts-page ts-wb">
    <!--
      页头（2026-11-05 重做）：原来是一整张「你好 + 登录账号/部门/数据范围/认证方式」欢迎卡，
      占 130px 却只在说“你是谁”。压成一行后，省下的高度全部给下面的待办。
    -->
    <header class="ts-wb__head">
      <h1 class="ts-wb__title">工作台</h1>
      <span class="ts-wb__divider">|</span>
      <span class="ts-wb__role">{{ roleLabel }} · {{ departmentText }}</span>
      <span class="ts-wb__spacer"></span>
      <span class="ts-wb__meta">
        {{ dataScopeText }} · {{ userStore.user?.authType === 'LDAP' ? 'AD 域控' : '本地账户' }}
      </span>
      <el-button size="small" :loading="checking" @click="checkHealth">刷新状态</el-button>
    </header>

    <!--
      指标条：个人 5 项 + 管理员 4 项合成**一条连续带**，竖线分隔。
      改造前是 9 张独立卡片（各带边框与阴影）、两行摆开且两行列宽不一致，约 200px 高。
      现在 60px 一屏排完，数字仍然一眼可见；主色只留给管理员那 4 项。
    -->
    <div v-loading="loading" class="ts-wb__kpis">
      <button
        v-for="card in personalCards"
        :key="card.key"
        type="button"
        class="ts-wb__kpi"
        @click="router.push(card.path)"
      >
        <b class="ts-wb__kpi-v">{{ statText(card.key) }}</b>
        <span class="ts-wb__kpi-l">{{ card.label }}</span>
      </button>
      <template v-if="isAdmin">
        <button
          v-for="card in adminCards"
          :key="card.key"
          type="button"
          class="ts-wb__kpi is-admin"
          @click="router.push(card.path)"
        >
          <b class="ts-wb__kpi-v">{{ statText(card.key) }}</b>
          <span class="ts-wb__kpi-l">{{ card.label }}</span>
        </button>
      </template>
    </div>

    <div class="ts-wb__cols">
      <div class="ts-wb__main">
        <!--
          待我审批：全页唯一能直接干活的地方，提到主区第一位（原来排在第 5 块，要往下滚）。
          改成 40px 行高的表格，一屏 5 行；「同意 / 驳回」就在行尾，不跳页。
        -->
        <section v-if="approvalOrders.length" class="ts-wb__panel">
          <div class="ts-wb__panel-head">
            <h2 class="ts-wb__panel-title">
              待我审批<span class="ts-wb__badge">{{ approvalTotal }}</span>
            </h2>
            <el-button link type="primary" size="small" @click="router.push('/order/approval')">
              全部工单
            </el-button>
          </div>
          <table class="ts-wb__table">
            <thead>
              <tr>
                <th class="col-no">工单号</th>
                <th>设备</th>
                <th class="col-who">申请人</th>
                <th class="col-type">类型</th>
                <th class="col-op">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="order in approvalOrders" :key="order.id">
                <td class="col-no ts-wb__mono">{{ order.orderNo }}</td>
                <td class="is-ellipsis">{{ order.deviceName ?? '（未指定设备）' }}</td>
                <td class="col-who">{{ order.applicantName ?? '-' }}</td>
                <td class="col-type">{{ order.useTypeLabel ?? order.statusLabel }}</td>
                <td class="col-op">
                  <el-button
                    link
                    type="primary"
                    size="small"
                    :loading="approving === order.id"
                    :disabled="approving !== null && approving !== order.id"
                    @click="approveInline(order, true)"
                  >
                    同意
                  </el-button>
                  <el-button
                    link
                    size="small"
                    :loading="approving === order.id"
                    :disabled="approving !== null && approving !== order.id"
                    @click="approveInline(order, false)"
                  >
                    驳回
                  </el-button>
                </td>
              </tr>
            </tbody>
          </table>
        </section>

        <!-- 部门借用：跟着主区走，去掉独立卡片外壳（空态时不再占半屏） -->
        <section v-if="canViewDashboard" v-loading="dashLoading" class="ts-wb__panel">
          <div class="ts-wb__panel-head">
            <h2 class="ts-wb__panel-title">部门借用</h2>
            <span class="ts-wb__hint">{{ dashRangeText }}</span>
            <el-button link type="primary" size="small" @click="router.push('/asset/report')">
              统计报表
            </el-button>
          </div>
          <div v-if="dashFailed" class="ts-wb__failed">
            <el-tag type="danger" effect="plain" size="small">统计加载失败</el-tag>
            <span class="ts-wb__hint">可在下方「统计」里刷新重试</span>
          </div>
          <div v-else-if="departmentBlock.rows.length" class="ts-wb__bars">
            <div v-for="row in departmentBlock.rows" :key="row.key" class="ts-wb__bar">
              <span class="ts-wb__bar-l" :title="row.label">{{ row.label }}</span>
              <span class="ts-wb__bar-track"><i :style="{ width: row.width + '%' }"></i></span>
              <span class="ts-wb__bar-c">{{ row.count }}</span>
            </div>
          </div>
          <p v-else class="ts-wb__empty">区间内暂无借用记录</p>
        </section>

        <!-- 统计：是「看」不是「做」，所以排主区末尾；3 指标横排 + 三组条形并排 -->
        <section v-if="canViewDashboard" v-loading="dashLoading" class="ts-wb__panel">
          <div class="ts-wb__panel-head">
            <h2 class="ts-wb__panel-title">统计</h2>
            <span class="ts-wb__hint">{{ dashRangeText }}</span>
            <span class="ts-wb__spacer"></span>
            <el-date-picker
              v-model="dashRange"
              type="daterange"
              value-format="YYYY-MM-DD"
              range-separator="至"
              start-placeholder="开始"
              end-placeholder="结束"
              :clearable="false"
              unlink-panels
              size="small"
              class="ts-wb__range"
              @change="handleDashRangeChange"
            />
            <el-radio-group v-model="dashGranularity" size="small" @change="handleDashGranularityChange">
              <el-radio-button v-for="option in GRANULARITY_OPTIONS" :key="option.value" :value="option.value">
                {{ option.label }}
              </el-radio-button>
            </el-radio-group>
            <el-button link type="primary" size="small" @click="loadDashboard">刷新</el-button>
          </div>

          <p v-if="dashMonthHint" class="ts-wb__hint ts-wb__hint--pad">{{ dashMonthHint }}</p>

          <div v-if="dashFailed" class="ts-wb__failed">
            <el-tag type="danger" effect="plain" size="small">统计加载失败</el-tag>
            <span class="ts-wb__hint">可点「刷新」重试；页面其余内容不受影响</span>
          </div>

          <template v-else-if="dash">
            <div class="ts-wb__mini">
              <div class="ts-wb__mini-item">
                <b>{{ dash.totalOrders }}</b>
                <span>区间工单</span>
              </div>
              <div class="ts-wb__mini-item">
                <b>{{ formatDashboardHours(dash.avgApprovalHours) }}</b>
                <span>平均审批</span>
                <em>{{ approvalSampleHint(dash) }}</em>
              </div>
              <div class="ts-wb__mini-item">
                <b>{{ formatDashboardRate(dash.overdueRate) }}</b>
                <span>超时率</span>
                <em>{{ overdueSampleHint(dash) }}</em>
              </div>
            </div>

            <div class="ts-wb__blocks">
              <div v-for="block in dashBlocks" :key="block.key" class="ts-wb__block">
                <h3>{{ block.title }}</h3>
                <p v-if="block.hint" class="ts-wb__hint">{{ block.hint }}</p>
                <div v-if="block.rows.length" class="ts-wb__bars">
                  <div v-for="row in block.rows" :key="row.key" class="ts-wb__bar">
                    <span class="ts-wb__bar-l" :title="row.label">{{ row.label }}</span>
                    <span class="ts-wb__bar-track"><i :style="{ width: row.width + '%' }"></i></span>
                    <span class="ts-wb__bar-c">{{ row.count }}</span>
                  </div>
                </div>
                <p v-else class="ts-wb__empty">区间内暂无工单</p>
              </div>
            </div>
          </template>
        </section>
      </div>

      <!--
        右栏固定 288：只放「要行动」和「要跳转」的东西。
        宽度固定是为了主区内容变化时右栏不跳。
      -->
      <aside class="ts-wb__side">
        <section v-if="dueSoonOrders.length" class="ts-wb__panel">
          <div class="ts-wb__panel-head">
            <h2 class="ts-wb__panel-title">即将到期</h2>
            <span class="ts-wb__hint">{{ RETURN_SOON_DAYS }} 天内</span>
            <el-button link type="primary" size="small" @click="router.push('/order/mine')">
              去归还
            </el-button>
          </div>
          <ul class="ts-wb__list">
            <li v-for="order in dueSoonOrders" :key="order.id">
              <!-- 后端返回的是「设备名（资产编号）」，右栏 288px 装不下两段，只取设备名 -->
              <span class="ts-wb__li-t">{{ (order.deviceName ?? '-').split('（')[0] }}</span>
              <span class="ts-wb__li-v">{{ (order.plannedEndTime ?? '').slice(5, 10) }}</span>
            </li>
          </ul>
        </section>

        <!--
          待我交付 / 待收回：原来右栏只有「待我处理 5」这一个数字，
          点进去还得再找单。这里直接列出前 5 条，右栏也顺势填满。
        -->
        <section v-if="handlingOrders.length" class="ts-wb__panel">
          <div class="ts-wb__panel-head">
            <h2 class="ts-wb__panel-title">
              待我交付<span class="ts-wb__badge">{{ statText('handling') }}</span>
            </h2>
            <el-button link type="primary" size="small" @click="router.push('/order/pending')">
              全部
            </el-button>
          </div>
          <ul class="ts-wb__list">
            <li v-for="order in handlingOrders" :key="order.id">
              <span class="ts-wb__li-t">{{ (order.deviceName ?? '-').split('（')[0] }}</span>
              <span class="ts-wb__li-v">{{ order.statusLabel }}</span>
            </li>
          </ul>
        </section>

        <!-- 14 个入口：原来横铺成一堵等宽按钮墙，改两列小条目 -->
        <section class="ts-wb__panel">
          <div class="ts-wb__panel-head">
            <h2 class="ts-wb__panel-title">常用入口</h2>
          </div>
          <div class="ts-wb__links">
            <button
              v-for="item in shortcuts"
              :key="item.path"
              type="button"
              @click="router.push(item.path)"
            >
              <i aria-hidden="true">{{ item.title.slice(0, 1) }}</i>
              <span>{{ item.title }}</span>
            </button>
          </div>
        </section>
      </aside>
    </div>

    <!-- 后端连通状态：原来是独占一整块卡片，压成页脚一行（运维排障信息，不占版面） -->
    <footer class="ts-wb__foot">
      <span class="ts-wb__foot-label">后端</span>
      <el-tag v-if="MOCK_ENABLED" type="warning" effect="plain" size="small">Mock 模式</el-tag>
      <template v-else-if="health">
        <el-tag type="success" effect="plain" size="small">{{ health.status }}</el-tag>
        <span class="ts-wb__hint">
          {{ health.application }} · {{ health.profile }} · traceId {{ health.traceId }}
        </span>
      </template>
      <el-tag v-else-if="healthFailed" type="danger" effect="plain" size="small">连接失败</el-tag>
      <el-tag v-else effect="plain" size="small">检测中…</el-tag>
      <span class="ts-wb__spacer"></span>
      <span class="ts-wb__hint">{{ userStore.user?.username }} · 登录账号</span>
    </footer>
  </div>
</template>

<style scoped>
/*
 * 工作台（2026-11-05 重做）
 *
 * 改造前是 8 个白卡纵向堆叠：欢迎卡 → 9 个统计卡 → 常用入口 → 部门借用
 * → 待我审批 → 即将到期 → 统计仪表盘 → 后端状态。1440×900 下要滚约 3.5 屏，
 * 而唯一能直接干活的「待我审批」排在第 5 块。
 *
 * 现在是 页头一行 + 指标条 + 主区两栏 + 页脚一行。三条规则：
 *   1. 卡片只在需要边界时出现；页头与页脚只用 1px 线，不套白卡（避免卡里套卡）。
 *   2. 主区放「要做的事」，右栏放「要跳转的事」，右栏宽固定 288，主区变化时它不跳。
 *   3. 字号只有四档：17 页标题 / 13 正文 / 11.5 表头与标签 / 20 指标数字。
 *      层级靠字重与颜色，不靠字号跳跃。
 */
.ts-wb { display: flex; flex-direction: column; gap: var(--ts-sp-3); }

/* ---------- 页头 ---------- */
.ts-wb__head { display: flex; align-items: center; gap: 10px; min-height: 30px; }

.ts-wb__title { margin: 0; font-size: var(--ts-fs-page); font-weight: 600; letter-spacing: -0.01em; }
.ts-wb__divider { color: var(--ts-border-strong); }
.ts-wb__role { font-size: var(--ts-fs-body); color: var(--ts-text-secondary); }
.ts-wb__meta { font-size: var(--ts-fs-caption); color: var(--ts-text-hint); }
.ts-wb__spacer { flex: 1 1 auto; }

/* ---------- 指标条 ---------- */
.ts-wb__kpis {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(112px, 1fr));
  background: var(--ts-surface);
  border: 1px solid var(--ts-border);
  border-radius: var(--ts-r-md);
  overflow: hidden;
}

.ts-wb__kpi {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 11px 14px;
  text-align: left;
  background: none;
  border: 0;
  border-left: 1px solid var(--ts-border);
  cursor: pointer;
  transition: background var(--ts-dur-fast) var(--ts-ease);
}

.ts-wb__kpi:first-child { border-left: 0; }
.ts-wb__kpi:hover { background: var(--ts-surface-hover); }
.ts-wb__kpi:focus-visible { outline: 2px solid var(--ts-accent); outline-offset: -2px; }

.ts-wb__kpi-v {
  font-size: 20px;
  font-weight: 600;
  line-height: 1.15;
  color: var(--ts-text);
  font-variant-numeric: tabular-nums;
}

.ts-wb__kpi-l { font-size: var(--ts-fs-caption); color: var(--ts-text-secondary); }
.ts-wb__kpi.is-admin .ts-wb__kpi-v { color: var(--ts-accent); }

/* ---------- 两栏 ---------- */
.ts-wb__cols {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 288px;
  gap: var(--ts-sp-3);
  align-items: start;
}

.ts-wb__main,
.ts-wb__side { display: flex; flex-direction: column; gap: var(--ts-sp-3); min-width: 0; }


/* ---------- 面板 ---------- */
.ts-wb__panel {
  background: var(--ts-surface);
  border: 1px solid var(--ts-border);
  border-radius: var(--ts-r-md);
  overflow: hidden;
}

.ts-wb__panel-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 14px;
  border-bottom: 1px solid var(--ts-border);
}

.ts-wb__panel-title {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  font-size: var(--ts-fs-card);
  font-weight: 600;
}

.ts-wb__panel-head .el-button { margin-left: auto; }

.ts-wb__badge {
  min-width: 18px;
  height: 17px;
  padding: 0 5px;
  display: inline-grid;
  place-items: center;
  font-size: 11px;
  font-weight: 600;
  line-height: 1;
  color: var(--ts-text-inverse);
  background: var(--ts-accent);
  border-radius: var(--ts-r-pill);
  font-variant-numeric: tabular-nums;
}

.ts-wb__hint { font-size: var(--ts-fs-caption); color: var(--ts-text-hint); }
.ts-wb__hint--pad { margin: 0; padding: 8px 14px 0; }
.ts-wb__empty { margin: 0; padding: 14px; font-size: var(--ts-fs-caption); color: var(--ts-text-hint); }
.ts-wb__failed { display: flex; align-items: center; gap: 8px; padding: 12px 14px; }

/* ---------- 审批表 ---------- */
.ts-wb__table {
  width: 100%;
  border-collapse: collapse;
  table-layout: fixed; /* 让长设备名按省略号收，否则 max-width:0 在 auto 布局里无效 */
  font-size: var(--ts-fs-body);
}

.ts-wb__table th {
  padding: 7px 14px;
  text-align: left;
  font-size: var(--ts-fs-caption);
  font-weight: 500;
  color: var(--ts-text-secondary);
  background: var(--ts-surface-sunken);
  border-bottom: 1px solid var(--ts-border);
  white-space: nowrap;
}

.ts-wb__table td {
  height: 40px;
  padding: 0 14px;
  border-bottom: 1px solid var(--ts-border);
  vertical-align: middle;
}

.ts-wb__table tbody tr:last-child td { border-bottom: 0; }
.ts-wb__table tbody tr:hover td { background: var(--ts-surface-hover); }

.ts-wb__table .col-no { width: 148px; }
.ts-wb__table .col-who { width: 76px; }
.ts-wb__table .col-type { width: 88px; }
.ts-wb__table .col-op { width: 124px; text-align: right; white-space: nowrap; }
.ts-wb__table .col-op .el-button + .el-button { margin-left: 10px; }

.ts-wb__mono {
  font-family: ui-monospace, "Cascadia Mono", Consolas, monospace;
  font-size: var(--ts-fs-caption);
  color: var(--ts-text-secondary);
}

.is-ellipsis { max-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* ---------- 条形 ---------- */
.ts-wb__bars { display: flex; flex-direction: column; gap: 6px; padding: 11px 14px; }

.ts-wb__bar {
  display: grid;
  grid-template-columns: 92px minmax(0, 1fr) 36px;
  align-items: center;
  gap: 10px;
  font-size: var(--ts-fs-caption);
}

.ts-wb__bar-l { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--ts-text-secondary); }

.ts-wb__bar-track { height: 6px; border-radius: 3px; background: var(--ts-surface-sunken); overflow: hidden; }
.ts-wb__bar-track i { display: block; height: 100%; background: var(--ts-accent); border-radius: 3px; }

.ts-wb__bar-c { text-align: right; color: var(--ts-text-secondary); font-variant-numeric: tabular-nums; }

/* ---------- 统计 ---------- */
.ts-wb__mini { display: flex; gap: 26px; padding: 11px 14px 2px; }
.ts-wb__mini-item { display: flex; flex-direction: column; gap: 1px; }
.ts-wb__mini-item b { font-size: 18px; font-weight: 600; line-height: 1.2; font-variant-numeric: tabular-nums; }
.ts-wb__mini-item span { font-size: var(--ts-fs-caption); color: var(--ts-text-hint); }
.ts-wb__mini-item em { font-style: normal; font-size: var(--ts-fs-micro); color: var(--ts-text-hint); }

.ts-wb__blocks {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(216px, 1fr));
  margin-top: 6px;
  border-top: 1px solid var(--ts-border);
}

.ts-wb__block { padding: 10px 14px; }
.ts-wb__block + .ts-wb__block { border-left: 1px solid var(--ts-border); }
.ts-wb__block h3 { margin: 0 0 7px; font-size: var(--ts-fs-caption); font-weight: 600; color: var(--ts-text-secondary); }
.ts-wb__block .ts-wb__bars { padding: 0; }
.ts-wb__block .ts-wb__empty { padding: 0; }

.ts-wb__range { width: 216px; }

/* ---------- 右栏 ---------- */
.ts-wb__list { margin: 0; padding: 4px 0; list-style: none; }

.ts-wb__list li {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 7px 14px;
  font-size: var(--ts-fs-body);
}

.ts-wb__li-t { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ts-wb__li-v {
  flex: none;
  font-size: var(--ts-fs-caption);
  color: var(--ts-text-secondary);
  font-variant-numeric: tabular-nums;
}

.ts-wb__links { display: grid; grid-template-columns: 1fr 1fr; gap: 2px; padding: 5px; }

.ts-wb__links button {
  display: flex;
  align-items: center;
  gap: 7px;
  min-width: 0;
  padding: 6px 8px;
  font-size: var(--ts-fs-body);
  color: var(--ts-text);
  text-align: left;
  background: none;
  border: 0;
  border-radius: var(--ts-r-sm);
  cursor: pointer;
}

.ts-wb__links button:hover { background: var(--ts-surface-hover); color: var(--ts-accent); }
.ts-wb__links button:focus-visible { outline: 2px solid var(--ts-accent); outline-offset: -2px; }

.ts-wb__links i {
  display: grid;
  place-items: center;
  width: 18px;
  height: 18px;
  flex: none;
  font-size: 11px;
  font-style: normal;
  font-weight: 600;
  color: var(--ts-text-secondary);
  background: var(--ts-surface-sunken);
  border-radius: 4px;
}

.ts-wb__links button:hover i { color: var(--ts-accent); background: var(--ts-accent-soft); }
.ts-wb__links span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* ---------- 页脚 ---------- */
.ts-wb__foot {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 24px;
  font-size: var(--ts-fs-caption);
  color: var(--ts-text-hint);
}

.ts-wb__foot-label { color: var(--ts-text-secondary); }

/* ---------- 响应式 ---------- */
/*
 * 1280 以下就收起右栏，不是 1024。
 * 1024 视口下侧栏占 240、内容区留白 48，主区只剩 436px —— 5 列表格（148+76+88+124）
 * 挤不下，表头会互相压住、面板标题被压成竖排。单栏后主区 736px，表格才回得去。
 */
@media (max-width: 1279px) {
  .ts-wb__cols { grid-template-columns: minmax(0, 1fr); }
  /* 9 项按 auto-fit 会排成 6+3、第二行空三格；固定 5 列排成 5+4，缺口最小 */
  .ts-wb__kpis { grid-template-columns: repeat(5, minmax(0, 1fr)); }
  .ts-wb__kpi:nth-child(5n + 1) { border-left: 0; }
}

@media (max-width: 767px) {
  .ts-wb__head { flex-wrap: wrap; gap: 6px 8px; }
  .ts-wb__spacer { display: none; }
  .ts-wb__meta { width: 100%; }

  /* 指标条两列；奇数格去掉左边线，前两格去掉上边线 */
  .ts-wb__kpis { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .ts-wb__kpi { border-left: 1px solid var(--ts-border); border-top: 1px solid var(--ts-border); }
  .ts-wb__kpi:nth-child(odd) { border-left: 0; }
  .ts-wb__kpi:nth-child(-n + 2) { border-top: 0; }
  /* 指标是奇数个（9 项）时，最后一格跨满整行，避免右下角空半格 */
  .ts-wb__kpi:last-child:nth-child(odd) { grid-column: 1 / -1; border-left: 0; }

  /* 表格转卡片：与项目 ≤767 用卡片的约定一致 */
  .ts-wb__table thead { display: none; }
  .ts-wb__table,
  .ts-wb__table tbody,
  .ts-wb__table tr,
  .ts-wb__table td { display: block; }

  .ts-wb__table tr { padding: 9px 14px; border-bottom: 1px solid var(--ts-border); }
  .ts-wb__table td { height: auto; padding: 1px 0; border: 0; }
  .ts-wb__table .col-no,
  .ts-wb__table .col-who,
  .ts-wb__table .col-type,
  .ts-wb__table .col-op { width: auto; }
  .ts-wb__table .col-no { margin-bottom: 2px; }
  .ts-wb__table .col-op { padding-top: 6px; text-align: left; }
  .ts-wb__table .is-ellipsis { max-width: none; }

  .ts-wb__mini { flex-wrap: wrap; gap: 14px 22px; }
  .ts-wb__blocks { grid-template-columns: minmax(0, 1fr); }
  .ts-wb__block + .ts-wb__block { border-left: 0; border-top: 1px solid var(--ts-border); }
  .ts-wb__panel-head { flex-wrap: wrap; }
  .ts-wb__range { width: 100%; }
}
</style>
