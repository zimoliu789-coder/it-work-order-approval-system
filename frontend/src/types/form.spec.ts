import { describe, expect, it } from 'vitest'
import {
  FIELD_KEY_PATTERN,
  FIELD_TYPE_META,
  ORDER_PREFIX_PATTERN,
  TYPE_CODE_PATTERN,
  cloneSchema,
  createField,
  dataFields,
  fieldTypeMeta,
  hasErrors,
  isDataFieldType,
  isEmptyValue,
  isMultiValued,
  schemaFields,
  uniqueFieldKey,
  validateField,
  validateFormData,
  validateSchemaForPublish,
  type FormField,
  type FormSchema
} from '@/types/form'

/**
 * 动态表单类型与校验单测（ 自定义申请类型）
 *
 * 为什么值得单测：
 * 1. `FIELD_TYPE_META` 是后端 `FormFieldType` 枚举的逐字镜像，字段类型 / 值种类一旦
 *    悄悄漂移，会出现「前端能拖进画布、后端不认识该类型」这类最难排查的线上缺陷。
 * 2. `validateFormData` / `validateSchemaForPublish` 与后端 `FormDataValidator` /
 *    `FormSchemaValidator` 同口径，是「提交前即时反馈」与「发布前拦住半成品」的依据。
 *    把口径固化为断言，任何一端改动导致前端放行 / 拦截错位都会立刻暴露。
 */

function field(partial: Partial<FormField> & Pick<FormField, 'type'>): FormField {
  return { label: '字段', required: false, ...partial }
}

describe('字段类型元数据 FIELD_TYPE_META', () => {
  it('16 种类型齐全且不重复，覆盖后端 FormFieldType 全部枚举', () => {
    const types = FIELD_TYPE_META.map((m) => m.type)
    expect(new Set(types).size).toBe(types.length)
    expect(types.sort()).toEqual(
      [
        'BIZ_GROUP',
        'CHECKBOX',
        'DATE',
        'DATETIME',
        'DESCRIPTION',
        'DEVICE',
        'DIVIDER',
        'FILE',
        'IMAGE',
        'MULTI_SELECT',
        'NUMBER',
        'RADIO',
        'SELECT',
        'TEXT',
        'TEXTAREA',
        'USER'
      ].sort()
    )
  })

  it('每种类型的 category / valueKind 均合法（面板分组与校验依据不缺失）', () => {
    const categories = new Set(['BASIC', 'SELECT', 'ADVANCED', 'LAYOUT'])
    const kinds = new Set([
      'NONE',
      'TEXT',
      'NUMBER',
      'DATE',
      'DATETIME',
      'OPTION',
      'OPTION_MULTI',
      'FILE',
      'USER_REF',
      'DEVICE_REF',
      'GROUP_REF'
    ])
    for (const meta of FIELD_TYPE_META) {
      expect(categories.has(meta.category)).toBe(true)
      expect(kinds.has(meta.valueKind)).toBe(true)
    }
  })

  it('未知类型：元数据返回 undefined，可据其降级为「不支持」', () => {
    expect(fieldTypeMeta('NOT_A_TYPE')).toBeUndefined()
    expect(fieldTypeMeta('')).toBeUndefined()
  })

  it('布局类（说明 / 分隔线）非数据字段，不参与校验', () => {
    expect(isDataFieldType('DESCRIPTION')).toBe(false)
    expect(isDataFieldType('DIVIDER')).toBe(false)
    expect(isDataFieldType('TEXT')).toBe(true)
  })

  it('多值字段仅多选与附件：值为数组', () => {
    expect(isMultiValued('MULTI_SELECT')).toBe(true)
    expect(isMultiValued('CHECKBOX')).toBe(true)
    expect(isMultiValued('FILE')).toBe(true)
    expect(isMultiValued('IMAGE')).toBe(true)
    expect(isMultiValued('SELECT')).toBe(false)
    expect(isMultiValued('TEXT')).toBe(false)
  })
})

