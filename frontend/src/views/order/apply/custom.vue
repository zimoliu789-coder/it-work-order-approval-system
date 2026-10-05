<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft } from '@element-plus/icons-vue'
import { uploadAttachments } from '@/api/attachment'
import { applyTypeApi } from '@/api/applyType'
import { orderApi } from '@/api/order'
import { useChooseCandidates, type CandidateSlot } from '@/composables/useChooseCandidates'
import FormRenderer from '@/components/FormRenderer.vue'
import { validateFormData, type FormData, type FormErrors } from '@/types/form'
import type { ApplyTypeItem } from '@/types/applyType'
import permissionPolicyApi from '@/api/permissionPolicy'
import type { ApplicableRiskMap } from '@/types/permissionPolicy'
import type { FlowChooseRequirement, FlowPreview, FlowPreviewCandidate } from '@/types/approvalFlow'

/**
 * 提交自定义申请（ / ）
 *
 * 流程：加载申请类型详情（含 schema）→ 动态渲染表单 → 提交（`POST /orders/custom`）→
 * 成功后把本地挑选的附件随单上传（绑定工单 id）→ 跳转「我的工单」。
 *
 * <h2>为什么附件在提交成功后才上传</h2>
 * 附件必须绑定业务记录 id，而工单 id 只有提交成功才拿到 —— 这与项目既有
 * 「申请附件 / 故障照片」完全一致（见 `PendingAttachmentPicker` 的说明）。
 * 因此附件字段的值不写进表单数据，附件通过 (bizType=CUSTOM_ORDER, bizId) 独立关联，
 * 详情页据此单独展示。
 *
 * <h2>：申请人自选审批人（APPLICANT_CHOOSE）</h2>
 * 当类型的审批方式是「自定义审批流程」时，流程里可能有节点的审批人由**申请人自选**。
 * 关键点：条件分支可能**绕过**该节点 —— 被绕过的节点不该要求用户挑人。
 * 因此这里不在前端自己解释流程定义，而是把表单数据发给后端 `flow-preview`，
 * 由它算准命中路径后返回"路径上需要自选的节点"及其可选范围/人数。
 *
 * <p>选择器**只用后端返回的候选人**构建，从构造上就选不出越界的人；
 * 提交时后端仍会重算并校验一次（前端只是体验，服务端是唯一事实来源）。
 *
 * <p>W4-D 起候选池**分两段取**：预览下发的 `candidates` 只是首屏子集
 * （范围 = 「全部员工」时不可能整份下发），用户键入关键字时再走
 * `GET /apply-types/{id}/choose-candidates` 分页搜索补齐 —— 截断与搜索必须同时到位，
 * 只截断会让排在首屏之后的人*真的选不到*（那是功能回归，不是优化）。
 *
 * <h2>无审批模式</h2>
 * 若该类型的审批方式为「无需审批」，提交后工单直接进入「已完成」，不会出现在审批待办里 ——
 * 页面会对此给出明确提示，避免用户以为提交失败。
 */
const route = useRoute()
const router = useRouter()

const typeId = computed(() => Number(route.params.typeId))

const loading = ref(false)
const submitting = ref(false)
const applyType = ref<ApplyTypeItem | null>(null)
/** 因越权 / 类型不存在 / 已停用而无法提交 */
const unavailableReason = ref('')

const formData = ref<FormData>({})
const pendingFiles = ref<Record<string, File[]>>({})
/** 是否已尝试提交过（决定错误提示是否显示） */
const submitted = ref(false)

const errors = computed<FormErrors>(() =>
  submitted.value ? validateFormData(applyType.value?.schema, formData.value) : {}
)

const noApproval = computed(() => applyType.value?.approvalMode === 'NONE')
/** 是否走自定义审批流程（决定是否渲染"申请人自选审批人"区块） */
const flowMode = computed(() => applyType.value?.approvalMode === 'FLOW')

// ----------------------------------------------------------------------
// 审批路径预览 + 申请人自选
// ----------------------------------------------------------------------

