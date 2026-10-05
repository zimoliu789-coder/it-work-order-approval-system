import type {
  ApiResponse,
  ChangePasswordRequest,
  HealthInfo,
  LoginRequest,
  LoginUserInfo
} from '@/types/api'

/**
 * 前端 Mock 数据（仅开发环境、仅当 VITE_USE_MOCK=true 时生效）
 *
 * 用途：后端未启动（例如尚未安装 JDK/MySQL）时走查 UI、核对 PC 与手机响应式效果。
 * 联调真实接口时请在 .env.development 中把 VITE_USE_MOCK 改为 false。
 */

/**
 * Mock 开关：必须同时满足「开发构建」与「显式开启」。
 * 仅判断 VITE_USE_MOCK 是不够的 —— 一旦该变量被误写进 .env.production，
 * 生产包会带上硬编码演示账号与明文口令；加上 DEV 守卫后，生产构建会把
 * 整个 mock 模块 tree-shake 掉，从根上消除误上线风险。
 */
export const MOCK_ENABLED = import.meta.env.DEV && import.meta.env.VITE_USE_MOCK === 'true'

const MOCK_USERS: Record<string, { user: LoginUserInfo; password: string }> = {
  '超级管理员': {
    password: 'Init@12345',
    user: {
      id: 1,
      username: '超级管理员',
      displayName: '超级管理员',
      role: 'super_admin',
      authType: 'LOCAL',
      departmentId: null,
      departmentName: null,
      forceChangePassword: true,
      superAdmin: true,
      builtInAdmin: true,
      adminOrAbove: true
    }
  },
  '王经理': {
    password: 'Init@12345',
    user: {
      id: 2,
      username: '王经理',
      displayName: '王经理',
      role: 'admin',
      authType: 'LOCAL',
      departmentId: 1,
      departmentName: '研发部',
      forceChangePassword: false,
      superAdmin: false,
      builtInAdmin: false,
      adminOrAbove: true
    }
  },
  '张三': {
    password: 'Init@12345',
    user: {
      id: 3,
      username: '张三',
      displayName: '张三',
      role: 'user',
      authType: 'LOCAL',
      departmentId: 1,
      departmentName: '研发部',
      forceChangePassword: false,
      superAdmin: false,
      builtInAdmin: false,
      adminOrAbove: false
    }
  }
}

/** 登录态与密码覆盖的持久化键（仅 Mock 模式使用，不影响真实接口链路） */
const SESSION_KEY = 'ts-mock-current-user'
const PASSWORD_KEY = 'ts-mock-passwords'

/**
 * 用 localStorage 保存 Mock 会话：
 * 1) 页面刷新后不会被打回登录页，便于走查登录后的页面布局；
 * 2) 新开标签页也能共享登录态（sessionStorage 不跨标签页）。
 * 注意：仅在 VITE_USE_MOCK=true 时才会走到本模块。
 */
function readSession(): LoginUserInfo | null {
  try {
    const raw = localStorage.getItem(SESSION_KEY)
    return raw ? (JSON.parse(raw) as LoginUserInfo) : null
  } catch {
    return null
  }
}

function writeSession(user: LoginUserInfo | null): void {
  try {
    if (user) {
      localStorage.setItem(SESSION_KEY, JSON.stringify(user))
    } else {
      localStorage.removeItem(SESSION_KEY)
    }
  } catch {
    /* 隐私模式下 localStorage 不可用，忽略 */
  }
}

function readPasswords(): Record<string, string> {
  try {
    const raw = localStorage.getItem(PASSWORD_KEY)
    return raw ? (JSON.parse(raw) as Record<string, string>) : {}
  } catch {
    return {}
  }
}

function writePassword(username: string, password: string): void {
  try {
    const all = readPasswords()
    all[username] = password
    localStorage.setItem(PASSWORD_KEY, JSON.stringify(all))
  } catch {
    /* 忽略 */
  }
}

let currentUser: LoginUserInfo | null = typeof localStorage === 'undefined' ? null : readSession()

function ok<T>(data: T, message = '操作成功'): ApiResponse<T> {
  return { code: 'SUCCESS', message, data, traceId: `mock-${Date.now()}` }
}

