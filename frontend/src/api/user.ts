import { http } from '@/api/request'
import type { BatchResult, PageResult } from '@/types/api'
import type {
  DimissionResult,
  UserAccount,
  UserAccountQuery,
  UserCreatePayload,
  UserImportExecutePayload,
  UserImportFailureReportPayload,
  UserImportPreview,
  UserImportResult,
  UserOption,
  UserPasswordResetVO,
  UserUpdatePayload
} from '@/types/user'

/**
 * 员工接口
 *
 * - {@link userApi.options}： 各类「选择员工」场景的公共支撑（全体登录用户可用）；
 * - {@link userApi.page} / 离职相关 / 写操作：仅 super_admin，服务端会二次校验角色（越权 403）。
 */
export const userApi = {
  /**
   * 员工下拉选项
   * @param keyword 姓名或登录名模糊匹配
   * @param departmentId 仅返回该分组的员工
   */
  options(params: { keyword?: string; departmentId?: number } = {}) {
    const query: Record<string, unknown> = {}
    if (params.keyword) query.keyword = params.keyword
    if (params.departmentId != null) query.departmentId = params.departmentId
    return http.get<UserOption[]>('/users/options', query)
  },

  /** 员工账号分页（super_admin / admin） */
  page(query: UserAccountQuery = {}) {
    const params: Record<string, unknown> = {}
    if (query.page != null) params.page = query.page
    if (query.size != null) params.size = query.size
    if (query.keyword) params.keyword = query.keyword
    if (query.dimission != null) params.dimission = query.dimission
    if (query.departmentId != null) params.departmentId = query.departmentId
    if (query.role) params.role = query.role
    // 账号来源筛选（ ）：后端只认 LOCAL / LDAP，非法值会显式报错
    if (query.authType) params.authType = query.authType
    return http.get<PageResult<UserAccount>>('/users/page', params)
  },

  /** 员工详情（编辑回填，super_admin / admin） */
  detail(id: number) {
    return http.get<UserAccount>(`/users/${id}`)
  },

  /**
   * 新增员工（仅 super_admin）
   *
   * 后端 DTO 含 password，新员工首登强制改密，基准口令由环境变量统一配置（演示/测试）。
   */
  create(data: UserCreatePayload) {
    return http.post<UserAccount>('/users', data)
  },

  /**
   * 批量调整员工部门（P3）
   *
   * 只改归属，**不重算在途工单的审批人快照** —— 已提交的工单仍按提交时冻结的规则走完，
   * 该变更对**新提交**的工单生效。
   */
  batchChangeDepartment(ids: number[], departmentId: number) {
    return http.post<BatchResult>('/users/batch/department', { ids, departmentId })
  },

  /**
   * 编辑员工（仅 super_admin）
   *
   * 登录名不可改（后端 DTO 无该字段），因此载荷里不传 username。
   */
  update(id: number, data: UserUpdatePayload) {
    return http.put<void>(`/users/${id}`, data)
  },

  /**
   * 重置密码（仅 super_admin）：**临时口令由服务端生成**并在返回值里带回。
   *
   * 2026-09-20 ：改造前这里传的是一个写死的默认口令，管理员在界面上
   * 既看不到也不被提示，属于「静默失效」。现在不再传口令，改为接收服务端生成的
   * 临时口令并展示给管理员（见 views/staff/organization/index.vue 的结果弹窗）。
   * 重置后该员工首登强制改密，且其全部既有会话立即失效。
   */
  resetPassword(id: number) {
    return http.post<UserPasswordResetVO>(`/users/${id}/password`)
  },

  /** 启用账号（仅 super_admin），与离职是两个独立概念 */
  enable(id: number) {
    return http.post<void>(`/users/${id}/enable`)
  },

  /** 禁用账号（仅 super_admin），与离职是两个独立概念 */
  disable(id: number) {
    return http.post<void>(`/users/${id}/disable`)
  },

  /**
   * 标记员工离职（仅 super_admin）
   *
   * 副作用：名下「使用中」工单全部转「待收回」并通知对应执行人；账号禁用、禁止登录。
   */
  markDimission(id: number) {
    return http.post<DimissionResult>(`/users/${id}/dimission`)
  },

  /** 恢复员工在职（仅 super_admin）：清空离职标记并重新启用账号 */
  reinstate(id: number) {
    return http.post<DimissionResult>(`/users/${id}/reinstate`)
  }
}

