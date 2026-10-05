/**
 * 复制文本到剪贴板（2026-09-20 ）
 *
 * <h2>为什么不直接用 navigator.clipboard 了事</h2>
 * <p>{@code navigator.clipboard} 只在<b>安全上下文</b>（https / localhost）下存在，
 * 且需要用户手势触发。本项目的真实部署形态是「应用容器只监听 80，TLS 全交外层反代」，
 * 内网里很可能是 {@code http://} 访问 —— 那种情况下 {@code navigator.clipboard}
 * 直接是 {@code undefined}，只写这一条路会导致「临时密码复制不了」。
 *
 * <p>因此保留 {@code document.execCommand('copy')} 这条老路作为回退：它虽然已被标准废弃，
 * 但在内网 http 环境下仍是唯一可用的方案。
 *
 * @param text 要复制的文本
 * @returns 是否复制成功；调用方据此决定是否提示「请手动选中复制」
 */
export async function copyText(text: string): Promise<boolean> {
  // 首选：异步剪贴板 API（仅在安全上下文下可用）
  if (typeof navigator !== 'undefined' && navigator.clipboard && window.isSecureContext) {
    try {
      await navigator.clipboard.writeText(text)
      return true
    } catch {
      // 落到下面的回退分支（例如用户拒绝了剪贴板权限）
    }
  }

  // 回退：临时 textarea + execCommand
  try {
    const textarea = document.createElement('textarea')
    textarea.value = text
    // 放到视口外，避免复制瞬间页面跳动
    textarea.style.position = 'fixed'
    textarea.style.top = '-1000px'
    textarea.style.left = '-1000px'
    textarea.setAttribute('readonly', '')
    document.body.appendChild(textarea)
    textarea.select()
    textarea.setSelectionRange(0, textarea.value.length)
    const ok = document.execCommand('copy')
    document.body.removeChild(textarea)
    return ok
  } catch {
    return false
  }
}
