<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deviceFaultApi } from '@/api/device'
import ListQueryNotice from '@/components/ListQueryNotice.vue'
import TablePage from '@/components/TablePage.vue'
import UserSelectDialog from '@/components/UserSelectDialog.vue'
import { useListQuery } from '@/composables/useListQuery'
import { useResponsive } from '@/composables/useResponsive'
import { useUserStore } from '@/store/user'
import { FAULT_STATUS_OPTIONS, faultStatusTagType, deviceStatusTagType, isOpenFault } from '@/types/device'
import type { DeviceFaultHandlePayload, DeviceFaultItem, FaultStatusCode } from '@/types/device'
import type { UserOption } from '@/types/user'
import type { ColumnDef } from '@/types/table'

/**
 * 设备故障记录（，V50 起放开给「报修处理人」）
 *
 * <h2>两种视角，同一页</h2>
 * - **管理员**：看全量台账，可按任意状态筛选，可报废、可强制处理；
 * - **报修处理人**（落在「申请类型与流程 → 故障报修 → 处理流程」配置范围内的人）：
 *   只看到**自己的待办** —— 未完结 且（还没人接 或 由他接）。
 *   这是**服务端强制的**：接口对非管理员恒按该范围过滤，
 *   传 `onlyMine=false` 也不会多出一行。页面上的差异（隐藏「待我处理」、
 *   收窄状态下拉、隐藏「报废」）只是把服务端的事实如实画出来，不是权限本身。
 *
 * 判据取 `userStore.adminOrAbove`（后端下发），与服务的准入判据同源；
 * **不要**用 `role === 'admin'` 自己拼一份 —— 那种口径迟早和服务端漂移。
 *
 * 来源三合一：借用人在使用中上报、管理员在台账直接登记、归还时登记为故障。
 *
 * <h2>V50：从「三态」变成处理流程</h2>
 * 处理链路：待处理 →（接单）处理中 →（修完）已修复 / 无法修复。
 * 处理动作：
 * - **接单**：待处理 → 处理中，接单人即被记为维修人。没有这一步，
 *   同一台设备可能两个人同时去修，而报修人也看不出「有没有人接手」；
 * - **维修完成**：设备「维修中 → 可用」；
 * - **无法修复**：维修结论 —— **设备状态不变**（仍维修中），报废与否由资产管理员另行决定。
 *   它与「报废」刻意分开，见后端 `FaultStatus` 的类注释；
 * - **标记报废**：设备 → 已报废（使用中禁止；状态机）。
 *
 * 说明：使用中上报的故障记录，其设备仍为「使用中」，需先归还并登记为故障，
 * 设备进入「维修中」后，此处才可接单 / 登记结果 / 报废。
 *
 * ---
 *  · W4-E：迁入公共表格层（`TablePage` + 列自定义）并接入筛选记忆（`useListQuery`）。
 *
 * 三条零回归铁律的落点：
 * 1. **无列偏好时 = 原硬编码列**：`columns` 逐列照抄改造前 `el-table-column` 的 label 与宽度
 *    （宽度一律用 `min-width`，与改造前一致 —— Element Plus 的 `width` 会被弹性布局压缩）；
 * 2. **移动端保留原卡片**：走 `#mobile` 插槽。本页卡片行与 PC 列**并非一一对应**
 *    （PC 的「处理信息」是「时间 + 人」两行合一格，卡片里拆成「处理时间」一行且**仅在已处理时出现**），
 *    由列定义强行生成卡片一定会改变既有呈现；
 * 3. **操作列锁定**：`configurable: false` —— 用户关掉它等于页面直接坏掉。
 *
 * 筛选记忆（决策 D4 第 1 条铁律）：`defaults` 逐字段等于改造前首次进入的取值
 * （`deviceKeyword` 空串、`status` 为 `null` = 未筛选），因此**无偏好时首次请求的参数与改造前完全一致**。
 */

const { isMobile } = useResponsive()
const userStore = useUserStore()

/** 是否为「看全量台账」的管理员视角（与服务端的准入判据同源） */
const adminView = computed(() => userStore.adminOrAbove)

/** 报废是**资产决策**，接口仍要求 device:fault:manage —— 一线维修人看不到这个按钮 */
const canScrapFaults = computed(() => userStore.hasPerm('device:fault:manage'))

