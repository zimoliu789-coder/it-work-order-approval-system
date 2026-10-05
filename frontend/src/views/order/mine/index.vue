<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { orderApi } from '@/api/order'
import { deviceFaultApi } from '@/api/device'
import OrderDetailDialog from '@/components/OrderDetailDialog.vue'
import TablePage from '@/components/TablePage.vue'
import UrgeButton from '@/components/UrgeButton.vue'
import { useExport } from '@/composables/useExport'
import { useResponsive } from '@/composables/useResponsive'
import { ORDER_STATUS_OPTIONS, orderStatusTagType } from '@/types/order'
import type { OrderExtendPayload, OrderItem, OrderStatusCode } from '@/types/order'
import type { ColumnDef } from '@/types/table'

/**
 * 我的工单（我提交的借用申请，）
 *
 * 能力：按状态 / 关键词查询、分页、查看详情、撤回、发起归还、**申请延期**、**上报故障**。
 * 撤回边界（ / ）：仅「审批中」「待交付」可撤回；撤回后工单转已撤回并释放设备。
 * 归还边界（，需求方  ）：
 * - 仅「使用中」且我为申请人才展示「归还设备」，点击后工单转「待收回」（设备仍保持使用中）；
 * - 是否可归还由服务端 canRequestReturn 判定，前端只负责渲染，避免权限判断散落多处；
 * - 「待收回」状态等待实际执行人确认收回，申请人无法自行确认。
 *
 *  追加：
 * - 申请延期：使用中且未用尽延期次数的短期借用可发起，服务端 canRequestExtend 判定；
 * - 上报故障：使用中的设备可在工单内上报，设备保持「使用中」，维修由管理员登记。
 */

const { isMobile } = useResponsive()

/**
 * 列定义（ · M3-B）
 *
 * 「我提交的」与「抄送我的」是两张独立的表，各有一份偏好（按路由键隔离）。
 * 逐列照抄改造前 `el-table-column` 的写法，保证无偏好时渲染与改造前一致；
 * 操作列锁定常显（`configurable: false`）。
 */
const columns: ColumnDef[] = [
  { key: 'orderNo', label: '工单编号', minWidth: 170, showOverflowTooltip: true },
  { key: 'deviceName', label: '设备', minWidth: 160, showOverflowTooltip: true },
  { key: 'useTypeLabel', label: '借用类型', width: 110, align: 'center' },
  { key: 'statusLabel', label: '状态', width: 128, align: 'center' },
  { key: 'expectedReturnDate', label: '期望归还', minWidth: 120 },
  { key: 'actualFinalHandlerName', label: '当前执行人', minWidth: 110 },
  { key: 'returnInfo', label: '归还信息', minWidth: 180, showOverflowTooltip: true },
  { key: 'createdAt', label: '提交时间', minWidth: 170, showOverflowTooltip: true },
  { key: 'action', label: '操作', width: 330, fixed: 'right', configurable: false }
]

const ccColumns: ColumnDef[] = [
  { key: 'orderNo', label: '工单编号', minWidth: 170, showOverflowTooltip: true },
  { key: 'applyTypeName', label: '申请类型 / 设备', minWidth: 170, showOverflowTooltip: true },
  { key: 'applicantName', label: '申请人', minWidth: 110, showOverflowTooltip: true },
  { key: 'statusLabel', label: '状态', width: 128, align: 'center' },
  { key: 'createdAt', label: '提交时间', minWidth: 170, showOverflowTooltip: true },
  { key: 'action', label: '操作', width: 110, fixed: 'right', configurable: false }
]

const loading = ref(false)
const records = ref<OrderItem[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)

/**
 * 顶部页签：「我提交的」与「抄送我的」。
 *
 * 之所以做成页签而不是新菜单：抄送是「知情」而不是「事务」，
 * 被抄送人不会天天来逛，单独占一个菜单反而是噪音。挂在「我的工单」下最符合直觉。
 */
