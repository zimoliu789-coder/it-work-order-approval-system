import { defineComponent, h, inject, provide, type Component, type PropType } from 'vue'

/**
 * Element Plus 子组件轻量桩（三波补做·第三波· 组件级测试基础设施）
 *
 * 为什么不直接 `app.use(ElementPlus)`：
 * 组件单测关注的是**本仓库业务组件的 props / 事件 / 交互与校验**，而非 Element Plus 自身。
 * 引入全量 EP 会显著拖慢用例、并把「弹窗 teleport 到 body」「下拉浮层」等实现细节
 * 带进断言，造成脆弱测试。这里用最小可用桩替换 `el-*`：
 * - 结构类（button/icon/dialog/form/form-item/alert/empty/tag）只透传插槽；
 * - 交互类（select/option/input/checkbox/table 分页）用原生控件承接，能真实触发
 *   `update:modelValue` 等事件，从而驱动被测组件的 v-model 与提交逻辑。
 *
 * 用 render 函数（而非 template 字符串）实现：避免依赖运行时模板编译器。
 */

/** 透传默认插槽的容器 */
function passthrough(name: string, tag = 'div'): Component {
  return defineComponent({
    name,
    setup(_, { slots }) {
      return () => h(tag, { class: slug(name) }, slots.default?.())
    }
  })
}

/** 透传默认 + 指定具名插槽的容器（如 dialog 的 footer、menu-item 的 title） */
function passthroughMulti(name: string, tag: string, slotNames: string[]): Component {
  return defineComponent({
    name,
    setup(_, { slots }) {
      return () =>
        h(
          tag,
          { class: slug(name) },
          [...slotNames.map((s) => slots[s]?.()), slots.default?.()].flat()
        )
    }
  })
}

function slug(name: string): string {
  // ElSubMenu -> el-sub-menu
  return name
    .replace(/^El/, '')
    .replace(/([a-z0-9])([A-Z])/g, '$1-$2')
    .toLowerCase()
    .replace(/^/, 'el-')
}

/**
 * el-radio-group / el-radio-button：**可交互**的一对。
 *
 * 原来它们只是 `passthrough` 容器 —— 渲染出来了、文案也进 DOM 了，但点击不会触发
 * `update:modelValue`，于是"改单选到底有没有写进去"这类断言**静默失去覆盖**：
 * 用例照样绿，只是从来没验证过写入路径（与 W2a 的 `el-option-group` 同一类坑）。
 * 本文件开头的设计取向本就写明"交互类用原生控件承接，能真实触发 update:modelValue"，
 * 这里只是把 radio 那一类补齐。
 *
 * 实现：组通过 provide 把"选中回调"交给子按钮；子按钮点击时把它自己的 `value` 交回去。
 * 子按钮刻意**保持 div**（不是 button）：若改成 `<button>`，`wrapper.findAll('button')`
 * 会突然多出一批元素，等于用桩件的实现细节去污染别的用例的断言。
 */
const RADIO_SELECT_KEY = 'tsRadioGroupSelect'

export const ElRadioGroupStub = defineComponent({
  name: 'ElRadioGroup',
  props: { modelValue: [String, Number, Boolean], disabled: Boolean },
  emits: ['update:modelValue'],
  setup(props, { slots, emit }) {
    provide(RADIO_SELECT_KEY, (value: unknown) => {
      if (props.disabled !== true) {
        emit('update:modelValue', value)
      }
    })
    return () =>
      h(
        'div',
        {
          class: 'el-radio-group',
          'data-model-value': props.modelValue == null ? '' : String(props.modelValue),
          'data-disabled': props.disabled === true ? 'true' : 'false'
        },
        slots.default?.()
      )
  }
})

export const ElRadioButtonStub = defineComponent({
  name: 'ElRadioButton',
  props: { value: [String, Number, Boolean], label: [String, Number] },
  setup(props, { slots }) {
    const select = inject<((value: unknown) => void) | null>(RADIO_SELECT_KEY, null)
    return () =>
      h(
        'div',
        { class: 'el-radio-button', onClick: () => select?.(props.value) },
        slots.default?.() ?? []
      )
  }
})

/** el-button：原生 button，转发 click */
export const ElButtonStub = defineComponent({
  name: 'ElButton',
  props: { link: Boolean, plain: Boolean, disabled: Boolean, loading: Boolean, type: String, size: String },
  emits: ['click'],
  setup(props, { slots, emit }) {
    return () =>
      h(
        'button',
        {
          class: 'el-button',
          disabled: props.disabled === true,
          onClick: (e: MouseEvent) => emit('click', e)
        },
        slots.default?.()
      )
  }
})

