<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Delete, Plus } from '@element-plus/icons-vue'
import UserSelectDialog from '@/components/UserSelectDialog.vue'
import roleApi from '@/api/role'
import { FLOW_MAX_TIME_LIMIT_HOURS, type FlowDefinition, type FlowFieldOption } from '@/types/approvalFlow'
import type { RoleItem } from '@/types/permission'
import type { UserOption } from '@/types/user'
import {
  CONDITION_OPERATORS,
  SIMPLE_RULE_OPTIONS,
  buildFlowDefinition,
  emptyLinearRow,
  linearProblems,
  parseFlowDefinition,
  type LinearRow,
  type SimpleRuleType
} from '@/utils/linearFlow'

/**
 * 钉钉式「线性」审批流程编辑器（ 项目 5）—— 只负责界面
 *
 * <h2>它解决的是什么问题</h2>
 * 原来的 FlowDesigner 是**节点 + 连线**的图式设计器：能表达会签 / 或签 / 抄送 /
 * 条件分支 / 加时 / 加签 / 上一节点指定 —— 能力齐全，但要求配置者懂「图」。
 * 说明：「页面从上到下列节点，每个节点一行，就是一条线 …… 不用画节点连线，
 * 非技术人员能直接配」。
 *
 * <h2>分工</h2>
 * 「行模型 ↔ FlowDefinition」的转换在 utils/linearFlow（纯函数、有单测），
 * 本组件只管渲染与交互。转换逻辑留在这里就只能靠挂载组件来测，而它的 bug（少一个分支、
 * 条件值按字符串比较、合流点接错）在界面**看不出来**，只会让工单走错分支。
 *
 * <h2>读不出来的定义不静默改写</h2>
 * 若传入的流程含抄送节点、多分支条件、或简化模型表达不了的审批人来源（会签、上一节点指定、
 * 申请人自选……），组件进入 `unsupported` 状态：只展示提示 + 跳转完整设计器的入口，
 * **不允许在线性视图里保存**。静默降级会把一个能用的流程裁成残的，而这种丢失在界面上看不出来。
 */

const props = withDefaults(
  defineProps<{
    modelValue: FlowDefinition | null
    /** 条件字段候选（来自绑定的表单；为空时退化为手填字段名） */
    fields?: FlowFieldOption[] | null
    readonly?: boolean
    /** 字段 key → 中文名（用于条件文案；不传则原样显示 key） */
    fieldLabelOf?: ((key: string) => string) | null
  }>(),
  { fields: null, readonly: false, fieldLabelOf: null }
)

const emit = defineEmits<{
  'update:modelValue': [FlowDefinition]
  /** 用户在「不可简化」提示里点了「打开完整流程设计器」 */
  'request-advanced': []
}>()

const rows = ref<LinearRow[]>([])
const unsupported = ref<string | null>(null)
/** 是否处于「读不出来」状态 —— 模板据此整块替换为提示 */
const blocked = computed(() => unsupported.value !== null)
/** 最近一次「由本组件发出」的定义签名，用于跳过自身触发的回灌（否则会无限重建） */
let lastEmitted = ''

/** 角色下拉（指定角色用） */
const roles = ref<RoleItem[]>([])
const roleOptions = computed(() => roles.value.map((role) => ({ value: role.roleCode, label: role.roleName })))

/** 选人弹窗：记录「正在为哪一行挑人」，避免开多个弹窗 */
const userPickerVisible = ref(false)
const userPickerTarget = ref<{ uid: string; useCond: boolean } | null>(null)

async function loadRoles(): Promise<void> {
  if (roles.value.length > 0) {
    return
  }
  try {
    roles.value = await roleApi.options()
  } catch {
    roles.value = []
  }
}

