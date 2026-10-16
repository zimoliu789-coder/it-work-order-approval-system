<script setup lang="ts">
import { reactive } from 'vue'
import { Delete, Plus } from '@element-plus/icons-vue'
import type { FormField, FormFieldTypeCode } from '@/types/form'
// 字段类型只认「唯一事实源」FIELD_TYPE_META / FIELD_CATEGORIES。
//
// ⚠️ 这里曾经写死 4 种类型（TEXT / DATE / NUMBER / SELECT），于是出现了 2026-10-16 报的 P0 bug：
// 「新建申请向导」的下拉里看不到多行文本、附件上传等 12 种类型，而**编辑配置页**（完整设计器）却有 ——
// 同一个表单在两个页面能配的东西不一样。根因不是「两份数组写歪了」，而是**这个组件自己维护了一份清单**。
// 现在全部由元数据驱动，改渲染器时不会再漏改这里。
import { addableFieldGroups, fieldTypeMeta } from '@/types/form'

/**
 * 申请表单的**字段清单编辑器**（简化版）
 *
 * <h2>和 FormDesigner 的分工</h2>
 * FormDesigner 是完整的拖拽设计器（栅格宽度 + 校验规则 + 版本发布），面向「表单很复杂」的场景；
 * 本组件是**轻量行式编辑器** —— 目标用户是配「加班申请」这类简单表单的管理员，不需要懂 key、栅格、版本。
 *
 * <p><b>但「简化」不等于「能力残缺」</b>：字段类型的种类必须与设计器**完全一致**，
 * 差别只在于「不暴露栅格 / 校验规则 / 版本这些技术细节」。
 * 见上方关于本 bug 的说明 —— 类型清单完全由 {@link FIELD_TYPE_META} 驱动，本组件不另维护。
 *
 * <h2>key 为什么是自动生成的</h2>
 * 说明：「编码、版本号这些技术细节藏起来，管理员看不到」。
 * 字段 key 就是这类技术细节，但它**必须存在且稳定**：
 * 流程里的条件（「金额 > 5000」）引用的正是字段 key。因此由组件自动分配
 * 单调递增的 `f1`、`f2`…，删除中间字段**不回收编号**，避免后面的字段偷偷换 key
 * 而让已配好的条件指向另一个字段（那是一种不报错的静默错配）。
 */

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

/**
 * 「添加字段」菜单：分组标题与条目**全部来自** {@link addableFieldGroups}（types/form 的唯一事实源）。
 *
 * 16 项平铺时找「附件上传」要扫一遍，分组标题本身就是导航（基础 / 选择 / 高级 / 布局）。
 * 与完整设计器共用同一份清单 —— 两侧都不再手写类型数组（这正是 P0 bug 的根因）。
 */
const ADDABLE_GROUPS = addableFieldGroups()

/** 类型在界面上的一律用元数据的中文名 —— 与设计器、渲染器、导出共用同一套措辞 */
function typeLabel(type: FormFieldTypeCode): string {
  return fieldTypeMeta(type)?.label ?? type
}

/** 该类型是否要配选项（下拉 / 多选 / 单选 / 复选 —— 由元数据判定，不写死 SELECT） */
function needsOptions(field: FormField): boolean {
  return fieldTypeMeta(field.type)?.supportsOptions === true
}

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
    label: `${meta?.label ?? '字段'}${props.modelValue.length + 1}`,
    type,
    required: false,
    // 带选项的类型给一条占位选项：让管理员一眼看出「这里要配选项」，
    // 而不是先存一个空列表、等发布时才被校验拦住
    options: meta?.supportsOptions ? [{ value: '选项一', label: '选项一' }] : null
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

// ----------------------------------------------------------------------
// 选项编辑（标签式）
// ----------------------------------------------------------------------

/**
 * 选项编辑采用**标签式**：已确认的选项是标签，输入框只负责「生成下一个标签」。
 *
 * <h2>为什么必须换掉「一行文本 + 每次输入回写」</h2>
 * 旧实现是 `:model-value="optionsText(field)"`（`join('、')` 且**过滤空项**）
 * 配合「每次按键都解析回写」。于是：你打下「事假、」时，末尾那个顿号被当成空项丢掉，
 * 回写后变回「事假」—— **分隔符在打字过程中被吃掉**，永远拆不出第二个选项。
 * 顿号、半角逗号、全角逗号**全都一样**，所以「只识别英文逗号」的直觉是错的，
 * 真凶是「受控回写 + 归一化」这个组合。
 *
 * <p>现在把「正在输入的原文」与「已确认的选项」拆成两个状态：
 * `optionDrafts` 只持有原文、**不参与回写**；按回车 / 顿号 / 逗号 / 失焦才生成标签。
 *
 * <p>草稿以**字段 key** 为键（不是下标）：字段上移下移、删中间项时不会串味。
 */
const optionDrafts = reactive<Record<string, string>>({})
const SEPARATOR = /[、,，]/

function draftKey(field: FormField, index: number): string {
  return field.key ?? `#${index}`
}

function draftOf(field: FormField, index: number): string {
  return optionDrafts[draftKey(field, index)] ?? ''
}

function currentOptions(field: FormField) {
  return field.options ?? []
}

/** 输入过程中的即时拆词：出现分隔符就把前面部分转成标签，只把最后一段留在输入框 */
function onOptionInput(index: number, field: FormField, value: string): void {
  const key = draftKey(field, index)
  if (!SEPARATOR.test(value)) {
    optionDrafts[key] = value
    return
  }
  const parts = value.split(SEPARATOR)
  const tail = parts.pop() ?? ''
  appendOptions(index, parts)
  optionDrafts[key] = tail
}