describe('字段 key / 编码 / 前缀正则（与后端逐字一致）', () => {
  it('字段 key：字母或下划线开头，只含字母数字下划线', () => {
    expect(FIELD_KEY_PATTERN.test('field_1')).toBe(true)
    expect(FIELD_KEY_PATTERN.test('_x')).toBe(true)
    expect(FIELD_KEY_PATTERN.test('A1_b2')).toBe(true)
    expect(FIELD_KEY_PATTERN.test('1abc')).toBe(false)
    expect(FIELD_KEY_PATTERN.test('has space')).toBe(false)
    expect(FIELD_KEY_PATTERN.test('')).toBe(false)
  })

  it('类型编码：字母开头，2–20 位', () => {
    expect(TYPE_CODE_PATTERN.test('purchase')).toBe(true)
    expect(TYPE_CODE_PATTERN.test('a1')).toBe(true)
    expect(TYPE_CODE_PATTERN.test('a')).toBe(false)
    expect(TYPE_CODE_PATTERN.test('1abc')).toBe(false)
    expect(TYPE_CODE_PATTERN.test('a'.repeat(21))).toBe(false)
  })

  it('工单编号前缀：字母开头，2–10 位，不含下划线', () => {
    expect(ORDER_PREFIX_PATTERN.test('PR')).toBe(true)
    expect(ORDER_PREFIX_PATTERN.test('PRCH01')).toBe(true)
    expect(ORDER_PREFIX_PATTERN.test('P')).toBe(false)
    expect(ORDER_PREFIX_PATTERN.test('P_R')).toBe(false)
    expect(ORDER_PREFIX_PATTERN.test('P'.repeat(11))).toBe(false)
  })
})

describe('构造与工具', () => {
  it('uniqueFieldKey：撞名时追加 _2 / _3', () => {
    expect(uniqueFieldKey('field_1', [])).toBe('field_1')
    expect(uniqueFieldKey('field_1', ['field_1'])).toBe('field_1_2')
    expect(uniqueFieldKey('field_1', ['field_1', 'field_1_2'])).toBe('field_1_3')
  })

  it('createField：数据字段自动补 key，选项类预置两个选项', () => {
    const text = createField('TEXT', 0)
    expect(text.key).toBe('field_1')
    expect(text.width).toBe(1)
    expect(text.maxLength).toBe(200)

    const select = createField('SELECT', 1)
    expect(select.options?.length).toBe(2)
    expect(select.key).toBe('field_2')

    // 布局类不产生数据 → 不分配 key
    expect(createField('DIVIDER', 2).key).toBeUndefined()
    expect(createField('DESCRIPTION', 2).content).toBe('')
  })

  it('cloneSchema：深拷贝字段与选项，改副本不影响原对象', () => {
    const schema: FormSchema = { fields: [createField('SELECT', 0)] }
    const copy = cloneSchema(schema)
    copy.fields[0].label = '改过了'
    copy.fields[0].options![0].label = '改过了'
    expect(schema.fields[0].label).not.toBe('改过了')
    expect(schema.fields[0].options![0].label).not.toBe('改过了')
  })

  it('schemaFields / dataFields：null 容错，且过滤布局类', () => {
    expect(schemaFields(null)).toEqual([])
    expect(schemaFields(undefined)).toEqual([])
    const schema: FormSchema = {
      fields: [field({ type: 'TEXT', key: 'a' }), field({ type: 'DIVIDER' })]
    }
    expect(dataFields(schema).map((f) => f.key)).toEqual(['a'])
  })
})

describe('isEmptyValue', () => {
  it('null / undefined / 空串 / 纯空白 / 空数组 视为空', () => {
    expect(isEmptyValue(null)).toBe(true)
    expect(isEmptyValue(undefined)).toBe(true)
    expect(isEmptyValue('')).toBe(true)
    expect(isEmptyValue('   ')).toBe(true)
    expect(isEmptyValue([])).toBe(true)
  })

  it('数字 0 / false / 非空数组 不视为空（0 是合法值，不能误判为未填）', () => {
    expect(isEmptyValue(0)).toBe(false)
    expect(isEmptyValue(false)).toBe(false)
    expect(isEmptyValue([1])).toBe(false)
    expect(isEmptyValue('x')).toBe(false)
  })
})

