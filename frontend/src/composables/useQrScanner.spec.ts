import { afterEach, describe, expect, it } from 'vitest'
import { defineComponent, h } from 'vue'
import { mount } from '@vue/test-utils'
import { cameraUnavailableReason, isCameraSupported, useQrScanner } from '@/composables/useQrScanner'

/**
 * 摄像头扫码组合式函数（P1 扫码借还）
 *
 * <h2>为什么重点测「不可用」的分支</h2>
 * <p>摄像头可用是「顺境」——真机上点一下就知道行不行。真正会出问题的是不可用的时候：
 * 生产若以 {@code http://IP:8080} 访问，浏览器会拒绝 {@code getUserMedia}，此时必须
 * <b>明确告诉用户改用手动输入</b>，而不是让扫码页卡在一个黑框上。
 * 而且「不安全上下文」（换 https 可以解决）与「浏览器不支持」（只能换浏览器）必须区分开，
 * 否则用户会照着错误的建议去折腾。
 */

const READER_ID = 'ts-scan-reader'

function setSecureContext(value: boolean): void {
  Object.defineProperty(window, 'isSecureContext', {
    value,
    configurable: true,
    writable: true
  })
}

function setMediaDevices(available: boolean): void {
  Object.defineProperty(navigator, 'mediaDevices', {
    value: available ? { getUserMedia: () => Promise.resolve() } : undefined,
    configurable: true,
    writable: true
  })
}

function mountScanner() {
  let api!: ReturnType<typeof useQrScanner>
  mount(
    defineComponent({
      setup() {
        api = useQrScanner(READER_ID)
        return () => h('div', { id: READER_ID })
      }
    })
  )
  return api
}

afterEach(() => {
  // 这两个属性被本例改了，必须还原 —— 否则会影响后续其它用例（测试间的隐式耦合）
  setSecureContext(true)
  setMediaDevices(false)
})

describe('isCameraSupported', () => {
  it('非安全上下文 ⇒ 不可用（浏览器安全策略，与代码无关）', () => {
    setSecureContext(false)
    setMediaDevices(true)
    expect(isCameraSupported()).toBe(false)
  })

  it('安全上下文但浏览器没有 getUserMedia ⇒ 不可用', () => {
    setSecureContext(true)
    setMediaDevices(false)
    expect(isCameraSupported()).toBe(false)
  })

  it('安全上下文且有 getUserMedia ⇒ 可用', () => {
    setSecureContext(true)
    setMediaDevices(true)
    expect(isCameraSupported()).toBe(true)
  })
})

describe('cameraUnavailableReason', () => {
  it('不安全上下文：指向 https/localhost，并明确给出手动输入这条出路', () => {
    setSecureContext(false)
    const reason = cameraUnavailableReason()
    expect(reason).toContain('安全上下文')
    expect(reason).toContain('手动输入')
  })

  it('安全上下文下的不支持：不能再提「安全上下文」，否则把人引去查 https', () => {
    setSecureContext(true)
    const reason = cameraUnavailableReason()
    expect(reason).toContain('不支持')
    expect(reason).not.toContain('安全上下文')
    expect(reason).toContain('手动输入')
  })
})

describe('useQrScanner 的降级路径', () => {
  it('环境不支持时 start 立即进入 FAILED 并给出提示（不会去加载扫码库）', async () => {
    setSecureContext(false)

    const scanner = mountScanner()
    expect(scanner.state.value).toBe('IDLE')

    await scanner.start(() => {})

    expect(scanner.state.value).toBe('FAILED')
    expect(scanner.errorMessage.value).toContain('安全上下文')
  })

  it('未启动时调用 stop 是安全的（不会抛错，状态仍是 IDLE）', async () => {
    setSecureContext(true)
    setMediaDevices(true)

    const scanner = mountScanner()
    await scanner.stop()

    expect(scanner.state.value).toBe('IDLE')
  })
})
