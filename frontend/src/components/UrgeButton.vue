<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { orderApi } from '@/api/order'
import type { OrderItem } from '@/types/order'

/**
 * 催办按钮 + 冷却倒计时（，需求方）
 *
 * 两种用途：
 * - kind='approval' 审批催办：申请人催当前审批节点审批人，冷却键＝「工单 + 当前节点」；
 * - kind='return'   归还催办：实际执行人催借用人，冷却键＝「工单」。
 *
 * 可见性由父组件决定（不在冷却期时服务端 canUrgeXxx 为 true；冷却期则为 false，
 * 但此时仍需展示按钮并置灰倒计时 —— 所以把「是否显示按钮」交给父组件，本组件只管倒计时与提交）。
 *
 * 倒计时的基准值来自服务端返回的 `*CooldownSeconds`（绝对剩余秒数），
 * 组件内部再每秒自减，避免依赖前端时钟与服务器时钟一致。
 */

const props = defineProps<{
  order: OrderItem
  kind: 'approval' | 'return'
  /** PC 表格里用 link 样式；移动卡片里用 plain 按钮 */
  link?: boolean
  plain?: boolean
}>()

const emit = defineEmits<{ (e: 'done'): void }>()

const submitting = ref(false)
const remaining = ref(0)
let timer: number | undefined

const baseLabel = computed(() => (props.kind === 'approval' ? '催办' : '催还'))

function serverCooldownSeconds(): number {
  const value =
    props.kind === 'approval'
      ? props.order.approvalUrgeCooldownSeconds
      : props.order.returnUrgeCooldownSeconds
  return value != null && value > 0 ? value : 0
}

function stopTimer(): void {
  if (timer != null) {
    window.clearInterval(timer)
    timer = undefined
  }
}

/** 服务端数据刷新（父组件重新 load 后 order 变化）→ 以服务端值为准重置倒计时 */
watch(
  () => [props.order.id, serverCooldownSeconds()] as const,
  () => {
    stopTimer()
    remaining.value = serverCooldownSeconds()
    if (remaining.value > 0) {
      timer = window.setInterval(() => {
        remaining.value = Math.max(0, remaining.value - 1)
        if (remaining.value === 0) {
          stopTimer()
        }
      }, 1000)
    }
  },
  { immediate: true }
)

onUnmounted(stopTimer)

/** 倒计时文案：>=1h 显示 h/m，>=1min 显示 m/s，否则 s */
function formatLeft(seconds: number): string {
  if (seconds >= 3600) {
    const hours = Math.floor(seconds / 3600)
    const minutes = Math.floor((seconds % 3600) / 60)
    return `${hours}小时${minutes}分`
  }
  if (seconds >= 60) {
    const minutes = Math.floor(seconds / 60)
    const secs = seconds % 60
    return `${minutes}分${secs}秒`
  }
  return `${seconds}秒`
}

const label = computed(() =>
  remaining.value > 0 ? `${baseLabel.value}(${formatLeft(remaining.value)})` : baseLabel.value
)

async function handleClick(): Promise<void> {
  const isApproval = props.kind === 'approval'
  try {
    await ElMessageBox.confirm(
      isApproval
        ? `确认催办工单「${props.order.orderNo}」的当前审批人尽快审批？系统将向审批人发送一条站内提醒。`
        : `确认向借用人「${props.order.applicantName ?? '-'}」发送归还催办提醒（工单 ${props.order.orderNo}）？`,
      isApproval ? '审批催办' : '归还催办',
      { type: 'info', confirmButtonText: '确认发送', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  submitting.value = true
  try {
    if (isApproval) {
      await orderApi.urgeApproval(props.order.id)
    } else {
      await orderApi.urgeReturn(props.order.id)
    }
    ElMessage.success(isApproval ? '已发送审批催办提醒' : '已发送归还催办提醒')
    emit('done')
  } catch {
    // 冷却未到 / 状态已变化等由请求层统一提示
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-button
    v-if="remaining > 0"
    :link="link"
    :plain="plain"
    type="primary"
    size="small"
    disabled
  >
    {{ label }}
  </el-button>
  <el-button
    v-else
    :link="link"
    :plain="plain"
    type="primary"
    size="small"
    :loading="submitting"
    @click.stop="handleClick"
  >
    {{ label }}
  </el-button>
</template>
