<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { orderApi } from '@/api/order'
import { cameraUnavailableReason, isCameraSupported, useQrScanner } from '@/composables/useQrScanner'
import {
  SCAN_ACTION_BORROW,
  SCAN_ACTION_RETURN,
  SCAN_ACTION_VIEW
} from '@/types/scan'
import type { ScanLookupResult } from '@/types/scan'

/**
 * 扫码借还（P1 扫码借还）
 *
 * <h2>入口与菜单</h2>
 * <p>刻意**不挂左侧菜单**：按项目既有约定（ 用户拍板「菜单骨架和 B 一起做，
 * 不要先空壳菜单」），扫码是「站在设备前才会用」的动作，挂在一级菜单里只是噪音。
 * 入口放在工作台快捷入口，路由本身仍在（便于手机加到桌面后直接打开）。
 *
 * <h2>分流规则</h2>
 * <p>扫到之后做什么**全部由服务端判定**（`/orders/scan-lookup`），前端只按 `action` 分支：
 * <ul>
 *   <li>{@code BORROW} → 跳申请页并预选该设备</li>
 *   <li>{@code RETURN} → **就地**弹归还确认（不跳页）</li>
 *   <li>{@code VIEW} → 跳我的工单（扫的是单据，只想看进度）</li>
 *   <li>{@code UNAVAILABLE} / {@code NONE} → 就地展示原因</li>
 * </ul>
 *
 * <h2>为什么归还就地做、不跳页</h2>
 * <p>归还的前提是「我已经知道要还哪一单」——扫码已经把这一单定位出来了。
 * 再让用户跳到「我的工单」里从一堆单子中找出那一单，是把已经确定的信息重新丢掉。
 * 就地确认同样有「确认」这一步，不是静默执行。
 */

const router = useRouter()

// 解构为顶层绑定 —— 模板只对顶层 ref 自动解包，写成 scanner.state 会渲染出 ref 对象本身
const {
  state: scannerState,
  errorMessage: scannerError,
  start: startScanner,
  stop: stopScanner
} = useQrScanner('ts-scan-reader')

const manualCode = ref('')
const looking = ref(false)
const returning = ref(false)
const result = ref<ScanLookupResult | null>(null)

const cameraSupported = isCameraSupported()
const cameraHint = cameraUnavailableReason()

const scanning = computed(() => scannerState.value === 'RUNNING')
const busy = computed(() => looking.value || returning.value)

onMounted(() => {
  // 需求：手机端打开即调摄像头。失败时（非 https / 无权限）会落到手动输入 —— 那条通路始终可用。
  if (cameraSupported) {
    void startScanner(handleScanned)
  }
})

async function lookup(code: string): Promise<void> {
  const trimmed = code.trim()
  if (!trimmed) {
    ElMessage.warning('请输入或扫描资产编号 / 工单号')
    return
  }
  looking.value = true
  try {
    result.value = await orderApi.scanLookup(trimmed)
  } catch {
    // 请求层已统一提示
  } finally {
    looking.value = false
  }
}

function handleScanned(text: string): void {
  manualCode.value = text
  void lookup(text)
}

async function submitManual(): Promise<void> {
  await stopScanner()
  await lookup(manualCode.value)
}

async function toggleCamera(): Promise<void> {
  if (scanning.value) {
    await stopScanner()
    return
  }
  await startScanner(handleScanned)
}

/** 借用：跳申请页并预选设备（申请页读 ?deviceId=） */
function goBorrow(): void {
  const deviceId = result.value?.device?.id
  if (deviceId == null) {
    return
  }
  void router.push({ path: '/order/apply', query: { deviceId: String(deviceId) } })
}

/** 查看：扫的是单据，跳我的工单看进度 */
function goOrder(): void {
  void router.push('/order/mine')
}

