import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import FlowDesigner from '@/components/FlowDesigner.vue'
import { epStubs } from '@/test/stubs'
import type { FlowCondition, FlowDefinition, FlowFieldOption } from '@/types/approvalFlow'

/**
 * FlowDesigner 组件级测试（ · ）
 *
 * <h2>覆盖点</h2>
 * 这些行为**只在组件挂载后才成立**，纯函数单测覆盖不到：
 * 1. 空流程时的引导按钮能真正写入 `start` 与首个节点（不是只改了一个数组）；
 * 2. DFS 画布对**分支汇合**的处理 —— 汇合点第一次全量展示、第二次渲染成「汇合到 …」引用，
 *    而不是重复渲染或丢节点（这是 DAG 相对树最容易写错的地方）；
 * 3. 属性面板随选中切换（选中审批节点才出现"审批人"编辑区）；
 * 4. 只读模式不给出任何"添加"入口；
 * 5. 发布预检通过 `defineExpose` 暴露给页面，页面据此禁用发布按钮。
 */
const FIELDS: FlowFieldOption[] = [
  { value: 'amount', label: '金额', kind: 'NUMBER' },
  { value: 'receiver', label: '接收人', kind: 'USER_REF' }
]

function designerFlow(): FlowDefinition {
  return {
    start: 'n1',
    nodes: [
      {
        key: 'n1',
        type: 'APPROVAL',
        name: '主管审批',
        signType: 'ANY_SIGN',
        approverRules: [{ type: 'ROLE', roleCode: 'admin' }],
        next: 'c1'
      },
      {
        key: 'c1',
        type: 'CONDITION',
        name: '金额判断',
        branches: [
          {
            key: 'b1',
            name: '金额大于5000',
            next: 'n2',
            condition: { logic: 'AND', rules: [{ field: 'amount', op: 'GT', value: '5000' }] }
          },
          { key: 'b2', name: '其它情况', else: true, next: 'n3' }
        ]
      },
      {
        key: 'n2',
        type: 'APPROVAL',
        name: '财务复核',
        signType: 'ALL_SIGN',
        approverRules: [{ type: 'APPLICANT_CHOOSE', scope: 'ALL', minCount: 1, maxCount: 2 }],
        next: 'n3'
      },
      {
        key: 'n3',
        type: 'APPROVAL',
        name: '归档确认',
        signType: 'ANY_SIGN',
        approverRules: [{ type: 'FORM_USER_FIELD', fieldKey: 'receiver' }],
        next: 'end'
      },
      { key: 'end', type: 'END', name: '结束' }
    ]
  }
}

interface DesignerVm {
  publishProblems: string[]
}

function mountDesigner(definition: FlowDefinition, readonly = false) {
  return mount(FlowDesigner, {
    props: { modelValue: definition, readonly, fieldOptions: FIELDS },
    global: { components: epStubs, stubs: { UserSelectDialog: true } }
  })
}

function vmOf(wrapper: ReturnType<typeof mountDesigner>): DesignerVm {
  return wrapper.vm as unknown as DesignerVm
}

