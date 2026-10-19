<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { orderApi } from '@/api/order'
import AttachmentUpload from '@/components/AttachmentUpload.vue'
import FormRenderer from '@/components/FormRenderer.vue'
import { useResponsive } from '@/composables/useResponsive'
import {
  approvalNodeTagType,
  countInactiveNodes,
  extendStatusTagType,
  forceOperationTagType,
  isCustomOrder,
  orderStatusTagType,
  transferRequestStatusTagType,
  visibleApprovalNodes
} from '@/types/order'
import type {
  ApprovalNodeStatusCode,
  OrderDetail,
  OrderExtendItem,
  OrderFormData,
  TransferCandidate,
  TransferGroupOption,
  TransferRequestItem,
  TransferScopeCode
} from '@/types/order'
import { useUserStore } from '@/store/user'

/**
 * 工单详情弹窗（：工单详情页展示最终处理部门、当前实际执行人、审批链路）
 *
 * 说明：把详情抽成公共组件，供「我的工单 / 审批待办 / 我的待处理」三页复用，
 * 避免同一份链路渲染逻辑维护三份（ 的教训：多处维护同一路径容易漂移）。
 * 操作按钮由调用方通过 #actions 插槽注入，组件本身只负责展示。
 *
 *  追加：借用延期记录时间线—— 与主单审批链路分开渲染，
 * 因为延期是子工单，其审批快照独立于主单，混在一起会让用户误读「工单被重新审批」。
 */

const props = defineProps<{
  modelValue: boolean
  orderId: number | null
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
}>()

const { isMobile } = useResponsive()
const userStore = useUserStore()

const loading = ref(false)
const detail = ref<OrderDetail | null>(null)
const extends_ = ref<OrderExtendItem[]>([])
/** 自定义申请的表单数据 + 当初那一版 schema（；非自定义工单为 null） */
const formData = ref<OrderFormData | null>(null)
/** 转单申请记录（V55：同组 / 跨组，含被拒绝的历史） */
const transferRequests = ref<TransferRequestItem[]>([])
/**
 * 是否展示完整骨架（ 激活史）。
 *
 * <p>默认关闭：`INACTIVE` 节点代表「流程还没走到这里」，展示给普通申请人会
 * 让人误以为自己的单子被卡在某一步。管理角色（admin / super_admin）才有权
 * 展开看完整链路 —— 他们本来就要负责排查「流程为什么不往下走」。
 */
const showSkeleton = ref(false)

/** 是否有权展开完整骨架（前端仅控制体验；服务端本就向有权查看工单者返回全部节点） */
const canSeeSkeleton = computed(
  () => userStore.role === 'admin' || userStore.role === 'super_admin'
)

/** 未激活节点数量（为 0 时不渲染开关，避免出现「显示 0 个」的噪音） */
const inactiveCount = computed(() => countInactiveNodes(detail.value?.nodes))

/** 实际渲染的节点：默认滤掉未激活节点；管理角色展开后才显示完整骨架 */
const visibleNodes = computed(() =>
  visibleApprovalNodes(detail.value?.nodes, showSkeleton.value && canSeeSkeleton.value)
)

/** 是否为自定义申请（决定详情用「动态表单」还是「设备 + 借用信息」渲染） */
const custom = computed(() => (detail.value ? isCustomOrder(detail.value) : false))

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value)
})

watch(
  () => [props.modelValue, props.orderId] as const,
  async ([open, id]) => {
    if (!open || id == null) {
      return
    }
    detail.value = null
    extends_.value = []
    formData.value = null
    transferRequests.value = []
    // 每次打开都回到默认视图：上一笔工单里展开的骨架不应"粘"到下一笔上
    showSkeleton.value = false
    loading.value = true
    try {
      const [order, extendsList, requests] = await Promise.all([
        orderApi.detail(id),
        // 延期是借用单专属，自定义工单查它会返回空；失败也不该影响详情展示
        orderApi.listExtends(id).catch(() => [] as OrderExtendItem[]),
        // 转单记录同理：无权查看的人会被 403，这里降级为空而不是让整个详情打不开
        orderApi.listTransferRequests(id).catch(() => [] as TransferRequestItem[])
      ])
      detail.value = order
      extends_.value = extendsList
      transferRequests.value = requests
      // 自定义申请：再取「当初那版」表单定义与用户填写的值
      if (isCustomOrder(order)) {
        try {
          formData.value = await orderApi.formData(id)
        } catch {
          formData.value = null
        }
      }
    } catch {
      detail.value = null
    } finally {
      loading.value = false
    }
  },
  { immediate: true }
)

function deviceText(order: OrderDetail): string {
  return order.deviceName ?? '-'
}

/** 到期日仅短期借用展示（需求方 ） */
function showExpectedReturn(order: OrderDetail): boolean {
  return order.useType === 'SHORT_TERM'
}

