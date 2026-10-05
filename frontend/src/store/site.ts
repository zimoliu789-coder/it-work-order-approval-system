import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import systemApi from '@/api/system'
import type { SiteInfo } from '@/types/system'

/**
 * 站点品牌状态（Pinia）—— ：系统名称与 logo 可配置。
 *
 * <h2>为什么必须是全局 store 而不是各页面自己请求</h2>
 * <p>同一份数据要被三处同时渲染：侧边栏顶部（PC 常驻 + 手机抽屉，共 2 个渲染点）、
 * 登录页标题、浏览器标签页标题。各页面各请求一次会有两个后果：
 * ① 每次路由切换都多一次网络往返；
 * ② 「改完立即全局生效」变成「刷新后才生效」—— 因为别处的副本不会被更新。
 * 收进 store 后，「保存成功 → store.apply(...)」即可让全部渲染点同时刷新。
 *
 * <h2>兜底值为什么与后端默认值一致</h2>
 * <p>{@link FALLBACK} 必须与后端 {@code SiteBranding.DEFAULT_*} 保持一致：
 * 接口失败（后端未启动 / 网络抖动）时界面仍显示正确名称，而不是空白标题 ——
 * 登录页尤其重要，那是用户看到的第一屏。
 */
const FALLBACK: SiteInfo = {
  siteName: '设备借用工单系统',
  // 版权刻意不兜底文案：服务端的默认值就是空串（见 SiteBranding.DEFAULT_COPYRIGHT），
  // 前端若自己补一句「© 20xx」，就会出现「接口失败时反而多出一行版权、接口正常时没有」
  // 这种最令人困惑的差异。
  copyright: '',
  logoType: 'TEXT',
  logoText: 'IT',
  logoUrl: null
}

export const useSiteStore = defineStore('site', () => {
  const info = ref<SiteInfo>({ ...FALLBACK })
  /** 是否已成功从后端取到过数据（用于区分「兜底值」与「服务端的值」） */
  const loaded = ref(false)
  /** 请求中标记：避免同一时刻并发拉取（启动 + 登录页挂载 + 保存后刷新） */
  let inflight: Promise<void> | null = null

  const siteName = computed(() => info.value.siteName || FALLBACK.siteName)
  const logoType = computed(() => info.value.logoType)
  /** 文字 logo 内容；图片形态时回落默认文字，避免渲染成空块 */
  const logoText = computed(() => info.value.logoText || FALLBACK.logoText)
  /**
   * 图片 logo 地址；文字形态时为 null。
   *
   * <p>地址尾部不带版本号：后端对该图片返回 {@code Cache-Control: no-store}，
   * 换了 logo 之后浏览器不会复用旧图。若改为可缓存，就必须在这里加
   * 时间戳参数 —— 否则「管理员换了 logo，别人还看到旧图」。
   */
  const logoUrl = computed(() => (logoType.value === 'IMAGE' ? info.value.logoUrl : null))

  /**
   * 版权文字（）。
   *
   * <p>与 {@link siteName} 的关键差别：**允许为空**，且为空时返回 null
   * 而不是补一句默认文案。调用方（登录页、侧边栏页脚）用 `v-if="siteCopyright"` 判断，
   * 于是「管理员没填 → 整行不渲染」这条需求由一处实现决定，不必每个渲染点各判一遍。
   */
  const siteCopyright = computed(() => info.value.copyright?.trim() || null)

  function apply(next: SiteInfo | null | undefined): void {
    if (!next || typeof next.siteName !== 'string' || !next.siteName) {
      return
    }
    info.value = next
    loaded.value = true
  }

  /**
   * 拉取站点品牌信息（免认证接口，登录页也可调用）。
   *
   * <p>失败**静默**：这是外观信息，请求失败不应弹错、更不应阻断启动；
   * 保留兜底值即可（与 metaApi.messageTypes 的失败取向一致）。
   */
  async function load(): Promise<void> {
    if (inflight) {
      return inflight
    }
    inflight = (async () => {
      try {
        apply(await systemApi.getSiteInfo())
      } catch {
        // 忽略：继续使用兜底值
      } finally {
        inflight = null
      }
    })()
    return inflight
  }

  /** 用「上传 logo / 恢复默认」接口的返回值直接更新，省一次往返 */
  function setFromApi(next: SiteInfo): void {
    apply(next)
  }

  return {
    info,
    loaded,
    siteName,
    siteCopyright,
    logoType,
    logoText,
    logoUrl,
    load,
    apply,
    setFromApi
  }
})