function syncFromModel(): void {
  const signature = JSON.stringify(props.modelValue ?? null)
  if (signature === lastEmitted) {
    return
  }
  const parsed = parseFlowDefinition(props.modelValue)
  rows.value = parsed.rows.length > 0 ? parsed.rows : [emptyLinearRow(0)]
  unsupported.value = parsed.unsupported
}

watch(() => props.modelValue, syncFromModel, { immediate: true })

/** 行变化 → 重建定义并 emit（`unsupported` 时不 emit，避免把残定义写回去） */
function flush(): void {
  if (unsupported.value !== null) {
    return
  }
  const definition = buildFlowDefinition(rows.value, props.fieldLabelOf ?? undefined)
  lastEmitted = JSON.stringify(definition)
  emit('update:modelValue', definition)
}

watch(rows, flush, { deep: true })

function addRow(): void {
  rows.value.push(emptyLinearRow(rows.value.length))
}

function removeRow(index: number): void {
  rows.value.splice(index, 1)
  if (rows.value.length === 0) {
    rows.value.push(emptyLinearRow(0))
  }
  // 重新编号「第N级审批」的默认名（只改仍是默认名的行，用户改过的不动）
  rows.value.forEach((row, i) => {
    if (/^第\d+级审批$/.test(row.name)) {
      row.name = `第${i + 1}级审批`
    }
  })
}

function openUserPicker(uid: string, useCond: boolean): void {
  userPickerTarget.value = { uid, useCond }
  userPickerVisible.value = true
  void loadRoles()
}

function handleUsersPicked(users: UserOption[]): void {
  const target = userPickerTarget.value
  if (!target) {
    return
  }
  const row = rows.value.find((item) => item.uid === target.uid)
  if (!row) {
    return
  }
  const ids = users.map((user) => user.id)
  const labels = users.map((user) => user.displayName || user.username || `#${user.id}`)
  if (target.useCond) {
    row.condUserIds = ids
    row.condUserLabels = labels
  } else {
    row.userIds = ids
    row.userLabels = labels
  }
}

/** 审批人这一行的「人话」摘要（列表右侧显示，便于一眼核对） */
function ruleSummary(row: LinearRow, useCond = false): string {
  const type: SimpleRuleType = useCond ? row.condRuleType : row.ruleType
  if (type === 'SPECIFIC_USER') {
    const labels = useCond ? row.condUserLabels : row.userLabels
    return labels.length > 0 ? labels.join('、') : '未选择人员'
  }
  if (type === 'ROLE') {
    const code = useCond ? row.condRoleCode : row.roleCode
    return code ? roleOptions.value.find((item) => item.value === code)?.label ?? code : '未选择角色'
  }
  return SIMPLE_RULE_OPTIONS.find((item) => item.value === type)?.hint ?? ''
}

/** 供父级在保存前调用：返回整份配置还没配全的地方 */
function incompleteProblems(): string[] {
  return linearProblems(rows.value, FLOW_MAX_TIME_LIMIT_HOURS)
}

/** 供父级取当前定义 */
function definition(): FlowDefinition {
  return buildFlowDefinition(rows.value, props.fieldLabelOf ?? undefined)
}

defineExpose({ incompleteProblems, definition, blocked })
</script>