describe('FlowDesigner', () => {
  it('空流程显示引导，点击后写入 start 与首个 APPROVAL 节点', async () => {
    const wrapper = mountDesigner({ start: '', nodes: [] })
    expect(wrapper.find('.ts-flow__empty').exists()).toBe(true)

    const button = wrapper.findAll('button.el-button').find((item) => item.text().includes('添加第一个审批节点'))
    expect(button).toBeTruthy()
    await button?.trigger('click')

    const emitted = wrapper.emitted('update:modelValue')
    expect(emitted).toBeTruthy()
    const payload = emitted?.[emitted.length - 1][0] as FlowDefinition
    expect(payload.nodes).toHaveLength(1)
    expect(payload.start).toBe(payload.nodes[0].key)
    expect(payload.nodes[0].type).toBe('APPROVAL')
    expect(payload.nodes[0].next).toBe(null)
    // 新建的审批节点默认带一条「指定人员」规则，避免一创建就是"未配置审批人"的非法态
    expect(payload.nodes[0].approverRules).toHaveLength(1)
    expect(payload.nodes[0].signType).toBe('ANY_SIGN')
  })

  it('按 DFS 展开画布：条件分支缩进、汇合点第二次渲染为「汇合到」引用', () => {
    const wrapper = mountDesigner(designerFlow())

    // 5 个节点卡片（含被两条边指向的 n3 —— 只在第一次出现时全量渲染）
    expect(wrapper.findAll('.ts-flow__card')).toHaveLength(5)
    // 2 条分支出口
    expect(wrapper.findAll('.ts-flow__branch')).toHaveLength(2)
    // n3 第二次出现时是引用块，不是又一张卡片
    const jumps = wrapper.findAll('.ts-flow__jump')
    expect(jumps).toHaveLength(1)
    expect(jumps[0].text()).toContain('汇合到')

    const text = wrapper.text()
    expect(text).toContain('主管审批')
    expect(text).toContain('金额判断')
    expect(text).toContain('财务复核')
    expect(text).toContain('归档确认')
    expect(text).toContain('默认出口')
    expect(text).toContain('金额 大于 5000')
  })

  it('选中审批节点后右侧出现审批人编辑区（含按来源切换的说明）', async () => {
    const wrapper = mountDesigner(designerFlow())
    expect(wrapper.find('.ts-flow__rules').exists()).toBe(false)

    await wrapper.findAll('.ts-flow__card')[0].trigger('click')
    expect(wrapper.find('.ts-flow__card.is-active').exists()).toBe(true)
    expect(wrapper.find('.ts-flow__rules').exists()).toBe(true)
    // 指定角色：角色下拉 + 提示
    expect(wrapper.text()).toContain('指定角色')
  })

  it('选中条件节点后右侧出现分支编辑区，默认出口没有条件编辑器', async () => {
    const wrapper = mountDesigner(designerFlow())
    // 第 2 张卡片是条件节点
    await wrapper.findAll('.ts-flow__card')[1].trigger('click')
    expect(wrapper.find('.ts-flow__branches').exists()).toBe(true)
    // 两条分支各有一个编辑器
    expect(wrapper.findAll('.ts-flow__branch-editor')).toHaveLength(2)
    // 默认出口不要求填条件
    expect(wrapper.text()).toContain('无需条件')
  })

  it('只读模式不渲染任何"添加节点"入口', () => {
    const wrapper = mountDesigner(designerFlow(), true)
    expect(wrapper.find('.ts-flow__add').exists()).toBe(false)
    expect(wrapper.text()).toContain('只读模式')
    // 卡片上的"插入"/删除按钮也应消失
    expect(wrapper.find('.ts-flow__card-actions').exists()).toBe(false)
  })

  it('通过 defineExpose 暴露发布预检结果（合法流程为空、缺默认出口有提示）', () => {
    const ok = mountDesigner(designerFlow())
    expect(vmOf(ok).publishProblems).toEqual([])

    const broken = designerFlow()
    broken.nodes[1].branches = [
      {
        key: 'b1',
        name: '金额大于5000',
        next: 'n2',
        condition: { logic: 'AND', rules: [{ field: 'amount', op: 'GT', value: '5000' }] }
      }
    ]
    const bad = mountDesigner(broken)
    expect(vmOf(bad).publishProblems.some((item) => item.includes('必须且只能有一个默认出口'))).toBe(true)
    // 页脚应展示第一条待完善项
    expect(bad.text()).toContain('发布前需完善')
  })

  it('孤岛节点会被单独列出而不是从画布上静默消失', () => {
    const flow = designerFlow()
    flow.nodes.push({
      key: 'orphan',
      type: 'APPROVAL',
      name: '孤儿节点',
      signType: 'ANY_SIGN',
      approverRules: [{ type: 'ROLE', roleCode: 'user' }],
      next: 'end'
    })
    const wrapper = mountDesigner(flow)
    expect(wrapper.find('.ts-flow__orphans').exists()).toBe(true)
    expect(wrapper.find('.ts-flow__orphans').text()).toContain('孤儿节点')
  })
})

