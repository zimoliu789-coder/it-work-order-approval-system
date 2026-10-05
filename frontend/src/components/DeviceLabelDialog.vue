<template>
  <el-dialog
    :model-value="modelValue"
    title="打印设备标签"
    :width="dialogWidth"
    append-to-body
    @update:model-value="close"
  >
    <!--
      工具条：标签纸尺寸 + 份数。
      尺寸存 localStorage（需求方选的「弹窗内选预设 + 记住上次」）——
      同一台机器常同时用几种标签纸，做成系统参数反而每次都要去改系统设置。
    -->
    <div class="lb__bar">
      <span class="ts-text-hint">标签纸</span>
      <el-select v-model="sizeKey" class="lb__size" @change="onSizeChange">
        <el-option v-for="s in LABEL_SIZES" :key="s.key" :label="s.label" :value="s.key" />
      </el-select>
      <span class="ts-text-hint">份数</span>
      <el-input-number v-model="copies" :min="1" :max="10" controls-position="right" class="lb__copies" />
      <span class="ts-text-hint">
        预览按屏幕缩放 {{ previewScalePercent }}%
        {{ previewScalePercent === PREVIEW_SCALE_DEFAULT ? '（未校准）' : '（已按你的屏幕校准）' }}
      </span>
      <el-button link type="primary" size="small" @click="showCalibration = !showCalibration">
        {{ showCalibration ? '收起校准' : '校准屏幕尺寸' }}
      </el-button>
    </div>

    <!--
      屏幕校准。
      浏览器不暴露屏幕物理 PPI，CSS 的 mm 只是「96px = 1in」的换算 ⇒ 1mm 在真实显示器上
      可能偏大也可能偏小（用户实测偏大约一倍）。用银行卡（ISO ID-1，85.6×54mm）比对一次即可，
      系数存在 localStorage。**校准只影响预览**，打印始终按真实标签纸尺寸走。
    -->
    <div v-show="showCalibration" class="lb__calib">
      <p class="lb__calib-title">拿一张{{ REFERENCE_CARD.label }}贴在屏幕上比对</p>
      <p class="ts-text-hint">
        拖动滑块，直到下面的方框与卡片边缘完全重合。校准只影响屏幕预览，打印始终按真实标签纸尺寸输出。
      </p>
      <div class="lb__calib-body">
        <div class="lb__calib-card" :style="calibrationCardStyle">
          <span>{{ REFERENCE_CARD.label }} {{ REFERENCE_CARD.widthMm }} × {{ REFERENCE_CARD.heightMm }} mm</span>
        </div>
        <div class="lb__calib-slider">
          <el-slider
            v-model="previewScalePercent"
            :min="PREVIEW_SCALE_MIN"
            :max="PREVIEW_SCALE_MAX"
            :step="1"
            @change="onScaleChange"
          />
          <div class="lb__calib-actions">
            <span class="ts-text-hint">{{ previewScalePercent }}%（100% = 浏览器默认换算）</span>
            <el-button link type="primary" size="small" @click="resetScale">恢复 100%</el-button>
          </div>
        </div>
      </div>
    </div>

    <el-alert
      v-if="skippedCount > 0"
      class="lb__alert"
      type="warning"
      show-icon
      :closable="false"
      :title="`有 ${skippedCount} 台设备没有资产编号，不会打印`"
      description="标签内容以资产编号为标识（二维码里写的也是它），缺编号的标签印出来无法扫码核对。"
    />

    <!-- 可编辑清单：需求「点击列表内某台 → 弹窗内可编辑，实时刷新预览」 -->
    <el-table :data="rows" size="small" max-height="200" class="lb__table">
      <el-table-column label="设备名称" min-width="200">
        <template #default="{ row }">
          <el-input v-model="row.deviceName" size="small" placeholder="设备名称" />
        </template>
      </el-table-column>
      <el-table-column label="资产编号" min-width="180">
        <template #default="{ row }">
          <el-input v-model="row.assetNo" size="small" placeholder="资产编号" />
        </template>
      </el-table-column>
      <el-table-column label="二维码内容" min-width="220">
        <template #default="{ row }">
          <span v-if="qrPayload(row.assetNo)" class="ts-text-hint lb__payload">{{ qrPayload(row.assetNo) }}</span>
          <el-tag v-else size="small" type="warning" effect="plain">缺资产编号</el-tag>
        </template>
      </el-table-column>
    </el-table>

    <!-- 预览：左二维码 + 右两行文字（上=资产编号加粗，下=设备名称小一号） -->
    <div v-loading="qrLoading" class="lb__preview">
      <div v-for="row in printableRows" :key="row.id" class="lb__item">
        <div class="lb__paper">
          <span v-html="labelHtml(row)"></span>
        </div>
        <span class="lb__paper-caption">{{ size.label }}</span>
      </div>
      <p v-if="printableRows.length === 0" class="ts-text-hint">没有可打印的标签</p>
    </div>

    <template #footer>
      <div class="lb__footer">
        <span class="lb__status">
          <template v-if="!btState.supported">
            <el-tag size="small" type="info" effect="plain">当前浏览器不支持蓝牙打印</el-tag>
            <span class="ts-text-hint">需在 Windows 上用 Chrome / Edge，且页面走 https 或 localhost。</span>
          </template>
          <template v-else-if="btState.connected">
            <el-tag size="small" type="success" effect="plain">已连接 {{ btState.deviceName }}</el-tag>
            <el-button link type="primary" size="small" @click="btDisconnect">断开</el-button>
          </template>
          <template v-else>
            <span class="ts-text-hint">蓝牙打印机未连接，点「蓝牙打印」会先弹出设备选择</span>
          </template>
          <span v-if="btState.error" class="lb__error">{{ btState.error }}</span>
        </span>
        <span class="lb__actions">
          <el-button @click="close">关闭</el-button>
          <el-button :loading="btState.busy" @click="handleBluetoothPrint">蓝牙打印</el-button>
          <el-button type="primary" :loading="printing" @click="handlePrint">打印</el-button>
        </span>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
