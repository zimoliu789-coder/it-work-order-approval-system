import { createRouter, createWebHistory, type RouteRecordRaw, type RouteRecordSingleView } from 'vue-router'
import { type MenuItem } from '@/config/menus'

/**
 * 路由与守卫（ 角色权限、 强制改密）
 *
 * 设计：
 * - 静态路由只包含登录、强制改密、错误页与布局骨架；
 * - 业务路由按「当前用户可见的菜单」动态注册（未授权路由根本不存在，前端体验层面不可达）；
 * - 后端 API 仍会二次校验权限（：前端隐藏只是 UI 体验）。
 *
 * 需求方三波·第一波· 起，菜单与路由的判据由「角色」升级为「权限码」
 * （由 store 计算好可见菜单后传入 {@link registerDynamicRoutes}），以支持自定义角色。
 */

export const WHITE_LIST = [
  '/login',
  // 初始化向导（本次新增）：系统还没有任何账号，必须允许未登录访问；
  // 它也是路由守卫在「未初始化」时唯一放行的目标（否则会无限重定向）。
  '/setup',
  '/change-password',
  // 找回密码（上线前）：整条流程都发生在登录之前，必须允许未登录访问
  '/forgot-password',
  // 首次登录绑定联系方式（上线前）：登录之后、进系统之前的一道闸门。
  // 放进白名单是因为「路由守卫的通用分支」会对它再次判定并要求绑定 ——
  // 若不在白名单，守卫会先因未完成会话恢复而把它当成未登录目标处理。
  '/bind-contact',
  '/403',
  '/404'
]

