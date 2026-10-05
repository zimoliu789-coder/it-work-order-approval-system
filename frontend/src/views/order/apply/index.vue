<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { deviceApi, deviceFaultApi } from '@/api/device'
import { uploadAttachments } from '@/api/attachment'
import { applyTypeApi } from '@/api/applyType'
import { orderApi } from '@/api/order'
import PendingAttachmentPicker from '@/components/PendingAttachmentPicker.vue'
import { useResponsive } from '@/composables/useResponsive'
import type { ApplyTypeOption } from '@/types/applyType'
import {
  borrowNodeTypeLabel,
  borrowNodeTypeTagType,
  shouldShowBorrowPath,
  type BorrowFlowPreview
} from '@/types/department'
import { DEFAULT_USE_TYPE, type DeviceOption } from '@/types/order'
import type { DeviceFaultSelectable, DeviceLockInfo } from '@/types/device'
import {
  formatCountdown,
  remainingLockSeconds,
  todayString,
  validateOrderForm,
  type OrderFormState
} from '@/utils/orderForm'

/**
 * 提交申请（，需求方  的两种工单类型 +  故障报修）
 *
 * 两步结构：
 * 1. 步骤一：卡片式选择申请类型，由 APPLY_TYPES 数组驱动（新增类型 = 加一项）；
 * 2. 步骤二：进入对应表单，顶部「← 返回重选类型」回到步骤一（若借表单已临时锁设备则释放锁）。
 *
 * <h2>：借用表单三项化</h2>
 * 需求文档「三·」要求员工只填三项。落地后：
 * <ul>
 *   <li><b>步骤一的借用卡片由两张合并为一张</b> —— 原「短期借用 / 长期领用」两张卡本质是
 *       「先选借用类型」，而借用类型已固定为短期借用（{@link DEFAULT_USE_TYPE}）。
 *       留着两张卡指向同一个表单，用户只会问「这两个有什么区别」。</li>
 *   <li><b>表单只剩「借什么设备 / 还的日期 / 用途」+ 附件</b>（附件按需求方决策 3 保留为可选）。</li>
 *   <li><b>归还日期由条件必填升级为常显必填</b> —— 原来的 `v-if="needReturnDate"` 与
 *       「长期领用 → 归还方式」提示一并删除。</li>
 * </ul>
 * 设备临时锁逻辑与审批路径预览（M1）**一字未改** ——
 * 它们与字段多少无关，改表单不该动流程。
 *
 * 故障报修表单：设备从 deviceFaultApi.selectableDevices() 下拉（label 直显），
 * 选中项若带 orderId 则提交时带上；故障描述 + 发生时间（不得晚于当前时间），提交 deviceFaultApi.report。
 *
 *  追加附件：借用提交时随单上传「申请附件」，故障报修时随单上传「故障照片」。
 * 附件需绑定业务记录 id，而 id 只能等主操作成功后才返回，故先在本地挑选、成功后统一上传；
 * 附件为可选，上传失败不阻断主流程（仅提示成功数量）。
 */

const { isMobile } = useResponsive()
const route = useRoute()
const router = useRouter()

// ------------------------------------------------------------------
// 自定义申请类型
//
// 与上面两种内置类型并列但<b>数据来源不同</b>：内置类型写死在 APPLY_TYPES 数组里，
// 自定义类型由管理员在「申请类型管理」中配置，且会按当前用户的提交权限过滤 ——
// 因此必须由服务端下发（前端过滤等于没过滤，直调接口即可绕过，）。
// 没有可提交的自定义类型时整块不渲染，避免出现一个永远点不出东西的空壳区域。
// ------------------------------------------------------------------

const customTypes = ref<ApplyTypeOption[]>([])

async function loadCustomTypes(): Promise<void> {
  try {
    customTypes.value = await applyTypeApi.enabled()
  } catch {
    customTypes.value = []
  }
}

/** 进入某个自定义类型的填写页（独立路由，便于刷新后仍停在该类型上） */
function selectCustomType(option: ApplyTypeOption): void {
  void router.push(`/order/apply/custom/${option.id}`)
}

// ------------------------------------------------------------------
// 两步状态
// ------------------------------------------------------------------

/**
 * 申请类型（**入口形态**，不是借用类型）。
 *
 *  起内置只有两项：借用设备 / 故障报修。原来的 `SHORT_TERM` / `LONG_TERM`
 * 两个值代表「借用类型」，已随选择器一起退役 —— 现在借用一律短期，入口不区分。
 */
type ApplyType = 'BORROW' | 'FAULT_REPORT'

interface ApplyTypeItem {
  value: ApplyType
  title: string
  desc: string
}

/** 由该数组驱动步骤一卡片，扩展新申请类型只需追加一项 */
const APPLY_TYPES: ApplyTypeItem[] = [
  { value: 'BORROW', title: '借用设备', desc: '选设备、填归还日期即可提交' },
  { value: 'FAULT_REPORT', title: '故障报修', desc: '设备报修，登记故障记录' }
]

