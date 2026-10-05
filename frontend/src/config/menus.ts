import type { RoleCode } from '@/types/api'

/**
 * 菜单结构（； 重组为**业务语义**分组）
 *
 * 说明：
 * - ready=true 表示该页面已交付并接入真实路由组件（见 router/index.ts 的 resolvePageComponent）。
 *   本字段保留：它是「菜单是否指向真实页面」的判据，未登记的路径会自动落入占位页。
 * - perm 表示该菜单需要的权限码（后端起事实源：`common/permission/PermissionCatalog`）。
 *   **有 perm 时以权限码为唯一判据**（支持自定义角色）；无 perm 时回退到 `roles` 角色判断。
 * - roles 仅在没有 perm 时生效；保留它是为了给「尚未进权限目录」的页面一个可见性依据。
 *
 * ⚠️ 双侧同步：菜单是前端 `APP_MENUS` + 后端 `PermissionCatalog.TREE` **两侧**的定义，
 * 改结构必须同步（后端那棵树供「角色与权限」页渲染勾选树，并决定 `allCodes()` 的展开结果）。
 * 后端 `TREE` 是**两层**结构（组 → 叶子），`buildAllCodes()` 只遍历「组的直接子节点」，
 * 因此**不可**再嵌一层分组，否则孙节点权限码会从目录里静默消失。
 * 这也是为什么需求文档里的「审批管理」分组在两侧都只能**平铺**——见「系统设置」段的注释。
 *
 * ⚠️ 「工作台」不在本文件里：它在 `layouts/BasicLayout.vue` 中硬编码为菜单首项
 * （PC 常驻 + 手机抽屉两个渲染点），且 `/dashboard` 是 staticRoutes 里的固定路由。
 * 若在此再补一个 `/dashboard` 菜单项，动态注册会产生同路径的重复路由并把真实工作台顶掉。
 *
 * ⚠️ 未落地的页面**不要**先挂菜单（ 用户拍板：「菜单骨架和 B 一起做，不要先空壳菜单」）。
 * 当前刻意未挂的页面（「主备配置」已在 落地，不再是待挂项）：
 * - **设备维修记录 / 设备盘点 / 扫码借还 / 工作台看板**：属~ 的 P0~P3，待 A~H 做完再排期。
 *
 * 历史沿革（保留关键节点，避免后人重复踩坑）：
 * - ：移除菜单上的 `phase` 阶段徽标（阶段编号属交付过程信息，不该长期面向用户）；
 * - 2026-09-20：删除与「部门管理」重复的 `/system/approval/flow` 入口（同一组件两套菜单）；
 * - ：把「系统管理」这个大杂烩按语义拆成三个并列一级目录；
 * - **（本版）**：组织与人员三合一（员工档案 + 业务分组 + 最终处理小组
 *   → 一个「组织与人员」页）、AD 页签合并、参数按业务域重组。改造前那套
 *   「员工管理 / 审批配置」一级目录已撤销：它们是把**配置工具**摆到一级菜单的结果，
 *   而需求文档要的是「业务系统」——日常使用（审批中心 / 资产管理 / 组织与人员 / 消息 / 数据统计）
 *   与系统配置（系统设置）分开，配置入口收进「系统设置」。
 */
export interface MenuItem {
  /** 路由 path */
  path: string
  /** 菜单标题 */
  title: string
  /** 图标（Element Plus 图标组件名，需在 main.ts 的 ICONS 中注册） */
  icon?: string
  /** 可见角色；仅当未声明 perm 时生效 */
  roles?: RoleCode[]
  /** 所需权限码（见后端 PermissionCatalog）；声明后以它为准 */
  perm?: string
  /** 是否已交付（已交付页面接入真实组件，未交付进入占位页） */
  ready?: boolean
  children?: MenuItem[]
}