const staticRoutes: RouteRecordRaw[] = [
  {
    path: '/setup',
    name: 'setup',
    component: () => import('@/views/setup/index.vue'),
    meta: { title: '初始化向导', public: true }
  },
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/login/index.vue'),
    meta: { title: '登录', public: true }
  },
  {
    path: '/change-password',
    name: 'changePassword',
    component: () => import('@/views/change-password/index.vue'),
    meta: { title: '修改密码', public: true }
  },
  {
    // 找回密码（上线前）：三步向导（账号 → 渠道 → 验证码与新密码）。
    // 独立整屏页（不带侧边栏）—— 使用者此时还没有会话。
    path: '/forgot-password',
    name: 'forgotPassword',
    component: () => import('@/views/forgot-password/index.vue'),
    meta: { title: '找回密码', public: true }
  },
  {
    // 首次登录绑定联系方式（上线前）：不绑定不能进系统，因此做成独立路由，
    // 由 router/guard.ts 依据后端下发的 requireContactBinding 强制重定向到本页。
    path: '/bind-contact',
    name: 'bindContact',
    component: () => import('@/views/bind-contact/index.vue'),
    meta: { title: '绑定联系方式', public: true }
  },
  {
    path: '/403',
    name: 'forbidden',
    component: () => import('@/views/error/403.vue'),
    meta: { title: '无权限', public: true }
  },
  {
    path: '/404',
    name: 'notFound',
    component: () => import('@/views/error/404.vue'),
    meta: { title: '页面不存在', public: true }
  },
  {
    path: '/',
    name: 'root',
    component: () => import('@/layouts/BasicLayout.vue'),
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('@/views/dashboard/index.vue'),
        meta: { title: '工作台' }
      },
      {
        path: 'profile',
        name: 'profile',
        component: () => import('@/views/profile/index.vue'),
        meta: { title: '个人中心' }
      },
      {
        // 修改密码（ 收尾优化·）：已登录用户自助修改密码。
        // 放在 root 布局下（复用顶栏 / 侧边栏），由头像下拉的「修改密码」进入 ——
        // 需求明确要求它归属「个人」而不是「系统管理」，因此不再是侧边菜单项。
        // 2026-09-20：菜单名由「密码管理」改为「修改密码」，避免与管理员侧的
        // 「重置密码」（管理员替他人改）混淆 —— 本页是「我自己改我自己的」。
        path: 'profile/password',
        name: 'profilePassword',
        component: () => import('@/views/profile/password.vue'),
        meta: { title: '修改密码' }
      },
      {
        // 导出记录：不占用侧边菜单，由「导出完成」消息或列表页按钮进入；
        // 放在 root 布局下以复用顶栏 / 侧边栏，避免跳到裸页面。
        path: 'export/records',
        name: 'exportRecords',
        component: () => import('@/views/export/records.vue'),
        meta: { title: '导出记录' }
      },
      {
        // 扫码借还（P1）：**刻意不挂左侧菜单** —— 按  用户拍板的
        // 「未落地的页面不要先挂空壳菜单」，且扫码是「站在设备前才会用」的动作，
        // 挂在一级菜单里只是噪音。入口在工作台快捷入口；路由独立存在，
        // 这样手机把工作台/扫码页加到桌面后可直接打开。
        // 不设 meta.perm：借用与归还都是任何登录用户的日常操作，
        // 与「可申请设备」接口同级别（服务端 isAuthenticated）。
        path: 'scan',
        name: 'scan',
        component: () => import('@/views/scan/index.vue'),
        meta: { title: '扫码借还' }
      },
      {
        // 盘点扫码核对（P2）：**刻意不挂左侧菜单** —— 它是从「设备盘点」列表点进来的
        // 一个任务内视图（带 :id），挂进菜单既无法表达「盘哪一个任务」，
        // 也会让菜单里多出一条点进去必然报错的项。
        // ⚠️ 本项目的路由是「菜单叶子 → 组件」映射式，菜单里没有的路径不会被自动注册，
        //    因此它必须显式写在这里，否则点「扫码核对」会掉进 404。
        path: 'asset/inventory/:id/check',
        name: 'inventoryCheck',
        component: () => import('@/views/asset/inventory/check.vue'),
        meta: { title: '盘点核对', perm: 'inventory:view' }
      },
      {
        // 表单设计器：独立整屏页面 —— 三栏布局（字段库 / 画布 / 属性）
        // 需要更多横向空间，塞进带侧边栏的常规页会很挤。
        // 不占菜单，由「申请类型管理」的「设计」按钮进入。
        // meta.perm 由 router/guard.ts 校验（前端隐藏只是体验，后端接口同样独立校验）。
        path: 'system/apply-type/designer/:templateId',
        name: 'formTemplateDesigner',
        component: () => import('@/views/system/apply-type/designer.vue'),
        meta: { title: '表单设计器', perm: 'form_template:view' }
      },
      {
        // 审批流程设计器：独立整屏页面 —— 三栏布局（图例 / 画布 / 属性）
        // 需要更多横向空间，且画布本身要占满高度。
        // 不占菜单（菜单项是列表页），由「审批流程模板」的「设计」按钮进入。
        // meta.perm 由 router/guard.ts 校验（前端隐藏只是体验，后端接口同样独立校验）。
        path: 'system/approval-flow/designer/:flowId',
        name: 'approvalFlowDesigner',
        component: () => import('@/views/system/approval-flow/designer.vue'),
        meta: { title: '审批流程设计器', perm: 'approval_flow:view' }
      },
      {
        // 提交自定义申请：按申请类型 id 进入对应的动态表单。
        // 做成独立路由（而非弹窗）是为了让「刷新后仍停在该类型上」「可分享链接」都能成立。
        path: 'order/apply/custom/:typeId',
        name: 'customApply',
        component: () => import('@/views/order/apply/custom.vue'),
        meta: { title: '提交自定义申请' }
      },
      {
        // 旧路由兼容（2026-09-20 菜单去重）。
        //
        // 原「系统管理 → 审批配置 → 分组审批流程配置」（`/system/approval/flow`）与
        // 「系统管理 → 员工管理 → 部门管理」（`/system/staff/biz-group`）
        // 指向同一个组件，属重复菜单，已删除前者。这里保留一条静态重定向，
        // 使管理员的历史书签、收藏夹、以及消息正文里可能出现的旧链接继续可用。
        //
        // 之所以放在**静态**路由里而不是动态注册：动态路由只注册「当前用户可见的菜单」，
        // 而旧路径已经不在菜单中了 —— 放静态路由才能保证任何人访问它都能被正确转发，
        // 之后再由 router/guard.ts 按目标路由的权限正常判定。
        path: 'system/approval/flow',
        name: 'legacyApprovalFlow',
        redirect: '/staff/organization',
        meta: { title: '组织与人员' }
      },
      {
        // ：组织与人员三合一（员工档案 / 部门管理 / 最终处理部门管理
        // → 一个「左部门树 + 右成员列表」的页面），三条旧路径全部并入新页。
        //
        // 之所以仍然保留重定向而不是直接删掉：这些路径会出现在**历史书签、收藏夹、
        // 以及站内消息的正文链接**里。消息是长期留存的，
        // 一条半年前的「某某工单已通过」消息里的链接不该点开就是 404。
        // 重定向后由 router/guard.ts 按目标路由的权限正常判定，无权限时同样会跳 403。
        path: 'system/staff/archive',
        name: 'legacyStaffArchive',
        redirect: '/staff/organization',
        meta: { title: '组织与人员' }
      },
      {
        // 旧「部门管理」（biz-group）路径 → 组织与人员
        path: 'system/staff/biz-group',
        name: 'legacyBizGroup',
        redirect: '/staff/organization',
        meta: { title: '组织与人员' }
      },
      {
        // 旧「最终处理部门管理」（handler-group）路径 → 组织与人员
        path: 'system/staff/handler-group',
        name: 'legacyHandlerGroup',
        redirect: '/staff/organization',
        meta: { title: '组织与人员' }
      }
    ]
  },
  {
    // 兜底：未匹配到的路径渲染 404 页面（不能直接 redirect，
    // 否则未登录用户访问深链时会被兜底路由抢先匹配，导致跳 404 而不是跳登录；
    // 真正是否需要跳登录 / 是否无权访问由 router/guard.ts 统一判定）
    path: '/:pathMatch(.*)*',
    name: 'catchAll',
    component: () => import('@/views/error/404.vue'),
    meta: { title: '页面不存在' }
  }
]