const step = ref<'select' | 'form'>('select')
const applyType = ref<ApplyType | null>(null)

/** 选中申请类型：借用类拉一次路径预览；故障类拉取可选设备 */
function selectType(type: ApplyType): void {
  applyType.value = type
  step.value = 'form'
  if (type === 'FAULT_REPORT') {
    void loadFaultDevices()
  } else {
    // 进入借用表单即拉一次路径预览（此时还没有设备，仍能展示不依赖设备的节点）
    void refreshBorrowPreview()
  }
}

/** 返回重选类型：若借表单持有临时锁，先释放，避免占用他人可选设备 */
function backToSelect(): void {
  void releaseLock()
  applyType.value = null
  step.value = 'select'
}

// ------------------------------------------------------------------
// 本地草稿缓存（需求方三波·第二波·；「前端增加本地草稿缓存，浏览器刷新也保留草稿」）
//
// 只缓存「用户填写的文本类字段」：设备 ID 不缓存 —— 临时锁（默认 5 分钟）早已过期，
// 恢复一个锁不存在的设备 ID 会让用户以为可以直接提交，提交时又报错，体验反而更差。
// 附件（File 对象）无法序列化，同样不缓存。草稿带 7 天有效期，过期自动丢弃。
// ------------------------------------------------------------------

const DRAFT_KEY = 'ticket:apply-draft'
const DRAFT_TTL_MS = 7 * 24 * 3600 * 1000

interface ApplyDraft {
  step: 'select' | 'form'
  applyType: ApplyType | null
  form: Pick<OrderFormState, 'reason' | 'expectedReturnDate'>
  fault: { faultDescription: string; occurredAt: string }
  savedAt: number
}

/**
 * 收口老草稿里的申请类型（ 迁移）。
 *
 *  之前草稿里存的是 `SHORT_TERM` / `LONG_TERM`（两张借用卡）。若原样恢复，
 * `step` 会被置为 `'form'`，而模板里没有任何一个 `applyType === ...` 分支能命中 ——
 * **页面会渲染成一片空白**（不报错，最难查）。因此这里统一映射到 `BORROW`。
 */
function normalizeDraftApplyType(value: unknown): ApplyType | null {
  if (value === 'BORROW' || value === 'FAULT_REPORT') {
    return value
  }
  if (value === 'SHORT_TERM' || value === 'LONG_TERM') {
    return 'BORROW'
  }
  return null
}

let draftTimer: ReturnType<typeof setTimeout> | null = null

/** 草稿是否含有值得保留的内容（全空则移除，避免每次进页面都提示「已恢复草稿」） */
function hasDraftContent(): boolean {
  return (
    form.reason.trim() !== '' ||
    form.expectedReturnDate != null ||
    faultForm.faultDescription.trim() !== '' ||
    faultForm.occurredAt !== ''
  )
}

/** 防抖落盘：输入过程中不必每次都写 localStorage */
function scheduleSaveDraft(): void {
  if (draftTimer !== null) {
    clearTimeout(draftTimer)
  }
  draftTimer = setTimeout(persistDraft, 400)
}

function persistDraft(): void {
  try {
    if ((step.value !== 'form' && applyType.value === null) || !hasDraftContent()) {
      localStorage.removeItem(DRAFT_KEY)
      return
    }
    const payload: ApplyDraft = {
      step: step.value,
      applyType: applyType.value,
      form: {
        reason: form.reason,
        expectedReturnDate: form.expectedReturnDate
      },
      fault: { faultDescription: faultForm.faultDescription, occurredAt: faultForm.occurredAt },
      savedAt: Date.now()
    }
    localStorage.setItem(DRAFT_KEY, JSON.stringify(payload))
  } catch {
    // 隐私模式 / 配额满：草稿是增强体验，写不进去就算了，绝不能影响主流程
  }
}

function clearDraft(): void {
  if (draftTimer !== null) {
    clearTimeout(draftTimer)
    draftTimer = null
  }
  try {
    localStorage.removeItem(DRAFT_KEY)
  } catch {
    // 忽略
  }
}

/** 恢复草稿（设备需重新选择；临时锁不会恢复） */
function restoreDraft(): void {
  let raw: string | null = null
  try {
    raw = localStorage.getItem(DRAFT_KEY)
  } catch {
    return
  }
  if (!raw) {
    return
  }
  let draft: ApplyDraft | null = null
  try {
    draft = JSON.parse(raw) as ApplyDraft
  } catch {
    clearDraft()
    return
  }
  if (!draft || typeof draft.savedAt !== 'number' || Date.now() - draft.savedAt > DRAFT_TTL_MS) {
    clearDraft()
    return
  }
  if (draft.form) {
    form.reason = draft.form.reason ?? ''
    form.expectedReturnDate = draft.form.expectedReturnDate ?? null
  }
  if (draft.fault) {
    faultForm.faultDescription = draft.fault.faultDescription ?? ''
    faultForm.occurredAt = draft.fault.occurredAt ?? ''
  }
  const restoredType = normalizeDraftApplyType(draft.applyType)
  applyType.value = restoredType
  step.value = draft.step === 'form' && restoredType !== null ? 'form' : 'select'
  if (step.value === 'form' && restoredType === 'FAULT_REPORT') {
    void loadFaultDevices()
  }
  ElMessage.info('已恢复上次未提交的草稿（设备需重新选择）')
}

