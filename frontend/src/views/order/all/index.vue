<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { orderApi } from '@/api/order'
import { applyTypeApi } from '@/api/applyType'
import { departmentApi } from '@/api/department'
import { userApi } from '@/api/user'
import OrderDetailDialog from '@/components/OrderDetailDialog.vue'
import TablePage from '@/components/TablePage.vue'
import { useExport } from '@/composables/useExport'
import { useResponsive } from '@/composables/useResponsive'
import {
  FORCE_OPERATION_OPTIONS,
  ORDER_STATUS_OPTIONS,
  USE_TYPE_OPTIONS,
  availableForceOperations,
  isCustomOrder,
  orderStatusTagType
} from '@/types/order'
import type {
  ForceOperationTypeCode,
  OrderAllQuery,
  OrderForcePayload,
  OrderItem,
  OrderStatusCode,
  OrderUseType
} from '@/types/order'
import type { ApplyTypeItem } from '@/types/applyType'
import type { DepartmentOption } from '@/types/department'
import type { ColumnDef } from '@/types/table'
import type { UserOption } from '@/types/user'

/**
 * 全部工单（全局视图，仅 super_admin / admin， 工单管理）
 *
 * 面向管理端的全量工单台账：支持状态 / 超时 / 申请人 / 设备 / 借用类型 / 提交时间 / 部门筛选，
 * 默认按提交时间倒序，可按提交时间、期望归还日期排序（排序字段后端白名单校验）。
 *
 *  ：状态筛选补充「待收回 / 已归还」，并新增「已超时」独立筛选
 * （超时是标记位而非状态，故不能并入状态筛选）；列表新增归还时间与实际收回人。
 *
 * 权限：菜单仅对 super_admin / admin 渲染，服务端 `/orders/all` 会再判一次角色（越权 403）。
 * 详情复用公共 OrderDetailDialog，展示完整审批快照链路。
 */

const { isMobile } = useResponsive()

/**
 * 列定义（ · M3-B）
 *
 * 逐列照抄改造前写法，包括两处 `sortable: 'custom'`（服务端排序，由 `@sort-change` 透传，
 * 见模板上的 `@sort-change="handleSortChange"`）。操作列锁定常显。
 */
const columns: ColumnDef[] = [
  { key: 'orderNo', label: '工单编号', minWidth: 170, showOverflowTooltip: true },
  { key: 'applicantName', label: '申请人', minWidth: 120 },
  { key: 'deviceName', label: '设备', minWidth: 180, showOverflowTooltip: true },
  { key: 'applyTypeName', label: '申请类型', minWidth: 120, showOverflowTooltip: true },
  { key: 'useTypeLabel', label: '借用类型', width: 110, align: 'center' },
  { key: 'createdAt', label: '提交时间', minWidth: 170, sortable: 'custom', showOverflowTooltip: true },
  { key: 'statusLabel', label: '状态', width: 128, align: 'center' },
  { key: 'currentStepOrder', label: '当前审批节点', minWidth: 170, showOverflowTooltip: true },
  { key: 'actualFinalHandlerName', label: '实际执行人', minWidth: 110 },
  { key: 'expectedReturnDate', label: '期望归还', minWidth: 130, sortable: 'custom', showOverflowTooltip: true },
  { key: 'actualEndTime', label: '归还时间 / 收回人', minWidth: 190, showOverflowTooltip: true },
  { key: 'action', label: '操作', width: 140, fixed: 'right', configurable: false }
]

const loading = ref(false)
const records = ref<OrderItem[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)

const deptOptions = ref<DepartmentOption[]>([])
/** 自定义申请类型：筛选下拉用「全部类型」（含已停用——历史工单仍可能属于它们） */
const applyTypes = ref<ApplyTypeItem[]>([])

