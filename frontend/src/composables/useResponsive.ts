import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

/**
 * 响应式断点组合式函数
 *
 * ════════════════════════════════════════════════════════════════
 * 三档断点 —— 与 `src/styles/index.css` 的 CSS 档位**逐字对齐**
 * ════════════════════════════════════════════════════════════════
 *  外屏 / 手机    ≤ 767px     抽屉菜单 + 卡片列表 + 44px 触控
 *  内屏 / 平板    768–1023px  图标栏 + 表格 + 40px 触控（折叠屏展开态落在这里）
 *  桌面          ≥ 1024px    完整侧栏 + 表格 + 32px 控件
 *
 * ⚠️ 平板档上界从 1199 收到 **1023**（与 CSS 同步）：
 *    1024–1199 这段宽度下「完整侧栏 + 表格」是**可用**的，没必要降级；
 *    而折叠屏内屏最高只到 ≈830px，768–1023 已完全覆盖。
 *    若 JS 与 CSS 不同步，会出现「CSS 按桌面给 32px 控件、JS 却按平板收起侧栏」
 *    这类互相打架的状态 —— 两边必须一起改。
 *
 * 使用 window.matchMedia 监听，避免 resize 高频触发带来的性能损耗。
 */
const MOBILE_QUERY = '(max-width: 767px)'
const TABLET_QUERY = '(min-width: 768px) and (max-width: 1023px)'
const PC_QUERY = '(min-width: 1024px)'

export function useResponsive() {
  const isMobile = ref(false)
  const isTablet = ref(false)
  const isPc = ref(false)

  let mobileQuery: MediaQueryList | null = null
  let tabletQuery: MediaQueryList | null = null
  let pcQuery: MediaQueryList | null = null

  const sync = (): void => {
    isMobile.value = mobileQuery?.matches ?? false
    isTablet.value = tabletQuery?.matches ?? false
    isPc.value = pcQuery?.matches ?? false
  }

  onMounted(() => {
    if (typeof window === 'undefined') {
      return
    }
    mobileQuery = window.matchMedia(MOBILE_QUERY)
    tabletQuery = window.matchMedia(TABLET_QUERY)
    pcQuery = window.matchMedia(PC_QUERY)
    sync()
    mobileQuery.addEventListener('change', sync)
    tabletQuery.addEventListener('change', sync)
    pcQuery.addEventListener('change', sync)
  })

  onBeforeUnmount(() => {
    mobileQuery?.removeEventListener('change', sync)
    tabletQuery?.removeEventListener('change', sync)
    pcQuery?.removeEventListener('change', sync)
  })

  /** 手机或平板：需要收起侧边栏、改用抽屉菜单 */
  const isCompact = computed(() => isMobile.value || isTablet.value)

  return { isMobile, isTablet, isPc, isCompact }
}
