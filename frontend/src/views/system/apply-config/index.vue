<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Download, Plus, Setting } from '@element-plus/icons-vue'
import { applyTypeApi } from '@/api/applyType'
import { formTemplateApi } from '@/api/form'
import { approvalFlowApi } from '@/api/approvalFlow'
import ApplyFieldEditor from '@/components/ApplyFieldEditor.vue'
import CustomFormExportDialog from '@/components/CustomFormExportDialog.vue'
import LinearFlowEditor from '@/components/LinearFlowEditor.vue'
import UserSelectDialog from '@/components/UserSelectDialog.vue'
import { useResponsive } from '@/composables/useResponsive'
import { useUserStore } from '@/store/user'
import {
  applyTypeStatusTagType,
  type ApplyTypeItem,
  type BuiltInApplyConfig,
  type BuiltInApplyConfigPayload,
  type FaultAssigneeTypeCode
} from '@/types/applyType'
import type { UserOption } from '@/types/user'
import {
  fieldTypeMeta,
  type FormField,
  type FormSchema
} from '@/types/form'
import type { FlowDefinition, FlowFieldOption } from '@/types/approvalFlow'

/**
 * 申请类型与审批流程（ 项目 3 / 4 / 5）—— **合并页**
 *
 * <h2>为什么把「申请类型管理」与「审批流程模板」合成一页</h2>
 * 说明：「不要分两个菜单，点进一个申请类型左边配表单、右边配审批流程，一个页面搞定」。
 * 改造前是两个菜单 + 三段式（建表单模板 → 发布版本 → 画流程 → 发布版本 → 再建类型），
 * 管理员要在三页之间来回跳。本页把「一个申请类型」当成**一个可配置的整体**：
 * 卡片列表 → 点卡片 → 左表单右流程。
 *
 * <h2>三步新建：为什么能「一步到位」</h2>
 * 说明：「点『新建申请』就三步：填名称 → 加表单字段 → 设审批人 …… 一步到位」。
 * 落实方式是后端的一次性接口 `POST /apply-types/full`：一个事务里建齐
 * 「表单模板 + 首发版本 + 流程 + 首发版本 + 类型」，中途失败整体回滚。
 * 因此界面上**没有「发布版本」这一步**，也没有编码 / 版本号输入框
 * （需求：「编码、版本号这些技术细节藏起来，管理员看不到」）。
 *
 * <h2>编辑已有类型为什么要「顺带发布新版本」</h2>
 * 已发布的表单 / 流程版本按设计**不可修改**（历史工单要按当时的版本回显）。
 * 因此这里的保存语义是：改草稿 → 发布出新版本 → 把申请类型重绑到新版本。
 * 老版本仍被历史工单引用，不受影响 —— 这是「版本冻结」的应有之义，不是副作用。
 */

const router = useRouter()
const userStore = useUserStore()
const { isMobile } = useResponsive()

const canManage = computed(() => userStore.hasPerm('apply_type:manage'))
const canExportData = computed(() => userStore.role === 'super_admin' || userStore.role === 'admin')

// ---------------------------------------------------------------------
// 列表
// ---------------------------------------------------------------------

const loading = ref(false)
const types = ref<ApplyTypeItem[]>([])

/**
 * 卡片图标。图标名按名渲染（main.ts 的 ICONS 全局注册），未注册的名字会渲成空标签，
 * 因此这里对未知名字回退到默认图标，而不是把空白留给用户。
 */
const ICON_OPTIONS = [
  'Tickets',
  'Timer',
  'Calendar',
  'Postcard',
  'ShoppingCart',
  'Key',
  'Document',
  'Notebook',
  'Box',
  'Lock',
  'Bell',
  'User'
]

function iconOf(item: ApplyTypeItem): string {
  return item.icon && ICON_OPTIONS.includes(item.icon) ? item.icon : 'Tickets'
}

async function loadTypes(): Promise<void> {
  loading.value = true
  try {
    types.value = await applyTypeApi.list()
  } catch {
    types.value = []
  } finally {
    loading.value = false
  }
}

/**
 * 卡片上的审批摘要（一行小字）。
 *
 * <h2>为什么不再回退成「自定义流程」</h2>
 * 需求方明确要求去掉卡片上的「自定义流程」字样 —— 那个词对管理员没有信息量：
 * 既没说有哪几级、也没说谁批。现在的取值要么是**流程真名**，
 * 要么是一句**人话描述**（按部门逐级审批 / 无需审批），扫一眼就知道这张单怎么走。
 */
function levelText(item: ApplyTypeItem): string {
  if (item.builtIn) {
    // 内置类型（V48）：报修不产生工单、没有审批环节，如实说明；
    // 借用单未绑自定义流程时走的是「部门流程 → 系统预置三级流程」，不是「没有流程」。
    if (item.typeCode === 'FAULT_REPORT') {
      // V50：报修有了完整的处理链路（通知 → 接单 → 修完登记结果）。
      // 原先这里写的是「提交后直接进入维修处理」，读起来像是「系统自己会处理」——
      // 而实际上第一步是有人去接单，接不接取决于「处理流程」通知到了谁。
      return '提交后进入维修处理流程'
    }
    return item.approvalFlowName ?? '按部门逐级审批'
  }
  if (item.approvalMode === 'NONE') {
    return '无需审批'
  }
  if (item.approvalMode === 'GROUP') {
    return '按部门逐级审批'
  }
  return item.approvalFlowName ?? '自定义审批流程'
}