/** el-select：原生 select，change 时 emit update:modelValue（数字或 null） */
export const ElSelectStub = defineComponent({
  name: 'ElSelect',
  props: {
    modelValue: { type: [Number, String], default: null },
    disabled: Boolean,
    loading: Boolean,
    filterable: Boolean,
    placeholder: String
  },
  emits: ['update:modelValue'],
  setup(props, { slots, emit }) {
    return () =>
      h(
        'select',
        {
          class: 'el-select',
          disabled: props.disabled === true,
          onChange: (e: Event) => {
            const raw = (e.target as HTMLSelectElement).value
            emit('update:modelValue', raw === '' ? null : Number(raw))
          }
        },
        slots.default?.()
      )
  }
})

/** el-option：原生 option */
export const ElOptionStub = defineComponent({
  name: 'ElOption',
  props: { value: { type: [Number, String], default: '' }, label: { type: String, default: '' } },
  setup(props) {
    return () => h('option', { value: props.value }, props.label)
  }
})

/**
 * el-option-group：分组下拉的组容器（ · M7 补）。
 *
 * 为什么必须显式注册：组件未注册时 Vue 只打印一条 `Failed to resolve component` 警告后继续渲染，
 * 用例照样"通过" —— 但分组的 label 与默认插槽都不会进入 DOM，
 * 「下拉里有哪些选项」这类断言会**静默失去覆盖**。补上桩件让分组结构真的被渲染。
 */
export const ElOptionGroupStub = defineComponent({
  name: 'ElOptionGroup',
  props: { label: { type: String, default: '' } },
  setup(props, { slots }) {
    return () => h('optgroup', { label: props.label }, slots.default ? slots.default() : [])
  }
})

/** el-input：按 type 渲染 input / textarea，input 时 emit update:modelValue */
export const ElInputStub = defineComponent({
  name: 'ElInput',
  props: { modelValue: { type: String, default: '' }, type: String, rows: Number, maxlength: [Number, String] },
  emits: ['update:modelValue'],
  setup(props, { emit }) {
    return () => {
      const isTextarea = props.type === 'textarea'
      return h(isTextarea ? 'textarea' : 'input', {
        class: isTextarea ? 'el-input el-textarea__inner' : 'el-input__inner',
        value: props.modelValue,
        onInput: (e: Event) =>
          emit('update:modelValue', (e.target as HTMLInputElement | HTMLTextAreaElement).value)
      })
    }
  }
})

/**
 * el-checkbox：**可交互**桩（-B 补）。
 *
 * 与 radio 同一类问题：原来的实现是 `passthrough('ElCheckbox', 'label')`，
 * 勾选框能渲染、文案也进 DOM，但点击不会 emit `update:modelValue`，
 * 于是"勾掉某列之后配置真的变了"这类断言**静默失去覆盖**。
 *
 * 实现上仍渲染 `<label>`（保持既有选择器不变），只是补上点击 → emit。
 * 另暴露 `data-checked`，让用例可以断言勾选态而不必依赖 EP 内部的 `.is-checked` 类名。
 */
export const ElCheckboxStub = defineComponent({
  name: 'ElCheckbox',
  props: {
    // 不声明 type：EP 的 checkbox 值类型受控方决定，桩件不额外收紧
    modelValue: { default: false },
    disabled: Boolean
  },
  emits: ['update:modelValue'],
  setup(props, { slots, emit }) {
    return () =>
      h(
        'label',
        {
          class: 'el-checkbox',
          'data-checked': props.modelValue === true ? 'true' : 'false',
          'data-disabled': props.disabled === true ? 'true' : 'false',
          onClick: () => {
            if (props.disabled === true) {
              return
            }
            // 与 EP 一致：受控用法下点击取反当前值
            emit('update:modelValue', props.modelValue === true ? false : true)
          }
        },
        slots.default?.()
      )
  }
})

/**
 * el-table / el-table-column：**父子协作**桩（-B 补）。
 *
 * 单纯把 `el-table-column` 做成透传容器没有意义 —— 它的 `#default` 插槽在真实 EP 里
 * 由表格逐行调用（`{ row, $index }`），透传桩拿不到任何行数据，于是
 * 「哪几列被渲染、次序如何、单元格内容对不对」**根本无法断言**。
 *
 * 这里用 provide/inject 复刻这一协作：表格把行数据交给列，列对每一行调用一次
 * `#default`。列节点上带 `data-col` / `data-label`，用例据此断言列的**集合与顺序**
 * （这正是 M3-B 的核心 —— 列自定义）。
 */
const TABLE_ROWS_KEY = 'tsTableRows'

export const ElTableStub = defineComponent({
  name: 'ElTable',
  props: { data: { type: Array as PropType<Record<string, unknown>[]>, default: () => [] } },
  emits: ['selection-change'],
  setup(props, { slots, emit, expose }) {
    provide(TABLE_ROWS_KEY, () => props.data ?? [])
    /**
     * 复刻 Element Plus 的 `clearSelection()`（ · W4-E 补）。
     *
     * 页面（消息中心）在翻页后要靠它清空勾选，而清空**必须同时触发一次
     * `selection-change`** —— 页面是用这个事件把自己的 `selectedRows` 同步为空的。
     * 若桩件只提供一个"什么都不做"的空方法，这条链路就永远测不到：
     * 用例会绿，但"翻页后勾选是否被清掉"从未被验证。
     */
    let clearedCount = 0
    expose({
      clearSelection: () => {
        clearedCount += 1
        emit('selection-change', [])
      },
      clearedCount: () => clearedCount
    })
    return () =>
      h('div', { class: 'el-table' }, [
        slots.default?.(),
        ...(props.data && props.data.length > 0 ? [] : [slots.empty?.()])
      ])
  }
})

