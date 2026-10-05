<script setup lang="ts">
import { useRouter } from 'vue-router'

/**
 * 403 无权限页（与后端 FORBIDDEN 错误码对应，）
 */
const router = useRouter()
</script>

<template>
  <div class="ts-error">
    <el-result icon="warning" title="403" sub-title="抱歉，你没有访问该页面的权限">
      <template #extra>
        <el-button @click="router.back()">返回上一页</el-button>
        <el-button type="primary" @click="router.replace('/dashboard')">回到工作台</el-button>
      </template>
    </el-result>
    <p class="ts-error__hint">
      若确实需要该权限，请联系超级管理员调整角色或部门配置。
    </p>
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
