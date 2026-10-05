<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { inventoryApi } from '@/api/inventory'
import { cameraUnavailableReason, isCameraSupported, useQrScanner } from '@/composables/useQrScanner'
import {
  INVENTORY_CHECK_OPTIONS,
  INVENTORY_FILTER_UNCHECKED,
  inventoryCheckLabel,
  inventoryCheckTagType,
  inventoryStatusTagType,
  isInventoryInProgress
} from '@/types/inventory'
import type { InventoryCheckCode, InventoryItemRow, InventoryTaskItem } from '@/types/inventory'

/**
 * 设备盘点 · 扫码核对（P2）
 *
 * <h2>为什么是「先选结果，再连续扫」</h2>
 * <p>现场的真实节奏是：绝大多数设备都在库，遇到对不上的才是个例。
 * 所以顶部先选一个当前结果（默认「在库」），之后每扫一台就按它登记一台，
 * 遇到缺失的切一次结果继续扫 —— 比「扫一台选一次」少一半操作。
 *
 * <h2>扫码失败不是死路</h2>
 * <p>摄像头只在安全上下文（https / localhost）下可用，且现场可能遇到标签磨损、光线不足。
 * 因此手动输入资产编号这条通路**始终可用**，扫不出也能把这一台盘完。
 *
 * <h2>重复核对</h2>
 * <p>同一台重复扫会以上一次为准覆盖（后端 update），这是刻意的：
 * 现场发现「刚扫错了想改」远比「防重复提交」更常见。
 */

const route = useRoute()
const router = useRouter()

const taskId = computed(() => Number(route.params.id))

const task = ref<InventoryTaskItem | null>(null)
const loading = ref(false)
/** 当前要登记的结果（默认在库 —— 绝大多数设备都在库，这是最省操作的选择） */
const checkResult = ref<InventoryCheckCode>('IN_PLACE')
const remark = ref('')
const manualCode = ref('')
const submitting = ref(false)
/** 最近核对（本地留存，方便现场回看刚才扫了什么） */
const recent = ref<InventoryItemRow[]>([])
/** 待盘清单（尚未核对的前若干台，无摄像头时按它手工盘） */
const pending = ref<InventoryItemRow[]>([])

const {
  state: scannerState,
  errorMessage: scannerError,
  start: startScanner,
  stop: stopScanner
} = useQrScanner('ts-inventory-reader')

const cameraSupported = isCameraSupported()
const cameraHint = cameraUnavailableReason()
const scanning = computed(() => scannerState.value === 'RUNNING')
const editable = computed(() => isInventoryInProgress(task.value?.status))
const busy = computed(() => submitting.value)

async function loadTask(): Promise<void> {
  try {
    task.value = await inventoryApi.detail(taskId.value)
  } catch {
    task.value = null
  }
}

async function loadPending(): Promise<void> {
  try {
    const res = await inventoryApi.items(taskId.value, {
      page: 1,
      size: 20,
      checkResult: INVENTORY_FILTER_UNCHECKED
    })
    pending.value = res.records
  } catch {
    pending.value = []
  }
}

/**
 * 登记一台。
 *
 * <p>提交后**只重拉任务计数与待盘清单**，不整页刷新 —— 手机上每盘一台都重拉全表
 * 会很卡，而且滚动位置会丢（盘点人正对着某一屏在逐条核对）。
 */
async function submitCheck(assetNo: string): Promise<void> {
  const code = assetNo.trim()
  if (!code || !editable.value) {
    return
  }
  submitting.value = true
  try {
    const row = await inventoryApi.check(taskId.value, {
      assetNo: code,
      checkResult: checkResult.value,
      remark: remark.value.trim() || null
    })
    recent.value = [row, ...recent.value.filter((item) => item.id !== row.id)].slice(0, 8)
    ElMessage.success(`已登记：${row.deviceName}（${inventoryCheckLabel(row.checkResult)}）`)
    manualCode.value = ''
    await Promise.all([loadTask(), loadPending()])
  } catch {
    // 请求层已统一提示；「不在本次范围内」后端有明确文案
  } finally {
    submitting.value = false
  }
}

/** 扫码识别到内容：登记后若仍处于持续扫码状态，则立刻继续扫下一台 */
async function onDecoded(text: string): Promise<void> {
  await submitCheck(text)
  if (scanning.value) {
    await startScanner(onDecoded)
  }
}

async function submitManual(): Promise<void> {
  await stopScanner()
  await submitCheck(manualCode.value)
}

