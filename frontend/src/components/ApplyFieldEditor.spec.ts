import { describe, expect, it } from 'vitest'
import { defineComponent, h } from 'vue'
import { mount, type VueWrapper } from '@vue/test-utils'
import ApplyFieldEditor from '@/components/ApplyFieldEditor.vue'
import { epStubs } from '@/test/stubs'
import { addableFieldGroups, FIELD_TYPE_META } from '@/types/form'
import type { FormField } from '@/types/form'

/**
 * 字段清单编辑器（简化版）组件测试
 *
 * <h2>覆盖两个已发生的 P0 缺陷 —— 去掉修复这两组用例必然失败</h2>
 * 1. **字段类型不全**：本组件曾写死 4 种（TEXT / DATE / NUMBER / SELECT），于是
 *    「新建申请向导」的下拉里看不到多行文本等 12 种类型，而编辑配置页却有 ——
 *    同一个表单在两个页面能配的东西不一样。守卫：菜单条目与元数据**逐项一致**。
 * 2. **选项分隔符被吃掉**：旧实现是「一行文本 + 每次输入解析回写 + join('、') + 过滤空项」，
 *    于是打下「事假、」时末尾顿号被当空项丢掉、回写后变回「事假」——
 *    **分隔符在打字过程中被吃掉**，永远拆不出第二项（顿号/逗号都一样）。
 *    守卫：敲下分隔符必须**立即生成标签**且输入框清空。
 *
 * <h2>为什么需要 {@link applyEmitted}</h2>
 * 组件只通过 `update:modelValue` 上报、**自己不持有数据**（与线上 v-model 用法一致）。
 * 只 mount 而不回写 props 的话，组件读到的永远是初始值 —— 断言会看到"改了个寂寞"。
 * 这里显式回写最后一条 emit，等价于父级 v-model。
 *
 * <h2>为什么要覆盖 ElDropdown 桩</h2>
 * 共享桩 `epStubs.ElDropdown` 只透传**默认插槽**，而「添加字段」的条目位于 `#dropdown`
 * **具名插槽**里 ⇒ 菜单条目根本不会进 DOM。这里用测试**局部**覆盖一个同时渲染两个插槽的桩，
 * 从而真正验证"菜单里有没有这些类型"；不动共享桩件（改它会影响其它用例的 DOM 断言）。
 */
const ElDropdownBothSlots = defineComponent({
  name: 'ElDropdown',
  setup(_, { slots }) {
    return () => h('div', { class: 'el-dropdown' }, [slots.default?.(), slots.dropdown?.()])
  }
})

const localStubs = { ...epStubs, ElDropdown: ElDropdownBothSlots }

type Editor = VueWrapper<InstanceType<typeof ApplyFieldEditor>>

function mountEditor(fields: FormField[]): Editor {
  return mount(ApplyFieldEditor, {
    props: { modelValue: fields },
    global: { components: localStubs }
  }) as Editor
}

/** 把组件最后一次 emit 的值回写到 props —— 等价于父级 v-model */
async function applyEmitted(wrapper: Editor): Promise<void> {
  const emitted = wrapper.emitted('update:modelValue')
  if (emitted && emitted.length > 0) {
    await wrapper.setProps({ modelValue: emitted[emitted.length - 1][0] as FormField[] })
  }
}

function field(partial: Partial<FormField> & Pick<FormField, 'key' | 'type'>): FormField {
  return { label: partial.key, required: false, options: null, ...partial }
}

function optionTags(wrapper: Editor): string[] {
  return wrapper.findAll('.ts-field-editor__tag').map((tag) => tag.text())
}

function optionInput(wrapper: Editor) {
  return wrapper.find('.ts-field-editor__tag-input')
}

/** 可添加的类型条目（排除分组标题那一类不可点的 dropdown-item） */
function addableItems(wrapper: Editor) {
  return wrapper
    .findAll('.el-dropdown-item')
    .filter((item) => !item.classes().includes('ts-field-editor__add-group'))
}

function currentFields(wrapper: Editor): FormField[] {
  const emitted = wrapper.emitted('update:modelValue')
  return emitted && emitted.length > 0 ? (emitted[emitted.length - 1][0] as FormField[]) : []
}

describe('「添加字段」清单的唯一事实源（P0 bug 守卫 · 纯函数层）', () => {
  it('清单必须覆盖元数据里的全部字段类型，且顺序一致', () => {
    const types = addableFieldGroups().flatMap((group) => group.items.map((item) => item.type))
    expect(types).toEqual(FIELD_TYPE_META.map((meta) => meta.type))
  })

  it('按四个分类分组，且不出现空分组', () => {
    const groups = addableFieldGroups()
    expect(groups.map((group) => group.code)).toEqual(['BASIC', 'SELECT', 'ADVANCED', 'LAYOUT'])
    for (const group of groups) {
      expect(group.items.length).toBeGreaterThan(0)
    }
  })

  it('此前缺失的多行文本在清单里（旧实现只有 4 种，会漏掉它）', () => {
    const types = addableFieldGroups().flatMap((group) => group.items.map((item) => item.type))
    expect(types).toContain('TEXTAREA')
    expect(types.length).toBeGreaterThan(4)
  })
})