describe('FlowDesigner · （抄送 / 审批时限 / 上一节点指定）', () => {
  /** 部门负责人审批(限时24h) → 抄送管理员 → 直属领导审批(限时48h) → 上一节点指定2人 → 结束 */
  function wave2Flow(): FlowDefinition {
    return {
      start: 'n1',
      nodes: [
        {
          key: 'n1',
          type: 'APPROVAL',
          name: '部门负责人审批',
          signType: 'ANY_SIGN',
          approverRules: [{ type: 'ROLE', roleCode: 'admin' }],
          timeLimitHours: 24,
          next: 'cc1'
        },
        {
          key: 'cc1',
          type: 'CC',
          name: '抄送管理员',
          approverRules: [{ type: 'ROLE', roleCode: 'admin' }],
          next: 'n2'
        },
        {
          key: 'n2',
          type: 'APPROVAL',
          name: '直属领导审批',
          signType: 'ANY_SIGN',
          approverRules: [{ type: 'LEADER' }],
          timeLimitHours: 48,
          next: 'n3'
        },
        {
          key: 'n3',
          type: 'APPROVAL',
          name: '上一节点指定的审批人',
          signType: 'ANY_SIGN',
          approverRules: [{ type: 'PREV_ASSIGN', assignCount: 2 }],
          next: 'end'
        },
        { key: 'end', type: 'END', name: '结束' }
      ]
    }
  }

  it('抄送节点渲染为独立卡片，卡片摘要写明「抄送」而非签署方式', () => {
    const wrapper = mountDesigner(wave2Flow())
    expect(wrapper.findAll('.ts-flow__card')).toHaveLength(5)

    const ccCard = wrapper.findAll('.ts-flow__card').find((card) => card.text().includes('抄送管理员'))
    expect(ccCard).toBeTruthy()
    expect(ccCard?.text()).toContain('抄送')
    // 抄送没有或签/会签的概念，摘要里不应出现签署方式
    expect(ccCard?.text()).not.toContain('或签')
  })

  it('选中抄送节点：编辑区文案切换为「抄送来源」，且不出现审批步骤才有的时限说明', async () => {
    const wrapper = mountDesigner(wave2Flow())
    const ccCard = wrapper.findAll('.ts-flow__card').find((card) => card.text().includes('抄送管理员'))
    await ccCard?.trigger('click')

    expect(wrapper.find('.ts-flow__rules').exists()).toBe(true)
    expect(wrapper.text()).toContain('添加抄送来源')
    // 抄送是"知会"，不应出现审批步骤才有的概念
    expect(wrapper.text()).not.toContain('添加审批人来源')
    expect(wrapper.text()).not.toContain('留空 = 不限时')
  })

  it('选中审批节点：出现时限输入说明，且卡片摘要回显该节点的时长', async () => {
    const wrapper = mountDesigner(wave2Flow())
    const n1 = wrapper.findAll('.ts-flow__card').find((card) => card.text().includes('部门负责人审批'))
    await n1?.trigger('click')
    expect(wrapper.text()).toContain('添加审批人来源')
    expect(wrapper.text()).toContain('留空 = 不限时')
    expect(wrapper.text()).toContain('限时 24 小时')
  })

  it('上一节点指定规则：卡片摘要写明「上一节点指定 N 人」', () => {
    const wrapper = mountDesigner(wave2Flow())
    const n3 = wrapper.findAll('.ts-flow__card').find((card) => card.text().includes('上一节点指定的审批人'))
    expect(n3?.text()).toContain('上一节点指定 2 人')
  })

  it(' 流程发布预检为空（抄送 / 时限 / 直属领导 / 上一节点指定组合合法）', () => {
    const wrapper = mountDesigner(wave2Flow())
    expect(vmOf(wrapper).publishProblems).toEqual([])
  })
})

/**
 *  · M3-A：条件嵌套组的**设计器交互**。
 *
 * <h2>为什么这批断言必须挂在组件上</h2>
 * 嵌套条件树的编辑走的是「路径」而不是下标（同一个下标会同时指向树里多个位置）。
 * 路径算错不会抛异常，只会**改错一个节点** —— 纯函数单测查不出"模板到底把哪条路径传下去了"，
 * 所以这里每一例都从**点击按钮**开始，最后回到 `update:modelValue` 的载荷上验证。
 *
 * 设计器是受控组件：点击后载荷不会自动回到 props，因此每次交互后用 `setProps` 回灌，
 * 模拟真实页面 `v-model` 的行为（不回灌就只能验证第一次点击，多步交互全是假的）。
 */
