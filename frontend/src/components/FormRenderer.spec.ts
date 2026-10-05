import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h, type Component } from 'vue'
import FormRenderer from '@/components/FormRenderer.vue'
import { epStubs } from '@/test/stubs'
import type { FormData, FormField, FormSchema } from '@/types/form'

/**
 * FormRenderer 组件级测试（ 自定义申请类型）
 *
 * 动态表单渲染器是整个自定义申请能力的「出/入水口」：设计器配出的 schema 靠它渲染成
 * 可填写或可回显的界面，用户填的值靠它 emit 回父组件。它一旦出错，表现为
 * 「字段不显示 / 值填了没保存 / 详情空白」，且都是静默的 —— 值得组件级回归。
 *
 * 覆盖点：
 * 1. 空 schema → 明确提示，不出现空白区；
 * 2. 只读态：标签、选项中文翻译、引用类解析、未知引用回落 #id；
 * 3. 编辑态：v-model 双向绑定（输入 → emit，清空 → 删 key）、必填/错误透传到表单项；
 * 4. 布局元素（说明 / 分隔线）两种模式都不进数据字段、不渲染表单项；
 * 5. 附件字段只读态给出「见工单附件区」而非空白。
 *
 * 引用类选项在 onMounted 里异步拉取，故用 vi.mock 固定返回值并 await flushPromises。
 */

vi.mock('@/api/user', () => ({
  default: { options: vi.fn(async () => [{ id: 1, displayName: '张三', departmentName: '运维组', dimission: false }]) },
  userApi: { options: vi.fn(async () => [{ id: 1, displayName: '张三', departmentName: '运维组', dimission: false }]) }
}))
vi.mock('@/api/department', () => ({
  departmentApi: {
    options: vi.fn(async () => [{ id: 7, deptName: '技术组', parentId: null, depth: 0, handlerGroup: false, displayPath: '公司 / 技术组' }])
  }
}))
vi.mock('@/api/order', () => ({ orderApi: { selectableDevices: vi.fn(async () => []) } }))

/** el-descriptions / item：渲染 label 与默认插槽，便于断言可读文本 */
const ElDescriptionsStub = defineComponent({
  name: 'ElDescriptions',
  setup(_, { slots }) {
    return () => h('div', { class: 'el-descriptions' }, slots.default?.())
  }
})

const ElDescriptionsItemStub = defineComponent({
  name: 'ElDescriptionsItem',
  props: { label: { type: String, default: '' }, span: { type: [Number, String], default: 1 } },
  setup(props, { slots }) {
    return () =>
      h('div', { class: 'el-descriptions-item' }, [
        h('span', { class: 'desc-label' }, props.label),
        h('span', { class: 'desc-value' }, slots.default?.() as unknown as never)
      ])
  }
})

/** el-form-item：暴露 label / required / error 供断言 */
const ElFormItemStub = defineComponent({
  name: 'ElFormItem',
  props: {
    label: { type: String, default: '' },
    required: Boolean,
    error: { type: String, default: '' },
    labelWidth: String,
    labelPosition: String
  },
  setup(props, { slots }) {
    return () =>
      h(
        'div',
        { class: 'el-form-item', 'data-required': String(props.required === true), 'data-error': props.error ?? '' },
        [h('label', { class: 'el-form-item__label' }, props.label), slots.default?.()]
      )
  }
})

/** 其余 EP 组件仅需占位（不产生交互断言） */
function passthrough(name: string, tag = 'div'): Component {
  return defineComponent({
    name,
    setup(_, { slots }) {
      return () => h(tag, { class: name.toLowerCase() }, slots.default?.())
    }
  })
}

const localStubs: Record<string, Component> = {
  ...epStubs,
  ElDescriptions: ElDescriptionsStub,
  ElDescriptionsItem: ElDescriptionsItemStub,
  ElFormItem: ElFormItemStub,
  ElDivider: passthrough('ElDivider', 'hr'),
  ElInputNumber: passthrough('ElInputNumber', 'input'),
  ElDatePicker: passthrough('ElDatePicker', 'input'),
  ElRadioGroup: passthrough('ElRadioGroup'),
  ElRadio: passthrough('ElRadio', 'label'),
  ElCheckboxGroup: passthrough('ElCheckboxGroup'),
  ElCheckbox: passthrough('ElCheckbox', 'label')
}

function mountRenderer(props: Record<string, unknown>) {
  return mount(FormRenderer, {
    props: { modelValue: {} as FormData, ...props },
    global: { components: localStubs }
  })
}

