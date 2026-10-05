import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

/**
 * 响应式断点组合式函数
 *
 * 0-767px    手机
 * 768-1199px 平板
 * 1200px+    PC
 *
 * 使用 window.matchMedia 监听，避免 resize 高频触发带来的性能损耗。
 */
const MOBILE_QUERY = '(max-width: 767px)'
const TABLET_QUERY = '(min-width: 768px) and (max-width: 1199px)'
const PC_QUERY = '(min-width: 1200px)'

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