async function toggleCamera(): Promise<void> {
  if (scanning.value) {
    await stopScanner()
    return
  }
  await startScanner(onDecoded)
}

/** 一键登记为预置结果（无摄像头时的主通路：对着清单逐条点） */
async function checkRow(row: InventoryItemRow, result: InventoryCheckCode): Promise<void> {
  const previous = checkResult.value
  checkResult.value = result
  await submitCheck(row.assetNo)
  checkResult.value = previous
}

async function handleComplete(): Promise<void> {
  const rest = (task.value?.totalCount ?? 0) - (task.value?.checkedCount ?? 0)
  let note = ''
  try {
    const res = await ElMessageBox.prompt(
      rest > 0
        ? `还有 ${rest} 台尚未核对，确认结束本次盘点？报告会如实显示未核对台数。`
        : '全部设备已核对完毕，确认结束本次盘点？',
      '完成盘点',
      {
        type: rest > 0 ? 'warning' : 'info',
        confirmButtonText: '确认完成',
        cancelButtonText: '取消',
        inputPlaceholder: '盘点结论（选填）',
        inputValidator: () => true
      }
    )
    note = res.value ?? ''
  } catch {
    return
  }
  try {
    await stopScanner()
    await inventoryApi.complete(taskId.value, note.trim() || null)
    ElMessage.success('盘点已完成')
    await loadTask()
  } catch {
    // 请求层已提示
  }
}

onMounted(async () => {
  loading.value = true
  await Promise.all([loadTask(), loadPending()])
  loading.value = false
})
</script>

<template>
  <div v-loading="loading" class="ts-page ts-inv-check">
    <!-- 任务概要 -->
    <section class="ts-card">
      <div class="ts-flex-between ts-inv-check__head">
        <div>
          <h3 class="ts-inv-check__title">
            {{ task?.taskName ?? '盘点任务' }}
            <el-tag v-if="task" :type="inventoryStatusTagType(task.status)" size="small" effect="plain">
              {{ task.statusLabel }}
            </el-tag>
          </h3>
          <p class="ts-text-hint">
            {{ task?.taskNo }} · {{ task?.scopeLabel }}
          </p>
        </div>
        <el-button link type="primary" @click="router.push('/asset/inventory')">返回任务列表</el-button>
      </div>

      <div v-if="task" class="ts-inv-check__progress">
        <el-progress
          :percentage="task.progressPercent"
          :stroke-width="14"
          class="ts-inv-check__bar"
        />
        <div class="ts-inv-check__counts">
          <span>已核对 <b>{{ task.checkedCount }}</b> / {{ task.totalCount }}</span>
          <span>在库 <b>{{ task.inPlaceCount }}</b></span>
          <span :class="{ 'ts-inv-check__danger': task.missingCount > 0 }">缺失 <b>{{ task.missingCount }}</b></span>
          <span>位置不符 <b>{{ task.wrongLocationCount }}</b></span>
        </div>
      </div>
    </section>

    <el-alert
      v-if="task && !editable"
      title="本次盘点已结束（已完成或已取消），只能查看，不能再核对。"
      type="info"
      :closable="false"
      show-icon
    />

    <!-- 核对操作 -->
    <section v-if="editable" class="ts-card">
      <h4 class="ts-inv-check__section">1. 选择本次登记结果</h4>
      <el-radio-group v-model="checkResult" :disabled="busy">
        <el-radio-button v-for="item in INVENTORY_CHECK_OPTIONS" :key="item.value" :value="item.value">
          {{ item.label }}
        </el-radio-button>
      </el-radio-group>
      <el-input
        v-model="remark"
        class="ts-inv-check__remark"
        placeholder="核对说明（选填，如：位置改为 A座2F）"
        maxlength="200"
        clearable
      />

      <h4 class="ts-inv-check__section">2. 扫码登记（每扫一台自动按上面的结果登记）</h4>
      <div v-if="cameraSupported" class="ts-inv-check__viewport">
        <div id="ts-inventory-reader" class="ts-inv-check__reader" :class="{ 'is-idle': !scanning }"></div>
        <p v-if="!scanning && scannerState !== 'STARTING'" class="ts-inv-check__viewport-tip">摄像头未开启</p>
        <p v-else-if="scannerState === 'STARTING'" class="ts-inv-check__viewport-tip">正在启动摄像头…</p>
      </div>
      <el-alert v-else :title="cameraHint" type="warning" :closable="false" show-icon />
      <el-alert
        v-if="scannerState === 'FAILED'"
        :title="scannerError"
        type="error"
        :closable="false"
        show-icon
      />
      <div class="ts-inv-check__actions">
        <el-button v-if="cameraSupported" :disabled="busy" @click="toggleCamera">
          {{ scanning ? '停止扫码' : '开启扫码' }}
        </el-button>
      </div>

      <el-divider content-position="center">或手动输入资产编号</el-divider>
      <div class="ts-inv-check__manual">
        <el-input
          v-model="manualCode"
          placeholder="如 IT-2026-0001"
          clearable
          :disabled="busy"
          @keyup.enter="submitManual"
        />
        <el-button type="primary" :loading="busy" @click="submitManual">登记</el-button>
      </div>
    </section>

    <!-- 最近核对 -->
    <section v-if="recent.length" class="ts-card">
      <h4 class="ts-inv-check__section">最近登记</h4>
      <ul class="ts-inv-check__recent">
        <li v-for="row in recent" :key="row.id" class="ts-inv-check__recent-item">
          <span class="ts-inv-check__recent-name">{{ row.deviceName }}</span>
          <span class="ts-text-hint">{{ row.assetNo }}</span>
          <el-tag :type="inventoryCheckTagType(row.checkResult)" size="small" effect="plain">
            {{ row.checkResultLabel }}
          </el-tag>
        </li>
      </ul>
    </section>

    <!-- 待盘清单 -->
    <section class="ts-card">
      <div class="ts-flex-between">
        <h4 class="ts-inv-check__section">尚未核对（前 {{ pending.length }} 台）</h4>
        <el-button v-if="editable" type="primary" plain @click="handleComplete">完成盘点</el-button>
      </div>
      <p v-if="!pending.length" class="ts-text-hint">本任务范围内已无未核对设备。</p>
      <el-table v-else :data="pending" size="small" border>
        <el-table-column prop="assetNo" label="资产编号" min-width="140" />
        <el-table-column prop="deviceName" label="设备" min-width="160" show-overflow-tooltip />
        <el-table-column prop="storageLocation" label="台账位置" min-width="130" show-overflow-tooltip />
        <el-table-column prop="expectedStatusLabel" label="台账状态" min-width="100" />
        <el-table-column v-if="editable" label="登记" min-width="220">
          <template #default="{ row }">
            <el-button size="small" text type="primary" :disabled="busy" @click="checkRow(row as InventoryItemRow, 'IN_PLACE')">
              在库
            </el-button>
            <el-button size="small" text type="danger" :disabled="busy" @click="checkRow(row as InventoryItemRow, 'MISSING')">
              缺失
            </el-button>
            <el-button
              size="small"
              text
              type="warning"
              :disabled="busy"
              @click="checkRow(row as InventoryItemRow, 'WRONG_LOCATION')"
            >
              位置不符
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>
  </div>