// ------------------------------------------------------------------
// 借用表单（ / ）； 起为「借什么设备 / 还的日期 / 用途」三项
// ------------------------------------------------------------------

const loadingDevices = ref(false)
const submitting = ref(false)
const deviceOptions = ref<DeviceOption[]>([])

/** 随单提交的申请附件（本地待上传，） */
const applyFiles = ref<File[]>([])

const lockInfo = ref<DeviceLockInfo | null>(null)
const lockRemaining = ref(0)
let lockTimer: ReturnType<typeof setInterval> | null = null

const form = reactive<OrderFormState>({
  deviceId: null,
  reason: '',
  expectedReturnDate: null
})

const lockText = computed(() => (lockInfo.value ? formatCountdown(lockRemaining.value) : ''))

const formErrors = computed(() => validateOrderForm(form))
const canSubmit = computed(() => formErrors.value.length === 0 && lockInfo.value != null && !submitting.value)

onMounted(async () => {
  await loadDevices()
  // 自定义申请类型：与服务端权限过滤后的可用类型
  await loadCustomTypes()
  // 恢复上次未提交的草稿（在设备列表就绪后，避免恢复出的设备项无法匹配）
  restoreDraft()
  // 扫码借还跳转带来的 ?deviceId=（P1）：放在草稿恢复**之后** ——
  // URL 是本次的明确动作，应当覆盖上次残留的草稿选择。
  await applyScanPreselect()
  // 草稿恢复后表单可能已就绪 → 拉一次借用路径预览（M1）
  void refreshBorrowPreview()
})

onBeforeUnmount(() => {
  stopLockTimer()
  // 离开页面主动释放；失败也无妨，服务端 5 分钟超时释放兜底
  void releaseLock()
})

async function loadDevices(): Promise<void> {
  loadingDevices.value = true
  try {
    deviceOptions.value = await orderApi.selectableDevices()
  } catch {
    deviceOptions.value = []
  } finally {
    loadingDevices.value = false
  }
}

function deviceLabel(device: DeviceOption): string {
  const category = device.secondaryCategoryName || device.primaryCategoryName || ''
  return `${device.deviceName}（${device.assetNo}）${category ? ' · ' + category : ''}`
}

/**
 * 扫码借还跳转带来的 ?deviceId=（P1 扫码借还）：自动进入借用表单并选中该设备。
 *
 * <h2>为什么必须连「进第二步」一起做</h2>
 * <p>申请页是两步式的（先选申请类型，再填表单），设备选择器只在第二步渲染。
 * 如果只把 deviceId 填进（看不见的）第二步表单里，用户扫完码看到的仍然是
 * 「请选择申请类型」—— 会以为扫码没生效，然后又自己选一遍。
 * 扫码的意图本来就非常明确：<b>就是要借这一台</b>，所以直接替他选好类型。
 *
 * <h2>为什么先查一遍可申请列表</h2>
 * <p>列表本身就是服务端的「可申请」口径（AVAILABLE，或临时锁已超时可直接接管）。
 * 不在列表里说明它已被借走 / 维修中 / 正被他人锁定，此时若仍然加锁，
 * 只会拿到一个必然失败的锁，用户还得自己猜「为什么扫了却没反应」。
 */
async function applyScanPreselect(): Promise<void> {
  const raw = route.query.deviceId
  if (typeof raw !== 'string' || !raw) {
    return
  }
  const deviceId = Number(raw)
  if (!Number.isFinite(deviceId)) {
    return
  }
  if (!deviceOptions.value.some((device) => device.id === deviceId)) {
    ElMessage.warning('该设备当前不可申请，请重新选择')
    return
  }
  selectType('BORROW')
  // 等第二步的表单渲染出来再选中：el-select 的选项依赖已加载的 deviceOptions，
  // 而设备选择器在那个分支里，未渲染时直接赋值不会触发选中态刷新。
  await nextTick()
  await handleDeviceChange(deviceId)
}