const flowPreview = ref<FlowPreview | null>(null)
/** 流程节点 key → 申请人所选审批人 user_id 列表 */
const selections = ref<Record<string, number[]>>({})

/** 上一次预览的「自选节点」定义：候选池被截断时无法比对成员，只能靠它判断范围是否变化 */
let previousRequirements: FlowChooseRequirement[] = []

/** 候选池状态（首屏来自预览子集，键入关键字走分页搜索）——  · W4-D */
const choose = useChooseCandidates({ applyTypeId: () => typeId.value })

/** 预览请求序号：丢弃过期响应，避免"慢的旧请求覆盖了快的新请求" */
let previewSeq = 0
let previewTimer: ReturnType<typeof setTimeout> | null = null

/**
 * 用最新的预览结果同步选择区。
 *
 * <p>刻意**保留**用户在仍命中的节点上已做的选择（而不是整体清空）——
 * 用户改一个不相关的字段时，已挑好的审批人不该被无端清掉。
 *
 * <p><b>候选池被截断时（W4-D）不能再按"这个人是否出现在 candidates 里"剔除</b>：
 * `candidates` 只是首屏若干条，排在后面的人不在里面，会被误判成"已不在范围内"而
 * **凭空清掉用户合法的选择**。因此按「候选池是否完整 / 范围是否变化」分三档处理；
 * 截断且范围未变时保留已选，交给服务端在提交时重算并给出准确提示。
 */
function syncSelections(preview: FlowPreview): void {
  const previous = selections.value
  const previousByKey = new Map(previousRequirements.map((item) => [item.nodeKey, item]))
  const next: Record<string, number[]> = {}
  for (const requirement of preview.chooseRequirements) {
    const picked = previous[requirement.nodeKey] ?? []
    if (picked.length === 0) {
      next[requirement.nodeKey] = []
      continue
    }
    const was = previousByKey.get(requirement.nodeKey)
    const sameRange =
      was !== undefined &&
      was.scope === requirement.scope &&
      (was.scopeValue ?? null) === (requirement.scopeValue ?? null)
    if (!requirement.candidatesTruncated) {
      // 候选池完整 ⇒ 能精确判定"这个人是否还在范围内"，维持原有剔除语义
      const allowed = new Set(requirement.candidates.map((candidate) => candidate.id))
      next[requirement.nodeKey] = picked.filter((id) => allowed.has(id))
    } else if (sameRange) {
      // 截断 + 范围未变 ⇒ 成员关系不可知，保留已选（提交时服务端重算兜底）
      next[requirement.nodeKey] = picked
    } else {
      // 截断 + 范围已变 ⇒ 原选择不可信，清空让用户重选
      next[requirement.nodeKey] = []
    }
  }
  selections.value = next
  previousRequirements = preview.chooseRequirements
}

async function refreshPreview(): Promise<void> {
  if (!flowMode.value) {
    flowPreview.value = null
    selections.value = {}
    return
  }
  const seq = ++previewSeq
  try {
    const preview = await applyTypeApi.flowPreview(typeId.value, formData.value)
    if (seq !== previewSeq) {
      return
    }
    flowPreview.value = preview
    choose.seed(preview.chooseRequirements)
    syncSelections(preview)
  } catch {
    if (seq === previewSeq) {
      flowPreview.value = null
    }
  }
}

function schedulePreview(): void {
  if (previewTimer) {
    clearTimeout(previewTimer)
  }
  previewTimer = setTimeout(() => {
    void refreshPreview()
  }, 350)
}

watch(formData, schedulePreview, { deep: true })

onBeforeUnmount(() => {
  if (previewTimer) {
    clearTimeout(previewTimer)
  }
})

const chooseRequirements = computed<FlowChooseRequirement[]>(() => flowPreview.value?.chooseRequirements ?? [])

