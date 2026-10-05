import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import authApi from '@/api/auth'
import { APP_MENUS, filterMenus, type MenuItem, type MenuVisibilityContext } from '@/config/menus'
import { clearDynamicRoutes, registerDynamicRoutes } from '@/router'
import type { DataScopeCode } from '@/types/permission'
import type {
  BindContactRequest,
  ChangePasswordRequest,
  LoginRequest,
  LoginUserInfo,
  RoleCode
} from '@/types/api'

/**
 * 登录用户状态（Pinia）
 *
 * 登录态本身存放在服务端签发的 HttpOnly Cookie 中，前端 store 只负责缓存
 * 「当前用户信息」「权限码集合」与「据此计算的可见菜单 + 路由」，
 * 因此刷新页面后通过 /auth/me 重新拉取即可恢复，无需把 token 存到 localStorage。
 *
 * 需求方三波·第一波·：菜单可见性由「角色」升级为「权限码」——
 * 内置三角色（super_admin / admin / user）的可见菜单与改造前完全一致；
 * 同时新建的自定义角色只要能拿到某个权限码，其对应菜单即可见（后端接口仍独立校验）。
 */
export const useUserStore = defineStore('user', () => {
  const user = ref<LoginUserInfo | null>(null)
  const loading = ref(false)

  const isLogin = computed(() => user.value !== null)
  const role = computed<RoleCode>(() => user.value?.role ?? 'user')
  const displayName = computed(() => user.value?.displayName ?? '')
  const forceChangePassword = computed(() => user.value?.forceChangePassword === true)

  /**
   * 是否为「内置超级管理员」（ /  / 的按钮显隐判据）。
   *
   * 与 `role === 'super_admin'` 的区别：其他超管也是 super_admin，但 builtInAdmin 为 false。
   * 「重置其他超管口令」「修改系统名称与 logo」「修改别人的手机号与邮箱」只归内置超管，
   * 因此不能只判角色。
   */
  const builtInAdmin = computed(() => user.value?.builtInAdmin === true)

  /**
   * 是否需要强制绑定联系方式（）。
   *
   * 由后端下发（手机号与邮箱都为空、且至少启用了一个验证渠道时为 true）。
   * 前端绝不自己推导 —— 判据必须与后端同源，否则会出现
   * 「路由守卫认为要绑定、后端接口认为不用」这类只在边界条件下暴露的不一致。
   */
  const requireContactBinding = computed(() => user.value?.requireContactBinding === true)

  /** 当前用户的手机号 / 邮箱（未绑定为 null），用于绑定引导与个人中心回填 */
  const phone = computed(() => user.value?.phone ?? null)
  const email = computed(() => user.value?.email ?? null)

  /** 当前用户权限码集合 */
  const permissions = computed<string[]>(() => user.value?.permissions ?? [])

  /** 权限列表是否已从后端加载（未加载时菜单回退按角色渲染） */
  const permsLoaded = computed(() => permissions.value.length > 0)

  /** 数据权限范围：ALL 全部 / GROUP 本部门 / SELF 仅本人 */
  const dataScope = computed<DataScopeCode>(() => user.value?.dataScope ?? 'SELF')

  /**
   * 是否拥有某权限码
   *
   * super_admin 直接短路放行 —— 与后端 `PermissionGuard` 的策略保持一致，
   * 保证即便数据库里的授权行被误删，超管界面依然完整、系统永远能被救回来。
   */
  function hasPerm(code: string): boolean {
    if (!code) {
      return true
    }
    if (role.value === 'super_admin') {
      return true
    }
    return permissions.value.includes(code)
  }

  /** 是否拥有其中任一权限码 */
  function hasAnyPerm(codes: string[]): boolean {
    return codes.some((code) => hasPerm(code))
  }

  function buildContext(info: LoginUserInfo): MenuVisibilityContext {
    const perms = info.permissions ?? []
    const isSuper = info.role === 'super_admin'
    return {
      role: info.role,
      permsLoaded: perms.length > 0,
      hasPerm: (code: string) => (isSuper ? true : perms.includes(code))
    }
  }

  /** 当前用户可见菜单 */
  const menus = computed<MenuItem[]>(() =>
    user.value ? filterMenus(APP_MENUS, buildContext(user.value)) : []
  )

  function applyUser(info: LoginUserInfo): LoginUserInfo {
    user.value = info
    // 路由与菜单使用同一份可见性计算，避免「菜单可见但路由未注册」的死链
    registerDynamicRoutes(filterMenus(APP_MENUS, buildContext(info)))
    return info
  }

  /** 登录 */
  async function login(payload: LoginRequest): Promise<LoginUserInfo> {
    loading.value = true
    try {
      const info = await authApi.login(payload)
      return applyUser(info)
    } finally {
      loading.value = false
    }
  }

  /** 拉取当前登录用户信息（刷新页面时恢复会话） */
  async function fetchCurrentUser(): Promise<LoginUserInfo> {
    const info = await authApi.me()
    return applyUser(info)
  }

  /** 修改密码 */
  async function changePassword(payload: ChangePasswordRequest): Promise<void> {
    await authApi.changePassword(payload)
    // 修改成功后服务端已清除强制改密标记，本地同步更新
    if (user.value) {
      user.value = { ...user.value, forceChangePassword: false }
    }
  }

  /**
   * 绑定 / 修改自己的手机号与邮箱（ / 四.3）。
   *
   * 接口会返回更新后的完整 `LoginUserInfo`，这里直接用它整体覆盖本地状态 ——
   * 绑定成功后 `requireContactBinding` 随之变为 false，绑定引导页因此可以立刻放行，
   * 无需再补一次 `/auth/me`（也就不会出现「已绑定但状态还是旧值」的一帧）。
   */
  async function bindContact(payload: BindContactRequest): Promise<LoginUserInfo> {
    const info = await authApi.bindContact(payload)
    return applyUser(info)
  }

  /** 退出登录 */
  async function logout(): Promise<void> {
    try {
      await authApi.logout()
    } catch {
      // 服务端登出失败（网络/服务已停）不应阻塞本地登出，
      // 否则 reset() 后用户会滞留在没有菜单的空壳页面。
    } finally {
      reset()
    }
  }

  /** 清理本地登录态与动态路由 */
  function reset(): void {
    user.value = null
    clearDynamicRoutes()
  }

  return {
    user,
    loading,
    isLogin,
    role,
    displayName,
    forceChangePassword,
    builtInAdmin,
    requireContactBinding,
    phone,
    email,
    permissions,
    permsLoaded,
    dataScope,
    hasPerm,
    hasAnyPerm,
    menus,
    login,
    fetchCurrentUser,
    changePassword,
    bindContact,
    logout,
    reset
  }
})