function field(partial: Partial<FormField> & Pick<FormField, 'type'>): FormField {
  return { label: '字段', required: false, ...partial }
}

describe('FormRenderer（空 schema）', () => {
  it('无字段时给出明确提示，而不是留一片空白', () => {
    const wrapper = mountRenderer({ schema: { fields: [] } as FormSchema })
    expect(wrapper.find('.el-empty').exists()).toBe(true)
  })
})

describe('FormRenderer（只读回显）', () => {
  const schema: FormSchema = {
    fields: [
      field({ type: 'TEXT', key: 'reason', label: '申请事由' }),
      field({
        type: 'SELECT',
        key: 'category',
        label: '类别',
        options: [
          { value: 'OFFICE', label: '办公用品' },
          { value: 'IT', label: 'IT 设备' }
        ]
      }),
      field({ type: 'USER', key: 'owner', label: '负责人' }),
      field({ type: 'BIZ_GROUP', key: 'dept', label: '归属分组' })
    ]
  }

  it('标签与文本值直接展示；选项值翻译成中文', async () => {
    const wrapper = mountRenderer({
      schema,
      readonly: true,
      modelValue: { reason: '采购办公用品', category: 'OFFICE' } as FormData
    })
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('申请事由')
    expect(text).toContain('采购办公用品')
    expect(text).toContain('办公用品') // OFFICE → 中文
  })

  it('引用类解析成名称（人员带分组、分组带名称）', async () => {
    const wrapper = mountRenderer({
      schema,
      readonly: true,
      modelValue: { owner: 1, dept: 7 } as FormData
    })
    await flushPromises()

    expect(wrapper.text()).toContain('张三')
    expect(wrapper.text()).toContain('技术组')
  })

  it('未知引用回落 #id（宁可显示编号，也不显示空白）', async () => {
    const wrapper = mountRenderer({
      schema,
      readonly: true,
      modelValue: { owner: 999 } as FormData
    })
    await flushPromises()

    expect(wrapper.text()).toContain('#999')
  })

  it('未填字段显示「-」', () => {
    const wrapper = mountRenderer({ schema, readonly: true, modelValue: {} as FormData })
    expect(wrapper.findAll('.desc-value').some((n) => n.text() === '-')).toBe(true)
  })

  it('附件字段只读态给出「见工单附件」提示，而非空白', () => {
    const wrapper = mountRenderer({
      schema: { fields: [field({ type: 'FILE', key: 'files', label: '附件' })] } as FormSchema,
      readonly: true,
      modelValue: {} as FormData
    })
    expect(wrapper.text()).toContain('工单附件')
  })
})

describe('FormRenderer（编辑态）', () => {
  const schema: FormSchema = {
    fields: [
      field({ type: 'TEXT', key: 'reason', label: '申请事由', required: true }),
      field({ type: 'DIVIDER' }),
      field({ type: 'DESCRIPTION', content: '请如实填写' })
    ]
  }

  it('数据字段渲染成表单项，布局元素不进表单项', () => {
    const wrapper = mountRenderer({ schema, modelValue: {} as FormData })
    const items = wrapper.findAll('.el-form-item')
    expect(items).toHaveLength(1)
    expect(items[0].attributes('data-required')).toBe('true')
    expect(wrapper.text()).toContain('请如实填写') // 说明文字
    expect(wrapper.find('hr').exists()).toBe(true) // 分隔线
  })

  it('输入文本 → emit update:modelValue（值写入对应 key）', async () => {
    const wrapper = mountRenderer({ schema, modelValue: {} as FormData })
    const input = wrapper.find('.el-form-item input')
    await input.setValue('采购打印纸')

    const emitted = wrapper.emitted('update:modelValue')
    expect(emitted).toBeTruthy()
    expect(emitted?.[0]?.[0]).toEqual({ reason: '采购打印纸' })
  })

  it('清空输入 → 删除该 key（提交载荷最小化）', async () => {
    const wrapper = mountRenderer({ schema, modelValue: { reason: '旧值' } as FormData })
    const input = wrapper.find('.el-form-item input')
    await input.setValue('')

    const emitted = wrapper.emitted('update:modelValue')
    expect(emitted?.[0]?.[0]).toEqual({})
  })

  it('错误文案透传到表单项（父组件用 validateFormData 计算后传入）', () => {
    const wrapper = mountRenderer({
      schema,
      modelValue: {} as FormData,
      errors: { reason: '「申请事由」为必填项' }
    })
    expect(wrapper.find('.el-form-item').attributes('data-error')).toBe('「申请事由」为必填项')
  })
})
