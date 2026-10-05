import { fileURLToPath, URL } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import { VitePWA } from 'vite-plugin-pwa'

/**
 * Vite 配置
 * - Element Plus 按需引入（规范 §4）
 * - 开发环境通过 proxy 将 /api 代理到后端 8080，前端与后端同源，
 *   使 HttpOnly Cookie 无需跨域即可正常读写（规范 §19）
 */
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const target = env.VITE_API_PROXY_TARGET || 'http://localhost:8080'

  return {
    plugins: [
      vue(),
      AutoImport({
        imports: ['vue', 'vue-router', 'pinia'],
        resolvers: [ElementPlusResolver({ importStyle: 'css' })],
        dts: 'src/types/auto-imports.d.ts',
        eslintrc: { enabled: false }
      }),
      Components({
        resolvers: [ElementPlusResolver({ importStyle: 'css' })],
        dts: 'src/types/components.d.ts',
        dirs: ['src/components']
      }),
      // ------------------------------------------------------------------
      // PWA（P1「手机 App」）：同一套响应式 Web 代码「添加到主屏」后全屏运行。
      //
      // 为什么不直接做原生 App：规范 §79 明确「必须采用同一套响应式 Web 前端，
      // 不复制维护 PC 版与手机独立版」；PWA 正是这条约束下的可安装形态，
      // 且无需 Android SDK / Xcode 即可交付。原生壳骨架见 frontend/capacitor/README.md。
      // ------------------------------------------------------------------
      VitePWA({
        // autoUpdate：新版本就绪后自动接管，不要求用户理解「有个新版本待更新」这类概念。
        // 内部系统里，让几百号人各自去点刷新是不现实的 —— 结果是长期跑旧版本。
        registerType: 'autoUpdate',
        includeAssets: ['apple-touch-icon-180x180.png'],
        manifest: {
          name: '设备借用工单系统',
          short_name: '设备借用',
          description: '企业内部设备借用工单系统：申请、审批、借用、归还与资产台账',
          lang: 'zh-CN',
          theme_color: '#1f3a5f',
          background_color: '#ffffff',
          display: 'standalone',
          start_url: '/',
          scope: '/',
          icons: [
            { src: 'pwa-192x192.png', sizes: '192x192', type: 'image/png' },
            { src: 'pwa-512x512.png', sizes: '512x512', type: 'image/png' },
            {
              src: 'pwa-maskable-512x512.png',
              sizes: '512x512',
              type: 'image/png',
              purpose: 'maskable'
            }
          ]
        },
        workbox: {
          globPatterns: ['**/*.{js,css,html,svg,png,ico,woff,woff2}'],
          // 换版本时清掉上一版预缓存，否则升级后可能残留旧 chunk 导致白屏
          cleanupOutdatedCaches: true,
          // 接口一律不进预缓存：工单状态、设备可借性这类数据必须实时，
          // 缓存住会让用户看到过期的「可用设备」并据此提交申请。
          navigateFallbackDenylist: [/^\/api\//]
        }
      })
    ],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url))
      }
    },
    server: {
      host: '0.0.0.0',
      port: Number(env.VITE_PORT || 5173),
      strictPort: false,
      proxy: {
        '/api': {
          target,
          changeOrigin: true
        }
      }
    },
    build: {
      outDir: 'dist',
      sourcemap: mode !== 'production',
      chunkSizeWarningLimit: 1500,
      rollupOptions: {
        // ECharts 体积（P3 实测留档，免得后人重复试错）：
        // · 按需注册（`echarts/core` + `use([...])`，见 components/ChartBox.vue）→ 570.85 kB（gzip 196.27 kB）
        // · 全量 `import * as echarts from 'echarts'` 对照        → 1,142.91 kB（gzip 384.82 kB）
        // ⇒ 按需写法本身就生效（省 188.5 kB gzip，降幅约 49%）。**不需要**额外配 treeshake：
        //   实测给 echarts 的 lib/chart/* 与 lib/component/* 加 moduleSideEffects=false，
        //   配前配后产物 hash 完全相同（毫无差别），因此不值得引入这个依赖内部目录结构的假设。
        output: {
          // 注意：不要把 'element-plus' 整个包名写进 manualChunks —— 那会让 Rollup 纳入
          // 它的全量 barrel 入口，直接使 unplugin 的按需引入失效（实测多打包约 1MB JS）。
          // 剩余的公共依赖交给 Rollup 自动分包。
          manualChunks: {
            vue: ['vue', 'vue-router', 'pinia']
          }
        }
      }
    }
  }
})
