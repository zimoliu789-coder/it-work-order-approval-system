<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, Clock, Document, Promotion } from '@element-plus/icons-vue'
import { approvalFlowApi } from '@/api/approvalFlow'
import { departmentApi } from '@/api/department'
import { formTemplateApi } from '@/api/form'
import { roleApi } from '@/api/role'
import FlowDesigner from '@/components/FlowDesigner.vue'
import { useResponsive } from '@/composables/useResponsive'
import { useUserStore } from '@/store/user'
import { fieldTypeMeta, isDataFieldType, type FormSchema } from '@/types/form'
import type { RoleOption } from '@/types/permission'
import type { DepartmentOption } from '@/types/department'
import {
  FLOW_SCOPE_OPTIONS,
  applyFlowDesignMeta,
  approverRuleLabel,
  flowScopeForbiddenRuleTypes,
  flowStatusTagType,
  type ApprovalFlowDetail,
  type ApprovalFlowVersionItem,
  type FlowDefinition,
  type FlowFieldOption,
  type FlowScopeCode
} from '@/types/approvalFlow'

/**
 * 审批流程设计器页（ · ）
 *
 * 承载「编辑流程基本信息 + 画流程 + 保存草稿 / 发布版本 + 版本历史」。
 * 与一期 `apply-type/designer.vue` 同构：设计器是可复用组件，这一页是薄壳，
 * 负责"取数据 / 调接口 / 报错"，不关心流程的结构细节。
 *
 * <h2>"参考表单"是什么、不是什么</h2>
 * 流程模板是**独立复用**的资产，设计时并不绑定具体表单。但条件分支要选字段、
 * 「表单人员字段」审批要选人员字段 —— 没有字段候选，作者就得手敲 key。
 * 因此这里提供一个**纯设计期**的「参考表单」下拉：选中某个已发布的表单版本后，
 * 把它<b>不含布局元素</b>的字段作为候选项喂给设计器（下拉 + 本地预检）。
 * 它**不写入流程定义**，也不改变后端语义 —— 字段是否真实存在，
 * 最终由后端在「申请类型」绑定本流程时强校验（设计文档 ）。
 */
const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const { isMobile } = useResponsive()

const canManage = computed(() => userStore.hasPerm('approval_flow:manage'))

const flowId = computed(() => Number(route.params.flowId))

const loading = ref(false)
const saving = ref(false)
const detail = ref<ApprovalFlowDetail | null>(null)
const versions = ref<ApprovalFlowVersionItem[]>([])
const versionsVisible = ref(false)

const form = reactive({ flowCode: '', flowName: '', description: '' })
const definition = ref<FlowDefinition>({ start: '', nodes: [] })

// 参考数据（下拉候选项）—— 任何一项加载失败都退化为空数组，不影响主流程
const roleOptions = ref<RoleOption[]>([])
const departmentOptions = ref<DepartmentOption[]>([])
/** 最终处理部门候选（全系统只有一个：IT运维组） */
const handlerDepartmentOptions = ref<DepartmentOption[]>([])

// 参考表单
interface RefFormOption {
  /** 表单模板版本 id */
  value: number
  label: string
}
const refFormOptions = ref<RefFormOption[]>([])
const refFormVersionId = ref<number | null>(null)
const referenceFields = ref<FlowFieldOption[]>([])

/**
 * 校验业务域（M1）：**只作用于发布前预检，不写入流程定义**。
 *
 * 为什么不持久化到流程模板：模板是**可复用资产**，同一份定义完全可能既被自定义申请类型
 * 引用（自定义域，带表单），又被部门引用（借用域，无表单）。把域固化成模板属性
 * 会让"同一份定义只能服务于一种业务"这一假设悄悄成立，反而限制了复用。
 *
 * 因此域是**本次校验的上下文**：作者知道这条流程将要绑给谁，就选哪个域。
 * 默认 CUSTOM（既有流程都是自定义表单流程，缺省即正确）。
 */
const validateScope = ref<FlowScopeCode>('CUSTOM')

/**
 * 当前校验域下**不可用**的审批人来源（ · W4-D / C8）。
 *
 * 读的是 `types/approvalFlow` 的统一入口：接口生效时用后端下发的禁用集，否则用本地兜底。
 * **不要**在这里重新硬编码一份列表 —— 之前正是这份硬编码与后端内联的 if 判定各自演化，
 * 造出「设计器让配、后端不让发」这种最难排查的组合。
 */
const forbiddenRuleTypes = computed(() => flowScopeForbiddenRuleTypes(validateScope.value))

/** 是否存在未发布草稿（发布按钮可用性的依据之一） */
const hasDraft = computed(() => detail.value?.draftVersionId != null)