/**
 * 菜单路径 → 页面组件
 *
 * 单一登记点：新增已交付页面只需在此加一行，未登记的菜单路径自动落入占位页并标注交付阶段。
 * 这样避免「菜单配置 / 路由组件映射 / 布局」多处维护同一份路径导致权限或页面漂移。
 */
function resolvePageComponent(path: string): RouteRecordSingleView['component'] {
  switch (path) {
    case '/system/logs':
      return () => import('@/views/system/logs.vue')
    case '/system/exception-log':
      // 异常日志：未预期异常（5xx）的检索页。
      // 仅超管可见（权限码 exception:view 不在 admin 的默认权限集里）——
      // 异常堆栈是给能改代码的人看的，业务管理员看了也无从下手。
      // 与「操作日志」刻意分成两页：一个记「谁做了什么」（审计面），
      // 一个记「系统因为什么坏了」（运维面），检索维度与处置动作都不同。
      return () => import('@/views/system/exception-log/index.vue')
    case '/system/security-log':
      // 安全日志：登录失败 / 账号锁定 / IP 封禁的检索与人工解封。
      // 仅超管可见（权限码 security:view）—— 解封是基础设施级动作。
      return () => import('@/views/system/security-log/index.vue')
    case '/staff/organization':
      // 组织与人员：钉钉通讯录风格的「左部门树 + 右成员列表」。
      // 合并了原「员工档案 / 部门管理 / 最终处理部门管理」三个页面 ——
      // 这三条旧路径已由上方 staticRoutes 的重定向统一转发到本页。
      return () => import('@/views/staff/organization/index.vue')
    case '/system/config':
      // 系统参数设置（需求方三波·第一波·）：可编辑保存、热生效
      return () => import('@/views/system/config/index.vue')
    case '/system/ldap':
      // AD 域控配置（； 由「员工管理」页签拆为独立页面）。
      // 路径同时由 /system/staff/ldap 迁到 /system/ldap —— 它已不再属于「员工管理」。
      return () => import('@/views/system/ad/index.vue')
    case '/system/ha':
      // 主备双机热备配置（， ）：仅超管可见，菜单权限码 ha:view。
      // 页面是「单页多卡片」而非多 Tab：启用与虚拟 IP → 节点列表 → 数据同步 → 部署指引，
      // 四段按运维实际操作顺序排列（先启用，再加备节点，再看同步，最后照指引上真机）。
      return () => import('@/views/system/ha/index.vue')
    case '/system/backup':
      // 备份记录（P0）：只看「备份到底有没有在跑」，调度与保留策略在系统参数里配
      return () => import('@/views/system/backup/index.vue')
    case '/system/role':
      // 角色与权限管理（需求方三波·第一波·）
      return () => import('@/views/system/role/index.vue')
    case '/system/upgrade':
      // 在线一键升级：上传 → 校验 → 备份 → 落盘 → 应用 → 回滚。
      // 仅超管可见（权限码 system:upgrade:view，且后端另有 app.upgrade.enabled 总开关）。
      return () => import('@/views/system/upgrade/index.vue')
    case '/system/apply-type':
      // 申请类型与审批流程：合并页 —— 卡片列表 + 三步新建向导 + 左表单右流程配置。
      // 改造前这里是「表单模板 / 申请类型」两个页签，而流程模板还是另一个菜单；
      // 说明「不要分两个菜单 …… 一个页面搞定」，因此合并到本页。
      return () => import('@/views/system/apply-config/index.vue')
    case '/system/approval-flow':
      // 审批流程模板列表页**已下线**：其能力并入上方的合并页。
      // 保留这个 case 而不是删掉，是为了让旧书签 / 旧链接不至于落到 404 ——
      // 直接重定向到合并页（在那里点「配置」即可改流程）。
      return { path: '/system/apply-type', replace: true }
    case '/system/flow-monitor':
      // 流程监控（ · M7）：模板工单量 / 平均审批时长 / 瓶颈节点
      return () => import('@/views/system/flow-monitor/index.vue')
    case '/asset/ledger':
      return () => import('@/views/asset/ledger/index.vue')
    case '/asset/category':
      return () => import('@/views/asset/category/index.vue')
    case '/asset/fault':
      //  设备故障记录
      return () => import('@/views/asset/fault/index.vue')
    case '/asset/inventory':
      // 设备盘点（P2）：盘点任务 + 扫码核对 + 盘点报告
      return () => import('@/views/asset/inventory/index.vue')
    case '/asset/report':
      //  统计报表：借用频次 / 审批时效 / 故障统计
      return () => import('@/views/asset/report/index.vue')
    case '/usage/records':
      // 使用记录（需求方三波·第一波·）：设备 / 员工借用历史查询
      return () => import('@/views/usage/records/index.vue')
    case '/order/apply':
      return () => import('@/views/order/apply/index.vue')
    case '/order/mine':
      return () => import('@/views/order/mine/index.vue')
    case '/order/approval':
      return () => import('@/views/order/approval/index.vue')
    case '/order/pending':
      return () => import('@/views/order/pending/index.vue')
    case '/order/all':
      return () => import('@/views/order/all/index.vue')
    case '/message/list':
      //  消息中心：分页浏览 + 筛选 + 已读 + 删除 + 跳转
      return () => import('@/views/message/index.vue')
    default:
      return () => import('@/views/placeholder/index.vue')
  }
}

