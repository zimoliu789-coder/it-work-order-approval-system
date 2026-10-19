<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { deviceCategoryApi, deviceFaultApi, deviceModelApi } from '@/api/device'
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
import { DEFAULT_USE_TYPE, type DivisionPrefill } from '@/types/order'
import type { DeviceCategory, DeviceModelOption, FaultDeviceLookup } from '@/types/device'
import { todayString, validateOrderForm, type OrderFormState } from '@/utils/orderForm'

/**
 * 提交申请（两种内置工单类型 + 自定义申请 + 故障报修）
 *
 * 两步结构：
 * 1. 步骤一：卡片式选择申请类型，由 APPLY_TYPES 数组驱动（新增类型 = 加一项）；
 * 2. 步骤二：进入对应表单，顶部「← 返回重选类型」回到步骤一。
 *
 * <h2>V56（第 9 项）：借用表单从「挑一台设备」改为「挑一个类别」</h2>
 * <p>需求方拍定：<b>借用单只声明「一级分类 + 二级分类 + 型号」，不绑定具体资产</b> ——
 * 具体哪一台由 IT 在交付时扫码绑定。连带产生三处结构性变化：
 * <ul>
 *   <li><b>临时锁整段删除</b>：{@code lockInfo} / 倒计时 / {@code deviceApi.lock} /
 *       「设备临时锁已失效」判据全部移除。后端连 {@code locked_by/locked_at/lock_token}
 *       三列与 {@code DeviceLockReleaseJob} 一起删了，前端留一套算不出东西的倒计时只会误导人；</li>
 *   <li><b>设备下拉换成三级联动</b>：一级分类（必填）→ 二级分类（选填）→ 型号（选填）；</li>
 *   <li><b>扫码预选从 deviceId 改为分类</b>：扫到可用设备时只能带「这类设备」进来，
 *       带不了「这一台」—— 传 deviceId 后端已不接收，等于按钮点了没反应。</li>
 * </ul>
 *
 * <p>审批路径预览（M1）仍在，但入参同步换成 {@code primaryCategoryId}：
 * 预览与提交必须用同一口径算条件分支，否则会画出与实际不一致的路径。
 * 预览失败依旧不阻断提交（它是提示，不是闸门）。
 *
 * <p>故障报修表单（V57 / 第 10 项）：两种填报方式，二选一 ——
 * <ul>
 *   <li><b>我名下的设备</b>：服务端返回「我负责的 ∪ 我正在用的」，选中项已带
 *       {@code reportable / reason / orderId}，前端不自己判定能不能报；</li>
 *   <li><b>公共设备 / 扫码</b>：按资产编号查（扫码枪直接扫进输入框），
 *       查不到返回 `found=false` 而不是报错 —— 输错编号是这个入口的日常动作。</li>
 * </ul>
 * 两种模式共用同一套「可报修」判定（后端 {@code toLookup} 是唯一口径），
 * 因此不会出现「下拉里能选、提交却被拒」。附件（申请附件 / 故障照片）为可选，
 * 上传失败不阻断主流程。
 */

const { isMobile } = useResponsive()
const route = useRoute()
const router = useRouter()

/**
 * 申请类型（**入口形态**，不是借用类型）。
 *
 * <p>内置只有两项：借用设备 / 故障报修。原来的 `SHORT_TERM` / `LONG_TERM`
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
  { value: 'BORROW', title: '借用设备', desc: '选设备类别与型号、填归还日期即可提交' },
  { value: 'FAULT_REPORT', title: '故障报修', desc: '设备报修，登记故障记录' }
]

const step = ref<'select' | 'form'>('select')
const applyType = ref<ApplyType | null>(null)

// ------------------------------------------------------------------
// 自定义申请类型
//
// 与两种内置类型并列但<b>数据来源不同</b>：内置类型写死在 APPLY_TYPES 数组里，
// 自定义类型由管理员在「申请类型管理」中配置，且会按当前用户的提交权限过滤 ——
// 因此必须由服务端下发（前端过滤等于没过滤，直调接口即可绕过）。
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

/** 选中申请类型：借用类拉一次路径预览；故障类按当前填报方式拉设备 */
function selectType(type: ApplyType): void {
  applyType.value = type
  step.value = 'form'
  if (type === 'FAULT_REPORT') {
    // 只加载当前模式需要的数据：另一个模式在切换时按需加载（省一次无用请求）
    if (faultMode.value === 'MINE') {
      void loadFaultOwnedDevices()
    }
  } else {
    // 进入借用表单即拉一次路径预览（此时还没选类别，仍能展示不依赖类别的节点）
    void refreshBorrowPreview()
  }
}

/** 返回重选类型 */
function backToSelect(): void {
  applyType.value = null
  step.value = 'select'
}