export const ElTableColumnStub = defineComponent({
  name: 'ElTableColumn',
  props: { prop: String, label: String, type: String },
  setup(props, { slots }) {
    const rows = inject<() => Record<string, unknown>[]>(TABLE_ROWS_KEY, () => [])
    return () =>
      h(
        'div',
        {
          class: 'el-table-column',
          'data-col': props.prop ?? '',
          // 多选列只有 `type`、没有 `prop`/`label` —— 必须单独暴露，否则
          // 「列表里第一列是不是多选框」这条断言无法表达（data-col 恒为空串）。
          'data-type': props.type ?? '',
          'data-label': props.label ?? ''
        },
        rows().map((row, index) => h('div', { class: 'ts-cell' }, slots.default?.({ row, $index: index }) ?? []))
      )
  }
})

/** el-pagination：暴露 total，并在按钮点击时 emit 翻页/改页长事件 */
export const ElPaginationStub = defineComponent({
  name: 'ElPagination',
  props: {
    currentPage: { type: Number, default: 1 },
    pageSize: { type: Number, default: 10 },
    total: { type: Number, default: 0 },
    layout: String
  },
  emits: ['current-change', 'size-change'],
  setup(props, { emit }) {
    return () =>
      h('div', { class: 'ts-pagination', 'data-total': String(props.total ?? 0), 'data-layout': props.layout ?? '' }, [
        h('button', {
          class: 'ts-pagination__next',
          onClick: () => emit('current-change', (props.currentPage ?? 1) + 1)
        }),
        h('button', {
          class: 'ts-pagination__size',
          onClick: () => emit('size-change', 20)
        })
      ])
  }
})

/**
 * 组件桩注册表：按 EP 组件名给出最小实现。
 * 用例通过 `global: { components: epStubs }` 注入，`el-*` 标签即可解析到这些桩。
 */
export const epStubs: Record<string, Component> = {
  ElButton: ElButtonStub,
  ElIcon: passthrough('ElIcon', 'i'),
  ElMenu: passthrough('ElMenu', 'ul'),
  ElMenuItem: passthroughMulti('ElMenuItem', 'li', ['title']),
  ElSubMenu: passthroughMulti('ElSubMenu', 'li', ['title']),
  ElForm: passthrough('ElForm', 'form'),
  ElFormItem: passthrough('ElFormItem', 'div'),
  ElDialog: passthroughMulti('ElDialog', 'div', ['header', 'footer']),
  ElAlert: defineComponent({
    name: 'ElAlert',
    props: { title: String, description: String },
    setup(props) {
      return () => h('div', { class: 'el-alert' }, [h('span', props.title ?? ''), h('span', props.description ?? '')])
    }
  }),
  /**
   * el-empty：把 `description` 渲染成文本（-B 补）。
   *
   * 原来只是透传默认插槽 —— 而项目里 el-empty 一律用 `description` 属性描述空态，
   * 于是「空列表到底显示了什么提示」根本无法断言（又是一个静默失去覆盖的例子）。
   */
  ElEmpty: defineComponent({
    name: 'ElEmpty',
    props: { description: { type: String, default: '' } },
    setup(props, { slots }) {
      return () => h('div', { class: 'el-empty' }, [h('span', props.description), slots.default?.()])
    }
  }),
  ElTag: passthrough('ElTag', 'span'),
  ElSelect: ElSelectStub,
  ElOption: ElOptionStub,
  ElOptionGroup: ElOptionGroupStub,
  ElInput: ElInputStub,
  // ：FlowDesigner 用到的下拉 / 单选 / 复选 / 数字输入。
  // 这些组件在用例中只需"能渲染出插槽内容"即可（断言的是业务组件的结构与文案），
  // 因此统一用透传桩；需要驱动 v-model 的交互仍由上面的 ElSelect/ElInput 承接。
  ElDropdown: passthrough('ElDropdown', 'div'),
  ElDropdownMenu: passthrough('ElDropdownMenu', 'div'),
  ElDropdownItem: passthrough('ElDropdownItem', 'div'),
  ElRadioGroup: ElRadioGroupStub,
  ElRadioButton: ElRadioButtonStub,
  ElRadio: passthrough('ElRadio', 'label'),
  ElCheckbox: ElCheckboxStub,
  ElCheckboxGroup: passthrough('ElCheckboxGroup', 'div'),
  ElInputNumber: passthrough('ElInputNumber', 'div'),
  // -B：公共表格层用到的三个组件
  ElTable: ElTableStub,
  ElTableColumn: ElTableColumnStub,
  ElPagination: ElPaginationStub
}