/** 归还：就地确认 —— 扫码已经定位到具体某一单，不必再让用户去列表里找 */
async function confirmReturn(): Promise<void> {
  const order = result.value?.order
  if (!order) {
    return
  }
  let note = ''
  try {
    const res = await ElMessageBox.prompt(
      `确认归还设备「${order.deviceName ?? '-'}」？提交后工单进入「待收回」，需等待实际执行人确认收回后，设备才会重新变为可用。`,
      '发起归还',
      {
        type: 'info',
        confirmButtonText: '确认发起归还',
        cancelButtonText: '取消',
        inputPlaceholder: '归还说明（选填，如：外观完好、配件齐全）',
        inputValidator: () => true
      }
    )
    note = res.value ?? ''
  } catch {
    return
  }

  returning.value = true
  try {
    await orderApi.requestReturn(order.id, note.trim() || null)
    ElMessage.success('已发起归还，等待实际执行人确认收回')
    // 重新查询一次：归还后该设备不再是我的在借，结果区要跟着变成新状态，
    // 否则页面上还停在「可归还」，用户会以为没成功而重复点。
    const code = result.value?.code
    if (code) {
      await lookup(code)
    }
  } catch {
    // 状态已变化等非法操作由请求层统一提示
  } finally {
    returning.value = false
  }
}

function scanAgain(): void {
  result.value = null
  manualCode.value = ''
  if (cameraSupported) {
    void startScanner(handleScanned)
  }
}
</script>

<template>
  <div class="ts-scan">
    <section class="ts-card ts-scan__intro">
      <h2 class="ts-scan__title">扫码借还</h2>
      <p class="ts-scan__desc">
        对准设备上的资产标签或单据上的二维码即可。识别后会自动判断该<b>借用</b>还是<b>归还</b>；
        摄像头不可用时，手动输入编号同样可以完成。
      </p>
    </section>

    <!-- 取景 / 手动输入 -->
    <section class="ts-card ts-scan__capture">
      <div v-if="cameraSupported" class="ts-scan__viewport">
        <div id="ts-scan-reader" class="ts-scan__reader" :class="{ 'is-idle': !scanning }"></div>
        <p v-if="!scanning && scannerState !== 'STARTING'" class="ts-scan__viewport-tip">
          摄像头未开启
        </p>
        <p v-else-if="scannerState === 'STARTING'" class="ts-scan__viewport-tip">正在启动摄像头…</p>
      </div>
      <el-alert
        v-else
        :title="cameraHint"
        type="warning"
        :closable="false"
        show-icon
        class="ts-scan__hint"
      />
      <el-alert
        v-if="scannerState === 'FAILED'"
        :title="scannerError"
        type="error"
        :closable="false"
        show-icon
        class="ts-scan__hint"
      />

      <div class="ts-scan__actions">
        <el-button v-if="cameraSupported" :disabled="busy" @click="toggleCamera">
          {{ scanning ? '停止扫码' : '开启扫码' }}
        </el-button>
      </div>

      <el-divider content-position="center">或手动输入</el-divider>

      <div class="ts-scan__manual">
        <el-input
          v-model="manualCode"
          placeholder="资产编号或工单号，如 IT-2026-0001"
          clearable
          :disabled="busy"
          @keyup.enter="submitManual"
        />
        <el-button type="primary" :loading="looking" :disabled="busy" @click="submitManual">
          查询
        </el-button>
      </div>
    </section>

    <!-- 识别结果 -->
    <section v-if="result" class="ts-card ts-scan__result">
      <!-- 未识别 / 不可操作 -->
      <template v-if="result.action !== SCAN_ACTION_BORROW && result.action !== SCAN_ACTION_RETURN && result.action !== SCAN_ACTION_VIEW">
        <el-result icon="warning" :title="result.actionLabel" :sub-title="result.reason ?? undefined">
          <template #extra>
            <el-button @click="scanAgain">重新扫码</el-button>
          </template>
        </el-result>
      </template>

      <template v-else>
        <div class="ts-scan__result-head">
          <el-tag :type="result.action === SCAN_ACTION_RETURN ? 'warning' : 'success'" effect="dark">
            {{ result.action === SCAN_ACTION_BORROW ? '可借用' : result.action === SCAN_ACTION_RETURN ? '可归还' : '可查看' }}
          </el-tag>
          <span class="ts-scan__result-label">{{ result.actionLabel }}</span>
        </div>

        <el-descriptions :column="1" border class="ts-scan__detail">
          <el-descriptions-item v-if="result.device" label="设备">
            {{ result.device.deviceName }}
          </el-descriptions-item>
          <el-descriptions-item v-if="result.device" label="资产编号">
            {{ result.device.assetNo }}
          </el-descriptions-item>
          <el-descriptions-item v-if="result.device" label="当前状态">
            {{ result.device.statusLabel }}
          </el-descriptions-item>
          <el-descriptions-item v-if="result.device?.storageLocation" label="存放位置">
            {{ result.device.storageLocation }}
          </el-descriptions-item>
          <el-descriptions-item v-if="result.order" label="工单号">
            {{ result.order.orderNo }}
          </el-descriptions-item>
          <el-descriptions-item v-if="result.order" label="工单状态">
            {{ result.order.statusLabel }}
          </el-descriptions-item>
          <el-descriptions-item v-if="result.order?.plannedEndTime" label="计划归还">
            {{ result.order.plannedEndTime }}
          </el-descriptions-item>
        </el-descriptions>

        <div class="ts-scan__result-actions">
          <el-button
            v-if="result.action === SCAN_ACTION_BORROW"
            type="primary"
            @click="goBorrow"
          >
            去填写借用申请
          </el-button>
          <el-button
            v-if="result.action === SCAN_ACTION_RETURN"
            type="primary"
            :loading="returning"
            :disabled="busy"
            @click="confirmReturn"
          >
            确认归还
          </el-button>
          <el-button v-if="result.action === SCAN_ACTION_VIEW" type="primary" @click="goOrder">
            查看我的工单
          </el-button>
          <el-button :disabled="busy" @click="scanAgain">重新扫码</el-button>
        </div>
      </template>
    </section>

    <section class="ts-card ts-scan__note">
      <h3 class="ts-scan__note-title">说明</h3>
      <ul class="ts-scan__note-list">
        <li>扫到<b>资产编号</b>：若这台正在被你借用，会直接进入归还确认；否则按设备当前状态提示借用或不可借用的原因。</li>
        <li>扫到<b>工单号</b>：只会跳到工单查看，<b>不会</b>顺手触发任何归还 —— 扫单据多半只是想看进度。</li>
        <li>摄像头需要 <b>https 或 localhost</b> 才能调用（浏览器安全策略）；其它情况请用手动输入，功能完全一致。</li>
      </ul>
    </section>
  </div>