function nodeStatusText(node: { statusLabel: string }): string {
  return node.statusLabel
}

/**
 * 节点时间戳。
 *
 * <p>未激活节点没有动作时间，也不该显示「待处理」—— 那不是「等我处理」，
 * 而是「还没轮到判定要不要我处理」。两者混用会制造假的待办焦虑。
 */
function nodeTimestamp(node: {
  status: ApprovalNodeStatusCode
  actionTime?: string | null
}): string {
  if (node.actionTime) {
    return node.actionTime
  }
  return node.status === 'INACTIVE' ? '未激活' : '待处理'
}

/** 快照非空但被全部过滤（只剩未激活节点）时的空态文案 —— 不能复用"无需审批"的说法 */
const emptyNodesDescription = computed(() => {
  if ((detail.value?.nodes?.length ?? 0) > 0) {
    return '审批链路已推进到尚未激活的节点，暂无可展示的进展'
  }
  return custom.value ? '该工单无需审批，已直接完成' : '该工单无需审批，已直接进入待交付'
})

/** 延期节点：非「待处理」的节点展示动作时间，否则展示「待处理」 */
function extendNodeTimestamp(node: { actionTime?: string | null }): string {
  return node.actionTime ?? '待处理'
}

// ----------------------------------------------------------------------
// 转单申请（V55 / D5.7）：同组 / 跨组三态
//
// UI 上只放「发起」与「处理」两个入口，判定全部交给服务端：
// 谁有资格发起、该由谁批、接收后能不能派这个人 —— 前端不重复实现一遍，
// 否则规则一改就是「按钮能点、接口 403」的经典错位。
// ----------------------------------------------------------------------

/** 发起转单弹窗 */
const transferForm = reactive({
  visible: false,
  scope: 'SAME_GROUP' as TransferScopeCode,
  targetHandlerId: null as number | null,
  targetDepartmentId: null as number | null,
  reason: '',
  saving: false
})

/** 同组转单的接班人候选（本组在职成员，服务端已排除当前执行人与申请人） */
const transferCandidates = ref<TransferCandidate[]>([])
/** 跨组转单的目标小组（含各自管理员；未配置管理员的小组不可选） */
const transferGroups = ref<TransferGroupOption[]>([])

/** 处理转单弹窗（同意 / 接收并分配 / 拒绝） */
const decideForm = reactive({
  visible: false,
  request: null as TransferRequestItem | null,
  accepted: true,
  targetHandlerId: null as number | null,
  comment: '',
  saving: false
})
/** 跨组接收时要选目标小组的成员 */
const decideMembers = ref<TransferCandidate[]>([])

/** 转单是否已被禁用（终态工单不允许转单，与服务端同一口径） */
const transferDisabled = computed(() => detail.value?.canTransfer !== true)

async function openTransferDialog(scope: TransferScopeCode): Promise<void> {
  transferForm.scope = scope
  transferForm.targetHandlerId = null
  transferForm.targetDepartmentId = null
  transferForm.reason = ''
  transferForm.visible = true
  if (props.orderId == null) {
    return
  }
  if (scope === 'SAME_GROUP') {
    transferCandidates.value = await orderApi
      .transferCandidates(props.orderId)
      .catch(() => [] as TransferCandidate[])
  } else {
    transferGroups.value = await orderApi.transferGroups().catch(() => [] as TransferGroupOption[])
  }
}

async function submitTransfer(): Promise<void> {
  if (props.orderId == null) {
    return
  }
  if (!transferForm.reason.trim()) {
    ElMessage.warning('请填写转单原因')
    return
  }
  if (transferForm.scope === 'SAME_GROUP' && transferForm.targetHandlerId == null) {
    ElMessage.warning('请选择本组的接手人')
    return
  }
  if (transferForm.scope === 'CROSS_GROUP' && transferForm.targetDepartmentId == null) {
    ElMessage.warning('请选择目标处理小组')
    return
  }
  transferForm.saving = true
  try {
    await orderApi.createTransferRequest(props.orderId, {
      scope: transferForm.scope,
      targetHandlerId: transferForm.scope === 'SAME_GROUP' ? transferForm.targetHandlerId : null,
      targetDepartmentId: transferForm.scope === 'CROSS_GROUP' ? transferForm.targetDepartmentId : null,
      reason: transferForm.reason.trim()
    })
    ElMessage.success(
      transferForm.scope === 'SAME_GROUP'
        ? '同组转单申请已提交，等待本组管理员审批'
        : '跨组转单申请已提交，等待目标小组管理员接收'
    )
    transferForm.visible = false
    await reloadAfterTransfer()
  } catch {
    // 权限 / 状态 / 已有待处理申请等错误由请求层统一提示
  } finally {
    transferForm.saving = false
  }
}

