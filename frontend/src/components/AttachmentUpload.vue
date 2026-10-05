<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Document, Download, Picture, Upload } from '@element-plus/icons-vue'
import { attachmentApi } from '@/api/attachment'
import {
  attachmentBizConfig,
  attachmentBizLabel,
  formatFileSize,
  isAllowedAttachment,
  type AttachmentBizTypeCode,
  type AttachmentItem
} from '@/types/attachment'

/**
 * 通用附件上传组件
 *
 * 设计要点：
 * - 一个组件覆盖四类业务（申请附件 / 驳回附件 / 归还照片 / 故障照片），
 *   通过 `bizType` + `bizId` 定位；类型相关的 accept 与文案由 `@/types/attachment` 的
 *   配置表统一提供，避免四处硬编码格式清单而分叉；
 * - 列表数据始终以服务端为准（上传/删除后重新拉取），不做本地乐观增删 ——
 *   附件涉及权限与数量上限，本地拼装容易与服务端真实状态不一致；
 * - 照片类附件点击直接大图预览，文档类点击下载；
 * - `readonly` 模式下隐藏上传与删除入口，用于「只读查看」场景。
 */

const props = withDefaults(
  defineProps<{
    bizType: AttachmentBizTypeCode
    bizId: number
    readonly?: boolean
    maxCount?: number
    maxSizeMb?: number
    title?: string
    /** 只读且无附件时整块隐藏（用于详情弹窗里并列展示多类附件，避免出现多个「暂无附件」空块） */
    hideEmpty?: boolean
  }>(),
  {
    readonly: false,
    maxCount: 10,
    maxSizeMb: 10,
    title: '附件',
    hideEmpty: false
  }
)

const emit = defineEmits<{ change: [count: number] }>()

const config = computed(() => attachmentBizConfig(props.bizType))
const imageOnly = computed(() => config.value?.imageOnly === true)
const accept = computed(() => config.value?.accept ?? '')

const inputRef = ref<HTMLInputElement | null>(null)
const loading = ref(false)
const uploading = ref(false)
const progress = ref(0)
const items = ref<AttachmentItem[]>([])

const previewVisible = ref(false)
const previewUrl = ref('')
const previewName = ref('')

const reachedLimit = computed(() => items.value.length >= props.maxCount)

/** 只读 + 无附件 + 非加载中 时整块不渲染（配合 hideEmpty） */
const hidden = computed(
  () => props.hideEmpty && props.readonly && !loading.value && items.value.length === 0
)

const hint = computed(() => {
  const kind = imageOnly.value ? '仅支持图片' : '支持文档 / 图片 / 压缩包'
  return `${kind}，单个不超过 ${props.maxSizeMb}MB，最多 ${props.maxCount} 个`
})

onMounted(loadItems)
watch(
  () => [props.bizType, props.bizId] as const,
  () => {
    void loadItems()
  }
)

async function loadItems(): Promise<void> {
  if (props.bizId == null) {
    items.value = []
    return
  }
  loading.value = true
  try {
    items.value = await attachmentApi.list(props.bizType, props.bizId)
    emit('change', items.value.length)
  } catch {
    // 无权查看 / 业务不存在等由请求层统一提示，这里保持空列表
    items.value = []
  } finally {
    loading.value = false
  }
}

function pick(): void {
  if (reachedLimit.value) {
    ElMessage.warning(`最多上传 ${props.maxCount} 个附件`)
    return
  }
  inputRef.value?.click()
}

