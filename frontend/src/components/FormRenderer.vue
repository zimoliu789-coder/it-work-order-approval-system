<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { departmentApi } from '@/api/department'
import { deviceCategoryApi, deviceModelApi } from '@/api/device'
import { orderApi } from '@/api/order'
import userApi from '@/api/user'
import PendingAttachmentPicker from '@/components/PendingAttachmentPicker.vue'
import { useResponsive } from '@/composables/useResponsive'
import { decoratePermissionLabel } from '@/types/permissionPolicy'
import {
  dataFields,
  fieldTypeMeta,
  schemaFields,
  todayString,
  type FormData,
  type FormErrors,
  type FormField,
  type FormSchema
} from '@/types/form'
import type { DepartmentOption } from '@/types/department'
import type { DeviceCategoryOption, DeviceModelOption } from '@/types/device'
import type { DeviceOption } from '@/types/order'
import type { UserOption } from '@/types/user'

/**
 * 动态表单渲染器
 *
 * <h2>三种用法，一个组件</h2>
 * <ul>
 *   <li><b>填写</b>（提交自定义申请）：`readonly=false`，`v-model` 绑定表单数据；</li>
 *   <li><b>只读回显</b>（工单详情）：`readonly=true`，按字段定义把值渲染成可读文本
 *       （选项翻译成中文、人员/设备/分组解析成名称）；</li>
 *   <li><b>设计器预览</b>：`readonly=true` + 传一份示例数据。</li>
 * </ul>
 *
 * <h2>为什么不引入第三方表单渲染库</h2>
 * 字段类型集合是我们自己定义的（既然后端 `FormFieldType` 是唯一事实来源），
 * 引入外部库意味着要在「库的 JSON 格式」与「我们的 schema」之间再维护一层映射；
 * 而映射层一旦漂移，就会出现「设计器配了、渲染器不认」。这里的实现直接消费后端 schema，
 * 新增一种字段类型只需在 `types/form.ts` 与模板中各加一处分支。
 *
 * <h2>附件字段的处理</h2>
 * 附件（FILE / IMAGE）需要服务端记录 id 才能落库，而工单 id 只能等提交成功后才拿到，
 * 因此编辑态只负责「本地挑选」（交给父组件在提交成功后统一上传，与项目既有
 * 「随单提交附件」的做法一致）；只读态则提示「见工单附件区」——
 * 附件列表由详情页的附件组件按 (bizType, bizId) 独立加载，不塞进表单数据。
 */
const props = withDefaults(
  defineProps<{
    schema?: FormSchema | null
    modelValue: FormData
    /** 只读模式（详情回显 / 预览） */
    readonly?: boolean
    /** 字段级错误（key → 文案），由父组件用 `validateFormData` 计算后传入 */
    errors?: FormErrors
    /** 编辑态：FILE / IMAGE 字段的本地待上传文件（key → File[]） */
    pendingFiles?: Record<string, File[]>
    /**
     * 「权限码 → 风险等级」映射。
     *
     * <p>只用于给**权限申请**表单的高危选项加「（高危 · 需两级审批）」标记。
     * 映射里没有的码原样返回 ⇒ 其它表单字段的选项文案不受影响，
     * 因此这个 prop 可以无脑传给所有 FormRenderer。
     *
     * <p>为什么在渲染层而不是把标记写进 schema：schema 是**已发布版本快照**，
     * 管理员改一次风险等级就要重新发布一版表单，而风险等级是会变的业务配置。
     */
    riskCodes?: Record<string, string>
  }>(),
  {
    schema: null,
    readonly: false,
    errors: () => ({}),
    pendingFiles: () => ({}),
    riskCodes: () => ({})
  }
)

const emit = defineEmits<{
  'update:modelValue': [FormData]
  'update:pendingFiles': [Record<string, File[]>]
}>()

const { isMobile } = useResponsive()

/**
 * 选项文案：命中的高危权限码会带上「（高危 · 需两级审批）」。
 *
 * <p>抽成函数而不是在模板里内联：模板里做字符串拼装无法被单测覆盖，
 * 而这条标记的意义正是「让申请人在做选择的那一刻知道要多走一级」——
 * 漏了就等于没有提醒。纯函数在 `types/permissionPolicy.spec.ts` 里有断言。
 */
function selectOptionLabel(option: { label: string; value: string }): string {
  return decoratePermissionLabel(option.label, option.value, props.riskCodes)
}

