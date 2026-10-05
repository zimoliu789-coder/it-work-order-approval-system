import { http } from '@/api/request'
import type { BatchResult, PageResult } from '@/types/api'
import type {
  DeviceCategory,
  DeviceCategoryPayload,
  DeviceFaultHandlePayload,
  DeviceFaultItem,
  DeviceFaultPayload,
  DeviceFaultSelectable,
  DeviceFaultQuery,
  DeviceImportExecutePayload,
  DeviceImportFailureReportPayload,
  DeviceImportPreview,
  DeviceImportResult,
  DeviceItem,
  DeviceLockInfo,
  DevicePayload,
  DeviceQuery,
  DeviceStatusCode
} from '@/types/device'

/**
 * 设备分类接口
 *
 * 权限：super_admin / admin 均可读写（「admin：设备台账管理」）。
 * 变更类操作后端纳入审计：删除为高风险同步留痕，其余为普通异步留痕。
 */
export const deviceCategoryApi = {
  /** 分类树（一级分类含 children 二级分类） */
  tree() {
    return http.get<DeviceCategory[]>('/device-categories')
  },

  /** 新增分类：parentId 为空或 0 表示一级分类 */
  create(data: DeviceCategoryPayload) {
    return http.post<number>('/device-categories', data)
  },

  /** 修改分类名称与备注（不支持调整层级，故不接收 parentId） */
  update(id: number, data: { categoryName: string; remark: string | null }) {
    return http.put<void>(`/device-categories/${id}`, data)
  },

  /** 同级排序（整组提交全部同级分类ID） */
  sort(parentId: number, orderedIds: number[]) {
    return http.put<void>('/device-categories/sort', { parentId, orderedIds })
  },

  /** 删除分类（有子分类或仍被设备引用时后端拒绝） */
  remove(id: number) {
    return http.delete<void>(`/device-categories/${id}`)
  }
}

/**
 * 设备台账接口（ / ）
 */
export const deviceApi = {
  /** 分页查询（关键词 / 分类 / 状态过滤） */
  page(query: DeviceQuery = {}) {
    const params: Record<string, unknown> = {}
    if (query.page != null) params.page = query.page
    if (query.size != null) params.size = query.size
    if (query.keyword) params.keyword = query.keyword
    if (query.primaryCategoryId != null) params.primaryCategoryId = query.primaryCategoryId
    if (query.secondaryCategoryId != null) params.secondaryCategoryId = query.secondaryCategoryId
    if (query.status) params.status = query.status
    return http.get<PageResult<DeviceItem>>('/devices', params)
  },

  /** 新增设备（后端固定从「可用」状态开始） */
  create(data: DevicePayload) {
    return http.post<number>('/devices', data)
  },

  /** 修改设备台账信息（不含状态） */
  update(id: number, data: DevicePayload) {
    return http.put<void>(`/devices/${id}`, data)
  },

  /**
   * 状态操作：报废 / 维修完成
   *
   * `reason` 是操作理由，后端只把它写进操作日志（`@AuditLog` HIGH 同步留痕），
   * **不会**写回设备备注 —— 设备备注是台账业务描述，两者不可互相覆盖。
   */
  changeStatus(id: number, targetStatus: DeviceStatusCode, reason?: string) {
    return http.put<void>(`/devices/${id}/status`, { targetStatus, reason: reason ?? null })
  },

  /**
   * 批量修改一级分类（P3）
   *
   * 只改主分类：单条的 `update` 是「整体替换」语义，批量套用会把品牌 / 型号 / 存放位置一并清空。
   */
  batchChangeCategory(ids: number[], categoryId: number) {
    return http.post<BatchResult>('/devices/batch/category', { ids, categoryId })
  },

  /**
   * 批量变更状态（P3）
   *
   * 逐台按**其当前状态**校验白名单，因此可能出现「一部分成功、一部分失败」——
   * 返回值里的 `failures` 会逐条给出原因，页面据此展示明细。
   */
  batchChangeStatus(ids: number[], targetStatus: DeviceStatusCode, reason?: string) {
    return http.post<BatchResult>('/devices/batch/status', {
      ids,
      targetStatus,
      reason: reason ?? null
    })
  },

  /** 软删除设备（：记录保留，资产编号不可复用） */
  remove(id: number) {
    return http.delete<void>(`/devices/${id}`)
  },

  // ------------------------------------------------------------------
  // 临时锁
  // ------------------------------------------------------------------

  /**
   * 临时锁定设备（申请页选定设备时调用）
   *
   * 返回 `lockToken` 与 `expiresAt`：令牌在提交申请时必须原样回传，
   * 释放锁时也必须携带（防止旧页面释放后来属于其他用户的新锁）。
   */
  lock(id: number) {
    return http.post<DeviceLockInfo>(`/devices/${id}/lock`)
  },

  /** 释放本人持有的临时锁（切换设备 / 取消填表 / 离开页面时调用） */
  unlock(id: number, lockToken: string) {
    return http.post<void>(`/devices/${id}/unlock`, { lockToken })
  },

  /** 管理员强制解除临时锁（；高风险操作，后端同步落审计日志） */
  forceUnlock(id: number) {
    return http.post<void>(`/devices/${id}/force-unlock`)
  }
}

