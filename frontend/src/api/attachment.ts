import request, { http } from '@/api/request'
import type { AttachmentBizTypeCode, AttachmentItem } from '@/types/attachment'
import { apiBase, downloadBinary } from '@/utils/download'

/**
 * 附件通用接口
 *
 * 上传/下载/删除/列表四类操作对全部业务对象复用，业务语义由 `bizType` 区分，
 * 权限（可见性）由后端按业务主体判定，前端不传 userId、也无法伪造。
 */

/** 上传进度回调（0-100） */
export type UploadProgressHandler = (percent: number) => void

export const attachmentApi = {
  /** 按业务对象列出附件（后端会先校验当前用户对该业务的可见性） */
  list(bizType: AttachmentBizTypeCode | string, bizId: number) {
    return http.get<AttachmentItem[]>('/attachments', { bizType, bizId })
  },

  /**
   * 上传附件（multipart）
   *
   * 走 axios 实例（默认导出）而非 `http` 包装：需要 `onUploadProgress` 配置项，
   * 且实例**未**预设 Content-Type，axios 会为 FormData 自动写入带 boundary 的
   * multipart/form-data（若预设 json 会导致后端解析失败、稳定 500）。
   */
  upload(
    bizType: AttachmentBizTypeCode | string,
    bizId: number,
    file: File,
    onProgress?: UploadProgressHandler
  ): Promise<AttachmentItem> {
    const form = new FormData()
    form.append('bizType', bizType)
    form.append('bizId', String(bizId))
    form.append('file', file)
    return request.post('/attachments', form, {
      onUploadProgress: (event) => {
        if (onProgress && event.total) {
          onProgress(Math.min(100, Math.round((event.loaded / event.total) * 100)))
        }
      }
    }) as unknown as Promise<AttachmentItem>
  },

  /** 删除附件（软删；仅上传者本人或 admin 以上） */
  remove(id: number) {
    return http.delete<void>(`/attachments/${id}`)
  },

  /**
   * 图片内联地址：用于 `<img>` 直接渲染缩略图 / 大图预览。
   *
   * 后端下载端点带登录鉴权，浏览器 `<img>` 同源请求会自动携带 Cookie；
   * `inline=true` 让后端以 inline 语义回传（而非触发下载）。
   */
  inlineUrl(id: number): string {
    return `${apiBase()}/attachments/${id}/download?inline=true`
  },

  /**
   * 下载附件到本地磁盘。
   *
   * 响应是二进制流而非统一 JSON 信封，故走 {@link downloadBinary}（原生 fetch +
   * Cookie + CSRF 头），而不是 axios 实例的响应拦截器。
   */
  download(id: number, fallbackName = '附件'): Promise<void> {
    return downloadBinary(`${apiBase()}/attachments/${id}/download`, fallbackName)
  }
}

/**
 * 批量上传：把「先本地挑选、主操作成功后再落库」的附件逐张上传。
 *
 * 用于「随单提交」类场景（申请 / 驳回 / 归还 / 故障）：附件必须绑定到业务记录 id，
 * 而 id 只能等主操作成功后才拿到。单个失败不阻断其余文件，返回成功数量供调用方提示。
 */
export async function uploadAttachments(
  bizType: AttachmentBizTypeCode | string,
  bizId: number,
  files: readonly File[]
): Promise<number> {
  let done = 0
  for (const file of files) {
    try {
      await attachmentApi.upload(bizType, bizId, file)
      done += 1
    } catch {
      // 单个失败（类型/大小/权限）由请求层统一提示，不阻断其余文件
    }
  }
  return done
}

export default attachmentApi