describe('FlowDesigner · （条件嵌套组）', () => {
  type Wrapper = ReturnType<typeof mountDesigner>

  /** 选中条件节点（画布里第 2 张卡片），右侧才会出现分支编辑区 */
  async function selectCondition(wrapper: Wrapper): Promise<void> {
    await wrapper.findAll('.ts-flow__card')[1].trigger('click')
  }

  function buttonByText(wrapper: Wrapper, text: string) {
    return wrapper.findAll('button').find((item) => item.text().replace(/\s+/g, '').includes(text))
  }

  function buttonsByText(wrapper: Wrapper, text: string) {
    return wrapper.findAll('button').filter((item) => item.text().replace(/\s+/g, '').includes(text))
  }

  /** 取最后一次 update:modelValue 的载荷，并回灌成新的 props */
  async function settle(wrapper: Wrapper): Promise<FlowDefinition> {
    const emitted = wrapper.emitted('update:modelValue')
    const payload = emitted?.[emitted.length - 1][0] as FlowDefinition
    await wrapper.setProps({ modelValue: payload })
    return payload
  }

  function conditionOf(definition: FlowDefinition): NonNullable<FlowCondition> {
    const node = definition.nodes.find((item) => item.key === 'c1')
    return node?.branches?.[0]?.condition as NonNullable<FlowCondition>
  }

  /** 根条件：AND（金额>5000 且 条件组（金额<100））—— 组内逻辑与根逻辑刻意都设成 AND，
   *  这样"改组内逻辑却写到了根上"这种错误会让断言真的变红 */
  function nestedFlow(): FlowDefinition {
    const flow = designerFlow()
    const branch = flow.nodes.find((item) => item.key === 'c1')?.branches?.[0]
    if (branch) {
      branch.condition = {
        logic: 'AND',
        rules: [
          { field: 'amount', op: 'GT', value: '5000' },
          {
            kind: 'GROUP',
            condition: { logic: 'AND', rules: [{ field: 'amount', op: 'LT', value: '100' }] }
          }
        ]
      }
    }
    return flow
  }

  /** 已经到 3 层上限：AND（rule 且 组（rule 且 组（rule））） */
  function depthThreeFlow(): FlowDefinition {
    const flow = designerFlow()
    const branch = flow.nodes.find((item) => item.key === 'c1')?.branches?.[0]
    if (branch) {
      branch.condition = {
        logic: 'AND',
        rules: [
          { field: 'amount', op: 'GT', value: '5000' },
          {
            kind: 'GROUP',
            condition: {
              logic: 'OR',
              rules: [
                { field: 'amount', op: 'LT', value: '100' },
                {
                  kind: 'GROUP',
                  condition: { logic: 'AND', rules: [{ field: 'receiver', op: 'NOT_EMPTY' }] }
                }
              ]
            }
          }
        ]
      }
    }
    return flow
  }

  it('「添加条件组」写入组信封（kind=GROUP + 一条空白规则），画布上立刻出现组头', async () => {
    const wrapper = mountDesigner(designerFlow())
    await selectCondition(wrapper)
    expect(wrapper.findAll('.ts-flow__cond-rule--group')).toHaveLength(0)

    await buttonByText(wrapper, '添加条件组')?.trigger('click')
    const next = await settle(wrapper)

    const rules = conditionOf(next).rules
    expect(rules).toHaveLength(2)
    expect(rules[1].kind).toBe('GROUP')
    // 刻意带一条空白规则：建成空组会先撞「嵌套条件组为空」，用户先看到一条假错误
    expect(rules[1].condition?.rules).toHaveLength(1)
    // 组信封上不留 field/op/value —— 留着就会被后端以「不应携带字段」拒绝发布
    expect(rules[1].field).toBeUndefined()
    expect(rules[1].op).toBeUndefined()

    expect(wrapper.findAll('.ts-flow__cond-rule--group')).toHaveLength(1)
    expect(wrapper.find('.ts-flow__cond-rule--group').text()).toContain('条件组')
  })

  it('嵌套组有【自己】的组内逻辑开关，改它不动根条件的关系', async () => {
    const wrapper = mountDesigner(nestedFlow())
    await selectCondition(wrapper)

    // 根条件的关系开关在分支头部；组内的开关在组自己的行上
    expect(wrapper.find('.ts-flow__logic .el-radio-group').attributes('data-model-value')).toBe('AND')
    const groupRow = wrapper.find('.ts-flow__cond-rule--group')
    expect(groupRow.find('.el-radio-group').attributes('data-model-value')).toBe('AND')

    const orButton = groupRow.findAll('.el-radio-button').find((item) => item.text().includes('任一满足'))
    await orButton?.trigger('click')
    const next = await settle(wrapper)

    const condition = conditionOf(next)
    expect(condition.rules[1].condition?.logic, '组内逻辑必须被改成 OR').toBe('OR')
    expect(condition.logic, '根条件的关系不能被"顺手"改掉').toBe('AND')
  })

  it('达到 3 层上限：最内层组的「添加条件组」被禁用，并直接写出原因', async () => {
    const wrapper = mountDesigner(depthThreeFlow())
    await selectCondition(wrapper)

    const addGroupButtons = buttonsByText(wrapper, '添加条件组')
    // 深度不到上限的那两处（根 + 外层组）仍可点：上限是"最深那条链"的属性，不是"组的总数"
    expect(addGroupButtons).toHaveLength(3)
    expect(addGroupButtons.filter((item) => item.attributes('disabled') !== undefined)).toHaveLength(1)
    expect(wrapper.find('.ts-flow__cond-limit').text()).toContain('已达嵌套上限')
    expect(wrapper.find('.ts-flow__cond-limit').text()).toContain('最多 3 层')
  })

  it('删除条件组只删该组，兄弟条件原样保留', async () => {
    const wrapper = mountDesigner(nestedFlow())
    await selectCondition(wrapper)
    expect(conditionOf(wrapper.props('modelValue') as FlowDefinition).rules).toHaveLength(2)

    const groupRow = wrapper.find('.ts-flow__cond-rule--group')
    const deleteButton = groupRow.findAll('button').at(-1)
    await deleteButton?.trigger('click')
    const next = await settle(wrapper)

    const rules = conditionOf(next).rules
    expect(rules).toHaveLength(1)
    expect(rules[0].field).toBe('amount')
    expect(wrapper.findAll('.ts-flow__cond-rule--group')).toHaveLength(0)
  })

  it('画布摘要保留嵌套括号（否则「A 且 B 或 C」与「A 且 (B 或 C)」看不出区别）', () => {
    const wrapper = mountDesigner(nestedFlow())
    const summary = wrapper.find('.ts-flow__branch-cond').text()
    expect(summary).toContain('（')
    expect(summary).toContain('）')
    // 括号里就是组内那一句：压平层级会让"我到底配了哪一层"无从核对
    expect(summary).toContain('金额 小于 100')
  })

  it('「添加条件」往根条件追加一条规则，不影响已有的组', async () => {
    const wrapper = mountDesigner(nestedFlow())
    await selectCondition(wrapper)

    // 必须锁定根级操作行：组行里也有一个同名的「添加条件」，
    // 用「第一个文案匹配」的写法会点到组里那条（曾经真的点错过）
    const rootActions = wrapper.find('.ts-flow__cond-actions')
    const addRule = rootActions
      .findAll('button')
      .find((item) => item.text().replace(/\s+/g, '').includes('添加条件'))
    await addRule?.trigger('click')
    const next = await settle(wrapper)

    const rules = conditionOf(next).rules
    expect(rules).toHaveLength(3)
    expect(rules[2].kind).toBeUndefined()
    expect(rules[1].kind).toBe('GROUP')
    // 组内那条规则不能被顺手改动
    expect(rules[1].condition?.rules).toHaveLength(1)
    expect(wrapper.findAll('.ts-flow__cond-rule--group')).toHaveLength(1)
  })

  it('组行的「添加条件」写进**当前组**，根条件不动（路径寻址的核心用途）', async () => {
    const wrapper = mountDesigner(nestedFlow())
    await selectCondition(wrapper)

    const groupRow = wrapper.find('.ts-flow__cond-rule--group')
    const addRuleInGroup = groupRow
      .findAll('button')
      .find((item) => item.text().replace(/\s+/g, '') === '添加条件')
    await addRuleInGroup?.trigger('click')
    const next = await settle(wrapper)

    const condition = conditionOf(next)
    expect(condition.rules, '根条件仍只有「一条规则 + 一个组」').toHaveLength(2)
    expect(condition.rules[1].condition?.rules, '新规则进了组里').toHaveLength(2)
    expect(condition.rules[0].field).toBe('amount')
  })

  it('组内可以再嵌一层，但嵌到上限后**新组自己**的按钮也立刻变灰（上限按位置算）', async () => {
    const wrapper = mountDesigner(nestedFlow())
    await selectCondition(wrapper)

    // 现状：根(1) → 组(2)。在组里加一层 → 正好到 3 层上限，允许。
    const groupRow = wrapper.find('.ts-flow__cond-rule--group')
    const addSubInGroup = groupRow
      .findAll('button')
      .find((item) => item.text().replace(/\s+/g, '') === '添加条件组')
    expect(addSubInGroup?.attributes('disabled'), '加之前不该是灰的').toBeUndefined()
    await addSubInGroup?.trigger('click')
    const next = await settle(wrapper)

    const inner = conditionOf(next).rules[1].condition
    expect(inner?.rules).toHaveLength(2)
    expect(inner?.rules?.[1].kind).toBe('GROUP')

    // 加完之后：新组在 3 层位置上，它自己不能再嵌 —— 变灰的是它，不是根上那两处
    const groupRows = wrapper.findAll('.ts-flow__cond-rule--group')
    expect(groupRows).toHaveLength(2)
    const deepest = groupRows[groupRows.length - 1]
    const deepestAdd = deepest
      .findAll('button')
      .find((item) => item.text().replace(/\s+/g, '') === '添加条件组')
    expect(deepestAdd?.attributes('disabled'), '最内层组必须被禁用').toBeDefined()
    expect(wrapper.find('.ts-flow__cond-limit').text()).toContain('已达嵌套上限')
  })
})