// ------------------------------------------------------------------
// 当前所在分部（V54 / D5）
//
// 「这张单发生在哪儿」决定由哪个处理小组接单，与「人事主部门」（决定审批上级）
// 是两件事 —— 出差在外的人两者天然不同。
//
// 分工约定（刻意如此）：
// - 服务端负责**预填**（出差中 ⇒ 出差目标分部，否则 ⇒ 人事主部门所属分部）
//   和**来源判定**（拿回传值跟此刻的预填值比对）；
// - 前端只做两件事：把预填值显示出来、把员工改过的值原样回传。
// 前端**不传来源** —— 可被篡改的审计字段等于没有审计。
// ------------------------------------------------------------------

const divisionPrefill = ref<DivisionPrefill | null>(null)
const divisionLoading = ref(false)
/** 员工当前选中的分部；null = 没取到预填（提交时由服务端兜底） */
const divisionId = ref<number | null>(null)
/**
 * 员工是否动过这个下拉。
 *
 * 用途只有一个：重新拉预填时**不要覆盖**用户的手动选择 ——
 * 否则一次刷新就把「我明明改成了上海」悄悄改回北京，而提交后才发现派错了组。
 */
const divisionTouched = ref(false)

/** 可选分部（服务端只给「启用中的一级分部」，前端不得自行过滤） */
const divisionOptions = computed(() => divisionPrefill.value?.options ?? [])

/** 下拉下方的来源提示：告诉员工「这个值哪来的、什么时候该改」 */
const divisionHint = computed(() => {
  const prefill = divisionPrefill.value
  if (!prefill) {
    return '无法获取分部信息，提交时按你的人事主部门处理'
  }
  if (divisionOptions.value.length === 0) {
    return '组织里还没有配置一级分部，请联系管理员'
  }
  if (divisionId.value == null) {
    return '未取到你的所属分部，请联系管理员确认部门配置'
  }
  if (divisionTouched.value && divisionId.value !== prefill.divisionId) {
    return '已手动修改：审批与派工会按你选择的分部匹配处理小组'
  }
  if (prefill.source === 'TRIP_PREFILL') {
    return prefill.tripOrderNo
      ? `出差自动带入（来自出差单 ${prefill.tripOrderNo}）`
      : '出差自动带入'
  }
  if (prefill.source === 'HR_PRIMARY') {
    return '按人事主部门自动带入；出差在外请改为目的地分部'
  }
  return ''
})

async function loadDivisionPrefill(): Promise<void> {
  divisionLoading.value = true
  try {
    const prefill = await orderApi.divisionPrefill()
    divisionPrefill.value = prefill
    if (!divisionTouched.value) {
      divisionId.value = prefill.divisionId
    }
  } catch {
    // 预填是体验增强：拉不到不影响提交（服务端会用同一套规则兜底），
    // 只是员工看不到「默认值哪来的」这条提示
    divisionPrefill.value = null
  } finally {
    divisionLoading.value = false
  }
}

/** 员工手动改分部（只记「动过」，落库来源仍由服务端判定） */
function handleDivisionChange(): void {
  divisionTouched.value = true
}

// ------------------------------------------------------------------
// 本地草稿缓存（需求方三波·第二波：「前端增加本地草稿缓存，浏览器刷新也保留草稿」）
//
// 只缓存「用户填写的文本类字段」：类别 / 型号 id 不缓存 —— 分类与型号字典
// 都可能被管理员停用或改造，恢复出一个已失效的 id 会让用户以为已经填好，
// 提交时又报「所选分类不存在」，体验反而更差。附件（File 对象）无法序列化，同样不缓存。
// 草稿带 7 天有效期，过期自动丢弃。
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
 * 收口老草稿里的申请类型。
 *
 * <p>早期草稿里存的是 `SHORT_TERM` / `LONG_TERM`（两张借用卡）。若原样恢复，
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

/** 恢复草稿（类别需重新选择） */
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
  if (step.value === 'form' && restoredType === 'FAULT_REPORT' && faultMode.value === 'MINE') {
    void loadFaultOwnedDevices()
  }
  ElMessage.info('已恢复上次未提交的草稿（设备类别需重新选择）')
}

// ------------------------------------------------------------------
// 借用表单（V56：借什么类别 / 还的日期 / 用途）
// ------------------------------------------------------------------

const submitting = ref(false)

/** 随单提交的申请附件（本地待上传） */
const applyFiles = ref<File[]>([])

const form = reactive<OrderFormState>({
  primaryCategoryId: null,
  secondaryCategoryId: null,
  modelId: null,
  reason: '',
  expectedReturnDate: null
})

const formErrors = computed(() => validateOrderForm(form))
const canSubmit = computed(() => formErrors.value.length === 0 && !submitting.value)

