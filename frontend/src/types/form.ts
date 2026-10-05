/**
 * 动态表单类型（ 自定义申请类型）
 *
 * <b>与后端的关系</b>：本文件的 {@link FormFieldTypeCode} 是后端
 * `com.enterprise.ticket.common.constant.FormFieldType` 的<b>逐字镜像</b> ——
 * 枚举名即 `schema_json` 里存的 `type` 值。新增字段类型时两端必须同步（单测里有对齐断言），
 * 否则会出现「前端能拖进画布、后端不认识该类型」这类最难排查的缺陷。
 *
 * <b>为什么字段定义「平铺」而非按类型分成多种结构</b>：与后端 FormField 保持一致 ——
 * 某一类型的专属属性对其它类型为空。用户在设计器里切换字段类型时（文本 → 数字），
 * 公共属性（key / label / required）不会丢；若用判别联合（discriminated union），
 * 切换类型就必须重建整个对象。
 */

/** 字段类型码（与后端枚举同名同值） */
export type FormFieldTypeCode =
  | 'TEXT'
  | 'TEXTAREA'
  | 'NUMBER'
  | 'DATE'
  | 'DATETIME'
  | 'SELECT'
  | 'MULTI_SELECT'
  | 'RADIO'
  | 'CHECKBOX'
  | 'FILE'
  | 'IMAGE'
  | 'USER'
  | 'DEVICE'
  | 'BIZ_GROUP'
  | 'DESCRIPTION'
  | 'DIVIDER'

/** 设计器左侧面板分组 */
export type FieldCategoryCode = 'BASIC' | 'SELECT' | 'ADVANCED' | 'LAYOUT'

/**
 * 值的种类 —— 决定服务端 / 前端如何校验该字段。
 *
 * 刻意不复用 JS 类型：这里要描述的是「校验语义」（单选还是多选、引用还是自由文本），
 * 而不是某种语言里的类型。
 */
export type ValueKind =
  | 'NONE'
  | 'TEXT'
  | 'NUMBER'
  | 'DATE'
  | 'DATETIME'
  | 'OPTION'
  | 'OPTION_MULTI'
  | 'FILE'
  | 'USER_REF'
  | 'DEVICE_REF'
  | 'GROUP_REF'

/** 字段类型元数据（设计器面板 / 渲染器 / 校验器共用一份，避免三处各写一遍） */
export interface FieldTypeMeta {
  type: FormFieldTypeCode
  /** 中文名（面板与属性面板标题） */
  label: string
  /** 面板分组 */
  category: FieldCategoryCode
  /** 值的种类（校验依据） */
  valueKind: ValueKind
  /** 是否需要配置「选项列表」 */
  supportsOptions: boolean
  /** 是否多人共用字段类型（设计器里的说明文案） */
  hint?: string
}

/** 选项（value 落库、label 展示，分离是为了「文案改了历史数据仍然可解析」） */
export interface FormOption {
  value: string
  label: string
}

/** 字段定义（与后端 FormField 一一对应） */
export interface FormField {
  /** 字段 key：字母/下划线开头，其后字母数字下划线；布局类字段为空 */
  key?: string | null
  label?: string | null
  type: FormFieldTypeCode
  required?: boolean | null
  placeholder?: string | null
  /** 帮助说明（展示在控件下方） */
  help?: string | null
  /** 栅格宽度：1 = 整行，2 = 半行 */
  width?: number | null
  /** 选项列表（仅选项类字段） */
  options?: FormOption[] | null
  /** 默认值（文本类为字符串；多选类为 JSON 数组字符串） */
  defaultValue?: string | null

  // 数字
  min?: number | null
  max?: number | null
  /** 小数位数 0–4 */
  precision?: number | null
  /** 单位（展示用） */
  unit?: string | null

  // 文本
  minLength?: number | null
  maxLength?: number | null
  pattern?: string | null

  // 日期 / 日期时间
  /** NONE / NOT_BEFORE_TODAY / NOT_AFTER_TODAY / CUSTOM */
  dateLimit?: string | null
  dateMin?: string | null
  dateMax?: string | null

  // 附件 / 图片
  maxCount?: number | null
  fileTypes?: string | null
  maxSizeMb?: number | null

  // 布局类
  content?: string | null
}

/** 表单定义（对应 form_template_version.schema_json 的根对象） */
export interface FormSchema {
  fields: FormField[]
}

/** 表单数据：字段 key → 值 */
export type FormData = Record<string, unknown>

/** 字段级错误：key → 错误文案（布局类字段不参与） */
export type FormErrors = Record<string, string>

