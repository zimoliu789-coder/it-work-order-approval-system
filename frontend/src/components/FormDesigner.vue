<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown, ArrowUp, CopyDocument, Delete, Plus, View } from '@element-plus/icons-vue'
import FormRenderer from '@/components/FormRenderer.vue'
import { useResponsive } from '@/composables/useResponsive'
import {
  FIELD_CATEGORIES,
  FIELD_KEY_PATTERN,
  createField,
  fieldTypeMeta,
  isDataFieldType,
  validateSchemaForPublish,
  type FormData,
  type FormField,
  type FormFieldTypeCode,
  type FormSchema
} from '@/types/form'

/**
 * 表单设计器
 *
 * <h2>为什么是「点击添加 + 拖拽排序」双通道</h2>
 * 纯拖拽在设计稿里好看，但实际用起来有两个问题：① 触屏设备上拖拽体验差；
 * ② 需要精细定位到「第 3 个字段之后」时，拖拽不如「上下移动按钮」准确。
 * 因此这里保留拖拽（低成本、符合直觉），同时提供点击添加与上下移动按钮作为等价路径 ——
 * 两条路径操作同一份数据，不存在「只有一种方式能做某件事」的死角。
 *
 * <h2>设计器只负责编辑，不负责保存</h2>
 * 组件 `v-model` 绑定一份 {@link FormSchema}，保存 / 发布由页面（持有模板 id）负责。
 * 这样同一个设计器既能用于「新建模板」，也能用于「编辑已有模板的草稿」。
 *
 * <h2>发布前的本地校验</h2>
 * {@link validateSchemaForPublish} 把「能提前发现的错误」在点发布时立刻告诉配置者 ——
 * 但真正的准入仍以后端校验为准（服务端是唯一事实来源）。
 */
const props = withDefaults(
  defineProps<{
    modelValue: FormSchema
    /** 只读（仅 form_template:view，如 admin）：可预览但不能改结构 */
    readonly?: boolean
    /** 保存中（禁用底部按钮，避免重复提交） */
    saving?: boolean
  }>(),
  { readonly: false, saving: false }
)

const emit = defineEmits<{
  'update:modelValue': [FormSchema]
  /** 请求页面保存草稿 */
  save: []
  /** 请求页面发布 */
  publish: []
}>()

const { isMobile } = useResponsive()

/** 字段库分组（声明在 paletteGroups 之前，避免「使用后定义」） */
const FIELD_TYPES_BY_CATEGORY: Record<string, FormFieldTypeCode[]> = {
  BASIC: ['TEXT', 'TEXTAREA', 'NUMBER', 'DATE', 'DATETIME'],
  SELECT: ['SELECT', 'MULTI_SELECT', 'RADIO', 'CHECKBOX'],
  ADVANCED: ['FILE', 'IMAGE', 'USER', 'DEVICE', 'BIZ_GROUP'],
  LAYOUT: ['DESCRIPTION', 'DIVIDER']
}

const fields = computed<FormField[]>(() => props.modelValue.fields ?? [])
const selectedIndex = ref<number | null>(null)
const previewVisible = ref(false)
const activeCategory = ref<string>('BASIC')
/** 移动端：在「字段库 / 画布 / 属性」之间切换 */
const mobilePane = ref<'palette' | 'canvas' | 'props'>('canvas')

const dragIndex = ref<number | null>(null)
const dragType = ref<FormFieldTypeCode | null>(null)

const selectedField = computed<FormField | null>(() =>
  selectedIndex.value == null ? null : (fields.value[selectedIndex.value] ?? null)
)

const paletteGroups = computed(() =>
  FIELD_CATEGORIES.map((category) => ({
    ...category,
    items: FIELD_TYPES_BY_CATEGORY[category.code] ?? []
  }))
)

// ----------------------------------------------------------------------
// 结构编辑
// ----------------------------------------------------------------------

function commit(nextFields: FormField[], select?: number | null): void {
  emit('update:modelValue', { fields: nextFields })
  if (select !== undefined) {
    selectedIndex.value = select
  }
}

/** 已有 key 集合（用于生成不重名的 key） */
function existingKeys(excludeIndex?: number): string[] {
  return fields.value
    .map((field, index) => (index === excludeIndex ? '' : (field.key ?? '')))
    .filter((key) => key !== '')
}