<template>
  <div class="ts-linear-flow">
    <!-- 不可简化：整块替换为说明 + 跳转完整设计器，绝不静默改写 -->
    <el-alert v-if="blocked" type="warning" :closable="false" show-icon class="ts-linear-flow__blocked">
      <template #title>该流程包含简化视图无法编辑的配置</template>
      <div class="ts-linear-flow__blocked-body">
        {{ unsupported }}
        <div class="ts-text-hint">
          简化视图只表达「一条直线上的审批人」。为避免把你的流程改残，这里不做降级改写 ——
          如需调整，请用完整的流程设计器（支持会签、抄送、多分支）。
        </div>
        <el-button v-if="!readonly" link type="primary" size="small" @click="emit('request-advanced')">
          打开完整流程设计器
        </el-button>
      </div>
    </el-alert>

    <template v-else>
      <div class="ts-linear-flow__hint">
        <span class="ts-text-hint">
          自上而下依次审批，一行就是一个审批节点。不用画连线 —— 非技术同学也能直接配。
        </span>
      </div>

      <div class="ts-linear-flow__rows">
        <div v-for="(row, index) in rows" :key="row.uid" class="ts-linear-flow__row">
          <div class="ts-linear-flow__marker">
            <span class="ts-linear-flow__index">{{ index + 1 }}</span>
            <span v-if="index < rows.length - 1 || row.advanced" class="ts-linear-flow__line" />
          </div>

          <div class="ts-linear-flow__body">
            <div class="ts-linear-flow__main">
              <el-input
                v-model="row.name"
                class="ts-linear-flow__name"
                maxlength="64"
                :disabled="readonly"
                placeholder="节点名称，如：部门主管审批"
              />

              <el-select v-model="row.ruleType" class="ts-linear-flow__rule" :disabled="readonly" @change="loadRoles">
                <el-option
                  v-for="option in SIMPLE_RULE_OPTIONS"
                  :key="option.value"
                  :label="option.label"
                  :value="option.value"
                />
              </el-select>

              <!-- 指定人：点开选人弹窗 -->
              <el-button
                v-if="row.ruleType === 'SPECIFIC_USER'"
                class="ts-linear-flow__pick"
                :disabled="readonly"
                @click="openUserPicker(row.uid, false)"
              >
                {{ row.userIds.length > 0 ? `已选 ${row.userIds.length} 人` : '选择人员' }}
              </el-button>
              <!-- 指定角色 -->
              <el-select
                v-else-if="row.ruleType === 'ROLE'"
                v-model="row.roleCode"
                class="ts-linear-flow__pick"
                :disabled="readonly"
                placeholder="选择角色"
              >
                <el-option v-for="role in roleOptions" :key="role.value" :label="role.label" :value="role.value" />
              </el-select>
              <span v-else class="ts-linear-flow__auto ts-text-hint">{{ ruleSummary(row) }}</span>

              <div class="ts-linear-flow__limit">
                <span class="ts-text-hint">限时</span>
                <el-input-number
                  v-model="row.timeLimitHours"
                  :min="1"
                  :max="FLOW_MAX_TIME_LIMIT_HOURS"
                  :step="1"
                  :disabled="readonly"
                  controls-position="right"
                  class="ts-linear-flow__hours"
                />
                <span class="ts-text-hint">小时（超时提醒）</span>
              </div>

              <el-button v-if="!readonly && rows.length > 1" link type="danger" :icon="Delete" @click="removeRow(index)">
                删除
              </el-button>
            </div>

            <!-- 高级条件（默认折叠） -->
            <el-collapse v-if="!readonly || row.advanced" class="ts-linear-flow__adv">
              <el-collapse-item :name="row.uid">
                <template #title>
                  <span class="ts-linear-flow__adv-title">
                    高级条件
                    <el-tag v-if="row.advanced" size="small" type="warning" effect="plain">已启用</el-tag>
                    <span v-else class="ts-text-hint">（选填：满足条件时多走一级审批）</span>
                  </span>
                </template>
                <div class="ts-linear-flow__adv-body">
                  <el-switch v-model="row.advanced" :disabled="readonly" active-text="启用条件分支" />
                  <template v-if="row.advanced">
                    <div class="ts-linear-flow__adv-line">
                      <span>当</span>
                      <el-select
                        v-model="row.condField"
                        class="ts-linear-flow__cond-field"
                        :disabled="readonly"
                        filterable
                        allow-create
                        default-first-option
                        placeholder="表单字段（如：金额）"
                      >
                        <el-option
                          v-for="field in fields ?? []"
                          :key="field.value"
                          :label="field.label"
                          :value="field.value"
                        />
                      </el-select>
                      <el-select v-model="row.condOp" class="ts-linear-flow__cond-op" :disabled="readonly">
                        <el-option v-for="op in CONDITION_OPERATORS" :key="op.value" :label="op.label" :value="op.value" />
                      </el-select>
                      <el-input
                        v-model="row.condValue"
                        class="ts-linear-flow__cond-value"
                        :disabled="readonly"
                        placeholder="比较值，如 5000"
                      />
                      <span>时，</span>
                    </div>
                    <div class="ts-linear-flow__adv-line">
                      <span>在本节点之后增加一级</span>
                      <el-select
                        v-model="row.condRuleType"
                        class="ts-linear-flow__rule"
                        :disabled="readonly"
                        @change="loadRoles"
                      >
                        <el-option
                          v-for="option in SIMPLE_RULE_OPTIONS"
                          :key="option.value"
                          :label="option.label"
                          :value="option.value"
                        />
                      </el-select>
                      <el-button
                        v-if="row.condRuleType === 'SPECIFIC_USER'"
                        :disabled="readonly"
                        @click="openUserPicker(row.uid, true)"
                      >
                        {{ row.condUserIds.length > 0 ? `已选 ${row.condUserIds.length} 人` : '选择人员' }}
                      </el-button>
                      <el-select
                        v-else-if="row.condRuleType === 'ROLE'"
                        v-model="row.condRoleCode"
                        class="ts-linear-flow__pick"
                        :disabled="readonly"
                        placeholder="选择角色"
                      >
                        <el-option v-for="role in roleOptions" :key="role.value" :label="role.label" :value="role.value" />
                      </el-select>
                      <span v-else class="ts-text-hint">{{ ruleSummary(row, true) }}</span>
                    </div>
                  </template>
                </div>
              </el-collapse-item>
            </el-collapse>
          </div>
        </div>
      </div>

      <div v-if="!readonly" class="ts-linear-flow__add">
        <el-button :icon="Plus" @click="addRow">加一级审批</el-button>
        <span class="ts-text-hint">最后一级审批通过后，申请即为「已完成」。</span>
      </div>
    </template>

    <UserSelectDialog v-model="userPickerVisible" :multiple="true" title="选择审批人" @confirm="handleUsersPicked" />
  </div>