</template>

<style scoped>
.ts-inv-check {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.ts-inv-check__head {
  gap: 12px;
}

.ts-inv-check__title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 4px;
  font-size: 17px;
}

.ts-inv-check__progress {
  margin-top: 12px;
}

.ts-inv-check__bar {
  margin-bottom: 8px;
}

.ts-inv-check__counts {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  font-size: 13px;
  color: var(--el-text-color-regular);
}

.ts-inv-check__danger {
  color: var(--el-color-danger);
  font-weight: 600;
}

.ts-inv-check__section {
  margin: 0 0 10px;
  font-size: 14px;
}

.ts-inv-check__remark {
  margin-top: 10px;
}

.ts-inv-check__viewport {
  position: relative;
  border-radius: 8px;
  overflow: hidden;
  background: #000;
  margin-bottom: 12px;
}

.ts-inv-check__reader {
  width: 100%;
  min-height: 240px;
}

.ts-inv-check__reader.is-idle {
  min-height: 70px;
  background: transparent;
}

.ts-inv-check__viewport-tip {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 0;
  color: #fff;
  background: rgba(0, 0, 0, 0.55);
}

.ts-inv-check__actions {
  display: flex;
  justify-content: center;
}

.ts-inv-check__manual {
  display: flex;
  gap: 8px;
}

.ts-inv-check__recent {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.ts-inv-check__recent-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 8px 10px;
  border: 1px solid var(--ts-border);
  border-radius: 6px;
}

.ts-inv-check__recent-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 手机端（ / -531） */
@media (max-width: 767px) {
  .ts-inv-check__reader {
    min-height: 190px;
  }

  .ts-inv-check__manual {
    flex-direction: column;
  }

  .ts-inv-check__counts {
    gap: 10px 14px;
  }
}
</style>
