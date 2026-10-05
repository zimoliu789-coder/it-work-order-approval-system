<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { UploadFile } from 'element-plus'
import { deviceImportApi } from '@/api/device'
import { useResponsive } from '@/composables/useResponsive'
import type { DeviceImportPreview, DeviceImportResult, DeviceImportRowResult } from '@/types/device'

/**
 * 设备批量导入弹窗（管理端，）
 *
 * 三步：下载模板 → 上传校验（非阻断，返回逐行结果）→ 确认导入（仅通过行）。
 * 校验失败不阻断流程：用户可先导入通过行，再下载失败明细修正后重导。
 *
 * 校验规则完全复用后端「单条新增」的服务层校验（同一套设备字段规则），
 * 前端只负责文件形态（.xlsx / ≤5MB）与结果展示。
 */

const props = defineProps<{ modelValue: boolean }>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'imported'): void
}>()

const { isMobile } = useResponsive()

/** 步骤标题（PC 步骤条与移动端单行指示共用同一份文案） */
const STEP_TITLES = ['下载模板并填写', '上传校验', '确认导入']

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value)
})

/** 与后端保持一致的上限，前端先拦一层避免无谓上传 */
const MAX_SIZE = 5 * 1024 * 1024

const file = ref<File | null>(null)
const selectedName = ref('')
const previewing = ref(false)
const executing = ref(false)
const preview = ref<DeviceImportPreview | null>(null)
const result = ref<DeviceImportResult | null>(null)

const activeStep = computed(() => {
  if (result.value) {
    return 2
  }
  if (preview.value) {
    return 1
  }
  return 0
})

const validRows = computed(() => (preview.value?.rows ?? []).filter((row) => row.valid))
const failedRows = computed(() => (preview.value?.rows ?? []).filter((row) => !row.valid))
const remainingFailures = computed(() => result.value?.failures ?? [])

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      reset()
    }
  }
)

function reset(): void {
  file.value = null
  selectedName.value = ''
  preview.value = null
  result.value = null
}

function handleFileChange(uploadFile: UploadFile): void {
  const raw = uploadFile.raw
  if (!raw) {
    return
  }
  if (!raw.name.toLowerCase().endsWith('.xlsx')) {
    ElMessage.warning('仅支持 .xlsx 格式的 Excel 文件')
    return
  }
  if (raw.size > MAX_SIZE) {
    ElMessage.warning('文件超过 5MB 上限，请拆分后重新导入')
    return
  }
  file.value = raw
  selectedName.value = raw.name
  preview.value = null
  result.value = null
}

async function handleDownloadTemplate(): Promise<void> {
  try {
    await deviceImportApi.downloadTemplate()
  } catch {
    // 错误提示由请求层统一处理
  }
}

async function handlePreview(): Promise<void> {
  if (!file.value) {
    ElMessage.warning('请先选择要导入的 .xlsx 文件')
    return
  }
  previewing.value = true
  try {
    preview.value = await deviceImportApi.preview(file.value)
    if (preview.value.successCount === 0) {
      ElMessage.warning('没有校验通过的数据行，请按失败原因修正后重新上传')
    } else {
      ElMessage.success(
        `解析完成：通过 ${preview.value.successCount} 条，失败 ${preview.value.failCount} 条`
      )
    }
  } catch {
    preview.value = null
  } finally {
    previewing.value = false
  }
}

async function handleExecute(): Promise<void> {
  if (!preview.value || validRows.value.length === 0) {
    return
  }
  executing.value = true
  try {
    result.value = await deviceImportApi.execute({
      fileName: preview.value.fileName,
      rows: validRows.value
    })
    if (result.value.importedCount > 0) {
      ElMessage.success(`成功导入 ${result.value.importedCount} 台`)
    }
    emit('imported')
  } catch {
    // 错误提示由请求层统一处理
  } finally {
    executing.value = false
  }
}

/** 下载失败明细：优先用导入后剩余失败行，其次用校验失败行 */
async function handleDownloadFailures(): Promise<void> {
  const rows = remainingFailures.value.length > 0 ? remainingFailures.value : failedRows.value
  if (rows.length === 0) {
    return
  }
  try {
    await deviceImportApi.downloadFailureReport({
      fileName: preview.value?.fileName ?? selectedName.value,
      rows
    })
  } catch {
    // 错误提示由请求层统一处理
  }
}

function rowClassName({ row }: { row: DeviceImportRowResult }): string {
  return row.valid ? '' : 'ts-import__row-invalid'
}
</script>