</template>

<style scoped>
.ts-linear-flow__blocked-body {
  margin-top: 6px;
  line-height: 1.7;
}

.ts-linear-flow__hint {
  margin-bottom: 10px;
}

.ts-linear-flow__rows {
  display: flex;
  flex-direction: column;
}

.ts-linear-flow__row {
  display: flex;
  gap: 10px;
}

.ts-linear-flow__marker {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 26px;
  flex: none;
}

.ts-linear-flow__index {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-size: 12px;
  font-weight: 600;
}

.ts-linear-flow__line {
  flex: 1;
  width: 2px;
  min-height: 18px;
  background: var(--el-border-color);
  margin: 4px 0;
}

.ts-linear-flow__body {
  flex: 1;
  min-width: 0;
  padding-bottom: 14px;
}

.ts-linear-flow__main {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.ts-linear-flow__name {
  width: 200px;
}

.ts-linear-flow__rule {
  width: 140px;
}

.ts-linear-flow__pick {
  width: 150px;
}

.ts-linear-flow__auto {
  min-width: 150px;
}

.ts-linear-flow__limit {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.ts-linear-flow__hours {
  width: 110px;
}

.ts-linear-flow__adv {
  margin-top: 6px;
}

.ts-linear-flow__adv-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
}

.ts-linear-flow__adv-body {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.ts-linear-flow__adv-line {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  font-size: 13px;
}

.ts-linear-flow__cond-field {
  width: 170px;
}

.ts-linear-flow__cond-op {
  width: 110px;
}

.ts-linear-flow__cond-value {
  width: 130px;
}

.ts-linear-flow__add {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 4px;
}
</style>
