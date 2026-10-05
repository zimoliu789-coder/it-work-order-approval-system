import { http } from '@/api/request'
import type { PageResult } from '@/types/api'
import type {
  InventoryCheckPayload,
  InventoryItemRow,
  InventoryReport,
  InventoryScopeOptions,
  InventoryTaskCreatePayload,
  InventoryTaskItem
} from '@/types/inventory'

/**
 * 设备盘点接口（P2）
 *
 * 权限：`inventory:view` 看任务与报告；`inventory:manage` 创建 / 核对 / 完成 / 取消。
 * 与设备台账同级别（admin 及以上；后端 @PreAuthorize 独立校验，前端隐藏只是体验）。
 */
export const inventoryApi = {
  /** 任务列表 */
  page(query: { page?: number; size?: number; status?: string; keyword?: string } = {}) {
    const params: Record<string, unknown> = {}
    if (query.page != null) params.page = query.page
    if (query.size != null) params.size = query.size
    if (query.status) params.status = query.status
    if (query.keyword) params.keyword = query.keyword
    return http.get<PageResult<InventoryTaskItem>>('/inventories', params)
  },

  /** 任务详情 */
  detail(id: number) {
    return http.get<InventoryTaskItem>(`/inventories/${id}`)
  },

  /**
   * 任务明细（分页）。
   *
   * @param checkResult 空为不限；传 `UNCHECKED` 只看「尚未核对」（核对页的待盘清单）
   */
  items(
    id: number,
    query: { page?: number; size?: number; checkResult?: string } = {}
  ) {
    const params: Record<string, unknown> = {}
    if (query.page != null) params.page = query.page
    if (query.size != null) params.size = query.size
    if (query.checkResult) params.checkResult = query.checkResult
    return http.get<PageResult<InventoryItemRow>>(`/inventories/${id}/items`, params)
  },

  /** 盘点报告（含缺失 / 位置不符清单） */
  report(id: number) {
    return http.get<InventoryReport>(`/inventories/${id}/report`)
  },

  /** 范围可选值（分类 + 存放位置） */
  scopeOptions() {
    return http.get<InventoryScopeOptions>('/inventories/scope-options')
  },

  /** 创建任务（后端会按范围一次性快照明细） */
  create(data: InventoryTaskCreatePayload) {
    return http.post<number>('/inventories', data)
  },

  /** 扫码核对一台（按资产编号在本任务内定位） */
  check(id: number, data: InventoryCheckPayload) {
    return http.post<InventoryItemRow>(`/inventories/${id}/check`, data)
  },

  /** 完成盘点（remark 为可选的盘点结论） */
  complete(id: number, remark?: string | null) {
    return http.post<void>(`/inventories/${id}/complete`, { remark: remark ?? null })
  },

  /** 取消盘点 */
  cancel(id: number, remark?: string | null) {
    return http.post<void>(`/inventories/${id}/cancel`, { remark: remark ?? null })
  }
}

export default inventoryApi