/** 选择设备：释放旧锁 → 加新锁 */
async function handleDeviceChange(nextId: number | null): Promise<void> {
  if (lockInfo.value && lockInfo.value.deviceId === nextId) {
    return
  }
  await releaseLock()
  form.deviceId = nextId
  // 设备决定 borrow.deviceCategoryId，会影响条件分支走向 → 锁变更后刷新路径预览
  void refreshBorrowPreview()
  if (nextId == null) {
    return
  }
  try {
    const locked = await deviceApi.lock(nextId)
    lockInfo.value = locked
    startLockTimer()
    ElMessage.success(`设备已临时锁定，请在 ${locked.timeoutMinutes} 分钟内提交申请`)
  } catch {
    // DEVICE_UNAVAILABLE：已被他人锁定；错误提示由请求层统一处理
    form.deviceId = null
    lockInfo.value = null
    await loadDevices()
    void refreshBorrowPreview()
  }
}

function startLockTimer(): void {
  stopLockTimer()
  updateRemaining()
  lockTimer = setInterval(updateRemaining, 1000)
}

function updateRemaining(): void {
  if (!lockInfo.value) {
    lockRemaining.value = 0
    return
  }
  lockRemaining.value = remainingLockSeconds(lockInfo.value.expiresAt)
  if (lockRemaining.value <= 0) {
    stopLockTimer()
    lockInfo.value = null
    form.deviceId = null
    ElMessage.warning('设备临时锁已超时，请重新选择设备')
    void loadDevices()
  }
}

function stopLockTimer(): void {
  if (lockTimer !== null) {
    clearInterval(lockTimer)
    lockTimer = null
  }
}

async function releaseLock(): Promise<void> {
  const current = lockInfo.value
  lockInfo.value = null
  lockRemaining.value = 0
  if (!current) {
    return
  }
  try {
    await deviceApi.unlock(current.deviceId, current.lockToken)
  } catch {
    // 幂等：锁可能已被提交或超时释放，静默忽略
  }
}

// ------------------------------------------------------------------
// 借用审批路径预览（ / M1）
//
// 接入自定义流程后，借用单的审批路径不再固定：
// 分组若绑定了流程模板，本单会按流程流转（条件分支会改变走向），
// 因此提交前必须让员工看到「这笔单会经过哪些节点」。
//
// 关键取舍：**预览失败绝不阻断提交**。
// 它是体验增强，不是准入 —— 真正的闸门是提交时服务端的流程解析。
// 预览接口挂了却让员工提交不了单，是把锦上添花做成了拦路石。
// ------------------------------------------------------------------

const borrowPreview = ref<BorrowFlowPreview | null>(null)
const borrowPreviewLoading = ref(false)
/** 预览请求序号：每次重新拉取前自增，用于丢弃过期响应（与分组详情页同法） */
let borrowPreviewSeq = 0

/** 命中路径上的节点（后端保证已按 stepOrder 升序） */
const borrowPathNodes = computed(() => borrowPreview.value?.nodes ?? [])
/** 本单不会经过的节点（条件分支未命中） */
const borrowSkippedNodes = computed(() => borrowPreview.value?.skippedNodes ?? [])

/**
 * 借用路径预览参数指纹。
 *
 * 只在「会影响路径求值的输入」变化时才重新请求：
 * 还的日期（决定 borrow.expectedDays）、设备（决定设备分类）、用途（文本条件可能用到）。
 * 用指纹而非 watch 多个 ref，是为了避免「同一次用户操作触发多次请求」。
 */
function previewSignature(): string {
  if (applyType.value !== 'BORROW') {
    return ''
  }
  return [form.expectedReturnDate ?? '', form.deviceId ?? '', form.reason.trim()].join('|')
}

async function refreshBorrowPreview(): Promise<void> {
  const signature = previewSignature()
  if (!signature) {
    borrowPreview.value = null
    return
  }
  const seq = ++borrowPreviewSeq
  borrowPreviewLoading.value = true
  try {
    const preview = await orderApi.borrowFlowPreview({
      useType: DEFAULT_USE_TYPE,
      expectedReturnDate: form.expectedReturnDate,
      deviceId: form.deviceId
    })
    if (seq !== borrowPreviewSeq) {
      return
    }
    borrowPreview.value = preview
  } catch {
    // 预览失败静默降级：不显示路径块，但员工仍可正常提交
    if (seq === borrowPreviewSeq) {
      borrowPreview.value = null
    }
  } finally {
    if (seq === borrowPreviewSeq) {
      borrowPreviewLoading.value = false
    }
  }
}

/** 节点类型标签（APPROVAL 审批 / CC 抄送） */
const previewNodeTypeLabel = borrowNodeTypeLabel
const previewNodeTypeTag = borrowNodeTypeTagType

/** 是否展示路径块：绑定流程且解析出节点（或存在被跳过的节点） */
const showBorrowPath = computed(() => shouldShowBorrowPath(borrowPreview.value))

function disabledReturnDate(date: Date): boolean {
  return date.getTime() < new Date(`${todayString()}T00:00:00`).getTime()
}

