<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

/**
 * 占位页
 *
 * 用途：当某个菜单路径尚未在 router 的 `resolvePageComponent` 中登记真实组件时，
 * 兜底渲染本页，避免白屏。
 *
 *  收尾优化· 做的两件事：
 * 1) **移除阶段表述**：改造前本页写着「该模块尚未开发完成」并暗示某个交付阶段，
 *    而到  全部功能均已交付 —— 这些表述会误导使用者以为系统仍是半成品；
 * 2) **清理失真的模块说明**：原先按路径写死的 `PATH_DESC` 里，
 *    `/system/password`（密码管理）与 `/system/staff/ldap`（AD 域控配置）都已交付真实页面
 *    （分别位于「个人 → 密码管理」与「系统管理 → AD 域控配置」——
 *     后者在  由「员工管理」页签拆为独立页面，路径同步改为 `/system/ldap`），
 *    对应的失效说明一并删除。
 *
 * 保留本页的意义：它是「菜单配了路径、但路由组件忘记登记」这一类疏漏的安全网。
 */
const route = useRoute()
const router = useRouter()

const title = computed(() => (route.meta?.title as string | undefined) ?? '功能模块')

/** 按路径给出的准确说明（未登记的路径回落到通用文案） */
const PATH_DESC: Record<string, string> = {}

const desc = computed(() => PATH_DESC[route.path] ?? '该页面暂未接入具体功能。')
</script>

<template>
  <div class="ts-page">
    <el-result icon="warning" :title="title" sub-title="该页面暂未接入具体功能">
      <template #extra>
        <el-button type="primary" @click="router.replace('/dashboard')">回到工作台</el-button>
      </template>
    </el-result>

    <section class="ts-card ts-placeholder__card">
      <h3 class="ts-placeholder__title">可能的原因</h3>
      <p class="ts-text-secondary ts-placeholder__desc">{{ desc }}</p>
      <p class="ts-text-secondary ts-placeholder__desc">
        若你确认该功能应当可用，请把本页地址（{{ route.path }}）反馈给系统管理员核对菜单配置。
      </p>
    </section>
  </div>
</template>

<style scoped>
.ts-placeholder__card {
  max-width: 760px;
  margin: 0 auto;
}

.ts-placeholder__title {
  margin: 0 0 8px;
  font-size: 15px;
  font-weight: 500;
}

.ts-placeholder__title:not(:first-child) {
  margin-top: 20px;
}

.ts-placeholder__desc {
  margin: 0;
  font-size: 13px;
  line-height: 1.9;
}
</style>