const statusText = computed(() => {
  if (!detail.value) {
    return ''
  }
  if (detail.value.draftVersionId != null) {
    return '当前为草稿版本（未发布）；发布后可被申请类型引用'
  }
  if (detail.value.publishedVersionNo != null) {
    return `当前展示最新已发布版本 v${detail.value.publishedVersionNo}；继续编辑会另存为新草稿`
  }
  return '尚未发布任何版本'
})

async function loadDetail(): Promise<void> {
  loading.value = true
  try {
    const data = await approvalFlowApi.detail(flowId.value)
    detail.value = data
    form.flowCode = data.flowCode
    form.flowName = data.flowName
    form.description = data.description ?? ''
    definition.value = data.definition
      ? { start: data.definition.start ?? '', nodes: (data.definition.nodes ?? []).map((node) => ({ ...node })) }
      : { start: '', nodes: [] }
  } catch {
    detail.value = null
  } finally {
    loading.value = false
  }
}

async function loadVersions(): Promise<void> {
  try {
    versions.value = await approvalFlowApi.versions(flowId.value)
  } catch {
    versions.value = []
  }
}

async function openVersions(): Promise<void> {
  versionsVisible.value = true
  await loadVersions()
}

/** 参考数据：角色 / 部门 / 最终处理部门 / 参考表单版本 */
async function loadReferenceData(): Promise<void> {
  try {
    roleOptions.value = await roleApi.options()
  } catch {
    roleOptions.value = []
  }
  try {
    // 部门选项只拉一次、两处复用：`handlerDepartmentOptions` 是从中筛出
    // `handlerGroup=true` 的那一个（最终处理部门）。分两次请求会拿到两份
    // 可能不一致的快照 —— 部门刚被改名时两处显示的就不是同一个名字。
    const options = await departmentApi.options()
    departmentOptions.value = options
    handlerDepartmentOptions.value = options.filter((dept) => dept.handlerGroup)
  } catch {
    departmentOptions.value = []
    handlerDepartmentOptions.value = []
  }
  try {
    const templates = await formTemplateApi.list()
    refFormOptions.value = templates
      .filter((template) => template.latestPublishedVersionId != null)
      .map((template) => ({
        value: template.latestPublishedVersionId as number,
        label: `${template.templateName} · v${template.latestVersionNo ?? '-'}`
      }))
  } catch {
    refFormOptions.value = []
  }
}

/** 把表单 schema 转成设计器能用的字段候选（剔除布局元素与无 key 的项） */
function toFieldOptions(schema: FormSchema | null | undefined): FlowFieldOption[] {
  const fields = schema?.fields ?? []
  const result: FlowFieldOption[] = []
  for (const field of fields) {
    if (!isDataFieldType(field.type) || !field.key) {
      continue
    }
    result.push({
      value: field.key,
      label: field.label || field.key,
      kind: fieldTypeMeta(field.type)?.valueKind ?? 'NONE'
    })
  }
  return result
}

async function onRefFormChange(): Promise<void> {
  if (refFormVersionId.value == null) {
    referenceFields.value = []
    return
  }
  try {
    const version = await formTemplateApi.version(refFormVersionId.value)
    referenceFields.value = toFieldOptions(version.schema)
    ElMessage.success(`已加载参考表单字段 ${referenceFields.value.length} 个`)
  } catch {
    referenceFields.value = []
  }
}

async function handleSave(): Promise<void> {
  if (!canManage.value) {
    return
  }
  const flowName = form.flowName.trim()
  if (!flowName) {
    ElMessage.warning('请填写流程名称')
    return
  }
  saving.value = true
  try {
    await approvalFlowApi.update(flowId.value, {
      flowCode: form.flowCode,
      flowName,
      description: form.description.trim() || null,
      definition: definition.value
    })
    ElMessage.success('草稿已保存')
    await loadDetail()
  } catch {
    // 由请求层统一提示
  } finally {
    saving.value = false
  }
}

/**
 * 发布前置校验（M4a）：**以后端结果为准**。
 *
 * <p>设计器里的 `validateFlowForPublish` 只是"即时预检"（体验好、零延迟），
 * 但它与后端 `FlowDefinitionValidator` 是两套实现，长期必然漂移。
 * 因此发布前必须把定义发到后端再校验一次，并以**后端返回的问题清单**作为最终准入 ——
 * 这样"前端说没问题、点发布却被拒"的情况会在这里被提前、完整地展示，
 * 而不是等用户点了发布才收到一条后端错误。
 *
 * <p>M1 补充：必须带上 {@link validateScope}。同一个设计器既要产出自定义表单流程、
 * 也要产出借用单流程，两者禁用规则集不同；不带域校验会让借用流程"预检通过、发布被拒"，
 * 正是 M4a 要消灭的口径不一致。
 *
 * @returns 通过返回 true；否则弹出问题清单并返回 false
 */
