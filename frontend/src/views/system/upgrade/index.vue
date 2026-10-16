<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage, ElMessageBox, type UploadFile } from 'element-plus'
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
  type UpgradeMode,
  type UpgradeOverview,
  type UpgradeTaskItem
} from '@/types/upgrade'

/**
 * 在线一键升级
 *
 * <h2>页面要回答的三个问题</h2>
 * <p>升级页的使用者往往是把系统交付给业务方的人，他在打开这个页面的那一刻最想知道的是：
 * <ol>
 *   <li><b>这个环境能不能在线升级？</b> —— 因此第一块就是能力总览：两个入口的开关、
 *       当前版本、运维代理是否就绪。生产默认关闭，若只挂权限码而不显示开关状态，
 *       超管会看到一个「能点、点了报错」的界面，他只会以为系统坏了；</li>
 *   <li><b>现在跑到哪一步了？</b> —— 步骤条 + 进度条 + 一句话提示
 *       （「现在该做什么」比「现在是什么状态」有用得多，见 `upgradeHint`）；</li>
 *   <li><b>出问题怎么退回去？</b> —— 回滚按钮与它生效的前提
 *       （回滚还原的是磁盘文件，仍需重启才生效；且首次部署没有备份可回滚）。</li>
 * </ol>
 *
 * <h2>为什么两个入口常显、且各有独立开关（V51）</h2>
 * <p>V49 时页面按「运维代理是否就绪」二选一显示入口。需求方 2026-10-13 改为
 * <b>两个入口都常显、各自独立开关、默认都关闭</b>，理由有二：
 * ① 二选一让两条路径<b>无法同框验证</b>（想验上传链路只能先把 agent 停掉）；
 * ②「就绪」是运行时状态，入口会随时间跳变，管理员难以预期。
 *
 * <p>「Docker 部署下上传包无效」这件事因此不再靠<b>隐藏入口</b>规避，而是靠
 * ① 卡片内的文字说明 + ② 执行时的显式拦截（点了会明确报错，不会静默）。</p>
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
const { isMobile, isCompact } = useResponsive()

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

/** 「一键在线升级」入口是否已启用（自己的开关，与环境变量取或） */
const onlineEnabled = computed(() => overview.value?.onlineEnabled === true)
/** 「上传升级包」入口是否已启用（自己的开关，与环境变量取或） */
const packageEnabled = computed(() => overview.value?.packageEnabled === true)
/** 两个入口都没开 —— 用于顶部给出「去哪里打开」的整体提示 */
const noneEnabled = computed(() => !onlineEnabled.value && !packageEnabled.value)

const applyConfigured = computed(() => overview.value?.applyCommandConfigured === true)