function requirementError(requirement: FlowChooseRequirement): string {
  const picked = selections.value[requirement.nodeKey] ?? []
  if (picked.length < requirement.minCount) {
    return `请至少选择 ${requirement.minCount} 人`
  }
  if (picked.length > requirement.maxCount) {
    return `最多只能选择 ${requirement.maxCount} 人`
  }
  return ''
}

const chooseErrors = computed(() => chooseRequirements.value.map(requirementError).filter((item) => item !== ''))

/** 下拉可见候选：搜索结果 + 已选项补齐（远程搜索换掉 options 后，已选项仍要显示姓名而不是数字 id） */
function candidateOptions(requirement: FlowChooseRequirement): FlowPreviewCandidate[] {
  return choose.optionsFor(requirement.nodeKey, selections.value[requirement.nodeKey] ?? [])
}

/** 该节点的候选池状态（总数 / 是否被截断 / 加载中） */
function candidateSlot(requirement: FlowChooseRequirement): CandidateSlot {
  return choose.slotOf(requirement.nodeKey)
}

/** el-select 远程搜索回调 */
function handleCandidateSearch(requirement: FlowChooseRequirement, keyword: string): void {
  void choose.search(requirement.nodeKey, keyword)
}

/** 附件字段总量（用于提交后判断是否需要上传） */
const pendingFileCount = computed(() =>
  Object.values(pendingFiles.value).reduce((sum, files) => sum + files.length, 0)
)

async function load(): Promise<void> {
  loading.value = true
  unavailableReason.value = ''
  try {
    const data = await applyTypeApi.detail(typeId.value)
    if (data.status !== 'ENABLED') {
      unavailableReason.value = `「${data.typeName}」已停用，暂时无法提交`
      applyType.value = null
      return
    }
    applyType.value = data
    submitted.value = false
    formData.value = {}
    pendingFiles.value = {}
    flowPreview.value = null
    selections.value = {}
    previousRequirements = []
    choose.reset()
    if (data.approvalMode === 'FLOW') {
      await refreshPreview()
    }
  } catch {
    unavailableReason.value = '该申请类型不存在，或你不在它的可提交范围内'
    applyType.value = null
  } finally {
    loading.value = false
  }
}

function goApplySelect(): void {
  void router.push('/order/apply')
}

async function handleSubmit(): Promise<void> {
  submitted.value = true
  const currentErrors = validateFormData(applyType.value?.schema, formData.value)
  const firstError = Object.values(currentErrors)[0]
  if (firstError) {
    ElMessage.warning(firstError)
    return
  }
  // 自选审批人：提交前先做一次本地校验（服务端仍会重算，这里只是省一次往返）
  if (flowMode.value) {
    await refreshPreview()
    const missing = chooseRequirements.value.find((requirement) => requirementError(requirement) !== '')
    if (missing) {
      ElMessage.warning(`「${missing.nodeName}」${requirementError(missing)}`)
      return
    }
  }
  submitting.value = true
  try {
    const orderId = await orderApi.createCustom({
      applyTypeId: typeId.value,
      formData: formData.value,
      // 非流程模式显式传 null：不把无关数据带到服务端
      approverSelections: flowMode.value ? selections.value : null
    })
    // 随单附件：绑定工单 id 上传（附件可选，失败不阻断主流程）
    let attached = 0
    if (orderId != null && pendingFileCount.value > 0) {
      const allFiles = Object.values(pendingFiles.value).flat()
      attached = await uploadAttachments('CUSTOM_ORDER', orderId, allFiles)
    }
    ElMessage.success(
      noApproval.value
        ? `申请已提交并直接完成${attached > 0 ? `，已上传 ${attached} 个附件` : ''}`
        : `申请已提交，等待审批${attached > 0 ? `，已上传 ${attached} 个附件` : ''}`
    )
    await router.push('/order/mine')
  } catch {
    // 表单校验未通过 / 类型已停用 / 无提交权限 / 自选审批人不合规等由请求层统一提示
  } finally {
    submitting.value = false
  }
}