async function handleSubmit(): Promise<void> {
  const errors = validateOrderForm(form)
  if (errors.length > 0) {
    ElMessage.warning(errors[0])
    return
  }
  if (!lockInfo.value) {
    ElMessage.warning('设备临时锁已失效，请重新选择设备')
    return
  }
  submitting.value = true
  try {
    const orderId = await orderApi.create({
      deviceId: form.deviceId as number,
      lockToken: lockInfo.value.lockToken,
      // 借用类型不再由用户选择：一律短期借用，显式回传便于日志与回归断言
      useType: DEFAULT_USE_TYPE,
      // 用途（原「借用原因」）选填：空白一律提交 null，避免落库一串空格
      reason: form.reason.trim() ? form.reason.trim() : null,
      expectedReturnDate: form.expectedReturnDate
    })
    // 申请附件：随单提交；附件可选，失败不阻断申请
    const attached =
      applyFiles.value.length > 0 && orderId != null
        ? await uploadAttachments('APPLY_ATTACHMENT', orderId, applyFiles.value)
        : 0
    ElMessage.success(
      attached > 0
        ? `申请已提交，并上传 ${attached} 个附件`
        : '申请已提交，可在「我的工单」查看审批进度'
    )
    resetFormAfterSubmit()
    await loadDevices()
  } catch {
    // 提交失败（锁失效 / 设备被抢占 / 无部门等）由请求层给出明确提示；
    // 设备可能已不可用，刷新可选设备列表
    void loadDevices()
  } finally {
    submitting.value = false
  }
}

function resetFormAfterSubmit(): void {
  stopLockTimer()
  lockInfo.value = null
  lockRemaining.value = 0
  form.deviceId = null
  form.reason = ''
  form.expectedReturnDate = null
  applyFiles.value = []
  // 已成功提交，草稿使命完成，清除避免下次进入又恢复成旧内容
  clearDraft()
  // 表单已清空，路径预览随之失效（下一次选择设备时会重新拉取）
  borrowPreview.value = null
  borrowPreviewSeq += 1
}

function handleReset(): void {
  void releaseLock()
  resetFormAfterSubmit()
}

// ------------------------------------------------------------------
// 故障报修表单（，第二条上报路径：台账 / 工单内统一入口）
// ------------------------------------------------------------------

const faultLoadingDevices = ref(false)
const faultSubmitting = ref(false)
const faultDeviceOptions = ref<DeviceFaultSelectable[]>([])

/** 随单提交的故障照片（本地待上传，，仅图片） */
const faultFiles = ref<File[]>([])

const faultForm = reactive({
  deviceId: null as number | null,
  faultDescription: '',
  occurredAt: ''
})

/** 选中设备项，用于回带关联工单 */
const selectedFaultDevice = computed(
  () => faultDeviceOptions.value.find((device) => device.deviceId === faultForm.deviceId) ?? null
)
/** 关联工单只读展示：选中设备若带 orderNo 则显示，否则「无（台账直接登记）」 */
const faultOrderText = computed(() =>
  selectedFaultDevice.value?.orderNo ? selectedFaultDevice.value.orderNo : '无（台账直接登记）'
)

async function loadFaultDevices(): Promise<void> {
  faultLoadingDevices.value = true
  try {
    faultDeviceOptions.value = await deviceFaultApi.selectableDevices()
  } catch {
    faultDeviceOptions.value = []
  } finally {
    faultLoadingDevices.value = false
  }
}

/** 故障发生时间不得晚于当前时间 */
function disabledFaultOccurredDate(date: Date): boolean {
  return date.getTime() > Date.now()
}

async function submitFault(): Promise<void> {
  if (faultForm.deviceId == null) {
    ElMessage.warning('请选择故障设备')
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
    const faultId = await deviceFaultApi.report({
      deviceId: faultForm.deviceId,
      // 选中设备若在使用中工单内（带 orderId）则带上；可用设备（台账登记）为 null
      orderId: selectedFaultDevice.value?.orderId ?? null,
      faultDescription: faultForm.faultDescription.trim(),
      occurredAt: faultForm.occurredAt
    })
    // 故障照片：绑定故障记录 id，随登记一并上传；照片可选
    const attached =
      faultFiles.value.length > 0 && faultId != null
        ? await uploadAttachments('FAULT_PHOTO', faultId, faultFiles.value)
        : 0
    ElMessage.success(
      attached > 0 ? `故障已登记，并上传 ${attached} 张照片` : '故障已登记'
    )
    // 登记成功后返回步骤一并重置故障表单，便于连续登记或切换类型
    faultForm.deviceId = null
    faultForm.faultDescription = ''
    faultForm.occurredAt = ''
    faultFiles.value = []
    faultDeviceOptions.value = []
    // 已成功登记，清除草稿
    clearDraft()
    backToSelect()
  } catch {
    // 设备状态已变化等冲突由请求层统一提示
  } finally {
    faultSubmitting.value = false
  }
}

// 表单与步骤变化即安排落盘（watch 仅用于持久化，不参与业务级联清理）。
// 必须放在 form / faultForm 声明之后：watch 在注册时即读取这两个响应式对象。
watch([step, applyType, form, faultForm], scheduleSaveDraft, { deep: true })