// ----------------------------------------------------------------------
// 元数据表
// ----------------------------------------------------------------------

/** 按面板分组顺序排列的全部字段类型元数据 */
export const FIELD_TYPE_META: FieldTypeMeta[] = [
  { type: 'TEXT', label: '单行文本', category: 'BASIC', valueKind: 'TEXT', supportsOptions: false },
  { type: 'TEXTAREA', label: '多行文本', category: 'BASIC', valueKind: 'TEXT', supportsOptions: false },
  { type: 'NUMBER', label: '数字', category: 'BASIC', valueKind: 'NUMBER', supportsOptions: false },
  { type: 'DATE', label: '日期', category: 'BASIC', valueKind: 'DATE', supportsOptions: false },
  { type: 'DATETIME', label: '日期时间', category: 'BASIC', valueKind: 'DATETIME', supportsOptions: false },
  { type: 'SELECT', label: '单选下拉', category: 'SELECT', valueKind: 'OPTION', supportsOptions: true },
  { type: 'MULTI_SELECT', label: '多选下拉', category: 'SELECT', valueKind: 'OPTION_MULTI', supportsOptions: true },
  { type: 'RADIO', label: '单选框', category: 'SELECT', valueKind: 'OPTION', supportsOptions: true },
  { type: 'CHECKBOX', label: '复选框', category: 'SELECT', valueKind: 'OPTION_MULTI', supportsOptions: true },
  { type: 'FILE', label: '附件上传', category: 'ADVANCED', valueKind: 'FILE', supportsOptions: false },
  { type: 'IMAGE', label: '图片上传', category: 'ADVANCED', valueKind: 'FILE', supportsOptions: false },
  { type: 'USER', label: '人员选择', category: 'ADVANCED', valueKind: 'USER_REF', supportsOptions: false },
  { type: 'DEVICE', label: '设备选择', category: 'ADVANCED', valueKind: 'DEVICE_REF', supportsOptions: false },
  { type: 'BIZ_GROUP', label: '部门选择', category: 'ADVANCED', valueKind: 'GROUP_REF', supportsOptions: false },
  { type: 'DESCRIPTION', label: '说明文字', category: 'LAYOUT', valueKind: 'NONE', supportsOptions: false },
  { type: 'DIVIDER', label: '分隔线', category: 'LAYOUT', valueKind: 'NONE', supportsOptions: false }
]

/** 设计器面板分组顺序与中文名 */
export const FIELD_CATEGORIES: Array<{ code: FieldCategoryCode; label: string }> = [
  { code: 'BASIC', label: '基础字段' },
  { code: 'SELECT', label: '选择字段' },
  { code: 'ADVANCED', label: '高级字段' },
  { code: 'LAYOUT', label: '布局元素' }
]

const META_BY_TYPE = new Map<FormFieldTypeCode, FieldTypeMeta>(FIELD_TYPE_META.map((m) => [m.type, m]))

/** 取字段类型元数据；未知类型返回 undefined（调用方据此降级为「不支持」） */
export function fieldTypeMeta(type: FormFieldTypeCode | string): FieldTypeMeta | undefined {
  return META_BY_TYPE.get(type as FormFieldTypeCode)
}

/** 是否数据字段（布局类不产生数据、不参与校验） */
export function isDataFieldType(type: FormFieldTypeCode | string): boolean {
  return fieldTypeMeta(type)?.valueKind !== 'NONE'
}

/** 是否多值字段（值为数组） */
export function isMultiValued(type: FormFieldTypeCode | string): boolean {
  const kind = fieldTypeMeta(type)?.valueKind
  return kind === 'OPTION_MULTI' || kind === 'FILE'
}

/** 类型中文名；未知类型回落原值 */
export function fieldTypeLabel(type: FormFieldTypeCode | string): string {
  return fieldTypeMeta(type)?.label ?? type
}

// ----------------------------------------------------------------------
// 构造与工具
// ----------------------------------------------------------------------

/** 空表单定义 */
export function emptySchema(): FormSchema {
  return { fields: [] }
}

/** 容错取字段列表（后端可能返回 null） */
export function schemaFields(schema?: FormSchema | null): FormField[] {
  return schema?.fields ?? []
}

/** 取数据字段（排除布局类） */
export function dataFields(schema?: FormSchema | null): FormField[] {
  return schemaFields(schema).filter((field) => isDataFieldType(field.type))
}

/**
 * 字段 key 合法性：字母/下划线开头，只含字母数字下划线。
 * 与后端 `FormSchemaValidator.KEY_PATTERN` 逐字一致。
 */