const activeTab = ref<'mine' | 'cc'>('mine')

// --- 抄送我的（，只读） ---
const ccLoading = ref(false)
const ccRecords = ref<OrderItem[]>([])
const ccTotal = ref(0)
const ccPage = ref(1)
const ccSize = ref(10)
/** 首次切到抄送页签才拉取，避免每次进页面都白打一个请求 */
let ccLoaded = false

const query = reactive({
  keyword: '',
  status: null as OrderStatusCode | null
})

/** 导出交互：普通员工导出仅限自己名下的工单（scope=MINE，后端强制） */
const { loading: exporting, runExport } = useExport()

/** 导出当前筛选条件下的「我的工单」 */
async function handleExport(): Promise<void> {
  await runExport(
    {
      type: 'ORDER',
      scope: 'MINE',
      order: {
        keyword: query.keyword.trim() || undefined,
        status: query.status ?? undefined
      }
    },
    '我的工单.xlsx'
  )
}

const detailVisible = ref(false)
const detailId = ref<number | null>(null)
const acting = ref(false)

// --- 延期申请 ---
const extendVisible = ref(false)
const extendTarget = ref<OrderItem | null>(null)
const extendForm = reactive({
  newEndTime: '',
  reason: ''
})
const extendSubmitting = ref(false)

// --- 上报故障 ---
const faultVisible = ref(false)
const faultTarget = ref<OrderItem | null>(null)
const faultForm = reactive({
  faultDescription: '',
  occurredAt: ''
})
const faultSubmitting = ref(false)