async function onFilesChange(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const selected = input.files ? Array.from(input.files) : []
  // 立即清空 value，允许连续选择同一个文件
  input.value = ''
  if (selected.length === 0) {
    return
  }

  const maxBytes = props.maxSizeMb * 1024 * 1024
  const accepted: File[] = []
  for (const file of selected) {
    if (items.value.length + accepted.length >= props.maxCount) {
      ElMessage.warning(`最多上传 ${props.maxCount} 个附件`)
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
    accepted.push(file)
  }
  if (accepted.length === 0) {
    return
  }

  uploading.value = true
  let done = 0
  for (const file of accepted) {
    progress.value = 0
    try {
      await attachmentApi.upload(props.bizType, props.bizId, file, (percent) => {
        progress.value = percent
      })
      done += 1
    } catch {
      // 单个失败不阻断其余文件；错误提示由请求层统一处理
    }
  }
  uploading.value = false
  progress.value = 0
  if (done > 0) {
    ElMessage.success(`已上传 ${done} 个附件`)
    await loadItems()
  }
}

function openItem(item: AttachmentItem): void {
  if (item.image) {
    previewUrl.value = attachmentApi.inlineUrl(item.id)
    previewName.value = item.fileName
    previewVisible.value = true
    return
  }
  void attachmentApi.download(item.id, item.fileName)
}

async function removeItem(item: AttachmentItem): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认删除附件「${item.fileName}」？`, '删除附件', { type: 'warning' })
  } catch {
    return
  }
  try {
    await attachmentApi.remove(item.id)
    ElMessage.success('附件已删除')
    await loadItems()
  } catch {
    // 无权删除等由请求层统一提示
  }
}
</script>

<template>
  <div v-if="!hidden" class="ts-attach">
    <div class="ts-attach__head">
      <span class="ts-attach__title">{{ title }}</span>
      <el-tag size="small" effect="plain" type="info">{{ attachmentBizLabel(bizType) }}</el-tag>
      <el-button
        v-if="!readonly"
        type="primary"
        plain
        size="small"
        :loading="uploading"
        :disabled="reachedLimit"
        @click="pick"
      >
        <el-icon><Upload /></el-icon>
        <span>上传附件</span>
      </el-button>
      <input
        ref="inputRef"
        class="ts-attach__input"
        type="file"
        :accept="accept"
        multiple
        @change="onFilesChange"
      />
    </div>

    <p class="ts-text-hint ts-attach__hint">
      {{ hint }}<template v-if="uploading"> · 上传中 {{ progress }}%</template>
    </p>

    <div v-loading="loading" class="ts-attach__body">
      <ul v-if="items.length > 0" class="ts-attach__list">
        <li v-for="item in items" :key="item.id" class="ts-attach__item">
          <el-image
            v-if="item.image"
            :src="attachmentApi.inlineUrl(item.id)"
            fit="cover"
            class="ts-attach__thumb"
            @click="openItem(item)"
          >
            <template #error>
              <div class="ts-attach__thumb-fallback"><el-icon><Picture /></el-icon></div>
            </template>
          </el-image>
          <div v-else class="ts-attach__thumb ts-attach__thumb--doc">
            <el-icon :size="20"><Document /></el-icon>
          </div>

          <div class="ts-attach__meta">
            <button class="ts-attach__name" type="button" @click="openItem(item)">
              {{ item.fileName }}
            </button>
            <span class="ts-text-hint">
              {{ formatFileSize(item.fileSize) }} · {{ item.uploaderName || '未知' }} · {{ item.createdAt || '' }}
            </span>
          </div>

          <div class="ts-attach__actions">
            <el-button link type="primary" size="small" @click="openItem(item)">
              <el-icon><Download v-if="!item.image" /><Picture v-else /></el-icon>
              <span>{{ item.image ? '预览' : '下载' }}</span>
            </el-button>
            <el-button v-if="!readonly" link type="danger" size="small" @click="removeItem(item)">
              <el-icon><Delete /></el-icon>
            </el-button>
          </div>
        </li>
      </ul>
      <p v-else-if="!loading" class="ts-attach__empty ts-text-hint">暂无附件</p>
    </div>

    <el-dialog v-model="previewVisible" :title="previewName" width="min(92vw, 720px)" append-to-body>
      <div class="ts-attach__preview">
        <img :src="previewUrl" :alt="previewName" />
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.ts-attach__head {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.ts-attach__title {
  font-size: 13px;
  font-weight: 500;
}

.ts-attach__input {
  display: none;
}

.ts-attach__hint {
  margin: 6px 0 8px;
  font-size: 12px;
}

.ts-attach__list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.ts-attach__item {
  display: flex;
  gap: 10px;
  align-items: center;
  padding: 8px 4px;
  border-bottom: 1px solid var(--ts-border);
}

.ts-attach__item:last-child {
  border-bottom: none;
}

.ts-attach__thumb {
  flex: 0 0 auto;
  width: 40px;
  height: 40px;
  border-radius: 6px;
  overflow: hidden;
  cursor: pointer;
}

.ts-attach__thumb--doc,
.ts-attach__thumb-fallback {
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(31, 58, 95, 0.06);
  color: #5b6472;
}

.ts-attach__meta {
  display: flex;
  flex: 1 1 auto;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}

.ts-attach__name {
  padding: 0;
  border: none;
  background: transparent;
  font-size: 13px;
  text-align: left;
  color: var(--el-color-primary);
  cursor: pointer;
  word-break: break-all;
}

.ts-attach__actions {
  display: flex;
  flex: 0 0 auto;
  gap: 4px;
  align-items: center;
}

.ts-attach__empty {
  margin: 0;
  padding: 12px 0;
  font-size: 12px;
  text-align: center;
}

.ts-attach__preview {
  display: flex;
  justify-content: center;
}

.ts-attach__preview img {
  max-width: 100%;
  max-height: 70vh;
  object-fit: contain;
}
</style>
