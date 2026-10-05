<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { deviceApi, deviceCategoryApi, deviceFaultApi } from '@/api/device'
import BatchResultDialog from '@/components/BatchResultDialog.vue'
import DeviceImportDialog from '@/components/DeviceImportDialog.vue'
import DeviceLabelDialog from '@/components/DeviceLabelDialog.vue'
import TablePage from '@/components/TablePage.vue'
import { useExport } from '@/composables/useExport'
import { useResponsive } from '@/composables/useResponsive'
import { useUserStore } from '@/store/user'
import {
  DEVICE_STATUS_OPTIONS,
  MANUAL_DEVICE_STATUS_OPTIONS,
  deviceStatusTagType,
  formatAmount
} from '@/types/device'
import type { DeviceCategory, DeviceItem, DeviceStatusCode } from '@/types/device'
import type { BatchResult } from '@/types/api'
import type { TableInstance } from 'element-plus'
import type { ColumnDef } from '@/types/table'
import type { LabelDevice } from '@/utils/qrLabel'

/**
 * 设备台账（ / ）
 *
 * 能力：查询（关键词 / 分类 / 状态）、分页、新增、编辑、软删除、管理员手动状态操作、
 * **台账直接登记故障**（ 第二条上报路径）。
 *
 * 状态机边界：本页只提供「报废」「维修完成」两类人工操作；
 * LOCKED / IN_APPROVAL / IN_USE 由借用工单流程驱动，台账界面不提供入口，
 * 避免绕过状态机直接改状态。
 *
 * 权限：admin 具备设备台账管理权限，故本页写操作对 admin 开放。
 */

const { isMobile } = useResponsive()
const userStore = useUserStore()

const canEdit = computed(() => userStore.role === 'super_admin' || userStore.role === 'admin')

/**
 * 列定义（ · M3-B）
 *
 * 逐列照抄改造前写法。写操作集中在「操作」列，该列 `configurable: false`：
 * 台账是管理页，用户把操作列关掉就等于「页面坏了」（且此页操作项最多，漏关的代价最大）。
 */
const columns: ColumnDef[] = [
  // 多选列（P3 批量操作）：TablePage 只渲染 type="selection"，key 仍作稳定标识
  { key: 'selection', label: '选择', width: 46, selection: true },
  { key: 'deviceName', label: '设备名称', minWidth: 150, showOverflowTooltip: true },
  { key: 'assetNo', label: '资产编号', width: 130 },
  { key: 'category', label: '分类', minWidth: 170, showOverflowTooltip: true },
  { key: 'brandModel', label: '品牌 / 型号', minWidth: 150, showOverflowTooltip: true },
  { key: 'statusLabel', label: '状态', width: 100, align: 'center' },
  { key: 'usage', label: '使用 / 占用', minWidth: 200, showOverflowTooltip: true },
  { key: 'storageLocation', label: '存放位置', minWidth: 120, showOverflowTooltip: true },
  { key: 'amount', label: '设备金额', minWidth: 110, align: 'right', showOverflowTooltip: true },
  { key: 'updatedAt', label: '更新时间', minWidth: 170, showOverflowTooltip: true },
  // 用 minWidth 而不是 width：多了一个「打印标签」按钮，且显式 width 会被表格弹性布局压缩
  { key: 'action', label: '操作', minWidth: 340, fixed: 'right', configurable: false }
]

const loading = ref(false)
const saving = ref(false)
const records = ref<DeviceItem[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)

const categoryTree = ref<DeviceCategory[]>([])

const query = reactive({
  keyword: '',
  primaryCategoryId: null as number | null,
  secondaryCategoryId: null as number | null,
  status: null as DeviceStatusCode | null
})

/** 导出交互：设备台账仅管理员可导出，后端仍会二次校验角色 */
const { loading: exporting, runExport } = useExport()

/**
 * 导出当前筛选条件下的设备台账。
 *
 * 「导出 = 当前列表视图」：这里把与列表查询同名的筛选参数原样透传，
 * 后端复用同一套列表查询构造导出数据，避免「页面筛出来的」和「导出文件里的」对不上。
 */
async function handleExport(): Promise<void> {
  await runExport(
    {
      type: 'DEVICE',
      device: {
        keyword: query.keyword.trim() || undefined,
        primaryCategoryId: query.primaryCategoryId ?? undefined,
        secondaryCategoryId: query.secondaryCategoryId ?? undefined,
        status: query.status ?? undefined
      }
    },
    '设备台账.xlsx'
  )
}

const dialogVisible = ref(false)
const dialogMode = ref<'create' | 'edit'>('create')
const editingId = ref<number | null>(null)

/** 批量导入弹窗可见性（导入成功后刷新台账列表） */
const importVisible = ref(false)

// --- 台账直接登记故障 ---
const faultVisible = ref(false)
const faultTarget = ref<DeviceItem | null>(null)
const faultForm = reactive({
  faultDescription: '',
  occurredAt: ''
})
const faultSubmitting = ref(false)

const form = reactive({
  deviceName: '',
  assetNo: '',
  primaryCategoryId: null as number | null,
  secondaryCategoryId: null as number | null,
  brand: '',
  model: '',
  serialNo: '',
  storageLocation: '',
  purchaseDate: null as string | null,
  amount: null as number | null,
  remark: ''
})

