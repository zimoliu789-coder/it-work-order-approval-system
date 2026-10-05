<script setup lang="ts">
/**
 * 批量操作结果弹窗（P3 批量操作）
 *
 * 台账与组织与人员两页共用。抽出来的理由不是省几行模板，而是这类弹窗最容易在两个页面
 * 「各写一版」后逐渐分叉：一边显示失败原因、另一边只提示「操作完成」，
 * 用户就得靠猜。批量操作**必然**存在部分失败的可能，失败明细是它的核心信息。
 */
import type { BatchResult } from '@/types/api'

withDefaults(
  defineProps<{
    modelValue: boolean
    result: BatchResult | null
    /** 弹窗标题 */
    title?: string
    /** 条目单位（台 / 人 / 条），用于计数文案 */
    unit?: string
  }>(),
  {
    title: '批量操作结果',
    unit: '条'
  }
)

const emit = defineEmits<{ 'update:modelValue': [boolean] }>()

function close(): void {
  emit('update:modelValue', false)
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    width="680px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <template v-if="result">
      <el-alert
        :type="result.failed > 0 ? 'warning' : 'success'"
        :closable="false"
        show-icon
        :title="`共 ${result.total} ${unit}：成功 ${result.succeeded}，失败 ${result.failed}`"
        :description="
          result.failed > 0
            ? '失败项已在下方列出原因，并保持勾选 —— 修正后可直接重试这些记录。'
            : undefined
        "
      />

      <el-table
        v-if="result.failures.length"
        :data="result.failures"
        size="small"
        border
        max-height="320"
        class="ts-mt-16"
      >
        <el-table-column prop="name" label="记录" min-width="170" show-overflow-tooltip />
        <el-table-column prop="reason" label="失败原因" min-width="300" show-overflow-tooltip />
      </el-table>
    </template>

    <template #footer>
      <el-button type="primary" @click="close">知道了</el-button>
    </template>
  </el-dialog>
</template>