/**
 * ：预置三级流程用到的两个新能力 ——
 * 「上级部门主管」来源（金额 > 阈值时的四级那一层）与「上一节点指定」的**指定范围**。
 *
 * 这里只断言**渲染**，不驱动下拉交互：`ElSelectStub` 把值转成 `Number`，
 * 而来源代码与范围代码都是字符串（`PARENT_DEPT_APPROVERS` / `IT_EXECUTOR`），
 * 转成 NaN 后 `changeRuleType` 会因"不认识这个类型"直接返回。用交互去测会得到一个
 * 恒真的假绿用例 —— 宁可只钉住"界面上有这几个选项"，把写入链路的覆盖交给
 * 发布预检那组用例（它们直接对 `FlowDefinition` 断言）。
 */
describe('FlowDesigner · （指定范围 / 上级部门主管）', () => {
  function prevAssignFlow(scope?: 'ALL' | 'IT_EXECUTOR'): FlowDefinition {
    return {
      start: 'n1',
      nodes: [
        {
          key: 'n1',
          type: 'APPROVAL',
          name: '直属主管审批',
          signType: 'ANY_SIGN',
          approverRules: [{ type: 'BIZ_GROUP_APPROVERS' }],
          next: 'n2'
        },
        {
          key: 'n2',
          type: 'APPROVAL',
          name: 'IT执行人处理',
          signType: 'ANY_SIGN',
          approverRules: [
            scope
              ? { type: 'PREV_ASSIGN', assignCount: 1, assignScope: scope }
              : { type: 'PREV_ASSIGN', assignCount: 1 }
          ],
          next: 'end'
        },
        { key: 'end', type: 'END', name: '结束' }
      ]
    }
  }

  it('「上一节点指定」属性面板含「指定范围」，两种范围都作为选项渲染出来', async () => {
    const wrapper = mountDesigner(prevAssignFlow('IT_EXECUTOR'))
    await wrapper.findAll('.ts-flow__card')[1].trigger('click')

    expect(wrapper.text()).toContain('指定人数')
    expect(wrapper.text()).toContain('指定范围')
    // 选项标签与节点摘要同源（ASSIGN_SCOPE_OPTIONS），两处说法必须一致
    expect(wrapper.text()).toContain('不限制（全部在职员工）')
    expect(wrapper.text()).toContain('IT执行人（IT执行人角色 或 IT运维组成员）')
  })

  it('未配 assignScope 的存量流程也能渲染出范围下拉（回落到「不限制」，不是空白控件）', async () => {
    const wrapper = mountDesigner(prevAssignFlow())
    await wrapper.findAll('.ts-flow__card')[1].trigger('click')

    expect(wrapper.text()).toContain('指定范围')
    // 面板上必须写明默认口径，否则配置者会以为"没配 = 随便谁都不行"
    expect(wrapper.text()).toContain('全部在职员工')
  })

  it('规则来源列表里「部门主管」与「上级部门主管」并存（两种来源都要选得到）', async () => {
    const wrapper = mountDesigner(prevAssignFlow())
    await wrapper.findAll('.ts-flow__card')[0].trigger('click')

    const options = wrapper.findAll('.ts-flow__rule-type option').map((item) => item.text())
    expect(options).toContain('部门主管')
    expect(options).toContain('上级部门主管')
  })

  it('只读模式下范围下拉被禁用（超管以外的人打开设计器只能看）', async () => {
    const wrapper = mountDesigner(prevAssignFlow('IT_EXECUTOR'), true)
    await wrapper.findAll('.ts-flow__card')[1].trigger('click')

    // 只取属性面板里的下拉：`ElDialog` 桩是无条件渲染的（不理会 v-model），
    // 弹窗里那个「指向已有节点」的选择器会一起被 find 到，把断言污染成假红。
    const selects = wrapper.findAll('.ts-flow__rules select.el-select')
    expect(selects.length).toBeGreaterThan(0)
    expect(selects.every((item) => item.attributes('disabled') !== undefined)).toBe(true)
  })
})