/** 二级分类候选：随所选一级分类联动 */
const secondaryOptions = computed(
  () => categoryTree.value.find((item) => item.id === query.primaryCategoryId)?.children ?? []
)
const formSecondaryOptions = computed(
  () => categoryTree.value.find((item) => item.id === form.primaryCategoryId)?.children ?? []
)

/**
 * 一级分类变化时清空二级分类，避免出现「二级不属于一级」的无效组合。
 *
 * 这里必须用 el-select 的 @change（用户交互语义），**不能**用 `watch(form.primaryCategoryId)`：
 * Vue 的 watch 回调在微任务中执行，会晚于 openEdit / openCreate 里的同步赋值，
 * 把刚回填（或从筛选条件预填）的二级分类重置为 null，导致保存后二级分类被静默清空。
 * 详见 .docs/phase3-code-review.md 的「C1」。
 */
function clearSecondaryOnPrimaryChange(): void {
  query.secondaryCategoryId = null
}

function clearFormSecondaryOnPrimaryChange(): void {
  form.secondaryCategoryId = null
}

onMounted(async () => {
  await loadCategories()
  await load()
})

async function loadCategories(): Promise<void> {
  try {
    categoryTree.value = await deviceCategoryApi.tree()
  } catch {
    categoryTree.value = []
  }
}

// ------------------------------------------------------------------
// 批量操作（P3）
// ------------------------------------------------------------------

/** 勾选行 id：PC 表格勾选驱动批量操作 */
const selectedIds = ref<number[]>([])
/** TablePage 暴露的内部表格实例 —— 用于把「保留失败项勾选」同步到界面上 */
const tablePageRef = ref<{ clearSelection?: () => void; tableRef?: TableInstance } | null>(null)
const batchCategoryVisible = ref(false)
const batchStatusVisible = ref(false)
const batchResultVisible = ref(false)
const batchResult = ref<BatchResult | null>(null)
const batchSubmitting = ref(false)
const batchCategoryId = ref<number | null>(null)
const batchForm = reactive<{ targetStatus: DeviceStatusCode | null; reason: string }>({
  targetStatus: null,
  reason: ''
})

function handleSelectionChange(rows: DeviceItem[]): void {
  selectedIds.value = rows.map((row) => row.id)
}

function clearSelection(): void {
  selectedIds.value = []
  // 双可选链：tablePageRef 可能未挂载，clearSelection 也可能不存在（移动端走卡片、无 el-table）
  tablePageRef.value?.clearSelection?.()
}

// ------------------------------------------------------------------
// 设备标签打印
// ------------------------------------------------------------------

const labelVisible = ref(false)
const labelDevices = ref<LabelDevice[]>([])

/**
 * 打开标签弹窗。
 *
 * <p>单台与批量走**同一个入口与同一个弹窗**：需求里「点某台再点打印标签」与「勾选多台批量打印」
 * 除了设备数量以外没有任何差别，分成两套只会有两份要同步维护的排版逻辑。
 */
function openLabel(rows: DeviceItem[]): void {
  labelDevices.value = rows.map((row) => ({
    id: row.id,
    assetNo: row.assetNo ?? '',
    deviceName: row.deviceName ?? ''
  }))
  labelVisible.value = true
}

/** 工具栏 / 批量条：对当前勾选的设备打标签 */
function openLabelForSelection(): void {
  const picked = records.value.filter((row) => selectedIds.value.includes(row.id))
  if (picked.length === 0) {
    ElMessage.warning('请先勾选要打印标签的设备')
    return
  }
  openLabel(picked)
}

/** 行内 / 移动端卡片：单台 */
function openLabelForRow(row: DeviceItem): void {
  openLabel([row])
}

function openBatchCategory(): void {
  batchCategoryId.value = null
  batchCategoryVisible.value = true
}

function openBatchStatus(): void {
  batchForm.targetStatus = null
  batchForm.reason = ''
  batchStatusVisible.value = true
}

/**
 * 批量操作收尾：弹出逐条结果，并**只保留失败项的勾选**。
 *
 * 保留失败项的意义：用户改完条件后能直接重试那几条，不必在几十上百行里重新找。
 * 但界面勾选必须与 `selectedIds` 同步 —— 否则批量栏写着「已选 3 台」而表格上一个勾都没有，
 * 用户无从判断到底会操作哪些。
 */
function finishBatch(result: BatchResult): void {
  batchResult.value = result
  batchResultVisible.value = true
  const failedIds = new Set(result.failures.map((item) => item.id))
  selectedIds.value = selectedIds.value.filter((id) => failedIds.has(id))
  const table = tablePageRef.value?.tableRef
  if (table) {
    records.value.forEach((row) => {
      table.toggleRowSelection?.(row, failedIds.has(row.id))
    })
  }
}

async function submitBatchCategory(): Promise<void> {
  if (batchCategoryId.value == null) {
    ElMessage.warning('请选择目标分类')
    return
  }
  batchSubmitting.value = true
  try {
    const result = await deviceApi.batchChangeCategory(selectedIds.value, batchCategoryId.value)
    batchCategoryVisible.value = false
    finishBatch(result)
    await load()
  } finally {
    batchSubmitting.value = false
  }
}