/**
 * 用途变化 → 防抖刷新路径预览（M1）。
 *
 * 用途可能被配成文本条件（如「仅出差需要」），但它是自由输入，逐字请求会打爆接口。
 * 防抖 600ms 后一次性请求；同时不配置 `immediate` —— 初始值由 onMounted / selectType 负责。
 */
let previewTimer: ReturnType<typeof setTimeout> | null = null
watch(
  () => form.reason,
  () => {
    if (previewTimer !== null) {
      clearTimeout(previewTimer)
    }
    previewTimer = setTimeout(() => {
      previewTimer = null
      void refreshBorrowPreview()
    }, 600)
  }
)

onBeforeUnmount(() => {
  if (previewTimer !== null) {
    clearTimeout(previewTimer)
    previewTimer = null
  }
})
</script>

<template>
  <div class="ts-page">
    <section class="ts-card">
      <!-- 步骤一：卡片式选择申请类型 -->
      <template v-if="step === 'select'">
        <h3 class="ts-order__title">提交申请</h3>
        <p class="ts-text-secondary ts-order__desc">请选择本次申请的类型，再填写对应表单。</p>
        <!-- ：卡片**平铺**。
             改造前自定义申请被一个「自定义申请」分组标题单独隔开，形成「内置 / 自定义」两段式布局；
             实际使用中两者都是「提交一张单」的入口，分段只增加了扫视成本。
             现合并为同一网格，顺序约定：**内置类型在前、自定义表单类在后** ——
             内置两项是高频操作；自定义类型数量随管理员配置增长，排在后面才不会把常用入口挤下去。
             自定义卡片的审批模式标签（分组审批 / 自定义流程 / 无需审批）保留，
             它回答的是「这张单交上去之后谁来批」，与排列位置无关。
             ：内置借用卡片由「短期借用 + 长期领用」两张合并为「借用设备」一张 ——
             借用类型不再由员工选择，两张卡指向同一个表单形态。 -->
        <div class="ts-apply__types">
          <div
            v-for="item in APPLY_TYPES"
            :key="item.value"
            class="ts-apply__type"
            role="button"
            tabindex="0"
            @click="selectType(item.value)"
            @keyup.enter="selectType(item.value)"
          >
            <h4 class="ts-apply__type-title">{{ item.title }}</h4>
            <p class="ts-apply__type-desc">{{ item.desc }}</p>
          </div>

          <!-- 自定义申请（由「申请类型管理」配置，按提交权限过滤后下发） -->
          <div
            v-for="item in customTypes"
            :key="item.id"
            class="ts-apply__type ts-apply__type--custom"
            role="button"
            tabindex="0"
            @click="selectCustomType(item)"
            @keyup.enter="selectCustomType(item)"
          >
            <h4 class="ts-apply__type-title">
              {{ item.typeName }}
              <el-tag
                size="small"
                effect="plain"
                :type="item.approvalMode === 'GROUP' ? 'warning' : 'info'"
              >
                {{ item.approvalModeLabel ?? (item.approvalMode === 'GROUP' ? '分组审批' : '无需审批') }}
              </el-tag>
            </h4>
            <p class="ts-apply__type-desc">{{ item.description || '自定义申请表单' }}</p>
          </div>
        </div>
      </template>

      <!-- 步骤二：进入对应表单 -->
      <template v-else>
        <div class="ts-apply__back" role="button" tabindex="0" @click="backToSelect" @keyup.enter="backToSelect">
          ← 返回重选类型
        </div>

        <!-- 借用表单（ 起为三项 + 可选附件；沿用现有锁逻辑） -->
        <template v-if="applyType === 'BORROW'">
          <h3 class="ts-order__title">申请借用设备</h3>
          <p class="ts-text-secondary ts-order__desc">
            选定设备后系统会为该设备加临时锁（默认 5 分钟，），请在有效期内提交；
            提交后设备进入审批中，审批通过由最终处理人交付。
          </p>

          <el-form class="ts-order__form ts-mt-16" label-width="104px" :label-position="isMobile ? 'top' : 'right'">
            <el-form-item label="借什么设备" required>
              <el-select
                :model-value="form.deviceId"
                class="ts-order__field"
                placeholder="请选择需要借用的设备"
                filterable
                clearable
                :loading="loadingDevices"
                @change="handleDeviceChange"
              >
                <el-option
                  v-for="device in deviceOptions"
                  :key="device.id"
                  :label="deviceLabel(device)"
                  :value="device.id"
                />
              </el-select>
              <div v-if="lockInfo" class="ts-order__lock">
                临时锁剩余 <strong>{{ lockText }}</strong>，超时后需重新选择设备
              </div>
              <div v-else class="ts-text-hint">选中设备即完成临时锁定；超时或更换设备会自动释放</div>
            </el-form-item>

            <el-form-item label="还的日期" required>
              <el-date-picker
                v-model="form.expectedReturnDate"
                class="ts-order__field"
                type="date"
                value-format="YYYY-MM-DD"
                placeholder="请选择归还日期"
                :disabled-date="disabledReturnDate"
              />
              <div class="ts-text-hint">
                借用日即今天；到期未归还会自动顺延（最多 2 次），2 次后转为超时告警
              </div>
            </el-form-item>

            <el-form-item label="用途">
              <el-input
                v-model="form.reason"
                class="ts-order__field"
                maxlength="500"
                show-word-limit
                placeholder="选填，一句话即可，例如：出差用"
              />
            </el-form-item>

            <!-- ：申请附件（，随单提交，可选；需求方决策 3 保留） -->
            <el-form-item label="申请附件">
              <PendingAttachmentPicker v-model="applyFiles" biz-type="APPLY_ATTACHMENT" label="随申请提交的材料" />
            </el-form-item>
          </el-form>

          <!--  / M1：审批路径预览（绑定流程时展示动态路径，否则不渲染） -->
          <div v-if="showBorrowPath && borrowPreview" v-loading="borrowPreviewLoading" class="ts-path ts-mt-16">
            <div class="ts-path__head">
              <strong class="ts-path__title">审批路径预览</strong>
              <el-tag v-if="borrowPreview.flowVersionLabel" size="small" effect="plain" type="primary">
                {{ borrowPreview.flowVersionLabel }}
              </el-tag>
              <span class="ts-text-hint">按当前填写内容预估，最终以提交时为准</span>
            </div>

            <div v-if="borrowPathNodes.length > 0" class="ts-path__chain">
              <template v-for="(node, index) in borrowPathNodes" :key="`${node.nodeKey}-${index}`">
                <div class="ts-path__node">
                  <el-tag size="small" effect="dark" :type="previewNodeTypeTag(node.nodeType)">
                    {{ previewNodeTypeLabel(node.nodeType) }}
                  </el-tag>
                  <span class="ts-path__node-name">{{ node.nodeName }}</span>
                  <span v-if="node.approverNames.length > 0" class="ts-path__node-approvers">
                    {{ node.approverNames.join('、') }}
                  </span>
                  <span v-else-if="node.nodeType === 'CC'" class="ts-path__node-approvers is-muted">
                    提交时确定
                  </span>
                  <span v-else class="ts-path__node-approvers is-muted">超级管理员兜底</span>
                  <span v-if="node.timeLimitHours != null" class="ts-path__node-limit">
                    限 {{ node.timeLimitHours }} 小时
                  </span>
                  <div v-if="node.conditionDesc" class="ts-path__node-cond">{{ node.conditionDesc }}</div>
                </div>
                <span v-if="index < borrowPathNodes.length - 1" class="ts-path__arrow">→</span>
              </template>
            </div>
            <el-empty v-else :image-size="48" description="按当前内容无需审批，提交后直接进入派工" />

            <div v-if="borrowSkippedNodes.length > 0" class="ts-path__skipped">
              <span class="ts-text-hint">
                本单不会经过：{{ borrowSkippedNodes.map((node) => node.nodeName).join('、') }}
              </span>
            </div>
          </div>

          <div class="ts-order__submit">
            <el-button @click="handleReset">重置</el-button>
            <el-button type="primary" :loading="submitting" :disabled="!canSubmit" @click="handleSubmit">
              提交申请
            </el-button>
          </div>
          <p v-if="formErrors.length > 0" class="ts-text-hint">提交前请完善：{{ formErrors[0] }}</p>
        </template>

        <!-- 故障报修表单 -->
        <template v-else-if="applyType === 'FAULT_REPORT'">
          <h3 class="ts-order__title">故障报修</h3>
          <p class="ts-text-secondary ts-order__desc">
            登记设备故障时填写故障描述与发生时间；工单内设备会自动关联工单，可用设备则为台账直接登记。
          </p>

          <el-form class="ts-order__form ts-mt-16" label-width="104px" :label-position="isMobile ? 'top' : 'right'">
            <el-form-item label="故障设备" required>
              <el-select
                v-model="faultForm.deviceId"
                class="ts-order__field"
                placeholder="请选择故障设备"
                filterable
                clearable
                :loading="faultLoadingDevices"
              >
                <el-option
                  v-for="device in faultDeviceOptions"
                  :key="device.deviceId"
                  :label="device.label"
                  :value="device.deviceId"
                />
              </el-select>
            </el-form-item>

            <el-form-item label="关联工单">
              <span class="ts-text-hint">{{ faultOrderText }}</span>
            </el-form-item>

            <el-form-item label="故障发生时间" required>
              <el-date-picker
                v-model="faultForm.occurredAt"
                class="ts-order__field"
                type="datetime"
                placeholder="选择故障发生时间"
                format="YYYY-MM-DD HH:mm:ss"
                value-format="YYYY-MM-DD HH:mm:ss"
                :disabled-date="disabledFaultOccurredDate"
              />
              <div class="ts-text-hint">发生时间不得晚于当前时间</div>
            </el-form-item>

            <el-form-item label="故障描述" required>
              <el-input
                v-model="faultForm.faultDescription"
                class="ts-order__field"
                type="textarea"
                :rows="3"
                maxlength="500"
                show-word-limit
                placeholder="请描述故障现象（如：屏幕出现竖线、无法开机）"
              />
            </el-form-item>

            <!-- ：故障照片（，随登记上传，仅图片） -->
            <el-form-item label="故障照片">
              <PendingAttachmentPicker v-model="faultFiles" biz-type="FAULT_PHOTO" label="现场照片" />
            </el-form-item>
          </el-form>

          <div class="ts-order__submit">
            <el-button @click="backToSelect">返回</el-button>
            <el-button type="danger" :loading="faultSubmitting" @click="submitFault">提交故障登记</el-button>
          </div>
        </template>
      </template>
    </section>
  </div>