/** 把输入框里残留的文本提交为标签（回车 / 失焦 / 粘贴兜底） */
function commitDraft(index: number, field: FormField): void {
  const key = draftKey(field, index)
  const raw = optionDrafts[key] ?? ''
  if (!raw.trim()) {
    optionDrafts[key] = ''
    return
  }
  appendOptions(index, raw.split(SEPARATOR))
  optionDrafts[key] = ''
}

/** 追加选项：忽略空项、按文字去重（重复项静默合并 —— 同一选项出现两次只会在下拉里重一遍） */
function appendOptions(index: number, parts: string[]): void {
  const field = props.modelValue[index]
  if (!field) {
    return
  }
  const existing = currentOptions(field)
  const seen = new Set(existing.map((option) => option.label))
  const added: typeof existing = []
  for (const part of parts) {
    const text = part.trim()
    if (!text || seen.has(text)) {
      continue
    }
    seen.add(text)
    added.push({ value: text, label: text })
  }
  if (added.length === 0) {
    return
  }
  patch(index, { options: [...existing, ...added] })
}

function removeOption(index: number, optionIndex: number): void {
  const field = props.modelValue[index]
  if (!field) {
    return
  }
  patch(index, { options: currentOptions(field).filter((_, i) => i !== optionIndex) })
}

function onOptionKeydown(event: KeyboardEvent, index: number, field: FormField): void {
  if (event.key === 'Enter') {
    // 阻止回车冒泡：向导里回车可能触发表单默认提交，那会把半截输入当成提交
    event.preventDefault()
    commitDraft(index, field)
    return
  }
  // 输入框为空时按退格 = 删掉最后一个标签（标签式输入的通用手感）
  if (event.key === 'Backspace' && !draftOf(field, index)) {
    const options = currentOptions(field)
    if (options.length > 0) {
      event.preventDefault()
      removeOption(index, options.length - 1)
    }
  }
}

// ----------------------------------------------------------------------
// 供父级预检
// ----------------------------------------------------------------------

/**
 * 返回还没配全的字段（父级在「下一步 / 提交」前调用）。
 *
 * 判定「有没有配选项」时把**输入框里的草稿**也算上：用户打完最后一个选项直接点了提交、
 * 而失焦提交还没来得及生效时，不该报「没有任何选项」这种自相矛盾的错。
 */
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
    if (!needsOptions(field)) {
      return
    }
    const pending = draftOf(field, index)
      .split(SEPARATOR)
      .map((item) => item.trim())
      .filter((item) => item !== '').length
    if (currentOptions(field).length + pending === 0) {
      problems.push(`${at}是「${typeLabel(field.type)}」但没有任何选项`)
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

      <!-- 选项：标签式。带选项的类型由元数据判定（下拉 / 多选 / 单选 / 复选） -->
      <div v-if="needsOptions(field)" class="ts-field-editor__options">
        <span class="ts-text-hint">选项</span>
        <div class="ts-field-editor__tags" :class="{ 'is-readonly': readonly }">
          <el-tag
            v-for="(option, optionIndex) in field.options ?? []"
            :key="`${option.value}-${optionIndex}`"
            class="ts-field-editor__tag"
            size="small"
            :closable="!readonly"
            :disable-transitions="true"
            @close="removeOption(index, optionIndex)"
          >
            {{ option.label }}
          </el-tag>

          <el-input
            v-if="!readonly"
            :model-value="draftOf(field, index)"
            class="ts-field-editor__tag-input"
            placeholder="输入后按回车或「、」；也可直接粘贴「事假、病假」"
            @update:model-value="(value: string) => onOptionInput(index, field, value)"
            @keydown="(event) => onOptionKeydown(event as KeyboardEvent, index, field)"
            @blur="commitDraft(index, field)"
          />
        </div>
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
          <template v-for="(group, groupIndex) in ADDABLE_GROUPS" :key="group.code">
            <!-- 分组标题：不可点，仅作导航；第 2 组起加上分隔线 -->
            <el-dropdown-item disabled :divided="groupIndex > 0" class="ts-field-editor__add-group">
              {{ group.label }}
            </el-dropdown-item>
            <el-dropdown-item
              v-for="item in group.items"
              :key="item.type"
              @click="addField(item.type)"
            >
              {{ item.label }}
            </el-dropdown-item>
          </template>
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
  align-items: flex-start;
  gap: 8px;
  margin-top: 8px;
}

/*
  标签容器长得像输入框（同边框 / 同圆角），好让「标签 + 输入」看起来是一个整体控件；
  聚焦时整体高亮，避免出现「输入框在容器里有自己一圈边框」的双层框。
*/
.ts-field-editor__tags {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  flex: 1;
  min-height: 32px;
  max-width: 520px;
  padding: 4px 8px;
  border: 1px solid var(--el-border-color);
  border-radius: 4px;
  background: var(--el-fill-color-blank);
  transition: border-color 0.2s;
}

.ts-field-editor__tags:focus-within {
  border-color: var(--el-color-primary);
}

.ts-field-editor__tags.is-readonly {
  background: var(--el-fill-color-light);
}

.ts-field-editor__tag {
  max-width: 100%;
}

/* 内嵌输入框去掉自身边框与焦点环，由容器统一承担视觉 */
.ts-field-editor__tag-input {
  flex: 1;
  min-width: 160px;
}

.ts-field-editor__tag-input :deep(.el-input__wrapper) {
  box-shadow: none;
  padding: 0;
  background: transparent;
}

.ts-field-editor__unit {
  width: 160px;
}

.ts-field-editor__add {
  margin-top: 4px;
}

/* 下拉里的分组标题：弱化的非交互文字 */
.ts-field-editor__add-group {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  cursor: default;
}
</style>