onMounted(load)

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await orderApi.mine({
      page: page.value,
      size: size.value,
      keyword: query.keyword.trim() || undefined,
      status: query.status
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

function handleSearch(): void {
  page.value = 1
  void load()
}

function handleReset(): void {
  query.keyword = ''
  query.status = null
  page.value = 1
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

/**
 * 页签切换。只在首次进入「抄送我的」时拉取，之后由用户翻页刷新。
 */
function handleTabChange(name: string | number): void {
  if (name === 'cc' && !ccLoaded) {
    void loadCc()
  }
}

/** 抄送我的（只读）：仅展示，无任何操作入口 —— 被抄送人无权审批/撤回/归还 */
async function loadCc(): Promise<void> {
  ccLoading.value = true
  try {
    const result = await orderApi.ccOrders(ccPage.value, ccSize.value)
    ccRecords.value = result.records
    ccTotal.value = result.total
    ccLoaded = true
  } catch {
    ccRecords.value = []
    ccTotal.value = 0
  } finally {
    ccLoading.value = false
  }
}

function handleCcPageChange(next: number): void {
  ccPage.value = next
  void loadCc()
}

function handleCcSizeChange(next: number): void {
  ccSize.value = next
  ccPage.value = 1
  void loadCc()
}

/** 仅审批中 / 待交付可撤回，其余状态下不展示撤回入口 */
function canCancel(order: OrderItem): boolean {
  return order.status === 'PENDING_APPROVAL' || order.status === 'PENDING_DELIVERY'
}

/** 使用中且我是申请人时可发起归还（服务端判定，） */
function canRequestReturn(order: OrderItem): boolean {
  return order.canRequestReturn === true
}

/** 使用中且未用尽延期次数时可申请延期（服务端判定，） */
function canRequestExtend(order: OrderItem): boolean {
  return order.canRequestExtend === true
}

/** 使用中的设备可由借用人上报故障，本页仅展示我提交的工单 */
function canReportFault(order: OrderItem): boolean {
  return order.status === 'BORROWED'
}

/** 是否展示「已超时」红标（超时是标记位，与状态标签并列，） */
function isTimeout(order: OrderItem): boolean {
  return order.borrowTimeout === true
}

function openDetail(order: OrderItem): void {
  detailId.value = order.id
  detailVisible.value = true
}

async function handleCancel(order: OrderItem): Promise<void> {
  let reason = ''
  try {
    const result = await ElMessageBox.prompt(
      `确认撤回工单「${order.orderNo}」（设备：${order.deviceName ?? '-'}）？撤回后设备将被释放，如仍需使用请重新提交申请。`,
      '撤回申请',
      {
        type: 'warning',
        confirmButtonText: '确认撤回',
        cancelButtonText: '取消',
        inputPlaceholder: '撤回原因（选填）',
        inputValidator: () => true
      }
    )
    reason = result.value ?? ''
  } catch {
    return
  }
  await doCancel(order, reason)
}

async function doCancel(order: OrderItem, reason: string): Promise<void> {
  acting.value = true
  try {
    await orderApi.cancel(order.id, reason.trim() || null)
    ElMessage.success('工单已撤回')
    detailVisible.value = false
    await load()
  } catch {
    // 状态已变化等非法操作由请求层统一提示
  } finally {
    acting.value = false
  }
}

/**
 * 发起归还（ 第一步，）
 *
 * 提示语明确「设备此时仍在使用中、需等执行人确认收回」，避免申请人误以为点完就已归还。
 */
async function handleRequestReturn(order: OrderItem): Promise<void> {
  let note = ''
  try {
    const result = await ElMessageBox.prompt(
      `确认归还设备「${order.deviceName ?? '-'}」？提交后工单进入「待收回」，需等待实际执行人「${
        order.actualFinalHandlerName ?? '处理人'
      }」确认收回后，设备才会重新变为可用。`,
      '发起归还',
      {
        type: 'info',
        confirmButtonText: '确认发起归还',
        cancelButtonText: '取消',
        inputPlaceholder: '归还说明（选填，如：外观完好、配件齐全）',
        inputValidator: () => true
      }
    )
    note = result.value ?? ''
  } catch {
    return
  }
  await doRequestReturn(order, note)
}

async function doRequestReturn(order: OrderItem, note: string): Promise<void> {
  acting.value = true
  try {
    await orderApi.requestReturn(order.id, note.trim() || null)
    ElMessage.success('已发起归还，等待实际执行人确认收回')
    detailVisible.value = false
    await load()
  } catch {
    // 状态已变化等非法操作由请求层统一提示
  } finally {
    acting.value = false
  }
}

// ------------------------------------------------------------------
// 申请延期
// ------------------------------------------------------------------

function openExtend(order: OrderItem): void {
  extendTarget.value = order
  extendForm.newEndTime = ''
  extendForm.reason = ''
  extendVisible.value = true
}

/** 新结束时间必须晚于当前时间 */
function disabledExtendDate(date: Date): boolean {
  return date.getTime() < Date.now()
}

async function submitExtend(): Promise<void> {
  const target = extendTarget.value
  if (!target) {
    return
  }
  if (!extendForm.newEndTime) {
    ElMessage.warning('请选择延期后的结束时间')
    return
  }
  if (!extendForm.reason.trim()) {
    ElMessage.warning('请填写延期原因')
    return
  }
  const payload: OrderExtendPayload = {
    newEndTime: extendForm.newEndTime,
    reason: extendForm.reason.trim()
  }
  extendSubmitting.value = true
  try {
    await orderApi.requestExtend(target.id, payload)
    ElMessage.success('延期申请已提交，等待审批')
    extendVisible.value = false
    detailVisible.value = false
    await load()
  } catch {
    // 次数用尽 / 已有审批中的延期等冲突由请求层统一提示
  } finally {
    extendSubmitting.value = false
  }
}

// ------------------------------------------------------------------
// 上报故障
// ------------------------------------------------------------------

function openFault(order: OrderItem): void {
  faultTarget.value = order
  faultForm.faultDescription = ''
  faultForm.occurredAt = nowText()
  faultVisible.value = true
}

/** 当前时间 yyyy-MM-dd HH:mm:ss（故障发生时间默认值） */
function nowText(): string {
  const d = new Date()
  const pad = (n: number): string => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(
    d.getMinutes()
  )}:${pad(d.getSeconds())}`
}

/** 故障发生时间不得晚于当前时间 */
function disabledOccurredDate(date: Date): boolean {
  return date.getTime() > Date.now()
}

async function submitFault(): Promise<void> {
  const target = faultTarget.value
  if (!target) {
    return
  }
  // 自定义申请不占用设备，理论上不会走到故障上报；此处兜底防止 deviceId 为空
  if (target.deviceId == null) {
    ElMessage.warning('该工单未关联设备，无法上报故障')
    return
  }
  if (!faultForm.faultDescription.trim()) {
    ElMessage.warning('请填写故障描述')
    return
  }
  if (!faultForm.occurredAt) {
    ElMessage.warning('请选择故障发生时间')
    return
  }
  faultSubmitting.value = true
  try {
    await deviceFaultApi.report({
      deviceId: target.deviceId,
      orderId: target.id,
      faultDescription: faultForm.faultDescription.trim(),
      occurredAt: faultForm.occurredAt
    })
    ElMessage.success('故障已上报，设备仍可由您继续使用，管理员维修后恢复可用')
    faultVisible.value = false
    detailVisible.value = false
    await load()
  } catch {
    // 非法状态（非使用中 / 非本人设备）由请求层统一提示
  } finally {
    faultSubmitting.value = false
  }
}

function deviceText(order: OrderItem): string {
  return order.deviceName ?? '-'
}

/** 到期日仅短期借用展示（需求方 ：长期领用无固定归还日期） */
function showExpectedReturn(order: OrderItem): boolean {
  return order.useType === 'SHORT_TERM'
}

function useTypeTagType(order: OrderItem): 'warning' | 'success' {
  return order.useType === 'SHORT_TERM' ? 'warning' : 'success'
}
</script>

<template>
  <div class="ts-page">
    <!--  页签：我提交的 / 抄送我的。pane 内容为空，实体内容由下方两个 section 承担 -->
    <el-tabs v-model="activeTab" class="ts-om__tabs" @tab-change="handleTabChange">
      <el-tab-pane label="我提交的" name="mine" />
      <el-tab-pane label="抄送我的" name="cc" />
    </el-tabs>

    <section v-show="activeTab === 'mine'" class="ts-card">
      <div class="ts-flex-between ts-om__head">
        <div>
          <h3 class="ts-om__title">我的工单</h3>
          <p class="ts-text-secondary ts-om__desc">
            我提交的全部借用申请。审批中与待交付状态可自行撤回；使用中可「归还设备」「申请延期」，设备故障可在此上报，待实际执行人确认收回后完成归还。
          </p>
        </div>
        <el-space>
          <el-button :loading="exporting" @click="handleExport">导出 Excel</el-button>
          <el-button type="primary" @click="$router.push('/order/apply')">+ 提交申请</el-button>
        </el-space>
      </div>

      <!-- 查询条件 -->
      <div class="ts-om__filters ts-mt-16">
        <el-input
          v-model="query.keyword"
          class="ts-om__filter-keyword"
          placeholder="工单编号 / 设备名称"
          clearable
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
        <el-select v-model="query.status" class="ts-om__filter-item" placeholder="工单状态" clearable>
          <el-option v-for="item in ORDER_STATUS_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <div class="ts-om__filter-actions">
          <el-button type="primary" :loading="loading" @click="handleSearch">查询</el-button>
          <el-button @click="handleReset">重置</el-button>
        </div>
      </div>

      <TablePage
        :columns="columns"
        :rows="records"
        :loading="loading"
        route-path="/order/mine"
        :page="page"
        :size="size"
        :total="total"
        empty-text="暂无工单，去「提交申请」发起一笔吧"
        class="ts-mt-16"
        @update:page="handlePageChange"
        @update:size="handleSizeChange"
      >
        <template #cell-deviceName="{ row }">{{ deviceText(row as OrderItem) }}</template>
        <template #cell-useTypeLabel="{ row }">
          <el-tag :type="useTypeTagType(row as OrderItem)" size="small" effect="plain">
            {{ (row as OrderItem).useTypeLabel }}
          </el-tag>
        </template>
        <template #cell-statusLabel="{ row }">
          <el-tag :type="orderStatusTagType((row as OrderItem).status)" size="small" effect="plain">
            {{ (row as OrderItem).statusLabel }}
          </el-tag>
          <el-tag v-if="isTimeout(row as OrderItem)" type="danger" size="small" effect="dark" class="ts-om__badge">
            已超时
          </el-tag>
          <el-tag
            v-if="(row as OrderItem).extendPending"
            type="warning"
            size="small"
            effect="plain"
            class="ts-om__badge"
          >
            延期审批中
          </el-tag>
        </template>
        <template #cell-expectedReturnDate="{ row }">
          <span v-if="showExpectedReturn(row as OrderItem)">{{ (row as OrderItem).expectedReturnDate || '-' }}</span>
          <span v-else class="ts-text-hint">长期领用</span>
        </template>
        <template #cell-actualFinalHandlerName="{ row }">
          <span v-if="(row as OrderItem).actualFinalHandlerName">{{ (row as OrderItem).actualFinalHandlerName }}</span>
          <span v-else class="ts-text-hint">-</span>
        </template>
        <template #cell-returnInfo="{ row }">
          <template v-if="(row as OrderItem).status === 'RETURNED'">
            <div>{{ (row as OrderItem).actualEndTime || '-' }}</div>
            <div class="ts-text-hint">收回人：{{ (row as OrderItem).returnedByName || '-' }}</div>
          </template>
          <span v-else-if="(row as OrderItem).status === 'PENDING_RETURN'" class="ts-text-hint">
            等待处理人收回
          </span>
          <span v-else class="ts-text-hint">-</span>
        </template>
        <template #cell-action="{ row }">
          <el-button link type="primary" size="small" @click="openDetail(row as OrderItem)">详情</el-button>
          <UrgeButton
            v-if="(row as OrderItem).status === 'PENDING_APPROVAL'"
            :order="row as OrderItem"
            kind="approval"
            link
            @done="load"
          />
          <el-button
            v-if="canRequestExtend(row as OrderItem)"
            link
            type="success"
            size="small"
            @click="openExtend(row as OrderItem)"
          >
            申请延期
          </el-button>
          <el-button
            v-if="canReportFault(row as OrderItem)"
            link
            type="danger"
            size="small"
            @click="openFault(row as OrderItem)"
          >
            上报故障
          </el-button>
          <el-button
            v-if="canRequestReturn(row as OrderItem)"
            link
            type="warning"
            size="small"
            @click="handleRequestReturn(row as OrderItem)"
          >
            归还设备
          </el-button>
          <el-button
            v-if="canCancel(row as OrderItem)"
            link
            type="danger"
            size="small"
            @click="handleCancel(row as OrderItem)"
          >
            撤回
          </el-button>
        </template>

        <!-- 移动端卡片保留改造前结构（「归还信息」在卡片里拆成两行且仅在已归还时出现） -->
        <template #mobile>
          <div v-loading="loading" class="ts-om__cards">
            <div v-for="row in records" :key="row.id" class="ts-om__card">
              <div class="ts-flex-between">
                <strong class="ts-om__card-title">{{ deviceText(row) }}</strong>
                <div class="ts-om__tags">
                  <el-tag :type="orderStatusTagType(row.status)" size="small" effect="plain">
                    {{ row.statusLabel }}
                  </el-tag>
                  <el-tag v-if="isTimeout(row)" type="danger" size="small" effect="dark">已超时</el-tag>
                  <el-tag v-if="row.extendPending" type="warning" size="small" effect="plain">延期审批中</el-tag>
                </div>
              </div>
              <div class="ts-om__card-row">
                <span class="ts-text-hint">工单编号</span>
                <span class="ts-om__card-value">{{ row.orderNo }}</span>
              </div>
              <div class="ts-om__card-row">
                <span class="ts-text-hint">借用类型</span>
                <el-tag :type="useTypeTagType(row)" size="small" effect="plain">{{ row.useTypeLabel }}</el-tag>
              </div>
              <div v-if="showExpectedReturn(row)" class="ts-om__card-row">
                <span class="ts-text-hint">期望归还</span>
                <span>{{ row.expectedReturnDate || '-' }}</span>
              </div>
              <div class="ts-om__card-row">
                <span class="ts-text-hint">当前执行人</span>
                <span>{{ row.actualFinalHandlerName || '-' }}</span>
              </div>
              <div v-if="row.status === 'RETURNED'" class="ts-om__card-row">
                <span class="ts-text-hint">归还时间</span>
                <span>{{ row.actualEndTime || '-' }}</span>
              </div>
              <div v-if="row.status === 'RETURNED'" class="ts-om__card-row">
                <span class="ts-text-hint">实际收回人</span>
                <span>{{ row.returnedByName || '-' }}</span>
              </div>
              <div class="ts-om__card-row">
                <span class="ts-text-hint">提交时间</span>
                <span>{{ row.createdAt }}</span>
              </div>
              <div class="ts-om__card-actions">
                <el-button size="small" @click="openDetail(row)">详情</el-button>
                <UrgeButton
                  v-if="row.status === 'PENDING_APPROVAL'"
                  :order="row"
                  kind="approval"
                  plain
                  @done="load"
                />
                <el-button v-if="canRequestExtend(row)" size="small" type="success" plain @click="openExtend(row)">
                  申请延期
                </el-button>
                <el-button v-if="canReportFault(row)" size="small" type="danger" plain @click="openFault(row)">
                  上报故障
                </el-button>
                <el-button
                  v-if="canRequestReturn(row)"
                  size="small"
                  type="warning"
                  plain
                  @click="handleRequestReturn(row)"
                >
                  归还设备
                </el-button>
                <el-button v-if="canCancel(row)" size="small" type="danger" plain @click="handleCancel(row)">
                  撤回
                </el-button>
              </div>
            </div>
            <el-empty v-if="!loading && records.length === 0" :image-size="70" description="暂无工单" />
          </div>
        </template>
      </TablePage>
    </section>

    <!-- 抄送我的：只读列表。抄送是「知情」而非「事务」——
         可查看完整详情与附件，但不参与审批，也不提供任何修改入口。 -->
    <section v-show="activeTab === 'cc'" class="ts-card">
      <div class="ts-flex-between ts-om__head">
        <div>
          <h3 class="ts-om__title">抄送我的</h3>
          <p class="ts-text-secondary ts-om__desc">
            流程中抄送给我的工单。抄送仅代表「需要我知情」，可查看工单进度与附件，但不参与审批、也不可对其做任何修改。
          </p>
        </div>
      </div>

      <TablePage
        :columns="ccColumns"
        :rows="ccRecords"
        :loading="ccLoading"
        route-path="/order/mine/cc"
        :page="ccPage"
        :size="ccSize"
        :total="ccTotal"
        empty-text="暂无抄送给我的工单"
        class="ts-mt-16"
        @update:page="handleCcPageChange"
        @update:size="handleCcSizeChange"
      >
        <template #cell-applyTypeName="{ row }">
          {{ (row as OrderItem).applyTypeName ?? deviceText(row as OrderItem) }}
        </template>
        <template #cell-statusLabel="{ row }">
          <el-tag :type="orderStatusTagType((row as OrderItem).status)" size="small" effect="plain">
            {{ (row as OrderItem).statusLabel }}
          </el-tag>
        </template>
        <template #cell-action="{ row }">
          <el-button link type="primary" @click="openDetail(row as OrderItem)">查看详情</el-button>
        </template>

        <!-- 移动端卡片保留改造前结构（标题是工单编号，且带「申请类型 / 设备」行） -->
        <template #mobile>
          <div v-loading="ccLoading" class="ts-om__cards">
            <div v-for="row in ccRecords" :key="row.id" class="ts-om__card">
              <div class="ts-om__card-title">{{ row.orderNo }}</div>
              <div class="ts-om__card-row">
                <span>申请人</span><span class="ts-om__card-value">{{ row.applicantName ?? '-' }}</span>
              </div>
              <div class="ts-om__card-row">
                <span>申请类型 / 设备</span>
                <span class="ts-om__card-value">{{ row.applyTypeName ?? deviceText(row) }}</span>
              </div>
              <div class="ts-om__card-row">
                <span>状态</span>
                <el-tag :type="orderStatusTagType(row.status)" size="small" effect="plain">
                  {{ row.statusLabel }}
                </el-tag>
              </div>
              <div class="ts-om__card-row">
                <span>提交时间</span><span class="ts-om__card-value">{{ row.createdAt }}</span>
              </div>
              <div class="ts-om__card-actions">
                <el-button size="small" type="primary" plain @click="openDetail(row)">查看详情</el-button>
              </div>
            </div>
            <el-empty v-if="!ccLoading && ccRecords.length === 0" :image-size="70" description="暂无抄送给我的工单" />
          </div>
        </template>
      </TablePage>
    </section>

    <OrderDetailDialog v-model="detailVisible" :order-id="detailId">
      <template #actions="{ detail }">
        <!-- 催办仅申请人可用（服务端强校验）：抄送/其他视角下不该出现一个必然失败的按钮 -->
        <UrgeButton
          v-if="detail.status === 'PENDING_APPROVAL' && detail.applicantSelf"
          :order="detail"
          kind="approval"
          @done="load"
        />
        <el-button
          v-if="canRequestExtend(detail)"
          type="success"
          :loading="acting"
          @click="openExtend(detail)"
        >
          申请延期
        </el-button>
        <el-button v-if="canReportFault(detail)" type="danger" plain :loading="acting" @click="openFault(detail)">
          上报故障
        </el-button>
        <el-button
          v-if="canRequestReturn(detail)"
          type="warning"
          :loading="acting"
          @click="handleRequestReturn(detail)"
        >
          归还设备
        </el-button>
        <el-button
          v-if="canCancel(detail)"
          type="danger"
          plain
          :loading="acting"
          @click="handleCancel(detail)"
        >
          撤回申请
        </el-button>
      </template>
    </OrderDetailDialog>

    <!-- 申请延期 -->
    <el-dialog v-model="extendVisible" title="申请借用延期" :width="isMobile ? '94%' : '480px'">
      <p class="ts-text-secondary ts-om__hint">
        工单：{{ extendTarget?.orderNo }}（设备：{{ extendTarget ? deviceText(extendTarget) : '-' }}）
      </p>
      <el-form label-width="110px" :label-position="isMobile ? 'top' : 'right'">
        <el-form-item label="当前计划归还">
          <span>{{ extendTarget?.plannedEndTime ?? '—' }}</span>
        </el-form-item>
        <el-form-item label="延期后结束时间" required>
          <el-date-picker
            v-model="extendForm.newEndTime"
            type="datetime"
            placeholder="选择延期后的结束时间"
            format="YYYY-MM-DD HH:mm:ss"
            value-format="YYYY-MM-DD HH:mm:ss"
            :disabled-date="disabledExtendDate"
            class="ts-om__picker"
          />
        </el-form-item>
        <el-form-item label="延期原因" required>
          <el-input
            v-model="extendForm.reason"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="请说明延期原因（如：项目尚未结束，预计还需 3 天）"
          />
        </el-form-item>
        <p class="ts-text-hint ts-om__hint">
          延期申请需按原审批链路审批，通过后计划归还时间自动更新；驳回则维持原时间。延期次数上限 {{ extendTarget?.extendMaxCount ?? 2 }} 次。
        </p>
      </el-form>
      <template #footer>
        <el-button @click="extendVisible = false">取消</el-button>
        <el-button type="primary" :loading="extendSubmitting" @click="submitExtend">提交延期申请</el-button>
      </template>
    </el-dialog>

    <!-- 上报故障 -->
    <el-dialog v-model="faultVisible" title="上报设备故障" :width="isMobile ? '94%' : '480px'">
      <p class="ts-text-secondary ts-om__hint">
        设备：{{ faultTarget ? deviceText(faultTarget) : '-' }}（工单：{{ faultTarget?.orderNo }}）
      </p>
      <el-form label-width="110px" :label-position="isMobile ? 'top' : 'right'">
        <el-form-item label="故障发生时间" required>
          <el-date-picker
            v-model="faultForm.occurredAt"
            type="datetime"
            placeholder="选择故障发生时间"
            format="YYYY-MM-DD HH:mm:ss"
            value-format="YYYY-MM-DD HH:mm:ss"
            :disabled-date="disabledOccurredDate"
            class="ts-om__picker"
          />
        </el-form-item>
        <el-form-item label="故障描述" required>
          <el-input
            v-model="faultForm.faultDescription"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="请描述故障现象（如：屏幕出现竖线、无法开机）"
          />
        </el-form-item>
        <p class="ts-text-hint ts-om__hint">
          上报后设备仍由您继续使用，管理员维修完成后设备恢复可用。图片附件将在后续版本支持。
        </p>
      </el-form>
      <template #footer>
        <el-button @click="faultVisible = false">取消</el-button>
        <el-button type="danger" :loading="faultSubmitting" @click="submitFault">提交故障上报</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* 页签：与下方卡片保持贴合，去掉 el-tabs 默认的多余下边距 */
.ts-om__tabs {
  margin-bottom: 8px;
}

.ts-om__tabs :deep(.el-tabs__header) {
  margin-bottom: 0;
}

.ts-om__head {
  gap: 16px;
  align-items: flex-start;
}

.ts-om__title {
  margin: 0;
  font-size: 16px;
  font-weight: 500;
}

.ts-om__desc {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.6;
}

.ts-om__filters {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.ts-om__filter-item {
  width: 170px;
}

.ts-om__filter-keyword {
  width: 240px;
}

.ts-om__filter-actions {
  display: flex;
  gap: 8px;
}

.ts-om__badge {
  margin-left: 4px;
}

.ts-om__tags {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.ts-om__hint {
  margin: 0 0 12px;
  font-size: 12px;
  line-height: 1.6;
}

.ts-om__picker {
  width: 100%;
}

.ts-om__cards {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.ts-om__card {
  padding: 12px;
  border: 1px solid var(--ts-border);
  border-radius: 10px;
  background: #fbfcfe;
}

.ts-om__card-title {
  word-break: break-all;
}

.ts-om__card-value {
  word-break: break-all;
  text-align: right;
}

.ts-om__card-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 3px 0;
  font-size: 13px;
}

.ts-om__card-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
}


@media (max-width: 767px) {
  .ts-om__head {
    flex-direction: column;
  }

  .ts-om__head .el-button {
    width: 100%;
  }

  .ts-om__filter-item,
  .ts-om__filter-keyword {
    width: 100%;
  }

  .ts-om__filter-actions .el-button {
    flex: 1 1 0;
  }

  .ts-om__card-actions .el-button {
    flex: 1 1 0;
    margin-left: 0;
  }
}
</style>