export const FIELD_KEY_PATTERN = /^[A-Za-z_][A-Za-z0-9_]*$/

/** 类型编码合法性（与后端 TYPE_CODE_PATTERN 一致：字母开头，2–20 位） */
export const TYPE_CODE_PATTERN = /^[A-Za-z][A-Za-z0-9_]{1,19}$/

/** 工单编号前缀合法性（后端 PREFIX_PATTERN：字母开头，2–10 位） */
export const ORDER_PREFIX_PATTERN = /^[A-Za-z][A-Za-z0-9]{1,9}$/

/** 由中文标签生成一个候选 key（拼音不可得，退化为 field_序号 / 语义化占位） */
export function suggestFieldKey(index: number): string {
  return `field_${index + 1}`
}

/** 依据已有 key 集合生成唯一 key（避免手动撞名） */
export function uniqueFieldKey(base: string, existing: readonly string[]): string {
  const used = new Set(existing)
  if (!used.has(base)) {
    return base
  }
  let i = 2
  while (used.has(`${base}_${i}`)) {
    i += 1
  }
  return `${base}_${i}`
}

/** 新建一个字段（按类型填默认属性；key 由调用方补齐） */
export function createField(type: FormFieldTypeCode, index: number): FormField {
  const meta = fieldTypeMeta(type)
  const field: FormField = {
    type,
    label: meta?.label ?? type,
    required: false,
    width: 1
  }
  if (meta?.supportsOptions) {
    field.options = [
      { value: 'option1', label: '选项一' },
      { value: 'option2', label: '选项二' }
    ]
  }
  if (type === 'DESCRIPTION') {
    field.label = '说明'
    field.content = ''
    field.required = false
  }
  if (type === 'DIVIDER') {
    field.label = '分隔线'
    field.required = false
  }
  if (isDataFieldType(type)) {
    field.key = suggestFieldKey(index)
  }
  if (type === 'TEXT') {
    field.maxLength = 200
  }
  if (type === 'TEXTAREA') {
    field.maxLength = 500
  }
  if (type === 'NUMBER') {
    field.precision = 0
  }
  if (type === 'DATE' || type === 'DATETIME') {
    field.dateLimit = 'NONE'
  }
  if (type === 'FILE' || type === 'IMAGE') {
    field.maxCount = 3
    field.maxSizeMb = 10
  }
  return field
}

/** 深拷贝字段（设计器增删改时避免引用共享） */
export function cloneField(field: FormField): FormField {
  return {
    ...field,
    options: field.options ? field.options.map((o) => ({ ...o })) : field.options
  }
}

/** 深拷贝 schema */
export function cloneSchema(schema?: FormSchema | null): FormSchema {
  return { fields: schemaFields(schema).map(cloneField) }
}

// ----------------------------------------------------------------------
// 前端校验（与后端 FormDataValidator 的规则保持同口径，用于提交前的即时反馈）
//
// 说明：前端校验只为「即时提示、提升体验」，真正的准入以后端为准（ 最小权限 /
// 服务端是唯一事实来源）。因此这份实现只覆盖可在前端判断的规则：
// 必填、长度、数字范围、日期限制、选项命中、多值非空；不覆盖「引用类 id 是否真实存在」
// —— 那必须查库，只能在服务端判定。
// ----------------------------------------------------------------------

/** 值是否「空」（null / undefined / 空串 / 空数组） */
export function isEmptyValue(value: unknown): boolean {
  if (value == null) {
    return true
  }
  if (typeof value === 'string') {
    return value.trim() === ''
  }
  if (Array.isArray(value)) {
    return value.length === 0
  }
  return false
}

function toNumber(value: unknown): number | null {
  if (typeof value === 'number') {
    return Number.isFinite(value) ? value : null
  }
  if (typeof value === 'string' && value.trim() !== '') {
    const parsed = Number(value)
    return Number.isFinite(parsed) ? parsed : null
  }
  return null
}

