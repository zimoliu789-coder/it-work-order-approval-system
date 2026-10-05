import { ElMessage } from 'element-plus'

/**
 * 二进制文件下载（附件  / 导出文件  共用）
 *
 * <p>为什么必须<b>绕开统一请求实例</b>：后端的下载端点返回的是文件流，不是
 * `{ code, message, data }` 信封；而 axios 实例的响应拦截器会把「不是信封」的响应
 * 判为格式异常直接抛错，下载必然失败。因此这里用原生 fetch：
 * - `credentials: 'include'` 让 HttpOnly Cookie 随请求发出（鉴权靠它，不靠前端拿 token）；
 * - 显式带 CSRF 头 `X-Requested-With`（ 第二层防护，即便下载是 GET 也保持一致）；
 * - 失败时尝试解析后端的信封错误体，把「文件已过期」「尚未生成」这类明确原因透出给用户，
 *   而不是一律显示「下载失败」。
 *
 * 抽成独立工具的原因：附件与导出两处的下载语义完全相同（鉴权、错误透出、按
 * Content-Disposition 命名），各写一份必然漂移 —— 实际已发生过「只有一处带了 CSRF 头」的问题。
 */

/** 从 Content-Disposition 解析文件名（优先 RFC 5987 的 filename*） */
export function resolveFilenameFromDisposition(disposition: string | null, fallback: string): string {
  if (!disposition) {
    return fallback
  }
  const utf8 = /filename\*=UTF-8''([^;]+)/i.exec(disposition)
  if (utf8?.[1]) {
    try {
      return decodeURIComponent(utf8[1])
    } catch {
      // 编码异常时回落下面的普通 filename 分支
    }
  }
  const plain = /filename="?([^";]+)"?/i.exec(disposition)
  return plain?.[1] ?? fallback
}

/**
 * 下载一个需要登录鉴权的二进制文件
 *
 * @param url 完整或相对（以 `/api` 开头）的下载地址
 * @param fallbackName 无法从响应头取到文件名时使用的兜底名
 */
export async function downloadBinary(url: string, fallbackName: string): Promise<void> {
  const response = await fetch(url, {
    method: 'GET',
    credentials: 'include',
    headers: { 'X-Requested-With': 'XMLHttpRequest' }
  })
  if (!response.ok) {
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
  const filename = resolveFilenameFromDisposition(response.headers.get('Content-Disposition'), fallbackName)
  const link = document.createElement('a')
  link.href = URL.createObjectURL(blob)
  link.download = filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(link.href)
}

/** 接口前缀，与 axios 实例保持一致（读同一份环境变量，避免两处配置分叉） */
export function apiBase(): string {
  return import.meta.env.VITE_API_BASE_URL || '/api'
}
