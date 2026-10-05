import { vi } from 'vitest'

/**
 * 组件级测试全局桩（三波补做·第三波·）
 *
 * 生产构建里 `ElMessage` / `ElMessageBox` 由 unplugin-auto-import 在编译期注入，
 * 测试环境刻意不启用该插件（见 vitest.config.ts），因此在此把等价对象挂到 `globalThis`：
 * 组件 `<script setup>` 里对二者的裸标识符引用会按 JS 语义回落到全局作用域。
 *
 * 导出这两个 mock 供用例断言（如「校验失败应弹 warning」），
 * 配合 vitest 的 `clearMocks`，每个用例前自动清空调用记录。
 */

export const elMessage = {
  success: vi.fn(),
  warning: vi.fn(),
  error: vi.fn(),
  info: vi.fn(),
  closeAll: vi.fn()
}

export const elMessageBox = {
  /** 默认「用户点了确认」；用例可 mockResolvedValueOnce / mockRejectedValueOnce 覆盖 */
  confirm: vi.fn(() => Promise.resolve('confirm')),
  alert: vi.fn(() => Promise.resolve('confirm')),
  prompt: vi.fn(() => Promise.resolve({ value: '', action: 'confirm' }))
}

Object.assign(globalThis, { ElMessage: elMessage, ElMessageBox: elMessageBox })
