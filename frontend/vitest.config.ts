import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

/**
 * Vitest 配置（Phase 4 引入；三波补做·第三波·需求 11 起扩展到组件级测试）
 *
 * 背景：Phase 3 的 Critical 缺陷（编辑弹窗二级分类被联动静默清空）正落在**前端表单联动**上，
 * 而当时前端没有任何自动化测试，回归只能靠人工点。Phase 4 起为纯逻辑层建立单测保护；
 * 第三波补做把覆盖推进到**组件层**（挂载真实 SFC、驱动 props/事件/交互），
 * 使「弹窗/校验/联动」这类只在挂载后才暴露的缺陷也能被自动化回归捕获。
 *
 * 设计取舍：
 * - 环境用 happy-dom：组件挂载需要 document / window.matchMedia 等浏览器能力。
 * - 不引入 AutoImport / Components 插件：组件里 `ElMessage` 等全局由 `src/test/setup.ts`
 *   显式挂到 globalThis；`el-*` 子组件在用例内按需 stub，保持单测隔离、快速、稳定。
 * - 纯逻辑 spec（types/utils）与组件 spec 共用同一配置：前者不受 happy-dom 影响。
 */
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  test: {
    environment: 'happy-dom',
    include: ['src/**/*.spec.ts'],
    setupFiles: ['./src/test/setup.ts'],
    // 只清调用记录、不还原实现：用例里 mockResolvedValueOnce 等一次性桩不受影响
    clearMocks: true,
    reporters: 'default'
  }
})
