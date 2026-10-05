<script setup lang="ts">
import { ref, watch } from 'vue'
import { useDragSort } from '@/composables/useDragSort'
import { useResponsive } from '@/composables/useResponsive'
import type { ColumnConfigRow } from '@/composables/useColumnConfig'

/**
 * 列配置弹窗（-B）
 *
 * 职责：让用户勾选「显示哪些列」并调整「列的先后顺序」。
 *
 * 设计要点：
 * 1. **草稿语义**：改动只落在本地 `draft` 上，点「保存」才 emit `confirm` 回给父组件，
 *    点「取消」/ 关闭弹窗则**丢弃**。这样用户可以在弹窗里随便试，不会改一次存一次。
 * 2. **锁定列**（`locked`，即「操作」列）：勾选框固定选中且禁用、不可拖动 ——
 *    用户把操作列关掉等于页面直接坏掉，这类列不参与配置。
 * 3. **不使用 `el-table`**：这里要的是「带拖拽的勾选列表」而不是数据表格，
 *    用原生 `ul/li` + HTML5 drag-and-drop（复用 `useDragSort`）结构更直白，
 *    也避免把 EP 表格的排序/选中语义误用成配置交互。
 * 4. **移动端不承载拖拽**（与 `useDragSort` 的既有约束一致）：触摸屏上原生 DnD 支持有限，
 *    因此每行都提供「上移/下移」按钮作为等价操作方式。
 *
 * 关于排序：`move` 与 `drag` 都只改 `draft`，最终由 `confirm` 一次性提交完整顺序，
 * 因此**不存在**"两次快照互相覆盖"的问题。
 */

const props = defineProps<{
  modelValue: boolean
  /** 可配置列（由 useColumnConfig 的 configRows 提供） */
  columns: ColumnConfigRow[]
  title?: string
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'confirm', payload: { order: string[]; hidden: string[] }): void
}>()

const { isMobile } = useResponsive()

/** 草稿行：与 ColumnConfigRow 同构，但字段可写 */
interface DraftRow {
  key: string
  label: string
  visible: boolean
  locked: boolean
  defaultVisible: boolean
}

const draft = ref<DraftRow[]>([])

const { dragIndex, overIndex, onStart, onOver, onDrop, move, reset: resetDrag } =
  useDragSort<DraftRow>(draft)

/** 打开时从 props 拷一份草稿；关闭时不动 draft（下次打开会重新拷贝，等于丢弃） */
watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      draft.value = props.columns.map((row) => ({ ...row }))
      resetDrag()
    }
  },
  { immediate: true }
)

function toggleVisible(row: DraftRow, value: unknown): void {
  if (row.locked) {
    // 锁定列恒可见：即使事件被伪造也不接受
    row.visible = true
    return
  }
  row.visible = value === true
}

function selectAll(): void {
  for (const row of draft.value) {
    row.visible = true
  }
}

function clearAll(): void {
  for (const row of draft.value) {
    row.visible = row.locked
  }
}

function restoreDefault(): void {
  for (const row of draft.value) {
    row.visible = row.defaultVisible
  }
}

function onRowDragStart(index: number): void {
  if (draft.value[index]?.locked) {
    return
  }
  onStart(index)
}

function onRowDragOver(index: number): void {
  if (draft.value[index]?.locked) {
    return
  }
  onOver(index)
}

function moveRow(index: number, offset: number): void {
  if (draft.value[index]?.locked) {
    return
  }
  move(index, offset)
}

function close(): void {
  emit('update:modelValue', false)
}

function confirm(): void {
  emit('confirm', {
    order: draft.value.map((row) => row.key),
    hidden: draft.value.filter((row) => !row.visible && !row.locked).map((row) => row.key)
  })
  close()
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="title ?? '列设置'"
    :width="isMobile ? '94%' : '480px'"
    @update:model-value="close"
  >
    <p class="ts-cols__hint">
      勾选需要显示的列，拖动或使用箭头调整先后顺序；「操作」列固定显示，不可隐藏。
    </p>

    <div class="ts-cols__toolbar">
      <el-button link type="primary" size="small" @click="selectAll">全选</el-button>
      <el-button link type="primary" size="small" @click="clearAll">全不选</el-button>
      <el-button link type="primary" size="small" @click="restoreDefault">恢复默认</el-button>
    </div>

    <ul class="ts-cols__list">
      <li
        v-for="(row, index) in draft"
        :key="row.key"
        class="ts-cols__item"
        :class="{
          'is-dragging': dragIndex === index,
          'is-over': overIndex === index && dragIndex !== index,
          'is-locked': row.locked
        }"
        :draggable="!row.locked"
        :data-column-key="row.key"
        @dragstart="onRowDragStart(index)"
        @dragover.prevent="onRowDragOver(index)"
        @drop="onDrop"
        @dragend="resetDrag"
      >
        <span class="ts-cols__grip" :class="{ 'is-disabled': row.locked }" aria-hidden="true">⣿</span>
        <el-checkbox
          class="ts-cols__check"
          :model-value="row.visible"
          :disabled="row.locked"
          @update:model-value="toggleVisible(row, $event)"
        >
          <span class="ts-cols__label">{{ row.label }}</span>
        </el-checkbox>
        <span class="ts-cols__locked-tag" v-if="row.locked">固定</span>
        <span class="ts-cols__moves">
          <el-button link size="small" :disabled="index === 0 || row.locked" @click="moveRow(index, -1)">
            上移
          </el-button>
          <el-button
            link
            size="small"
            :disabled="index === draft.length - 1 || row.locked"
            @click="moveRow(index, 1)"
          >
            下移
          </el-button>
        </span>
      </li>
    </ul>

    <template #footer>
      <el-button @click="close">取消</el-button>
      <el-button type="primary" @click="confirm">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.ts-cols__hint {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--ts-text-secondary);
}

.ts-cols__toolbar {
  display: flex;
  gap: 12px;
  margin: 8px 0 4px;
}

.ts-cols__list {
  margin: 0;
  padding: 0;
  list-style: none;
  max-height: 46vh;
  overflow-y: auto;
}

.ts-cols__item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 6px;
  border: 1px solid transparent;
  border-radius: 6px;
}

.ts-cols__item.is-locked {
  background: #f7f8fa;
}

.ts-cols__item.is-dragging {
  opacity: 0.5;
}

.ts-cols__item.is-over {
  border-color: var(--ts-primary, #409eff);
}

.ts-cols__grip {
  cursor: grab;
  color: var(--ts-text-secondary);
  font-size: 12px;
  line-height: 1;
  user-select: none;
}

.ts-cols__grip.is-disabled {
  cursor: not-allowed;
  opacity: 0.35;
}

.ts-cols__check {
  flex: 1 1 auto;
  min-width: 0;
}

.ts-cols__label {
  word-break: break-all;
}

.ts-cols__locked-tag {
  flex: none;
  font-size: 11px;
  color: var(--ts-text-secondary);
}

.ts-cols__moves {
  flex: none;
  display: flex;
  gap: 6px;
}

@media (max-width: 767px) {
  .ts-cols__item {
    flex-direction: column;
    align-items: flex-start;
    gap: 2px;
  }

  .ts-cols__moves {
    align-self: flex-end;
  }
}
</style>