async function preflightValidate(): Promise<boolean> {
  try {
    const result = await approvalFlowApi.validate(
      definition.value,
      refFormVersionId.value,
      validateScope.value
    )
    if (result.valid) {
      return true
    }
    const list = result.problems.map((item) => `· ${item}`).join('\n')
    await ElMessageBox.alert(
      `服务端校验发现 ${result.problems.length} 个问题，请修正后再发布：\n\n${list}`,
      '流程校验未通过',
      { type: 'warning', customClass: 'ts-flow-problems' }
    )
    return false
  } catch {
    // 校验接口本身失败（网络 / 无权限）：不阻断发布 ——
    // 后端 publish() 仍是最终严格闸门，用户不会因为"预检接口挂了"而完全无法发布
    return true
  }
}

/**
 * 切换校验域时的**即时提示**（不阻断，仅提前告知）。
 *
 * 唯一真闸门是后端校验；这里只是把"已经配了该域不支持的规则"这一情况
 * 在切换的当下就说清楚，而不是等到点发布才弹一屏问题。
 */
const scopeWarnings = computed(() => {
  const forbidden = forbiddenRuleTypes.value
  if (forbidden.length === 0) {
    return []
  }
  const hits: string[] = []
  for (const node of definition.value.nodes ?? []) {
    for (const rule of node.approverRules ?? []) {
      if (forbidden.includes(rule.type)) {
        hits.push(`节点「${node.name || node.key}」使用了「${approverRuleLabel(rule.type)}」`)
      }
    }
  }
  return hits
})

/** 发布会先落一次草稿再发布：避免"改了流程却忘了保存"导致发布的是旧内容 */
async function handlePublish(): Promise<void> {
  if (!canManage.value) {
    return
  }
  const flowName = form.flowName.trim()
  if (!flowName) {
    ElMessage.warning('请填写流程名称')
    return
  }
  try {
    await ElMessageBox.confirm(
      '发布后该版本将被冻结、不可再修改，且可被申请类型引用。确认发布？',
      '发布流程版本',
      { type: 'warning' }
    )
  } catch {
    return
  }
  saving.value = true
  try {
    // M4a：以服务端校验为准（本地预检只是即时提示，不作为最终准入）
    if (!(await preflightValidate())) {
      return
    }
    await approvalFlowApi.update(flowId.value, {
      flowCode: form.flowCode,
      flowName,
      description: form.description.trim() || null,
      definition: definition.value
    })
    const versionId = await approvalFlowApi.publish(flowId.value)
    ElMessage.success(`已发布版本（id ${versionId}），可用于绑定申请类型`)
    await loadDetail()
  } catch {
    // 校验未通过时后端返回定位信息，请求层已提示
  } finally {
    saving.value = false
  }
}

function goBack(): void {
  void router.push('/system/approval-flow')
}

/**
 * 拉取设计器约束元数据（ · W4-D / C8）。
 *
 * **失败不影响可用性**：拿不到就整体回退本地兜底默认（`applyFlowDesignMeta(null)` 的语义），
 * 设计器照常编辑，只是「接口权威值」缺席。发布结论始终以后端为准 ——
 * 与 M4a 定下的分工一致（前端只做即时预检）。
 */
async function loadDesignMeta(): Promise<void> {
  try {
    applyFlowDesignMeta(await approvalFlowApi.designMeta())
  } catch {
    applyFlowDesignMeta(null)
  }
}

onMounted(async () => {
  await loadDetail()
  await loadReferenceData()
  await loadDesignMeta()
})
</script>