function uniqueKey(base: string): string {
  const used = new Set(existingKeys())
  if (!used.has(base)) {
    return base
  }
  let i = 2
  while (used.has(`${base}_${i}`)) {
    i += 1
  }
  return `${base}_${i}`
}

function addField(type: FormFieldTypeCode, at?: number): void {
  if (props.readonly) {
    return
  }
  const field = createField(type, fields.value.length)
  if (isDataFieldType(type)) {
    field.key = uniqueKey(field.key ?? `field_${fields.value.length + 1}`)
  }
  const next = [...fields.value]
  const index = at == null ? next.length : at
  next.splice(index, 0, field)
  commit(next, index)
  mobilePane.value = 'canvas'
}

function moveField(index: number, delta: number): void {
  if (props.readonly) {
    return
  }
  const target = index + delta
  if (target < 0 || target >= fields.value.length) {
    return
  }
  const next = [...fields.value]
  const [moved] = next.splice(index, 1)
  next.splice(target, 0, moved)
  commit(next, target)
}

function duplicateField(index: number): void {
  if (props.readonly) {
    return
  }
  const source = fields.value[index]
  const copy: FormField = {
    ...source,
    options: source.options?.map((option) => ({ ...option }))
  }
  if (isDataFieldType(source.type)) {
    copy.key = uniqueKey(`${source.key ?? 'field'}_copy`)
  }
  const next = [...fields.value]
  next.splice(index + 1, 0, copy)
  commit(next, index + 1)
}

async function removeField(index: number): Promise<void> {
  if (props.readonly) {
    return
  }
  const field = fields.value[index]
  const label = field.label || fieldTypeMeta(field.type)?.label || '该字段'
  try {
    await ElMessageBox.confirm(`确认删除「${label}」？`, '删除字段', { type: 'warning' })
  } catch {
    return
  }
  const next = [...fields.value]
  next.splice(index, 1)
  commit(next, next.length === 0 ? null : Math.min(index, next.length - 1))
}

function patchField(index: number, patch: Partial<FormField>): void {
  if (props.readonly) {
    return
  }
  commit(
    fields.value.map((field, i) => (i === index ? { ...field, ...patch } : field)),
    index
  )
}

/**
 * 以下 *Selected* 系列：把「当前选中字段」的索引收进组件内部，
 * 避免在模板里写 `selectedIndex as number` 这类 TypeScript 断言
 * （模板表达式的能力集比 <script> 窄，保持模板只用最朴素的调用最稳）。
 */
function patchSelected(patch: Partial<FormField>): void {
  const index = selectedIndex.value
  if (index == null) {
    return
  }
  patchField(index, patch)
}

function normalizeSelectedKey(): void {
  const index = selectedIndex.value
  if (index == null) {
    return
  }
  const field = fields.value[index]
  if (!isDataFieldType(field.type)) {
    return
  }
  if (!field.key) {
    patchField(index, { key: uniqueKey(`field_${index + 1}`) })
  }
}

// ----------------------------------------------------------------------
// 选项编辑
// ----------------------------------------------------------------------

function addSelectedOption(): void {
  const index = selectedIndex.value
  if (index == null) {
    return
  }
  const options = [...(fields.value[index].options ?? [])]
  let seq = options.length + 1
  while (options.some((option) => option.value === `option${seq}`)) {
    seq += 1
  }
  options.push({ value: `option${seq}`, label: `选项${seq}` })
  patchField(index, { options })
}

function updateSelectedOption(optionIndex: number, patch: { value?: string; label?: string }): void {
  const index = selectedIndex.value
  if (index == null) {
    return
  }
  const options = (fields.value[index].options ?? []).map((option, i) =>
    i === optionIndex ? { ...option, ...patch } : option
  )
  patchField(index, { options })
}

function removeSelectedOption(optionIndex: number): void {
  const index = selectedIndex.value
  if (index == null) {
    return
  }
  const options = (fields.value[index].options ?? []).filter((_, i) => i !== optionIndex)
  patchField(index, { options })
}

// ----------------------------------------------------------------------
// 拖拽
// ----------------------------------------------------------------------

function onPaletteDragStart(type: FormFieldTypeCode, event: DragEvent): void {
  if (props.readonly) {
    return
  }
  dragType.value = type
  dragIndex.value = null
  event.dataTransfer?.setData('text/plain', type)
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'copy'
  }
}