</template>

<style scoped>
.ts-scan {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.ts-scan__title {
  margin: 0 0 8px;
  font-size: 18px;
}

.ts-scan__desc {
  margin: 0;
  color: var(--el-text-color-regular);
  line-height: 1.7;
}

.ts-scan__viewport {
  position: relative;
  border-radius: 8px;
  overflow: hidden;
  background: #000;
}

.ts-scan__reader {
  width: 100%;
  min-height: 260px;
}

.ts-scan__reader.is-idle {
  min-height: 80px;
  background: transparent;
}

.ts-scan__viewport-tip {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 0;
  color: #fff;
  background: rgba(0, 0, 0, 0.55);
}

.ts-scan__hint {
  margin-bottom: 12px;
}

.ts-scan__actions {
  display: flex;
  justify-content: center;
  margin-top: 12px;
}

.ts-scan__manual {
  display: flex;
  gap: 8px;
}

.ts-scan__result-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.ts-scan__result-label {
  color: var(--el-text-color-regular);
}

.ts-scan__detail {
  margin-bottom: 16px;
}

.ts-scan__result-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.ts-scan__note-title {
  margin: 0 0 8px;
  font-size: 15px;
}

.ts-scan__note-list {
  margin: 0;
  padding-left: 20px;
  color: var(--el-text-color-regular);
  line-height: 1.9;
}

/* 手机端（ / -531）：按钮铺满整行、取景区降低高度避免顶掉结果区 */
@media (max-width: 767px) {
  .ts-scan__reader {
    min-height: 200px;
  }

  .ts-scan__manual {
    flex-direction: column;
  }

  .ts-scan__result-actions {
    flex-direction: column;
  }

  .ts-scan__result-actions :deep(.el-button) {
    width: 100%;
    margin-left: 0;
  }

  .ts-scan__actions :deep(.el-button) {
    width: 100%;
  }
}
</style>