/** 单字段校验；返回错误文案，null 表示通过 */
export function validateField(field: FormField, value: unknown): string | null {
  const meta = fieldTypeMeta(field.type)
  if (!meta || meta.valueKind === 'NONE') {
    return null
  }
  const empty = isEmptyValue(value)
  const label = field.label ?? field.key ?? '该项'

  if (empty) {
    return field.required ? `「${label}」为必填项` : null
  }

  switch (meta.valueKind) {
    case 'TEXT': {
      if (typeof value !== 'string') {
        return `「${label}」格式不正确`
      }
      const len = value.trim().length
      if (field.minLength != null && len < field.minLength) {
        return `「${label}」至少 ${field.minLength} 个字符`
      }
      if (field.maxLength != null && len > field.maxLength) {
        return `「${label}」不能超过 ${field.maxLength} 个字符`
      }
      if (field.pattern) {
        try {
          if (!new RegExp(field.pattern).test(value)) {
            return `「${label}」格式不符合要求`
          }
        } catch {
          // 非法正则由发布校验拦截；前端遇到损坏配置时不阻断提交
        }
      }
      return null
    }
    case 'NUMBER': {
      const num = toNumber(value)
      if (num == null) {
        return `「${label}」必须是数字`
      }
      if (field.min != null && num < field.min) {
        return `「${label}」不能小于 ${field.min}${field.unit ?? ''}`
      }
      if (field.max != null && num > field.max) {
        return `「${label}」不能大于 ${field.max}${field.unit ?? ''}`
      }
      return null
    }
    case 'DATE':
      return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value)
        ? validateDateLimit(field, value, label)
        : `「${label}」日期格式不正确`
    case 'DATETIME':
      return typeof value === 'string' && value.trim() !== ''
        ? validateDateLimit(field, value.slice(0, 10), label)
        : `「${label}」时间格式不正确`
    case 'OPTION': {
      const options = field.options ?? []
      const hit = options.some((option) => option.value === value)
      return hit ? null : `「${label}」取值不在可选范围内`
    }
    case 'OPTION_MULTI': {
      if (!Array.isArray(value)) {
        return `「${label}」格式不正确`
      }
      const allowed = new Set((field.options ?? []).map((option) => option.value))
      const bad = value.find((item) => !allowed.has(String(item)))
      return bad == null ? null : `「${label}」包含不可选的取值`
    }
    case 'FILE': {
      if (!Array.isArray(value)) {
        return `「${label}」格式不正确`
      }
      if (field.maxCount != null && value.length > field.maxCount) {
        return `「${label}」最多上传 ${field.maxCount} 个`
      }
      return null
    }
    case 'USER_REF':
    case 'DEVICE_REF':
    case 'GROUP_REF':
      // 存在性只能由服务端查库判定，前端不判
      return null
    default:
      return null
  }
}

function validateDateLimit(field: FormField, date: string, label: string): string | null {
  const limit = field.dateLimit ?? 'NONE'
  const today = todayString()
  if (limit === 'NOT_BEFORE_TODAY' && date < today) {
    return `「${label}」不能早于今天`
  }
  if (limit === 'NOT_AFTER_TODAY' && date > today) {
    return `「${label}」不能晚于今天`
  }
  if (limit === 'CUSTOM') {
    if (field.dateMin && date < field.dateMin) {
      return `「${label}」不能早于 ${field.dateMin}`
    }
    if (field.dateMax && date > field.dateMax) {
      return `「${label}」不能晚于 ${field.dateMax}`
    }
  }
  return null
}

/** 校验整份表单数据，返回字段级错误集合 */
export function validateFormData(schema: FormSchema | null | undefined, data: FormData): FormErrors {
  const errors: FormErrors = {}
  for (const field of dataFields(schema)) {
    const key = field.key
    if (!key) {
      continue
    }
    const message = validateField(field, data[key])
    if (message) {
      errors[key] = message
    }
  }
  return errors
}

/** 本地今天（yyyy-MM-dd） */
export function todayString(): string {
  const now = new Date()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  return `${now.getFullYear()}-${month}-${day}`
}

/** 是否含错误 */
export function hasErrors(errors: FormErrors): boolean {
  return Object.keys(errors).length > 0
}

// ----------------------------------------------------------------------
// 表单模板（form_template / form_template_version）
// ----------------------------------------------------------------------

/** 模板状态 */
export type FormTemplateStatusCode = 'DRAFT' | 'PUBLISHED' | 'DISABLED'

/** 模板列表项（不含 schema） */
export interface FormTemplateItem {
  id: number
  templateName: string
  description?: string | null
  status: FormTemplateStatusCode
  statusLabel?: string | null
  /** 最新已发布版本号；从未发布为 null */
  latestVersionNo?: number | null
  latestPublishedVersionId?: number | null
  /** 是否存在未发布的草稿 */
  hasDraft?: boolean | null
  /** 字段数（有草稿算草稿，否则算最新已发布版本） */
  fieldCount?: number | null
  createdAt?: string | null
  updatedAt?: string | null
}