/**
 * 设备标签打印弹窗
 *
 * 需求要点与落点：
 *  1/2/3. 单台、多台（勾选）都走这一个弹窗，每台一个标签 —— 由父页把 `devices` 传进来即可；
 *  4.     设备名称 / 资产编号**可编辑**，改完立即重算几何并重画二维码（`watch` + 防抖）；
 *  5.     左边二维码、右边两行文字（上=资产编号加粗、下=设备名称小一号）—— 按实拍图；
 *  7.     「打印」走浏览器打印：把标签写进新窗口并设 `@page` 为标签纸尺寸，用户自选打印机；
 *  8.     「蓝牙打印」走 Web Bluetooth + TSPL（`useBluetoothLabelPrinter`）。
 *
 * ⚠️ 预览与打印**共用** `renderLabelHtml`（同一份 HTML），因此不存在「屏幕上刚好、印出来溢出」。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { create as qrCreate, toString as qrToString } from 'qrcode'
import {
  buildPrintDocument,
  buildTspl,
  DEFAULT_LABEL_SIZE_KEY,
  findLabelSize,
  LABEL_CSS,
  LABEL_PREVIEW_SCALE_KEY,
  LABEL_SIZE_STORAGE_KEY,
  LABEL_SIZES,
  PREVIEW_SCALE_DEFAULT,
  PREVIEW_SCALE_MAX,
  PREVIEW_SCALE_MIN,
  qrPayload,
  REFERENCE_CARD,
  renderLabelHtml,
  type LabelDevice
} from '@/utils/qrLabel'
import { useBluetoothLabelPrinter } from '@/composables/useBluetoothLabelPrinter'

const props = defineProps<{
  modelValue: boolean
  devices: LabelDevice[]
}>()

const emit = defineEmits<{ (e: 'update:modelValue', value: boolean): void }>()

const {
  state: btState,
  connect: btConnect,
  disconnect: btDisconnect,
  print: btPrint,
  refreshSupport: btRefreshSupport
} = useBluetoothLabelPrinter()

const rows = ref<LabelDevice[]>([])
const qrMap = ref<Record<string, string>>({})
const qrLoading = ref(false)
const printing = ref(false)
const copies = ref(1)

const sizeKey = ref<string>(localStorage.getItem(LABEL_SIZE_STORAGE_KEY) ?? DEFAULT_LABEL_SIZE_KEY)
const size = computed(() => findLabelSize(sizeKey.value))

// ------------------------------------------------------------------
// 屏幕校准（预览专用）
// ------------------------------------------------------------------

const showCalibration = ref(false)

function readScalePercent(): number {
  const raw = Number(localStorage.getItem(LABEL_PREVIEW_SCALE_KEY))
  return Number.isFinite(raw) && raw >= PREVIEW_SCALE_MIN && raw <= PREVIEW_SCALE_MAX
    ? raw
    : PREVIEW_SCALE_DEFAULT
}

const previewScalePercent = ref<number>(readScalePercent())
/** 预览用的缩放系数；**打印时一律用 1**（见 handlePrint） */
const previewScale = computed(() => previewScalePercent.value / 100)

