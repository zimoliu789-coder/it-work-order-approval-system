import { http } from '@/api/request'
import type { PageResult } from '@/types/api'
import type { MarkAllReadResult, MessageItem, MessageQuery } from '@/types/message'

/**
 * 站内消息接口（ 右上角消息铃铛 /  站内消息通知系统）
 *
 * 后端所有端点都只操作「当前登录者自己的消息」（用户维度取自登录态，不通过参数传入），
 * 因此前端无需、也无法指定 userId。
 */
export const messageApi = {
  /** 我的消息分页（unreadOnly=true 只看未读；可再按类型 / 关键词筛选） */
  mine(query: MessageQuery = {}) {
    const params: Record<string, unknown> = {}
    if (query.page != null) params.page = query.page
    if (query.size != null) params.size = query.size
    if (query.unreadOnly != null) params.unreadOnly = query.unreadOnly
    if (query.messageType) params.messageType = query.messageType
    if (query.keyword) params.keyword = query.keyword
    return http.get<PageResult<MessageItem>>('/messages', params)
  },

  /** 未读消息数（铃铛红点） */
  async unreadCount(): Promise<number> {
    const result = await http.get<{ count: number }>('/messages/unread-count')
    return result?.count ?? 0
  },

  /** 标记单条已读 */
  markRead(id: number) {
    return http.put<void>(`/messages/${id}/read`)
  },

  /** 全部标记已读 */
  markAllRead() {
    return http.put<MarkAllReadResult>('/messages/read-all')
  },

  /** 删除我的某条消息（服务端校验归属，非本人消息返回 MESSAGE_NOT_FOUND） */
  remove(id: number) {
    return http.delete<void>(`/messages/${id}`)
  },

  /**
   * 批量删除我的消息（需求方三波·第二波·）
   *
   * 服务端条件恒带 user_id：传入他人消息 ID 不会误删，只会不计入返回条数。
   */
  batchDelete(ids: number[]) {
    return http.post<{ affected: number }>('/messages/batch-delete', { ids })
  }
}

export default messageApi