const userOptions = ref<UserOption[]>([])
const deviceOptions = ref<DeviceOption[]>([])
const groupOptions = ref<DepartmentOption[]>([])
/** 设备分类下拉项（一级 + 二级扁平，V59） */
const categoryOptions = ref<DeviceCategoryOption[]>([])
/** 型号下拉项（仅启用型号，V59 / R3） */
const modelOptions = ref<DeviceModelOption[]>([])
const modelOptionsLoading = ref(false)

/**
 * 一级分部选项（V53）—— 「一级分部选择」字段的下拉数据源。
 *
 * <p>一级分部 = **组织树根节点的直接子节点**。这里用「根的 id」推导，而不是写死
 * `depth === 1`：depth 的起始值属于后端实现细节，而「谁的父节点是根」在语义上
 * 恰好就是一级分部，不受 depth 约定变化影响。
 *
 * <p>找不到根节点（选项为空 / 数据异常）时**回落到全部部门**：宁可多列几项让用户能选，
 * 也不要给一个空下拉 —— 空下拉会让人以为「系统里没有分部」。
 */
const rootDeptOptions = computed(() => {
  const root = groupOptions.value.find((item) => item.parentId == null)
  if (!root) {
    return groupOptions.value
  }
  return groupOptions.value.filter((item) => item.parentId === root.id)
})

const fields = computed<FormField[]>(() => schemaFields(props.schema))
const editableFields = computed<FormField[]>(() => dataFields(props.schema))

/** 栅格：宽度 2 = 半行（移动端一律整行） */
function spanOf(field: FormField): string {
  return !isMobile.value && field.width === 2 ? 'span 1' : 'span 2'
}

function keyOf(field: FormField): string {
  return field.key ?? ''
}

function rawValue(field: FormField): unknown {
  return props.modelValue[keyOf(field)]
}

/** 写入一个字段值：空值直接删除 key，保持提交载荷最小（后端会拒绝未定义字段，不会拒绝缺字段） */
function update(field: FormField, value: unknown): void {
  const key = keyOf(field)
  if (!key) {
    return
  }
  const next: FormData = { ...props.modelValue }
  if (value == null || (typeof value === 'string' && value.trim() === '')) {
    delete next[key]
  } else if (Array.isArray(value) && value.length === 0) {
    delete next[key]
  } else {
    next[key] = value
  }
  emit('update:modelValue', next)
}

/**
 * 一级分类变更：顺带清掉**已不属于新一级**的二级分类。
 *
 * <p>不清的后果：用户先选「电脑 → 笔记本」，再把一级改成「办公用品」，
 * 二级仍留着「笔记本」—— 提交时后端按「二级必须归属一级」校验直接拒，
 * 而用户看到的界面上两项都是填好的，只会认为系统乱报错。
 * （与改造前提交页的 {@code handlePrimaryChange} 同一取向；
 * 项目约定：级联清空用 @change 主动做，不开 watch。）
 */
function updatePrimaryCategory(field: FormField, value: unknown): void {
  update(field, value)
  for (const sub of fields.value) {
    if (sub.type !== 'DEVICE_SUBCATEGORY' || !sub.key) {
      continue
    }
    const current = props.modelValue[sub.key]
    if (current == null) {
      continue
    }
    const stillBelongs = categoryOptions.value.some(
      (item) => String(item.id) === String(current) && String(item.parentId) === String(value ?? '')
    )
    if (!stillBelongs) {
      update(sub, null)
    }
  }
}

// ----------------------------------------------------------------------
// 取值辅助（模板里用，避免在模板中写类型断言）
// ----------------------------------------------------------------------

function textValue(field: FormField): string {
  const value = rawValue(field)
  return value == null ? '' : String(value)
}

function numberValue(field: FormField): number | undefined {
  const value = rawValue(field)
  if (typeof value === 'number') {
    return value
  }
  if (typeof value === 'string' && value.trim() !== '') {
    const parsed = Number(value)
    return Number.isFinite(parsed) ? parsed : undefined
  }
  return undefined
}

function singleValue(field: FormField): string | undefined {
  const value = rawValue(field)
  if (value == null || value === '') {
    return undefined
  }
  return String(value)
}

function multiValue(field: FormField): string[] {
  const value = rawValue(field)
  if (!Array.isArray(value)) {
    return []
  }
  return value.map((item) => String(item))
}

function fileList(field: FormField): File[] {
  return props.pendingFiles[keyOf(field)] ?? []
}

function updateFiles(field: FormField, files: File[]): void {
  emit('update:pendingFiles', { ...props.pendingFiles, [keyOf(field)]: files })
}