</template>

<style scoped>
.ts-order__title {
  margin: 0;
  font-size: 16px;
  font-weight: 500;
}

.ts-order__desc {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.6;
}

.ts-order__form {
  max-width: 720px;
}

.ts-order__field {
  width: 100%;
}

.ts-order__lock {
  margin-top: 4px;
  font-size: 12px;
  color: #e6a23c;
}

.ts-order__submit {
  display: flex;
  gap: 12px;
  justify-content: flex-end;
  max-width: 720px;
  margin-top: 8px;
}

/* 步骤一：类型选择卡片。
   列宽用 auto-fill + minmax 而不是写死 3 列 —— 内置卡片只有 2 张（ 合并后），
   写死 3 列会空出半行；自定义申请类型的数量由管理员配置，无法预知。
   auto-fill 让「有几张排几张、放不下自动换行」，两种情况都不会出现空洞或挤压。 */
.ts-apply__types {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 16px;
  margin-top: 16px;
}

.ts-apply__type {
  padding: 20px 16px;
  border: 1px solid var(--ts-border);
  border-radius: 10px;
  background: #fbfcfe;
  cursor: pointer;
  transition: border-color 0.15s, box-shadow 0.15s, transform 0.15s;
}

.ts-apply__type:hover,
.ts-apply__type:focus {
  border-color: var(--el-color-primary);
  box-shadow: 0 2px 12px rgba(64, 158, 255, 0.15);
  outline: none;
}

