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
import { useResponsive } from '@/composables/useResponsive'
import { useUserStore } from '@/store/user'
import { applyTypeStatusTagType, approvalModeTagType, type ApplyTypeItem } from '@/types/applyType'
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

/** 卡片上的「N 级审批」摘要：直接数流程版本的节点数（APPROVAL 才算一级） */
function levelText(item: ApplyTypeItem): string {
  if (item.approvalMode === 'NONE') {
    return '无需审批'
  }
  if (item.approvalMode === 'GROUP') {
    return '按部门逐级审批'
  }
  return item.approvalFlowName ?? '自定义流程'
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
            <el-tag size="small" effect="plain" :type="approvalModeTagType(item.approvalMode)">
              {{ item.approvalModeLabel ?? item.approvalMode }}
            </el-tag>
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
            <el-button v-if="canManage" link type="danger" size="small" :icon="Delete" @click="removeType(item)">
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
