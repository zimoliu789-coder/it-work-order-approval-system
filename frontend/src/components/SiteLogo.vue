<script setup lang="ts">
import { useSiteStore } from '@/store/site'

/**
 * 站点 logo（）—— 文字图标与上传图片两种形态的**唯一渲染入口**。
 *
 * <h2>为什么必须收成一个组件</h2>
 * <p>同一个 logo 要在三处渲染：侧边栏顶部（PC 常驻 + 手机抽屉，两个渲染点）、登录页。
 * 若每处各写一遍「是图片就 img、否则渲染文字」，三份实现必然出现一处漏判 ——
 * 表现为某个页面把 {@code FILE:2026/09/xxx.png} 这样的库内引用串当文字显示出来。
 * 收成一处后，判断逻辑只此一份，新增渲染点也不会再漏。
 *
 * <h2>尺寸与配色为什么不由本组件决定</h2>
 * <p>侧边栏的 mark 是 28px / 深蓝底 / 12px 圆角，登录页是 46px（移动端 40px）/
 * 主题色底 / 12px 圆角 —— 两处尺寸与配色本就不同，硬编码进组件会把它们绑死。
 * 因此本组件只管「渲染什么」，外观由**外层容器的 class** 决定：
 * 图片用 {@code width/height:100%} 撑满容器并继承圆角，文字直接继承容器的字号与颜色。
 */
const siteStore = useSiteStore()
</script>

<template>
  <img
    v-if="siteStore.logoType === 'IMAGE'"
    class="ts-site-logo__img"
    :src="siteStore.logoUrl ?? ''"
    alt="系统 logo"
  />
  <span v-else class="ts-site-logo__text">{{ siteStore.logoText }}</span>
</template>

<style scoped>
.ts-site-logo__img {
  display: block;
  width: 100%;
  height: 100%;
  /* 继承外层容器的圆角（侧边栏 7px / 登录页 12px），避免图片自己带一个方形直角 */
  border-radius: inherit;
  object-fit: cover;
}

.ts-site-logo__text {
  /* 外层容器已居中并给出字号，这里只需去掉行高带来的额外高度 */
  line-height: 1;
}
</style>
