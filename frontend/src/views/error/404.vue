<script setup lang="ts">
import { useRouter } from 'vue-router'

/**
 * 404 页面不存在
 */
const router = useRouter()
</script>

<template>
  <div class="ts-error">
    <el-result icon="info" title="404" sub-title="页面不存在，或该模块尚未在您的角色下开放">
      <template #extra>
        <el-button @click="router.back()">返回上一页</el-button>
        <el-button type="primary" @click="router.replace('/dashboard')">回到工作台</el-button>
      </template>
    </el-result>
    <p class="ts-error__hint">若您认为该页面应当可以访问，请联系系统管理员核对菜单与权限配置。</p>
  </div>
</template>

<style scoped>
.ts-error {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 60vh;
  padding: 24px 16px;
}

.ts-error__hint {
  margin: 0;
  font-size: 13px;
  text-align: center;
  color: var(--ts-text-hint);
}
</style>
