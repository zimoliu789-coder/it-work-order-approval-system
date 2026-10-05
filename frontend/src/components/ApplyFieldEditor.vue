<script setup lang="ts">
import { computed } from 'vue'
import { Delete, Plus } from '@element-plus/icons-vue'
import type { FormField, FormFieldTypeCode } from '@/types/form'
// 这里引用的是「字段类型元数据」这一份事实源（types/form.ts 的 FIELD_TYPE_META），
// 而不是另抄一份中文名 —— 抄一份的下场是改了渲染器忘了改这里。
import { fieldTypeMeta } from '@/types/form'

/**
 * 申请表单的**字段清单编辑器**（ 项目 4）
 *
 * <h2>和 FormDesigner 的分工</h2>
 * FormDesigner 是完整的拖拽设计器（16 种字段类型 + 栅格宽度 + 校验规则 + 版本发布），
 * 面向「表单很复杂」的场景；本组件只做需求里点名的那 4 种：
 * **单行文本 / 日期 / 数字 / 下拉选择** —— 目标用户是配「加班申请」这类简单表单的管理员，
 * 不需要懂 key、栅格、版本。
 *
 * <h2>key 为什么是自动生成的</h2>
 * 说明：「编码、版本号这些技术细节藏起来，管理员看不到」。
 * 字段 key 就是这类技术细节，但它**必须存在且稳定**：
 * 流程里的条件（「金额 > 5000」）引用的正是字段 key。因此由组件自动分配
 * 单调递增的 `f1`、`f2`…，删除中间字段**不回收编号**，避免后面的字段偷偷换 key
 * 而让已配好的条件指向另一个字段（那是一种不报错的静默错配）。
 */

/** 本组件支持的类型（需求点名的 4 种） */
const SUPPORTED_TYPES: FormFieldTypeCode[] = ['TEXT', 'DATE', 'NUMBER', 'SELECT']

/**
 * 这 4 种类型在**本界面**的叫法。
 *
 * 刻意不复用 types/form.ts 的通用中文名：那里 `SELECT` 叫「单选下拉」，
 * 而说明写的是「下拉选择」—— 管理员是照着需求找按钮的，措辞不一致就成了「找不到」。
 * 其余类型仍回落到通用名（本组件目前不暴露它们）。
 */
const SUPPORTED_LABEL: Record<string, string> = {
  TEXT: '单行文本',
  DATE: '日期',
  NUMBER: '数字',
  SELECT: '下拉选择'
}

/** 「+」菜单的条目 */
const ADDABLE = computed(() =>
  SUPPORTED_TYPES.map((type) => {
    const meta = fieldTypeMeta(type)
    return { type, label: SUPPORTED_LABEL[type] ?? meta?.label ?? type }
  })
)

const props = withDefaults(
  defineProps<{
    modelValue: FormField[]
    readonly?: boolean
  }>(),
  { readonly: false }
)

const emit = defineEmits<{
  'update:modelValue': [FormField[]]
}>()

/** 单调递增的 key 序号；初值取当前最大编号 + 1，避免编辑已有表单时撞号 */
let keySeed = 0
function nextKey(existing: FormField[]): string {
  if (keySeed === 0) {
    for (const field of existing) {
      const match = /^f(\d+)$/.exec(field.key ?? '')
      if (match) {
        keySeed = Math.max(keySeed, Number(match[1]))
      }
    }
  }
  keySeed += 1
  return `f${keySeed}`
}

function addField(type: FormFieldTypeCode): void {
  const meta = fieldTypeMeta(type)
  const next: FormField = {
    key: nextKey(props.modelValue),
    label: `${SUPPORTED_LABEL[type] ?? meta?.label ?? '字段'}${props.modelValue.length + 1}`,
    type,
    required: false,
    options: meta?.supportsOptions ? [{ value: 'A', label: '选项一' }] : null
  }
  emit('update:modelValue', [...props.modelValue, next])
}

function patch(index: number, patchObj: Partial<FormField>): void {
  const next = props.modelValue.map((field, i) => (i === index ? { ...field, ...patchObj } : field))
  emit('update:modelValue', next)
}

function remove(index: number): void {
  emit(
    'update:modelValue',
    props.modelValue.filter((_, i) => i !== index)
  )
}

function move(index: number, delta: number): void {
  const target = index + delta
  if (target < 0 || target >= props.modelValue.length) {
    return
  }
  const next = [...props.modelValue]
  const [item] = next.splice(index, 1)
  next.splice(target, 0, item)
  emit('update:modelValue', next)
}