// ------------------------------------------------------------------
// 设备类别 / 型号字典（V56）
//
// 三个下拉的数据来源刻意不同，别合并：
// - 一级分类：`/device-categories` 分类树（只取根节点）；
// - 二级分类：从已加载的树里按所选一级过滤（**不再请求接口** —— 树是一次性全量下发的，
//   再请求一遍只会出现「两次响应不一致」这类难查问题）；
// - 型号：`/device-models/options`（仅启用型号）。字典**没有分类维度**
//   （`device_model` 只有 model_name / brand），所以它不随分类过滤 ——
//   选错型号的风险由交付时的三层校验（分类 → 型号 → 空闲）兜住。
// ------------------------------------------------------------------

const categoryTree = ref<DeviceCategory[]>([])
const categoryLoading = ref(false)
const modelOptions = ref<DeviceModelOption[]>([])
const modelLoading = ref(false)

/** 只保留启用的分类由服务端保证（树接口不下发停用项），前端不再二次过滤 */
const primaryOptions = computed(() => categoryTree.value)

/** 当前所选一级分类下的二级分类 */
const secondaryOptions = computed(() => {
  if (form.primaryCategoryId == null) {
    return []
  }
  return categoryTree.value.find((item) => item.id === form.primaryCategoryId)?.children ?? []
})

const secondaryPlaceholder = computed(() => {
  if (form.primaryCategoryId == null) {
    return '请先选择一级分类'
  }
  return secondaryOptions.value.length === 0
    ? '该类别下没有二级分类，可跳过'
    : '选填，不选表示该类别下任意子类'
})

async function loadCategories(): Promise<void> {
  categoryLoading.value = true
  try {
    categoryTree.value = await deviceCategoryApi.tree()
  } catch {
    categoryTree.value = []
  } finally {
    categoryLoading.value = false
  }
}

async function loadModels(): Promise<void> {
  modelLoading.value = true
  try {
    modelOptions.value = await deviceModelApi.options()
  } catch {
    modelOptions.value = []
  } finally {
    modelLoading.value = false
  }
}

/**
 * 一级分类变化：清空二级与型号。
 *
 * <p>必须清 —— 旧二级可能不属于新一级（服务端会拒绝，但用户看到的是一句
 * 提交后的报错，而不是「我换了类别，下面的选项跟着变了」）。
 * 型号一并清空：它是跨类别的全局字典，留着会让人以为「型号已经选好了」。
 */
function handlePrimaryChange(): void {
  form.secondaryCategoryId = null
  form.modelId = null
  void refreshBorrowPreview()
}

function handleSecondaryChange(): void {
  void refreshBorrowPreview()
}

// ------------------------------------------------------------------
// 借用审批路径预览（M1）
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
 * 还的日期（决定 borrow.expectedDays）、类别（决定 borrow.deviceCategoryId）、
 * 用途（文本条件可能用到）。
 * 用指纹而非 watch 多个 ref，是为了避免「同一次用户操作触发多次请求」。
 *
 * <p>V56：指纹里的 deviceId 换成 primaryCategoryId —— 预览与提交的入参必须同一口径，
 * 否则「预览画三级、提交走四级」这类漂移会重新出现。
 */