const calibrationCardStyle = computed(() => ({
  width: `${REFERENCE_CARD.widthMm * previewScale.value}mm`,
  height: `${REFERENCE_CARD.heightMm * previewScale.value}mm`
}))

function onScaleChange(): void {
  localStorage.setItem(LABEL_PREVIEW_SCALE_KEY, String(previewScalePercent.value))
}

function resetScale(): void {
  previewScalePercent.value = PREVIEW_SCALE_DEFAULT
  onScaleChange()
}

const dialogWidth = computed(() => (typeof window !== 'undefined' && window.innerWidth < 768 ? '96%' : '900px'))

const printableRows = computed(() => rows.value.filter((row) => !!qrPayload(row.assetNo)))
const skippedCount = computed(() => rows.value.length - printableRows.value.length)

function labelHtml(row: LabelDevice): string {
  // 预览传校准系数：让屏幕上的物理尺寸等于标签纸尺寸
  return renderLabelHtml(row, size.value, qrMap.value[String(row.id)] ?? '', previewScale.value)
}

// ------------------------------------------------------------------
// 二维码生成
// ------------------------------------------------------------------

let qrTimer: ReturnType<typeof setTimeout> | null = null

/**
 * 重新生成二维码。
 *
 * 只在「资产编号变了」时才值得重算，但为了简单统一按整批重算 —— 数量上限是勾选数（≤200），
 * 且用 300ms 防抖，输入过程中不会卡。
 */
async function refreshQr(): Promise<void> {
  qrLoading.value = true
  try {
    const next: Record<string, string> = {}
    for (const row of rows.value) {
      const payload = qrPayload(row.assetNo)
      if (!payload) {
        continue
      }
      next[String(row.id)] = await qrToString(payload, {
        type: 'svg',
        margin: 0,
        errorCorrectionLevel: 'L'
      })
    }
    qrMap.value = next
  } finally {
    qrLoading.value = false
  }
}

function scheduleQr(): void {
  if (qrTimer) {
    clearTimeout(qrTimer)
  }
  qrTimer = setTimeout(() => {
    void refreshQr()
  }, 300)
}

watch(
  () => rows.value.map((r) => r.assetNo).join('\u0001'),
  () => scheduleQr()
)

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      rows.value = props.devices.map((d) => ({ ...d }))
      copies.value = 1
      // 组件随页面一起挂载 ⇒ 「蓝牙能力」与「屏幕校准系数」都不能在 setup 时只读一次，
      // 否则弹窗过很久才打开、或在别的标签页校准过，这里用的都是陈旧值。
      btRefreshSupport()
      previewScalePercent.value = readScalePercent()
      void refreshQr()
    }
  }
)

// ------------------------------------------------------------------
// 预览样式：LABEL_CSS 是唯一事实源，运行时注入一次
// （v-html 的内容不吃 <style scoped>，所以不能写在 SFC 的 scoped 块里）
// ------------------------------------------------------------------

const STYLE_ID = 'ts-device-label-style'

function injectLabelCss(): void {
  if (document.getElementById(STYLE_ID)) {
    return
  }
  const el = document.createElement('style')
  el.id = STYLE_ID
  el.textContent = LABEL_CSS
  document.head.appendChild(el)
}

onMounted(injectLabelCss)
onBeforeUnmount(() => {
  if (qrTimer) {
    clearTimeout(qrTimer)
  }
})

// ------------------------------------------------------------------
// 打印
// ------------------------------------------------------------------

function onSizeChange(): void {
  localStorage.setItem(LABEL_SIZE_STORAGE_KEY, sizeKey.value)
}

/**
 * 浏览器打印。
 *
 * 用**新窗口 + `@page { size: <标签纸> }`**，而不是在当前页做 `@media print`：
 * 后者要把应用外壳（侧边栏 / 顶栏 / 弹窗遮罩）全部藏掉，任何一处漏藏都会在标签纸上多印一条；
 * 新窗口里只有标签，且 `@page` 能真正把纸张尺寸钉死（否则按 A4 排版，标签缩到纸中间）。
 */
