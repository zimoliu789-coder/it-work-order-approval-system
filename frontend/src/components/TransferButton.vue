<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { orderApi } from '@/api/order'
import { useResponsive } from '@/composables/useResponsive'
import type { OrderItem, TransferCandidate } from '@/types/order'

/**
 * 工单转交按钮 + 弹窗（， + 需求方）
 *
 * 为什么抽成独立组件：转交入口在「我的待处理」里同时出现在 PC 表格、移动卡片、详情弹窗三处，
 * 每处都要「拉候选 → 选人 → 填原因 → 二次确认 → 提交」，若内联维护三份必然漂移。
 * 组件自带弹窗，父组件只需 `<TransferButton :order="row" link @done="load" />`。
 *
 * 转交对象由后端接口按角色返回（普通执行人=同组其他在职成员；super_admin=全部有效用户），
 * 前端不做任何权限推断 —— 与 canTransfer 字段「服务端判定、前端只渲染」保持一致。
 */

const props = defineProps<{
  order: OrderItem
  /** PC 表格里用 link 样式；移动卡片里用 plain 按钮 */
  link?: boolean
  plain?: boolean
}>()

const emit = defineEmits<{ (e: 'done'): void }>()

const { isMobile } = useResponsive()

const visible = ref(false)
const loadingCandidates = ref(false)
const candidates = ref<TransferCandidate[]>([])
const submitting = ref(false)

const form = reactive<{ newHandlerId: number | null; comment: string }>({
  newHandlerId: null,
  comment: ''
})

const candidateLabel = (item: TransferCandidate): string => {
  const name = item.displayName ?? `用户#${item.userId}`
  const load = item.inFlightCount ?? 0
  return `${name}（在办 ${load} 单）`
}

const noCandidate = computed(() => !loadingCandidates.value && candidates.value.length === 0)

async function open(): Promise<void> {
  form.newHandlerId = null
  form.comment = ''
  candidates.value = []
  visible.value = true
  loadingCandidates.value = true
  try {
    candidates.value = await orderApi.transferCandidates(props.order.id)
  } catch {
    candidates.value = []
  } finally {
    loadingCandidates.value = false
  }
}

async function submit(): Promise<void> {
  if (form.newHandlerId == null) {
    ElMessage.warning('请选择转交对象')
    return
  }
  const comment = form.comment.trim()
  if (!comment) {
    ElMessage.warning('请填写转交原因')
    return
  }
  const target = candidates.value.find((item) => item.userId === form.newHandlerId)
  const targetName = target?.displayName ?? `用户#${form.newHandlerId}`
  try {
    await ElMessageBox.confirm(
      `确认将工单「${props.order.orderNo}」（设备：${props.order.deviceName ?? '-'}）转交给「${targetName}」？` +
        '转交后该工单的交付 / 收回 / 超时告警等待办将全部由其接手，您将不再处理该工单。',
      '确认转交',
      { type: 'warning', confirmButtonText: '确认转交', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  submitting.value = true
  try {
    await orderApi.transfer(props.order.id, { newHandlerId: form.newHandlerId, comment })
    ElMessage.success('工单已转交，后续待办由新执行人处理')
    visible.value = false
    emit('done')
  } catch {
    // 目标不在组 / 状态已变化 / 并发冲突等由请求层统一提示
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-button
    v-if="link"
    link
    type="warning"
    size="small"
    @click.stop="open"
  >
    转交
  </el-button>
  <el-button v-else type="warning" :plain="plain" size="small" @click.stop="open">转交</el-button>

  <el-dialog
    v-model="visible"
    title="转交工单"
    :width="isMobile ? '94%' : '480px'"
    append-to-body
  >
    <p class="ts-transfer__hint">
      工单：{{ order.orderNo }}（设备：{{ order.deviceName ?? '-' }}，申请人：{{ order.applicantName ?? '-' }}）
    </p>
    <el-alert
      v-if="noCandidate"
      class="ts-transfer__alert"
      type="warning"
      :closable="false"
      show-icon
      title="暂无可转交对象"
      description="本工单的最终处理部门内没有其他在职成员（或您不是当前执行人）。请联系管理员调整处理小组或执行人。"
    />
    <el-form label-width="90px" :label-position="isMobile ? 'top' : 'right'">
      <el-form-item label="转交对象" required>
        <el-select
          v-model="form.newHandlerId"
          class="ts-transfer__select"
          :loading="loadingCandidates"
          :disabled="noCandidate"
          placeholder="请选择同组在职成员"
          filterable
        >
          <el-option
            v-for="item in candidates"
            :key="item.userId"
            :label="candidateLabel(item)"
            :value="item.userId"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="转交原因" required>
        <el-input
          v-model="form.comment"
          type="textarea"
          :rows="3"
          maxlength="500"
          show-word-limit
          placeholder="请说明转交原因（如同一岗位交接、临时顶班等）"
        />
      </el-form-item>
    </el-form>
    <p class="ts-text-hint ts-transfer__note">
      转交后执行人即变更，超时告警、归还确认权限、待办列表全部跟随新执行人；新执行人会收到站内消息通知，申请人可在工单详情看到本次转交记录。
    </p>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button
        type="warning"
        :loading="submitting"
        :disabled="noCandidate"
        @click="submit"
      >
        确认转交
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.ts-transfer__hint {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.6;
}

.ts-transfer__alert {
  margin-bottom: 12px;
}

.ts-transfer__select {
  width: 100%;
}

.ts-transfer__note {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
}
</style>