const query = reactive<{
  status: OrderStatusCode | null
  borrowTimeout: boolean | null
  transferred: boolean | null
  applicantKeyword: string
  deviceKeyword: string
  useType: OrderUseType | null
  submitRange: [string, string] | null
  departmentId: number | null
  applyTypeId: number | null
  sortBy: 'createdAt' | 'expectedReturnDate' | null
  sortOrder: 'asc' | 'desc'
}>({
  status: null,
  borrowTimeout: null,
  transferred: null,
  applicantKeyword: '',
  deviceKeyword: '',
  useType: null,
  submitRange: null,
  departmentId: null,
  applyTypeId: null,
  sortBy: null,
  sortOrder: 'desc'
})

/** 导出交互：管理员导出全部工单（scope=ALL） */
const { loading: exporting, runExport } = useExport()

/** 导出当前筛选条件下的全部工单（导出 = 当前列表视图，筛选参数与列表同口径） */
async function handleExport(): Promise<void> {
  await runExport(
    {
      type: 'ORDER',
      scope: 'ALL',
      order: {
        status: query.status ?? undefined,
        applicantKeyword: query.applicantKeyword.trim() || undefined,
        deviceKeyword: query.deviceKeyword.trim() || undefined,
        useType: query.useType ?? undefined,
        submitTimeFrom: query.submitRange?.[0] ?? undefined,
        submitTimeTo: query.submitRange?.[1] ?? undefined,
        departmentId: query.departmentId ?? undefined,
        borrowTimeout: query.borrowTimeout ?? undefined,
        transferred: query.transferred ?? undefined,
        applyTypeId: query.applyTypeId ?? undefined
      }
    },
    '全部工单.xlsx'
  )
}

const detailVisible = ref(false)
const detailId = ref<number | null>(null)

// ------------------------------------------------------------------
// 超管强制干预（仅 canForceOperate===true 的行出现按钮）
// ------------------------------------------------------------------

const forceVisible = ref(false)
const forceOrder = ref<OrderItem | null>(null)
const forceLoading = ref(false)
const userOptions = ref<UserOption[]>([])
const forceForm = reactive<{
  operationType: ForceOperationTypeCode | ''
  reason: string
  targetApproverId: number | null
  targetHandlerId: number | null
}>({
  operationType: '',
  reason: '',
  targetApproverId: null,
  targetHandlerId: null
})

/** 按当前工单状态给出可选强制操作子集（映射函数集中在 types 层，便于扩展） */
const forceAvailableOptions = computed(() => {
  if (!forceOrder.value) {
    return []
  }
  const codes = availableForceOperations(forceOrder.value.status)
  return FORCE_OPERATION_OPTIONS.filter((o) => codes.includes(o.value))
})

const forceNeedApprover = computed(() => forceForm.operationType === 'FORCE_TRANSFER_APPROVAL')
const forceNeedHandler = computed(() => forceForm.operationType === 'FORCE_TRANSFER_HANDLER')
/** 仅从「在职且启用」的人里选目标审批人 / 执行人 */
const availableUsers = computed(() => userOptions.value.filter((u) => u.available))

onMounted(async () => {
  await loadDepartments()
  await loadApplyTypes()
  await load()
})

async function loadDepartments(): Promise<void> {
  try {
    deptOptions.value = await departmentApi.options()
  } catch {
    deptOptions.value = []
  }
}