/**
 * 状态筛选选项。
 *
 * 处理人只有未完结的单，若仍给出「已修复 / 已报废」，选了必然是空列表 ——
 * 那种「点了没反应」的控件比没有更让人困惑。
 */
const statusOptions = computed(() =>
  adminView.value ? FAULT_STATUS_OPTIONS : FAULT_STATUS_OPTIONS.filter((item) => isOpenFault(item.value))
)

/** 列定义（代码为事实源；无列偏好时逐列等于改造前渲染） */
const columns: ColumnDef[] = [
  { key: 'device', label: '设备', minWidth: 180, showOverflowTooltip: true },
  { key: 'orderNo', label: '关联工单', minWidth: 150, showOverflowTooltip: true },
  { key: 'faultDescription', label: '故障描述', minWidth: 200, showOverflowTooltip: true },
  { key: 'occurredAt', label: '发生时间', minWidth: 160, showOverflowTooltip: true },
  { key: 'reporterName', label: '上报人', width: 100 },
  { key: 'statusLabel', label: '故障状态', width: 100, align: 'center' },
  { key: 'deviceStatusLabel', label: '设备状态', width: 100, align: 'center' },
  { key: 'handledInfo', label: '处理信息', minWidth: 170, showOverflowTooltip: true },
  // P2：维修过程三项（人 / 费用 / 配件）。与「处理信息」（登记人 + 时间）分开两列 ——
  // 「谁登记的」与「谁动手修的」在维修外包场景下是两个人，合在一格里必然被读错。
  { key: 'repairInfo', label: '维修信息', minWidth: 190, showOverflowTooltip: true },
  // V50 起操作列最多同时出现四个动作（接单 / 维修完成 / 无法修复 / 报废），
  // 180px 会把它们挤成两行；260px 让最常见的一种状态恰好一行放下。
  { key: 'action', label: '操作', width: 260, fixed: 'right', configurable: false }
]

const loading = ref(false)
const records = ref<DeviceFaultItem[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)

/** 筛选条件（默认值 = 改造前首屏取值；仅这两个字段参与记忆，`page` / `size` 刻意不持久化） */
const { query, remembered, persist, reset } = useListQuery({
  routePath: '/asset/fault',
  // `onlyMine` 默认 false —— 首屏请求与改造前逐字节一致（零回归）
  defaults: { deviceKeyword: '', status: null as FaultStatusCode | null, onlyMine: false }
})

// 处理弹窗（维修完成 / 无法修复 / 报废共用）
const handleVisible = ref(false)
const handleKind = ref<'repair' | 'unrepairable' | 'scrap'>('repair')
const handleTarget = ref<DeviceFaultItem | null>(null)
const handleRemark = ref('')
const submitting = ref(false)
const acting = ref(false)