<template>
  <div class="ts-page">
    <section class="ts-card">
      <div class="ts-designer-page__head">
        <div class="ts-designer-page__head-main">
          <el-button link :icon="ArrowLeft" @click="goBack">返回审批流程模板</el-button>
          <h3 class="ts-designer-page__title">
            {{ detail?.flowName ?? '审批流程设计器' }}
            <el-tag v-if="detail" size="small" effect="plain" :type="flowStatusTagType(detail.status)">
              {{ detail.statusLabel ?? detail.status }}
            </el-tag>
          </h3>
          <p class="ts-text-hint">{{ statusText }}</p>
        </div>
        <el-button size="small" plain :icon="Clock" @click="openVersions">版本历史</el-button>
      </div>

      <el-form
        v-loading="loading"
        class="ts-designer-page__meta"
        label-width="88px"
        :label-position="isMobile ? 'top' : 'right'"
      >
        <el-form-item label="流程名称" required>
          <el-input v-model="form.flowName" :disabled="!canManage" maxlength="64" />
        </el-form-item>
        <el-form-item label="流程编码">
          <el-input v-model="form.flowCode" disabled />
          <div class="ts-text-hint">编码一经创建不可修改（申请类型与版本历史都以此定位）。</div>
        </el-form-item>
        <el-form-item label="流程说明">
          <el-input
            v-model="form.description"
            :disabled="!canManage"
            type="textarea"
            :rows="2"
            maxlength="255"
            show-word-limit
          />
        </el-form-item>
        <el-form-item label="参考表单">
          <el-select
            v-model="refFormVersionId"
            class="ts-designer-page__field"
            clearable
            filterable
            placeholder="选填：选一个表单版本，用于给出字段候选"
            @change="onRefFormChange"
          >
            <el-option v-for="option in refFormOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
          <div class="ts-text-hint">
            <el-icon><Document /></el-icon>
            仅作设计期的字段提示（用于条件字段 / 表单人员字段下拉），<b>不写入流程定义</b>。
            字段是否存在，最终由后端在你把本流程绑定到申请类型时强校验。
          </div>
        </el-form-item>
        <el-form-item label="校验业务域">
          <el-radio-group v-model="validateScope" :disabled="!canManage">
            <el-radio-button v-for="option in FLOW_SCOPE_OPTIONS" :key="option.value" :value="option.value">
              {{ option.label }}
            </el-radio-button>
          </el-radio-group>
          <div class="ts-text-hint">
            决定「条件字段候选范围」与「禁用的审批人来源」。借用单流程禁用<b>表单人员字段</b>与
            <b>申请人自选</b>（借用提交页没有表单人员字段，也没有选人器）。
            此项<b>不写入流程定义</b>——同一条流程模板能绑给多个对象，域属于校验上下文，发布时以绑定对象的域为准。
          </div>
          <el-alert
            v-if="scopeWarnings.length > 0"
            type="warning"
            :closable="false"
            show-icon
            class="ts-designer-page__scope-warn"
          >
            <template #title>当前定义与所选校验域不兼容，按此发布会被后端拒绝：</template>
            <ul class="ts-designer-page__scope-warn-list">
              <li v-for="(item, index) in scopeWarnings" :key="index">{{ item }}</li>
            </ul>
          </el-alert>
        </el-form-item>
      </el-form>

      <el-alert
        v-if="hasDraft && canManage"
        type="warning"
        :closable="false"
        show-icon
        title="当前有未发布的草稿改动，点击右下角「发布版本」后才会生成可被申请类型引用的新版本。"
        class="ts-designer-page__alert"
      />

      <FlowDesigner
        v-model="definition"
        :readonly="!canManage"
        :saving="saving"
        :role-options="roleOptions"
        :department-options="departmentOptions"
        :handler-department-options="handlerDepartmentOptions"
        :field-options="referenceFields"
        :forbidden-rule-types="forbiddenRuleTypes"
        class="ts-mt-16"
        @save="handleSave"
        @publish="handlePublish"
      />
    </section>

    <!-- 版本历史 -->
    <el-dialog v-model="versionsVisible" title="版本历史" :width="isMobile ? '94%' : '620px'">
      <el-table :data="versions" border size="small">
        <el-table-column prop="versionNo" label="版本" width="80">
          <template #default="{ row }">v{{ row.versionNo }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.draft" size="small" type="warning" effect="plain">草稿</el-tag>
            <el-tag v-else size="small" type="success" effect="plain">已发布</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="审批节点" width="90">
          <template #default="{ row }">{{ row.nodeCount ?? 0 }}</template>
        </el-table-column>
        <el-table-column prop="publishedAt" label="发布时间" min-width="160">
          <template #default="{ row }">{{ row.publishedAt ?? '—' }}</template>
        </el-table-column>
      </el-table>
      <p class="ts-text-hint ts-mt-16">
        <el-icon><Promotion /></el-icon>
        已发布的版本不可修改；被申请类型引用的版本会决定该类型新提交工单的审批口径，
        历史工单按提交时的快照回显，不受后续改版影响。
      </p>
    </el-dialog>
  </div>
</template>

<style scoped>
.ts-designer-page__head {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: flex-start;
  justify-content: space-between;
}

.ts-designer-page__head-main {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.ts-designer-page__title {
  display: flex;
  gap: 8px;
  align-items: center;
  margin: 0;
  font-size: 16px;
  font-weight: 500;
}

.ts-designer-page__meta {
  max-width: 720px;
  margin-top: 12px;
}

.ts-designer-page__field {
  width: 100%;
}

.ts-designer-page__alert {
  margin-top: 8px;
}

.ts-designer-page__scope-warn {
  margin-top: 8px;
}

.ts-designer-page__scope-warn-list {
  margin: 4px 0 0;
  padding-left: 18px;
  line-height: 1.6;
}
</style>