/**
 * 「权限码 → 风险等级」映射：给权限申请表单的高危项加提示。
 *
 * <p>只要求登录即可读取 —— 普通员工没有 role:view，但正是**他们**需要看到
 * 「这个权限要两级审批」。读失败不阻塞申请（退化成没有标记）。
 */
const permissionRisks = ref<ApplicableRiskMap>({})

async function loadPermissionRisks(): Promise<void> {
  try {
    permissionRisks.value = await permissionPolicyApi.applicableRisks()
  } catch {
    permissionRisks.value = {}
  }
}

onMounted(() => {
  void loadPermissionRisks()
  void load()
})
</script>

<template>
  <div class="ts-page">
    <section class="ts-card">
      <div class="ts-custom-apply__back" role="button" tabindex="0" @click="goApplySelect" @keyup.enter="goApplySelect">
        <el-icon><ArrowLeft /></el-icon>
        <span>返回重选类型</span>
      </div>

      <div v-loading="loading">
        <el-alert
          v-if="unavailableReason"
          type="warning"
          :closable="false"
          show-icon
          :title="unavailableReason"
          class="ts-mt-16"
        />

        <template v-else-if="applyType">
          <h3 class="ts-custom-apply__title">{{ applyType.typeName }}</h3>
          <p class="ts-text-secondary ts-custom-apply__desc">
            {{ applyType.description || '请按以下字段填写申请内容。' }}
            <template v-if="noApproval">该类型无需审批，提交后直接进入「已完成」。</template>
            <template v-else-if="flowMode">
              提交后按「{{ flowPreview?.approvalFlowName ?? applyType.approvalFlowName ?? '自定义审批流程' }}」流转，
              审批路径会随你填写的内容确定。
            </template>
            <template v-else>提交后按你所在部门的审批流逐级审批，全部通过即完成。</template>
          </p>

          <div class="ts-custom-apply__form">
            <FormRenderer
              v-model="formData"
              v-model:pending-files="pendingFiles"
              :schema="applyType.schema"
              :errors="errors"
              :risk-codes="permissionRisks"
            />
          </div>

          <!-- 审批路径预览（：让申请人一眼看到"会走到谁那里"） -->
          <template v-if="flowMode && flowPreview?.flowUsed && flowPreview.nodes.length > 0">
            <h4 class="ts-custom-apply__section">审批路径预览</h4>
            <div class="ts-custom-apply__path">
              <template v-for="(node, index) in flowPreview.nodes" :key="`${node.nodeKey}-${index}`">
                <span
                  class="ts-custom-apply__path-node"
                  :class="{ 'is-skipped': !node.onPath }"
                  :title="node.conditionDesc ?? ''"
                >
                  {{ node.nodeName }}
                </span>
                <span v-if="index < flowPreview.nodes.length - 1" class="ts-custom-apply__path-arrow">→</span>
              </template>
            </div>
            <p class="ts-text-hint">
              灰色节点表示按你当前填写的内容<strong>不会经过</strong>；改动申请内容后路径会实时更新。
            </p>
          </template>

          <!-- 申请人自选审批人（仅"命中路径上的、配置了申请人自选"的节点） -->
          <template v-if="flowMode && chooseRequirements.length > 0">
            <h4 class="ts-custom-apply__section">请选择审批人</h4>
            <p class="ts-text-hint ts-custom-apply__choose-desc">
              按你填写的内容，本流程需要你为下列节点指定审批人。可选范围与人数据流程配置固定，
              提交后将随审批快照一并冻结。
            </p>
            <div
              v-for="requirement in chooseRequirements"
              :key="requirement.nodeKey"
              class="ts-custom-apply__choose"
            >
              <div class="ts-custom-apply__choose-head">
                <span class="ts-custom-apply__choose-name">{{ requirement.nodeName }}</span>
                <el-tag size="small" effect="plain">{{ requirement.signTypeLabel }}</el-tag>
                <span class="ts-text-hint">
                  {{ requirement.scopeLabel }} · 需选 {{ requirement.minCount }}–{{ requirement.maxCount }} 人
                </span>
              </div>
              <el-select
                v-model="selections[requirement.nodeKey]"
                multiple
                filterable
                remote
                reserve-keyword
                :remote-method="(keyword) => handleCandidateSearch(requirement, keyword)"
                :loading="candidateSlot(requirement).loading"
                class="ts-custom-apply__choose-select"
                placeholder="从可选范围中选择审批人"
              >
                <el-option
                  v-for="candidate in candidateOptions(requirement)"
                  :key="candidate.id"
                  :label="candidate.name"
                  :value="candidate.id"
                />
              </el-select>
              <p v-if="candidateSlot(requirement).truncated" class="ts-text-hint ts-custom-apply__choose-hint">
                <template v-if="candidateSlot(requirement).keyword">
                  匹配 {{ candidateSlot(requirement).total }} 人，当前显示前
                  {{ candidateSlot(requirement).options.length }} 人，继续输入姓名可缩小范围。
                </template>
                <template v-else>
                  可选范围共 {{ candidateSlot(requirement).total }} 人，此处仅列出首屏，输入姓名可搜索更多。
                </template>
              </p>
              <div v-if="submitted && requirementError(requirement)" class="ts-custom-apply__choose-error">
                {{ requirementError(requirement) }}
              </div>
            </div>
          </template>

          <div class="ts-custom-apply__submit">
            <el-button @click="goApplySelect">返回</el-button>
            <el-button
              type="primary"
              :loading="submitting"
              :disabled="flowMode && chooseErrors.length > 0 && submitted"
              @click="handleSubmit"
            >
              {{ noApproval ? '提交（无需审批）' : '提交申请' }}
            </el-button>
          </div>
        </template>
      </div>
    </section>
  </div>
