<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import type { UploadFile } from 'element-plus'
import { Refresh, Upload } from '@element-plus/icons-vue'
import { upgradeApi } from '@/api/upgrade'
import { useResponsive } from '@/composables/useResponsive'
import {
  UPGRADE_ACTIVE_STATUSES,
  formatPackageSize,
  isUpgradeActive,
  shortSha,
  upgradeHint,
  upgradeTagType,
  type UpgradeOverview,
  type UpgradeTaskItem
} from '@/types/upgrade'

/**
 * 在线一键升级
 *
 * <h2>页面要回答的三个问题</h2>
 * <p>升级页的使用者往往是把系统交付给业务方的人，他在打开这个页面的那一刻最想知道的是：
 * <ol>
 *   <li><b>这个环境能不能在线升级？</b> —— 因此第一块就是能力总览：总开关、
 *       是否配置了外部应用命令、当前版本。生产默认关闭总开关，
 *       若只挂权限码而不显示开关状态，超管会看到一个「能点、点了报错」的界面，
 *       他只会以为系统坏了；</li>
 *   <li><b>现在跑到哪一步了？</b> —— 步骤条 + 进度条 + 一句话提示
 *       （「现在该做什么」比「现在是什么状态」有用得多，见 `upgradeHint`）；</li>
 *   <li><b>出问题怎么退回去？</b> —— 回滚按钮与它生效的前提
 *       （回滚还原的是磁盘文件，仍需重启才生效；且首次部署没有备份可回滚）。</li>
 * </ol>
 *
 * <h2>为什么上传与「应用」是两个按钮</h2>
 * <p>真实运维里这两件事的时机完全不同：上传包不碰运行中的系统（白天也能做），
 * 而应用会替换产物并重启后端（必须放在维护窗口）。合成一个「上传即升级」的按钮，
 * 等于让一次误点直接触发重启，而大文件传输还会把这个窗口拉长到几分钟。
 *
 * <h2>轮询的取舍</h2>
 * <p>只有存在<b>活跃任务</b>时才轮询（4 秒一次），任务到终态或页面隐藏即停。
 * 用一个常驻的定时器「反正也便宜」是不对的：升级页打开着不动是常态，
 * 而稳态下每 4 秒一次请求会持续占用连接与后端线程，也让审计日志被无意义请求填满。
 */
const { isMobile } = useResponsive()

const loading = ref(false)
const overview = ref<UpgradeOverview | null>(null)
const tasks = ref<UpgradeTaskItem[]>([])
const currentTask = ref<UpgradeTaskItem | null>(null)

/** 上传中的文件（仅用于展示文件名与大小，真正的上传在点击「上传并校验」时发生） */
const pendingFile = ref<File | null>(null)
/** 上传进度百分比；-1 表示不在上传中 */
const uploadPercent = ref(-1)
const uploading = ref(false)
const applying = ref(false)
const rollingBack = ref(false)

let pollTimer: ReturnType<typeof setInterval> | null = null

const enabled = computed(() => overview.value?.enabled === true)
const applyConfigured = computed(() => overview.value?.applyCommandConfigured === true)

/** 「待应用」且已配置外部命令 —— 这是唯一应该高亮主按钮的状态 */
const canApply = computed(
  () => enabled.value && applyConfigured.value && currentTask.value?.status === 'READY_TO_APPLY'
)

const maxSizeMb = computed(() => overview.value?.packageMaxSizeMb ?? 300)

/** 步骤条：把后端状态映射到 5 个粗粒度阶段（细粒度状态放进 el-tag） */
const STEPS = [
  { key: 'UPLOAD', title: '上传校验', desc: '结构与哈希' },
  { key: 'BACKUP', title: '备份', desc: '当前产物' },
  { key: 'STAGE', title: '落盘', desc: '解压到 staging' },
  { key: 'APPLY', title: '应用', desc: '替换与重启' },
  { key: 'DONE', title: '完成', desc: '确认生效' }
] as const

