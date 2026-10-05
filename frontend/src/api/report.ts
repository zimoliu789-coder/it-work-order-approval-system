import { http } from '@/api/request'
import type {
  ApprovalEfficiencyReport,
  DashboardOverview,
  DeviceFaultReport,
  DeviceUsageReport,
  ReportQuery
} from '@/types/report'

/**
 * 统计报表接口
 *
 * 三个端点均仅 super_admin / admin 可访问；前端菜单隐藏只是体验，后端会二次校验。
 */
export const reportApi = {
  /** 设备借用频次（按设备、按分类） */
  deviceUsage(query: ReportQuery = {}) {
    return http.get<DeviceUsageReport>('/reports/device-usage', buildParams(query))
  },

  /** 工单审批时效（平均审批耗时、超时审批工单） */
  approvalEfficiency(query: ReportQuery = {}) {
    return http.get<ApprovalEfficiencyReport>('/reports/approval-efficiency', buildParams(query))
  },

  /** 设备故障统计（故障数量、故障设备分布） */
  deviceFault(query: ReportQuery = {}) {
    return http.get<DeviceFaultReport>('/reports/device-fault', buildParams(query))
  },

  /**
   * 管理概览（P3 管理层数据看板）：借出 / 逾期 / 设备利用率 / 部门借用排行。
   *
   * 未传 month 时后端把区间回落为**当前月**（卡面文案是「本月借出」）——
   * 这与三张明细报表「不选月份=整年」的口径刻意不同，调用方需知晓。
   */
  overview(query: ReportQuery = {}) {
    return http.get<DashboardOverview>('/reports/overview', buildParams(query))
  }
}

/**
 * 时间筛选参数：只把「有值」的字段发出去。
 *
 * 传 `year=undefined` 与「不传 year」在后端是两种语义（前者被 JSON 序列化成 null
 * 会被当作「不限年份」，但 URL 里出现 `year=` 空串则可能被解析成非法数字），
 * 因此这里显式过滤空值。
 */
function buildParams(query: ReportQuery): Record<string, unknown> {
  const params: Record<string, unknown> = {}
  if (query.year != null) {
    params.year = query.year
  }
  if (query.month != null) {
    params.month = query.month
  }
  return params
}

export default reportApi
