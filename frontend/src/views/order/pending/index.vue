<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { orderApi } from '@/api/order'
import { uploadAttachments } from '@/api/attachment'
import OrderDetailDialog from '@/components/OrderDetailDialog.vue'
import PendingAttachmentPicker from '@/components/PendingAttachmentPicker.vue'
import TablePage from '@/components/TablePage.vue'
import TransferButton from '@/components/TransferButton.vue'
import UrgeButton from '@/components/UrgeButton.vue'
import { useResponsive } from '@/composables/useResponsive'
import {
  RETURN_CONDITION_OPTIONS,
  isDueOrTimeout,
  orderStatusTagType,
  returnConditionPlaceholder,
  returnConditionRequiresRemark
} from '@/types/order'
import type { OrderItem, ReturnConditionCode } from '@/types/order'
import type { ColumnDef } from '@/types/table'

/**
 * 我的待处理（我是实际执行人：待交付 / 待收回 / 使用中， / ）
 *
 * 流程：
 * 1. 审批全部通过后系统按加权随机指派实际执行人 → 此页「确认交付」→ 工单/设备转「使用中」；
 * 2. 申请人发起归还后工单转「待收回」→ 此页「确认收回」并登记设备状态；
 * 3. 超时工单（borrowTimeout）执行人可**直接确认收回**，无需申请人先发起。
 *
 * 列表由后端按「超时优先」排序，超时工单排在最前，避免执行人漏看。
 *
 *  追加：确认收回时可随单上传「归还照片」，绑定工单 id，
 * 在收回成功后统一上传；照片可选、失败不阻断收回。
 */

const { isMobile } = useResponsive()

/**
 * 列定义（ · M3-B 迁入公共表格层）
 *
 * 逐列照抄改造前 `el-table-column` 的写法（宽度语义也照抄：长内容列一律 `minWidth`），
 * 因此**首次访问（无列偏好）时的渲染与改造前逐列一致**。
 * `key` 取**真实字段名**，这样没有自定义渲染的列可以直接走公共层的取值回落。
 * 「操作」列 `configurable: false` —— 锁定常显，不可被列设置隐藏。
 */
const columns: ColumnDef[] = [
  { key: 'orderNo', label: '工单编号', minWidth: 170, showOverflowTooltip: true },
  { key: 'deviceName', label: '设备', minWidth: 160, showOverflowTooltip: true },
  { key: 'applicantName', label: '申请人', width: 100 },
  { key: 'useTypeLabel', label: '借用类型', width: 110, align: 'center' },
  { key: 'statusLabel', label: '状态', width: 110, align: 'center' },
  { key: 'plannedEndTime', label: '计划归还', minWidth: 170, showOverflowTooltip: true },
  { key: 'returnNote', label: '归还说明', minWidth: 160, showOverflowTooltip: true },
  { key: 'createdAt', label: '提交时间', minWidth: 170, showOverflowTooltip: true },
  { key: 'action', label: '操作', width: 250, fixed: 'right', configurable: false }
]

const loading = ref(false)
const records = ref<OrderItem[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)

const detailVisible = ref(false)
const detailId = ref<number | null>(null)
const acting = ref(false)

/** 确认收回弹窗 */
const confirmVisible = ref(false)
const confirmTarget = ref<OrderItem | null>(null)
const confirmSubmitting = ref(false)
const confirmForm = reactive<{ condition: ReturnConditionCode; remark: string }>({
  condition: 'GOOD',
  remark: ''
})

/** 随收回提交的归还照片（本地待上传，，仅图片） */
const returnFiles = ref<File[]>([])

const confirmConditionEffect = computed(
  () => RETURN_CONDITION_OPTIONS.find((item) => item.value === confirmForm.condition)?.deviceEffect ?? ''
)

const confirmConditionLabel = computed(
  () => RETURN_CONDITION_OPTIONS.find((item) => item.value === confirmForm.condition)?.label ?? ''
)

/**
 * 说明是否必填。
 *
 * <p>与服务端 {@code ReturnCondition#isRemarkRequired} 同一口径。前端这一层只是
 * <b>提前拦住</b>，避免用户点完「确认收回」才收到 400 —— 服务端仍会独立校验一次，
 * 因为绕过界面直接调接口同样能提交（而「损坏但没说明」的记录事后无法追责）。
 */