const activeStep = computed(() => {
  const status = currentTask.value?.status
  switch (status) {
    case 'PENDING':
    case 'VALIDATING':
      return 0
    case 'BACKING_UP':
      return 1
    case 'STAGING':
      return 2
    case 'READY_TO_APPLY':
      return 3
    case 'APPLYING':
      return 3
    case 'SUCCESS':
    case 'ROLLED_BACK':
    case 'FAILED':
      return 4
    default:
      return 0
  }
})

const stepStatus = computed<'wait' | 'process' | 'finish' | 'error' | 'success'>(() => {
  switch (currentTask.value?.status) {
    case 'SUCCESS':
      return 'finish'
    case 'FAILED':
      return 'error'
    case 'ROLLED_BACK':
      return 'error'
    default:
      return 'process'
  }
})

async function loadOverview(): Promise<void> {
  overview.value = await upgradeApi.overview()
}

async function loadTasks(): Promise<void> {
  tasks.value = await upgradeApi.tasks()
}

/** 拉一次总览 + 历史；若存在活跃任务，把它设为当前关注对象 */
async function refreshAll(showLoading = false): Promise<void> {
  if (showLoading) {
    loading.value = true
  }
  try {
    await Promise.all([loadOverview(), loadTasks()])
    const activeNo = overview.value?.activeTaskNo
    if (activeNo && currentTask.value?.taskNo !== activeNo) {
      currentTask.value = await upgradeApi.detail(activeNo)
    }
    syncPolling()
  } finally {
    loading.value = false
  }
}