async function submitBatchStatus(): Promise<void> {
  if (!batchForm.targetStatus) {
    ElMessage.warning('请选择目标状态')
    return
  }
  // 报废 / 丢失不可逆 ⇒ 二次确认必须写明「数量 + 目标状态」，
  // 只说"确认执行吗"等于没确认
  if (batchForm.targetStatus === 'SCRAPPED' || batchForm.targetStatus === 'LOST') {
    const label = MANUAL_DEVICE_STATUS_OPTIONS.find(
      (item) => item.value === batchForm.targetStatus
    )?.label
    await ElMessageBox.confirm(
      `确认把选中的 ${selectedIds.value.length} 台设备改为「${label}」？该操作会影响设备的可借状态。`,
      '批量变更状态',
      { type: 'warning', confirmButtonText: '确认变更' }
    )
  }
  batchSubmitting.value = true
  try {
    const result = await deviceApi.batchChangeStatus(
      selectedIds.value,
      batchForm.targetStatus,
      batchForm.reason
    )
    batchStatusVisible.value = false
    finishBatch(result)
    await load()
  } finally {
    batchSubmitting.value = false
  }
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await deviceApi.page({
      page: page.value,
      size: size.value,
      keyword: query.keyword.trim() || undefined,
      primaryCategoryId: query.primaryCategoryId,
      secondaryCategoryId: query.secondaryCategoryId,
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
  query.primaryCategoryId = null
  query.secondaryCategoryId = null
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

function resetForm(): void {
  form.deviceName = ''
  form.assetNo = ''
  form.primaryCategoryId = null
  form.secondaryCategoryId = null
  form.brand = ''
  form.model = ''
  form.serialNo = ''
  form.storageLocation = ''
  form.purchaseDate = null
  form.amount = null
  form.remark = ''
}

function openCreate(): void {
  dialogMode.value = 'create'
  editingId.value = null
  resetForm()
  // 若当前筛选已选定分类，新增时默认带入，减少重复选择
  form.primaryCategoryId = query.primaryCategoryId
  form.secondaryCategoryId = query.secondaryCategoryId
  dialogVisible.value = true
}

function openEdit(row: DeviceItem): void {
  dialogMode.value = 'edit'
  editingId.value = row.id
  form.deviceName = row.deviceName
  form.assetNo = row.assetNo
  form.primaryCategoryId = row.primaryCategoryId
  // 后端省略 null 字段，可空字段读出来是 undefined，需显式归一化后再回填表单
  form.secondaryCategoryId = row.secondaryCategoryId ?? null
  form.brand = row.brand ?? ''
  form.model = row.model ?? ''
  form.serialNo = row.serialNo ?? ''
  form.storageLocation = row.storageLocation ?? ''
  form.purchaseDate = row.purchaseDate ?? null
  // 未录入金额回填为 null（不是 0）—— 0 与「未录入」在审批分档里语义不同
  form.amount = row.amount ?? null
  form.remark = row.remark ?? ''
  dialogVisible.value = true
}

function trimmedOrNull(value: string): string | null {
  const trimmed = value.trim()
  return trimmed ? trimmed : null
}

async function handleSubmit(): Promise<void> {
  const deviceName = form.deviceName.trim()
  const assetNo = form.assetNo.trim()
  if (!deviceName) {
    ElMessage.warning('请填写设备名称')
    return
  }
  if (!assetNo) {
    ElMessage.warning('请填写资产编号')
    return
  }
  if (form.primaryCategoryId == null) {
    ElMessage.warning('请选择一级分类')
    return
  }
  const payload = {
    deviceName,
    assetNo,
    primaryCategoryId: form.primaryCategoryId,
    secondaryCategoryId: form.secondaryCategoryId,
    brand: trimmedOrNull(form.brand),
    model: trimmedOrNull(form.model),
    serialNo: trimmedOrNull(form.serialNo),
    storageLocation: trimmedOrNull(form.storageLocation),
    purchaseDate: form.purchaseDate,
    amount: form.amount,
    remark: trimmedOrNull(form.remark)
  }
  saving.value = true
  try {
    if (dialogMode.value === 'create') {
      await deviceApi.create(payload)
      ElMessage.success('设备已创建')
    } else if (editingId.value != null) {
      await deviceApi.update(editingId.value, payload)
      ElMessage.success('设备信息已更新')
    }
    dialogVisible.value = false
    await load()
  } catch {
    // 错误提示由请求层统一处理（资产编号重复等）
  } finally {
    saving.value = false
  }
}

/**
 * 报废：允许「可用 / 维修中 / 已丢失」（ + P0）。
 *
 * <p>加上「已丢失」的理由：丢失的设备确认找不回来后，就是要走报废 ——
 * 若这里不放行，管理员只能先「找回」再报废，等于凭空伪造一次找回。
 * 后端 {@code DeviceStatus#canManualTransfer} 已同步放行 LOST → SCRAPPED。
 */
function canScrap(status: DeviceStatusCode): boolean {
  return status === 'AVAILABLE' || status === 'MAINTENANCE' || status === 'LOST'
}

/** 维修完成：仅「维修中」允许 */
function canCompleteMaintenance(status: DeviceStatusCode): boolean {
  return status === 'MAINTENANCE'
}

/** 台账直接登记故障：仅「可用」设备，登记后设备进入「维修中」 */
function canRegisterFault(status: DeviceStatusCode): boolean {
  return status === 'AVAILABLE'
}

/**
 * 找回：仅「已丢失」允许（P0）。
 *
 * <p>丢失与报废的区别就在这里：报废是不可逆的终态，而丢失<b>可能找回来</b>。
 * 没有这条通路的话，一台被误标丢失的设备就只能永久退出可借资产。
 */
function canRetrieve(status: DeviceStatusCode): boolean {
  return status === 'LOST'
}

/**
 * 转维修：仅「可用」允许（P0，用户明确要求）。
 *
 * <p>典型场景：归还检查登记「缺配件」后设备主体回到「可用」，
 * 管理员看到检查说明后若认为缺件影响使用，需要一条把它转维修中的通路。
 * ⚠️ 只对「可用」开放：<b>使用中</b>的设备必须走归还流程进维修，
 * 手工置入会让设备状态与工单状态彻底脱钩（工单永远收不回）。
 */
function canSendToMaintenance(status: DeviceStatusCode): boolean {
  return status === 'AVAILABLE'
}

/** 标记丢失：仅「可用 / 维修中」允许（P0，盘点或日常发现设备不见了） */
function canMarkLost(status: DeviceStatusCode): boolean {
  return status === 'AVAILABLE' || status === 'MAINTENANCE'
}

async function handleScrap(row: DeviceItem): Promise<void> {
  let reason = ''
  try {
    const result = await ElMessageBox.prompt(
      `确认报废设备「${row.deviceName}」（资产编号 ${row.assetNo}）？报废后不可再被申请借用，历史工单仍可查询。`,
      '设备报废',
      {
        type: 'warning',
        confirmButtonText: '确认报废',
        cancelButtonText: '取消',
        inputPlaceholder: '报废原因（选填，仅记入操作日志用于审计追溯，不影响设备备注）',
        inputValidator: () => true
      }
    )
    reason = result.value ?? ''
  } catch {
    return
  }
  try {
    await deviceApi.changeStatus(row.id, 'SCRAPPED', reason.trim() || undefined)
    ElMessage.success('设备已报废')
    await load()
  } catch {
    // 借用中等非法操作由后端返回明确提示
  }
}

async function handleCompleteMaintenance(row: DeviceItem): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认设备「${row.deviceName}」（资产编号 ${row.assetNo}）维修完成？确认后设备将恢复为「可用」状态。`,
      '维修完成',
      { type: 'info', confirmButtonText: '确认完成', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await deviceApi.changeStatus(row.id, 'AVAILABLE', '维修完成')
    ElMessage.success('设备已恢复为可用')
    await load()
  } catch {
    // 错误提示由请求层统一处理
  }
}

async function handleRetrieve(row: DeviceItem): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认设备「${row.deviceName}」（资产编号 ${row.assetNo}）已找回？确认后设备将恢复为「可用」状态，可再次被申请借用。`,
      '找回设备',
      { type: 'info', confirmButtonText: '确认找回', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await deviceApi.changeStatus(row.id, 'AVAILABLE', '丢失找回')
    ElMessage.success('设备已恢复为可用')
    await load()
  } catch {
    // 错误提示由请求层统一处理
  }
}

async function handleSendToMaintenance(row: DeviceItem): Promise<void> {
  let reason = ''
  try {
    const result = await ElMessageBox.prompt(
      `将设备「${row.deviceName}」（资产编号 ${row.assetNo}）改为「维修中」？改为维修中后不可再被申请借用，修好后可点「维修完成」恢复。`,
      '转维修中',
      {
        type: 'warning',
        confirmButtonText: '确认转维修',
        cancelButtonText: '取消',
        inputPlaceholder: '送修原因（选填，例如「归还时缺配件，需补齐」）',
        inputValidator: () => true
      }
    )
    reason = result.value ?? ''
  } catch {
    return
  }
  try {
    await deviceApi.changeStatus(row.id, 'MAINTENANCE', reason.trim() || '管理员手动转维修')
    ElMessage.success('设备已转为维修中')
    await load()
  } catch {
    // 错误提示由请求层统一处理
  }
}

async function handleMarkLost(row: DeviceItem): Promise<void> {
  let reason = ''
  try {
    const result = await ElMessageBox.prompt(
      `将设备「${row.deviceName}」（资产编号 ${row.assetNo}）标记为「已丢失」？标记后设备不可再被申请借用；若日后找回，可点「找回」恢复为可用。`,
      '标记丢失',
      {
        type: 'warning',
        confirmButtonText: '确认标记丢失',
        cancelButtonText: '取消',
        inputPlaceholder: '丢失情况说明（选填，例如「盘点时未找到」）',
        inputValidator: () => true
      }
    )
    reason = result.value ?? ''
  } catch {
    return
  }
  try {
    await deviceApi.changeStatus(row.id, 'LOST', reason.trim() || '管理员标记丢失')
    ElMessage.success('设备已标记为丢失')
    await load()
  } catch {
    // 错误提示由请求层统一处理
  }
}

async function handleDelete(row: DeviceItem): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认删除设备「${row.deviceName}」（资产编号 ${row.assetNo}）？删除为软删除（记录保留可追溯），但该资产编号不可再被复用。`,
      '删除设备',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await deviceApi.remove(row.id)
    ElMessage.success('设备已删除')
    // 删除后当前页可能已空，回退一页
    if (records.value.length === 1 && page.value > 1) {
      page.value -= 1
    }
    await load()
  } catch {
    // 错误提示由请求层统一处理
  }
}

// ------------------------------------------------------------------
// 台账直接登记故障
// ------------------------------------------------------------------

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

function openFault(row: DeviceItem): void {
  faultTarget.value = row
  faultForm.faultDescription = ''
  faultForm.occurredAt = nowText()
  faultVisible.value = true
}

async function submitFault(): Promise<void> {
  const target = faultTarget.value
  if (!target) {
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
      deviceId: target.id,
      orderId: null,
      faultDescription: faultForm.faultDescription.trim(),
      occurredAt: faultForm.occurredAt
    })
    ElMessage.success('故障已登记，设备进入「维修中」')
    faultVisible.value = false
    await load()
  } catch {
    // 设备状态已变化等冲突由请求层统一提示
  } finally {
    faultSubmitting.value = false
  }
}

function categoryText(row: DeviceItem): string {
  if (!row.primaryCategoryName) {
    return '-'
  }
  return row.secondaryCategoryName
    ? `${row.primaryCategoryName} / ${row.secondaryCategoryName}`
    : row.primaryCategoryName
}

/**
 * 设备金额展示文案。
 *
 * 「未录入」与「0 元」是两件事：金额为空时审批走「不超过阈值」的分支，
 * 显示成 `0.00 元` 会让维护人员以为系统把它当成了 0 元设备，而不再去补录。
 * 收成一个函数而不是在模板里写三元：模板表达式拿不到 `v-if` 的类型收窄
 * （`(row as DeviceItem).amount` 会被判成 `number | null | undefined`）。
 */
function amountText(row: DeviceItem): string {
  const amount = row.amount
  return amount == null ? '未录入' : `${formatAmount(amount)} 元`
}

/**
 * 设备当前使用信息主行（需求方 ）
 *
 * 设备表不冗余使用人，使用信息以「占用中的工单」为准（后端 usageMap 装配）。
 * 三种情形：临时锁定（显示锁定人）> 有占用工单（使用人 + 类型）> 无（空闲）。
 */
function usagePrimary(row: DeviceItem): string {
  if (row.status === 'LOCKED') {
    return row.lockedByName ? `临时锁定：${row.lockedByName}` : '临时锁定中'
  }
  if (!row.usage) {
    return '-'
  }
  const name = row.usage.userName ?? '-'
  return `${name} · ${row.usage.useTypeLabel}`
}

/** 使用信息次行：工单号 + 归还日期（仅短期借用有归还日期） */
function usageSecondary(row: DeviceItem): string {
  if (row.status === 'LOCKED') {
    return row.lockExpiresAt ? `锁到期 ${row.lockExpiresAt}` : '超时自动释放'
  }
  if (!row.usage) {
    return ''
  }
  if (row.usage.useType === 'SHORT_TERM' && row.usage.expectedReturnDate) {
    return `${row.usage.orderNo} · 归还 ${row.usage.expectedReturnDate}`
  }
  return row.usage.orderNo
}

/** 是否处于占用态（使用中 / 审批中 / 待交付 / 临时锁定），用于控制台与卡片强调展示 */
function isOccupied(row: DeviceItem): boolean {
  return row.status === 'LOCKED' || row.status === 'IN_APPROVAL' || row.status === 'IN_USE'
}
</script>

<template>
  <div class="ts-page">
    <section class="ts-card">
      <div class="ts-flex-between ts-dev__head">
        <div>
          <h3 class="ts-dev__title">设备台账</h3>
          <p class="ts-text-secondary ts-dev__desc">
            资产编号全局唯一；报废（不可再申请）与删除（台账隐藏）是两种不同语义。
          </p>
        </div>
        <el-space>
          <!--
            打印标签：对**勾选**的设备打。勾一台就是单张、勾 N 台就是批量 ——
            需求里的「点某台再点打印标签」与「勾选多台批量打印」本就是同一个动作。
          -->
          <el-button :disabled="selectedIds.length === 0" @click="openLabelForSelection">打印标签</el-button>
        </el-space>
        <el-space v-if="canEdit">
          <el-button @click="importVisible = true">批量导入</el-button>
          <el-button :loading="exporting" @click="handleExport">导出 Excel</el-button>
          <el-button type="primary" @click="openCreate">+ 新增设备</el-button>
        </el-space>
      </div>

      <!-- 查询条件 -->
      <div class="ts-dev__filters ts-mt-16">
        <el-input
          v-model="query.keyword"
          class="ts-dev__filter-item ts-dev__filter-keyword"
          placeholder="设备名称 / 资产编号 / 序列号"
          clearable
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
        <el-select
          v-model="query.primaryCategoryId"
          class="ts-dev__filter-item"
          placeholder="一级分类"
          clearable
          @change="clearSecondaryOnPrimaryChange"
        >
          <el-option v-for="item in categoryTree" :key="item.id" :label="item.categoryName" :value="item.id" />
        </el-select>
        <el-select
          v-model="query.secondaryCategoryId"
          class="ts-dev__filter-item"
          placeholder="二级分类"
          clearable
          :disabled="query.primaryCategoryId == null"
        >
          <el-option v-for="item in secondaryOptions" :key="item.id" :label="item.categoryName" :value="item.id" />
        </el-select>
        <el-select v-model="query.status" class="ts-dev__filter-item" placeholder="设备状态" clearable>
          <el-option v-for="item in DEVICE_STATUS_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <div class="ts-dev__filter-actions">
          <el-button type="primary" :loading="loading" @click="handleSearch">查询</el-button>
          <el-button @click="handleReset">重置</el-button>
        </div>
      </div>

      <!-- 批量操作条（P3）：只在有勾选时出现，避免常态占用版面 -->
      <div v-if="selectedIds.length > 0" class="ts-dev__bulk ts-mt-16">
        <span class="ts-text-hint">已选 {{ selectedIds.length }} 台</span>
        <el-button type="primary" size="small" :loading="batchSubmitting" @click="openBatchCategory">
          批量改分类
        </el-button>
        <el-button type="primary" size="small" :loading="batchSubmitting" @click="openBatchStatus">
          批量改状态
        </el-button>
        <el-button size="small" @click="openLabelForSelection">打印标签</el-button>
        <el-button size="small" @click="clearSelection">取消选择</el-button>
      </div>

      <TablePage
        ref="tablePageRef"
        :columns="columns"
        :rows="records"
        :loading="loading"
        route-path="/asset/ledger"
        :page="page"
        :size="size"
        :total="total"
        empty-text="暂无设备，请先新增或调整筛选条件"
        class="ts-mt-16"
        @update:page="handlePageChange"
        @update:size="handleSizeChange"
        @selection-change="handleSelectionChange"
      >
        <template #cell-category="{ row }">{{ categoryText(row as DeviceItem) }}</template>
        <template #cell-brandModel="{ row }">
          <span v-if="(row as DeviceItem).brand || (row as DeviceItem).model">
            {{ [(row as DeviceItem).brand, (row as DeviceItem).model].filter(Boolean).join(' / ') }}
          </span>
          <span v-else class="ts-text-hint">-</span>
        </template>
        <template #cell-statusLabel="{ row }">
          <el-tag :type="deviceStatusTagType((row as DeviceItem).status)" size="small" effect="plain">
            {{ (row as DeviceItem).statusLabel }}
          </el-tag>
        </template>
        <template #cell-usage="{ row }">
          <template v-if="isOccupied(row as DeviceItem)">
            <div>{{ usagePrimary(row as DeviceItem) }}</div>
            <div class="ts-text-hint">{{ usageSecondary(row as DeviceItem) }}</div>
          </template>
          <span v-else class="ts-text-hint">空闲</span>
        </template>
        <template #cell-storageLocation="{ row }">
          <span v-if="(row as DeviceItem).storageLocation">{{ (row as DeviceItem).storageLocation }}</span>
          <span v-else class="ts-text-hint">-</span>
        </template>
        <template #cell-amount="{ row }">
          <span
            v-if="(row as DeviceItem).amount == null"
            class="ts-text-hint"
            title="未录入金额的设备，借用审批按「不超过阈值」处理"
          >
            未录入
          </span>
          <span v-else>{{ amountText(row as DeviceItem) }}</span>
        </template>
        <template #cell-action="{ row }">
          <el-button link type="primary" size="small" @click="openLabelForRow(row as DeviceItem)">
            打印标签
          </el-button>
          <el-button v-if="canEdit" link type="primary" size="small" @click="openEdit(row as DeviceItem)">
            编辑
          </el-button>
          <el-button
            v-if="canEdit && canCompleteMaintenance((row as DeviceItem).status)"
            link
            type="success"
            size="small"
            @click="handleCompleteMaintenance(row as DeviceItem)"
          >
            维修完成
          </el-button>
          <el-button
            v-if="canEdit && canRegisterFault((row as DeviceItem).status)"
            link
            type="danger"
            size="small"
            @click="openFault(row as DeviceItem)"
          >
            登记故障
          </el-button>
          <el-button
            v-if="canEdit && canSendToMaintenance((row as DeviceItem).status)"
            link
            type="primary"
            size="small"
            @click="handleSendToMaintenance(row as DeviceItem)"
          >
            转维修
          </el-button>
          <el-button
            v-if="canEdit && canRetrieve((row as DeviceItem).status)"
            link
            type="success"
            size="small"
            @click="handleRetrieve(row as DeviceItem)"
          >
            找回
          </el-button>
          <el-button
            v-if="canEdit && canMarkLost((row as DeviceItem).status)"
            link
            type="danger"
            size="small"
            @click="handleMarkLost(row as DeviceItem)"
          >
            标记丢失
          </el-button>
          <el-button
            v-if="canEdit && canScrap((row as DeviceItem).status)"
            link
            type="warning"
            size="small"
            @click="handleScrap(row as DeviceItem)"
          >
            报废
          </el-button>
          <el-button v-if="canEdit" link type="danger" size="small" @click="handleDelete(row as DeviceItem)">
            删除
          </el-button>
          <span
            v-if="canEdit && !canScrap((row as DeviceItem).status)"
            class="ts-text-hint"
            :title="'状态为「' + (row as DeviceItem).statusLabel + '」，状态变更由工单流程驱动'"
          >
            状态流转中
          </span>
        </template>

        <!-- 移动端卡片保留改造前结构（无「更新时间」行，且「使用 / 占用」仅占用时出现） -->
        <template #mobile>
          <div v-loading="loading" class="ts-dev__cards">
            <div v-for="row in records" :key="row.id" class="ts-dev__card">
              <div class="ts-flex-between">
                <strong class="ts-dev__card-title">{{ row.deviceName }}</strong>
                <el-tag :type="deviceStatusTagType(row.status)" size="small" effect="plain">
                  {{ row.statusLabel }}
                </el-tag>
              </div>
              <div class="ts-dev__card-row">
                <span class="ts-text-hint">资产编号</span>
                <span>{{ row.assetNo }}</span>
              </div>
              <div class="ts-dev__card-row">
                <span class="ts-text-hint">分类</span>
                <span>{{ categoryText(row) }}</span>
              </div>
              <div v-if="isOccupied(row)" class="ts-dev__card-row">
                <span class="ts-text-hint">使用 / 占用</span>
                <span class="ts-dev__usage">
                  <span>{{ usagePrimary(row) }}</span>
                  <span class="ts-text-hint">{{ usageSecondary(row) }}</span>
                </span>
              </div>
              <div class="ts-dev__card-row">
                <span class="ts-text-hint">品牌型号</span>
                <span>{{ [row.brand, row.model].filter(Boolean).join(' / ') || '-' }}</span>
              </div>
              <div class="ts-dev__card-row">
                <span class="ts-text-hint">存放位置</span>
                <span>{{ row.storageLocation || '-' }}</span>
              </div>
              <div class="ts-dev__card-row">
                <span class="ts-text-hint">设备金额</span>
                <span>{{ row.amount != null ? formatAmount(row.amount) + ' 元' : '未录入' }}</span>
              </div>
              <div v-if="canEdit" class="ts-dev__card-actions">
                <el-button size="small" @click="openLabelForRow(row)">打印标签</el-button>
                <el-button size="small" @click="openEdit(row)">编辑</el-button>
                <el-button
                  v-if="canCompleteMaintenance(row.status)"
                  size="small"
                  type="success"
                  plain
                  @click="handleCompleteMaintenance(row)"
                >
                  维修完成
                </el-button>
                <el-button
                  v-if="canRegisterFault(row.status)"
                  size="small"
                  type="danger"
                  plain
                  @click="openFault(row)"
                >
                  登记故障
                </el-button>
                <el-button
                  v-if="canSendToMaintenance(row.status)"
                  size="small"
                  type="primary"
                  plain
                  @click="handleSendToMaintenance(row)"
                >
                  转维修
                </el-button>
                <el-button
                  v-if="canRetrieve(row.status)"
                  size="small"
                  type="success"
                  plain
                  @click="handleRetrieve(row)"
                >
                  找回
                </el-button>
                <el-button
                  v-if="canMarkLost(row.status)"
                  size="small"
                  type="danger"
                  plain
                  @click="handleMarkLost(row)"
                >
                  标记丢失
                </el-button>
                <el-button v-if="canScrap(row.status)" size="small" type="warning" plain @click="handleScrap(row)">
                  报废
                </el-button>
                <el-button size="small" type="danger" plain @click="handleDelete(row)">删除</el-button>
              </div>
            </div>
            <el-empty
              v-if="!loading && records.length === 0"
              :image-size="70"
              description="暂无设备，请先新增或调整筛选条件"
            />
          </div>
        </template>
      </TablePage>

      <p v-if="!canEdit" class="ts-text-hint">当前角色为只读，维护设备台账需要管理员及以上权限</p>
    </section>

    <!-- 新增 / 编辑 -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogMode === 'create' ? '新增设备' : '编辑设备'"
      :width="isMobile ? '94%' : '640px'"
    >
      <el-form label-width="88px" :label-position="isMobile ? 'top' : 'right'">
        <el-form-item label="设备名称" required>
          <el-input v-model="form.deviceName" maxlength="128" show-word-limit placeholder="例如：ThinkPad X1 Carbon" />
        </el-form-item>
        <el-form-item label="资产编号" required>
          <el-input v-model="form.assetNo" maxlength="64" show-word-limit placeholder="全局唯一，例如：IT-2026-0001" />
        </el-form-item>
        <el-form-item label="一级分类" required>
          <el-select
            v-model="form.primaryCategoryId"
            class="ts-dev__form-select"
            placeholder="请选择一级分类"
            @change="clearFormSecondaryOnPrimaryChange"
          >
            <el-option v-for="item in categoryTree" :key="item.id" :label="item.categoryName" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="二级分类">
          <el-select
            v-model="form.secondaryCategoryId"
            class="ts-dev__form-select"
            placeholder="选填（可不指定二级分类）"
            clearable
            :disabled="form.primaryCategoryId == null"
          >
            <el-option
              v-for="item in formSecondaryOptions"
              :key="item.id"
              :label="item.categoryName"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="品牌">
          <el-input v-model="form.brand" maxlength="64" placeholder="选填" />
        </el-form-item>
        <el-form-item label="型号">
          <el-input v-model="form.model" maxlength="128" placeholder="选填" />
        </el-form-item>
        <el-form-item label="序列号">
          <el-input v-model="form.serialNo" maxlength="128" placeholder="选填（支持按序列号搜索）" />
        </el-form-item>
        <el-form-item label="存放位置">
          <el-input v-model="form.storageLocation" maxlength="128" placeholder="选填，例如：A 座 3F 机房" />
        </el-form-item>
        <el-form-item label="购置日期">
          <el-date-picker
            v-model="form.purchaseDate"
            class="ts-dev__form-select"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选填"
          />
        </el-form-item>
        <el-form-item label="设备金额">
          <el-input-number
            v-model="form.amount"
            class="ts-dev__form-select"
            :min="0"
            :max="9999999999"
            :precision="2"
            :step="100"
            :controls="false"
            placeholder="选填，单位：元"
          />
          <span class="ts-text-hint">用于借用审批的金额分档（超过阈值时加一级上级部门主管审批）</span>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="500" show-word-limit placeholder="选填" />
        </el-form-item>
      </el-form>
      <p class="ts-text-hint">
        提示：新设备一律以「可用」状态创建；状态变更请使用列表中的「登记故障 / 维修完成 / 报废」操作。
      </p>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>

    <!-- 台账直接登记故障 -->
    <el-dialog v-model="faultVisible" title="登记设备故障" :width="isMobile ? '94%' : '460px'">
      <p class="ts-text-secondary ts-dev__fault-hint">
        设备：{{ faultTarget?.deviceName }}（资产编号 {{ faultTarget?.assetNo }}）
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
            class="ts-dev__form-select"
          />
        </el-form-item>
        <el-form-item label="故障描述" required>
          <el-input
            v-model="faultForm.faultDescription"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="请描述故障现象（如：开不了机、屏幕破损）"
          />
        </el-form-item>
      </el-form>
      <p class="ts-text-hint">登记后设备进入「维修中」，维修完成后可在「设备故障记录」页登记恢复可用。</p>
      <template #footer>
        <el-button @click="faultVisible = false">取消</el-button>
        <el-button type="danger" :loading="faultSubmitting" @click="submitFault">确认登记</el-button>
      </template>
    </el-dialog>

    <!-- 批量改分类（P3） -->
    <el-dialog v-model="batchCategoryVisible" title="批量修改分类" :width="isMobile ? '94%' : '440px'">
      <p class="ts-text-hint">
        将选中的 {{ selectedIds.length }} 台设备的一级分类统一改为下方所选。只改分类，品牌 / 型号 /
        存放位置不受影响。
      </p>
      <el-select
        v-model="batchCategoryId"
        placeholder="请选择目标分类"
        class="ts-mt-16"
        style="width: 100%"
      >
        <el-option v-for="item in categoryTree" :key="item.id" :label="item.categoryName" :value="item.id" />
      </el-select>
      <template #footer>
        <el-button @click="batchCategoryVisible = false">取消</el-button>
        <el-button type="primary" :loading="batchSubmitting" @click="submitBatchCategory">
          确认修改
        </el-button>
      </template>
    </el-dialog>

    <!-- 批量改状态（P3） -->
    <el-dialog v-model="batchStatusVisible" title="批量变更状态" :width="isMobile ? '94%' : '460px'">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="逐台按各自的当前状态校验"
        description="只有「可用 / 维修中 / 已丢失 / 已报废」可作为手工目标；使用中与审批中的设备会被拒绝，并在结果里列出原因。"
      />
      <el-form label-width="80px" class="ts-mt-16">
        <el-form-item label="目标状态">
          <el-select v-model="batchForm.targetStatus" placeholder="请选择目标状态" style="width: 100%">
            <el-option
              v-for="item in MANUAL_DEVICE_STATUS_OPTIONS"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="操作理由">
          <el-input
            v-model="batchForm.reason"
            maxlength="255"
            show-word-limit
            placeholder="选填，会记入审计日志"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="batchStatusVisible = false">取消</el-button>
        <el-button type="primary" :loading="batchSubmitting" @click="submitBatchStatus">
          确认变更
        </el-button>
      </template>
    </el-dialog>

    <BatchResultDialog v-model="batchResultVisible" :result="batchResult" title="批量操作结果" unit="台" />

    <DeviceLabelDialog v-model="labelVisible" :devices="labelDevices" />

    <!-- 批量导入（管理端，与单条新增权限一致） -->
    <DeviceImportDialog v-model="importVisible" @imported="load" />
  </div>
</template>

<style scoped>
/* 批量操作条（P3）：浅底 + 流式换行，窄屏下按钮自动折行 */
.ts-dev__bulk {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  background: var(--el-fill-color-light);
  border-radius: 8px;
}

.ts-dev__head {
  gap: 16px;
  align-items: flex-start;
}

.ts-dev__title {
  margin: 0;
  font-size: 16px;
  font-weight: 500;
}

.ts-dev__desc {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.6;
}

.ts-dev__filters {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.ts-dev__filter-item {
  width: 170px;
}

.ts-dev__filter-keyword {
  width: 240px;
}

.ts-dev__filter-actions {
  display: flex;
  gap: 8px;
}

.ts-dev__cards {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.ts-dev__card {
  padding: 12px;
  border: 1px solid var(--ts-border);
  border-radius: 10px;
  background: #fbfcfe;
}

.ts-dev__card-title {
  word-break: break-all;
}

.ts-dev__card-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 3px 0;
  font-size: 13px;
}

.ts-dev__usage {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
  text-align: right;
}

.ts-dev__card-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
}


.ts-dev__form-select {
  width: 100%;
}

.ts-dev__fault-hint {
  margin: 0 0 12px;
  font-size: 12px;
  line-height: 1.6;
}

@media (max-width: 767px) {
  .ts-dev__head {
    flex-direction: column;
  }

  .ts-dev__head .el-button {
    width: 100%;
  }

  .ts-dev__filter-item,
  .ts-dev__filter-keyword {
    width: 100%;
  }

  .ts-dev__filter-actions .el-button {
    flex: 1 1 0;
  }

  .ts-dev__card-actions .el-button {
    flex: 1 1 0;
    margin-left: 0;
  }
}
</style>
