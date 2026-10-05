<script setup lang="ts">
import { computed, ref } from 'vue'
import { Delete, Paperclip, Upload } from '@element-plus/icons-vue'
import {
  attachmentBizConfig,
  formatFileSize,
  isAllowedAttachment,
  type AttachmentBizTypeCode
} from '@/types/attachment'

/**
 * 本地待上传附件选择器（配合 `uploadAttachments` 使用）
 *
 * 用于「随单提交」场景：附件必须绑定业务记录 id，而 id 要等主操作（提交申请 / 驳回 /
 * 归还 / 故障登记）成功后才拿到。因此这里只负责「本地挑选 + 校验 + 预览」，
 * 真正的上传由调用方在主操作成功后调用 `uploadAttachments` 完成。
 *
 * 校验口径与 `AttachmentUpload` 完全一致（同源 `@/types/attachment`），
 * 避免同一类型在「随单」与「补传」两处出现不同的格式/大小限制。
 */

const props = withDefaults(
  defineProps<{
    modelValue: File[]
    bizType: AttachmentBizTypeCode
    label?: string
    maxCount?: number
    maxSizeMb?: number
  }>(),
  {
    label: '附件（可选）',
    maxCount: 10,
    maxSizeMb: 10
  }
)

const emit = defineEmits<{ 'update:modelValue': [File[]] }>()

const inputRef = ref<HTMLInputElement | null>(null)

const config = computed(() => attachmentBizConfig(props.bizType))
const imageOnly = computed(() => config.value?.imageOnly === true)
const accept = computed(() => config.value?.accept ?? '')

const hint = computed(() => {
  const kind = imageOnly.value ? '仅支持图片' : '支持文档 / 图片 / 压缩包'
  return `${kind}，单个不超过 ${props.maxSizeMb}MB，最多 ${props.maxCount} 个`
})

function pick(): void {
  if (props.modelValue.length >= props.maxCount) {
    ElMessage.warning(`最多选择 ${props.maxCount} 个附件`)
    return
  }
  inputRef.value?.click()
}

function onFilesChange(event: Event): void {
  const input = event.target as HTMLInputElement
  const selected = input.files ? Array.from(input.files) : []
  input.value = ''
  if (selected.length === 0) {
    return
  }

  const maxBytes = props.maxSizeMb * 1024 * 1024
  const next = [...props.modelValue]
  for (const file of selected) {
    if (next.length >= props.maxCount) {
      ElMessage.warning(`最多选择 ${props.maxCount} 个附件`)
      break
    }
    if (file.size > maxBytes) {
      ElMessage.warning(`「${file.name}」超过 ${props.maxSizeMb}MB`)
      continue
    }
    if (!isAllowedAttachment(file.name, imageOnly.value)) {
      ElMessage.warning(imageOnly.value ? `「${file.name}」不是图片` : `「${file.name}」类型不支持`)
      continue
    }
    // 同名同大小视为重复，避免用户重复选择叠加
    if (next.some((item) => item.name === file.name && item.size === file.size)) {
      continue
    }
    next.push(file)
  }
  // 仅在确有新增时回传：全部被拒或全部重复时保持父组件 v-model 引用不变，
  // 避免「选了非法文件」也触发一次无意义的重渲染（组件级测试回归点）。
  if (next.length > props.modelValue.length) {
    emit('update:modelValue', next)
  }
}

function removeAt(index: number): void {
  const next = [...props.modelValue]
  next.splice(index, 1)
  emit('update:modelValue', next)
}
</script>

<template>
  <div class="ts-pick">
    <div class="ts-pick__head">
      <span class="ts-pick__label">{{ label }}</span>
      <el-button type="primary" plain size="small" @click="pick">
        <el-icon><Paperclip /></el-icon>
        <span>选择文件</span>
      </el-button>
      <input ref="inputRef" class="ts-pick__input" type="file" :accept="accept" multiple @change="onFilesChange" />
      <span class="ts-text-hint ts-pick__hint">{{ hint }}</span>
    </div>

    <ul v-if="modelValue.length > 0" class="ts-pick__list">
      <li v-for="(file, index) in modelValue" :key="`${file.name}-${index}`" class="ts-pick__item">
        <el-icon class="ts-pick__icon"><Upload /></el-icon>
        <span class="ts-pick__name">{{ file.name }}</span>
        <span class="ts-text-hint">{{ formatFileSize(file.size) }}</span>
        <el-button link type="danger" size="small" @click="removeAt(index)">
          <el-icon><Delete /></el-icon>
        </el-button>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.ts-pick__head {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.ts-pick__label {
  font-size: 13px;
}

.ts-pick__input {
  display: none;
}

.ts-pick__hint {
  font-size: 12px;
}

.ts-pick__list {
  margin: 8px 0 0;
  padding: 0;
  list-style: none;
}

.ts-pick__item {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 6px 4px;
  border-bottom: 1px dashed var(--ts-border);
}

.ts-pick__item:last-child {
  border-bottom: none;
}

.ts-pick__icon {
  color: #5b6472;
}

.ts-pick__name {
  flex: 1 1 auto;
  min-width: 0;
  font-size: 13px;
  word-break: break-all;
}
</style>