/** 模板详情（含 schema：有草稿给草稿，无草稿给最新已发布版本） */
export interface FormTemplateDetail {
  id: number
  templateName: string
  description?: string | null
  status: FormTemplateStatusCode
  statusLabel?: string | null
  /** 当前可编辑/展示的版本 id */
  versionId?: number | null
  versionNo?: number | null
  /** 当前展示的版本是否为草稿 */
  draft?: boolean | null
  published?: boolean | null
  latestPublishedVersionId?: number | null
  latestPublishedVersionNo?: number | null
  /** 是否已被申请类型引用（引用时不允许删除） */
  referenced?: boolean | null
  schema?: FormSchema | null
  createdAt?: string | null
  updatedAt?: string | null
}

/** 模板版本（列表不含 schema，详情含） */
export interface FormTemplateVersion {
  id: number
  templateId: number
  versionNo: number
  draft?: boolean | null
  fieldCount?: number | null
  publishedAt?: string | null
  publishedBy?: number | null
  publishedByName?: string | null
  schema?: FormSchema | null
}

/** 保存模板请求（整体覆盖式：看到什么就是存了什么） */
export interface FormTemplatePayload {
  templateName: string
  description?: string | null
  schema: FormSchema
}

/**
 * 发布前的本地校验（与后端 FormSchemaValidator 同口径的关键项）。
 *
 * 目的不是替代服务端校验，而是把「能提前发现的错误」在点发布时立刻告诉配置者——
 * 此刻他正开着设计器，改起来最快。
 */
export function validateSchemaForPublish(schema: FormSchema): string[] {
  const problems: string[] = []
  const fields = schemaFields(schema)
  if (fields.length === 0) {
    problems.push('表单至少需要 1 个字段')
    return problems
  }
  if (fields.length > 50) {
    problems.push('表单字段数量不能超过 50 个')
  }
  const keys = new Set<string>()
  fields.forEach((field, index) => {
    const position = `第 ${index + 1} 个字段`
    const meta = fieldTypeMeta(field.type)
    if (!meta) {
      problems.push(`${position}的字段类型不合法：${field.type}`)
      return
    }
    if (field.width != null && field.width !== 1 && field.width !== 2) {
      problems.push(`${position}的栅格宽度只能是 1 或 2`)
    }
    if (field.type === 'DESCRIPTION') {
      if (!field.content || field.content.trim() === '') {
        problems.push(`${position}（说明文字）的内容不能为空`)
      }
      return
    }
    if (field.type === 'DIVIDER') {
      return
    }
    if (!field.label || field.label.trim() === '') {
      problems.push(`${position}的显示名称不能为空`)
    }
    const key = field.key ?? ''
    if (!key.trim()) {
      problems.push(`字段「${field.label ?? position}」的字段 key 不能为空`)
    } else if (!FIELD_KEY_PATTERN.test(key)) {
      problems.push(`字段 key「${key}」不合法：必须以字母或下划线开头，只能包含字母、数字、下划线`)
    } else if (keys.has(key)) {
      problems.push(`字段 key「${key}」重复，同一表单内 key 必须唯一`)
    } else {
      keys.add(key)
    }
    if (meta.supportsOptions) {
      const options = field.options ?? []
      if (options.length === 0) {
        problems.push(`${position}（${meta.label}）至少需要配置 1 个选项`)
      }
      const values = new Set<string>()
      for (const option of options) {
        if (!option.value?.trim()) {
          problems.push(`${position}存在选项值为空的选项`)
        } else if (!option.label?.trim()) {
          problems.push(`${position}存在显示名为空的选项`)
        } else if (values.has(option.value)) {
          problems.push(`${position}存在重复的选项值：${option.value}`)
        } else {
          values.add(option.value)
        }
      }
    }
    if (field.type === 'DATE' || field.type === 'DATETIME') {
      if (field.dateLimit === 'CUSTOM' && !field.dateMin && !field.dateMax) {
        problems.push(`${position}选择了「自定义」日期范围，需要至少填写起始或截止日期`)
      }
      if (field.dateMin && field.dateMax && field.dateMin > field.dateMax) {
        problems.push(`${position}的自定义起始日期不能晚于截止日期`)
      }
    }
    // 附件字段不能设为必填（第一期）：附件以「随单上传、绑定工单 id」的方式存储，
    // 值不写进表单数据，因此服务端无从校验「有没有传附件」。若允许必填，
    // 会配出一个「无论怎么填都提交不了」的表单 —— 宁可在发布前拦住。
    if ((field.type === 'FILE' || field.type === 'IMAGE') && field.required === true) {
      problems.push(`${position}（${meta.label}）不能设为必填：附件以「随单上传、绑定工单」的方式存储`)
    }
  })
  return problems
}