describe('validateField（前端即时校验，与后端同口径）', () => {
  it('必填为空 → 报错；非必填为空 → 通过', () => {
    expect(validateField(field({ type: 'TEXT', key: 'a', label: '申请事由', required: true }), '')).toBe(
      '「申请事由」为必填项'
    )
    expect(validateField(field({ type: 'TEXT', key: 'a', label: '申请事由' }), '')).toBeNull()
  })

  it('布局类字段永不报错', () => {
    expect(validateField(field({ type: 'DIVIDER', required: true }), undefined)).toBeNull()
    expect(validateField(field({ type: 'DESCRIPTION', required: true }), '')).toBeNull()
  })

  it('文本：长度上下限与正则', () => {
    const f = field({ type: 'TEXT', key: 'a', label: '备注', minLength: 2, maxLength: 5 })
    expect(validateField(f, 'a')).toBe('「备注」至少 2 个字符')
    expect(validateField(f, 'abcdef')).toBe('「备注」不能超过 5 个字符')
    expect(validateField(f, 'abc')).toBeNull()

    const pattern = field({ type: 'TEXT', key: 'b', label: '手机号', pattern: '^\\d{11}$' })
    expect(validateField(pattern, '123')).toBe('「手机号」格式不符合要求')
    expect(validateField(pattern, '13800000000')).toBeNull()
  })

  it('数字：非数字报错，越界给出带单位的提示', () => {
    const f = field({ type: 'NUMBER', key: 'n', label: '金额', min: 0, max: 1000, unit: '元' })
    expect(validateField(f, 'abc')).toBe('「金额」必须是数字')
    expect(validateField(f, -1)).toBe('「金额」不能小于 0元')
    expect(validateField(f, 2000)).toBe('「金额」不能大于 1000元')
    expect(validateField(f, 500)).toBeNull()
  })

  it('日期：格式错误 / 不能早于今天 / 自定义范围', () => {
    const bad = field({ type: 'DATE', key: 'd', label: '日期' })
    expect(validateField(bad, '2026/01/01')).toBe('「日期」日期格式不正确')

    const notBefore = field({ type: 'DATE', key: 'd', label: '预约日期', dateLimit: 'NOT_BEFORE_TODAY' })
    expect(validateField(notBefore, '2000-01-01')).toBe('「预约日期」不能早于今天')

    const custom = field({
      type: 'DATE',
      key: 'd',
      label: '日期',
      dateLimit: 'CUSTOM',
      dateMin: '2026-01-01',
      dateMax: '2026-12-31'
    })
    expect(validateField(custom, '2025-12-31')).toBe('「日期」不能早于 2026-01-01')
    expect(validateField(custom, '2027-01-01')).toBe('「日期」不能晚于 2026-12-31')
    expect(validateField(custom, '2026-06-06')).toBeNull()
  })

  it('单选：取值必须命中选项', () => {
    const f = field({
      type: 'SELECT',
      key: 's',
      label: '类别',
      options: [
        { value: 'A', label: '甲' },
        { value: 'B', label: '乙' }
      ]
    })
    expect(validateField(f, 'C')).toBe('「类别」取值不在可选范围内')
    expect(validateField(f, 'A')).toBeNull()
  })

  it('多选：必须是数组且每个值都在选项内', () => {
    const f = field({
      type: 'MULTI_SELECT',
      key: 'm',
      label: '标签',
      options: [
        { value: 'A', label: '甲' },
        { value: 'B', label: '乙' }
      ]
    })
    expect(validateField(f, 'A')).toBe('「标签」格式不正确')
    expect(validateField(f, ['A', 'X'])).toBe('「标签」包含不可选的取值')
    expect(validateField(f, ['A', 'B'])).toBeNull()
  })

  it('附件：超过数量上限报错', () => {
    const f = field({ type: 'FILE', key: 'f', label: '附件', maxCount: 2 })
    expect(validateField(f, [1, 2, 3])).toBe('「附件」最多上传 2 个')
    expect(validateField(f, [1])).toBeNull()
  })

  it('引用类（人员 / 设备 / 分组）前端不做存在性判定', () => {
    expect(validateField(field({ type: 'USER', key: 'u', label: '负责人' }), 99999)).toBeNull()
    expect(validateField(field({ type: 'DEVICE', key: 'd', label: '设备' }), 99999)).toBeNull()
    expect(validateField(field({ type: 'BIZ_GROUP', key: 'g', label: '分组' }), 99999)).toBeNull()
  })
})

describe('validateFormData', () => {
  it('汇总数据字段的错误，布局类不产生错误', () => {
    const schema: FormSchema = {
      fields: [
        field({ type: 'TEXT', key: 'reason', label: '事由', required: true }),
        field({ type: 'DIVIDER' }),
        field({ type: 'NUMBER', key: 'amount', label: '金额', max: 100 })
      ]
    }
    const errors = validateFormData(schema, { reason: '', amount: 200 })
    expect(hasErrors(errors)).toBe(true)
    expect(errors.reason).toBe('「事由」为必填项')
    expect(errors.amount).toBe('「金额」不能大于 100')
    expect(Object.keys(errors)).toHaveLength(2)
  })

  it('schema 为 null 时不报错（容错）', () => {
    expect(validateFormData(null, { any: 'x' })).toEqual({})
  })

  it('合法数据返回空错误集合', () => {
    const schema: FormSchema = {
      fields: [field({ type: 'TEXT', key: 'reason', label: '事由', required: true })]
    }
    expect(validateFormData(schema, { reason: '采购办公用品' })).toEqual({})
  })
})

