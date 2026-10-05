import { http } from '@/api/request'
import type { DashboardQuery, DashboardSummary } from '@/types/dashboard'

/**
 * 统计仪表盘接口（ · M5）
 *
 * 只读端点，需权限码 `dashboard:view`（admin 默认持有，超管恒定放行）。
 * 前端区块隐藏只是体验，后端 `@PreAuthorize` 会二次校验 —— 直调接口必须被拦。
 */
export const dashboardApi = {
  /**
   * 区间聚合摘要。
   *
   * 只把「有值」的参数发出去：`from=` 空串会被后端当成非法日期解析失败，
   * 而「不传」才是「用默认值（近 30 天）」的语义（与 `api/report.ts` 的 buildParams 同一考虑）。
   */
  summary(query: DashboardQuery = {}) {
    return http.get<DashboardSummary>('/dashboard/summary', buildParams(query))
  }
}

function buildParams(query: DashboardQuery): Record<string, unknown> {
  const params: Record<string, unknown> = {}
  if (query.from) {
    params.from = query.from
  }
  if (query.to) {
    params.to = query.to
  }
  if (query.granularity) {
    params.granularity = query.granularity
  }
  return params
}

export default dashboardApi