function onCardDragStart(index: number, event: DragEvent): void {
  if (props.readonly) {
    return
  }
  dragIndex.value = index
  dragType.value = null
  event.dataTransfer?.setData('text/plain', String(index))
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move'
  }
}

function onDropOnCard(index: number, event: DragEvent): void {
  event.preventDefault()
  handleDrop(index)
}

function onDropAtEnd(event: DragEvent): void {
  event.preventDefault()
  handleDrop(fields.value.length)
}

function handleDrop(position: number): void {
  if (props.readonly) {
    return
  }
  if (dragType.value) {
    addField(dragType.value, position)
  } else if (dragIndex.value != null) {
    const from = dragIndex.value
    if (from === position || from + 1 === position) {
      resetDrag()
      return
    }
    const next = [...fields.value]
    const [moved] = next.splice(from, 1)
    const target = from < position ? position - 1 : position
    next.splice(target, 0, moved)
    commit(next, target)
  }
  resetDrag()
}

function resetDrag(): void {
  dragIndex.value = null
  dragType.value = null
}

// ----------------------------------------------------------------------
// 预览
// ----------------------------------------------------------------------

/** 预览用示例数据：尽量让每种控件都有可见内容（不参与真实提交） */
const previewData = computed<FormData>(() => {
  const data: FormData = {}
  for (const field of fields.value) {
    if (!isDataFieldType(field.type) || !field.key) {
      continue
    }
    const kind = fieldTypeMeta(field.type)?.valueKind
    switch (kind) {
      case 'TEXT':
        data[field.key] = '示例文本'
        break
      case 'NUMBER':
        data[field.key] = 100
        break
      case 'DATE':
        data[field.key] = new Date().toISOString().slice(0, 10)
        break
      case 'DATETIME':
        data[field.key] = `${new Date().toISOString().slice(0, 10)} 09:00:00`
        break
      case 'OPTION':
        data[field.key] = field.options?.[0]?.value ?? ''
        break
      case 'OPTION_MULTI':
        data[field.key] = field.options?.slice(0, 2).map((option) => option.value) ?? []
        break
      default:
        break
    }
  }
  return data
})

const publishProblems = computed(() => validateSchemaForPublish(props.modelValue))

function canvasLabel(field: FormField, index: number): string {
  if (field.type === 'DIVIDER') {
    return '分隔线'
  }
  if (field.type === 'DESCRIPTION') {
    return '说明文字'
  }
  return field.label || field.key || `字段 ${index + 1}`
}

function summaryOf(field: FormField): string {
  const meta = fieldTypeMeta(field.type)
  if (!meta) {
    return '未知类型'
  }
  const parts = [meta.label]
  if (isDataFieldType(field.type)) {
    parts.push(field.required ? '必填' : '选填')
    if (field.width === 2) {
      parts.push('半行')
    }
    if (field.key) {
      parts.push(`key: ${field.key}`)
    }
  }
  return parts.join(' · ')
}

function requestSave(): void {
  // 保存草稿刻意宽松：只拦截「key 重复 / key 非法」这类一旦发布必然失败、且改起来不费力的结构错误
  const blocking = publishProblems.value.filter(
    (problem) => problem.includes('重复') || problem.includes('不合法')
  )
  if (blocking.length > 0) {
    ElMessage.warning(blocking[0])
    return
  }
  emit('save')
}

function requestPublish(): void {
  if (publishProblems.value.length > 0) {
    ElMessage.warning(publishProblems.value[0])
    return
  }
  emit('publish')
}

defineExpose({ publishProblems })
</script>

