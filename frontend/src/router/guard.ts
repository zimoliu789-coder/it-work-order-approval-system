import { watch } from 'vue'
import type { Router } from 'vue-router'
import { useSiteStore } from '@/store/site'
import { useUserStore } from '@/store/user'
import { WHITE_LIST } from '@/router'
import setupApi from '@/api/setup'

/**
 * 初始化状态缓存（本次新增）。
 *
 * 只在会话内探一次：**默认视为「已初始化」**，这样后端不可达时不会把所有人
 * 永久挡在向导页（那会把「网络问题」变成「系统不可用」）。判断错误时用户看到的是
 * 登录页的连接错误提示，比一个永远打不开的向导页好排查。
 */
let setupChecked = false
let setupInitialized = true

async function ensureSetupChecked(): Promise<boolean> {
  if (setupChecked) {
    return setupInitialized
  }
  try {
    const status = await setupApi.status()
    setupInitialized = status.initialized
  } catch {
    setupInitialized = true
  }
  setupChecked = true
  return setupInitialized
}

/** 供测试与「初始化完成后」手动失效缓存 */
export function resetSetupCheck(initialized: boolean): void {
  setupChecked = true
  setupInitialized = initialized
}

/**
 * 全局路由守卫（ 角色权限、 强制改密、 UI 规范）
 *
 * 处理链：
 * 1. 白名单页面直接放行；已登录用户访问登录页自动跳工作台；
 * 2. 未登录时尝试用 HttpOnly Cookie 恢复会话，成功则动态注册路由后重新解析目标地址；
 * 3. 恢复失败跳登录页并记录回跳地址；
 * 4. 强制改密状态下，除改密页外一律强制跳转（与后端 FORCE_CHANGE_PASSWORD 拦截呼应）；
 * 5. 未匹配到路由（含无权访问的菜单）统一进 404。
 */
export function setupRouterGuard(router: Router): void {
  router.beforeEach(async (to) => {
    const userStore = useUserStore()
    const target = to.path

    // 初始化闸门（本次新增）：库中还没有超级管理员时，任何页面都先引导到初始化向导。
    // 放在最前 —— 它比「登录态」更基础：没有超管时连一个可登录的账号都不存在。
    const initialized = await ensureSetupChecked()
    if (!initialized) {
      return target === '/setup' ? true : '/setup'
    }
    if (target === '/setup') {
      // 已初始化：向导页不再有意义。回登录页（已登录则直接回工作台）。
      return userStore.isLogin ? '/dashboard' : '/login'
    }

    if (WHITE_LIST.includes(target)) {
      if (target === '/login' && userStore.isLogin) {
        if (userStore.forceChangePassword) {
          return '/change-password'
        }
        // ：改完密码之后还有一道「绑定联系方式」。
        // 放在这里而不是只放在下面的通用分支：登录后直奔 /login 的用户
        // 也会被引导到绑定页，不会出现「绕过登录页就跳过了绑定」的缺口。
        if (userStore.requireContactBinding) {
          return '/bind-contact'
        }
        return '/dashboard'
      }
      return true
    }

    if (!userStore.isLogin) {
      try {
        await userStore.fetchCurrentUser()
        // 动态路由刚注册完成，需要重新解析一次目标地址。
        // 必须用 path/query/hash 分开传，不能把 to.fullPath 塞进 path 字段 —— 
        // vue-router 不会解析 location 对象里的 query 字符串，会导致 ?page=3 之类的参数被丢弃。
        return { path: to.path, query: to.query, hash: to.hash, replace: true }
      } catch {
        return {
          path: '/login',
          query: to.fullPath === '/' ? {} : { redirect: to.fullPath }
        }
      }
    }

    if (userStore.forceChangePassword && target !== '/change-password') {
      return '/change-password'
    }

    // 首次登录强制绑定手机号或邮箱（）：不绑定就不能进系统。
    //
    // 判据 `requireContactBinding` 由后端计算并下发（手机号与邮箱都为空、
    // 且至少启用了一个验证渠道）；前端绝不自己推导 —— 两处各算一遍，
    // 迟早出现「守卫认为要绑定、后端认为不用」这类只在边界条件暴露的不一致。
    // ：两个验证开关都关时后端会下发 false，用户因此不会被堵在这里。
    if (userStore.requireContactBinding && target !== '/bind-contact') {
      return '/bind-contact'
    }

    // 静态路由的权限码校验（ 引入）。
    //
    // 动态业务路由只注册「当前用户可见的菜单」，未授权路由根本不存在；但**静态路由**
    // （如整屏的表单设计器 / 提交自定义申请）不经过菜单过滤，因此这里补一道校验 ——
    // 否则任何登录用户直接敲 URL 就能打开页面。
    // 注意：这仍只是「体验层」的拦截，真正的准入由后端每个接口独立判定。
    const requiredPerm = to.meta?.perm as string | undefined
    if (requiredPerm && !userStore.hasPerm(requiredPerm)) {
      return '/403'
    }

    if (to.matched.length === 0 || to.name === 'catchAll') {
      return '/404'
    }

    return true
  })

  const siteStore = useSiteStore()

  /**
   * 应用浏览器标签页标题。
   *
   * 站点名称改为读全局 store（）：它由后端下发、管理员可在界面上改。
   * 刻意不用构建期常量 —— 那种常量改了名称不会变，正是本需求要消除的「改了没生效」。
   * 名称未加载时 store 用的是与后端一致的兜底值，故此处无需判空。
   */
  const applyTitle = (to = router.currentRoute.value): void => {
    const title = (to.meta?.title as string | undefined) ?? ''
    document.title = title ? `${title} - ${siteStore.siteName}` : siteStore.siteName
  }

  router.afterEach((to) => applyTitle(to))

  // 站点名称是**异步**到达的：首次进入时标题先按兜底名渲染，接口返回后必须重新应用一次，
  // 否则自定义名称要等用户下一次导航才生效 —— 表现为「改完名称刷新页面标题还是旧的」。
  watch(() => siteStore.siteName, () => applyTitle())
}
