import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import MenuTree from '@/components/MenuTree.vue'
import { epStubs } from '@/test/stubs'
import type { MenuItem } from '@/config/menus'

/**
 * MenuTree 组件级测试（三波补做·第三波·； 收尾优化· 同步调整）
 *
 * 覆盖点（这些行为只在组件挂载后才成立，纯函数单测覆盖不到）：
 * 1. 递归渲染：父级 -> 子级的层级结构是否正确展开；
 * 2. 叶子/分组的模板分支：有 children 走 sub-menu，无 children 走 menu-item；
 * 3. 不再渲染任何 `P{n}` 阶段徽标（阶段信息已从面向用户的界面移除）。
 */

function mountTree(items: MenuItem[]) {
  return mount(MenuTree, {
    props: { items },
    global: { components: epStubs }
  })
}

const ITEMS: MenuItem[] = [
  { path: '/dashboard', title: '工作台', ready: true },
  {
    path: '/order',
    title: '工单管理',
    children: [
      { path: '/order/mine', title: '我的工单', ready: true },
      { path: '/order/all', title: '全部工单', perm: 'order:all:view', ready: true }
    ]
  }
]

describe('MenuTree', () => {
  it('按层级递归渲染叶子与分组', () => {
    const wrapper = mountTree(ITEMS)

    // 顶层叶子 + 分组内叶子，共 3 个 menu-item；分组本身是 sub-menu
    const leaves = wrapper.findAll('.el-menu-item')
    expect(leaves).toHaveLength(3)
    expect(wrapper.findAll('.el-sub-menu')).toHaveLength(1)

    const text = wrapper.text()
    expect(text).toContain('工作台')
    expect(text).toContain('工单管理')
    expect(text).toContain('我的工单')
    expect(text).toContain('全部工单')
  })

  it('不再渲染任何阶段徽标（P1 / P9 等一律不出现）', () => {
    const wrapper = mountTree(ITEMS)
    expect(wrapper.find('.ts-menu-tag').exists()).toBe(false)
    expect(wrapper.text()).not.toMatch(/P\d+/)
  })

  it('空菜单渲染为空，不抛错', () => {
    const wrapper = mountTree([])
    expect(wrapper.findAll('.el-menu-item')).toHaveLength(0)
    expect(wrapper.findAll('.el-sub-menu')).toHaveLength(0)
  })
})