function disabledDate(field: FormField) {
  const limit = field.dateLimit ?? 'NONE'
  const today = new Date(`${todayString()}T00:00:00`)
  return (date: Date): boolean => {
    if (limit === 'NOT_BEFORE_TODAY') {
      return date.getTime() < today.getTime()
    }
    if (limit === 'NOT_AFTER_TODAY') {
      return date.getTime() > today.getTime()
    }
    if (limit === 'CUSTOM' && field.dateMin) {
      return date.getTime() < new Date(`${field.dateMin}T00:00:00`).getTime()
    }
    return false
  }
}

// ----------------------------------------------------------------------
// 只读显示
// ----------------------------------------------------------------------

function optionLabel(field: FormField, value: unknown): string {
  const options = field.options ?? []
  if (Array.isArray(value)) {
    const labels = value.map((item) => options.find((o) => o.value === String(item))?.label ?? String(item))
    return labels.join('、')
  }
  return options.find((option) => option.value === String(value))?.label ?? String(value ?? '')
}

/**
 * 引用类字段的显示名解析。
 *
 * 解析失败（无权查看选项 / 选项列表尚未返回）回落 `#id`：宁可显示一个编号，
 * 也不要显示空白 —— 空白会让查看者以为「用户没填」，而编号至少能证明有值。
 */
function refLabel(field: FormField, value: unknown): string {
  const id = String(value ?? '')
  switch (field.type) {
    case 'USER': {
      const hit = userOptions.value.find((user) => user.id === Number(id))
      return hit ? `${hit.displayName}${hit.dimission ? '（离职）' : ''}` : `#${id}`
    }
    case 'DEVICE': {
      const hit = deviceOptions.value.find((device) => device.id === Number(id))
      return hit ? `${hit.deviceName}（${hit.assetNo}）` : `#${id}`
    }
    case 'BIZ_GROUP':
    // 一级分部选择取值也是部门 id，显示名解析与部门选择一致
    case 'ROOT_DEPT': {
      const hit = groupOptions.value.find((item) => item.id === Number(id))
      return hit ? hit.deptName : `#${id}`
    }
    case 'DEVICE_CATEGORY':
    // 二级分类与一级分类同为分类 id，显示名解析共用一份选项
    case 'DEVICE_SUBCATEGORY': {
      const hit = categoryOptions.value.find((item) => item.id === Number(id))
      return hit ? hit.categoryName : `#${id}`
    }
    case 'DEVICE_MODEL': {
      const hit = modelOptions.value.find((item) => item.id === Number(id))
      return hit ? `${hit.modelName}${hit.brand ? ' · ' + hit.brand : ''}` : `#${id}`
    }
    default:
      return id
  }
}

function displayText(field: FormField): string {
  const value = rawValue(field)
  const meta = fieldTypeMeta(field.type)
  if (!meta) {
    return ''
  }
  if (value == null || value === '' || (Array.isArray(value) && value.length === 0)) {
    return '-'
  }
  switch (meta.valueKind) {
    case 'OPTION':
    case 'OPTION_MULTI':
      return optionLabel(field, value)
    case 'USER_REF':
    case 'DEVICE_REF':
    case 'GROUP_REF':
    case 'CATEGORY_REF':
    case 'MODEL_REF':
      return refLabel(field, value)
    case 'NUMBER':
      return `${value}${field.unit ?? ''}`
    case 'FILE':
      return Array.isArray(value) ? `已上传 ${value.length} 个附件` : String(value)
    default:
      return Array.isArray(value) ? value.map((item) => String(item)).join('、') : String(value)
  }
}

// ----------------------------------------------------------------------
// 引用类选项懒加载
// ----------------------------------------------------------------------