// ---------------------------------------------------------------------
// 新建：三步向导
// ---------------------------------------------------------------------

/** 默认流程：一级审批（与 LinearFlowEditor 的空行一致，避免打开时是"空定义"） */
function defaultFlow(): FlowDefinition {
  return {
    start: 'n1',
    nodes: [
      {
        key: 'n1',
        type: 'APPROVAL',
        name: '第1级审批',
        signType: 'ANY_SIGN',
        approverRules: [{ type: 'LEADER' }],
        next: 'end',
        timeLimitHours: 24
      },
      { key: 'end', type: 'END', name: '结束' }
    ]
  }
}

const wizardVisible = ref(false)
const wizardStep = ref(0)
const wizardSubmitting = ref(false)
const wizard = reactive({
  typeName: '',
  icon: 'Tickets',
  description: '',
  fields: [] as FormField[],
  flow: defaultFlow() as FlowDefinition
})

const fieldEditorRef = ref<InstanceType<typeof ApplyFieldEditor> | null>(null)
const wizardFlowRef = ref<InstanceType<typeof LinearFlowEditor> | null>(null)

/** 第三步条件字段的候选：来自第二步配的字段（label 显示、key 落库） */
const wizardFieldOptions = computed<FlowFieldOption[]>(() =>
  wizard.fields
    .filter((field) => (field.key ?? '') !== '')
    .map((field) => ({
      value: field.key as string,
      label: (field.label ?? '').trim() || (field.key as string),
      kind: fieldTypeMeta(field.type)?.valueKind ?? 'TEXT'
    }))
)

function openWizard(): void {
  wizardStep.value = 0
  wizard.typeName = ''
  wizard.icon = 'Tickets'
  wizard.description = ''
  wizard.fields = []
  wizard.flow = defaultFlow()
  wizardVisible.value = true
}

function wizardNext(): void {
  if (wizardStep.value === 0) {
    if (!wizard.typeName.trim()) {
      ElMessage.warning('请填写申请名称')
      return
    }
  }
  if (wizardStep.value === 1) {
    const problems = fieldEditorRef.value?.incompleteProblems() ?? []
    if (problems.length > 0) {
      ElMessage.warning(problems[0])
      return
    }
  }
  wizardStep.value = Math.min(wizardStep.value + 1, 2)
}

function wizardPrev(): void {
  wizardStep.value = Math.max(wizardStep.value - 1, 0)
}

async function submitWizard(): Promise<void> {
  const problems = wizardFlowRef.value?.incompleteProblems() ?? []
  if (problems.length > 0) {
    ElMessage.warning(problems[0])
    return
  }
  if ((wizard.flow.nodes ?? []).length === 0) {
    ElMessage.warning('请至少配置一个审批节点')
    return
  }
  const schema: FormSchema = { fields: wizard.fields }
  wizardSubmitting.value = true
  try {
    await applyTypeApi.createFull({
      typeName: wizard.typeName.trim(),
      icon: wizard.icon,
      description: wizard.description.trim() || null,
      formSchema: schema,
      flow: wizard.flow,
      submitPermissionType: 'ALL'
    })
    ElMessage.success('申请类型已创建')
    wizardVisible.value = false
    await loadTypes()
  } catch {
    // 名称重复 / 字段或流程校验未通过等由请求层统一提示
  } finally {
    wizardSubmitting.value = false
  }
}

// ---------------------------------------------------------------------
// 配置：左表单 / 右流程
// ---------------------------------------------------------------------

const configVisible = ref(false)
const configLoading = ref(false)
const configSaving = ref(false)
const configTarget = ref<ApplyTypeItem | null>(null)
const configFields = ref<FormField[]>([])
const configFlow = ref<FlowDefinition | null>(null)
const configFlowUnsupported = ref(false)
const configFieldEditorRef = ref<InstanceType<typeof ApplyFieldEditor> | null>(null)
const configFlowRef = ref<InstanceType<typeof LinearFlowEditor> | null>(null)

/** 保存时要重绑的版本目标（由版本 id 反查模板 / 流程的 id） */
const configCtx = reactive({
  templateId: null as number | null,
  flowId: null as number | null,
  templateName: '',
  flowCode: '',
  flowName: '',
  description: '' as string | null
})

const configFieldOptions = computed<FlowFieldOption[]>(() =>
  configFields.value
    .filter((field) => (field.key ?? '') !== '')
    .map((field) => ({
      value: field.key as string,
      label: (field.label ?? '').trim() || (field.key as string),
      kind: fieldTypeMeta(field.type)?.valueKind ?? 'TEXT'
    }))
)

const fieldLabelOf = (key: string): string =>
  configFields.value.find((field) => field.key === key)?.label ?? key

/** 保存可用性：既要有 manage 权限，也要能定位到源模板 / 源流程，且流程可在线性视图里编辑 */
const configSavable = computed(
  () => canManage.value && configCtx.templateId != null && configCtx.flowId != null && !configFlowUnsupported.value
)

