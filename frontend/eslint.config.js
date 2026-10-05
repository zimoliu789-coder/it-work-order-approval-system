import js from '@eslint/js'
import globals from 'globals'
import pluginVue from 'eslint-plugin-vue'
import tseslint from 'typescript-eslint'

/**
 * ESLint 平铺配置（三波补做·第三波·需求 16「前端 ESLint 接入修复」）
 *
 * 背景：前三波之前前端只有 `vue-tsc --noEmit` 做类型检查，没有任何 lint。
 * 类型检查抓不到「未使用变量、可疑空块、v-for 缺 key、组件命名不规范」等静态问题；
 * 此前多轮的修复清单里已多次出现这类「本可被 lint 提前拦下」的低级缺陷。
 *
 * 规则取舍（避免一次性引入数百条风格噪音淹没真实问题）：
 * - 采用 `js.recommended` + `tseslint.recommended`（正确性为主）+ `vue/flat/essential`
 *   （只含 Vue 的**正确性**规则，不含属性顺序、换行等排版规则）；
 * - `tseslint` 的 eslint-recommended 会关闭 `no-undef`（由 TS 负责标识符解析）；
 *   我们进一步显式关闭，因为 `<script setup>` 里 `ref` / `ElMessage` 等由
 *   unplugin-auto-import 在编译期注入，静态 lint 看不到但运行时存在；
 * - 放开 `no-explicit-any`：项目在测试桩 / 第三方交互处确有合理用 any 的场景。
 */
export default tseslint.config(
  {
    ignores: [
      'dist/**',
      'node_modules/**',
      'coverage/**',
      // 由 unplugin 自动生成的声明文件，非手写、不应参与 lint
      'src/types/auto-imports.d.ts',
      'src/types/components.d.ts'
    ]
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  ...pluginVue.configs['flat/essential'],
  {
    files: ['**/*.{ts,vue}'],
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      globals: { ...globals.browser, ...globals.node }
    },
    rules: {
      // 标识符交给 TypeScript + 自动导入处理
      'no-undef': 'off',
      // 允许下划线前缀显式表达「故意不使用」
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_', caughtErrorsIgnorePattern: '^_' }
      ],
      '@typescript-eslint/no-explicit-any': 'off',
      // 空块若含注释不算空；此处再放行空 catch（项目大量采用「静默失败」策略）
      'no-empty': ['error', { allowEmptyCatch: true }],
      // 路由页面 / 布局等以目录 index.vue 组织，名称天然单词，关闭多词组件名强制
      'vue/multi-word-component-names': 'off'
    }
  },
  {
    files: ['**/*.vue'],
    languageOptions: {
      parserOptions: {
        parser: tseslint.parser,
        extraFileExtensions: ['.vue']
      }
    }
  }
)