.ts-apply__type:active {
  transform: translateY(1px);
}

.ts-apply__type-title {
  margin: 0;
  font-size: 15px;
  font-weight: 500;
}

.ts-apply__type-desc {
  margin: 8px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}

/* 自定义申请卡片（ 起与内置卡片同格平铺）：
   「自定义申请」分组标题与其独立外边距已随平铺一并移除（`.ts-apply__group-title` /
   `.ts-apply__types--custom` 两条死规则已删除）。
   这里只保留自定义卡片标题的 flex 布局 —— 它要在类型名右侧并排放审批模式标签。 */
.ts-apply__type--custom .ts-apply__type-title {
  display: flex;
  gap: 8px;
  align-items: center;
}

/* 步骤二：返回重选类型 */
.ts-apply__back {
  display: inline-block;
  margin-bottom: 4px;
  font-size: 13px;
  color: var(--el-color-primary);
  cursor: pointer;
  user-select: none;
}

.ts-apply__back:hover,
.ts-apply__back:focus {
  opacity: 0.8;
  outline: none;
}

/* 审批路径预览（M1）：横向链路 + 节点卡片 */
.ts-path {
  max-width: 720px;
  padding: 12px 14px;
  border: 1px solid var(--ts-border);
  border-radius: 10px;
  background: #fbfcfe;
}

.ts-path__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.ts-path__title {
  font-size: 14px;
}

.ts-path__chain {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: 8px;
}

.ts-path__node {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 132px;
  padding: 8px 10px;
  border: 1px solid var(--ts-border);
  border-radius: 8px;
  background: #fff;
}

.ts-path__node-name {
  font-size: 13px;
  font-weight: 500;
  word-break: break-all;
}

.ts-path__node-approvers {
  font-size: 12px;
  color: var(--el-text-color-regular);
  word-break: break-all;
}

.ts-path__node-approvers.is-muted {
  color: var(--ts-text-hint);
}

.ts-path__node-limit {
  font-size: 12px;
  color: #e6a23c;
}

.ts-path__node-cond {
  font-size: 12px;
  line-height: 1.5;
  color: var(--ts-text-secondary);
}

.ts-path__arrow {
  align-self: center;
  color: var(--ts-text-hint);
}

.ts-path__skipped {
  margin-top: 10px;
}

@media (max-width: 767px) {
  .ts-order__submit .el-button {
    flex: 1 1 0;
  }

  .ts-apply__types {
    grid-template-columns: 1fr;
  }
}
</style>