/**
 * 设备批量导入接口（管理端， 设备台账管理）
 *
 * 流程：下载模板 → 上传校验（非阻断，返回逐行结果）→ 确认导入（只导入通过行）
 * → 失败行下载明细后修正重导。
 *
 * 权限：super_admin / admin（与单条新增一致）；后端逐行复用单条新增校验规则。
 */
export const deviceImportApi = {
  /** 下载导入模板（.xlsx，表头 + 示例行） */
  downloadTemplate() {
    return downloadFile('/devices/import/template', '设备导入模板.xlsx', 'GET')
  },

  /** 上传校验：解析全部行做逐行校验，返回预览（不写库） */
  preview(file: File) {
    const form = new FormData()
    form.append('file', file)
    return http.post<DeviceImportPreview>('/devices/import/preview', form)
  },

  /** 确认导入：仅导入校验通过的行，分批事务 */
  execute(payload: DeviceImportExecutePayload) {
    return http.post<DeviceImportResult>('/devices/import/execute', payload)
  },

  /** 下载失败明细（附「失败原因」列，供修正后重导） */
  downloadFailureReport(payload: DeviceImportFailureReportPayload) {
    return downloadFile('/devices/import/failure-report', '设备导入失败明细.xlsx', 'POST', payload)
  }
}

/**
 * 设备故障接口（，）
 *
 * 权限：上报仅要求已登录（借用人只能报自己使用中的设备，服务层校验）；
 * 查询与处理（维修完成 / 报废）仅 super_admin / admin。
 */
export const deviceFaultApi = {
  /** 上报故障（工单内上报携带 orderId；台账直接登记传 null） */
  report(data: DeviceFaultPayload) {
    return http.post<number>('/device-faults', data)
  },

  /**
   * 故障可选设备（已登录即可）
   *
   * 普通用户只拿到「本人使用中工单对应设备」（orderId 非空）；
   * admin / super_admin 额外拿到「可用设备」（orderId 为空，对应台账直接登记）。
   * 返回项含后端拼好的 `label`，可直接用于下拉展示。
   */
  selectableDevices() {
    return http.get<DeviceFaultSelectable[]>('/device-faults/selectable-devices')
  },

  /** 故障记录分页（仅 super_admin / admin） */
  page(query: DeviceFaultQuery = {}) {
    const params: Record<string, unknown> = {}
    if (query.page != null) params.page = query.page
    if (query.size != null) params.size = query.size
    if (query.status) params.status = query.status
    if (query.deviceKeyword) params.deviceKeyword = query.deviceKeyword
    return http.get<PageResult<DeviceFaultItem>>('/device-faults', params)
  },

  /** 某设备的故障历史（设备台账展开查看） */
  listByDevice(deviceId: number) {
    return http.get<DeviceFaultItem[]>(`/device-faults/device/${deviceId}`)
  },

  /** 登记维修完成（设备 维修中 → 可用） */
  repair(id: number, data: DeviceFaultHandlePayload) {
    return http.put<void>(`/device-faults/${id}/repair`, data)
  },

  /** 故障标记报废（设备 → 已报废；使用中禁止） */
  scrap(id: number, data: DeviceFaultHandlePayload) {
    return http.put<void>(`/device-faults/${id}/scrap`, data)
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
 * 这里直接用 fetch 携带 Cookie 与 CSRF 头，并复用同一 baseURL 前缀。
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

export default deviceApi