const confirmRemarkRequired = computed(() => returnConditionRequiresRemark(confirmForm.condition))

/** 说明栏提示语：必填时直接告诉用户写什么，而不是干巴巴一句「必填」 */
const confirmRemarkPlaceholder = computed(() => returnConditionPlaceholder(confirmForm.condition))

onMounted(load)

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await orderApi.myHandling(page.value, size.value)
    records.value = result.records
    total.value = result.total
  } catch {
    records.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function handlePageChange(next: number): void {
  page.value = next
  void load()
}

function handleSizeChange(next: number): void {
  size.value = next
  page.value = 1
  void load()
}

function openDetail(order: OrderItem): void {
  detailId.value = order.id
  detailVisible.value = true
}

/** 仅「待交付」可确认交付 */
function canDeliver(order: OrderItem): boolean {
  return order.status === 'PENDING_DELIVERY'
}

/** 待收回，或超时的使用中工单（执行人可直接收回，）——由服务端判定 */
function canConfirm(order: OrderItem): boolean {
  return order.canConfirmReturn === true
}

/** 是否展示「已超时」红标 */
function isTimeout(order: OrderItem): boolean {
  return order.borrowTimeout === true
}

async function handleDeliver(order: OrderItem): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认已将设备「${order.deviceName ?? '-'}」交付给申请人「${order.applicantName ?? '-'}」？确认后工单进入「使用中」，设备状态同步变为「使用中」。`,
      '确认交付',
      { type: 'info', confirmButtonText: '确认已交付', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  acting.value = true
  try {
    await orderApi.deliver(order.id)
    ElMessage.success('交付完成，工单已进入使用中')
    detailVisible.value = false
    await load()
  } catch {
    // 状态已变化等非法操作由请求层统一提示
  } finally {
    acting.value = false
  }
}

/** 打开确认收回弹窗（登记设备状态 + 备注 + 归还照片） */
function openConfirm(order: OrderItem): void {
  confirmTarget.value = order
  confirmForm.condition = 'GOOD'
  confirmForm.remark = ''
  returnFiles.value = []
  confirmVisible.value = true
}

async function submitConfirm(): Promise<void> {
  const target = confirmTarget.value
  if (!target) {
    return
  }
  // 说明必填由服务端裁决，这里提前拦住（错误提示与后端文案保持一致，避免两套说法）
  if (confirmRemarkRequired.value && !confirmForm.remark.trim()) {
    ElMessage.warning(`检查结果为「${confirmConditionLabel.value}」时必须填写说明`)
    return
  }
  confirmSubmitting.value = true
  try {
    await orderApi.confirmReturn(target.id, confirmForm.condition, confirmForm.remark)
    // 归还照片：绑定工单 id，收回成功后统一上传；照片可选
    const attached = returnFiles.value.length > 0
      ? await uploadAttachments('RETURN_PHOTO', target.id, returnFiles.value)
      : 0
    ElMessage.success(attached > 0 ? `已确认收回，并上传 ${attached} 张照片` : '已确认收回，设备已归还')
    returnFiles.value = []
    confirmVisible.value = false
    detailVisible.value = false
    await load()
  } catch {
    // 错误由请求层统一提示
  } finally {
    confirmSubmitting.value = false
  }
}

function deviceText(order: OrderItem): string {
  return order.deviceName ?? '-'
}

function useTypeTagType(order: OrderItem): 'warning' | 'success' {
  return order.useType === 'SHORT_TERM' ? 'warning' : 'success'
}
</script>

<template>
  <div class="ts-page">
    <section class="ts-card">
      <div class="ts-flex-between ts-op__head">
        <div>
          <h3 class="ts-op__title">我的待处理</h3>
          <p class="ts-text-secondary ts-op__desc">
            我是实际执行人的工单：待交付需「确认交付」；待收回（或已超时）需「确认收回」并登记设备状态。
          </p>
        </div>
        <el-button :loading="loading" @click="load">刷新</el-button>
      </div>

      <TablePage
        :columns="columns"
        :rows="records"
        :loading="loading"
        route-path="/order/pending"
        :page="page"
        :size="size"
        :total="total"
        empty-text="暂无待您处理的工单"
        class="ts-mt-16"
        @update:page="handlePageChange"
        @update:size="handleSizeChange"
      >
        <template #cell-deviceName="{ row }">{{ deviceText(row as OrderItem) }}</template>
        <template #cell-useTypeLabel="{ row }">
          <el-tag :type="useTypeTagType(row as OrderItem)" size="small" effect="plain">
            {{ (row as OrderItem).useTypeLabel }}
          </el-tag>
        </template>
        <template #cell-statusLabel="{ row }">
          <el-tag :type="orderStatusTagType((row as OrderItem).status)" size="small" effect="plain">
            {{ (row as OrderItem).statusLabel }}
          </el-tag>
          <el-tag v-if="isTimeout(row as OrderItem)" type="danger" size="small" effect="dark" class="ts-op__timeout">
            已超时
          </el-tag>
        </template>
        <template #cell-plannedEndTime="{ row }">
          <span v-if="(row as OrderItem).plannedEndTime">{{ (row as OrderItem).plannedEndTime }}</span>
          <span v-else class="ts-text-hint">长期领用</span>
        </template>
        <template #cell-returnNote="{ row }">
          <span v-if="(row as OrderItem).returnNote">{{ (row as OrderItem).returnNote }}</span>
          <span v-else class="ts-text-hint">-</span>
        </template>
        <template #cell-action="{ row }">
          <el-button link type="primary" size="small" @click="openDetail(row as OrderItem)">详情</el-button>
          <TransferButton
            v-if="(row as OrderItem).canTransfer === true"
            :order="row as OrderItem"
            link
            @done="load"
          />
          <UrgeButton
            v-if="(row as OrderItem).status === 'BORROWED' && isDueOrTimeout(row as OrderItem)"
            :order="row as OrderItem"
            kind="return"
            link
            @done="load"
          />
          <el-button
            v-if="canDeliver(row as OrderItem)"
            link
            type="success"
            size="small"
            @click="handleDeliver(row as OrderItem)"
          >
            确认交付
          </el-button>
          <el-button
            v-if="canConfirm(row as OrderItem)"
            link
            type="warning"
            size="small"
            @click="openConfirm(row as OrderItem)"
          >
            确认收回
          </el-button>
        </template>

        <!--
          移动端卡片：刻意用 #mobile 插槽保留**改造前**的卡片结构（而不是由列定义生成）。
          这些卡片行与 PC 列并非一一对应（如「计划归还 / 归还说明」仅在有关时才出现），
          由列生成会改变既有移动端呈现 —— 与「零回归」冲突。
        -->
        <template #mobile>
          <div v-loading="loading" class="ts-op__cards">
            <div v-for="row in records" :key="row.id" class="ts-op__card">
              <div class="ts-flex-between">
                <strong class="ts-op__card-title">{{ deviceText(row) }}</strong>
                <div class="ts-op__tags">
                  <el-tag :type="orderStatusTagType(row.status)" size="small" effect="plain">
                    {{ row.statusLabel }}
                  </el-tag>
                  <el-tag v-if="isTimeout(row)" type="danger" size="small" effect="dark">已超时</el-tag>
                </div>
              </div>
              <div class="ts-op__card-row">
                <span class="ts-text-hint">工单编号</span>
                <span class="ts-op__card-value">{{ row.orderNo }}</span>
              </div>
              <div class="ts-op__card-row">
                <span class="ts-text-hint">申请人</span>
                <span>{{ row.applicantName || '-' }}</span>
              </div>
              <div class="ts-op__card-row">
                <span class="ts-text-hint">借用类型</span>
                <el-tag :type="useTypeTagType(row)" size="small" effect="plain">{{ row.useTypeLabel }}</el-tag>
              </div>
              <div v-if="row.plannedEndTime" class="ts-op__card-row">
                <span class="ts-text-hint">计划归还</span>
                <span>{{ row.plannedEndTime }}</span>
              </div>
              <div v-if="row.returnNote" class="ts-op__card-row">
                <span class="ts-text-hint">归还说明</span>
                <span>{{ row.returnNote }}</span>
              </div>
              <div class="ts-op__card-row">
                <span class="ts-text-hint">提交时间</span>
                <span>{{ row.createdAt }}</span>
              </div>
              <div class="ts-op__card-actions">
                <el-button size="small" @click="openDetail(row)">详情</el-button>
                <TransferButton v-if="row.canTransfer === true" :order="row" plain @done="load" />
                <UrgeButton
                  v-if="row.status === 'BORROWED' && isDueOrTimeout(row)"
                  :order="row"
                  kind="return"
                  plain
                  @done="load"
                />
                <el-button v-if="canDeliver(row)" size="small" type="success" plain @click="handleDeliver(row)">
                  确认交付
                </el-button>
                <el-button v-if="canConfirm(row)" size="small" type="warning" plain @click="openConfirm(row)">
                  确认收回
                </el-button>
              </div>
            </div>
            <el-empty
              v-if="!loading && records.length === 0"
              :image-size="70"
              description="暂无待您处理的工单"
            />
          </div>
        </template>
      </TablePage>
    </section>

    <OrderDetailDialog v-model="detailVisible" :order-id="detailId">
      <template #actions="{ detail }">
        <TransferButton v-if="detail.canTransfer === true" :order="detail" @done="load" />
        <UrgeButton
          v-if="detail.status === 'BORROWED' && isDueOrTimeout(detail)"
          :order="detail"
          kind="return"
          @done="load"
        />
        <el-button
          v-if="canDeliver(detail)"
          type="success"
          :loading="acting"
          @click="handleDeliver(detail)"
        >
          确认交付
        </el-button>
        <el-button v-if="canConfirm(detail)" type="warning" @click="openConfirm(detail)">确认收回</el-button>
      </template>
    </OrderDetailDialog>

    <!-- 确认收回：登记设备状态（ 第二步）+ 归还照片 -->
    <el-dialog v-model="confirmVisible" title="确认收回设备" :width="isMobile ? '94%' : '520px'">
      <div v-if="confirmTarget" class="ts-op__confirm">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="工单编号">{{ confirmTarget.orderNo }}</el-descriptions-item>
          <el-descriptions-item label="设备">{{ deviceText(confirmTarget) }}</el-descriptions-item>
          <el-descriptions-item label="申请人">{{ confirmTarget.applicantName ?? '-' }}</el-descriptions-item>
        </el-descriptions>

        <el-alert
          v-if="isTimeout(confirmTarget) && confirmTarget.status === 'BORROWED'"
          class="ts-mt-16"
          type="warning"
          :closable="false"
          show-icon
          title="该工单已超时"
          description="超时工单无需申请人先发起归还，执行人可直接确认收回；收回后设备恢复可用（或进入维修中）。"
        />

        <el-form class="ts-mt-16" label-width="90px">
          <el-form-item label="归还检查">
            <el-select v-model="confirmForm.condition" class="ts-op__confirm-select">
              <el-option
                v-for="item in RETURN_CONDITION_OPTIONS"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
            <p class="ts-text-hint ts-op__confirm-effect">{{ confirmConditionEffect }}</p>
          </el-form-item>
          <el-form-item label="检查说明" :required="confirmRemarkRequired">
            <el-input
              v-model="confirmForm.remark"
              type="textarea"
              :rows="3"
              maxlength="500"
              show-word-limit
              :placeholder="confirmRemarkPlaceholder"
            />
          </el-form-item>
          <!-- ：归还照片（，仅图片，可选） -->
          <el-form-item label="归还照片">
            <PendingAttachmentPicker v-model="returnFiles" biz-type="RETURN_PHOTO" label="实物照片" />
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="confirmVisible = false">取消</el-button>
        <el-button type="primary" :loading="confirmSubmitting" @click="submitConfirm">确认收回</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.ts-op__head {
  gap: 16px;
  align-items: flex-start;
}

.ts-op__title {
  margin: 0;
  font-size: 16px;
  font-weight: 500;
}

.ts-op__desc {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.6;
}

.ts-op__timeout {
  margin-left: 4px;
}

.ts-op__tags {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.ts-op__cards {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.ts-op__card {
  padding: 12px;
  border: 1px solid var(--ts-border);
  border-radius: 10px;
  background: #fbfcfe;
}

.ts-op__card-title {
  word-break: break-all;
}

.ts-op__card-value {
  word-break: break-all;
  text-align: right;
}

.ts-op__card-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 3px 0;
  font-size: 13px;
}

.ts-op__card-actions {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}

.ts-op__confirm-select {
  width: 100%;
}

.ts-op__confirm-effect {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.6;
}

@media (max-width: 767px) {
  .ts-op__head {
    flex-direction: column;
  }

  .ts-op__head .el-button {
    width: 100%;
  }

  .ts-op__card-actions .el-button {
    flex: 1 1 0;
    margin-left: 0;
  }
}
</style>