</template>

<style scoped>
.ts-custom-apply__back {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  margin-bottom: 4px;
  font-size: 13px;
  color: var(--el-color-primary);
  cursor: pointer;
  user-select: none;
}

.ts-custom-apply__back:hover,
.ts-custom-apply__back:focus {
  opacity: 0.8;
  outline: none;
}

.ts-custom-apply__title {
  margin: 8px 0 0;
  font-size: 16px;
  font-weight: 500;
}

.ts-custom-apply__desc {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.7;
}

.ts-custom-apply__form {
  max-width: 880px;
  margin-top: 16px;
}

.ts-custom-apply__section {
  max-width: 880px;
  margin: 20px 0 8px;
  font-size: 14px;
  font-weight: 500;
}

.ts-custom-apply__path {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  max-width: 880px;
  padding: 10px 12px;
  border: 1px solid var(--ts-border);
  border-radius: 8px;
  background: #fbfcfe;
}

.ts-custom-apply__path-node {
  font-size: 13px;
}

.ts-custom-apply__path-node.is-skipped {
  color: var(--el-text-color-disabled);
  text-decoration: line-through;
}

.ts-custom-apply__path-arrow {
  color: var(--el-text-color-disabled);
}

.ts-custom-apply__choose-desc {
  max-width: 880px;
}

.ts-custom-apply__choose {
  max-width: 880px;
  padding: 12px;
  margin-bottom: 12px;
  border: 1px solid var(--ts-border);
  border-radius: 10px;
  background: #fbfcfe;
}

.ts-custom-apply__choose-head {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
}

.ts-custom-apply__choose-name {
  font-weight: 500;
}

.ts-custom-apply__choose-select {
  width: 100%;
}

.ts-custom-apply__choose-hint {
  margin: 6px 0 0;
}

.ts-custom-apply__choose-error {
  margin-top: 6px;
  font-size: 12px;
  color: var(--el-color-danger);
}

.ts-custom-apply__submit {
  display: flex;
  gap: 12px;
  justify-content: flex-end;
  max-width: 880px;
  margin-top: 8px;
}

@media (max-width: 767px) {
  .ts-custom-apply__submit .el-button {
    flex: 1 1 0;
  }
}
</style>