async function openDecide(row: TransferRequestItem, accepted: boolean): Promise<void> {
  decideForm.request = row
  decideForm.accepted = accepted
  decideForm.targetHandlerId = null
  decideForm.comment = ''
  decideMembers.value = []
  decideForm.visible = true
  // 跨组「接收」必须当场指定本组执行人 —— 否则工单会卡在「无人负责」的中间态
  if (accepted && row.transferScope === 'CROSS_GROUP' && row.toDepartmentId != null) {
    decideMembers.value = await orderApi
      .transferGroupMembers(row.toDepartmentId)
      .catch(() => [] as TransferCandidate[])
  }
}

async function submitDecide(): Promise<void> {
  const row = decideForm.request
  if (!row) {
    return
  }
  if (decideForm.accepted && row.transferScope === 'CROSS_GROUP' && decideForm.targetHandlerId == null) {
    ElMessage.warning('接收跨组转单时必须指定本组的执行人')
    return
  }
  if (!decideForm.accepted && !decideForm.comment.trim()) {
    ElMessage.warning('请填写拒绝原因，便于对方知道下一步怎么办')
    return
  }
  decideForm.saving = true
  try {
    await orderApi.decideTransferRequest(row.id, {
      accepted: decideForm.accepted,
      targetHandlerId: decideForm.accepted ? decideForm.targetHandlerId : null,
      comment: decideForm.comment.trim() || null
    })
    ElMessage.success(decideForm.accepted ? '转单已生效' : '已拒绝，工单留在原组')
    decideForm.visible = false
    await reloadAfterTransfer()
  } catch {
    // 冲突 / 越权由请求层提示
  } finally {
    decideForm.saving = false
  }
}