/** 「待应用」且已配置外部命令 —— 这是唯一应该高亮主按钮的状态 */
const canApply = computed(
  () => packageEnabled.value && applyConfigured.value && currentTask.value?.status === 'READY_TO_APPLY'
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

// ---------------------------------------------------------------------
// V51：两个入口常显，各带自己的开关
// ---------------------------------------------------------------------

/** 环境变量把功能钉在「开」上时，页面开关关不掉 —— 必须置灰并说明，否则像按钮坏了 */
const envForced = computed(() => overview.value?.envForced === true)

/** 运维代理是否就绪 —— 只用于在线升级卡片内的提示与按钮可用性（V51 起不再决定显示哪个入口） */
const agentReady = computed(() => overview.value?.agentReady === true)

/** 两个开关各自的提交中状态（分开，避免拨一个时另一个也在转圈） */
const togglingOnline = ref(false)
const togglingPackage = ref(false)
const onlineStarting = ref(false)

/**
 * 切换某个入口的开关。
 *
 * `mode` 必传：后端按它决定改哪一行 system_config，**不会**用缺省值兜底 ——
 * 传错等于改错另一个开关，而页面上只会看到「怎么那个也变了」。
 */
async function toggleEntry(mode: UpgradeMode, next: boolean): Promise<void> {
  const busy = mode === 'ONLINE' ? togglingOnline : togglingPackage
  busy.value = true
  try {
    await upgradeApi.setEnabled(mode, next)
    const label = mode === 'ONLINE' ? '一键在线升级' : '上传升级包'
    // 提示语里点明「改了立刻生效」：过去这一步要 SSH 改 .env 再重建容器，
    // 管理员的第一反应往往是「我是不是还得重启一下」
    ElMessage.success(next ? `已启用「${label}」（立刻生效，无需重启）` : `已关闭「${label}」`)
  } catch {
    // 由请求层统一提示
  } finally {
    busy.value = false
    await refreshAll()
  }
}

/**
 * el-switch 的 change 事件给的是 `string | number | boolean`，
 * 这里显式收敛成布尔再交给 `toggleEntry`（否则模板里的类型断言会很难读）。
 */
function onOnlineSwitch(value: string | number | boolean): void {
  void toggleEntry('ONLINE', value === true)
}

function onPackageSwitch(value: string | number | boolean): void {
  void toggleEntry('PACKAGE', value === true)
}

async function startOnlineUpgrade(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '将拉取最新镜像并重建后端与前端容器。过程中系统会短暂不可用（约 1 分钟），是否继续？',
      '一键在线升级',
      { type: 'warning', confirmButtonText: '开始升级', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  onlineStarting.value = true
  try {
    await upgradeApi.applyOnline()
    ElMessage.success('已下发升级请求，运维代理正在拉取镜像并重建容器')
    await refreshAll(true)
  } catch {
    // 由请求层统一提示（含「代理未就绪」这类明确原因）
  } finally {
    onlineStarting.value = false
  }
}
</script>

<template>
  <div v-loading="loading" class="upgrade-page">
    <el-alert
      v-if="overview && noneEnabled"
      class="upgrade-page__notice"
      type="warning"
      show-icon
      :closable="false"
      title="两个升级入口都未启用"
    >
      <template #default>
        <div>
          升级接口能替换服务器上的可执行文件，等价于代码执行能力，因此<b>默认关闭</b>。
          本环境目前两个入口都处于关闭状态。
        </div>
        <div class="upgrade-page__notice-tip">
          打开方式：把下面两张卡片右上角的开关拨开即可，开关存在数据库里、改完立刻生效 ——
          <b>不需要去改 .env，也不需要重建容器</b>。两个开关互相独立，可以只开其中一个。
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
        <el-descriptions-item label="升级入口">
          <div class="upgrade-page__entries">
            <el-tag :type="onlineEnabled ? 'success' : 'info'" size="small">
              一键在线升级：{{ onlineEnabled ? '已启用' : '已关闭' }}
            </el-tag>
            <el-tag :type="packageEnabled ? 'success' : 'info'" size="small">
              上传升级包：{{ packageEnabled ? '已启用' : '已关闭' }}
            </el-tag>
          </div>
          <span v-if="envForced" class="upgrade-page__muted">
            （由部署配置强制开启，此处关不掉）
          </span>
        </el-descriptions-item>
        <el-descriptions-item label="运维代理">
          <el-tag :type="agentReady ? 'success' : 'info'" size="small">
            {{ agentReady ? '就绪' : '未就绪' }}
          </el-tag>
          <span v-if="agentReady && overview?.agentVersion" class="upgrade-page__muted">
            （v{{ overview.agentVersion }}）
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
          <span>一键在线升级</span>
          <div class="upgrade-page__switch">
            <span class="upgrade-page__muted">启用</span>
            <el-switch
              :model-value="onlineEnabled"
              :loading="togglingOnline"
              :disabled="envForced || togglingOnline"
              @change="onOnlineSwitch"
            />
          </div>
        </div>
      </template>

      <p class="upgrade-page__muted">
        拉取最新镜像并重建后端与前端容器。系统会把请求交给运维代理（ops-agent）执行，
        不需要你 SSH 上去敲命令。
      </p>

      <el-alert
        v-if="!onlineEnabled"
        class="upgrade-page__hint"
        type="warning"
        :closable="false"
        show-icon
        title="本入口未启用：把卡片右上角的开关拨开即可（改完立刻生效）。"
      />
      <el-alert
        v-else-if="!agentReady"
        class="upgrade-page__hint"
        type="warning"
        :closable="false"
        show-icon
        title="运维代理未就绪：一键升级需要它执行「拉镜像 + 重建容器」。请确认 ops-agent 容器正在运行。"
      />
      <el-alert
        v-else
        class="upgrade-page__hint"
        type="info"
        :closable="false"
        show-icon
        title="升级过程中服务会短暂不可用（重建容器约 1 分钟）；已提交的工单与数据不受影响。"
      />

      <div class="upgrade-page__actions">
        <el-button
          type="primary"
          :icon="Refresh"
          :disabled="!onlineEnabled || !agentReady"
          :loading="onlineStarting"
          @click="startOnlineUpgrade"
        >
          立即升级到最新版
        </el-button>
      </div>
    </el-card>

    <el-card class="upgrade-page__card" shadow="never">
      <template #header>
        <div class="upgrade-page__card-head">
          <span>上传升级包</span>
          <div class="upgrade-page__switch">
            <span class="upgrade-page__muted">启用</span>
            <el-switch
              :model-value="packageEnabled"
              :loading="togglingPackage"
              :disabled="envForced || togglingPackage"
              @change="onPackageSwitch"
            />
          </div>
        </div>
      </template>

      <p class="upgrade-page__muted">
        包内需含 manifest.json、backend.jar 与 frontend/dist/**，上限 {{ maxSizeMb }}MB。
        <b>只服务非容器化部署</b>：容器跑的是镜像，上传的 jar / dist 落在宿主目录里不会被加载 ——
        Docker 部署请用上面的「一键在线升级」。
      </p>

      <el-alert
        v-if="agentReady"
        class="upgrade-page__hint"
        type="info"
        :closable="false"
        show-icon
        title="检测到运维代理正在运行，说明本环境很可能是 Docker 部署：此时上传的升级包不会被加载，请优先用「一键在线升级」。"
      />

      <el-alert
        v-if="!packageEnabled"
        class="upgrade-page__hint"
        type="warning"
        :closable="false"
        show-icon
        title="本入口未启用：把卡片右上角的开关拨开即可（改完立刻生效）。"
      />

      <el-upload
        drag
        :auto-upload="false"
        :limit="1"
        :show-file-list="false"
        accept=".zip"
        :disabled="!packageEnabled || uploading"
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
          :disabled="!packageEnabled || !pendingFile"
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
        <!--
          实时进展行：agent 在「应用」阶段每换一个动作就写一行（拉镜像 → 重建容器 → 等健康）。
          刻意放在 message 之上 —— 前者是「此刻正在发生什么」（会变），后者是「已下发的指令」（不变）。
          先看到会变的那条，管理员才不会被一个不动的进度条误导成「界面卡死了」。
        -->
        <div v-if="currentTask.progressText" class="upgrade-page__hint-live">
          <span class="upgrade-page__live-dot" aria-hidden="true" />
          {{ currentTask.progressText }}
        </div>
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
        v-if="!isCompact"
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

      <!--
        紧凑档降级为卡片（≤1023px，内屏 / 手机）。
        本表 7 列，在 840px 内屏上平均列宽不足 92px —— 表格已不可读，
        与 TablePage 各页在 ≤767px 的降级是同一处置，只是本页列更宽、阈值提前到 1023。
      -->
      <div v-else class="upgrade-page__history-cards">
        <div
          v-for="row in tasks"
          :key="row.taskNo"
          class="upgrade-page__history-card"
          @click="focusTask(row)"
        >
          <div class="upgrade-page__history-card-head">
            <strong class="upgrade-page__history-card-title">{{ row.packageName }}</strong>
            <el-tag :type="upgradeTagType(row.status)" size="small">{{ row.statusLabel }}</el-tag>
          </div>
          <div class="upgrade-page__history-card-row">
            <span class="upgrade-page__muted">版本</span>
            <span>{{ row.sourceVersion || '—' }} → {{ row.targetVersion }}</span>
          </div>
          <div class="upgrade-page__history-card-row">
            <span class="upgrade-page__muted">任务号</span>
            <span class="upgrade-page__mono">{{ row.taskNo }}</span>
          </div>
          <div class="upgrade-page__history-card-row">
            <span class="upgrade-page__muted">发起人</span>
            <span>{{ row.operatorName || '—' }}</span>
          </div>
          <div class="upgrade-page__history-card-row">
            <span class="upgrade-page__muted">时间</span>
            <span>{{ row.createdAt || '—' }}</span>
          </div>
        </div>
        <el-empty v-if="tasks.length === 0" :image-size="70" description="暂无升级记录" />
      </div>
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

.upgrade-page__switch {
  display: flex;
  align-items: center;
  gap: 8px;
}

.upgrade-page__entries {
  display: flex;
  align-items: center;
  gap: 8px;
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

/*
  agent 的实时进展。用主色 + 加粗与下面的静态说明拉开层次：
  「这条在动」是本次改动唯一要传达的信息，不能让两条文字看起来一样重。
*/
.upgrade-page__hint-live {
  display: flex;
  align-items: baseline;
  gap: 6px;
  margin-bottom: 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--el-color-primary);
  word-break: break-all;
}

.upgrade-page__live-dot {
  flex: 0 0 auto;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--el-color-primary);
  animation: upgrade-live-pulse 1.2s ease-in-out infinite;
}

@keyframes upgrade-live-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.25; }
}

/* 呼吸点只是点缀、不是信息本身 —— 尊重系统的「减少动态效果」偏好 */
@media (prefers-reduced-motion: reduce) {
  .upgrade-page__live-dot {
    animation: none;
  }
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

/* ---------- 升级历史的紧凑档卡片（≤1023px 替代表格） ---------- */
.upgrade-page__history-cards {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.upgrade-page__history-card {
  border: 1px solid var(--ts-border);
  border-radius: var(--ts-radius);
  background: var(--ts-surface);
  padding: 12px;
  cursor: pointer;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}

.upgrade-page__history-card:hover {
  border-color: var(--ts-accent);
  box-shadow: 0 2px 8px rgb(0 0 0 / 6%);
}

.upgrade-page__history-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}

.upgrade-page__history-card-title {
  font-size: var(--ts-fs-body);
  font-weight: 600;
  word-break: break-all;
}

.upgrade-page__history-card-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 4px 0;
  font-size: var(--ts-fs-caption);
  line-height: 1.6;
}

.upgrade-page__history-card-row > .upgrade-page__muted:first-child {
  flex: 0 0 56px;
}
</style>
