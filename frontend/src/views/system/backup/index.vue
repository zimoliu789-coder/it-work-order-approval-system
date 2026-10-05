<template>
  <div class="ts-backup">
    <el-card shadow="never">
      <template #header>
        <div class="ts-backup__head">
          <span class="ts-backup__title">备份记录</span>
          <el-button type="primary" :loading="running" @click="handleRun">立即备份</el-button>
        </div>
      </template>

      <!--
        概览：把「最后一次成功备份时间」放在最显眼的位置。
        这是本功能唯一的健康判据 —— 开关开着而 mysqldump 已经连续失败三周，
        界面上仍然一切正常，直到真的需要恢复那天才发现一个可用归档都没有。
      -->
      <el-alert
        v-if="overview"
        class="ts-backup__hero"
        :type="heroType"
        :closable="false"
        show-icon
        :title="heroTitle"
        :description="heroDescription"
      />

      <el-descriptions v-if="overview" class="ts-mt-16" :column="isMobile ? 1 : 3" border size="small">
        <el-descriptions-item label="自动备份">
          {{ overview.enabled ? '已启用' : '未启用' }}
        </el-descriptions-item>
        <el-descriptions-item label="备份时刻">
          {{ formatBackupHour(overview.backupHour) }}
        </el-descriptions-item>
        <el-descriptions-item label="保留天数">{{ overview.retentionDays }} 天</el-descriptions-item>
        <el-descriptions-item label="备份目录" :span="isMobile ? 1 : 3">
          {{ overview.dir }}
          <el-tag v-if="overview.dirWritable" class="ts-ml-8" size="small" type="success" effect="plain">
            可写
          </el-tag>
          <el-tag v-else class="ts-ml-8" size="small" type="danger" effect="plain">不可写</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="最后成功备份" :span="isMobile ? 1 : 2">
          {{ overview.lastSuccessAt ?? '从未成功过' }}
          <span v-if="overview.lastSuccessFile" class="ts-text-hint">
            （{{ overview.lastSuccessFile }}，{{ overview.lastSuccessSizeText }}）
          </span>
        </el-descriptions-item>
        <el-descriptions-item label="今天是否已自动备份">
          {{ overview.scheduledToday ? '是' : '否' }}
        </el-descriptions-item>
        <el-descriptions-item label="调度说明" :span="isMobile ? 1 : 3">
          {{ overview.scheduleHint }}
        </el-descriptions-item>
        <el-descriptions-item v-if="overview.lastFailureAt" label="最近一次失败" :span="isMobile ? 1 : 3">
          <span class="ts-backup__failure">{{ overview.lastFailureAt }}</span>
          {{ overview.lastFailureReason }}
        </el-descriptions-item>
      </el-descriptions>

      <el-table
        v-loading="loading"
        class="ts-mt-16"
        :data="records"
        size="small"
        row-key="id"
        :empty-text="loading ? '加载中…' : '暂无备份记录'"
      >
        <el-table-column prop="startedAt" label="开始时间" min-width="160" />
        <el-table-column label="触发方式" min-width="100">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :type="backupTriggerTagType((row as BackupRecordItem).triggerType)">
              {{ (row as BackupRecordItem).triggerLabel }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="fileName" label="文件" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            {{ (row as BackupRecordItem).fileName || '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="sizeText" label="大小" min-width="100" />
        <el-table-column prop="durationText" label="耗时" min-width="100" />
        <el-table-column label="状态" min-width="100">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :type="backupStatusTagType((row as BackupRecordItem).status)">
              {{ (row as BackupRecordItem).statusLabel }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作人" min-width="100">
          <template #default="{ row }">
            {{ (row as BackupRecordItem).operatorName ?? '-' }}
          </template>
        </el-table-column>
        <el-table-column label="失败原因" min-width="260" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="(row as BackupRecordItem).errorMessage" class="ts-backup__failure">
              {{ (row as BackupRecordItem).errorMessage }}
            </span>
            <span v-else>-</span>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-if="total > 0"
        class="ts-mt-16"
        layout="total, prev, pager, next"
        :total="total"
        :current-page="page"
        :page-size="size"
        @current-change="handlePageChange"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
/**
 * 数据库备份记录页（P0）
 *
 * <h2>这个页面要回答的唯一问题</h2>
 * <p><b>「备份到底有没有在跑」</b>。所以首屏最显眼的是「最后一次成功备份时间」，
 * 而不是开关状态 —— 开关开着但一次都没成功过，是本功能最危险的失效形态。
 *
 * <h2>为什么权限不在前端判</h2>
 * <p>本页只有 {@code backup:view} 才进得来（路由与菜单都由该码控制），
 * 而能看到本页的人必然是超管（该码不进任何角色的默认权限集）。
 * 「立即备份」的写权限由后端 {@code backup:manage} 独立把关，
 * 前端重复判一次只会引入两套口径 —— 若将来把查看权限开放给 admin 而忘了同步这里，
 * 按钮会「显示但点不动」，比直接不给更让人困惑。
 */
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { backupApi } from '@/api/backup'
import {
  backupStatusTagType,
  backupTriggerTagType,
  formatBackupHour,
  type BackupOverview,
  type BackupRecordItem
} from '@/types/backup'

const loading = ref(false)
const running = ref(false)
const records = ref<BackupRecordItem[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const overview = ref<BackupOverview | null>(null)

/** 窄屏判定：与全站其它页面同一断点（<= 768px） */
const isMobile = computed(() => window.innerWidth <= 768)

/**
 * 首屏横幅：按「严重程度」从高到低取第一条真正需要处置的事实。
 *
 * 顺序刻意如此 —— 若把「未启用」放最后，一个目录不可写的环境会先看到
 * 「未启用」的提示（而它可能只是 Docker 部署用侧车备份的正常形态），
 * 于是真正的问题被无害信息盖住。
 */
const heroType = computed<'success' | 'warning' | 'error' | 'info'>(() => {
  const data = overview.value
  if (!data) {
    return 'info'
  }
  if (!data.dirWritable) {
    return 'error'
  }
  if (data.lastFailureAt) {
    return 'error'
  }
  if (!data.enabled) {
    return 'warning'
  }
  if (!data.lastSuccessAt) {
    return 'warning'
  }
  return 'success'
})

const heroTitle = computed(() => {
  const data = overview.value
  if (!data) {
    return ''
  }
  if (!data.dirWritable) {
    return '备份目录不可写，备份一定不会成功'
  }
  if (data.lastFailureAt) {
    return '最近一次备份失败'
  }
  if (!data.enabled) {
    return '自动备份未启用'
  }
  if (!data.lastSuccessAt) {
    return '尚未成功备份过'
  }
  return '备份正常'
})

const heroDescription = computed(() => {
  const data = overview.value
  if (!data) {
    return ''
  }
  if (!data.dirWritable) {
    return `当前目录：${data.dir}。请检查目录权限或 NAS 挂载状态，或到「系统参数 → 高级参数」修改备份目录。`
  }
  if (data.lastFailureAt) {
    return `失败时间：${data.lastFailureAt}；原因：${data.lastFailureReason ?? '-'}。历史归档因「先成功后清理」的规则未被删除。`
  }
  if (!data.enabled) {
    return 'Docker 部署请使用 deploy/backup 备份容器，两者不要同时开启；非容器部署可到「系统参数 → 高级参数」打开本开关。'
  }
  if (!data.lastSuccessAt) {
    return data.scheduleHint
  }
  return `最后一次成功备份：${data.lastSuccessAt}（${data.lastSuccessFile ?? '-'}）。${data.scheduleHint}`
})

onMounted(load)

async function load(): Promise<void> {
  loading.value = true
  try {
    const [pageResult, overviewResult] = await Promise.all([
      backupApi.page(page.value, size.value),
      backupApi.overview()
    ])
    records.value = pageResult.records
    total.value = pageResult.total
    overview.value = overviewResult
  } catch {
    // 错误由请求层统一提示
  } finally {
    loading.value = false
  }
}

function handlePageChange(next: number): void {
  page.value = next
  void load()
}

/**
 * 立即备份（同步执行）。
 *
 * ⚠️ 后端对「环境类失败」返回的是 status=FAILED 的**正常响应**而不是异常，
 * 因此这里必须判 `status`，不能只看请求有没有报错 ——
 * 否则一次「目录不可写、什么都没备份出来」会以绿色成功提示结束。
 */
async function handleRun(): Promise<void> {
  running.value = true
  try {
    const result = await backupApi.run()
    if (result.status === 'SUCCESS') {
      ElMessage.success(`备份成功：${result.fileName}（${result.sizeText}）`)
    } else {
      ElMessage.warning(`备份失败：${result.errorMessage ?? '未知原因'}（详见下方记录）`)
    }
    page.value = 1
    await load()
  } catch {
    // 请求层已提示（如「已有备份正在执行」）
  } finally {
    running.value = false
  }
}
</script>

<style scoped>
.ts-backup__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.ts-backup__title {
  font-weight: 600;
}

.ts-backup__hero {
  margin-bottom: 0;
}

.ts-backup__failure {
  color: var(--el-color-danger);
}

.ts-mt-16 {
  margin-top: 16px;
}

.ts-ml-8 {
  margin-left: 8px;
}
</style>