async function openConfig(item: ApplyTypeItem): Promise<void> {
  // 内置类型（V48）没有表单模板，左表单 / 右流程那套编辑器对它不适用 ——
  // 它有自己的配置面：只能改「表单说明」与「审批流程」。
  if (item.builtIn) {
    await openBuiltInConfig(item)
    return
  }
  configTarget.value = item
  configFields.value = []
  configFlow.value = null
  configFlowUnsupported.value = false
  configCtx.templateId = null
  configCtx.flowId = null
  configVisible.value = true
  configLoading.value = true
  try {
    // 版本的「源」要靠反查：申请类型只存了版本 id，而编辑 / 发布是模板级 / 流程级的操作。
    // 反查条件是「该模板 / 流程的最新已发布版本 == 本类型绑定的版本」——
    // 成立时说明类型跟的是最新版（正常情况）；不成立说明它绑的是更早的历史版本，
    // 此时**不静默改写成最新版**（那等于顺手把类型升级了），而是给出明确提示。
    const [templates, flows] = await Promise.all([formTemplateApi.list(), approvalFlowApi.list()])
    const template = templates.find((it) => it.latestPublishedVersionId === item.formTemplateVersionId)
    const flow = flows.find((it) => it.latestPublishedVersionId === item.approvalFlowVersionId)
    configCtx.templateId = template?.id ?? null
    configCtx.flowId = flow?.id ?? null
    configCtx.templateName = template?.templateName ?? item.formTemplateName ?? item.typeName
    configCtx.flowCode = flow?.flowCode ?? ''
    configCtx.flowName = flow?.flowName ?? item.approvalFlowName ?? item.typeName
    configCtx.description = flow?.description ?? null

    if (template) {
      const detail = await formTemplateApi.detail(template.id)
      configFields.value = (detail.schema?.fields ?? []).map((field) => ({ ...field, options: field.options ? [...field.options] : null }))
    }
    if (flow) {
      const detail = await approvalFlowApi.detail(flow.id)
      configFlow.value = detail.definition ?? null
    }
  } catch {
    configFields.value = []
    configFlow.value = null
  } finally {
    configLoading.value = false
  }
}

async function saveConfig(): Promise<void> {
  const item = configTarget.value
  if (!item || configCtx.templateId == null || configCtx.flowId == null) {
    ElMessage.warning('无法定位该类型引用的表单 / 流程，请到「高级编辑」中处理')
    return
  }
  const problems = [
    ...(configFieldEditorRef.value?.incompleteProblems() ?? []),
    ...(configFlowRef.value?.incompleteProblems() ?? [])
  ]
  if (problems.length > 0) {
    ElMessage.warning(problems[0])
    return
  }
  const definition = configFlow.value
  if (!definition) {
    ElMessage.warning('请至少配置一个审批节点')
    return
  }
  configSaving.value = true
  try {
    // ① 表单：存草稿 → 发布出新版本
    await formTemplateApi.update(configCtx.templateId, {
      templateName: configCtx.templateName,
      description: item.description ?? null,
      schema: { fields: configFields.value }
    })
    const formVersionId = await formTemplateApi.publish(configCtx.templateId)

    // ② 流程：存草稿 → 发布出新版本
    await approvalFlowApi.update(configCtx.flowId, {
      flowCode: configCtx.flowCode,
      flowName: configCtx.flowName,
      description: configCtx.description,
      definition
    })
    const flowVersionId = await approvalFlowApi.publish(configCtx.flowId)

    // ③ 类型：重绑到新版本（老版本仍被历史工单引用）
    await applyTypeApi.update(item.id, {
      typeCode: item.typeCode,
      typeName: item.typeName,
      icon: item.icon ?? null,
      description: item.description ?? null,
      sortOrder: item.sortOrder ?? null,
      formTemplateVersionId: formVersionId,
      orderPrefix: item.orderPrefix ?? null,
      approvalMode: 'FLOW',
      approvalFlowVersionId: flowVersionId,
      submitPermissionType: item.submitPermissionType,
      submitPermissionValues: item.submitPermissionValues ?? []
    })
    ElMessage.success('已保存（表单与流程各发布了一版新版本，历史工单不受影响）')
    configVisible.value = false
    await loadTypes()
  } catch {
    // 校验未通过（如流程含运行期特性但总开关未开）由请求层提示
  } finally {
    configSaving.value = false
  }
}

function openAdvancedDesigner(): void {
  if (configCtx.flowId == null) {
    return
  }
  configVisible.value = false
  void router.push(`/system/approval-flow/designer/${configCtx.flowId}`)
}

/**
 * 打开**完整表单设计器**（ 补）。
 *
 * <h2>为什么必须补这个入口</h2>
 *  把管理页换成本合并页后，`/system/apply-type/designer/:templateId` 这条路由
 * **失去了唯一的界面入口** —— 能力还在、路由还在，但从界面上再也走不到。
 * 而本页内嵌的 {@link ApplyFieldEditor} 只支持 4 种字段（单行文本 / 日期 / 数字 / 下拉选择），
 * 预置的「请假申请」用的 `DATETIME`、以及附件 / 人员 / 设备等字段**只能靠完整设计器**新增。
 * 能力静默消失比功能坏掉更难发现，所以这里把它接回来。
 *
 * 与「打开完整流程设计器」对称：那两个 designer 都是"薄壳页 + 可复用设计器组件"，
 * 因此这里只负责跳转，不重复实现设计器。
 */