function handlePrint(): void {
  const list = printableRows.value
  if (list.length === 0) {
    ElMessage.warning('没有可打印的标签：资产编号为空，二维码里就没有内容')
    return
  }
  printing.value = true
  try {
    const labels: string[] = []
    for (const row of list) {
      const html = renderLabelHtml(row, size.value, qrMap.value[String(row.id)] ?? '')
      for (let i = 0; i < copies.value; i += 1) {
        labels.push(html)
      }
    }
    const win = window.open('', '_blank')
    if (!win) {
      ElMessage.error('浏览器拦截了打印窗口，请允许本站弹出窗口后重试')
      return
    }
    win.document.open()
    win.document.write(buildPrintDocument(labels, size.value, '设备标签'))
    win.document.close()
    // 二维码是内联 SVG（没有外部图片要等），给渲染留一拍即可
    setTimeout(() => {
      win.focus()
      win.print()
    }, 400)
  } finally {
    printing.value = false
  }
}

/**
 * 蓝牙打印：先连（未连时弹设备选择），再按 TSPL 逐张发送。
 *
 * 一张标签一条指令（各自带 `PRINT 1,n`）而不是拼成一条大指令：
 * 中途失败时前面已经打出来的标签不会丢，且报错能指到是哪一张。
 */
async function handleBluetoothPrint(): Promise<void> {
  const list = printableRows.value
  if (list.length === 0) {
    ElMessage.warning('没有可打印的标签：资产编号为空，二维码里就没有内容')
    return
  }
  try {
    if (!btState.value.connected) {
      await btConnect()
    }
  } catch {
    ElMessage.error(btState.value.error || '蓝牙连接失败')
    return
  }
  try {
    for (const row of list) {
      const payload = qrPayload(row.assetNo)
      const moduleCount = qrCreate(payload, { errorCorrectionLevel: 'L' }).modules.size
      await btPrint(buildTspl(row, size.value, moduleCount, { copies: copies.value, ecc: 'L' }))
    }
    ElMessage.success(`已发送 ${list.length} 张标签到「${btState.value.deviceName}」`)
  } catch {
    ElMessage.error(btState.value.error || '蓝牙打印失败')
  }
}

function close(): void {
  emit('update:modelValue', false)
}
</script>

<style scoped>
.lb__bar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 12px;
  align-items: center;
}

.lb__size {
  width: 150px;
}

.lb__copies {
  width: 110px;
}

.lb__alert {
  margin-top: 12px;
}

.lb__table {
  margin-top: 12px;
}

.lb__payload {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
}

/*
  预览区：每个标签按**实际尺寸**渲染（渲染出来的 mm 由 CSS 换算成像素，
  与打印时的物理尺寸一一对应），外加虚线框标出标签边界。
*/
.lb__preview {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  max-height: 320px;
  padding: 12px;
  margin-top: 12px;
  overflow: auto;
  background: var(--ts-bg);
  border-radius: 8px;
}

.lb__paper {
  border: 1px dashed var(--ts-border);
  background: #fff;
}

.lb__footer {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 12px;
  align-items: center;
  justify-content: space-between;
}

.lb__status {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  text-align: left;
}

.lb__error {
  color: var(--el-color-danger);
  font-size: 12px;
}

.lb__actions {
  display: inline-flex;
  gap: 8px;
}

/* ---------------- 屏幕校准 ---------------- */
.lb__calib {
  padding: 12px;
  margin-top: 12px;
  border: 1px dashed var(--ts-border);
  border-radius: 8px;
}

.lb__calib-title {
  margin: 0 0 4px;
  font-size: 13px;
  font-weight: 500;
}

.lb__calib-body {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  align-items: center;
  margin-top: 12px;
}

/* 参照物方框：尺寸 = 真实卡片尺寸 × 校准系数，用户拿卡片贴着调到重合 */
.lb__calib-card {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 10px;
  color: var(--ts-text-hint);
  background: #fff;
  border: 1px solid var(--ts-text-hint);
  border-radius: 3px;
}

.lb__calib-slider {
  flex: 1 1 280px;
  min-width: 220px;
}

.lb__calib-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.lb__item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  align-items: flex-start;
}

.lb__paper-caption {
  font-size: 11px;
  color: var(--ts-text-hint);
}
</style>