/**
 * 选项编辑：界面上用「逗号分隔的一行文本」承载，落库仍是 `{value,label}[]`。
 *
 * `value` 直接用用户输入的文字（而不是生成 `opt1`）：这些选项会进历史工单的表单数据，
 * 用文字做 value 时数据自解释，导出 Excel 也是人看得懂的。改文案会改变 value ——
 * 但这是一次性新建表单，不存在"改文案"的历史包袱。
 */
function optionsText(field: FormField): string {
  return (field.options ?? []).map((option) => option.label).join('、')
}

function setOptionsText(index: number, text: string): void {
  const items = text
    .split(/[、,，]/)
    .map((item) => item.trim())
    .filter((item) => item !== '')
  patch(index, {
    options: items.map((item) => ({ value: item, label: item }))
  })
}

function typeLabel(type: FormFieldTypeCode): string {
  return SUPPORTED_LABEL[type] ?? fieldTypeMeta(type)?.label ?? type
}

/** 供父级预检：返回还没配全的字段 */
function incompleteProblems(): string[] {
  const problems: string[] = []
  if (props.modelValue.length === 0) {
    problems.push('请至少添加一个表单字段')
  }
  props.modelValue.forEach((field, index) => {
    const at = `第 ${index + 1} 个字段`
    if (!(field.label ?? '').trim()) {
      problems.push(`${at}未填写名称`)
    }
    if (field.type === 'SELECT' && (field.options ?? []).length === 0) {
      problems.push(`${at}是下拉选择但没有任何选项`)
    }
  })
  return problems
}

defineExpose({ incompleteProblems })
</script>

<template>
  <div class="ts-field-editor">
    <div v-if="modelValue.length === 0" class="ts-field-editor__empty">
      <el-empty description="还没有字段，点下面的「添加字段」开始" :image-size="70" />
    </div>

    <div v-for="(field, index) in modelValue" :key="field.key ?? index" class="ts-field-editor__row">
      <div class="ts-field-editor__head">
        <span class="ts-field-editor__badge">{{ index + 1 }}</span>
        <el-tag size="small" effect="plain">{{ typeLabel(field.type) }}</el-tag>

        <el-input
          :model-value="field.label ?? ''"
          class="ts-field-editor__label"
          maxlength="32"
          :disabled="readonly"
          placeholder="字段名称，如：加班日期"
          @update:model-value="(value: string) => patch(index, { label: value })"
        />

        <el-checkbox
          :model-value="field.required === true"
          :disabled="readonly"
          @update:model-value="(value: string | number | boolean) => patch(index, { required: Boolean(value) })"
        >
          必填
        </el-checkbox>

        <template v-if="!readonly">
          <el-button link size="small" :disabled="index === 0" @click="move(index, -1)">上移</el-button>
          <el-button
            link
            size="small"
            :disabled="index === modelValue.length - 1"
            @click="move(index, 1)"
          >
            下移
          </el-button>
          <el-button link size="small" type="danger" :icon="Delete" @click="remove(index)">删除</el-button>
        </template>
      </div>

      <div v-if="field.type === 'SELECT'" class="ts-field-editor__options">
        <span class="ts-text-hint">选项</span>
        <el-input
          :model-value="optionsText(field)"
          class="ts-field-editor__options-input"
          :disabled="readonly"
          placeholder="用「、」分隔，如：事假、病假、年假"
          @update:model-value="(value: string) => setOptionsText(index, value)"
        />
      </div>

      <div v-if="field.type === 'NUMBER'" class="ts-field-editor__extra">
        <span class="ts-text-hint">单位</span>
        <el-input
          :model-value="field.unit ?? ''"
          class="ts-field-editor__unit"
          maxlength="8"
          :disabled="readonly"
          placeholder="选填，如：元 / 台"
          @update:model-value="(value: string) => patch(index, { unit: value })"
        />
      </div>
    </div>

    <el-dropdown v-if="!readonly" trigger="click" class="ts-field-editor__add">
      <el-button type="primary" plain :icon="Plus">添加字段</el-button>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item v-for="item in ADDABLE" :key="item.type" @click="addField(item.type)">
            {{ item.label }}
          </el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
  </div>
</template>

<style scoped>
.ts-field-editor__empty {
  padding: 4px 0 10px;
}

.ts-field-editor__row {
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  margin-bottom: 8px;
}

.ts-field-editor__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.ts-field-editor__badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.ts-field-editor__label {
  width: 220px;
}

.ts-field-editor__options,
.ts-field-editor__extra {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}

.ts-field-editor__options-input {
  max-width: 420px;
}

.ts-field-editor__unit {
  width: 160px;
}

.ts-field-editor__add {
  margin-top: 4px;
}
</style>