function openFormDesigner(): void {
  if (configCtx.templateId == null) {
    return
  }
  configVisible.value = false
  void router.push(`/system/apply-type/designer/${configCtx.templateId}`)
}

// ---------------------------------------------------------------------
// 系统内置类型的配置（V48）
// ---------------------------------------------------------------------

/**
 * 内置类型的配置面与自定义类型**完全不同**，因此独立成一个弹窗。
 *
 * <p>差别在哪：自定义类型是「表单模板 + 流程模板 + 类型」三件套，改的是字段与流程；
 * 内置类型的表单在代码里（借用表单 / 报修表单），管理员能动的只有「说明」与「审批流程」。
 * 把它塞进左表单右流程那个编辑器，只会让人误以为表单也能改。
 */
const builtInVisible = ref(false)
const builtInLoading = ref(false)
const builtInSaving = ref(false)
const builtInTarget = ref<ApplyTypeItem | null>(null)
const builtInCfg = ref<BuiltInApplyConfig | null>(null)
const builtInDescription = ref('')
const builtInFlow = ref<FlowDefinition | null>(null)
const builtInFlowRef = ref<InstanceType<typeof LinearFlowEditor> | null>(null)

// ---- 处理流程（V50，仅故障报修）：提交后通知谁来修 ----
//
// 它与上面的「审批流程」是**并列且互斥**的两块：借用走审批流、报修走处理流。
// 之所以不复用审批流程编辑器：报修不产生工单，装不进「审批节点 + 快照」那套模型，
// 需要管理员配的只有「通知给谁」这一件事。

/** 后端下发的处理流程配置（只有报修有） */
const builtInHandler = computed(() => builtInCfg.value?.handler ?? null)
const builtInAssigneeType = ref<FaultAssigneeTypeCode>('DEPARTMENT')
const builtInAssigneeValue = ref('')
const builtInUserPicked = ref<{ id: number; name: string } | null>(null)
const builtInUserPickerVisible = ref(false)

/** 当前通知方式的说明（取自后端下发的 typeOptions，不在前端再写一份中文） */
const builtInTypeDescription = computed(() => {
  const type = builtInAssigneeType.value
  return builtInHandler.value?.typeOptions.find((opt) => opt.value === type)?.description ?? ''
})

/**
 * 切换通知方式时清空目标值。
 *
 * 用 `@change` 而**不是** `watch`：`watch` 在程序化回填（打开弹窗时按配置赋值）时也会触发，
 * 于是「打开一个已经配好的弹窗」会顺手把回显的选择清空 —— 管理员什么都没改、点保存，
 * 配置就被静默改成了「未指定」。`@change` 只在用户真的点了单选时触发。
 */
function onAssigneeTypeChange(): void {
  builtInAssigneeValue.value = ''
  builtInUserPicked.value = null
}

function handleBuiltInUserPicked(picked: UserOption[]): void {
  const first = picked[0]
  builtInUserPicked.value = first ? { id: first.id, name: first.displayName || first.username } : null
}

/** 提交时用的目标值：空值一律按「未指定」传 null（后端按类型判断是否合法） */
function currentAssigneeValue(): string | null {
  if (builtInAssigneeType.value === 'USER') {
    return builtInUserPicked.value ? String(builtInUserPicked.value.id) : null
  }
  return builtInAssigneeValue.value || null
}

function resetBuiltInHandler(): void {
  builtInAssigneeType.value = 'DEPARTMENT'
  builtInAssigneeValue.value = ''
  builtInUserPicked.value = null
}

/**
 * 把接口回来的配置铺进表单。
 *
 * 打开弹窗与「保存后原地刷新」两条路径都走它 —— 两份赋值代码迟早会漂移，
 * 而漂移的表现是「刚保存完看着对、重新打开又不一样」。
 */
function applyBuiltInCfg(cfg: BuiltInApplyConfig): void {
  builtInCfg.value = cfg
  builtInDescription.value = cfg.description ?? ''
  // 深拷贝：编辑器会就地改这份定义，直接引用会把「点了取消」也变成「改过了」
  builtInFlow.value = cfg.flowDefinition
    ? (JSON.parse(JSON.stringify(cfg.flowDefinition)) as FlowDefinition)
    : null
  const handler = cfg.handler ?? null
  builtInAssigneeType.value = handler?.assigneeType ?? 'DEPARTMENT'
  builtInAssigneeValue.value = handler?.assigneeValue ?? ''
  builtInUserPicked.value =
    handler && handler.assigneeType === 'USER' && handler.assigneeValue
      ? { id: Number(handler.assigneeValue), name: handler.userDisplayName ?? `用户 #${handler.assigneeValue}` }
      : null
}

/** 内置流程的条件字段候选：来自后端的借用域字段目录（与提交时的 schema 同源） */
const builtInFieldOptions = computed<FlowFieldOption[]>(() =>
  (builtInCfg.value?.fieldSchema?.fields ?? [])
    .filter((field) => (field.key ?? '') !== '')
    .map((field) => ({
      value: field.key as string,
      label: (field.label ?? '').trim() || (field.key as string),
      kind: fieldTypeMeta(field.type)?.valueKind ?? 'TEXT'
    }))
)

const builtInFieldLabelOf = (key: string): string =>
  builtInCfg.value?.fieldSchema?.fields?.find((field) => field.key === key)?.label ?? key

