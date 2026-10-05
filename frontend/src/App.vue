<script setup lang="ts">
import zhCn from 'element-plus/es/locale/lang/zh-cn'

/**
 * 应用根组件
 *
 * - 通过 ElConfigProvider 统一中文语言包（分页、表格、空态等文案）
 * - 全局只保留一个 router-view，布局逻辑封装在 BasicLayout
 */
</script>

<template>
  <el-config-provider :locale="zhCn">
    <router-view />
  </el-config-provider>
</template>

<style>
#app {
  height: 100%;
}
</style>