function fail(code: string, message: string): ApiResponse<null> {
  return { code, message, data: null, traceId: `mock-${Date.now()}` }
}

// ---------------------------------------------------------------------
//  演示数据（Mock 只读桩，形状与后端接口保持一致）
// ---------------------------------------------------------------------

/** 员工选项（含所属分组；与后端 DemoDataInitializer 的演示数据对应） */
const MOCK_USER_OPTIONS = [
  { id: 2, username: '张伟', displayName: '张伟', role: 'user', departmentId: 1, departmentName: '研发部', dimission: false, enabled: true, available: true },
  { id: 3, username: '李娜', displayName: '李娜', role: 'user', departmentId: 1, departmentName: '研发部', dimission: false, enabled: true, available: true },
  { id: 4, username: '刘洋', displayName: '刘洋', role: 'admin', departmentId: 1, departmentName: '研发部', dimission: false, enabled: true, available: true },
  { id: 5, username: '王强', displayName: '王强', role: 'user', departmentId: 2, departmentName: '市场部', dimission: false, enabled: true, available: true },
  { id: 6, username: '陈晨', displayName: '陈晨', role: 'user', departmentId: 3, departmentName: '行政部', dimission: false, enabled: true, available: true },
  { id: 7, username: '赵敏', displayName: '赵敏', role: 'admin', departmentId: 3, departmentName: '行政部', dimission: false, enabled: true, available: true }
]

/**
 * 部门树。
 *
 * 只提供只读桩，便于后端未启动时走查「组织与人员」页的树渲染与人数徽标；
 * 写操作仍以真实后端为准（mock 不实现写接口）。
 */
const MOCK_DEPARTMENT_TREE = [
  {
    id: 1,
    deptName: '研发部',
    parentId: null,
    path: '/1/',
    depth: 0,
    sortOrder: 1,
    handlerGroup: false,
    approvalFlowVersionId: null,
    status: true,
    remark: '演示数据',
    memberCount: 2,
    totalMemberCount: 3,
    managers: [MOCK_USER_OPTIONS[2]],
    children: [
      {
        id: 5,
        deptName: '前端一组',
        parentId: 1,
        path: '/1/5/',
        depth: 1,
        sortOrder: 1,
        handlerGroup: false,
        approvalFlowVersionId: null,
        status: true,
        remark: '演示数据',
        memberCount: 1,
        totalMemberCount: 1,
        managers: [],
        children: []
      }
    ]
  },
  {
    id: 2,
    deptName: '市场部',
    parentId: null,
    path: '/2/',
    depth: 0,
    sortOrder: 2,
    handlerGroup: false,
    approvalFlowVersionId: null,
    status: true,
    remark: '演示数据',
    memberCount: 1,
    totalMemberCount: 1,
    managers: [],
    children: []
  },
  {
    id: 3,
    deptName: '行政部',
    parentId: null,
    path: '/3/',
    depth: 0,
    sortOrder: 3,
    handlerGroup: false,
    approvalFlowVersionId: null,
    status: true,
    remark: '演示数据',
    memberCount: 2,
    totalMemberCount: 2,
    managers: [],
    children: []
  },
  {
    id: 4,
    deptName: 'IT运维组',
    parentId: null,
    path: '/4/',
    depth: 0,
    sortOrder: 4,
    handlerGroup: true,
    approvalFlowVersionId: null,
    status: true,
    remark: '最终处理部门（发设备环节的执行部门）',
    memberCount: 5,
    totalMemberCount: 5,
    managers: [],
    children: []
  }
]

/** 部门扁平选项（下拉用，带层级路径） */
const MOCK_DEPARTMENT_OPTIONS = [
  { id: 1, deptName: '研发部', parentId: null, depth: 0, handlerGroup: false, displayPath: '研发部' },
  { id: 5, deptName: '前端一组', parentId: 1, depth: 1, handlerGroup: false, displayPath: '研发部 / 前端一组' },
  { id: 2, deptName: '市场部', parentId: null, depth: 0, handlerGroup: false, displayPath: '市场部' },
  { id: 3, deptName: '行政部', parentId: null, depth: 0, handlerGroup: false, displayPath: '行政部' },
  { id: 4, deptName: 'IT运维组', parentId: null, depth: 0, handlerGroup: true, displayPath: 'IT运维组' }
]


