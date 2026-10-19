import { http } from '@/api/request'
import type { BatchResult, PageResult } from '@/types/api'
import type {
  DeviceCategory,
  DeviceCategoryOption,
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
  DeviceModelItem,
  DeviceModelOption,
  DeviceModelPayload,
  DeviceModelStatusCode,
  DeviceModelSyncResult,
  DevicePayload,
  DeviceQuery,
  DeviceStatusCode,
  FaultDeviceLookup
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

  /**
   * 分类下拉选项（一级 + 二级扁平列表）。
   *
   * <p>**普通员工可用**（后端只要求登录）：借用单放开表单自定义后，
   * 「设备分类 / 二级分类」是提交页的系统字段，而分类树接口挂
   * `device:category:manage`（只有资产管理员有）。改造前提交页一直在调树接口，
   * 403 被 catch 吞掉后下拉是空的且没有任何提示 —— 「没法提交但看不到原因」。
   */
  options() {
    return http.get<DeviceCategoryOption[]>('/device-categories/options')
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

  /**
   * 批量设置负责人（V57 / 第 10 项）
   *
   * `ownerId` 传 null = **清空负责人**（设备转为公共设备，只能扫码报修）。
   * 只改 owner_id 一列，与分类批量操作同理，不会动品牌 / 型号等字段。
   */
  batchAssignOwner(ids: number[], ownerId: number | null) {
    return http.post<BatchResult>('/devices/batch/owner', { ids, ownerId })
  },

  /** 软删除设备（：记录保留，资产编号不可复用） */
  remove(id: number) {
    return http.delete<void>(`/devices/${id}`)
  }

  // ------------------------------------------------------------------
  // 临时锁：V56（第 9 项）起整体移除
  //
  // 申请人不再挑选具体资产（只选分类 + 型号），设备绑定延后到 IT 交付扫码，
  // 因此 lock / unlock / forceUnlock 三个方法与后端的对应端点一起删除。
  // 不要试图「先锁设备再提交」—— 后端字段已不存在，调用会 404。
  // ------------------------------------------------------------------
}

/**
 * 设备型号字典接口（V56 / R3）
 *
 * 权限：下拉 `options` 仅要求已登录（借用申请页要用）；其余需 `device:ledger:manage`。
 * 没有删除接口 —— 型号被台账与历史工单引用，删除会留下悬空 model_id；
 * 要「清理」录错的型号，正确做法是停用后新建一条。
 */
export const deviceModelApi = {
  /** 管理列表（型号名 / 品牌关键字 + 状态筛选） */
  page(query: { page?: number; size?: number; keyword?: string; status?: DeviceModelStatusCode | null } = {}) {
    const params: Record<string, unknown> = {}
    if (query.page != null) params.page = query.page
    if (query.size != null) params.size = query.size
    if (query.keyword) params.keyword = query.keyword
    if (query.status) params.status = query.status
    return http.get<PageResult<DeviceModelItem>>('/device-models', params)
  },

  /** 下拉选项（仅启用型号；借用表单与台账共用） */
  options(keyword?: string) {
    const params: Record<string, unknown> = {}
    if (keyword) params.keyword = keyword
    return http.get<DeviceModelOption[]>('/device-models/options', params)
  },

  create(data: DeviceModelPayload) {
    return http.post<number>('/device-models', data)
  },

  update(id: number, data: DeviceModelPayload) {
    return http.put<void>(`/device-models/${id}`, data)
  },

  /** 启停（停用后新增下拉不再出现，既有设备与历史工单关联不变） */
  changeStatus(id: number, status: DeviceModelStatusCode) {
    return http.put<void>(`/device-models/${id}/status`, { status })
  },

  /**
   * 从台账存量文本补齐字典（把 model_id 为空的行去重纳入并回填）
   *
   * 这是「型号待整理」的收敛入口，可反复执行；返回本次新建 / 回填的数量。
   */
  syncFromLedger() {
    return http.post<DeviceModelSyncResult>('/device-models/sync-from-ledger')
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

  /**
   * 「我的设备」（V57 / 第 10 项）—— 我负责的 ∪ 我正在用的。
   *
   * <p>两类缺一不可：资产负责人通常是 IT，普通员工手上那台的 `owner_id` 不是他，
   * 只查负责人会得到一个空列表，把报修入口变成摆设。
   * 返回项已带 `reportable / reason / orderId`，前端不做二次判定。
   */
  ownedDevices() {
    return http.get<FaultDeviceLookup[]>('/device-faults/owned-devices')
  },

  /**
   * 按资产编号查一台设备（V57 / 第 10 项「公共设备」模式，扫码枪直接扫进输入框）。
   *
   * 查不到返回 `found = false` 而不是抛错 —— 输错编号是这个入口的日常动作，
   * 每次都弹一个红色报错会让人以为系统坏了。
   */
  lookup(assetNo: string) {
    return http.get<FaultDeviceLookup>('/device-faults/lookup', { assetNo })
  },

  /** 故障记录分页（仅 super_admin / admin） */
  page(query: DeviceFaultQuery = {}) {
    const params: Record<string, unknown> = {}
    if (query.page != null) params.page = query.page
    if (query.size != null) params.size = query.size
    if (query.status) params.status = query.status
    if (query.deviceKeyword) params.deviceKeyword = query.deviceKeyword
    // 只在为 true 时携带：false 与「不传」对后端是同一件事，
    // 但少发一个参数能让「首屏请求与改造前逐字节一致」这条零回归约定继续成立
    if (query.onlyMine) params.onlyMine = true
    return http.get<PageResult<DeviceFaultItem>>('/device-faults', params)
  },

  /** 某设备的故障历史（设备台账展开查看） */
  listByDevice(deviceId: number) {
    return http.get<DeviceFaultItem[]>(`/device-faults/device/${deviceId}`)
  },

  /**
   * 接单（V50）：待处理 → 处理中，并把接单人记为处理人 / 维修人。
   *
   * 只要求登录，不要求 `device:fault:manage` —— 接单的是一线维修人员，
   * 而那个权限码按现有角色配置只发给管理员。真正的授权在后端：
   * 「管理员，或落在『处理流程』通知范围内的人」。
   */
  accept(id: number) {
    return http.put<void>(`/device-faults/${id}/accept`, null)
  },

  /** 登记维修完成（设备 维修中 → 可用） */
  repair(id: number, data: DeviceFaultHandlePayload) {
    return http.put<void>(`/device-faults/${id}/repair`, data)
  },

  /**
   * 判定「无法修复」（V50）：维修结论，**设备保持维修中**，等资产管理员决定报废或调拨。
   *
   * 与 `scrap` 的区别是本质的：这一条不改变设备状态。
   * 若这里顺手把设备放回「可用」，等于把一台修不好的设备重新发出去。
   */
  markUnrepairable(id: number, data: DeviceFaultHandlePayload) {
    return http.put<void>(`/device-faults/${id}/unrepairable`, data)
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