<template>
  <el-dialog
    v-model="visible"
    title="批量导入设备"
    :width="isMobile ? '96%' : '880px'"
    class="ts-import__dialog"
  >
    <!-- PC：步骤条；移动端宽度不足会把标题挤成竖排，改用单行文字指示 -->
    <el-steps v-if="!isMobile" :active="activeStep" simple class="ts-import__steps">
      <el-step title="下载模板并填写" />
      <el-step title="上传校验" />
      <el-step title="确认导入" />
    </el-steps>
    <p v-else class="ts-text-secondary ts-import__steps">
      第 {{ activeStep + 1 }} / 3 步 · {{ STEP_TITLES[activeStep] }}
    </p>

    <div class="ts-import__block">
      <div class="ts-import__block-head">
        <span class="ts-import__block-title">① 下载模板</span>
        <el-button link type="primary" @click="handleDownloadTemplate">下载导入模板(.xlsx)</el-button>
      </div>
      <p class="ts-text-hint">
        模板含表头与一行示例数据（示例行标注「导入前删除」，上传时自动忽略）。一级分类填名称、按名匹配。
      </p>
    </div>

    <div class="ts-import__block">
      <div class="ts-import__block-head">
        <span class="ts-import__block-title">② 上传校验</span>
        <div class="ts-import__upload">
          <el-upload :auto-upload="false" :show-file-list="false" accept=".xlsx" :on-change="handleFileChange">
            <el-button size="small">选择文件</el-button>
          </el-upload>
          <el-button size="small" type="primary" :loading="previewing" :disabled="!file" @click="handlePreview">
            开始校验
          </el-button>
        </div>
      </div>
      <p v-if="selectedName" class="ts-text-secondary ts-import__file">已选择：{{ selectedName }}</p>
      <p v-if="preview" class="ts-import__summary">
        共 {{ preview.totalCount }} 行，通过
        <span class="ts-import__ok">{{ preview.successCount }}</span> 行，失败
        <span class="ts-import__fail">{{ preview.failCount }}</span> 行
      </p>
    </div>

    <div v-if="preview" class="ts-import__block">
      <div class="ts-import__block-head">
        <span class="ts-import__block-title">③ 校验结果</span>
        <el-button v-if="failedRows.length > 0" link type="primary" @click="handleDownloadFailures">
          下载失败明细
        </el-button>
      </div>
      <el-table
        :data="preview.rows"
        border
        size="small"
        max-height="320"
        :row-class-name="rowClassName"
        class="ts-mt-16"
      >
        <el-table-column prop="rowNo" label="行号" width="64" />
        <el-table-column prop="deviceName" label="设备名称" min-width="140" show-overflow-tooltip />
        <el-table-column prop="assetNo" label="资产编号" min-width="120" show-overflow-tooltip />
        <el-table-column prop="primaryCategoryName" label="一级分类" min-width="110" show-overflow-tooltip />
        <el-table-column
          v-if="!isMobile"
          prop="secondaryCategoryName"
          label="二级分类"
          min-width="110"
          show-overflow-tooltip
        />
        <el-table-column label="结果" width="86" align="center">
          <template #default="{ row }">
            <el-tag :type="(row as DeviceImportRowResult).valid ? 'success' : 'danger'" size="small" effect="plain">
              {{ (row as DeviceImportRowResult).valid ? '通过' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="失败原因" min-width="190" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="!(row as DeviceImportRowResult).valid" class="ts-import__fail">
              {{ (row as DeviceImportRowResult).reason }}
            </span>
            <span v-else class="ts-text-hint">-</span>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-alert
      v-if="result"
      class="ts-mt-16"
      :type="result.failedCount > 0 ? 'warning' : 'success'"
      :closable="false"
      :title="`成功导入 ${result.importedCount} 台`"
      :description="
        result.failedCount > 0
          ? `另有 ${result.failedCount} 行导入失败，可下载失败明细修正后重导。`
          : '全部校验通过的数据行均已导入。'
      "
    />
    <div v-if="result && remainingFailures.length > 0" class="ts-import__actions">
      <el-button link type="primary" @click="handleDownloadFailures">下载失败明细</el-button>
    </div>

    <template #footer>
      <el-button @click="visible = false">{{ result ? '完成' : '关闭' }}</el-button>
      <el-button
        v-if="preview && !result"
        type="primary"
        :loading="executing"
        :disabled="validRows.length === 0"
        @click="handleExecute"
      >
        确认导入（{{ validRows.length }} 条）
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.ts-import__steps {
  margin-bottom: 16px;
}

.ts-import__block {
  padding: 12px 0;
  border-bottom: 1px dashed var(--ts-border);
}

.ts-import__block:last-of-type {
  border-bottom: none;
}

.ts-import__block-head {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
}

.ts-import__block-title {
  font-size: 14px;
  font-weight: 500;
}

.ts-import__upload {
  display: flex;
  gap: 8px;
}

.ts-import__file {
  margin: 8px 0 0;
  font-size: 12px;
}

.ts-import__summary {
  margin: 8px 0 0;
  font-size: 13px;
}

.ts-import__ok {
  font-weight: 600;
  color: #67c23a;
}

.ts-import__fail {
  font-weight: 600;
  color: #f56c6c;
}

.ts-import__actions {
  margin-top: 8px;
  text-align: right;
}

:deep(.ts-import__row-invalid) td {
  background: #fef0f0 !important;
}
</style>