describe('ApplyFieldEditor · 字段类型菜单（P0 bug 守卫 · 组件层）', () => {
  it('菜单渲染出的条目与元数据逐项一致', () => {
    const wrapper = mountEditor([])
    expect(addableItems(wrapper).map((item) => item.text())).toEqual(
      FIELD_TYPE_META.map((meta) => meta.label)
    )
  })

  it('点「多行文本」能把它加进字段列表', async () => {
    const wrapper = mountEditor([])

    const item = addableItems(wrapper).find((node) => node.text() === '多行文本')
    expect(item).toBeTruthy()
    await item!.trigger('click')

    const fields = currentFields(wrapper)
    expect(fields).toHaveLength(1)
    expect(fields[0].type).toBe('TEXTAREA')
  })
})

describe('ApplyFieldEditor · 选项输入（分隔符不被吃掉）', () => {
  it('输入「事假、」立即生成标签，且输入框被清空（不是变回「事假」）', async () => {
    const wrapper = mountEditor([field({ key: 'f1', type: 'SELECT', options: [] })])

    const input = optionInput(wrapper)
    await input.setValue('事假、')
    await applyEmitted(wrapper)

    expect(optionTags(wrapper)).toEqual(['事假'])
    // 关键断言：分隔符若被「回写归一化」吃掉，这里会残留「事假」，第二项永远输不进去
    expect((input.element as HTMLInputElement).value).toBe('')
  })

  it('顿号、半角逗号、全角逗号都当分隔符', async () => {
    const wrapper = mountEditor([field({ key: 'f1', type: 'SELECT', options: [] })])

    const input = optionInput(wrapper)
    // 分隔符即提交；最后一段留在输入框里等回车/失焦（标签式输入的通用手感）
    await input.setValue('事假、病假,年假，调休')
    await applyEmitted(wrapper)
    expect(optionTags(wrapper)).toEqual(['事假', '病假', '年假'])

    await input.trigger('keydown', { key: 'Enter' })
    await applyEmitted(wrapper)
    expect(optionTags(wrapper)).toEqual(['事假', '病假', '年假', '调休'])
  })

  it('失焦提交输入框里的残留文本（粘贴后不按回车也能落库）', async () => {
    const wrapper = mountEditor([field({ key: 'f1', type: 'SELECT', options: [] })])

    const input = optionInput(wrapper)
    await input.setValue('笔记本电脑、显示器、键盘')
    await applyEmitted(wrapper)
    expect(optionTags(wrapper)).toEqual(['笔记本电脑', '显示器'])

    await input.trigger('blur')
    await applyEmitted(wrapper)
    expect(optionTags(wrapper)).toEqual(['笔记本电脑', '显示器', '键盘'])
  })

  it('重复项自动去重', async () => {
    const wrapper = mountEditor([field({ key: 'f1', type: 'SELECT', options: [] })])

    const input = optionInput(wrapper)
    await input.setValue('事假、事假、')
    await applyEmitted(wrapper)

    expect(optionTags(wrapper)).toEqual(['事假'])
  })

  it('输入框为空时按退格删掉最后一个标签', async () => {
    const wrapper = mountEditor([
      field({
        key: 'f1',
        type: 'SELECT',
        options: [
          { value: '事假', label: '事假' },
          { value: '病假', label: '病假' }
        ]
      })
    ])

    expect(optionTags(wrapper)).toEqual(['事假', '病假'])
    await optionInput(wrapper).trigger('keydown', { key: 'Backspace' })
    await applyEmitted(wrapper)
    expect(optionTags(wrapper)).toEqual(['事假'])
  })
})

describe('ApplyFieldEditor · 提交前预检', () => {
  it('多选下拉没有选项也会被检出（旧实现只判 SELECT，会漏掉它）', () => {
    const wrapper = mountEditor([
      field({ key: 'f1', type: 'MULTI_SELECT', label: '感兴趣的方向', options: [] })
    ])

    expect(wrapper.vm.incompleteProblems().join('\n')).toContain('没有任何选项')
  })

  it('输入框里还剩草稿时不算「没有任何选项」（避免自相矛盾的报错）', async () => {
    const wrapper = mountEditor([
      field({ key: 'f1', type: 'SELECT', label: '请假类型', options: [] })
    ])

    // 只输入、不回车也不失焦：草稿还没变成标签
    await optionInput(wrapper).setValue('事假')

    expect(wrapper.vm.incompleteProblems().join('\n')).not.toContain('没有任何选项')
  })

  it('不带选项的类型（如单行文本）不会被要求配选项', () => {
    const wrapper = mountEditor([field({ key: 'f1', type: 'TEXT', label: '姓名' })])
    expect(wrapper.vm.incompleteProblems()).toEqual([])
  })
})