async function openBuiltInConfig(item: ApplyTypeItem): Promise<void> {
  builtInTarget.value = item
  builtInCfg.value = null
  builtInDescription.value = ''
  builtInFlow.value = null
  resetBuiltInHandler()
  builtInVisible.value = true
  builtInLoading.value = true
  try {
    applyBuiltInCfg(await applyTypeApi.builtInConfig(item.id))
  } catch {
    builtInCfg.value = null
  } finally {
    builtInLoading.value = false
  }
}

async function saveBuiltInConfig(): Promise<void> {
  const cfg = builtInCfg.value
  const item = builtInTarget.value
  if (!cfg || !item) {
    return
  }
  const description = builtInDescription.value.trim() || null

  // 流程不可编辑（故障报修）：提交说明，流程版本原样带回；
  // 有处理流程的类型再带上「通知给谁」。
  if (!cfg.flowEditable) {
    builtInSaving.value = true
    try {
      const payload: BuiltInApplyConfigPayload = {
        description,
        approvalFlowVersionId: cfg.approvalFlowVersionId ?? null
      }
      if (cfg.hasHandler) {
        // 接口约定「不传 assigneeType = 本次不改处理流程」，而这里明确是要改
        payload.assigneeType = builtInAssigneeType.value
        payload.assigneeValue = currentAssigneeValue()
      }
      await applyTypeApi.updateBuiltInConfig(item.id, payload)
      ElMessage.success('已保存')
      if (cfg.hasHandler) {
        // 留在弹窗里并**原地刷新**，而不是保存即关闭：
        // 这块配置的价值有一半在「当前会通知到几个人」——关掉弹窗等于把刚配的东西立刻藏起来，
        // 而「配了但会通知到 0 人」正是最该当场看见、当场改掉的情况。
        applyBuiltInCfg(await applyTypeApi.builtInConfig(item.id))
      } else {
        builtInVisible.value = false
        await loadTypes()
      }
    } catch {
      // 由请求层统一提示
    } finally {
      builtInSaving.value = false
    }
    return
  }

  if (!builtInFlow.value) {
    ElMessage.warning('请至少配置一个审批节点')
    return
  }
  const problems = builtInFlowRef.value?.incompleteProblems() ?? []
  if (problems.length > 0) {
    ElMessage.warning(problems[0])
    return
  }

  builtInSaving.value = true
  try {
    // 与自定义类型同一条口径：存草稿 → 发布新版本 → 把类型重绑到新版本。
    // 老版本仍被历史工单引用，因此**改流程改不到已提交的单**。
    let flowVersionId: number
    if (cfg.flowTemplateId != null) {
      await approvalFlowApi.update(cfg.flowTemplateId, {
        flowCode: cfg.flowCode ?? '',
        flowName: cfg.flowName ?? `${item.typeName}审批流程`,
        description: null,
        definition: builtInFlow.value
      })
      flowVersionId = await approvalFlowApi.publish(cfg.flowTemplateId)
    } else {
      // 首次配置：该内置类型此前走的不是流程模板（系统预置 / 部门流程），
      // 因此这里**新建**一份流程模板来承载管理员画的定义。
      const flowId = await approvalFlowApi.create({
        flowCode: `BUILTIN_${item.typeCode}`,
        flowName: `${item.typeName}审批流程`,
        description: '由「申请类型与审批流程」页为系统内置类型创建',
        definition: builtInFlow.value
      })
      flowVersionId = await approvalFlowApi.publish(flowId)
    }
    await applyTypeApi.updateBuiltInConfig(item.id, { description, approvalFlowVersionId: flowVersionId })
    ElMessage.success('已保存，之后新提交的借用单将按新流程审批（历史工单不受影响）')
    builtInVisible.value = false
    await loadTypes()
  } catch {
    // 校验未通过（如流程含运行期特性但总开关未开）由请求层提示
  } finally {
    builtInSaving.value = false
  }
}

// ---------------------------------------------------------------------
// 启停 / 删除 / 导出
// ---------------------------------------------------------------------

async function toggleStatus(item: ApplyTypeItem): Promise<void> {
  const target = item.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
  const action = target === 'ENABLED' ? '启用' : '停用'
  try {
    await ElMessageBox.confirm(`确认${action}「${item.typeName}」？`, `${action}申请类型`, { type: 'warning' })
  } catch {
    return
  }
  try {
    await applyTypeApi.updateStatus(item.id, target)
    ElMessage.success(`已${action}`)
    await loadTypes()
  } catch {
    // 由请求层提示
  }
}