/** 转单成功后重取详情与转单记录（执行人 / 小组 / 状态都可能已变） */
async function reloadAfterTransfer(): Promise<void> {
  if (props.orderId == null) {
    return
  }
  try {
    const [order, requests] = await Promise.all([
      orderApi.detail(props.orderId),
      orderApi.listTransferRequests(props.orderId).catch(() => [] as TransferRequestItem[])
    ])
    detail.value = order
    transferRequests.value = requests
  } catch {
    // 重取失败不打断用户：下一次打开详情会拿到最新数据
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    title="工单详情"
    :width="isMobile ? '94%' : '720px'"
    class="ts-order__dialog"
  >
    <div v-loading="loading">
      <template v-if="detail">
        <el-descriptions :column="isMobile ? 1 : 2" border size="small">
          <el-descriptions-item label="工单编号">{{ detail.orderNo }}</el-descriptions-item>
          <el-descriptions-item label="工单状态">
            <el-tag :type="orderStatusTagType(detail.status)" size="small" effect="plain">
              {{ detail.statusLabel }}
            </el-tag>
          </el-descriptions-item>
          <!-- ：自定义申请没有设备 / 借用类型 / 使用地点，
               这些列不渲染（而不是渲染成空白行），避免查看者以为「数据丢了」 -->
          <el-descriptions-item v-if="custom" label="申请类型">
            {{ detail.applyTypeName ?? formData?.applyTypeName ?? '-' }}
          </el-descriptions-item>
          <template v-else>
            <el-descriptions-item label="申请设备">{{ deviceText(detail) }}</el-descriptions-item>
            <el-descriptions-item label="借用类型">
              <el-tag size="small" :type="detail.useType === 'SHORT_TERM' ? 'warning' : 'success'" effect="plain">
                {{ detail.useTypeLabel }}
              </el-tag>
            </el-descriptions-item>
          </template>
          <el-descriptions-item label="申请人">{{ detail.applicantName ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="所属分组">{{ detail.departmentName ?? '-' }}</el-descriptions-item>
          <!-- V54 / D5：当前所在分部（决定由哪个处理小组接单，与「所属分组」= 人事主部门
               是两件事）。V54 之前的单没有这两个值 → 整格不渲染，而不是显示两个「-」 -->
          <el-descriptions-item
            v-if="detail.currentDivisionName || detail.divisionSourceLabel"
            label="当前所在分部"
          >
            {{ detail.currentDivisionName ?? '-' }}
            <el-tag v-if="detail.divisionSourceLabel" size="small" effect="plain" type="info">
              {{ detail.divisionSourceLabel }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item v-if="!custom && showExpectedReturn(detail)" label="期望归还日期">
            {{ detail.expectedReturnDate ?? '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="最终处理部门">{{ detail.handlerGroupName ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="当前执行人">
            {{ detail.actualFinalHandlerName ?? '待分配' }}
          </el-descriptions-item>
          <el-descriptions-item v-if="!custom && detail.reason" label="用途" :span="isMobile ? 1 : 2">
            {{ detail.reason }}
          </el-descriptions-item>
          <el-descriptions-item label="提交时间">{{ detail.createdAt }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.deliveredAt" label="交付时间">{{ detail.deliveredAt }}</el-descriptions-item>

          <!-- ：借用期限 / 超时 / 归还（ /  / ） -->
          <el-descriptions-item v-if="detail.plannedEndTime" label="计划归还时间">
            {{ detail.plannedEndTime }}
          </el-descriptions-item>
          <el-descriptions-item v-if="(detail.autoExtendCount ?? 0) > 0" label="自动顺延次数">
            {{ detail.autoExtendCount }} 次
          </el-descriptions-item>
          <el-descriptions-item v-if="(detail.extendUsedCount ?? 0) > 0" label="主动延期次数">
            {{ detail.extendUsedCount }} / {{ detail.extendMaxCount ?? 2 }} 次
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.borrowTimeout" label="超时状态">
            <el-tag type="danger" size="small" effect="dark">已超时</el-tag>
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.returnTrigger" label="归还来源">
            {{ detail.returnTriggerLabel ?? detail.returnTrigger }}
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.returnNote" label="归还说明" :span="isMobile ? 1 : 2">
            {{ detail.returnNote }}
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.returnCondition" label="归还检查">
            <el-tag
              size="small"
              effect="plain"
              :type="detail.returnCondition === 'GOOD' ? 'success' : 'danger'"
            >
              {{ detail.returnConditionLabel ?? detail.returnCondition }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.returnRemark" label="检查说明" :span="isMobile ? 1 : 2">
            {{ detail.returnRemark }}
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.returnedByName" label="实际收回人">
            {{ detail.returnedByName }}
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.actualEndTime" label="实际归还时间">
            {{ detail.actualEndTime }}
          </el-descriptions-item>
        </el-descriptions>

        <!-- V55 / D5.7：转单入口。同组=组内换人（本组管理员批）；跨组=整单移交（对方管理员接收分配）。
             判定交给服务端（谁能发起），前端只按 canTransfer 决定是否露出入口。 -->
        <div v-if="detail.canTransfer" class="ts-order__transfer-bar">
          <el-button size="small" :disabled="transferDisabled" @click="openTransferDialog('SAME_GROUP')">
            同组转单
          </el-button>
          <el-button size="small" :disabled="transferDisabled" @click="openTransferDialog('CROSS_GROUP')">
            跨组转单
          </el-button>
          <span class="ts-text-hint">组内换人需本组管理员审批；跨组需对方管理员接收并指派执行人</span>
        </div>

        <!-- ：自定义申请的表单内容（按「当初那版」schema 只读回显） -->
        <template v-if="custom">
          <h4 class="ts-order__section">申请内容</h4>
          <FormRenderer v-if="formData" :schema="formData.schema" :model-value="formData.data" readonly />
          <el-empty v-else :image-size="60" description="未能加载表单内容" />
        </template>

        <h4 class="ts-order__section">审批链路（提交时固化的快照）</h4>
        <!--  激活史：未激活节点默认不展示 —— 它代表「流程还没走到这里」，
             与 SKIPPED（走过但没命中）是完全不同的两件事，混在一起会让申请人以为单子被卡住。
             管理角色可展开完整骨架，用来排查「流程为什么停在这一步」。 -->
        <div v-if="canSeeSkeleton && inactiveCount > 0" class="ts-order__skeleton-toggle">
          <el-checkbox v-model="showSkeleton" size="small">
            显示未激活节点（{{ inactiveCount }}）
          </el-checkbox>
          <span class="ts-text-hint">未激活＝条件依赖审批过程中才产生的数据，尚未轮到判定</span>
        </div>
        <el-timeline v-if="visibleNodes.length > 0">
          <el-timeline-item
            v-for="node in visibleNodes"
            :key="node.id"
            :timestamp="nodeTimestamp(node)"
            placement="top"
            :type="approvalNodeTagType(node.status)"
          >
            <div
              class="ts-order__node"
              :class="{ 'is-skipped': node.status === 'SKIPPED', 'is-inactive': node.status === 'INACTIVE' }"
            >
              <!-- ：抄送节点不是审批步骤，因此不展示或签/会签，用「抄送」标签表明性质 -->
              <el-tag v-if="node.nodeType === 'CC'" size="small" type="success" effect="plain">抄送</el-tag>
              <!-- ：FLOW 模式下节点带名字（如「财务复核」），用它替代「第 N 步」更可读；
                   没有名字时（借用单 / 分组审批）保持既有的「第 N 步 + 审批人」展示。
                   条件未命中的节点没有审批人（approver_id 为 NULL），用文案直说，而不是显示「-」。
                   ：「待上一节点指定」与「待指派」是两种不同的等待，必须分开说 ——
                   混成一句会让审批人以为系统出故障（实际是上一节点漏点了名）。
                   ：「未激活」是第三种等待 —— 连"要不要它审批"都还没定，必须单独说。 -->
              <template v-if="node.nodeName">
                <span class="ts-order__node-name">{{ node.nodeName }}</span>
                <span v-if="node.approverName" class="ts-order__node-approver">{{ node.approverName }}</span>
                <span v-else-if="node.status === 'SKIPPED'" class="ts-text-hint">无审批人</span>
                <span v-else-if="node.status === 'INACTIVE'" class="ts-text-hint">未激活</span>
                <span v-else-if="node.pendingAssign" class="ts-text-hint">待上一节点指定</span>
                <span v-else class="ts-text-hint">待指派</span>
              </template>
              <template v-else>
                <span class="ts-order__node-step">第 {{ node.stepOrder }} 步</span>
                <span class="ts-order__node-name">{{ node.approverName ?? '-' }}</span>
              </template>
              <el-tag v-if="node.nodeType !== 'CC'" size="small" effect="plain">{{ node.signTypeLabel }}</el-tag>
              <el-tag size="small" :type="approvalNodeTagType(node.status)" effect="plain">
                {{ nodeStatusText(node) }}
              </el-tag>
              <el-tag v-if="node.status === 'SKIPPED'" size="small" type="info" effect="plain">
                条件未命中
              </el-tag>
              <!-- ：审批时限与超时。超时由服务端判定（overdue），不拿本地时钟与 deadlineAt 比 -->
              <el-tag v-if="node.overdue" size="small" type="danger" effect="dark">
                已超时{{ node.overdueHours != null ? ` ${node.overdueHours} 小时` : '' }}
              </el-tag>
              <el-tag v-else-if="node.deadlineAt && node.status === 'PENDING'" size="small" type="info" effect="plain">
                限时：{{ node.deadlineAt }}
              </el-tag>
              <el-tag v-if="node.superBackup" size="small" type="warning" effect="dark">超管兜底</el-tag>
              <el-tag v-if="node.fallback" size="small" type="danger" effect="plain">原审批人离职</el-tag>
            </div>
            <p v-if="node.assignedByPrev" class="ts-text-hint ts-order__comment">
              本节点审批人由上一节点{{ node.assignedByName ? `「${node.assignedByName}」` : '' }}指定
            </p>
            <!-- ：激活说明（为什么这个节点现在是"未激活"，或它是怎么被激活的） -->
            <p v-if="node.runtimeReason" class="ts-text-hint ts-order__comment">{{ node.runtimeReason }}</p>
            <p v-if="node.conditionDesc" class="ts-text-hint ts-order__comment">{{ node.conditionDesc }}</p>
            <p v-if="node.actionComment" class="ts-text-secondary ts-order__comment">
              审批意见：{{ node.actionComment }}
            </p>
          </el-timeline-item>
        </el-timeline>
        <el-empty
          v-else
          :image-size="60"
          :description="emptyNodesDescription"
        />

        <!-- ：延期记录（，独立子工单快照，不并入主单审批链路） -->
        <template v-if="extends_.length > 0">
          <h4 class="ts-order__section">借用延期记录</h4>
          <div v-for="item in extends_" :key="item.id" class="ts-order__extend">
            <div class="ts-order__extend-head">
              <el-tag :type="extendStatusTagType(item.status)" size="small" effect="plain">
                {{ item.statusLabel }}
              </el-tag>
              <span class="ts-order__extend-time">发起于 {{ item.createdAt }}</span>
            </div>
            <div class="ts-order__extend-range">
              <span class="ts-text-hint">原定结束</span>
              <span>{{ item.originalEndTime ?? '-' }}</span>
              <span class="ts-order__extend-arrow">→</span>
              <span class="ts-text-hint">申请延长至</span>
              <strong>{{ item.newEndTime }}</strong>
            </div>
            <p class="ts-text-secondary ts-order__comment">延期原因：{{ item.reason }}</p>
            <p v-if="item.actionComment" class="ts-text-secondary ts-order__comment">
              审批意见：{{ item.actionComment }}
            </p>
            <el-timeline v-if="item.nodes.length > 0" class="ts-order__extend-nodes">
              <el-timeline-item
                v-for="node in item.nodes"
                :key="node.id"
                :timestamp="extendNodeTimestamp(node)"
                placement="top"
                :type="approvalNodeTagType(node.status)"
              >
                <div class="ts-order__node">
                  <span class="ts-order__node-step">第 {{ node.stepOrder }} 步</span>
                  <span class="ts-order__node-name">{{ node.approverName ?? '-' }}</span>
                  <el-tag size="small" effect="plain">{{ node.signTypeLabel }}</el-tag>
                  <el-tag size="small" :type="approvalNodeTagType(node.status)" effect="plain">
                    {{ nodeStatusText(node) }}
                  </el-tag>
                </div>
                <p v-if="node.actionComment" class="ts-text-secondary ts-order__comment">
                  审批意见：{{ node.actionComment }}
                </p>
              </el-timeline-item>
            </el-timeline>
            <p v-else class="ts-text-hint ts-order__comment">该延期无需审批，已直接生效</p>
          </div>
        </template>

        <!-- ：转交记录（；需求方「申请人可见转交记录」） -->
        <template v-if="(detail.transfers ?? []).length > 0">
          <h4 class="ts-order__section">转交记录</h4>
          <el-timeline>
            <el-timeline-item
              v-for="item in detail.transfers"
              :key="item.id"
              :timestamp="item.createdAt"
              placement="top"
              :type="item.transferType === 'AUTO_DIMISSION' ? 'warning' : 'info'"
            >
              <div class="ts-order__node">
                <span class="ts-order__node-name">{{ item.oldHandlerName ?? '-' }}</span>
                <span class="ts-text-hint">转交给</span>
                <span class="ts-order__node-name">{{ item.newHandlerName ?? '-' }}</span>
                <el-tag
                  size="small"
                  effect="plain"
                  :type="item.transferType === 'AUTO_DIMISSION' ? 'warning' : 'info'"
                >
                  {{ item.transferTypeLabel ?? item.transferType }}
                </el-tag>
              </div>
              <p v-if="item.transferComment" class="ts-text-secondary ts-order__comment">
                转交原因：{{ item.transferComment }}
              </p>
              <p class="ts-text-hint ts-order__comment">操作人：{{ item.transferOperatorName ?? '-' }}</p>
            </el-timeline-item>
          </el-timeline>
        </template>

        <!-- V55 / D5.7：转单申请记录（含被拒绝的历史 —— 那才是"为什么挂了三天"的解释） -->
        <template v-if="transferRequests.length > 0">
          <h4 class="ts-order__section">转单记录</h4>
          <el-timeline>
            <el-timeline-item
              v-for="item in transferRequests"
              :key="item.id"
              :timestamp="item.createdAt"
              placement="top"
              :type="transferRequestStatusTagType(item.status)"
            >
              <div class="ts-order__node">
                <el-tag size="small" effect="plain" type="info">
                  {{ item.transferScopeLabel ?? item.transferScope }}
                </el-tag>
                <span class="ts-order__node-name">{{ item.fromHandlerName ?? '-' }}</span>
                <span class="ts-text-hint">转交给</span>
                <span class="ts-order__node-name">{{ item.toHandlerName ?? '待对方指派' }}</span>
                <el-tag size="small" effect="plain" :type="transferRequestStatusTagType(item.status)">
                  {{ item.statusLabel ?? item.status }}
                </el-tag>
              </div>
              <p class="ts-text-hint ts-order__comment">
                小组：{{ item.fromDepartmentName ?? '-' }} → {{ item.toDepartmentName ?? '-' }}；
                待处理人：{{ item.toAdminName ?? '-' }}
              </p>
              <p class="ts-text-secondary ts-order__comment">转单原因：{{ item.reason }}</p>
              <p v-if="item.decideByName" class="ts-text-hint ts-order__comment">
                处理人：{{ item.decideByName }}（{{ item.decideAt ?? '-' }}）
                <template v-if="item.decideComment">；意见：{{ item.decideComment }}</template>
              </p>
              <!-- 处理入口：canDecide 由服务端判定（我是审批人 / 接收人 且 仍待处理） -->
              <div v-if="item.canDecide" class="ts-order__decide-bar">
                <el-button type="primary" size="small" @click="openDecide(item, true)">
                  {{ item.transferScope === 'CROSS_GROUP' ? '接收并分配' : '同意转单' }}
                </el-button>
                <el-button size="small" @click="openDecide(item, false)">拒绝</el-button>
              </div>
            </el-timeline-item>
          </el-timeline>
        </template>

        <!-- ：催办记录（需求方；催办不改变工单状态，只留痕 + 发消息） -->
        <template v-if="(detail.urges ?? []).length > 0">
          <h4 class="ts-order__section">催办记录</h4>
          <el-timeline>
            <el-timeline-item
              v-for="item in detail.urges"
              :key="item.id"
              :timestamp="item.createdAt"
              placement="top"
              :type="item.urgeType === 'RETURN' ? 'warning' : 'primary'"
            >
              <div class="ts-order__node">
                <el-tag size="small" effect="plain" :type="item.urgeType === 'RETURN' ? 'warning' : 'info'">
                  {{ item.urgeTypeLabel ?? item.urgeType }}
                </el-tag>
                <span class="ts-order__node-name">{{ item.operatorName ?? '-' }}</span>
                <span class="ts-text-hint">催</span>
                <span class="ts-order__node-name">{{ item.targetUserName ?? '-' }}</span>
                <el-tag v-if="item.nodeStepOrder != null" size="small" effect="plain">
                  第 {{ item.nodeStepOrder }} 步
                </el-tag>
              </div>
            </el-timeline-item>
          </el-timeline>
        </template>

        <!-- 超管强制干预记录（仅当存在时展示；强制操作不改变原审批链路，单独成块以免与审批快照混淆） -->
        <template v-if="(detail.forceOperations ?? []).length > 0">
          <h4 class="ts-order__section">强制干预记录</h4>
          <el-timeline>
            <el-timeline-item
              v-for="item in detail.forceOperations"
              :key="item.id"
              :timestamp="item.createdAt"
              placement="top"
              :type="forceOperationTagType(item.operationType)"
            >
              <div class="ts-order__node">
                <el-tag size="small" effect="plain" :type="forceOperationTagType(item.operationType)">
                  {{ item.operationTypeLabel }}
                </el-tag>
                <span class="ts-order__node-name">{{ item.operatorName ?? '-' }}</span>
              </div>
              <p v-if="item.oldStatusLabel && item.newStatusLabel" class="ts-text-secondary ts-order__comment">
                状态变化：{{ item.oldStatusLabel }} → {{ item.newStatusLabel }}
              </p>
              <p v-if="item.newApproverName" class="ts-text-secondary ts-order__comment">
                原审批人：{{ item.oldApproverName ?? '-' }} → {{ item.newApproverName }}
              </p>
              <p v-if="item.newHandlerName" class="ts-text-secondary ts-order__comment">
                原执行人：{{ item.oldHandlerName ?? '-' }} → {{ item.newHandlerName }}
              </p>
              <p class="ts-text-secondary ts-order__comment">原因：{{ item.reason }}</p>
            </el-timeline-item>
          </el-timeline>
        </template>

        <!-- ：附件——只读展示，无附件时整块隐藏 -->
        <template v-if="orderId != null">
          <h4 class="ts-order__section">附件</h4>
          <div class="ts-order__attach">
            <!-- ：自定义工单的附件用独立业务类型，与借用单的「申请附件」区分开 -->
            <AttachmentUpload
              v-if="custom"
              :biz-type="'CUSTOM_ORDER'"
              :biz-id="orderId"
              readonly
              hide-empty
              title="自定义工单附件"
            />
            <template v-else>
              <AttachmentUpload
                :biz-type="'APPLY_ATTACHMENT'"
                :biz-id="orderId"
                readonly
                hide-empty
                title="申请附件"
              />
              <AttachmentUpload
                :biz-type="'RETURN_PHOTO'"
                :biz-id="orderId"
                readonly
                hide-empty
                title="归还照片"
              />
            </template>
            <!-- 驳回附件两类工单都可能有（自定义申请被驳回时同样可附材料） -->
            <AttachmentUpload
              :biz-type="'REJECT_ATTACHMENT'"
              :biz-id="orderId"
              readonly
              hide-empty
              title="驳回附件"
            />
          </div>
        </template>

        <div v-if="$slots.actions" class="ts-order__actions">
          <slot name="actions" :detail="detail" />
        </div>
      </template>
      <el-empty v-else-if="!loading" :image-size="70" description="未能加载工单详情" />
    </div>

    <!-- 发起转单（同组 / 跨组）—— 嵌在详情内，避免用户在两页之间来回对照 -->
    <el-dialog
      v-model="transferForm.visible"
      :title="transferForm.scope === 'SAME_GROUP' ? '同组转单' : '跨组转单'"
      :width="isMobile ? '92%' : '480px'"
      append-to-body
    >
      <el-form label-width="88px" :label-position="isMobile ? 'top' : 'right'">
        <el-form-item v-if="transferForm.scope === 'SAME_GROUP'" label="接手人" required>
          <el-select
            v-model="transferForm.targetHandlerId"
            class="ts-order__field"
            placeholder="选择本组内的接手人"
            filterable
          >
            <el-option
              v-for="item in transferCandidates"
              :key="item.userId"
              :label="`${item.displayName ?? item.userId}（在办 ${item.inFlightCount ?? 0} 单）`"
              :value="item.userId"
            />
          </el-select>
          <div class="ts-text-hint">提交后需本组管理员审批；你本人是管理员时当场生效</div>
        </el-form-item>
        <el-form-item v-else label="目标小组" required>
          <el-select
            v-model="transferForm.targetDepartmentId"
            class="ts-order__field"
            placeholder="选择接收的处理小组"
            filterable
          >
            <el-option
              v-for="item in transferGroups"
              :key="item.departmentId"
              :label="`${item.departmentName} · ${item.adminName ?? '未配置管理员'}`"
              :value="item.departmentId"
              :disabled="item.selectable === false"
            />
          </el-select>
          <div class="ts-text-hint">由对方管理员接收后在本组指派执行人；对方也可拒绝退回</div>
        </el-form-item>
        <el-form-item label="转单原因" required>
          <el-input
            v-model="transferForm.reason"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="例如：出差在外，交由同事跟进"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="transferForm.visible = false">取消</el-button>
        <el-button type="primary" :loading="transferForm.saving" @click="submitTransfer">提交</el-button>
      </template>
    </el-dialog>

    <!-- 处理转单（同组审批 / 跨组接收并分配 / 拒绝） -->
    <el-dialog
      v-model="decideForm.visible"
      :title="decideForm.accepted ? '同意转单' : '拒绝转单'"
      :width="isMobile ? '92%' : '480px'"
      append-to-body
    >
      <el-form label-width="96px" :label-position="isMobile ? 'top' : 'right'">
        <el-form-item
          v-if="decideForm.accepted && decideForm.request?.transferScope === 'CROSS_GROUP'"
          label="本组执行人"
          required
        >
          <el-select
            v-model="decideForm.targetHandlerId"
            class="ts-order__field"
            placeholder="接收后指派给本组哪位成员"
            filterable
          >
            <el-option
              v-for="item in decideMembers"
              :key="item.userId"
              :label="item.displayName ?? String(item.userId)"
              :value="item.userId"
            />
          </el-select>
          <div class="ts-text-hint">接收即生效：执行人与处理小组会同时变更</div>
        </el-form-item>
        <el-form-item :label="decideForm.accepted ? '处理意见' : '拒绝原因'" :required="!decideForm.accepted">
          <el-input
            v-model="decideForm.comment"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            :placeholder="decideForm.accepted ? '选填' : '必填：让发起人知道下一步怎么办'"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="decideForm.visible = false">取消</el-button>
        <el-button
          :type="decideForm.accepted ? 'primary' : 'danger'"
          :loading="decideForm.saving"
          @click="submitDecide"
        >
          确认
        </el-button>
      </template>
    </el-dialog>
  </el-dialog>
</template>

<style scoped>
.ts-order__section {
  margin: 18px 0 12px;
  font-size: 14px;
  font-weight: 500;
}

/* V55：转单入口与处理按钮（与既有操作区风格一致，不加新色） */
.ts-order__transfer-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-top: 12px;
}

.ts-order__decide-bar {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}

/* 转单弹窗里的下拉占满一行（与申请页同一处理） */
.ts-order__field {
  width: 100%;
}

.ts-order__node {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.ts-order__node-step {
  color: var(--ts-text-secondary, #909399);
  font-size: 12px;
}

.ts-order__node-name {
  font-weight: 500;
}

/* ：FLOW 节点名旁边跟一个次要的审批人名字（两者都要显示，但主次分明） */
.ts-order__node-approver {
  font-size: 12px;
  color: var(--ts-text-secondary, #909399);
}

/* 条件未命中的节点：整行弱化，避免与真正走过的节点混为一谈 */
.ts-order__node.is-skipped .ts-order__node-name {
  font-weight: 400;
  color: var(--el-text-color-disabled);
}

/*  激活史：未激活节点比 SKIPPED 更弱 —— 它是"尚未发生"，
   用虚线边框 + 禁用文字色区分"走过但没命中"（实线弱化） */
.ts-order__node.is-inactive {
  padding: 4px 8px;
  border: 1px dashed var(--el-border-color);
  border-radius: 4px;
  background: var(--el-fill-color-lighter);
}

.ts-order__node.is-inactive .ts-order__node-name {
  font-weight: 400;
  color: var(--el-text-color-disabled);
}

/* 未激活节点开关：左对齐，提示与勾选框同一行，窄屏自动换行 */
.ts-order__skeleton-toggle {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin: 0 0 12px;
}

.ts-order__comment {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.6;
}

.ts-order__extend {
  padding: 12px;
  margin-bottom: 12px;
  border: 1px solid var(--ts-border, #ebeef5);
  border-radius: 10px;
  background: #fbfcfe;
}

.ts-order__extend-head {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.ts-order__extend-time {
  color: var(--ts-text-secondary, #909399);
  font-size: 12px;
}

.ts-order__extend-range {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-top: 8px;
  font-size: 13px;
}

.ts-order__extend-arrow {
  color: var(--ts-text-secondary, #909399);
}

.ts-order__extend-nodes {
  margin-top: 12px;
  padding-left: 4px;
}

.ts-order__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: flex-end;
  margin-top: 16px;
}

.ts-order__attach {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
</style>