/**
 * 处理 Mock 请求
 */
export async function mockRequest<T>(method: string, url: string, body?: unknown): Promise<ApiResponse<T>> {
  // 模拟网络延时，便于观察 loading 状态
  await new Promise((resolve) => setTimeout(resolve, 260))
  const path = url.replace(/^\/api/, '').split('?')[0]
  const upperMethod = method.toUpperCase()

  if (upperMethod === 'POST' && path === '/auth/login') {
    const payload = body as LoginRequest
    const username = payload?.username?.trim() ?? ''
    const entry = MOCK_USERS[username]
    const storedPassword = readPasswords()[username]
    const effectivePassword = storedPassword ?? entry?.password
    if (!entry || effectivePassword !== payload.password) {
      return fail('BAD_CREDENTIALS', '账号或密码错误，剩余 4 次尝试机会') as ApiResponse<T>
    }
    // 本会话内已改过密码的用户不再强制改密
    currentUser = { ...entry.user, forceChangePassword: storedPassword ? false : entry.user.forceChangePassword }
    writeSession(currentUser)
    return ok(currentUser, '登录成功') as ApiResponse<T>
  }

  if (upperMethod === 'POST' && path === '/auth/logout') {
    currentUser = null
    writeSession(null)
    return ok<null>(null, '已退出登录') as ApiResponse<T>
  }

  if (upperMethod === 'GET' && path === '/auth/me') {
    if (!currentUser) {
      return fail('UNAUTHORIZED', '未登录或登录状态已失效，请重新登录') as ApiResponse<T>
    }
    return ok(currentUser) as ApiResponse<T>
  }

  if (upperMethod === 'POST' && path === '/auth/change-password') {
    const payload = body as ChangePasswordRequest
    if (payload.newPassword !== payload.confirmPassword) {
      return fail('PARAM_INVALID', '两次输入的新密码不一致') as ApiResponse<T>
    }
    if (payload.newPassword.length < 8) {
      return fail('PASSWORD_WEAK', '密码长度不能少于 8 位') as ApiResponse<T>
    }
    if (currentUser) {
      // 让 Mock 行为更接近真实：改密后更新密码并清除强制改密标记（同一会话内持久）
      const name = currentUser.username
      writePassword(name, payload.newPassword)
      if (MOCK_USERS[name]) {
        MOCK_USERS[name].user.forceChangePassword = false
      }
      currentUser = { ...currentUser, forceChangePassword: false }
      writeSession(currentUser)
    }
    return ok<null>(null, '密码修改成功') as ApiResponse<T>
  }

  if (upperMethod === 'GET' && path === '/health') {
    return ok<HealthInfo>({
      status: 'UP',
      application: 'ticket-system(mock)',
      profile: 'dev',
      time: new Date().toLocaleString('zh-CN'),
      traceId: 'mock'
    }) as ApiResponse<T>
  }

  if (upperMethod === 'GET' && path === '/system/configs') {
    return ok<unknown[]>([]) as ApiResponse<T>
  }

  if (upperMethod === 'GET' && path === '/logs') {
    return ok({ records: [], total: 0, current: 1, size: 20, pages: 0 }) as ApiResponse<T>
  }

  // ------------------------------------------------------------------
  // ：部门 / 审批节点 / 最终处理部门
  // 仅提供只读桩，便于后端未启动时走查配置界面；写操作仍以真实后端为准。
  // ------------------------------------------------------------------
  if (upperMethod === 'GET' && path === '/users/options') {
    return ok(MOCK_USER_OPTIONS) as ApiResponse<T>
  }

  if (upperMethod === 'GET' && path === '/departments/tree') {
    return ok(MOCK_DEPARTMENT_TREE) as ApiResponse<T>
  }

  if (upperMethod === 'GET' && path === '/departments/options') {
    return ok(MOCK_DEPARTMENT_OPTIONS) as ApiResponse<T>
  }

  return fail('NOT_FOUND', `Mock 未实现的接口：${upperMethod} ${path}`) as ApiResponse<T>
}
