import { http } from '@/api/request'
import type { PageResult } from '@/types/api'
import type {
  SecurityEventItem,
  SecurityLogOptions,
  SecurityLogQuery,
  SecurityOverview
} from '@/types/securityLog'

/**
 * 安全日志接口—— 对应后端 `module/security/controller/SecurityLogController`。
 *
 * <h2>权限</h2>
 * 全部端点挂 `security:view`，**只归超管**：人工解封是基础设施级动作
 * （等于把一个来源重新放进门），与「基础设施级告警只发超管」同一取向。
 *
 * <h2>为什么没有「删除事件」</h2>
 * 安全事件是取证数据 —— 能删就等于能销毁证据。超期行由每日清理任务按保留天数统一删除。
 */
export const securityLogApi = {
  /** 分页查询安全事件 */
  page(query: SecurityLogQuery) {
    const params: Record<string, unknown> = {
      page: query.page ?? 1,
      size: query.size ?? 20
    }
    if (query.eventType) params.eventType = query.eventType
    if (query.ip) params.ip = query.ip.trim()
    if (query.username) params.username = query.username.trim()
    if (query.startTime) params.startTime = query.startTime
    if (query.endTime) params.endTime = query.endTime
    return http.get<PageResult<SecurityEventItem>>('/security-logs', params)
  },

  /** 概览：当前封禁列表 + 失败最多的 IP + 各类型计数（同屏内容合成一个请求） */
  overview(hours = 24) {
    return http.get<SecurityOverview>('/security-logs/overview', { hours })
  },

  /** 筛选下拉选项（由后端枚举生成） */
  options() {
    return http.get<SecurityLogOptions>('/security-logs/options')
  },

  /**
   * 人工解封。
   *
   * <p>用 POST 而不是 DELETE：这不是「删除一条封禁记录」，而是「改变它的状态」——
   * 记录会保留，`unblocked_at` / `unblocked_by` 都要留痕。
   * 用 DELETE 会让审计上看起来像「有人删了证据」。
   */
  unblock(id: number) {
    return http.post<void>(`/security-logs/blocks/${id}/unblock`)
  }
}

export default securityLogApi
