<script setup lang="ts">
/**
 * 密码策略说明（ 收尾优化·）
 *
 * 纯展示组件：策略内容由父页面从 `GET /api/auth/password-policy` 拉取后传入。
 *
 * 为什么不在这里自己拉：**两个使用方都需要那个 `authType`**
 * （`/change-password` 与 `/profile/password` 都要据此决定是否隐藏改密表单），
 * 若本组件各自拉一次，同一页面就会出现两次请求，还会出现
 * 「策略已到、authType 未到」的中间态。因此取值归父页面，本组件只负责渲染。
 */
defineProps<{
  /** 策略条目（中文，已由后端拼好） */
  rules: string[]
}>()
</script>

<template>
  <div class="ts-policy">
    <h3 class="ts-policy__title">密码策略</h3>
    <ul class="ts-policy__list">
      <li v-for="rule in rules" :key="rule" class="ts-policy__item">
        <el-icon class="ts-policy__icon"><CircleCheck /></el-icon>
        <span>{{ rule }}</span>
      </li>
    </ul>
    <p class="ts-policy__hint">提示：以上说明与实际校验使用同一份参数，管理员调整后会同步更新。</p>
  </div>
</template>

<style scoped>
.ts-policy {
  padding: 14px 16px;
  background: #f7f9fc;
  border: 1px solid var(--ts-border);
  border-radius: 10px;
}

.ts-policy__title {
  margin: 0 0 10px;
  font-size: 14px;
  font-weight: 500;
}

.ts-policy__list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.ts-policy__item {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  padding: 3px 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--ts-text-secondary);
}

.ts-policy__icon {
  margin-top: 3px;
  flex: 0 0 auto;
  color: #3f8f4f;
  font-size: 13px;
}

.ts-policy__hint {
  margin: 10px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--ts-text-hint);
}
</style>