/**
 * 员工批量导入接口（管理端，仅 super_admin）
 *
 * 流程：下载模板 → 上传校验（非阻断，返回逐行结果）→ 确认导入（只导入通过行）
 * → 失败行下载明细后修正重导。
 *
 * 下载类接口走 fetch（二进制流非统一 JSON 信封，不能走 http 实例）；
 * 上传用 FormData，交由 http 实例自动设 Content-Type，与设备导入一致。
 */
export const userImportApi = {
  /** 下载导入模板（.xlsx，列：姓名/登录名/初始密码/部门名称/角色/显示名称） */
  downloadTemplate() {
    return downloadFile('/users/import/template', '员工导入模板.xlsx', 'GET')
  },

  /** 上传校验：解析全部行做逐行校验（双重唯一校验），返回预览（不写库） */
  preview(file: File) {
    const form = new FormData()
    form.append('file', file)
    return http.post<UserImportPreview>('/users/import/preview', form)
  },

  /** 确认导入：仅导入校验通过的行 */
  execute(payload: UserImportExecutePayload) {
    return http.post<UserImportResult>('/users/import/execute', payload)
  },

  /** 下载失败明细（含「失败原因」列，供修正后重导） */
  downloadFailureReport(payload: UserImportFailureReportPayload) {
    return downloadFile('/users/import/failure-report', '员工导入失败明细.xlsx', 'POST', payload)
  }
}

/** 与 axios 实例保持一致的接口前缀 */
function apiBase(): string {
  return import.meta.env.VITE_API_BASE_URL || '/api'
}

/**
 * 文件下载（模板 / 失败明细）
 *
 * 这两类响应是二进制流而非统一 JSON 信封，因此**不能**走 `http`：
 * 其响应拦截器会把非信封响应判定为「格式异常」并抛错。
 * 这里直接用 fetch 携带 Cookie 与 CSRF 头，并复用同一 baseURL 前缀（与设备导入实现一致）。
 */
async function downloadFile(
  url: string,
  fallbackName: string,
  method: 'GET' | 'POST',
  body?: unknown
): Promise<void> {
  const response = await fetch(`${apiBase()}${url}`, {
    method,
    credentials: 'include',
    headers: body
      ? { 'Content-Type': 'application/json;charset=UTF-8', 'X-Requested-With': 'XMLHttpRequest' }
      : { 'X-Requested-With': 'XMLHttpRequest' },
    body: body ? JSON.stringify(body) : undefined
  })
  if (!response.ok) {
    // 后端的错误仍是 JSON 信封，尽量取出可读 message
    let message = '下载失败，请稍后重试'
    try {
      const err = (await response.json()) as { message?: string }
      if (err?.message) {
        message = err.message
      }
    } catch {
      // 非 JSON 错误体，保留兜底文案
    }
    ElMessage.error(message)
    throw new Error(message)
  }
  const blob = await response.blob()
  const filename = resolveFilename(response.headers.get('Content-Disposition'), fallbackName)
  const link = document.createElement('a')
  link.href = URL.createObjectURL(blob)
  link.download = filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(link.href)
}

/** 从 Content-Disposition 解析文件名（优先 RFC 5987 的 filename*） */
function resolveFilename(disposition: string | null, fallback: string): string {
  if (!disposition) {
    return fallback
  }
  const utf8 = /filename\*=UTF-8''([^;]+)/i.exec(disposition)
  if (utf8?.[1]) {
    return decodeURIComponent(utf8[1])
  }
  const plain = /filename="?([^";]+)"?/i.exec(disposition)
  return plain?.[1] ?? fallback
}

export default userApi