/** 自定义申请类型：筛选用全部类型（含已停用，历史工单仍可能属于它们） */
async function loadApplyTypes(): Promise<void> {
  try {
    applyTypes.value = await applyTypeApi.list()
  } catch {
    applyTypes.value = []
  }
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const params: OrderAllQuery = {
      page: page.value,
      size: size.value,
      status: query.status,
      borrowTimeout: query.borrowTimeout,
      transferred: query.transferred,
      applicantKeyword: query.applicantKeyword.trim() || undefined,
      deviceKeyword: query.deviceKeyword.trim() || undefined,
      useType: query.useType,
      submitTimeFrom: query.submitRange?.[0] ?? null,
      submitTimeTo: query.submitRange?.[1] ?? null,
      departmentId: query.departmentId,
      applyTypeId: query.applyTypeId
    }
    // 默认（不显式排序）交给后端按提交时间倒序，保持与列表默认一致
    if (query.sortBy) {
      params.sortBy = query.sortBy
      params.sortOrder = query.sortOrder
    }
    const result = await orderApi.allOrders(params)
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
  query.status = null
  query.borrowTimeout = null
  query.transferred = null
  query.applicantKeyword = ''
  query.deviceKeyword = ''
  query.useType = null
  query.submitRange = null
  query.departmentId = null
  query.applyTypeId = null
  query.sortBy = null
  query.sortOrder = 'desc'
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

/** 表格排序 → 后端排序参数（仅放行白名单字段） */
function handleSortChange({ prop, order }: { prop: string | null; order: string | null }): void {
  if (!order) {
    // 取消排序 → 回到默认「提交时间倒序」
    query.sortBy = null
    query.sortOrder = 'desc'
  } else {
    query.sortBy = prop === 'expectedReturnDate' ? 'expectedReturnDate' : 'createdAt'
    query.sortOrder = order === 'ascending' ? 'asc' : 'desc'
  }
  page.value = 1
  void load()
}

function openDetail(order: OrderItem): void {
  detailId.value = order.id
  detailVisible.value = true
}

/** 打开强制操作弹窗：根据工单状态重置表单并拉取可选目标人 */
function openForce(order: OrderItem): void {
  forceOrder.value = order
  forceForm.operationType = ''
  forceForm.reason = ''
  forceForm.targetApproverId = null
  forceForm.targetHandlerId = null
  forceVisible.value = true
  void loadUserOptions()
}

async function loadUserOptions(): Promise<void> {
  try {
    userOptions.value = await userApi.options()
  } catch {
    userOptions.value = []
  }
}

/** 强制操作类型变更后，清空与之无关的目标人，避免脏数据提交 */
function onForceOperationChange(): void {
  if (!forceNeedApprover.value) {
    forceForm.targetApproverId = null
  }
  if (!forceNeedHandler.value) {
    forceForm.targetHandlerId = null
  }
}

/** 提交前校验：类型必选、原因必填（≤500）、转交类必选目标人 */
function validateForce(): string | null {
  if (!forceForm.operationType) {
    return '请选择强制操作类型'
  }
  if (!forceForm.reason.trim()) {
    // 后端对空原因返回 FORCE_REASON_REQUIRED（非 400 校验），前端先拦截提升体验
    return '强制原因必填'
  }
  if (forceForm.reason.trim().length > 500) {
    return '强制原因不能超过 500 字'
  }
  if (forceNeedApprover.value && !forceForm.targetApproverId) {
    return '请选择目标审批人'
  }
  if (forceNeedHandler.value && !forceForm.targetHandlerId) {
    return '请选择目标执行人'
  }
  return null
}

async function submitForce(): Promise<void> {
  const order = forceOrder.value
  if (!order) {
    return
  }
  const error = validateForce()
  if (error) {
    ElMessage.warning(error)
    return
  }
  // 高风险操作二次确认，且明确提示「不可撤销 + 审计 + 通知」
  try {
    await ElMessageBox.confirm('强制操作将记录审计并通知相关方，且不可撤销，确认执行？', '高风险操作确认', {
      type: 'warning',
      confirmButtonText: '确认强制操作',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  forceLoading.value = true
  try {
    const payload: OrderForcePayload = {
      operationType: forceForm.operationType as ForceOperationTypeCode,
      reason: forceForm.reason.trim(),
      targetApproverId: forceNeedApprover.value ? forceForm.targetApproverId : null,
      targetHandlerId: forceNeedHandler.value ? forceForm.targetHandlerId : null
    }
    await orderApi.force(order.id, payload)
    ElMessage.success('强制操作成功')
    forceVisible.value = false
    await load()
  } catch {
    // 失败提示已由 request 拦截器统一处理（含 FORCE_REASON_REQUIRED 等错误码）
  } finally {
    forceLoading.value = false
  }
}

/** 设备（"设备名（资产编号）"，后端已合并）；自定义申请无设备 → 显示「—」而非空 */
function deviceText(order: OrderItem): string {
  return order.deviceName ?? '—'
}

function applicantText(order: OrderItem): string {
  return order.applicantName ?? '-'
}

function departmentText(order: OrderItem): string {
  return order.departmentName ?? '-'
}

/**
 * 申请类型列。
 *
 * 自定义申请显示其类型名；普通借用单显示「借用申请」——
 * 两行都给出明确文案，避免出现一列里大量空白让人以为数据没取到。
 */
function applyTypeText(order: OrderItem): string {
  if (isCustomOrder(order)) {
    return order.applyTypeName ?? '自定义申请'
  }
  return order.orderTypeLabel ?? '借用申请'
}

/** 借用类型：自定义申请不适用（NULL）→ 显示「—」 */
function useTypeText(order: OrderItem): string {
  return order.useTypeLabel ?? '—'
}

/** 借用类型标签：自定义申请不适用 → 用中性色（不能用「成功绿」暗示它是长期领用） */
function useTypeTagType(order: OrderItem): 'warning' | 'success' | 'info' {
  if (!order.useType) {
    return 'info'
  }
  return order.useType === 'SHORT_TERM' ? 'warning' : 'success'
}

/** 当前审批节点：第几步 · 由谁审批（无待办节点时显示 -） */
function currentApprovalText(order: OrderItem): string {
  if (!order.currentStepOrder) {
    return '-'
  }
  return `第 ${order.currentStepOrder} 步 · ${order.currentApproverName ?? '-'}`
}

function handlerText(order: OrderItem): string {
  return order.actualFinalHandlerName ?? '待分配'
}

function expectedReturnText(order: OrderItem): string {
  // 自定义申请没有「期望归还」概念（不占用设备）
  if (isCustomOrder(order)) {
    return '—'
  }
  if (order.useType !== 'SHORT_TERM') {
    return '长期'
  }
  return order.expectedReturnDate ?? '-'
}

function isTimeout(order: OrderItem): boolean {
  return order.borrowTimeout === true
}
</script>

<template>
  <div class="ts-page">
    <section class="ts-card">
      <div class="ts-flex-between ts-all__head">
        <div>
          <h3 class="ts-all__title">全部工单</h3>
          <p class="ts-text-secondary ts-all__desc">
            全量借用工单台账（管理端）：可按状态、超时、申请人、设备、借用类型、提交时间、部门筛选，默认按提交时间倒序。
          </p>
        </div>
        <el-space>
          <el-button :loading="loading" @click="load">刷新</el-button>
          <el-button type="primary" :loading="exporting" @click="handleExport">导出 Excel</el-button>
        </el-space>
      </div>

      <!-- 筛选条件 -->
      <div class="ts-all__filters ts-mt-16">
        <el-select v-model="query.status" class="ts-all__filter-item" placeholder="状态" clearable>
          <el-option v-for="item in ORDER_STATUS_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <el-select v-model="query.borrowTimeout" class="ts-all__filter-item" placeholder="超时" clearable>
          <el-option label="仅看已超时" :value="true" />
        </el-select>
        <el-select v-model="query.transferred" class="ts-all__filter-item" placeholder="转交" clearable>
          <el-option label="仅看已转交" :value="true" />
        </el-select>
        <el-select v-model="query.useType" class="ts-all__filter-item" placeholder="借用类型" clearable>
          <el-option v-for="item in USE_TYPE_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <!-- 申请类型：含已停用类型，便于追溯历史工单 -->
        <el-select
          v-model="query.applyTypeId"
          class="ts-all__filter-item ts-all__filter-wide"
          placeholder="申请类型"
          clearable
        >
          <el-option v-for="item in applyTypes" :key="item.id" :label="item.typeName" :value="item.id" />
        </el-select>
        <el-input
          v-model="query.applicantKeyword"
          class="ts-all__filter-item"
          placeholder="申请人姓名"
          clearable
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
        <el-input
          v-model="query.deviceKeyword"
          class="ts-all__filter-item ts-all__filter-wide"
          placeholder="设备名称 / 资产编号"
          clearable
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
        <el-select v-model="query.departmentId" class="ts-all__filter-item" placeholder="部门" clearable>
          <el-option
            v-for="item in deptOptions"
            :key="item.id"
            :label="item.displayPath"
            :value="item.id"
          />
        </el-select>
        <el-date-picker
          v-model="query.submitRange"
          class="ts-all__filter-date"
          type="daterange"
          value-format="YYYY-MM-DD"
          range-separator="至"
          start-placeholder="提交开始"
          end-placeholder="提交结束"
          unlink-panels
        />
        <div class="ts-all__filter-actions">
          <el-button type="primary" :loading="loading" @click="handleSearch">查询</el-button>
          <el-button @click="handleReset">重置</el-button>
        </div>
      </div>

      <TablePage
        :columns="columns"
        :rows="records"
        :loading="loading"
        route-path="/order/all"
        :page="page"
        :size="size"
        :total="total"
        empty-text="暂无符合条件的工单"
        class="ts-mt-16"
        @update:page="handlePageChange"
        @update:size="handleSizeChange"
        @sort-change="handleSortChange"
      >
        <template #cell-applicantName="{ row }">
          <div>{{ applicantText(row as OrderItem) }}</div>
          <div class="ts-text-hint">{{ departmentText(row as OrderItem) }}</div>
        </template>
        <template #cell-deviceName="{ row }">{{ deviceText(row as OrderItem) }}</template>
        <template #cell-applyTypeName="{ row }">
          <el-tag v-if="isCustomOrder(row as OrderItem)" type="primary" size="small" effect="plain">
            {{ applyTypeText(row as OrderItem) }}
          </el-tag>
          <span v-else>{{ applyTypeText(row as OrderItem) }}</span>
        </template>
        <template #cell-useTypeLabel="{ row }">
          <el-tag :type="useTypeTagType(row as OrderItem)" size="small" effect="plain">
            {{ useTypeText(row as OrderItem) }}
          </el-tag>
        </template>
        <template #cell-statusLabel="{ row }">
          <el-tag :type="orderStatusTagType((row as OrderItem).status)" size="small" effect="plain">
            {{ (row as OrderItem).statusLabel }}
          </el-tag>
          <el-tag v-if="isTimeout(row as OrderItem)" type="danger" size="small" effect="dark" class="ts-all__timeout">
            已超时
          </el-tag>
          <el-tag
            v-if="(row as OrderItem).extendPending"
            type="warning"
            size="small"
            effect="plain"
            class="ts-all__timeout"
          >
            延期审批中
          </el-tag>
          <el-tag
            v-if="(row as OrderItem).transferred"
            type="info"
            size="small"
            effect="plain"
            class="ts-all__timeout"
          >
            已转交
          </el-tag>
        </template>
        <template #cell-currentStepOrder="{ row }">
          <span v-if="(row as OrderItem).currentStepOrder">{{ currentApprovalText(row as OrderItem) }}</span>
          <span v-else class="ts-text-hint">-</span>
        </template>
        <template #cell-actualFinalHandlerName="{ row }">{{ handlerText(row as OrderItem) }}</template>
        <template #cell-expectedReturnDate="{ row }">
          <span v-if="(row as OrderItem).useType === 'LONG_TERM'" class="ts-text-hint">长期</span>
          <span v-else>{{ (row as OrderItem).expectedReturnDate || '-' }}</span>
        </template>
        <template #cell-actualEndTime="{ row }">
          <template v-if="(row as OrderItem).actualEndTime">
            <div>{{ (row as OrderItem).actualEndTime }}</div>
            <div class="ts-text-hint">收回人：{{ (row as OrderItem).returnedByName || '-' }}</div>
          </template>
          <span v-else class="ts-text-hint">-</span>
        </template>
        <template #cell-action="{ row }">
          <el-button link type="primary" size="small" @click="openDetail(row as OrderItem)">详情</el-button>
          <el-button
            v-if="(row as OrderItem).canForceOperate === true"
            link
            type="danger"
            size="small"
            @click="openForce(row as OrderItem)"
          >
            强制操作
          </el-button>
        </template>

        <!-- 移动端卡片保留改造前结构（自定义申请隐藏「设备 / 借用类型」两行） -->
        <template #mobile>
          <div v-loading="loading" class="ts-all__cards">
            <div v-for="row in records" :key="row.id" class="ts-all__card">
              <div class="ts-flex-between">
                <strong class="ts-all__card-title">{{ applyTypeText(row) }}</strong>
                <div class="ts-all__tags">
                  <el-tag :type="orderStatusTagType(row.status)" size="small" effect="plain">
                    {{ row.statusLabel }}
                  </el-tag>
                  <el-tag v-if="isTimeout(row)" type="danger" size="small" effect="dark">已超时</el-tag>
                  <el-tag v-if="row.extendPending" type="warning" size="small" effect="plain">延期审批中</el-tag>
                  <el-tag v-if="row.transferred" type="info" size="small" effect="plain">已转交</el-tag>
                </div>
              </div>
              <div class="ts-all__card-row">
                <span class="ts-text-hint">工单编号</span>
                <span class="ts-all__card-value">{{ row.orderNo }}</span>
              </div>
              <div class="ts-all__card-row">
                <span class="ts-text-hint">申请人</span>
                <span>{{ applicantText(row) }} · {{ departmentText(row) }}</span>
              </div>
              <div class="ts-all__card-row">
                <span class="ts-text-hint">申请类型</span>
                <el-tag v-if="isCustomOrder(row)" type="primary" size="small" effect="plain">
                  {{ applyTypeText(row) }}
                </el-tag>
                <span v-else>{{ applyTypeText(row) }}</span>
              </div>
              <!-- 自定义申请不占用设备，此行对它们无意义 → 隐藏 -->
              <div v-if="!isCustomOrder(row)" class="ts-all__card-row">
                <span class="ts-text-hint">设备</span>
                <span>{{ deviceText(row) }}</span>
              </div>
              <div v-if="!isCustomOrder(row)" class="ts-all__card-row">
                <span class="ts-text-hint">借用类型</span>
                <el-tag :type="useTypeTagType(row)" size="small" effect="plain">{{ useTypeText(row) }}</el-tag>
              </div>
              <div class="ts-all__card-row">
                <span class="ts-text-hint">当前审批节点</span>
                <span>{{ currentApprovalText(row) }}</span>
              </div>
              <div class="ts-all__card-row">
                <span class="ts-text-hint">实际执行人</span>
                <span>{{ handlerText(row) }}</span>
              </div>
              <div class="ts-all__card-row">
                <span class="ts-text-hint">期望归还</span>
                <span>{{ expectedReturnText(row) }}</span>
              </div>
              <div v-if="row.actualEndTime" class="ts-all__card-row">
                <span class="ts-text-hint">归还时间</span>
                <span>{{ row.actualEndTime }}</span>
              </div>
              <div v-if="row.actualEndTime" class="ts-all__card-row">
                <span class="ts-text-hint">实际收回人</span>
                <span>{{ row.returnedByName || '-' }}</span>
              </div>
              <div class="ts-all__card-row">
                <span class="ts-text-hint">提交时间</span>
                <span>{{ row.createdAt }}</span>
              </div>
              <div class="ts-all__card-actions">
                <el-button size="small" @click="openDetail(row)">详情</el-button>
                <el-button
                  v-if="row.canForceOperate === true"
                  size="small"
                  type="danger"
                  plain
                  @click="openForce(row)"
                >
                  强制操作
                </el-button>
              </div>
            </div>
            <el-empty v-if="!loading && records.length === 0" :image-size="70" description="暂无符合条件的工单" />
          </div>
        </template>
      </TablePage>
    </section>

    <OrderDetailDialog v-model="detailVisible" :order-id="detailId" />

    <!-- 超管强制操作弹窗（仅 canForceOperate 行可见入口） -->
    <el-dialog
      v-model="forceVisible"
      title="强制操作"
      :width="isMobile ? '94%' : '560px'"
      :close-on-click-modal="false"
      @closed="forceLoading = false"
    >
      <template v-if="forceOrder">
        <el-alert
          type="warning"
          :closable="false"
          show-icon
          class="ts-all__force-alert"
          title="强制操作将记录审计并通知相关方，且不可撤销。"
        />

        <el-form label-width="96px" class="ts-mt-16">
          <el-form-item label="工单">
            <span class="ts-text-secondary">{{ forceOrder.orderNo }} · {{ forceOrder.statusLabel }}</span>
          </el-form-item>

          <el-form-item label="操作类型" required>
            <el-select
              v-model="forceForm.operationType"
              placeholder="请选择强制操作类型"
              style="width: 100%"
              @change="onForceOperationChange"
            >
              <el-option
                v-for="opt in forceAvailableOptions"
                :key="opt.value"
                :label="opt.label"
                :value="opt.value"
              />
            </el-select>
          </el-form-item>

          <el-form-item v-if="forceNeedApprover" label="目标审批人" required>
            <el-select
              v-model="forceForm.targetApproverId"
              placeholder="仅可选择在职且启用的人员"
              filterable
              style="width: 100%"
            >
              <el-option v-for="u in availableUsers" :key="u.id" :label="u.displayName" :value="u.id" />
            </el-select>
          </el-form-item>

          <el-form-item v-if="forceNeedHandler" label="目标执行人" required>
            <el-select
              v-model="forceForm.targetHandlerId"
              placeholder="仅可选择在职且启用的人员（可跨组）"
              filterable
              style="width: 100%"
            >
              <el-option v-for="u in availableUsers" :key="u.id" :label="u.displayName" :value="u.id" />
            </el-select>
          </el-form-item>

          <el-form-item label="强制原因" required>
            <el-input
              v-model="forceForm.reason"
              type="textarea"
              :rows="3"
              maxlength="500"
              show-word-limit
              placeholder="请填写强制操作原因（必填，最多 500 字）"
            />
          </el-form-item>
        </el-form>
      </template>

      <template #footer>
        <el-button :loading="forceLoading" @click="forceVisible = false">取消</el-button>
        <el-button type="danger" :loading="forceLoading" @click="submitForce">确认强制操作</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.ts-all__head {
  gap: 16px;
  align-items: flex-start;
}

.ts-all__title {
  margin: 0;
  font-size: 16px;
  font-weight: 500;
}

.ts-all__desc {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.6;
}

.ts-all__filters {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.ts-all__filter-item {
  width: 160px;
}

.ts-all__filter-wide {
  width: 200px;
}

.ts-all__filter-date {
  width: 250px;
}

.ts-all__filter-actions {
  display: flex;
  gap: 8px;
}

.ts-all__timeout {
  margin-left: 4px;
}

.ts-all__tags {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.ts-all__cards {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.ts-all__card {
  padding: 12px;
  border: 1px solid var(--ts-border);
  border-radius: 10px;
  background: #fbfcfe;
}

.ts-all__card-title {
  word-break: break-all;
}

.ts-all__card-value {
  word-break: break-all;
  text-align: right;
}

.ts-all__card-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 3px 0;
  font-size: 13px;
}

.ts-all__card-actions {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}


@media (max-width: 767px) {
  .ts-all__head {
    flex-direction: column;
  }

  .ts-all__head .el-button {
    width: 100%;
  }

  .ts-all__filter-item,
  .ts-all__filter-wide,
  .ts-all__filter-date {
    width: 100%;
  }

  .ts-all__filter-actions .el-button {
    flex: 1 1 0;
  }

  .ts-all__card-actions .el-button {
    flex: 1 1 0;
    margin-left: 0;
  }
}
</style>