async function removeType(item: ApplyTypeItem): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认删除申请类型「${item.typeName}」？已被工单使用的类型不能删除（只能停用）。`,
      '删除申请类型',
      { type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await applyTypeApi.remove(item.id)
    ElMessage.success('申请类型已删除')
    await loadTypes()
  } catch {
    // 由请求层提示
  }
}

const exportVisible = ref(false)
const exportTarget = ref<ApplyTypeItem | null>(null)

function openExport(item: ApplyTypeItem): void {
  exportTarget.value = item
  exportVisible.value = true
}

onMounted(loadTypes)
</script>

<template>
  <div class="ts-page">
    <section class="ts-card">
      <div class="ts-applycfg__head">
        <div>
          <h3 class="ts-order__title">申请类型与审批流程</h3>
          <p class="ts-text-secondary ts-applycfg__desc">
            每个申请类型一张卡片，点进去左边配表单、右边配审批流程。员工在「提交申请」页看到的卡片就来自这里。
          </p>
        </div>
        <el-button v-if="canManage" type="primary" :icon="Plus" @click="openWizard">新建申请</el-button>
      </div>

      <div v-loading="loading" class="ts-applycfg__grid">
        <el-empty v-if="!loading && types.length === 0" description="还没有申请类型，点右上角「新建申请」开始" />
        <div v-for="item in types" :key="item.id" class="ts-applycfg__card">
          <div class="ts-applycfg__card-head">
            <span class="ts-applycfg__icon">
              <component :is="iconOf(item)" />
            </span>
            <div class="ts-applycfg__card-title">
              <h4>{{ item.typeName }}</h4>
              <p class="ts-text-hint">{{ item.description || '未填写说明' }}</p>
            </div>
          </div>

          <div class="ts-applycfg__card-tags">
            <!-- 系统内置标记（V48）：一眼看出这张卡片改不了结构、也删不掉 -->
            <el-tag v-if="item.builtIn" size="small" effect="plain" type="info">系统内置</el-tag>
            <el-tag size="small" effect="plain" :type="applyTypeStatusTagType(item.status)">
              {{ item.statusLabel ?? item.status }}
            </el-tag>
            <span class="ts-text-hint">{{ levelText(item) }}</span>
          </div>

          <div class="ts-applycfg__card-foot">
            <el-button link type="primary" size="small" :icon="Setting" @click="openConfig(item)">配置</el-button>
            <el-button
              v-if="canManage"
              link
              size="small"
              :type="item.status === 'ENABLED' ? 'warning' : 'success'"
              @click="toggleStatus(item)"
            >
              {{ item.status === 'ENABLED' ? '停用' : '启用' }}
            </el-button>
            <el-button v-if="canExportData" link type="primary" size="small" :icon="Download" @click="openExport(item)">
              导出数据
            </el-button>
            <!-- 系统内置类型不可删除（V48）：后端也会拒绝，这里干脆不显示按钮 -->
            <el-button
              v-if="canManage && !item.builtIn"
              link
              type="danger"
              size="small"
              :icon="Delete"
              @click="removeType(item)"
            >
              删除
            </el-button>
          </div>
        </div>
      </div>
    </section>

    <!-- 新建：三步向导 -->
    <el-dialog
      v-model="wizardVisible"
      title="新建申请"
      :width="isMobile ? '96%' : '860px'"
      :close-on-click-modal="false"
      top="5vh"
    >
      <el-steps :active="wizardStep" align-center finish-status="success" class="ts-applycfg__steps">
        <el-step title="填名称" description="申请叫什么" />
        <el-step title="加表单字段" description="员工要填什么" />
        <el-step title="设审批人" description="谁来批" />
      </el-steps>

      <!-- 第一步 -->
      <div v-show="wizardStep === 0" class="ts-applycfg__step">
        <el-form label-width="88px" :label-position="isMobile ? 'top' : 'right'">
          <el-form-item label="申请名称" required>
            <el-input v-model="wizard.typeName" maxlength="64" placeholder="如：加班申请" />
          </el-form-item>
          <el-form-item label="图标">
            <div class="ts-applycfg__icon-picker">
              <span
                v-for="name in ICON_OPTIONS"
                :key="name"
                class="ts-applycfg__icon-option"
                :class="{ 'is-active': wizard.icon === name }"
                role="button"
                tabindex="0"
                @click="wizard.icon = name"
                @keyup.enter="wizard.icon = name"
              >
                <component :is="name" />
              </span>
            </div>
          </el-form-item>
          <el-form-item label="说明">
            <el-input
              v-model="wizard.description"
              type="textarea"
              :rows="2"
              maxlength="255"
              show-word-limit
              placeholder="选填，展示在提交页卡片上"
            />
          </el-form-item>
        </el-form>
      </div>

      <!-- 第二步 -->
      <div v-show="wizardStep === 1" class="ts-applycfg__step">
        <p class="ts-text-hint ts-applycfg__step-hint">
          点「添加字段」选类型：单行文本 / 日期 / 数字 / 下拉选择。字段的顺序就是员工填写时的顺序。
        </p>
        <ApplyFieldEditor ref="fieldEditorRef" v-model="wizard.fields" />
      </div>

      <!-- 第三步 -->
      <div v-show="wizardStep === 2" class="ts-applycfg__step">
        <LinearFlowEditor ref="wizardFlowRef" v-model="wizard.flow" :fields="wizardFieldOptions" />
      </div>

      <template #footer>
        <el-button @click="wizardVisible = false">取消</el-button>
        <el-button v-if="wizardStep > 0" @click="wizardPrev">上一步</el-button>
        <el-button v-if="wizardStep < 2" type="primary" @click="wizardNext">下一步</el-button>
        <el-button v-else type="primary" :loading="wizardSubmitting" @click="submitWizard">完成创建</el-button>
      </template>
    </el-dialog>

    <!-- 配置：左表单 / 右流程 -->
    <el-dialog
      v-model="configVisible"
      :title="`配置：${configTarget?.typeName ?? ''}`"
      :width="isMobile ? '96%' : '1080px'"
      :close-on-click-modal="false"
      top="4vh"
    >
      <div v-loading="configLoading" class="ts-applycfg__split">
        <div class="ts-applycfg__pane">
          <div class="ts-applycfg__pane-head">
            <h4 class="ts-applycfg__pane-title">表单字段（员工要填什么）</h4>
            <el-button
              v-if="canManage && configCtx.templateId != null"
              link
              type="primary"
              size="small"
              @click="openFormDesigner"
            >
              高级表单设计
            </el-button>
          </div>
          <ApplyFieldEditor ref="configFieldEditorRef" v-model="configFields" :readonly="!canManage" />
        </div>
        <div class="ts-applycfg__pane">
          <h4 class="ts-applycfg__pane-title">审批流程（谁来批）</h4>
          <LinearFlowEditor
            ref="configFlowRef"
            v-model="configFlow"
            :fields="configFieldOptions"
            :field-label-of="fieldLabelOf"
            :readonly="!canManage"
            @request-advanced="openAdvancedDesigner"
          />
        </div>
      </div>

      <el-alert v-if="configCtx.templateId == null || configCtx.flowId == null" type="info" :closable="false" show-icon>
        <template #title>该申请类型引用的表单 / 流程不是「最新已发布版本」</template>
        为避免误改，这里不自动升级到最新版。请到「高级编辑」里处理，或新建一个申请类型。
      </el-alert>

      <template #footer>
        <el-button @click="configVisible = false">关闭</el-button>
        <el-button v-if="canManage" type="primary" :loading="configSaving" :disabled="!configSavable" @click="saveConfig">
          保存
        </el-button>
      </template>
    </el-dialog>


    <!-- 系统内置类型的配置（V48）：只有「表单说明」与「审批流程」两项可动 -->
    <el-dialog
      v-model="builtInVisible"
      :title="`配置：${builtInTarget?.typeName ?? ''}`"
      :width="isMobile ? '96%' : '900px'"
      :close-on-click-modal="false"
      top="5vh"
    >
      <div v-loading="builtInLoading" class="ts-applycfg__builtin">
        <el-alert type="info" :closable="false" show-icon>
          <template #title>系统内置类型</template>
          {{ builtInCfg?.formNote }}
        </el-alert>

        <el-form label-width="88px" :label-position="isMobile ? 'top' : 'right'">
          <el-form-item label="表单说明">
            <el-input
              v-model="builtInDescription"
              maxlength="255"
              show-word-limit
              placeholder="展示在提交页卡片上的一行说明"
              :disabled="!canManage"
            />
          </el-form-item>
        </el-form>

        <template v-if="builtInCfg?.flowEditable">
          <el-alert
            :type="builtInCfg?.flowBound ? 'success' : 'warning'"
            :closable="false"
            show-icon
          >
            <template #title>
              {{ builtInCfg?.flowBound ? '已启用自定义审批流程' : '当前走默认审批（按部门逐级 / 系统预置三级流程）' }}
            </template>
            保存后，<strong>之后新提交</strong>的借用单按这里的流程审批；已提交的工单按提交时的快照走，不受影响。
          </el-alert>

          <h4 class="ts-applycfg__pane-title">审批流程（谁来批）</h4>
          <LinearFlowEditor
            ref="builtInFlowRef"
            v-model="builtInFlow"
            :fields="builtInFieldOptions"
            :field-label-of="builtInFieldLabelOf"
            :readonly="!canManage"
          />
        </template>
        <!--
          「处理流程」（V50）：报修不走审批，但有一整条处理链路。
          改造前这里只有一句「不经过审批，因此没有可配置的审批流程」——
          一个纯否定式的说明：管理员读完只知道「这儿没东西」，而实际上
          「提交后通知谁」是必须有人配的一项，不配就没人会去接单。
        -->
        <template v-else-if="builtInHandler">
          <el-alert
            :type="builtInHandler.resolvedCount > 0 ? 'info' : 'warning'"
            :closable="false"
            show-icon
          >
            <template #title>处理流程（提交后通知谁来修）</template>
            报修不经过审批，提交后直接进入维修处理。这里决定提交时报修单<b>通知给谁</b> ——
            被通知的人可以在「设备故障记录」页接单，修完后登记维修结果。
          </el-alert>

          <h4 class="ts-applycfg__pane-title">通知给谁</h4>
          <el-form label-width="96px" :label-position="isMobile ? 'top' : 'right'">
            <el-form-item label="通知方式">
              <el-radio-group
                :model-value="builtInAssigneeType"
                :disabled="!canManage"
                @change="onAssigneeTypeChange"
              >
                <el-radio-button
                  v-for="opt in builtInHandler.typeOptions"
                  :key="opt.value"
                  :value="opt.value"
                >
                  {{ opt.label }}
                </el-radio-button>
              </el-radio-group>
              <p v-if="builtInTypeDescription" class="ts-text-hint ts-applycfg__inline-hint">
                {{ builtInTypeDescription }}
              </p>
            </el-form-item>

            <el-form-item v-if="builtInAssigneeType === 'DEPARTMENT'" label="通知部门">
              <el-select
                v-model="builtInAssigneeValue"
                class="ts-applycfg__handler-select"
                clearable
                filterable
                :disabled="!canManage"
                placeholder="不选 = 用系统里的最终处理部门"
              >
                <el-option
                  v-for="opt in builtInHandler.departmentOptions"
                  :key="opt.value"
                  :label="opt.label"
                  :value="opt.value"
                />
              </el-select>
            </el-form-item>

            <el-form-item v-else-if="builtInAssigneeType === 'ROLE'" label="通知角色">
              <el-select
                v-model="builtInAssigneeValue"
                class="ts-applycfg__handler-select"
                filterable
                :disabled="!canManage"
                placeholder="请选择角色"
              >
                <el-option
                  v-for="opt in builtInHandler.roleOptions"
                  :key="opt.value"
                  :label="opt.label"
                  :value="opt.value"
                />
              </el-select>
            </el-form-item>

            <el-form-item v-else label="指定人员">
              <el-input
                :model-value="builtInUserPicked?.name ?? ''"
                class="ts-applycfg__handler-select"
                readonly
                placeholder="请选择一个人"
                :disabled="!canManage"
              >
                <template #append>
                  <el-button :disabled="!canManage" @click="builtInUserPickerVisible = true">
                    选择
                  </el-button>
                </template>
              </el-input>
            </el-form-item>
          </el-form>

          <el-alert v-if="builtInHandler.resolvedCount === 0" type="warning" :closable="false" show-icon>
            <template #title>当前配置会通知到 0 个人</template>
            报修仍能正常提交，但不会有任何人收到通知，也就没人会去接单 ——
            请检查上面选中的部门 / 角色目前是否已有在职成员。
          </el-alert>
          <p v-else class="ts-text-hint ts-applycfg__inline-hint">
            当前会通知：{{ builtInHandler.effectiveLabel }}，共
            {{ builtInHandler.resolvedCount }} 名在职人员。
          </p>
        </template>
        <div v-else class="ts-text-hint ts-applycfg__builtin-fixed">
          该系统内置类型不经过审批，也没有需要配置的处理流程。
        </div>
      </div>

      <template #footer>
        <el-button @click="builtInVisible = false">关闭</el-button>
        <el-button v-if="canManage" type="primary" :loading="builtInSaving" @click="saveBuiltInConfig">
          保存
        </el-button>
      </template>
    </el-dialog>

    <!-- 「指定人员」用带搜索的人员选择器，而不是把整份名册塞进下拉（规格书按 500 人设计） -->
    <UserSelectDialog
      v-model="builtInUserPickerVisible"
      :multiple="false"
      @confirm="handleBuiltInUserPicked"
    />

    <CustomFormExportDialog v-model="exportVisible" :apply-type="exportTarget" />
  </div>
</template>

<style scoped>
.ts-applycfg__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.ts-applycfg__desc {
  margin: 6px 0 0;
}

.ts-applycfg__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 14px;
  margin-top: 16px;
  min-height: 80px;
}

.ts-applycfg__card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  background: var(--el-bg-color);
  transition: box-shadow 0.15s, border-color 0.15s;
}

.ts-applycfg__card:hover {
  border-color: var(--el-color-primary-light-5);
  box-shadow: 0 2px 10px rgb(0 0 0 / 6%);
}

.ts-applycfg__card-head {
  display: flex;
  align-items: center;
  gap: 12px;
}

.ts-applycfg__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  flex: none;
  border-radius: 10px;
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-size: 20px;
}

.ts-applycfg__card-title h4 {
  margin: 0;
  font-size: 15px;
}

.ts-applycfg__card-title p {
  margin: 2px 0 0;
}

.ts-applycfg__card-tags {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.ts-applycfg__card-foot {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
  border-top: 1px solid var(--el-border-color-lighter);
  padding-top: 8px;
}

.ts-applycfg__builtin {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.ts-applycfg__builtin-fixed {
  padding: 12px 14px;
  border: 1px dashed var(--el-border-color);
  border-radius: 8px;
}

/* 处理流程（V50）：下拉与单选说明同一宽度，避免每换一次通知方式行宽就跳一下 */
.ts-applycfg__handler-select {
  max-width: 420px;
}

.ts-applycfg__inline-hint {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.6;
}

.ts-applycfg__builtin .el-alert + .ts-applycfg__pane-title {
  margin-top: 16px;
}

.ts-applycfg__steps {
  margin-bottom: 18px;
}

.ts-applycfg__step {
  min-height: 240px;
}

.ts-applycfg__step-hint {
  margin: 0 0 10px;
}

.ts-applycfg__split {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 16px;
}

.ts-applycfg__pane {
  min-width: 0;
}

.ts-applycfg__pane-title {
  margin: 0 0 10px;
  font-size: 14px;
  color: var(--el-text-color-regular);
}

/* 标题与右侧「高级表单设计」入口同一行（不要让入口另起一行、把表单字段挤下去） */
.ts-applycfg__pane-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.ts-applycfg__pane-head .ts-applycfg__pane-title {
  margin: 0;
}

.ts-applycfg__icon-picker {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.ts-applycfg__icon-option {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: 8px;
  border: 1px solid var(--el-border-color);
  cursor: pointer;
  font-size: 17px;
}

.ts-applycfg__icon-option.is-active {
  border-color: var(--el-color-primary);
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

@media (max-width: 900px) {
  .ts-applycfg__split {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
