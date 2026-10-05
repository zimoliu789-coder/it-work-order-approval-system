/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 后端接口前缀，默认 /api（由 Vite proxy 转发，保证 Cookie 同源） */
  readonly VITE_API_BASE_URL: string
  /** 开发代理目标，默认 http://localhost:8080 */
  readonly VITE_API_PROXY_TARGET: string
  /** 开发端口 */
  readonly VITE_PORT: string
  /** 是否启用前端 Mock（无后端时用于 UI 走查，仅开发环境有效） */
  readonly VITE_USE_MOCK: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>
  export default component
}