describe('validateSchemaForPublish（发布前拦截半成品）', () => {
  it('空表单：至少 1 个字段', () => {
    expect(validateSchemaForPublish({ fields: [] })).toContain('表单至少需要 1 个字段')
  })

  it('字段 key 非法 / 重复 / 为空 分别报错', () => {
    const illegal = validateSchemaForPublish({
      fields: [field({ type: 'TEXT', key: '1bad', label: '甲' })]
    })
    expect(illegal.some((p) => p.includes('不合法'))).toBe(true)

    const dup = validateSchemaForPublish({
      fields: [
        field({ type: 'TEXT', key: 'same', label: '甲' }),
        field({ type: 'TEXT', key: 'same', label: '乙' })
      ]
    })
    expect(dup.some((p) => p.includes('重复'))).toBe(true)

    const missing = validateSchemaForPublish({ fields: [field({ type: 'TEXT', key: '', label: '甲' })] })
    expect(missing.some((p) => p.includes('不能为空'))).toBe(true)
  })

  it('显示名称为空报错', () => {
    const problems = validateSchemaForPublish({ fields: [field({ type: 'TEXT', key: 'a', label: '  ' })] })
    expect(problems.some((p) => p.includes('显示名称不能为空'))).toBe(true)
  })

  it('选项类字段：无选项 / 选项值重复 报错', () => {
    const none = validateSchemaForPublish({
      fields: [field({ type: 'SELECT', key: 's', label: '类别', options: [] })]
    })
    expect(none.some((p) => p.includes('至少需要配置 1 个选项'))).toBe(true)

    const dup = validateSchemaForPublish({
      fields: [
        field({
          type: 'SELECT',
          key: 's',
          label: '类别',
          options: [
            { value: 'A', label: '甲' },
            { value: 'A', label: '乙' }
          ]
        })
      ]
    })
    expect(dup.some((p) => p.includes('重复的选项值'))).toBe(true)
  })

  it('附件字段不能设为必填（第一期约束）', () => {
    const problems = validateSchemaForPublish({
      fields: [field({ type: 'FILE', key: 'f', label: '附件', required: true })]
    })
    expect(problems.some((p) => p.includes('不能设为必填'))).toBe(true)
  })

  it('说明文字内容为空报错；分隔线无需名称', () => {
    expect(
      validateSchemaForPublish({ fields: [field({ type: 'DESCRIPTION', content: '  ' })] }).some((p) =>
        p.includes('内容不能为空')
      )
    ).toBe(true)
    // 仅一个分隔线：不算数据字段，但至少 1 个字段的判定按原始字段数
    expect(validateSchemaForPublish({ fields: [field({ type: 'DIVIDER' })] })).toEqual([])
  })

  it('自定义日期范围：缺上下界 / 起始晚于截止 报错', () => {
    const missing = validateSchemaForPublish({
      fields: [field({ type: 'DATE', key: 'd', label: '日期', dateLimit: 'CUSTOM' })]
    })
    expect(missing.some((p) => p.includes('需要至少填写起始或截止日期'))).toBe(true)

    const reversed = validateSchemaForPublish({
      fields: [
        field({
          type: 'DATE',
          key: 'd',
          label: '日期',
          dateLimit: 'CUSTOM',
          dateMin: '2026-12-31',
          dateMax: '2026-01-01'
        })
      ]
    })
    expect(reversed.some((p) => p.includes('不能晚于截止日期'))).toBe(true)
  })

  it('栅格宽度非 1/2 报错', () => {
    const problems = validateSchemaForPublish({
      fields: [field({ type: 'TEXT', key: 'a', label: '甲', width: 3 })]
    })
    expect(problems.some((p) => p.includes('栅格宽度只能是 1 或 2'))).toBe(true)
  })

  it('合法表单返回空问题列表', () => {
    const schema: FormSchema = {
      fields: [
        field({ type: 'TEXT', key: 'title', label: '采购事由', required: true }),
        field({
          type: 'SELECT',
          key: 'category',
          label: '类别',
          options: [
            { value: 'OFFICE', label: '办公用品' },
            { value: 'IT', label: 'IT 设备' }
          ]
        }),
        field({ type: 'NUMBER', key: 'amount', label: '预算金额' })
      ]
    }
    expect(validateSchemaForPublish(schema)).toEqual([])
  })
})
