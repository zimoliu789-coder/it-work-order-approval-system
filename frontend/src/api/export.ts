import { http } from '@/api/request'
import type { PageResult } from '@/types/api'
import type { ExportRequest, ExportResult, ExportTaskItem, ExportTypeCode } from '@/types/export'
import { apiBase, downloadBinary } from '@/utils/download'

/**
 * Excel 导出接口
 *
 * 同步与异步共用同一个入口：后端按数据量决定当场返回文件还是转后台生成，
 * 前端只需读 `mode` 分支（SYNC 直接下载 / ASYNC 提示等待消息通知）。
 */
export const exportApi = {
  /** 发起导出 */
  create(payload: ExportRequest) {
    return http.post<ExportResult>('/exports', payload)
  },

  /** 我的导出记录（管理员为全部人的记录） */
  tasks(params: { type?: ExportTypeCode; limit?: number } = {}) {
    const query: Record<string, unknown> = {}
    if (params.type) query.type = params.type
    if (params.limit != null) query.limit = params.limit
    return http.get<ExportTaskItem[]>('/exports', query)
  },

  /** 导出记录分页（导出记录页使用） */
  page(params: { type?: ExportTypeCode; page?: number; size?: number } = {}) {
    const query: Record<string, unknown> = {}
    if (params.type) query.type = params.type
    if (params.page != null) query.page = params.page
    if (params.size != null) query.size = params.size
    return http.get<PageResult<ExportTaskItem>>('/exports/page', query)
  },

  /** 导出记录详情 */
  detail(id: number) {
    return http.get<ExportTaskItem>(`/exports/${id}`)
  },

  /** 删除导出记录（仅本人或管理员；同时删除磁盘文件） */
  remove(id: number) {
    return http.delete<void>(`/exports/${id}`)
  },

  /**
   * 下载导出文件
   *
   * 与附件下载共用 {@link downloadBinary}：响应是二进制流而非统一 JSON 信封，
   * 不能走 axios 实例的响应拦截器。
   */
  download(id: number, fallbackName = '导出文件.xlsx'): Promise<void> {
    return downloadBinary(`${apiBase()}/exports/${id}/download`, fallbackName)
  }
}

export default exportApi