async function loadRefOptions(): Promise<void> {
  const types = new Set(editableFields.value.map((field) => field.type))
  if (types.has('USER')) {
    try {
      userOptions.value = await userApi.options()
    } catch {
      // 无权或网络异常：保留为空，只读态回落 #id
    }
  }
  if (types.has('DEVICE')) {
    try {
      deviceOptions.value = await orderApi.selectableDevices()
    } catch {
      // 同上
    }
  }
  // 部门选择与「一级分部选择」共用同一份部门选项（后者在渲染时过滤到一级）
  if (types.has('BIZ_GROUP') || types.has('ROOT_DEPT')) {
    try {
      groupOptions.value = await departmentApi.options()
    } catch {
      // 普通员工无 staff:view 权限（部门选项接口要求它），回落 #id
    }
  }
  // 设备分类（V59）：一级 / 二级共用一个扁平列表，二级的选项按**当前选中的一级**过滤。
  // 这里刻意只发一次请求：树是一次性全量下发的，再按一级请求一遍只会出现
  // 「两次响应不一致」这类难查问题（与原提交页同一取向）。
  if (types.has('DEVICE_CATEGORY') || types.has('DEVICE_SUBCATEGORY')) {
    try {
      categoryOptions.value = await deviceCategoryApi.options()
    } catch {
      // 分类是借用单的必填依据：取不到就留空，由后端在提交时报字段级错误
    }
  }
  if (types.has('DEVICE_MODEL')) {
    modelOptionsLoading.value = true
    try {
      modelOptions.value = await deviceModelApi.options()
    } catch {
      // 同上
    } finally {
      modelOptionsLoading.value = false
    }
  }
}

/**
 * 二级分类选项：按**同一表单内所选的一级分类**过滤。
 *
 * <p>为什么必须联动而不是列全部二级：一级与二级在值域上都是分类 id，
 * 列出全部会让「电脑」下面出现「办公用品」这种属于另一棵树的选项，
 * 用户选了下单，交付时按一级校验必然不匹配。
 */
const primaryCategoryField = computed(() =>
  fields.value.find((field) => field.type === 'DEVICE_CATEGORY')
)

const subCategoryOptions = computed(() => {
  const primaryField = primaryCategoryField.value
  if (!primaryField) {
    return []
  }
  const primary = String(rawValue(primaryField) ?? '')
  if (!primary) {
    return []
  }
  return categoryOptions.value.filter((item) => item.level === 2 && String(item.parentId) === primary)
})

/** 一级分类选项：只列 level=1（后端已按启用过滤，前端只做层级收窄） */
const primaryCategoryOptions = computed(() =>
  categoryOptions.value.filter((item) => item.level === 1)
)

/**
 * 二级分类的占位提示：一级没选时说「请先选择」，选了但没有子类时说「可跳过」。
 *
 * <p>与改造前提交页的措辞保持一致 —— 那个页面已经用真实用户验证过这两种说法
 * 不会引起「是不是坏了」的误解。
 */
const subCategoryPlaceholder = computed(() => {
  const primaryField = primaryCategoryField.value
  const primary = primaryField ? String(rawValue(primaryField) ?? '') : ''
  if (!primary) {
    return '请先选择设备类别'
  }
  return subCategoryOptions.value.length === 0 ? '该类别下没有二级分类，可跳过' : '选填，不选表示该类别下任意子类'
})

onMounted(loadRefOptions)
watch(() => props.schema, loadRefOptions)
</script>

