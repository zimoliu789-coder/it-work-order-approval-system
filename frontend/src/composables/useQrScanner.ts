import { onBeforeUnmount, ref } from 'vue'
import type { Html5Qrcode } from 'html5-qrcode'

/**
 * 摄像头扫码组合式函数（P1 扫码借还）
 *
 * <h2>为什么要单独封装</h2>
 * <p>浏览器调摄像头有三个必须先讲清楚的前提，散在页面里极易漏掉其中之一：
 * <ol>
 *   <li><b>安全上下文</b>：只有 https 或 localhost 才暴露 {@code getUserMedia}。
 *       生产若以 {@code http://IP:8080} 访问，摄像头会被浏览器直接拒绝 ——
 *       这不是代码 bug，是浏览器安全策略。所以「手动输入编码」不是备用功能，是必需的通路。</li>
 *   <li><b>库要按需加载</b>：扫码库（含解码器）体积不小，而它只在扫码页用到，
 *       因此走动态 {@code import}，不进首屏包。</li>
 *   <li><b>识别到一次就停</b>：摄像头每帧都会回调，不停会连续触发查询请求。</li>
 * </ol>
 */

export type QrScannerState = 'IDLE' | 'STARTING' | 'RUNNING' | 'FAILED'

/**
 * 当前环境能否调用摄像头。
 *
 * <p>注意区分「不安全」与「不支持」：前者靠 https 就能解决，后者只能换浏览器 ——
 * 提示语必须区分开，否则用户会照着错误的建议去折腾。
 */
export function isCameraSupported(): boolean {
  if (typeof window === 'undefined' || typeof navigator === 'undefined') {
    return false
  }
  if (window.isSecureContext !== true) {
    return false
  }
  return typeof navigator.mediaDevices?.getUserMedia === 'function'
}

/** 环境不满足时的原因说明（可直接展示给用户） */
export function cameraUnavailableReason(): string {
  if (typeof window === 'undefined') {
    return '当前环境无法调用摄像头，请手动输入资产编号。'
  }
  if (window.isSecureContext !== true) {
    return '当前页面不是安全上下文（需通过 https 或 localhost 访问），浏览器不允许调用摄像头。请改用手动输入资产编号。'
  }
  return '当前浏览器不支持调用摄像头，请改用手动输入资产编号。'
}

export function useQrScanner(elementId: string) {
  const state = ref<QrScannerState>('IDLE')
  const errorMessage = ref('')
  let scanner: Html5Qrcode | null = null

  /** 停止取景并释放摄像头。未启动时调用是安全的（内部吞掉 stop 的 reject）。 */
  async function stop(): Promise<void> {
    const instance = scanner
    scanner = null
    if (!instance) {
      if (state.value !== 'FAILED') {
        state.value = 'IDLE'
      }
      return
    }
    try {
      await instance.stop()
      instance.clear()
    } catch {
      // 摄像头尚未真正启动 / 已被浏览器回收时 stop 会 reject，属正常路径
    }
    if (state.value !== 'FAILED') {
      state.value = 'IDLE'
    }
  }

  /**
   * 启动取景。识别到内容后**自动停止**并把文本交给 {@code onDecoded}。
   *
   * @param onDecoded 识别回调（只会被调用一次，随后自动停止）
   */
  async function start(onDecoded: (text: string) => void): Promise<void> {
    if (state.value === 'STARTING' || state.value === 'RUNNING') {
      return
    }
    errorMessage.value = ''

    if (!isCameraSupported()) {
      state.value = 'FAILED'
      errorMessage.value = cameraUnavailableReason()
      return
    }

    state.value = 'STARTING'
    try {
      const { Html5Qrcode: Scanner } = await import('html5-qrcode')
      const instance = new Scanner(elementId)
      scanner = instance
      await instance.start(
        { facingMode: 'environment' },
        { fps: 10, qrbox: { width: 240, height: 240 } },
        (decodedText: string) => {
          // 先停再回调：不停会按帧连续触发，把手动输入/结果区冲得无法阅读
          void stop()
          if (decodedText.trim()) {
            onDecoded(decodedText.trim())
          }
        },
        () => {
          // 每帧「未识别到码」都会走这里，属正常情况，不能当错误弹提示
        }
      )
      state.value = 'RUNNING'
    } catch (error) {
      scanner = null
      state.value = 'FAILED'
      errorMessage.value = describeStartError(error)
    }
  }

  onBeforeUnmount(() => {
    void stop()
  })

  return { state, errorMessage, start, stop }
}

/** 把摄像头启动失败翻译成用户能照做的提示 */
function describeStartError(error: unknown): string {
  const name = (error as { name?: string } | null)?.name ?? ''
  if (name === 'NotAllowedError' || name === 'SecurityError') {
    return '摄像头权限被拒绝。请在浏览器地址栏的权限设置里允许本站使用摄像头，或改用手动输入资产编号。'
  }
  if (name === 'NotFoundError' || name === 'OverconstrainedError') {
    return '没有找到可用的摄像头。请改用手动输入资产编号。'
  }
  if (name === 'NotReadableError') {
    return '摄像头被其它程序占用。请关闭正在使用摄像头的应用后重试，或改用手动输入。'
  }
  const detail = error instanceof Error ? error.message : String(error)
  return `摄像头启动失败：${detail}。请改用手动输入资产编号。`
}