/** 轮询仅在「当前任务处于活跃状态」时开启 */
function syncPolling(): void {
  const needPoll = isUpgradeActive(currentTask.value?.status)
  if (needPoll && pollTimer == null) {
    pollTimer = setInterval(() => {
      void pollCurrent()
    }, 4000)
  } else if (!needPoll && pollTimer != null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

async function pollCurrent(): Promise<void> {
  const taskNo = currentTask.value?.taskNo
  if (!taskNo) {
    return
  }
  try {
    currentTask.value = await upgradeApi.detail(taskNo)
    // 任务进入终态（或从「应用前」跳到终态）时，历史与总览都需要更新
    if (!isUpgradeActive(currentTask.value.status)) {
      await Promise.all([loadOverview(), loadTasks()])
    }
    syncPolling()
  } catch {
    // 升级过程中后端会真的重启，期间 502/超时都是预期内的 —— 静默重试，
    // 弹一次「无法连接后端服务」只会让人误以为升级失败
  }
}

function onFileChange(file: UploadFile): void {
  pendingFile.value = (file.raw as File | undefined) ?? null
}

function onFileRemove(): void {
  pendingFile.value = null
}

/** 前端前置校验：只挡明显不合规的，真正的判定（zip 结构 / manifest / 哈希）都由服务端完成 */
function precheck(file: File): string | null {
  if (!file.name.toLowerCase().endsWith('.zip')) {
    return '升级包必须是 .zip 文件'
  }
  if (file.size > maxSizeMb.value * 1024 * 1024) {
    return `升级包 ${formatPackageSize(file.size)} 超过上限 ${maxSizeMb.value}MB`
  }
  return null
}

async function submitUpload(): Promise<void> {
  const file = pendingFile.value
  if (!file) {
    ElMessage.warning('请先选择升级包')
    return
  }
  const problem = precheck(file)
  if (problem) {
    ElMessage.warning(problem)
    return
  }

  uploading.value = true
  uploadPercent.value = 0
  try {
    const task = await upgradeApi.upload(file, (percent) => {
      uploadPercent.value = percent
    })
    currentTask.value = task
    pendingFile.value = null
    ElMessage.success(`升级包已校验通过（目标版本 ${task.targetVersion}），可择机应用`)
    await Promise.all([loadOverview(), loadTasks()])
    syncPolling()
  } catch {
    // 失败提示由响应拦截器统一给出（含服务端的具体原因：哈希不匹配 / 缺 manifest / 路径不安全…）
    await refreshAll()
  } finally {
    uploading.value = false
    uploadPercent.value = -1
  }
}

async function applyNow(): Promise<void> {
  const task = currentTask.value
  if (!task) {
    return
  }
  try {
    await ElMessageBox.confirm(
      `即将应用版本 ${task.targetVersion}。后端会在替换过程中重启，期间服务短暂不可用。是否继续？`,
      '确认应用升级',
      { type: 'warning', confirmButtonText: '立即应用', cancelButtonText: '取消' }
    )
  } catch {
    return
  }

  applying.value = true
  try {
    currentTask.value = await upgradeApi.apply(task.taskNo)
    ElMessage.info('已发起应用，页面会持续查询结果')
    syncPolling()
  } finally {
    applying.value = false
  }
}

async function rollback(): Promise<void> {
  const task = currentTask.value
  if (!task) {
    return
  }
  try {
    await ElMessageBox.confirm(
      `将把当前产物还原为该任务备份的版本（${task.sourceVersion || '升级前版本'}）。`
        + '还原的是磁盘文件，仍需重启后端 / 重建容器后生效。是否继续？',
      '确认回滚',
      { type: 'warning', confirmButtonText: '回滚', cancelButtonText: '取消' }
    )
  } catch {
    return
  }

  rollingBack.value = true
  try {
    currentTask.value = await upgradeApi.rollback(task.taskNo)
    ElMessage.success('已回滚，请安排重启以生效')
    await refreshAll()
  } finally {
    rollingBack.value = false
  }
}

function focusTask(row: UpgradeTaskItem): void {
  currentTask.value = row
  syncPolling()
}

/** 页面被切到后台时暂停轮询：没人看的页面不该继续消耗请求 */
function onVisibilityChange(): void {
  if (document.hidden) {
    if (pollTimer != null) {
      clearInterval(pollTimer)
      pollTimer = null
    }
  } else {
    syncPolling()
  }
}

onMounted(async () => {
  await refreshAll(true)
  document.addEventListener('visibilitychange', onVisibilityChange)
})

onUnmounted(() => {
  if (pollTimer != null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
  document.removeEventListener('visibilitychange', onVisibilityChange)
})

/** 供模板使用（避免在模板里直接引用 types 的函数） */
const activeStatuses = UPGRADE_ACTIVE_STATUSES
</script>

<template>
  <div v-loading="loading" class="upgrade-page">
    <el-alert
      v-if="overview && !enabled"
      class="upgrade-page__notice"
      type="warning"
      show-icon
      :closable="false"
      title="在线升级功能未启用"
    >
      <template #default>
        <div>
          本环境尚未开启在线升级（<code>app.upgrade.enabled=false</code>）。
          这是<b>生产环境的默认取向</b>：升级接口能替换服务器上的可执行文件，
          等价于代码执行能力，需要显式开启。
        </div>
        <div class="upgrade-page__notice-tip">
          启用方式：在部署配置中设置 <code>UPGRADE_ENABLED=true</code>，
          并配置 <code>UPGRADE_APPLY_COMMAND</code> 指向编排脚本
          （见 <code>deploy/ha/scripts/upgrade-apply.sh</code> 与部署手册「在线升级」章节）。
        </div>
      </template>
    </el-alert>

    <el-card class="upgrade-page__card" shadow="never">
      <template #header>
        <div class="upgrade-page__card-head">
          <span>能力总览</span>
          <el-button :icon="Refresh" text :loading="loading" @click="refreshAll(true)">刷新</el-button>
        </div>
      </template>
      <el-descriptions :column="isMobile ? 1 : 4" border>
        <el-descriptions-item label="当前版本">
          {{ overview?.currentVersion || '（未知，首次部署）' }}
        </el-descriptions-item>
        <el-descriptions-item label="在线升级">
          <el-tag :type="enabled ? 'success' : 'info'" size="small">
            {{ enabled ? '已启用' : '未启用' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="外部应用命令">
          <el-tag :type="applyConfigured ? 'success' : 'warning'" size="small">
            {{ applyConfigured ? '已配置' : '未配置' }}
          </el-tag>
          <span v-if="!applyConfigured" class="upgrade-page__muted">
            （上传后停在「待应用」，需运维手动应用）
          </span>
        </el-descriptions-item>
        <el-descriptions-item label="回滚">
          <el-tag :type="overview?.rollbackEnabled ? 'success' : 'info'" size="small">
            {{ overview?.rollbackEnabled ? '允许' : '已关闭' }}
          </el-tag>
        </el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-card class="upgrade-page__card" shadow="never">
      <template #header>
        <div class="upgrade-page__card-head">
          <span>上传升级包</span>
          <span class="upgrade-page__muted">
            包内需含 manifest.json、backend.jar 与 frontend/dist/**，上限 {{ maxSizeMb }}MB
          </span>
        </div>
      </template>

      <el-upload
        drag
        :auto-upload="false"
        :limit="1"
        :show-file-list="false"
        accept=".zip"
        :disabled="!enabled || uploading"
        :on-change="onFileChange"
        :on-remove="onFileRemove"
      >
        <el-icon class="upgrade-page__upload-icon"><Upload /></el-icon>
        <div class="upgrade-page__upload-text">
          将升级包拖到此处，或<em>点击选择</em>
        </div>
        <template #tip>
          <div class="upgrade-page__muted">
            服务端会校验包结构、逐文件 SHA-256 与路径安全；校验不通过不会触碰任何运行中的文件。
          </div>
        </template>
      </el-upload>

      <div v-if="pendingFile" class="upgrade-page__file">
        <el-tag type="info" size="small">已选择</el-tag>
        <span class="upgrade-page__file-name">{{ pendingFile.name }}</span>
        <span class="upgrade-page__muted">{{ formatPackageSize(pendingFile.size) }}</span>
      </div>

      <el-progress
        v-if="uploadPercent >= 0"
        class="upgrade-page__progress"
        :percentage="uploadPercent"
        :stroke-width="14"
      />

      <div class="upgrade-page__actions">
        <el-button
          type="primary"
          :icon="Upload"
          :disabled="!enabled || !pendingFile"
          :loading="uploading"
          @click="submitUpload"
        >
          上传并校验
        </el-button>
        <el-button
          v-if="pendingFile"
          :disabled="uploading"
          @click="pendingFile = null"
        >
          清除
        </el-button>
      </div>
    </el-card>

    <el-card v-if="currentTask" class="upgrade-page__card" shadow="never">
      <template #header>
        <div class="upgrade-page__card-head">
          <span>当前任务</span>
          <el-tag :type="upgradeTagType(currentTask.status)" size="small">
            {{ currentTask.statusLabel }}
          </el-tag>
        </div>
      </template>

      <el-steps
        class="upgrade-page__steps"
        :active="activeStep"
        :status="stepStatus"
        :direction="isMobile ? 'vertical' : 'horizontal'"
        align-center
      >
        <el-step v-for="step in STEPS" :key="step.key" :title="step.title" :description="step.desc" />
      </el-steps>

      <el-progress
        class="upgrade-page__progress"
        :percentage="currentTask.progress ?? 0"
        :status="currentTask.status === 'FAILED' ? 'exception' : undefined"
        :stroke-width="14"
      />

      <el-alert
        class="upgrade-page__hint"
        :type="currentTask.status === 'FAILED' ? 'error' : 'info'"
        :closable="false"
        show-icon
        :title="upgradeHint(currentTask, applyConfigured)"
      >
        <div v-if="currentTask.message" class="upgrade-page__hint-detail">
          {{ currentTask.message }}
        </div>
      </el-alert>

      <el-descriptions class="upgrade-page__detail" :column="isMobile ? 1 : 3" border size="small">
        <el-descriptions-item label="任务号">{{ currentTask.taskNo }}</el-descriptions-item>
        <el-descriptions-item label="版本">
          {{ currentTask.sourceVersion || '（首次部署）' }} → {{ currentTask.targetVersion }}
        </el-descriptions-item>
        <el-descriptions-item label="升级包">
          {{ currentTask.packageName }}（{{ formatPackageSize(currentTask.packageSize) }}）
        </el-descriptions-item>
        <el-descriptions-item label="包校验和">
          <span class="upgrade-page__mono">{{ shortSha(currentTask.packageSha256) }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="发起人">{{ currentTask.operatorName || '—' }}</el-descriptions-item>
        <el-descriptions-item label="时间">
          {{ currentTask.startedAt || currentTask.createdAt || '—' }}
        </el-descriptions-item>
      </el-descriptions>

      <div class="upgrade-page__actions">
        <el-button
          type="primary"
          :disabled="!canApply"
          :loading="applying"
          @click="applyNow"
        >
          立即应用
        </el-button>
        <el-button
          type="danger"
          plain
          :disabled="!currentTask.rollbackable"
          :loading="rollingBack"
          @click="rollback"
        >
          回滚
        </el-button>
        <el-button :icon="Refresh" @click="pollCurrent">刷新状态</el-button>
      </div>
    </el-card>

    <el-card class="upgrade-page__card" shadow="never">
      <template #header>
        <div class="upgrade-page__card-head">
          <span>升级历史</span>
          <span class="upgrade-page__muted">点击任意一行可查看该任务的详情与操作</span>
        </div>
      </template>

      <el-table
        :data="tasks"
        size="small"
        row-key="taskNo"
        :highlight-current-row="true"
        @row-click="focusTask"
      >
        <el-table-column prop="taskNo" label="任务号" min-width="190" show-overflow-tooltip />
        <el-table-column label="版本" min-width="150">
          <template #default="{ row }">
            <span>{{ (row as UpgradeTaskItem).sourceVersion || '—' }} → {{ (row as UpgradeTaskItem).targetVersion }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" min-width="120">
          <template #default="{ row }">
            <el-tag :type="upgradeTagType((row as UpgradeTaskItem).status)" size="small">
              {{ (row as UpgradeTaskItem).statusLabel }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="packageName" label="升级包" min-width="180" show-overflow-tooltip />
        <el-table-column label="发起人" min-width="110">
          <template #default="{ row }">{{ (row as UpgradeTaskItem).operatorName || '—' }}</template>
        </el-table-column>
        <el-table-column label="时间" min-width="170">
          <template #default="{ row }">
            {{ (row as UpgradeTaskItem).createdAt || '—' }}
          </template>
        </el-table-column>
        <el-table-column label="活跃" width="70" align="center">
          <template #default="{ row }">
            <el-tag v-if="activeStatuses.includes((row as UpgradeTaskItem).status)" type="primary" size="small">
              进行中
            </el-tag>
            <span v-else class="upgrade-page__muted">—</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.upgrade-page {
  padding: 16px;
}

.upgrade-page__notice {
  margin-bottom: 16px;
}

.upgrade-page__notice-tip {
  margin-top: 8px;
  font-size: 13px;
}

.upgrade-page__card {
  margin-bottom: 16px;
}

.upgrade-page__card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.upgrade-page__muted {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.upgrade-page__upload-icon {
  font-size: 40px;
  color: var(--el-text-color-placeholder);
}

.upgrade-page__upload-text {
  color: var(--el-text-color-regular);
}

.upgrade-page__file {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
  flex-wrap: wrap;
}

.upgrade-page__file-name {
  font-weight: 600;
  word-break: break-all;
}

.upgrade-page__progress {
  margin-top: 16px;
}

.upgrade-page__steps {
  margin-bottom: 8px;
}

.upgrade-page__hint {
  margin-top: 12px;
}

.upgrade-page__hint-detail {
  margin-top: 4px;
  font-size: 13px;
  word-break: break-all;
}

.upgrade-page__detail {
  margin-top: 12px;
}

.upgrade-page__mono {
  font-family: var(--el-font-family-monospace, monospace);
}

.upgrade-page__actions {
  display: flex;
  gap: 8px;
  margin-top: 16px;
  flex-wrap: wrap;
}
</style>