function previewSignature(): string {
  if (applyType.value !== 'BORROW') {
    return ''
  }
  return [form.expectedReturnDate ?? '', form.primaryCategoryId ?? '', form.reason.trim()].join('|')
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
      primaryCategoryId: form.primaryCategoryId
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

// ------------------------------------------------------------------
// 「锁」相关代码已整体删除（V56）
//
// 原来这里有一整套：lockInfo / lockRemaining / lockTimer / startLockTimer /
// updateRemaining / stopLockTimer / releaseLock，以及「没锁就不能提交」的判据
// （canSubmit 曾要求 lockInfo != null）。删掉的原因不是前端想简化，而是
// 这套东西已经没有对端了：后端 `DeviceService#lock/unlock/forceUnlock/
// releaseExpiredLocks`、`DeviceLockReleaseJob`、`device.locked_*` 三列、
// 以及 `POST /api/devices/{id}/lock` 三个端点全部移除，调用只会 404。
// 顺带的好处是：员工提交时不再有「5 分钟内必须填完」这种无谓倒计时压力。
// ------------------------------------------------------------------

async function handleSubmit(): Promise<void> {
  const errors = validateOrderForm(form)
  if (errors.length > 0) {
    ElMessage.warning(errors[0])
    return
  }
  submitting.value = true
  try {
    const orderId = await orderApi.create({
      // V56：只声明「借哪一类 + 什么型号」，具体资产由 IT 交付扫码绑定
      primaryCategoryId: form.primaryCategoryId as number,
      secondaryCategoryId: form.secondaryCategoryId ?? null,
      modelId: form.modelId ?? null,
      // 借用类型不再由用户选择：一律短期借用，显式回传便于日志与回归断言
      useType: DEFAULT_USE_TYPE,
      // 用途（原「借用原因」）选填：空白一律提交 null，避免落库一串空格
      reason: form.reason.trim() ? form.reason.trim() : null,
      expectedReturnDate: form.expectedReturnDate,
      // 当前所在分部（V54）：只在员工改过时传自己选的值；
      // 没改过就传预填值（等价于「接受默认」），来源一律交服务端比对判定
      currentDivisionId: divisionId.value
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
  } catch {
    // 提交失败（分类被停用 / 无部门等）由请求层给出明确提示；
    // 分类可能已变化，刷新一次分类树
    void loadCategories()
  } finally {
    submitting.value = false
  }
}

function resetFormAfterSubmit(): void {
  form.primaryCategoryId = null
  form.secondaryCategoryId = null
  form.modelId = null
  form.reason = ''
  form.expectedReturnDate = null
  applyFiles.value = []
  // 已成功提交，草稿使命完成，清除避免下次进入又恢复成旧内容
  clearDraft()
  // 表单已清空，路径预览随之失效（下一次选择类别时会重新拉取）
  borrowPreview.value = null
  borrowPreviewSeq += 1
  // 分部回到默认：清掉「动过」标记并重新预填 —— 预填本身取决于
  // 「今天是否在出差」，隔几个小时再填一单，默认值可能已经变了
  divisionTouched.value = false
  void loadDivisionPrefill()
}

function handleReset(): void {
  resetFormAfterSubmit()
}

/**
 * 扫码借还跳转带来的 ?primaryCategoryId=（P1 扫码借还 / V56 调整）。
 *
 * <h2>参数为什么变了</h2>
 * <p>V56 之前扫码页传的是 `?deviceId=`「就要这一台」。现在借用单不绑具体资产，
 * 只能带「扫到的这台属于哪一类」进来，所以参数改为 primaryCategoryId /
 * secondaryCategoryId / modelId 三个。
 *
 * <h2>为什么要校验二级分类归属</h2>
 * <p>URL 是可以手改的（也可能是上一版前端留下的旧链接）。若把不属于所传一级分类的
 * 二级 id 直接塞进表单，用户看到的是一个「选项里根本没有」的值 —— 要么静默错乱，
 * 要么提交后才报错。这里在可选项里找不到就丢弃它。
 */
async function applyScanPreselect(): Promise<void> {
  const rawPrimary = route.query.primaryCategoryId
  if (typeof rawPrimary !== 'string' || !rawPrimary) {
    return
  }
  const primaryCategoryId = Number(rawPrimary)
  if (!Number.isFinite(primaryCategoryId)) {
    return
  }
  if (!primaryOptions.value.some((item) => item.id === primaryCategoryId)) {
    ElMessage.warning('扫码带来的设备类别已不可用，请手动选择')
    return
  }
  selectType('BORROW')
  // 等第二步表单渲染出来再赋值：下拉的选项依赖已加载的分类树，
  // 未渲染时直接赋值不会触发选中态刷新。
  await nextTick()
  form.primaryCategoryId = primaryCategoryId

  const rawSecondary = route.query.secondaryCategoryId
  const secondaryCategoryId = typeof rawSecondary === 'string' ? Number(rawSecondary) : NaN
  form.secondaryCategoryId =
    Number.isFinite(secondaryCategoryId) &&
    secondaryOptions.value.some((item) => item.id === secondaryCategoryId)
      ? secondaryCategoryId
      : null

  const rawModel = route.query.modelId
  const modelId = typeof rawModel === 'string' ? Number(rawModel) : NaN
  form.modelId =
    Number.isFinite(modelId) && modelOptions.value.some((item) => item.id === modelId) ? modelId : null

  void refreshBorrowPreview()
}

// ------------------------------------------------------------------
// 故障报修表单（第二种填报模式并存的统一入口，V57 / 第 10 项）
//
// 两种模式看着不同，要回答的问题完全一样：这台设备是哪台、能不能报、
// 报修时绑定哪张工单。判定一律由服务端给（reportable / reason / orderId），
// 前端**不做二次判定** —— 两边各判一次，迟早出现「能选但提交被拒」。
// ------------------------------------------------------------------

/**
 * 填报方式。
 *
 * - MINE ：「我名下的设备」= 我负责的（owner_id=我）∪ 我正在用的（使用中借用单）
 *          —— 只查负责人会让普通员工看到一个空列表（资产负责人通常是 IT）；
 * - ASSET：公共设备，按资产编号查（扫码枪扫进输入框），对应「会议室打印机」
 *          这类没有负责人的设备。
 */
type FaultMode = 'MINE' | 'ASSET'

const faultMode = ref<FaultMode>('MINE')
const faultSubmitting = ref(false)

/** 模式一：我名下的设备 */
const faultOwnedDevices = ref<FaultDeviceLookup[]>([])
const faultOwnedLoading = ref(false)

/** 模式二：按资产编号查到的结果（null = 还没查 / 查询失败） */
const faultAssetNo = ref('')
const faultLookupResult = ref<FaultDeviceLookup | null>(null)
const faultLookupLoading = ref(false)

/** 随单提交的故障照片（本地待上传，仅图片） */
const faultFiles = ref<File[]>([])

const faultForm = reactive({
  deviceId: null as number | null,
  faultDescription: '',
  occurredAt: ''
})

const faultModeHint = computed(() =>
  faultMode.value === 'MINE'
    ? '我负责的资产 + 我正在借用的设备；使用中的设备会自动关联到你的借用单'
    : '扫资产二维码或输入编号：适用于会议室打印机这类没有负责人的设备，报修后设备进入维修中'
)

/**
 * 当前选中的设备（两种模式收口成同一个结构）。
 *
 * <p>模板、提交、原因提示全部读它，而不是各自去翻 `faultOwnedDevices` 或
 * `faultLookupResult` —— 否则「模式二查到了、提交时却还在读模式一的 deviceId」
 * 这类错会在切换模式时凭空出现。
 */
const faultPicked = computed<FaultDeviceLookup | null>(() => {
  if (faultMode.value === 'ASSET') {
    return faultLookupResult.value
  }
  return faultOwnedDevices.value.find((item) => item.deviceId === faultForm.deviceId) ?? null
})

/** 选中项是否可提交（后端判定为唯一口径） */
const faultCanSubmit = computed(
  () => faultPicked.value?.reportable === true && faultPicked.value.deviceId != null
)

/** 关联工单只读展示：带 orderNo 说明是工单内上报，否则台账直接登记 */
const faultOrderText = computed(() => {
  const picked = faultPicked.value
  if (picked == null || picked.deviceId == null) {
    return '—'
  }
  return picked.orderNo ? picked.orderNo : '无（台账直接登记）'
})

/** 下拉展示文案：设备名（资产编号）· 状态 */
function faultDeviceLabel(device: FaultDeviceLookup): string {
  const name = device.deviceName ?? '未命名设备'
  const asset = device.assetNo ? `（${device.assetNo}）` : ''
  const status = device.deviceStatusLabel ? ` · ${device.deviceStatusLabel}` : ''
  return `${name}${asset}${status}`
}

async function loadFaultOwnedDevices(): Promise<void> {
  faultOwnedLoading.value = true
  try {
    faultOwnedDevices.value = await deviceFaultApi.ownedDevices()
  } catch {
    faultOwnedDevices.value = []
  } finally {
    faultOwnedLoading.value = false
  }
}

/**
 * 下拉可选项：滤掉 deviceId 为空的项。
 *
 * <p>服务端不会下发这种数据（没查到设备时整条返回 found=false），但类型上 deviceId 可空 ——
 * `el-option` 的 value 不接受 null。用类型守卫顺手把 deviceId 收窄成 number，
 * 模板里就不必写 `!` 断言（断言一旦失效，是运行时才发现的下拉错乱）。
 */
const faultOwnedOptions = computed(() =>
  faultOwnedDevices.value.filter(
    (item): item is FaultDeviceLookup & { deviceId: number } => item.deviceId != null
  )
)

/**
 * 切换填报方式：清掉另一模式的选择。
 *
 * <p>必须清 —— 两个模式的「当前设备」是两套数据，留着上一个模式的选择会让
 * 「关联工单」显示出与眼前输入框无关的工单号，用户会以为系统记错了。
 */
function switchFaultMode(value: string | number | boolean | undefined): void {
  if (value !== 'MINE' && value !== 'ASSET') {
    return
  }
  faultMode.value = value
  faultForm.deviceId = null
  faultLookupResult.value = null
  if (value === 'MINE' && faultOwnedDevices.value.length === 0) {
    void loadFaultOwnedDevices()
  }
}

/** 按资产编号查询（扫码枪扫完直接触发回车） */
async function lookupFaultAsset(): Promise<void> {
  const assetNo = faultAssetNo.value.trim()
  if (!assetNo) {
    ElMessage.warning('请扫码或输入资产编号')
    return
  }
  faultLookupLoading.value = true
  try {
    faultLookupResult.value = await deviceFaultApi.lookup(assetNo)
  } catch {
    // 网络 / 服务异常与「查不到这台设备」是两件事：前者由请求层统一提示，
    // 这里只清空结果，不能伪装成 found=false 让人以为编号敲错了
    faultLookupResult.value = null
  } finally {
    faultLookupLoading.value = false
  }
}

/** 故障发生时间不得晚于当前时间 */
function disabledFaultOccurredDate(date: Date): boolean {
  return date.getTime() > Date.now()
}

async function submitFault(): Promise<void> {
  const picked = faultPicked.value
  if (picked == null || picked.deviceId == null) {
    ElMessage.warning(
      faultMode.value === 'ASSET' ? '请先扫码或输入资产编号并查询到设备' : '请选择故障设备'
    )
    return
  }
  // 「不可报修」的拦截放在前端只是省一次请求，真正的闸门在服务端
  if (!picked.reportable) {
    ElMessage.warning(picked.reason ?? '这台设备当前无法报修')
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
      deviceId: picked.deviceId,
      // 我在用这台设备时 orderId 才有值（走工单路径，设备保持使用中）；
      // 公共设备为 null（走台账登记，设备 可用 → 维修中）
      orderId: picked.orderId ?? null,
      faultDescription: faultForm.faultDescription.trim(),
      occurredAt: faultForm.occurredAt,
      // 报修不产生工单，分部只能落在故障记录上（V54）—— 否则「各分部报修量」没有数据源
      currentDivisionId: divisionId.value
    })
    // 故障照片：绑定故障记录 id，随登记一并上传；照片可选
    const attached =
      faultFiles.value.length > 0 && faultId != null
        ? await uploadAttachments('FAULT_PHOTO', faultId, faultFiles.value)
        : 0
    ElMessage.success(attached > 0 ? `故障已登记，并上传 ${attached} 张照片` : '故障已登记')
    // 登记成功后返回步骤一并重置故障表单，便于连续登记或切换类型
    resetFaultForm()
    // 已成功登记，清除草稿
    clearDraft()
    // 分部回到默认（理由同借用表单：预填随「今天是否在出差」变化）
    divisionTouched.value = false
    void loadDivisionPrefill()
    backToSelect()
  } catch {
    // 设备状态已变化等冲突由请求层统一提示
  } finally {
    faultSubmitting.value = false
  }
}

function resetFaultForm(): void {
  faultForm.deviceId = null
  faultForm.faultDescription = ''
  faultForm.occurredAt = ''
  faultFiles.value = []
  faultAssetNo.value = ''
  faultLookupResult.value = null
  // 列表缓存一并清掉：下次进入报修时重新拉，才能看到刚变过状态的那台
  faultOwnedDevices.value = []
}

// ------------------------------------------------------------------
// 生命周期
// ------------------------------------------------------------------

onMounted(async () => {
  // 分类树与型号字典先加载：下面 restoreDraft / applyScanPreselect 都要用选项
  await Promise.all([loadCategories(), loadModels()])
  // 自定义申请类型：与服务端权限过滤后的可用类型
  await loadCustomTypes()
  // 当前所在分部预填（V54）：两种内置表单共用，进入页面就取一次
  await loadDivisionPrefill()
  // 恢复上次未提交的草稿
  restoreDraft()
  // 扫码借还跳转带来的 ?primaryCategoryId=（P1 / V56）：放在草稿恢复**之后** ——
  // URL 是本次的明确动作，应当覆盖上次残留的草稿内容。
  await applyScanPreselect()
  // 草稿恢复后表单可能已就绪 → 拉一次借用路径预览（M1）
  void refreshBorrowPreview()
})

// 表单与步骤变化即安排落盘（watch 仅用于持久化，不参与业务级联清理；
// 级联清空由 handlePrimaryChange 这类事件处理器负责，见约定：
// 「级联清空不用 watch」—— watch 触发的清空会与用户操作抢时序）。
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
        <!-- 卡片**平铺**。
             改造前自定义申请被一个「自定义申请」分组标题单独隔开，形成「内置 / 自定义」两段式布局；
             实际使用中两者都是「提交一张单」的入口，分段只增加了扫视成本。
             现合并为同一网格，顺序约定：**内置类型在前、自定义表单类在后** ——
             内置两项是高频操作；自定义类型数量随管理员配置增长，排在后面才不会把常用入口挤下去。
             自定义卡片**不再**显示审批模式标签（V48）：需求方明确要求去掉卡片上的
             「自定义流程」字样 —— 那个词对提交人没有信息量（既没说几级、也没说谁批），
             却多占一行视觉重量。审批路径在选定类型后的「路径预览」里会逐级列出。 -->
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
            <h4 class="ts-apply__type-title">{{ item.typeName }}</h4>
            <p class="ts-apply__type-desc">{{ item.description || '自定义申请表单' }}</p>
          </div>
        </div>
      </template>

      <!-- 步骤二：进入对应表单 -->
      <template v-else>
        <div class="ts-apply__back" role="button" tabindex="0" @click="backToSelect" @keyup.enter="backToSelect">
          ← 返回重选类型
        </div>

        <!-- 借用表单（V56：借什么类别 / 还的日期 / 用途 + 可选附件） -->
        <template v-if="applyType === 'BORROW'">
          <h3 class="ts-order__title">申请借用设备</h3>
          <p class="ts-text-secondary ts-order__desc">
            说明「借哪一类设备、什么型号」即可，无需挑具体某一台；
            审批通过后由 IT 交付时扫码录入资产编号，那时才会锁定设备。
          </p>

          <el-form class="ts-order__form ts-mt-16" label-width="104px" :label-position="isMobile ? 'top' : 'right'">
            <el-form-item label="设备类别" required>
              <el-select
                :model-value="form.primaryCategoryId"
                class="ts-order__field"
                placeholder="请选择要借用的设备类别"
                filterable
                clearable
                :loading="categoryLoading"
                @change="handlePrimaryChange"
              >
                <el-option
                  v-for="category in primaryOptions"
                  :key="category.id"
                  :label="category.categoryName"
                  :value="category.id"
                />
              </el-select>
              <div class="ts-text-hint">交付时这台设备的类别必须与所选类别一致</div>
            </el-form-item>

            <el-form-item label="二级分类">
              <el-select
                v-model="form.secondaryCategoryId"
                class="ts-order__field"
                :placeholder="secondaryPlaceholder"
                filterable
                clearable
                :disabled="secondaryOptions.length === 0"
                @change="handleSecondaryChange"
              >
                <el-option
                  v-for="category in secondaryOptions"
                  :key="category.id"
                  :label="category.categoryName"
                  :value="category.id"
                />
              </el-select>
            </el-form-item>

            <el-form-item label="型号">
              <el-select
                v-model="form.modelId"
                class="ts-order__field"
                placeholder="选填，留空表示同类别内哪台都行"
                filterable
                clearable
                :loading="modelLoading"
              >
                <el-option
                  v-for="model in modelOptions"
                  :key="model.id"
                  :label="model.brand ? `${model.modelName}（${model.brand}）` : model.modelName"
                  :value="model.id"
                />
              </el-select>
              <div class="ts-text-hint">填了型号则交付时按型号核对；型号清单由资产管理员维护</div>
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

            <!-- V54 / D5：当前所在分部（决定由哪个处理小组接单；预填值员工可改） -->
            <el-form-item label="当前所在分部">
              <el-select
                v-model="divisionId"
                class="ts-order__field"
                placeholder="请选择当前所在分部"
                :loading="divisionLoading"
                :disabled="divisionOptions.length === 0"
                @change="handleDivisionChange"
              >
                <el-option
                  v-for="item in divisionOptions"
                  :key="item.id"
                  :label="item.deptName"
                  :value="item.id"
                />
              </el-select>
              <div class="ts-text-hint">{{ divisionHint }}</div>
            </el-form-item>

            <!-- 申请附件（随单提交，可选；需求方决策 3 保留） -->
            <el-form-item label="申请附件">
              <PendingAttachmentPicker v-model="applyFiles" biz-type="APPLY_ATTACHMENT" label="随申请提交的材料" />
            </el-form-item>
          </el-form>

          <!-- M1：审批路径预览（绑定流程时展示动态路径，否则不渲染） -->
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
            选「我名下的设备」从你自己的设备里挑；会议室打印机这类公共设备改用「公共设备 / 扫码」按资产编号填报。
            正在使用的设备会自动关联到你的借用单，报修后设备仍归你使用，去向由归还登记决定。
          </p>

          <el-form class="ts-order__form ts-mt-16" label-width="104px" :label-position="isMobile ? 'top' : 'right'">
            <!-- 填报方式（V57 / 第 10 项）：两种模式二选一，判定口径统一由服务端给 -->
            <el-form-item label="填报方式" required>
              <el-radio-group :model-value="faultMode" @change="switchFaultMode">
                <el-radio-button value="MINE">我名下的设备</el-radio-button>
                <el-radio-button value="ASSET">公共设备 / 扫码</el-radio-button>
              </el-radio-group>
              <div class="ts-text-hint">{{ faultModeHint }}</div>
            </el-form-item>

            <!-- 模式一：我负责的 ∪ 我正在用的 -->
            <el-form-item v-if="faultMode === 'MINE'" label="故障设备" required>
              <el-select
                v-model="faultForm.deviceId"
                class="ts-order__field"
                placeholder="请选择故障设备"
                filterable
                clearable
                :loading="faultOwnedLoading"
              >
                <el-option
                  v-for="device in faultOwnedOptions"
                  :key="device.deviceId"
                  :label="faultDeviceLabel(device)"
                  :value="device.deviceId"
                  :disabled="!device.reportable"
                />
              </el-select>
              <div v-if="!faultOwnedLoading && faultOwnedOptions.length === 0" class="ts-text-hint">
                你名下没有可报修的设备 —— 请切换到「公共设备 / 扫码」按资产编号填报
              </div>
              <!-- 不可报修的原因直接展示：后端给的是「能照着做」的一句话 -->
              <div v-else-if="faultPicked && !faultPicked.reportable" class="ts-fault__reason">
                {{ faultPicked.reason }}
              </div>
            </el-form-item>

            <!-- 模式二：扫码 / 输入资产编号 -->
            <el-form-item v-else label="资产编号" required>
              <div class="ts-fault__lookup">
                <el-input
                  v-model="faultAssetNo"
                  placeholder="扫描资产二维码，或直接输入编号后回车"
                  clearable
                  @keyup.enter="lookupFaultAsset"
                />
                <el-button type="primary" :loading="faultLookupLoading" @click="lookupFaultAsset">
                  查询
                </el-button>
              </div>
              <div
                v-if="faultLookupResult"
                class="ts-fault__result"
                :class="{ 'is-blocked': !faultLookupResult.reportable }"
              >
                <template v-if="faultLookupResult.found">
                  <div class="ts-fault__result-title">
                    <span>{{ faultLookupResult.deviceName }}（{{ faultLookupResult.assetNo }}）</span>
                    <el-tag size="small" effect="plain">{{ faultLookupResult.deviceStatusLabel }}</el-tag>
                  </div>
                  <div class="ts-text-hint">
                    {{ faultLookupResult.primaryCategoryName || '未归类'
                    }}<template v-if="faultLookupResult.secondaryCategoryName">
                      / {{ faultLookupResult.secondaryCategoryName }}</template
                    ><template v-if="faultLookupResult.orderNo">
                      · 将关联你的工单 {{ faultLookupResult.orderNo }}</template
                    >
                  </div>
                  <div v-if="!faultLookupResult.reportable" class="ts-fault__reason">
                    {{ faultLookupResult.reason }}
                  </div>
                </template>
                <div v-else class="ts-fault__reason">没找到这个资产编号，请核对后重试</div>
              </div>
            </el-form-item>

            <el-form-item label="关联工单">
              <span class="ts-text-hint">{{ faultOrderText }}</span>
            </el-form-item>

            <!-- V54 / D5：报修同样按「当前所在分部」匹配处理小组 -->
            <el-form-item label="当前所在分部">
              <el-select
                v-model="divisionId"
                class="ts-order__field"
                placeholder="请选择当前所在分部"
                :loading="divisionLoading"
                :disabled="divisionOptions.length === 0"
                @change="handleDivisionChange"
              >
                <el-option
                  v-for="item in divisionOptions"
                  :key="item.id"
                  :label="item.deptName"
                  :value="item.id"
                />
              </el-select>
              <div class="ts-text-hint">{{ divisionHint }}</div>
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

            <!-- 故障照片（随登记上传，仅图片） -->
            <el-form-item label="故障照片">
              <PendingAttachmentPicker v-model="faultFiles" biz-type="FAULT_PHOTO" label="现场照片" />
            </el-form-item>
          </el-form>

          <div class="ts-order__submit">
            <el-button @click="backToSelect">返回</el-button>
            <el-button
              type="danger"
              :loading="faultSubmitting"
              :disabled="!faultCanSubmit"
              @click="submitFault"
            >
              提交故障登记
            </el-button>
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

.ts-order__submit {
  display: flex;
  gap: 12px;
  justify-content: flex-end;
  max-width: 720px;
  margin-top: 8px;
}

/* 步骤一：类型选择卡片。
   列宽用 auto-fill + minmax 而不是写死 3 列 —— 内置卡片只有 2 张，
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

/* 自定义申请卡片（与内置卡片同格平铺）：
   「自定义申请」分组标题与其独立外边距已随平铺一并移除。
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

/* 故障报修「公共设备」模式：输入框 + 查询按钮，窄屏纵向堆叠 */
.ts-fault__lookup {
  display: flex;
  gap: 8px;
  width: 100%;
}

.ts-fault__result {
  width: 100%;
  padding: 10px 12px;
  margin-top: 8px;
  border: 1px solid var(--ts-border);
  border-radius: 8px;
  background: #fbfcfe;
}

/* 不可报修：整块转为警示底色，避免「填完了才发现提交不了」 */
.ts-fault__result.is-blocked {
  border-color: #f5dab1;
  background: #fdf6ec;
}

.ts-fault__result-title {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  font-size: 13px;
  font-weight: 500;
}

.ts-fault__reason {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.6;
  color: #e6a23c;
}

@media (max-width: 767px) {
  .ts-fault__lookup {
    flex-direction: column;
  }

  .ts-order__submit .el-button {
    flex: 1 1 0;
  }

  .ts-apply__types {
    grid-template-columns: 1fr;
  }
}
</style>