onMounted(load)

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await deviceFaultApi.page({
      page: page.value,
      size: size.value,
      deviceKeyword: query.deviceKeyword.trim() || undefined,
      status: query.status,
      onlyMine: query.onlyMine
    })
    records.value = result.records
    total.value = result.total
  } catch {
    records.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

/** 用户主动查询 = 唯一的落盘时机（D4 第 2 条：只有用户存了偏好才覆盖） */
function handleSearch(): void {
  page.value = 1
  void persist()
  void load()
}

/**
 * 重置：恢复默认值 + 清除本页持久化偏好（D4 第 4 条：既有「重置」按钮同时清空持久化）
 */
async function handleReset(): Promise<void> {
  await reset()
  page.value = 1
  await load()
}

/**
 * 「待我处理」勾选即查询。
 *
 * 它不跟其它筛选一起等「查询」按钮：这是一键切到「我的待办」的快捷入口，
 * 勾了却还要再点一次查询，会让人以为勾选没生效。
 */
function handleOnlyMineChange(): void {
  page.value = 1
  void persist()
  void load()
}

function handlePageChange(next: number): void {
  page.value = next
  void load()
}

function handleSizeChange(next: number): void {
  size.value = next
  page.value = 1
  void load()
}

/** 没人接单时可接单。接单后这单挂在自己名下，别人不能再接。 */
function canAccept(item: DeviceFaultItem): boolean {
  return item.status === 'PENDING_REPAIR'
}

/**
 * 未完结（待处理 / 处理中）且设备已收回（维修中）时才可登记结果。
 *
 * ⚠️ 判据必须用 isOpenFault 而不是 `=== 'PENDING_REPAIR'`：
 * 接单之后状态变成「处理中」，若仍按旧判据，接单人反而看不到「维修完成」按钮 ——
 * 接了单却交不了差，而且页面上一点报错都没有。
 */
function canRepair(item: DeviceFaultItem): boolean {
  return isOpenFault(item.status) && item.deviceStatus === 'MAINTENANCE'
}

/**
 * 判定「无法修复」的条件与「维修完成」相同 —— 都要先把设备收回来（维修中）。
 *
 * 它是**维修结论**、不是资产决策：设备状态不变，仍等资产管理员决定报废或调拨。
 * 因此它与「报废」是两个按钮、两条路径，不能合并。
 */
function canUnrepairable(item: DeviceFaultItem): boolean {
  return canRepair(item)
}

/** 未完结且设备为维修中 / 可用时可标记报废（使用中禁止，）—— 只判状态，不判权限 */
function canScrap(item: DeviceFaultItem): boolean {
  return isOpenFault(item.status) && (item.deviceStatus === 'MAINTENANCE' || item.deviceStatus === 'AVAILABLE')
}

/**
 * 当前用户此刻**能点**报废。
 *
 * 把「状态允许」与「权限允许」分成两个函数：`isSettled` 判的是「这条单还有没有事要做」，
 * 它只能看状态 —— 若把权限折进 `canScrap`，一条仍待资产决策的单会被判成「已处理」。
 */
function canScrapHere(item: DeviceFaultItem): boolean {
  return canScrapFaults.value && canScrap(item)
}

/**
 * 处理人视角下「这条单对我无事可做，但它在等资产决策」。
 *
 * 场景：故障未完结、设备已是「可用」（说明维修侧没事了），能做的只剩报废 ——
 * 那是资产管理员的事。此时既不该给出按钮，也不该显示「已处理」（它并没有结束），
 * 于是如实说明它在等谁。
 */
function needsAssetDecision(item: DeviceFaultItem): boolean {
  return (
    !canScrapFaults.value &&
    canScrap(item) &&
    !canRepair(item) &&
    !canAccept(item) &&
    !waitingReturn(item)
  )
}

/** 未完结但设备仍在使用中：提示需先归还登记故障 */
function waitingReturn(item: DeviceFaultItem): boolean {
  return isOpenFault(item.status) && item.deviceStatus === 'IN_USE'
}

/** 操作列没有任何可用动作 —— 即这单已经定性结束（已修复 / 无法修复 / 已报废） */
function isSettled(item: DeviceFaultItem): boolean {
  return !canAccept(item) && !canRepair(item) && !canScrap(item) && !waitingReturn(item)
}

/**
 * 接单。
 *
 * 二次确认不是走过场：接单后这单就挂在自己名下、别人不能再接，
 * 一次误点会让真正能修的人被挡在外面，而当事人只会觉得「系统卡住了」。
 */
async function acceptFault(item: DeviceFaultItem): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认接下「${deviceText(item)}」这单报修？接单后由你负责处理，其他人不能再接。`,
      '接单',
      { type: 'info', confirmButtonText: '接单', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  acting.value = true
  try {
    await deviceFaultApi.accept(item.id)
    ElMessage.success('已接单，请在修完后登记维修结果')
    await load()
  } catch {
    // 并发接单（刚被别人接走）等冲突由请求层统一提示
  } finally {
    acting.value = false
  }
}

// P2 维修过程（仅「维修完成」填）：维修人可以留空（外送维修没有系统账号）
const repairerId = ref<number | null>(null)
const repairerName = ref('')
const repairCost = ref('')
const replacedParts = ref('')
const pickerVisible = ref(false)

// 弹窗文案随动作变化（维持单一 dialog，避免三份几乎相同的表单各自漂移）
const handleDialogTitle = computed(() => {
  switch (handleKind.value) {
    case 'repair':
      return '登记维修完成'
    case 'scrap':
      return '故障设备报废'
    default:
      return '判定「无法修复」'
  }
})

const handleRemarkLabel = computed(() => {
  switch (handleKind.value) {
    case 'repair':
      return '维修说明'
    case 'scrap':
      return '报废原因'
    default:
      return '无法修复说明'
  }
})

const handleRemarkPlaceholder = computed(() => {
  switch (handleKind.value) {
    case 'repair':
      return '选填，如：更换屏幕后恢复正常'
    case 'scrap':
      return '选填，如：主板损坏无法修复'
    default:
      return '选填，如：主板进水腐蚀，无维修价值'
  }
})

const handleConfirmText = computed(() => {
  switch (handleKind.value) {
    case 'repair':
      return '确认维修完成'
    case 'scrap':
      return '确认报废'
    default:
      return '确认无法修复'
  }
})

const handleConfirmType = computed<'success' | 'warning' | 'danger'>(() => {
  switch (handleKind.value) {
    case 'repair':
      return 'success'
    case 'scrap':
      return 'danger'
    default:
      return 'warning'
  }
})

/**
 * 是否需要填「实际维修人」：修好了要，判定修不好**也要** ——
 * 「谁看过、结论是什么」同样需要留痕。只有报废不涉及（那是资产动作，不是维修动作）。
 */
const needsRepairer = computed(() => handleKind.value !== 'scrap')

function openHandle(item: DeviceFaultItem, kind: 'repair' | 'unrepairable' | 'scrap'): void {
  handleKind.value = kind
  handleTarget.value = item
  handleRemark.value = ''
  // 每次打开都清空：上一次填的维修人/费用绝不能被带到下一台设备上 ——
  // 这类「表单残留」造成的错账（把 A 设备的维修费记到 B 上）事后极难发现
  repairerId.value = null
  repairerName.value = ''
  repairCost.value = ''
  replacedParts.value = ''
  handleVisible.value = true
}

function handleRepairerPicked(picked: UserOption[]): void {
  const first = picked[0]
  repairerId.value = first ? first.id : null
  repairerName.value = first ? first.displayName || first.username : ''
}

function clearRepairer(): void {
  repairerId.value = null
  repairerName.value = ''
}

/**
 * 维修费用输入 → 数值。
 *
 * 空串按**未填**处理（返回 null），而不是 0：
 * 「0 元维修」（保修期内免费）与「没填维修费」在统计里是两件事，
 * 混成一个 0 会让「平均维修成本」被大量无意义的 0 拉低。
 */
function parseCost(): number | null {
  const raw = repairCost.value.trim()
  if (!raw) {
    return null
  }
  const value = Number(raw)
  return Number.isFinite(value) ? value : null
}

async function submitHandle(): Promise<void> {
  const target = handleTarget.value
  if (!target) {
    return
  }
  submitting.value = true
  try {
    const kind = handleKind.value
    const payload: DeviceFaultHandlePayload = {
      remark: handleRemark.value.trim() || null,
      // 维修人：修好与「修不好」都要记（后者同样要留下「谁看的」）；只有报废不涉及。
      repairerId: needsRepairer.value ? repairerId.value : null,
      // 费用 / 配件**只有「维修完成」才提交**：
      // 「无法修复」时后端不落这两列，传过去只会让填表的人以为记上了、其实什么都没存。
      repairCost: kind === 'repair' ? parseCost() : null,
      replacedParts: kind === 'repair' ? replacedParts.value.trim() || null : null
    }
    if (kind === 'repair') {
      await deviceFaultApi.repair(target.id, payload)
      ElMessage.success('已登记维修完成，设备回到可用')
    } else if (kind === 'scrap') {
      await deviceFaultApi.scrap(target.id, payload)
      ElMessage.success('设备已标记报废')
    } else {
      await deviceFaultApi.markUnrepairable(target.id, payload)
      ElMessage.success('已判定「无法修复」，设备仍为维修中，待资产处理')
    }
    handleVisible.value = false
    await load()
  } catch {
    // 设备状态已变化等冲突由请求层统一提示
  } finally {
    submitting.value = false
  }
}

function deviceText(item: DeviceFaultItem): string {
  if (!item.deviceName) {
    return '-'
  }
  return item.assetNo ? `${item.deviceName}（${item.assetNo}）` : item.deviceName
}
</script>

<template>
  <div class="ts-page">
    <section class="ts-card">
      <div class="ts-flex-between ts-df__head">
        <div>
          <h3 class="ts-df__title">设备故障记录</h3>
          <p v-if="adminView" class="ts-text-secondary ts-df__desc">
            汇总借用人上报、台账登记、归还登记的故障。处理流程：待处理 → 接单（处理中）→ 已修复 / 无法修复。
            设备处于「维修中」时才可接单后的登记结果或报废；使用中上报的故障需先归还并登记为故障。
          </p>
          <p v-else class="ts-text-secondary ts-df__desc">
            你负责的设备报修待办：待处理的可以直接<b>接单</b>，接单后修完登记「已修复」，
            修不了的登记「无法修复」。已结束的单会移出本列表，不再出现在这里。
            需要报废的设备由资产管理员处理。
          </p>
        </div>
        <el-button :loading="loading" @click="load">刷新</el-button>
      </div>

      <ListQueryNotice :visible="remembered" class="ts-mt-16" @clear="handleReset" />

      <!-- 查询条件 -->
      <div class="ts-df__filters ts-mt-16">
        <el-input
          v-model="query.deviceKeyword"
          class="ts-df__filter-keyword"
          placeholder="设备名称 / 资产编号"
          clearable
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
        <el-select v-model="query.status" class="ts-df__filter-item" placeholder="故障状态" clearable>
          <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <!--
          「待我处理」= 未完结 + 没人接单或我接的单；勾选即查询。
          只给管理员：对处理人来说服务端本来就只返回这个范围，
          再摆一个勾不动（勾不勾都一样）的开关，只会让人以为它坏了。
        -->
        <el-checkbox
          v-if="adminView"
          v-model="query.onlyMine"
          class="ts-df__filter-check"
          @change="handleOnlyMineChange"
        >
          待我处理
        </el-checkbox>
        <div class="ts-df__filter-actions">
          <el-button type="primary" :loading="loading" @click="handleSearch">查询</el-button>
          <el-button @click="handleReset">重置</el-button>
        </div>
      </div>

      <TablePage
        :columns="columns"
        :rows="records"
        :loading="loading"
        route-path="/asset/fault"
        :page="page"
        :size="size"
        :total="total"
        empty-text="暂无故障记录"
        class="ts-mt-16"
        @update:page="handlePageChange"
        @update:size="handleSizeChange"
      >
        <template #cell-device="{ row }">{{ deviceText(row as DeviceFaultItem) }}</template>
        <template #cell-orderNo="{ row }">
          <span v-if="(row as DeviceFaultItem).orderNo">{{ (row as DeviceFaultItem).orderNo }}</span>
          <span v-else class="ts-text-hint">台账登记</span>
        </template>
        <template #cell-faultDescription="{ row }">{{ (row as DeviceFaultItem).faultDescription }}</template>
        <template #cell-occurredAt="{ row }">{{ (row as DeviceFaultItem).occurredAt }}</template>
        <template #cell-reporterName="{ row }">{{ (row as DeviceFaultItem).reporterName || '-' }}</template>
        <template #cell-statusLabel="{ row }">
          <el-tag :type="faultStatusTagType((row as DeviceFaultItem).status)" size="small" effect="plain">
            {{ (row as DeviceFaultItem).statusLabel }}
          </el-tag>
        </template>
        <template #cell-deviceStatusLabel="{ row }">
          <el-tag
            v-if="(row as DeviceFaultItem).deviceStatus"
            :type="deviceStatusTagType((row as DeviceFaultItem).deviceStatus!)"
            size="small"
            effect="plain"
          >
            {{ (row as DeviceFaultItem).deviceStatusLabel }}
          </el-tag>
          <span v-else class="ts-text-hint">-</span>
        </template>
        <template #cell-handledInfo="{ row }">
          <template v-if="(row as DeviceFaultItem).handledAt">
            <div>{{ (row as DeviceFaultItem).handledAt }}</div>
            <div class="ts-text-hint">{{ (row as DeviceFaultItem).handledByName || '-' }}</div>
          </template>
          <span v-else class="ts-text-hint">待处理</span>
        </template>
        <template #cell-repairInfo="{ row }">
          <template v-if="(row as DeviceFaultItem).repairerName || (row as DeviceFaultItem).repairCost != null || (row as DeviceFaultItem).replacedParts">
            <div v-if="(row as DeviceFaultItem).repairerName">维修人：{{ (row as DeviceFaultItem).repairerName }}</div>
            <div v-if="(row as DeviceFaultItem).repairCost != null" class="ts-text-hint">
              费用：¥{{ (row as DeviceFaultItem).repairCost }}
            </div>
            <div v-if="(row as DeviceFaultItem).replacedParts" class="ts-text-hint">
              配件：{{ (row as DeviceFaultItem).replacedParts }}
            </div>
          </template>
          <span v-else class="ts-text-hint">—</span>
        </template>
        <template #cell-action="{ row }">
          <el-button
            v-if="canAccept(row as DeviceFaultItem)"
            link
            type="primary"
            size="small"
            :loading="acting"
            @click="acceptFault(row as DeviceFaultItem)"
          >
            接单
          </el-button>
          <el-button
            v-if="canRepair(row as DeviceFaultItem)"
            link
            type="success"
            size="small"
            @click="openHandle(row as DeviceFaultItem, 'repair')"
          >
            维修完成
          </el-button>
          <el-button
            v-if="canUnrepairable(row as DeviceFaultItem)"
            link
            type="warning"
            size="small"
            @click="openHandle(row as DeviceFaultItem, 'unrepairable')"
          >
            无法修复
          </el-button>
          <el-button
            v-if="canScrapHere(row as DeviceFaultItem)"
            link
            type="danger"
            size="small"
            @click="openHandle(row as DeviceFaultItem, 'scrap')"
          >
            报废
          </el-button>
          <span v-if="waitingReturn(row as DeviceFaultItem)" class="ts-text-hint">待归还登记故障</span>
          <span
            v-if="needsAssetDecision(row as DeviceFaultItem)"
            class="ts-text-hint"
          >
            待资产管理员处理
          </span>
          <span v-if="isSettled(row as DeviceFaultItem)" class="ts-text-hint">已处理</span>
        </template>

        <!-- 移动端卡片：保留改造前结构（「处理时间」仅在已处理时出现） -->
        <template #mobile>
          <div class="ts-df__cards">
            <div v-for="row in records" :key="row.id" class="ts-df__card">
              <div class="ts-flex-between">
                <strong class="ts-df__card-title">{{ deviceText(row) }}</strong>
                <el-tag :type="faultStatusTagType(row.status)" size="small" effect="plain">{{ row.statusLabel }}</el-tag>
              </div>
              <div class="ts-df__card-row">
                <span class="ts-text-hint">关联工单</span>
                <span class="ts-df__card-value">{{ row.orderNo || '台账登记' }}</span>
              </div>
              <div class="ts-df__card-row">
                <span class="ts-text-hint">故障描述</span>
                <span class="ts-df__card-value">{{ row.faultDescription }}</span>
              </div>
              <div class="ts-df__card-row">
                <span class="ts-text-hint">发生时间</span>
                <span>{{ row.occurredAt }}</span>
              </div>
              <div class="ts-df__card-row">
                <span class="ts-text-hint">上报人</span>
                <span>{{ row.reporterName || '-' }}</span>
              </div>
              <div class="ts-df__card-row">
                <span class="ts-text-hint">设备状态</span>
                <el-tag v-if="row.deviceStatus" :type="deviceStatusTagType(row.deviceStatus)" size="small" effect="plain">
                  {{ row.deviceStatusLabel }}
                </el-tag>
                <span v-else>-</span>
              </div>
              <div v-if="row.handledAt" class="ts-df__card-row">
                <span class="ts-text-hint">处理时间</span>
                <span>{{ row.handledAt }}</span>
              </div>
              <div class="ts-df__card-actions">
                <el-button
                  v-if="canAccept(row)"
                  size="small"
                  type="primary"
                  plain
                  :loading="acting"
                  @click="acceptFault(row)"
                >
                  接单
                </el-button>
                <el-button v-if="canRepair(row)" size="small" type="success" plain @click="openHandle(row, 'repair')">
                  维修完成
                </el-button>
                <el-button
                  v-if="canUnrepairable(row)"
                  size="small"
                  type="warning"
                  plain
                  @click="openHandle(row, 'unrepairable')"
                >
                  无法修复
                </el-button>
                <el-button
                  v-if="canScrapHere(row)"
                  size="small"
                  type="danger"
                  plain
                  @click="openHandle(row, 'scrap')"
                >
                  报废
                </el-button>
                <span v-if="waitingReturn(row)" class="ts-text-hint">待归还登记故障后可处理</span>
                <span v-if="needsAssetDecision(row)" class="ts-text-hint">待资产管理员处理</span>
              </div>
            </div>
            <el-empty v-if="!loading && records.length === 0" :image-size="70" description="暂无故障记录" />
          </div>
        </template>
      </TablePage>
    </section>

    <!-- 维修人选择（P2）：复用通用用户选择器，单选取第一人 -->
    <UserSelectDialog v-model="pickerVisible" :multiple="false" @confirm="handleRepairerPicked" />

    <!-- 处理弹窗（维修完成 / 报废） -->
    <el-dialog
      v-model="handleVisible"
      :title="handleDialogTitle"
      :width="isMobile ? '94%' : '460px'"
    >
      <p class="ts-text-secondary ts-df__hint">
        设备：{{ handleTarget ? deviceText(handleTarget) : '-' }}
      </p>
      <el-form label-width="90px" :label-position="isMobile ? 'top' : 'right'">
        <el-form-item :label="handleRemarkLabel">
          <el-input
            v-model="handleRemark"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            :placeholder="handleRemarkPlaceholder"
          />
        </el-form-item>

        <!-- 维修人：修好了要记，判定「无法修复」也要记（谁看的、结论是什么） -->
        <el-form-item v-if="needsRepairer" label="实际维修人">
          <el-input :model-value="repairerName" readonly placeholder="选填；外送维修可留空">
            <template #append>
              <el-button @click="pickerVisible = true">选择</el-button>
            </template>
          </el-input>
          <el-button v-if="repairerId != null" link type="danger" @click="clearRepairer">清除</el-button>
        </el-form-item>
        <!-- 费用 / 配件只有「维修完成」会落库，因此只在那一档出现 -->
        <el-form-item v-if="handleKind === 'repair'" label="维修费用">
          <el-input v-model="repairCost" placeholder="元，如 320（可留空；保修内免费填 0）" />
        </el-form-item>
        <el-form-item v-if="handleKind === 'repair'" label="更换配件">
          <el-input v-model="replacedParts" maxlength="200" placeholder="选填，如：屏幕总成 ×1" />
        </el-form-item>
      </el-form>
      <p v-if="handleKind === 'unrepairable'" class="ts-text-secondary ts-df__warn">
        判定「无法修复」后，这单报修到此结束；<b>设备状态不变</b>（仍为「维修中」），
        由资产管理员另行决定报废或调拨 —— 这一步不代表设备已报废。
      </p>
      <p v-if="handleKind === 'scrap'" class="ts-text-secondary ts-df__warn">
        报废后设备进入「已报废」，不可再被借用；资产编号不可复用。
      </p>
      <template #footer>
        <el-button @click="handleVisible = false">取消</el-button>
        <el-button :type="handleConfirmType" :loading="submitting" @click="submitHandle">
          {{ handleConfirmText }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.ts-df__head {
  gap: 16px;
  align-items: flex-start;
}

.ts-df__title {
  margin: 0;
  font-size: 16px;
  font-weight: 500;
}

.ts-df__desc {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.6;
}

.ts-df__filters {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.ts-df__filter-item {
  width: 170px;
}

.ts-df__filter-keyword {
  width: 240px;
}

.ts-df__filter-check {
  margin: 0 4px;
  white-space: nowrap;
}

.ts-df__filter-actions {
  display: flex;
  gap: 8px;
}

.ts-df__hint {
  margin: 0 0 12px;
  font-size: 12px;
  line-height: 1.6;
}

.ts-df__warn {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-color-danger);
}

.ts-df__cards {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.ts-df__card {
  padding: 12px;
  border: 1px solid var(--ts-border);
  border-radius: 10px;
  background: #fbfcfe;
}

.ts-df__card-title {
  word-break: break-all;
}

.ts-df__card-value {
  word-break: break-all;
  text-align: right;
}

.ts-df__card-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 3px 0;
  font-size: 13px;
}

.ts-df__card-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
}

@media (max-width: 767px) {
  .ts-df__head {
    flex-direction: column;
  }

  .ts-df__head .el-button {
    width: 100%;
  }

  .ts-df__filter-item,
  .ts-df__filter-keyword {
    width: 100%;
  }

  .ts-df__filter-check {
    margin: 0 0 4px;
  }

  .ts-df__filter-actions .el-button {
    flex: 1 1 0;
  }

  .ts-df__card-actions .el-button {
    flex: 1 1 0;
    margin-left: 0;
  }
}
</style>