<template>
  <div class="ts-designer" :class="{ 'is-mobile': isMobile }">
    <!-- 移动端顶部页签 -->
    <div v-if="isMobile" class="ts-designer__tabs">
      <el-radio-group v-model="mobilePane" size="small">
        <el-radio-button value="palette">字段库</el-radio-button>
        <el-radio-button value="canvas">画布</el-radio-button>
        <el-radio-button value="props">属性</el-radio-button>
      </el-radio-group>
    </div>

    <div class="ts-designer__body">
      <!-- 左：字段库 -->
      <aside v-show="!isMobile || mobilePane === 'palette'" class="ts-designer__panel">
        <el-tabs v-model="activeCategory">
          <el-tab-pane
            v-for="group in paletteGroups"
            :key="group.code"
            :label="group.label"
            :name="group.code"
          >
            <div class="ts-designer__palette-list">
              <button
                v-for="type in group.items"
                :key="type"
                class="ts-designer__palette-item"
                type="button"
                :draggable="!readonly"
                :disabled="readonly"
                @click="addField(type)"
                @dragstart="onPaletteDragStart(type, $event)"
              >
                <el-icon><Plus /></el-icon>
                <span>{{ fieldTypeMeta(type)?.label }}</span>
              </button>
            </div>
            <p class="ts-text-hint">点击直接添加到末尾，或拖拽到画布指定位置。</p>
          </el-tab-pane>
        </el-tabs>
      </aside>

      <!-- 中：画布 -->
      <section v-show="!isMobile || mobilePane === 'canvas'" class="ts-designer__panel">
        <div class="ts-designer__canvas-head">
          <span class="ts-designer__canvas-title">表单画布</span>
          <span class="ts-text-hint">{{ fields.length }} / 50 个字段</span>
          <div class="ts-designer__canvas-actions">
            <el-button size="small" plain :icon="View" @click="previewVisible = true">预览</el-button>
          </div>
        </div>

        <div
          class="ts-designer__drop"
          :class="{ 'is-empty': fields.length === 0 }"
          @dragover.prevent
          @drop="onDropAtEnd"
        >
          <el-empty v-if="fields.length === 0" :image-size="70" description="从左侧字段库添加字段" />

          <div
            v-for="(field, index) in fields"
            :key="`card-${index}`"
            class="ts-designer__card"
            :class="{ 'is-active': selectedIndex === index }"
            :draggable="!readonly"
            @click="selectedIndex = index"
            @dragstart="onCardDragStart(index, $event)"
            @dragover.prevent
            @drop.stop="onDropOnCard(index, $event)"
          >
            <div class="ts-designer__card-main">
              <span class="ts-designer__card-label">
                {{ canvasLabel(field, index) }}
                <el-tag
                  v-if="field.required && isDataFieldType(field.type)"
                  size="small"
                  type="danger"
                  effect="plain"
                >
                  必填
                </el-tag>
              </span>
              <span class="ts-text-hint ts-designer__card-summary">{{ summaryOf(field) }}</span>
            </div>
            <div v-if="!readonly" class="ts-designer__card-actions">
              <el-button link size="small" :disabled="index === 0" @click.stop="moveField(index, -1)">
                <el-icon><ArrowUp /></el-icon>
              </el-button>
              <el-button
                link
                size="small"
                :disabled="index === fields.length - 1"
                @click.stop="moveField(index, 1)"
              >
                <el-icon><ArrowDown /></el-icon>
              </el-button>
              <el-button link size="small" @click.stop="duplicateField(index)">
                <el-icon><CopyDocument /></el-icon>
              </el-button>
              <el-button link type="danger" size="small" @click.stop="removeField(index)">
                <el-icon><Delete /></el-icon>
              </el-button>
            </div>
          </div>
        </div>
      </section>

      <!-- 右：属性 -->
      <aside v-show="!isMobile || mobilePane === 'props'" class="ts-designer__panel">
        <template v-if="selectedField">
          <h4 class="ts-designer__props-title">
            字段属性
            <el-tag size="small" effect="plain">{{ fieldTypeMeta(selectedField.type)?.label }}</el-tag>
          </h4>

          <el-form label-width="88px" size="small" :label-position="isMobile ? 'top' : 'right'">
            <!-- 说明文字：只需内容 -->
            <template v-if="selectedField.type === 'DESCRIPTION'">
              <el-form-item label="说明内容" required>
                <el-input
                  :model-value="selectedField.content ?? ''"
                  type="textarea"
                  :rows="4"
                  maxlength="500"
                  show-word-limit
                  :disabled="readonly"
                  placeholder="展示在表单中的说明文字"
                  @update:model-value="(v) => patchSelected({ content: String(v) })"
                />
              </el-form-item>
            </template>

            <template v-else-if="selectedField.type === 'DIVIDER'">
              <p class="ts-text-hint">分隔线只是视觉元素，不产生数据，无需配置。</p>
            </template>

            <template v-else>
              <el-form-item label="显示名称" required>
                <el-input
                  :model-value="selectedField.label ?? ''"
                  :disabled="readonly"
                  maxlength="64"
                  @update:model-value="(v) => patchSelected({ label: String(v) })"
                />
              </el-form-item>

              <el-form-item label="字段 key" required>
                <el-input
                  :model-value="selectedField.key ?? ''"
                  :disabled="readonly"
                  maxlength="64"
                  placeholder="字母/下划线开头，如 purchaseTitle"
                  @update:model-value="(v) => patchSelected({ key: String(v) })"
                  @blur="normalizeSelectedKey"
                />
                <div
                  v-if="selectedField.key && !FIELD_KEY_PATTERN.test(selectedField.key)"
                  class="ts-text-hint ts-designer__error"
                >
                  key 必须以字母或下划线开头，只能包含字母、数字、下划线
                </div>
                <div v-else class="ts-text-hint">同一表单内必须唯一；提交时数据以此 key 存储。</div>
              </el-form-item>

              <el-form-item label="栅格宽度">
                <el-radio-group
                  :model-value="selectedField.width ?? 1"
                  :disabled="readonly"
                  @update:model-value="(v) => patchSelected({ width: Number(v) })"
                >
                  <el-radio-button :value="1">整行</el-radio-button>
                  <el-radio-button :value="2">半行</el-radio-button>
                </el-radio-group>
              </el-form-item>

              <el-form-item label="是否必填">
                <el-switch
                  :model-value="selectedField.required === true"
                  :disabled="readonly || selectedField.type === 'FILE' || selectedField.type === 'IMAGE'"
                  @update:model-value="(v) => patchSelected({ required: Boolean(v) })"
                />
                <div
                  v-if="selectedField.type === 'FILE' || selectedField.type === 'IMAGE'"
                  class="ts-text-hint"
                >
                  附件以「随单上传、绑定工单」的方式存储，不作为必填校验项（第一期）。
                </div>
              </el-form-item>

              <el-form-item label="占位提示">
                <el-input
                  :model-value="selectedField.placeholder ?? ''"
                  :disabled="readonly"
                  @update:model-value="(v) => patchSelected({ placeholder: String(v) })"
                />
              </el-form-item>

              <el-form-item label="帮助说明">
                <el-input
                  :model-value="selectedField.help ?? ''"
                  :disabled="readonly"
                  @update:model-value="(v) => patchSelected({ help: String(v) })"
                />
              </el-form-item>

              <!-- 选项类 -->
              <el-form-item v-if="fieldTypeMeta(selectedField.type)?.supportsOptions" label="选项">
                <div class="ts-designer__options">
                  <div
                    v-for="(option, optionIndex) in selectedField.options ?? []"
                    :key="`opt-${optionIndex}`"
                    class="ts-designer__option-row"
                  >
                    <el-input
                      :model-value="option.value"
                      :disabled="readonly"
                      size="small"
                      placeholder="值"
                      class="ts-designer__option-value"
                      @update:model-value="(v) => updateSelectedOption(optionIndex, { value: String(v) })"
                    />
                    <el-input
                      :model-value="option.label"
                      :disabled="readonly"
                      size="small"
                      placeholder="显示名"
                      class="ts-designer__option-label"
                      @update:model-value="(v) => updateSelectedOption(optionIndex, { label: String(v) })"
                    />
                    <el-button
                      v-if="!readonly"
                      link
                      type="danger"
                      size="small"
                      @click="removeSelectedOption(optionIndex)"
                    >
                      <el-icon><Delete /></el-icon>
                    </el-button>
                  </div>
                  <el-button v-if="!readonly" size="small" plain :icon="Plus" @click="addSelectedOption">
                    添加选项
                  </el-button>
                </div>
              </el-form-item>

              <!-- 数字 -->
              <template v-if="selectedField.type === 'NUMBER'">
                <el-form-item label="最小值">
                  <el-input-number
                    :model-value="selectedField.min ?? undefined"
                    :disabled="readonly"
                    controls-position="right"
                    class="ts-designer__full"
                    @update:model-value="(v) => patchSelected({ min: v == null ? null : Number(v) })"
                  />
                </el-form-item>
                <el-form-item label="最大值">
                  <el-input-number
                    :model-value="selectedField.max ?? undefined"
                    :disabled="readonly"
                    controls-position="right"
                    class="ts-designer__full"
                    @update:model-value="(v) => patchSelected({ max: v == null ? null : Number(v) })"
                  />
                </el-form-item>
                <el-form-item label="小数位数">
                  <el-input-number
                    :model-value="selectedField.precision ?? 0"
                    :disabled="readonly"
                    :min="0"
                    :max="4"
                    controls-position="right"
                    class="ts-designer__full"
                    @update:model-value="(v) => patchSelected({ precision: v == null ? 0 : Number(v) })"
                  />
                </el-form-item>
                <el-form-item label="单位">
                  <el-input
                    :model-value="selectedField.unit ?? ''"
                    :disabled="readonly"
                    placeholder="如：元"
                    @update:model-value="(v) => patchSelected({ unit: String(v) })"
                  />
                </el-form-item>
              </template>

              <!-- 文本 -->
              <template v-if="selectedField.type === 'TEXT' || selectedField.type === 'TEXTAREA'">
                <el-form-item label="最大长度">
                  <el-input-number
                    :model-value="selectedField.maxLength ?? undefined"
                    :disabled="readonly"
                    :min="1"
                    :max="2000"
                    controls-position="right"
                    class="ts-designer__full"
                    @update:model-value="(v) => patchSelected({ maxLength: v == null ? null : Number(v) })"
                  />
                </el-form-item>
                <el-form-item label="校验正则">
                  <el-input
                    :model-value="selectedField.pattern ?? ''"
                    :disabled="readonly"
                    placeholder="选填，如 ^1[3-9][0-9]{9}$"
                    @update:model-value="(v) => patchSelected({ pattern: String(v) })"
                  />
                </el-form-item>
              </template>

              <!-- 日期 -->
              <template v-if="selectedField.type === 'DATE' || selectedField.type === 'DATETIME'">
                <el-form-item label="范围限制">
                  <el-select
                    :model-value="selectedField.dateLimit ?? 'NONE'"
                    :disabled="readonly"
                    class="ts-designer__full"
                    @update:model-value="(v) => patchSelected({ dateLimit: String(v) })"
                  >
                    <el-option label="不限制" value="NONE" />
                    <el-option label="不早于今天" value="NOT_BEFORE_TODAY" />
                    <el-option label="不晚于今天" value="NOT_AFTER_TODAY" />
                    <el-option label="自定义区间" value="CUSTOM" />
                  </el-select>
                </el-form-item>
                <template v-if="selectedField.dateLimit === 'CUSTOM'">
                  <el-form-item label="起始日期">
                    <el-date-picker
                      :model-value="selectedField.dateMin ?? ''"
                      type="date"
                      value-format="YYYY-MM-DD"
                      :disabled="readonly"
                      class="ts-designer__full"
                      @update:model-value="(v) => patchSelected({ dateMin: v ? String(v) : null })"
                    />
                  </el-form-item>
                  <el-form-item label="截止日期">
                    <el-date-picker
                      :model-value="selectedField.dateMax ?? ''"
                      type="date"
                      value-format="YYYY-MM-DD"
                      :disabled="readonly"
                      class="ts-designer__full"
                      @update:model-value="(v) => patchSelected({ dateMax: v ? String(v) : null })"
                    />
                  </el-form-item>
                </template>
              </template>

              <!-- 附件 -->
              <template v-if="selectedField.type === 'FILE' || selectedField.type === 'IMAGE'">
                <el-form-item label="数量上限">
                  <el-input-number
                    :model-value="selectedField.maxCount ?? 1"
                    :disabled="readonly"
                    :min="1"
                    :max="10"
                    controls-position="right"
                    class="ts-designer__full"
                    @update:model-value="(v) => patchSelected({ maxCount: v == null ? 1 : Number(v) })"
                  />
                </el-form-item>
                <el-form-item label="单件上限MB">
                  <el-input-number
                    :model-value="selectedField.maxSizeMb ?? 10"
                    :disabled="readonly"
                    :min="1"
                    :max="50"
                    controls-position="right"
                    class="ts-designer__full"
                    @update:model-value="(v) => patchSelected({ maxSizeMb: v == null ? 10 : Number(v) })"
                  />
                </el-form-item>
                <el-form-item label="允许类型">
                  <el-input
                    :model-value="selectedField.fileTypes ?? ''"
                    :disabled="readonly"
                    placeholder="选填，如 pdf,docx（留空用系统白名单）"
                    @update:model-value="(v) => patchSelected({ fileTypes: String(v) })"
                  />
                </el-form-item>
              </template>
            </template>
          </el-form>
        </template>

        <el-empty v-else :image-size="60" description="在画布中选中一个字段以编辑属性" />
      </aside>
    </div>

    <!-- 底部操作 -->
    <div class="ts-designer__footer">
      <span class="ts-text-hint">
        <template v-if="readonly">只读模式：你没有表单模板的管理权限（form_template:manage）</template>
        <template v-else-if="publishProblems.length > 0">发布前需完善：{{ publishProblems[0] }}</template>
        <template v-else>表单校验通过，可以保存草稿或直接发布</template>
      </span>
      <div class="ts-designer__footer-actions">
        <el-button :disabled="readonly" :loading="saving" @click="requestSave">保存草稿</el-button>
        <el-button
          type="primary"
          :disabled="readonly || publishProblems.length > 0"
          :loading="saving"
          @click="requestPublish"
        >
          发布版本
        </el-button>
      </div>
    </div>

    <el-dialog v-model="previewVisible" title="表单预览" :width="isMobile ? '94%' : '720px'">
      <FormRenderer :schema="modelValue" :model-value="previewData" readonly />
    </el-dialog>
  </div>