<template>
  <div class="ts-form-render">
    <el-empty v-if="fields.length === 0" :image-size="60" description="该表单没有字段" />

    <!-- 只读回显 -->
    <el-descriptions v-else-if="readonly" :column="isMobile ? 1 : 2" border size="small">
      <template v-for="(field, index) in fields" :key="`ro-${index}`">
        <el-descriptions-item v-if="field.type === 'DESCRIPTION'" :span="isMobile ? 1 : 2" label="说明">
          <span class="ts-form-render__desc">{{ field.content }}</span>
        </el-descriptions-item>
        <el-descriptions-item v-else-if="field.type === 'DIVIDER'" :span="isMobile ? 1 : 2">
          <el-divider />
        </el-descriptions-item>
        <el-descriptions-item
          v-else
          :label="field.label ?? field.key ?? ''"
          :span="isMobile ? 1 : field.width === 2 ? 1 : 2"
        >
          <template v-if="field.type === 'FILE' || field.type === 'IMAGE'">
            <span class="ts-text-hint">附件已随单上传，见「工单附件」区</span>
          </template>
          <template v-else>{{ displayText(field) }}</template>
        </el-descriptions-item>
      </template>
    </el-descriptions>

    <!-- 编辑态 -->
    <div v-else class="ts-form-render__grid">
      <div
        v-for="(field, index) in fields"
        :key="`ed-${index}`"
        class="ts-form-render__cell"
        :style="{ gridColumn: spanOf(field) }"
      >
        <!-- 说明文字 -->
        <div v-if="field.type === 'DESCRIPTION'" class="ts-form-render__notice">
          {{ field.content }}
        </div>

        <!-- 分隔线 -->
        <el-divider v-else-if="field.type === 'DIVIDER'" />

        <el-form-item
          v-else
          :label="field.label ?? ''"
          :required="field.required === true"
          :error="props.errors[keyOf(field)]"
          :label-position="isMobile ? 'top' : 'right'"
          label-width="104px"
          class="ts-form-render__item"
        >
          <el-input
            v-if="field.type === 'TEXT'"
            :model-value="textValue(field)"
            :placeholder="field.placeholder ?? undefined"
            :maxlength="field.maxLength ?? undefined"
            clearable
            @update:model-value="(v) => update(field, v)"
          />

          <el-input
            v-else-if="field.type === 'TEXTAREA'"
            :model-value="textValue(field)"
            type="textarea"
            :rows="3"
            :placeholder="field.placeholder ?? undefined"
            :maxlength="field.maxLength ?? undefined"
            show-word-limit
            @update:model-value="(v) => update(field, v)"
          />

          <el-input-number
            v-else-if="field.type === 'NUMBER'"
            :model-value="numberValue(field)"
            :min="field.min ?? undefined"
            :max="field.max ?? undefined"
            :precision="field.precision ?? undefined"
            :placeholder="field.placeholder ?? undefined"
            controls-position="right"
            class="ts-form-render__number"
            @update:model-value="(v) => update(field, v)"
          />

          <el-date-picker
            v-else-if="field.type === 'DATE'"
            :model-value="singleValue(field)"
            type="date"
            value-format="YYYY-MM-DD"
            :placeholder="field.placeholder ?? '请选择日期'"
            :disabled-date="disabledDate(field)"
            class="ts-form-render__full"
            @update:model-value="(v) => update(field, v)"
          />

          <el-date-picker
            v-else-if="field.type === 'DATETIME'"
            :model-value="singleValue(field)"
            type="datetime"
            format="YYYY-MM-DD HH:mm:ss"
            value-format="YYYY-MM-DD HH:mm:ss"
            :placeholder="field.placeholder ?? '请选择日期时间'"
            :disabled-date="disabledDate(field)"
            class="ts-form-render__full"
            @update:model-value="(v) => update(field, v)"
          />

          <el-select
            v-else-if="field.type === 'SELECT'"
            :model-value="singleValue(field)"
            :placeholder="field.placeholder ?? '请选择'"
            clearable
            filterable
            class="ts-form-render__full"
            @update:model-value="(v) => update(field, v)"
          >
            <el-option
              v-for="option in field.options ?? []"
              :key="option.value"
              :label="selectOptionLabel(option)"
              :value="option.value"
            />
          </el-select>

          <el-select
            v-else-if="field.type === 'MULTI_SELECT'"
            :model-value="multiValue(field)"
            multiple
            collapse-tags
            collapse-tags-tooltip
            :placeholder="field.placeholder ?? '请选择（可多选）'"
            clearable
            filterable
            class="ts-form-render__full"
            @update:model-value="(v) => update(field, v)"
          >
            <el-option
              v-for="option in field.options ?? []"
              :key="option.value"
              :label="selectOptionLabel(option)"
              :value="option.value"
            />
          </el-select>

          <el-radio-group
            v-else-if="field.type === 'RADIO'"
            :model-value="singleValue(field)"
            @update:model-value="(v) => update(field, v)"
          >
            <el-radio v-for="option in field.options ?? []" :key="option.value" :value="option.value">
              {{ option.label }}
            </el-radio>
          </el-radio-group>

          <el-checkbox-group
            v-else-if="field.type === 'CHECKBOX'"
            :model-value="multiValue(field)"
            @update:model-value="(v) => update(field, v)"
          >
            <el-checkbox v-for="option in field.options ?? []" :key="option.value" :value="option.value">
              {{ option.label }}
            </el-checkbox>
          </el-checkbox-group>

          <el-select
            v-else-if="field.type === 'USER'"
            :model-value="singleValue(field)"
            :placeholder="field.placeholder ?? '请选择人员'"
            filterable
            clearable
            class="ts-form-render__full"
            @update:model-value="(v) => update(field, v)"
          >
            <el-option
              v-for="user in userOptions"
              :key="user.id"
              :label="`${user.displayName}${user.departmentName ? ' · ' + user.departmentName : ''}`"
              :value="String(user.id)"
            />
          </el-select>

          <el-select
            v-else-if="field.type === 'DEVICE'"
            :model-value="singleValue(field)"
            :placeholder="field.placeholder ?? '请选择设备'"
            filterable
            clearable
            class="ts-form-render__full"
            @update:model-value="(v) => update(field, v)"
          >
            <el-option
              v-for="device in deviceOptions"
              :key="device.id"
              :label="`${device.deviceName}（${device.assetNo}）`"
              :value="String(device.id)"
            />
          </el-select>

          <el-select
            v-else-if="field.type === 'BIZ_GROUP'"
            :model-value="singleValue(field)"
            :placeholder="field.placeholder ?? '请选择部门'"
            filterable
            clearable
            class="ts-form-render__full"
            @update:model-value="(v) => update(field, v)"
          >
            <el-option
              v-for="group in groupOptions"
              :key="group.id"
              :label="group.deptName"
              :value="String(group.id)"
            />
          </el-select>

          <!-- 一级分部选择（V53）：与「部门选择」同构，但**只列组织树一级部门**，无开关可切 -->
          <el-select
            v-else-if="field.type === 'ROOT_DEPT'"
            :model-value="singleValue(field)"
            :placeholder="field.placeholder ?? '请选择分部'"
            filterable
            clearable
            class="ts-form-render__full"
            @update:model-value="(v) => update(field, v)"
          >
            <el-option
              v-for="dept in rootDeptOptions"
              :key="dept.id"
              :label="dept.deptName"
              :value="String(dept.id)"
            />
          </el-select>

          <!-- 设备分类（V59）：只列一级；借用单交付时按它做三层校验的第一层 -->
          <el-select
            v-else-if="field.type === 'DEVICE_CATEGORY'"
            :model-value="singleValue(field)"
            :placeholder="field.placeholder ?? '请选择设备类别'"
            filterable
            clearable
            class="ts-form-render__full"
            @update:model-value="(v) => updatePrimaryCategory(field, v)"
          >
            <el-option
              v-for="category in primaryCategoryOptions"
              :key="category.id"
              :label="category.categoryName"
              :value="String(category.id)"
            />
          </el-select>

          <!-- 设备二级分类（V59）：选项随所选一级联动，一级没选时整个禁用 -->
          <el-select
            v-else-if="field.type === 'DEVICE_SUBCATEGORY'"
            :model-value="singleValue(field)"
            :placeholder="subCategoryPlaceholder"
            filterable
            clearable
            :disabled="subCategoryOptions.length === 0"
            class="ts-form-render__full"
            @update:model-value="(v) => update(field, v)"
          >
            <el-option
              v-for="category in subCategoryOptions"
              :key="category.id"
              :label="category.categoryName"
              :value="String(category.id)"
            />
          </el-select>

          <!-- 设备型号（V59 / R3）：读型号字典，存 model_id（不做字符串比对） -->
          <el-select
            v-else-if="field.type === 'DEVICE_MODEL'"
            :model-value="singleValue(field)"
            :placeholder="field.placeholder ?? '选填，留空表示同类别内哪台都行'"
            filterable
            clearable
            :loading="modelOptionsLoading"
            class="ts-form-render__full"
            @update:model-value="(v) => update(field, v)"
          >
            <el-option
              v-for="model in modelOptions"
              :key="model.id"
              :label="`${model.modelName}${model.brand ? ' · ' + model.brand : ''}`"
              :value="String(model.id)"
            />
          </el-select>

          <PendingAttachmentPicker
            v-else-if="field.type === 'FILE' || field.type === 'IMAGE'"
            :model-value="fileList(field)"
            biz-type="CUSTOM_ORDER"
            :label="field.label ?? '附件'"
            :max-count="field.maxCount ?? 3"
            :max-size-mb="field.maxSizeMb ?? 10"
            @update:model-value="(files) => updateFiles(field, files)"
          />

          <div v-if="field.help" class="ts-text-hint ts-form-render__help">{{ field.help }}</div>
        </el-form-item>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ts-form-render__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 4px 20px;
}

.ts-form-render__cell {
  min-width: 0;
}

.ts-form-render__item {
  margin-bottom: 16px;
}

/* 布局元素占满整行，且不需要 label 列 */
.ts-form-render__notice {
  padding: 8px 12px;
  margin-bottom: 16px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--el-text-color-regular);
  background: var(--el-fill-color-light);
  border-left: 3px solid var(--el-color-primary);
  border-radius: 4px;
  white-space: pre-wrap;
}

.ts-form-render__desc {
  white-space: pre-wrap;
}

.ts-form-render__full {
  width: 100%;
}

.ts-form-render__number {
  width: 100%;
}

.ts-form-render__help {
  margin-top: 2px;
  font-size: 12px;
}

@media (max-width: 767px) {
  .ts-form-render__grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
