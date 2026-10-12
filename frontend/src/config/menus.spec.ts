import { describe, expect, it } from 'vitest'
import { APP_MENUS, filterMenus, type MenuItem, type MenuVisibilityContext } from '@/config/menus'
import type { RoleCode } from '@/types/api'

/**
 * 菜单可见性：故障报修「处理人」（V50）
 *
 * <h2>这条通道为什么值得单独测</h2>
 * 「设备故障记录」页严格来说**不是**靠角色或权限码开放的 —— 它的执行人是
 * 「处理流程」里配的人（可能是 it_executor 角色、也可能是某个部门成员）。
 * 为此给菜单加了一条 `visibleForFaultRepairer` 通道。它的失效方式很安静：
 * 处理人拿不到这个菜单 → 站内消息点进去一次之后再也找不到入口 →
 * 只能等下一次报修通知。因此把三种身份都固化成断言。
 */

function ctx(patch: Partial<MenuVisibilityContext> = {}): MenuVisibilityContext {
  return {
    role: 'user' as RoleCode,
    permsLoaded: true,
    hasPerm: () => false,
    faultRepairer: false,
    ...patch
  }
}

/** 「资产管理」一级分组（设备故障记录挂在它下面） */
function assetGroup(menus: MenuItem[]): MenuItem | undefined {
  return menus.find((menu) => menu.path === '/asset')
}

function childPaths(menus: MenuItem[]): string[] {
  return (assetGroup(menus)?.children ?? []).map((child) => child.path)
}

/** 摊平出全部叶子路径（含所有一级分组下的条目） */
function leafPaths(menus: MenuItem[]): string[] {
  const result: string[] = []
  const walk = (items: MenuItem[]): void => {
    for (const item of items) {
      if (item.children && item.children.length > 0) {
        walk(item.children)
      } else {
        result.push(item.path)
      }
    }
  }
  walk(menus)
  return result
}

describe('菜单可见性 · 故障报修处理人（V50）', () => {
  it('处理人：看得到「设备故障记录」，且资产分组下只有这一条', () => {
    const menus = filterMenus(APP_MENUS, ctx({ faultRepairer: true }))

    expect(childPaths(menus)).toEqual(['/asset/fault'])
  })

  it('普通员工（既不是处理人、也没有任何资产权限）：整组不出现', () => {
    const menus = filterMenus(APP_MENUS, ctx())

    expect(assetGroup(menus)).toBeUndefined()
  })

  it('管理员：仍按权限码可见（faultRepairer 为 false 也不受影响）', () => {
    const menus = filterMenus(
      APP_MENUS,
      ctx({ role: 'admin' as RoleCode, hasPerm: (code) => code === 'device:fault:view' })
    )

    expect(childPaths(menus)).toEqual(['/asset/fault'])
  })

  it('该通道只放行显式标记的条目 —— 不会顺带放开其它受限页面', () => {
    // 它是「一条窄通道」，不是「处理人 = 放开资产模块」。台账 / 分类 / 盘点都必须仍然看不见，
    // 管理员专属的「全部工单」同理。
    const menus = filterMenus(APP_MENUS, ctx({ faultRepairer: true }))

    expect(childPaths(menus)).toEqual(['/asset/fault'])
    expect(leafPaths(menus)).not.toContain('/asset/ledger')
    expect(leafPaths(menus)).not.toContain('/order/all')
  })
})