</template>

<style scoped>
.ts-designer {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.ts-designer__body {
  display: grid;
  grid-template-columns: 220px minmax(0, 1fr) 320px;
  gap: 12px;
  align-items: start;
}

.ts-designer__panel {
  padding: 12px;
  border: 1px solid var(--ts-border);
  border-radius: 8px;
  background: #fff;
}

.ts-designer__palette-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.ts-designer__palette-item {
  display: flex;
  gap: 4px;
  align-items: center;
  justify-content: center;
  padding: 8px 6px;
  font-size: 12px;
  border: 1px dashed var(--ts-border);
  border-radius: 6px;
  background: #fbfcfe;
  cursor: grab;
}

.ts-designer__palette-item:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.ts-designer__palette-item:not(:disabled):hover {
  border-color: var(--el-color-primary);
  color: var(--el-color-primary);
}

.ts-designer__canvas-head {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
}

.ts-designer__canvas-title {
  font-size: 14px;
  font-weight: 500;
}

.ts-designer__canvas-actions {
  margin-left: auto;
}

.ts-designer__drop {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-height: 120px;
}

.ts-designer__drop.is-empty {
  justify-content: center;
}

.ts-designer__card {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 8px 10px;
  border: 1px solid var(--ts-border);
  border-radius: 6px;
  background: #fbfcfe;
  cursor: pointer;
}

.ts-designer__card.is-active {
  border-color: var(--el-color-primary);
  box-shadow: 0 0 0 1px var(--el-color-primary) inset;
}

.ts-designer__card-main {
  display: flex;
  flex: 1 1 auto;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}

.ts-designer__card-label {
  display: flex;
  gap: 6px;
  align-items: center;
  font-size: 13px;
}

.ts-designer__card-summary {
  font-size: 12px;
}

.ts-designer__card-actions {
  display: flex;
  flex: 0 0 auto;
  gap: 2px;
  align-items: center;
}

.ts-designer__props-title {
  display: flex;
  gap: 8px;
  align-items: center;
  margin: 0 0 12px;
  font-size: 14px;
  font-weight: 500;
}

.ts-designer__options {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
}

.ts-designer__option-row {
  display: flex;
  gap: 6px;
  align-items: center;
}

.ts-designer__option-value,
.ts-designer__option-label {
  flex: 1 1 0;
  min-width: 0;
}

.ts-designer__full {
  width: 100%;
}

.ts-designer__error {
  color: var(--el-color-danger);
}

.ts-designer__footer {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  border: 1px solid var(--ts-border);
  border-radius: 8px;
  background: #fbfcfe;
}

.ts-designer__footer-actions {
  display: flex;
  gap: 8px;
}

.ts-designer__tabs {
  display: flex;
  justify-content: center;
}

@media (max-width: 1023px) {
  .ts-designer__body {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
