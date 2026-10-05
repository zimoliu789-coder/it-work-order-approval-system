import { http } from '@/api/request'
import type { PageResult } from '@/types/api'
import type { UsageQuery, UsageRecordItem } from '@/types/usage'

/**
 * 使用记录接口（需求方三波·第一波·）
 *
 * 权限：usage:view（默认授予 admin / super_admin）；
 * 后端按角色的 data_scope 自动收窄范围（SELF / GROUP / ALL），前端不参与范围判定。
 */
export const usageApi = {
  /** 使用记录分页查询 */
  records(query: UsageQuery = {}) {
    const params: Record<string, unknown> = {
      page: query.page ?? 1,
      size: query.size ?? 20
    }
    if (query.scope) params.scope = query.scope
    if (query.targetId != null) params.targetId = query.targetId
    if (query.keyword) params.keyword = query.keyword
    if (query.status) params.status = query.status
    if (query.useType) params.useType = query.useType
    if (query.startTime) params.startTime = query.startTime
    if (query.endTime) params.endTime = query.endTime
    return http.get<PageResult<UsageRecordItem>>('/usage/records', params)
  }
}

export default usageApi