export const APP_MENUS: MenuItem[] = [
  {
    // 「工单管理」更名「审批中心」：改造前这个名字只涵盖了「提交申请」这一侧，
    // 而管理员在这里做的主要是「审批」；员工与管理层都能在此找到属于自己的那一份列表。
    path: '/order',
    title: '审批中心',
    icon: 'Tickets',
    children: [
      // 提交申请仍占菜单：工作台虽有「提交申请」快捷入口，但那是**页面内**的入口，
      // 菜单里去掉会让「从侧边栏找提交入口」的用户无处可点（原样保留，零回归）。
      { path: '/order/apply', title: '提交申请', perm: 'order:apply', ready: true },
      { path: '/order/approval', title: '待我审批', perm: 'order:approval', ready: true },
      // 「我的待处理」= IT 执行人侧的发放 / 回收待办，与「待我审批」是两件事：
      // 前者是"该我动手发设备"，后者是"该我点头批单"，混在一起会让执行人漏掉实操环节。
      { path: '/order/pending', title: '我的待处理', perm: 'order:pending', ready: true },
      { path: '/order/mine', title: '我发起的申请', perm: 'order:mine', ready: true },
      // 全局视图：仅管理端可见（ 工单管理；后端 API 仍二次校验权限，）
      { path: '/order/all', title: '全部工单', perm: 'order:all:view', ready: true }
    ]
  },
  {
    path: '/asset',
    title: '资产管理',
    icon: 'Box',
    children: [
      { path: '/asset/ledger', title: '设备台账', perm: 'device:ledger:view', ready: true },
      { path: '/asset/category', title: '设备分类管理', perm: 'device:category:manage', ready: true },
      //  设备故障记录：汇总上报 / 台账登记 / 归还登记三类故障。
      // 需求的「设备维修记录」（维修中列表 + 维修历史）在它基础上扩展，属 P2 排期。
      { path: '/asset/fault', title: '设备故障记录', perm: 'device:fault:view', ready: true },
      // 设备盘点（P2）：盘点的对象就是台账本体，报告里的「盘亏」也是台账数据
      { path: '/asset/inventory', title: '设备盘点', perm: 'inventory:view', ready: true }
      // 「统计报表」已按需求文档移入「数据统计」——它报的是数据，不是资产本体。
    ]
  },
  {
    // 组织与人员：三合一后的**唯一**入口 ——
    // 员工档案 / 业务分组 / 最终处理小组全部收进这一个页面的「左部门树 + 右成员列表」。
    //
    // 做成**一级叶子菜单**而不是「一级目录 + 一个子项」：目录下只有一个子项的菜单
    // 会白白多一次点击，且视觉上像没做完。
    //
    // 权限码复用 staff:view（后端 PermissionCatalog 的菜单码）：进入即可看（只读），
    // 写操作由 staff:manage / staff:import / department:manage 分别控制。
    path: '/staff/organization',
    title: '组织与人员',
    icon: 'UserFilled',
    perm: 'staff:view',
    ready: true
  },
  {
    path: '/message',
    title: '消息中心',
    icon: 'Bell',
    // 全部角色可见：消息是「我自己的通知」，与角色无关
    children: [{ path: '/message/list', title: '我的消息', perm: 'message:list', ready: true }]
  },
  {
    // 「使用记录」升格为「数据统计」：改造前它只是「查设备借用历史」，
    // 需求文档把它与统计报表归到一起，定义为「跑出来的数据」这一类。
    path: '/usage',
    title: '数据统计',
    icon: 'DataLine',
    children: [
      { path: '/usage/records', title: '使用记录', perm: 'usage:view', ready: true },
      { path: '/asset/report', title: '统计报表', perm: 'asset:report:view', ready: true }
    ]
  },
  {
    path: '/system',
    title: '系统设置',
    icon: 'Setting',
    children: [
      { path: '/system/role', title: '角色与权限', perm: 'role:view', ready: true },
      { path: '/system/flow-monitor', title: '流程监控', perm: 'flow_monitor:view', ready: true },
      { path: '/system/logs', title: '操作日志', perm: 'log:view', ready: true },
      // 异常日志：与操作日志相邻，但**只归超管** ——
      // 异常堆栈是给能改代码的人看的，管理员看了也无从下手；
      // 与「基础设施级告警只发超管」同一取向。
      // 位置与后端 PermissionCatalog.TREE 保持一致（操作日志之后、定时任务之前），
      // 否则角色页勾选树的顺序会和侧边栏菜单对不上。
      { path: '/system/exception-log', title: '异常日志', perm: 'exception:view', ready: true },
      // 安全日志：登录失败 / 账号锁定 / IP 封禁的检索与人工解封。
      // 与异常日志相邻（同属「系统出了什么事」的观测面），同样只归超管 ——
      // 解封 IP 是基础设施级动作。security:view 不在 DEFAULT_PERMISSIONS 里 ⇒ 无需授权迁移。
      { path: '/system/security-log', title: '安全日志', perm: 'security:view', ready: true },
      // 在线一键升级：升级接口能替换服务器上的可执行文件，
      // 等价于代码执行能力，因此连 view 也不给 admin，只归超管。
      { path: '/system/upgrade', title: '在线升级', perm: 'system:upgrade:view', ready: true },
      // AD 域控配置（； 拆为独立页）：决定「谁能进系统」
      // + 存服务账号绑定密码，属平台级配置。可见性由 ad:view 控制（仅超管，刻意不给 admin）。
      { path: '/system/ldap', title: 'AD 域控配置', perm: 'ad:view', ready: true },
      // 主备配置（， ）：主备双机热备的可视化页。
      // 需求文档明确「放在系统设置下，不要藏进高级设置，维护人员一眼能看到」，故与 AD、系统参数平铺。
      // 可见性由 ha:view 控制（仅超管；ha:view / ha:manage 都不在 DEFAULT_PERMISSIONS 里
      // ⇒ 既有角色不会被授予、本批**无需授权迁移**）。写操作由 ha:manage 控制。
      // 位置与后端 PermissionCatalog.TREE 保持一致（AD 域控配置与系统参数之间），
      // 否则角色页勾选树的顺序会和侧边栏菜单对不上。
      { path: '/system/ha', title: '主备配置', perm: 'ha:view', ready: true },
      // 备份记录（P0）：与「主备配置」同属基础设施级观测面 ——
      // 一个看「故障时能不能顶上」，一个看「数据丢没丢得回来」。
      // 可见性由 backup:view 控制（仅超管：backup:view / backup:manage 都不在
      // DEFAULT_PERMISSIONS 里 ⇒ 既有角色不会被授予、本批**无需授权迁移**）。
      // 位置与后端 PermissionCatalog.TREE 保持一致（主备配置与系统参数之间）。
      { path: '/system/backup', title: '备份记录', perm: 'backup:view', ready: true },
      // 系统参数（需求方三波·第一波·）：可编辑保存、热生效，仅超管
      { path: '/system/config', title: '系统参数', perm: 'config:view', ready: true },
      // ---- 审批管理（：从原「高级设置」拿出来，直接放在系统设置下）----
      //
      // ⚠️ 需求文档想要一个「审批管理」分组（申请类型管理 + 审批流程模板 + 流程监控 三项）。
      // 但后端权限目录只支持**两层**：`buildAllCodes()` 只遍历组的直接子节点，
      // 嵌三层会让孙节点的权限码从目录里静默消失（角色页勾选树上直接看不到，
      // 保存后「权限消失」且不报错）。因此这里与其它系统设置项**平铺**，
      // 用相邻位置与注释表达「同属审批配置」。流程监控已在上方（按需求文档顺序排在前面）。
      //
      // 这三项都是「配单怎么批」的小众配置（表单设计器 / 流程设计器 / 申请类型），
      // 需求文档取向：出厂预置一套能用的，普通管理员看不到、也不需要动。
      //
      //  变更：**「审批流程模板」菜单已下线**，其能力并入「申请类型与流程」合并页
      // （说明：「不要分两个菜单，点进一个申请类型左边配表单、右边配审批流程，一个页面搞定」）。
      // 权限码 `apply_type:view` 继续承担本页准入；`approval_flow:view` 只保留给
      // 「完整流程设计器」整屏页（由配置面板里的「打开完整流程设计器」进入，不占菜单）。
      { path: '/system/apply-type', title: '申请类型与流程', perm: 'apply_type:view', ready: true }
    ]
  }
]