const router = createRouter({
  history: createWebHistory(),
  routes: staticRoutes,
  scrollBehavior: () => ({ top: 0 })
})

/** 已动态注册的路由名，便于切换账号时清理 */
let registeredRouteNames: string[] = []

/** 收集菜单树中的所有叶子节点 */
function collectLeaves(menus: MenuItem[]): MenuItem[] {
  const leaves: MenuItem[] = []
  const walk = (items: MenuItem[]): void => {
    for (const item of items) {
      if (item.children && item.children.length > 0) {
        walk(item.children)
      } else {
        leaves.push(item)
      }
    }
  }
  walk(menus)
  return leaves
}

/**
 * 注册业务路由
 *
 * @param menus 当前用户可见的菜单树（由 store 依据权限 + 角色计算后传入）
 */
export function registerDynamicRoutes(menus: MenuItem[]): void {
  clearDynamicRoutes()

  const leaves = collectLeaves(menus)
  for (const leaf of leaves) {
    const name = 'menu:' + leaf.path
    const path = leaf.path.startsWith('/') ? leaf.path.slice(1) : leaf.path
    router.addRoute('root', {
      path,
      name,
      component: resolvePageComponent(leaf.path),
      meta: {
        title: leaf.title,
        // `phase` 已从菜单配置中移除（ 收尾优化·）：
        // 阶段编号只在交付过程中有意义，功能全部交付后它只会误导使用者。
        ready: leaf.ready === true
      }
    })
    registeredRouteNames.push(name)
  }
}

/** 清理动态路由（退出登录 / 切换账号时调用） */
export function clearDynamicRoutes(): void {
  for (const name of registeredRouteNames) {
    if (router.hasRoute(name)) {
      router.removeRoute(name)
    }
  }
  registeredRouteNames = []
}

export default router
