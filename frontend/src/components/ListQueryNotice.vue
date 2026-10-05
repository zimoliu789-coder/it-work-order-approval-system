<script setup lang="ts">
import { InfoFilled } from '@element-plus/icons-vue'

/**
 * 「本页已记住筛选」提示条（ · W4-E，决策 D4 第 4 条）
 *
 * D4 明确要求筛选记忆必须「**可见 + 可清除**」。这两件事都需要一个落点，
 * 而 T1 三张页面（故障管理 / 导出记录 / 使用记录）各写一遍必然出现三份逐渐漂移的文案与样式，
 * 因此收敛成一个小组件。
 *
 * 为什么不放进 `TablePage`：`TablePage` 管的是**表格本体**（列 / 分页 / 空态），
 * 筛选区归页面自己管 —— 每页的筛选项差异极大（有的是两个输入框，有的是日期区间 + 远程选择器），
 * 收进公共层只会变成一堆 props 与插槽。提示条紧邻筛选区，归页面更自然。
 *
 * 刻意做成**非侵入式**：`visible` 为假时**不渲染任何节点**，不占据默认布局的高度，
 * 因此对既有页面的首屏版式零影响（这也是「零回归」的一部分）。
 */
defineProps<{
  /** 是否展示（= 该页 `useListQuery` 的 `remembered`） */
  visible: boolean
  /** 补充说明：本页有字段**刻意不记忆**时，在此写明原因（否则用户会当成 bug） */
  hint?: string
}>()

const emit = defineEmits<{ (e: 'clear'): void }>()
</script>

<template>
  <div v-if="visible" class="ts-list-query-notice">
    <el-icon class="ts-list-query-notice__icon"><InfoFilled /></el-icon>
    <span class="ts-list-query-notice__text">
      本页已记住筛选条件，进入时自动沿用上次的查询视图。
      <span v-if="hint" class="ts-list-query-notice__hint">{{ hint }}</span>
    </span>
    <el-button link type="primary" size="small" @click="emit('clear')">清除记忆</el-button>
  </div>
</template>

<style scoped>
.ts-list-query-notice {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  padding: 6px 10px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--ts-text-secondary);
  background: #eef4fd;
  border: 1px solid #d6e4f7;
  border-radius: 6px;
}

.ts-list-query-notice__icon {
  flex: none;
  color: var(--ts-accent);
  font-size: 14px;
}

.ts-list-query-notice__text {
  flex: 1 1 auto;
  min-width: 0;
}

.ts-list-query-notice__hint {
  color: var(--ts-text-hint);
}

@media (max-width: 767px) {
  .ts-list-query-notice {
    align-items: flex-start;
    flex-wrap: wrap;
  }
}
</style>
