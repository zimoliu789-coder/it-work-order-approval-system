import { http } from '@/api/request'
import type { PageResult } from '@/types/api'
import type {
  ExceptionLogDetail,
  ExceptionLogItem,
  ExceptionLogOptions,
  ExceptionLogQuery,
  ExceptionLogStats
} from '@/types/exceptionLog'

/**
 * 异常日志接口—— 对应后端 `module/system/controller/ExceptionLogController`。
 *
 * <h2>权限</h2>
 * 全部端点挂 `exception:view`，**只归超管**（不在 admin 的默认权限集里）：
 * 异常堆栈是给能改代码的人看的，给业务管理员开放只会产生「看不懂但也不敢忽略」的噪音。
 *
 * <h2>为什么没有删除接口</h2>
 * 异常日志是取证数据 —— 能删就等于能销毁证据。超期行由每日清理任务按保留天数统一删除，
 * 那是「策略」而不是「手滑」（与操作日志同一取向）。
 */
export const exceptionLogApi = {
  /** 分页查询（空字符串的筛选条件不发，避免后端收到一堆空参数） */
  page(query: ExceptionLogQuery) {
    const params: Record<string, unknown> = {
      page: query.page ?? 1,
      size: query.size ?? 20
    }
    if (query.category) params.category = query.category
    if (query.severity) params.severity = query.severity
    if (query.alertState) params.alertState = query.alertState
    if (query.keyword) params.keyword = query.keyword
    if (query.startTime) params.startTime = query.startTime
    if (query.endTime) params.endTime = query.endTime
    return http.get<PageResult<ExceptionLogItem>>('/exception-logs', params)
  },

  /** 单条详情（含完整堆栈） */
  detail(id: number) {
    return http.get<ExceptionLogDetail>(`/exception-logs/${id}`)
  },

  /** 筛选下拉选项（分类 / 分级 / 告警状态，由后端枚举生成） */
  options() {
    return http.get<ExceptionLogOptions>('/exception-logs/options')
  },

  /** 顶部概览计数 */
  stats() {
    return http.get<ExceptionLogStats>('/exception-logs/stats')
  }
}

export default exceptionLogApi