/** 判断某个菜单是否对给定上下文可见 */
export interface MenuVisibilityContext {
  role: RoleCode
  /** 权限码判断；permissions 未加载时应回退返回 false，交由 roles 兜底 */
  hasPerm: (code: string) => boolean
  /** 权限列表是否已从后端加载（未加载时对「有 perm 的菜单」回退到角色判断） */
  permsLoaded: boolean
}

function roleAllowed(menu: MenuItem, role: RoleCode): boolean {
  if (!menu.roles || menu.roles.length === 0) {
    return true
  }
  return menu.roles.includes(role)
}

/** 按可见性上下文过滤菜单（递归，父级无可访问子项时隐藏父级） */
export function filterMenus(menus: MenuItem[], ctx: MenuVisibilityContext): MenuItem[] {
  const result: MenuItem[] = []
  for (const menu of menus) {
    if (!isVisible(menu, ctx)) {
      continue
    }
    const item: MenuItem = { ...menu }
    if (menu.children && menu.children.length > 0) {
      item.children = filterMenus(menu.children, ctx)
      if (item.children.length === 0) {
        continue
      }
    }
    result.push(item)
  }
  return result
}

function isVisible(menu: MenuItem, ctx: MenuVisibilityContext): boolean {
  if (menu.perm) {
    // 权限列表已加载：以权限码为唯一判据（支持自定义角色）
    if (ctx.permsLoaded) {
      return ctx.hasPerm(menu.perm)
    }
    // 权限列表未加载（老会话/接口降级）：回退角色判断，保证内置角色菜单不丢
    return roleAllowed(menu, ctx.role)
  }
  // 无权限码的菜单：按角色判断（自定义角色不会命中，符合「未登记页面不放开」的保守取向）
  return roleAllowed(menu, ctx.role)
}

/**
 * 按角色过滤菜单（兼容旧调用：仅用于权限列表缺失时的兜底与单测）。
 *
 * @deprecated 新代码请使用 {@link filterMenus}，它同时支持权限码与角色。
 */
export function filterMenusByRole(menus: MenuItem[], role: RoleCode): MenuItem[] {
  const result: MenuItem[] = []
  for (const menu of menus) {
    if (!roleAllowed(menu, role)) {
      continue
    }
    const item: MenuItem = { ...menu }
    if (menu.children && menu.children.length > 0) {
      item.children = filterMenusByRole(menu.children, role)
      if (item.children.length === 0) {
        continue
      }
    }
    result.push(item)
  }
  return result
}

/** 扁平化菜单，便于路由与面包屑查找 */
export function flattenMenus(menus: MenuItem[]): MenuItem[] {
  const result: MenuItem[] = []
  const walk = (items: MenuItem[]): void => {
    for (const item of items) {
      result.push(item)
      if (item.children) {
        walk(item.children)
      }
    }
  }
  walk(menus)
  return result
}
