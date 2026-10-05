<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { orderApi } from '@/api/order'
import { uploadAttachments } from '@/api/attachment'
import OrderDetailDialog from '@/components/OrderDetailDialog.vue'
import PendingAttachmentPicker from '@/components/PendingAttachmentPicker.vue'
import TablePage from '@/components/TablePage.vue'
import { useResponsive } from '@/composables/useResponsive'
import { extendStatusTagType, orderStatusTagType } from '@/types/order'
import type { OrderExtendItem, OrderItem } from '@/types/order'
import type { ColumnDef } from '@/types/table'
import type { UserOption } from '@/types/user'

/**
 * 审批待办（轮到我审批的工单与延期， /  / ）
 *
 * 分为两个标签：
 * - 借用审批：当前步骤轮到我的借用工单；
 * - 延期审批：轮到我审批的**延期子工单**。
 * 两类审批的待办来源不同（主单快照 vs 延期快照），后端分表存放，
 * 因此前端也分开展示，避免「把延期审批混进主单审批」造成误操作。
 *
 * 语义：列表只包含「当前步骤轮到我、且尚可操作」的记录（后端按审批快照实时算当前步骤），
 * 因此这里不再按状态二次过滤。
 *
 *  追加：驳回时可随审批意见上传「驳回附件」。附件绑定工单 id，
 * 在主单驳回成功后统一上传；附件可选、失败不阻断驳回。延期的驳回不挂附件（ 四类中没有延期驳回）。
 */

const { isMobile } = useResponsive()

/**
 * 两个页签各一份列定义（ · M3-B）
 *
 * 两份偏好**按各自路由键隔离**（`/order/approval` 与 `/order/approval/extend`），
 * 否则在「借用审批」里关掉的列会连带把「延期审批」的列也关掉。
 * 其余约定见 `order/pending` 的同类注释：逐列照抄改造前写法、操作列锁定常显。
 */
const columns: ColumnDef[] = [
  { key: 'orderNo', label: '工单编号', minWidth: 170, showOverflowTooltip: true },
  { key: 'deviceName', label: '设备', minWidth: 160, showOverflowTooltip: true },
  { key: 'applicantName', label: '申请人', width: 100 },
  { key: 'useTypeLabel', label: '借用类型', width: 110, align: 'center' },
  { key: 'statusLabel', label: '状态', width: 100, align: 'center' },
  { key: 'currentStepOrder', label: '我的步骤', width: 100, align: 'center' },
  { key: 'createdAt', label: '提交时间', minWidth: 170, showOverflowTooltip: true },
  { key: 'action', label: '操作', width: 180, fixed: 'right', configurable: false }
]

const extendColumns: ColumnDef[] = [
  { key: 'orderNo', label: '关联工单', minWidth: 170, showOverflowTooltip: true },
  { key: 'applicantName', label: '申请人', width: 100 },
  { key: 'originalEndTime', label: '原定结束', minWidth: 160 },
  { key: 'newEndTime', label: '申请延长至', minWidth: 160 },
  { key: 'reason', label: '延期原因', minWidth: 180, showOverflowTooltip: true },
  { key: 'statusLabel', label: '状态', width: 110, align: 'center' },
  { key: 'createdAt', label: '发起时间', minWidth: 170, showOverflowTooltip: true },
  { key: 'action', label: '操作', width: 140, fixed: 'right', configurable: false }
]

type TabKey = 'order' | 'extend'
const activeTab = ref<TabKey>('order')

// --- 借用审批 ---
const loading = ref(false)
const records = ref<OrderItem[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)

// --- 延期审批 ---
const extendLoading = ref(false)
const extends_ = ref<OrderExtendItem[]>([])
const extendTotal = ref(0)
const extendPage = ref(1)
const extendSize = ref(10)

const detailVisible = ref(false)
const detailId = ref<number | null>(null)

/** 审批操作弹窗（通过 / 驳回共用，驳回必填意见）；kind 区分主单 / 延期 */
const actionVisible = ref(false)
const actionApproved = ref(true)
const actionKind = ref<'order' | 'extend'>('order')
const actionOrder = ref<OrderItem | null>(null)
const actionExtend = ref<OrderExtendItem | null>(null)
const actionComment = ref('')
const acting = ref(false)

/** 随驳回提交的附件（本地待上传，） */
const rejectFiles = ref<File[]>([])

// ---------------------------------------------------------------------
// 「上一节点指定审批人」（ 补范围说明与权限口径）
// ---------------------------------------------------------------------

/** 需要指定的人数（0 = 下一步骤不是待指派节点，此时不显示选择器） */
const assignRequired = ref(0)
/** 已选定的下一节点审批人 */
const assignSelected = ref<number[]>([])
/**
 * 候选人员。
 *
 * 由服务端按该节点的 `assignScope` 给出（预置流程第 3 级 = IT执行人角色 ∪ IT运维组成员），
 * 并已排除申请人本人（自审回避）；提交时服务端还会再校验一次，
 * 这里只是不让用户选到一个必被拒的人。
 */
const assignCandidates = ref<UserOption[]>([])
/** 待指派节点的名称（如「IT执行人处理」），仅用于弹窗文案 */
const assignNodeName = ref('')
/** 可选范围的中文说明（服务端下发，与详情同源） */
const assignScopeLabel = ref('')
/** 是否限定了范围（false = 全部在职员工） */
const assignRestricted = ref(false)
const assignLoading = ref(false)

/**
 * 打开「通过」弹窗时，问一次「下一步要不要我点名、能从谁里点」。
 *
 * 为什么不再拼两个接口：改造前人数取自详情节点、候选取自 `/users/options`，
 * 而后者挂 `staff:view` —— 普通审批人（直属主管 / IT主管）请求 403，
 * 候选被 catch 吞成空数组，于是「必须指定 1 人才能通过」变成永远无法满足的前置条件，
 * 工单静默卡死。`assign-candidates` 一次给全人数 / 候选 / 范围说明，
 * 且与工单详情同权限口径（能看这笔单就能拿到候选）。
 *
 * 拉取失败仍不阻断审批：让服务端用权威报错说话，而不是前端自己造一个可能不准的拦截。
 */
async function loadAssignRequirement(orderId: number): Promise<void> {
  assignRequired.value = 0
  assignSelected.value = []
  assignCandidates.value = []
  assignNodeName.value = ''
  assignScopeLabel.value = ''
  assignRestricted.value = false
  assignLoading.value = true
  try {
    const result = await orderApi.assignCandidates(orderId)
    assignRequired.value = result.requiredCount ?? 0
    assignCandidates.value = result.candidates ?? []
    assignNodeName.value = result.nodeName ?? ''
    assignScopeLabel.value = result.assignScopeLabel ?? ''
    assignRestricted.value = result.restricted === true
  } catch {
    // 不拦审批：交给服务端判定
  } finally {
    assignLoading.value = false
  }
}

onMounted(load)

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await orderApi.pendingApproval(page.value, size.value)
    records.value = result.records
    total.value = result.total
  } catch {
    records.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

async function loadExtends(): Promise<void> {
  extendLoading.value = true
  try {
    const result = await orderApi.myExtendApproval(extendPage.value, extendSize.value)
    extends_.value = result.records
    extendTotal.value = result.total
  } catch {
    extends_.value = []
    extendTotal.value = 0
  } finally {
    extendLoading.value = false
  }
}

/** 切换到延期标签时按需加载，避免每次进入页面都多发一次请求 */
function handleTabChange(name: string | number): void {
  if (name === 'extend' && extends_.value.length === 0 && extendTotal.value === 0) {
    void loadExtends()
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

function handleExtendPageChange(next: number): void {
  extendPage.value = next
  void loadExtends()
}

function handleExtendSizeChange(next: number): void {
  extendSize.value = next
  extendPage.value = 1
  void loadExtends()
}

function openDetail(order: OrderItem): void {
  detailId.value = order.id
  detailVisible.value = true
}

function openAction(order: OrderItem, approved: boolean): void {
  actionKind.value = 'order'
  actionOrder.value = order
  actionExtend.value = null
  actionApproved.value = approved
  actionComment.value = ''
  rejectFiles.value = []
  assignRequired.value = 0
  assignSelected.value = []
  actionVisible.value = true
  // 只有「通过」才可能触发下一步的指派；驳回会把后续节点全部作废，不需要点名
  if (approved) {
    void loadAssignRequirement(order.id)
  }
}

function openExtendAction(item: OrderExtendItem, approved: boolean): void {
  actionKind.value = 'extend'
  actionExtend.value = item
  actionOrder.value = null
  actionApproved.value = approved
  actionComment.value = ''
  rejectFiles.value = []
  actionVisible.value = true
}

async function submitAction(): Promise<void> {
  const comment = actionComment.value.trim()
  if (!actionApproved.value && !comment) {
    ElMessage.warning('驳回必须填写审批意见')
    return
  }
  // ：下一步如果是「上一节点指定审批人」，必须选够人数才能通过
  if (actionKind.value === 'order' && actionApproved.value && assignRequired.value > 0) {
    if (assignSelected.value.length !== assignRequired.value) {
      ElMessage.warning(`下一步骤需指定 ${assignRequired.value} 位审批人，请选择后再通过`)
      return
    }
  }
  acting.value = true
  try {
    if (actionKind.value === 'order') {
      const target = actionOrder.value
      if (!target) {
        return
      }
      const nextApproverIds = assignRequired.value > 0 ? assignSelected.value : null
      await orderApi.approve(target.id, actionApproved.value, comment || null, nextApproverIds)
      // 驳回附件：仅驳回场景、仅主单；附件可选，失败不阻断
      const attached =
        !actionApproved.value && rejectFiles.value.length > 0
          ? await uploadAttachments('REJECT_ATTACHMENT', target.id, rejectFiles.value)
          : 0
      ElMessage.success(
        actionApproved.value
          ? '已通过该工单'
          : attached > 0
            ? `已驳回该工单，并上传 ${attached} 个附件`
            : '已驳回该工单'
      )
      rejectFiles.value = []
      actionVisible.value = false
      detailVisible.value = false
      if (records.value.length === 1 && page.value > 1) {
        page.value -= 1
      }
      await load()
    } else {
      const target = actionExtend.value
      if (!target) {
        return
      }
      await orderApi.approveExtend(target.id, actionApproved.value, comment || null)
      ElMessage.success(actionApproved.value ? '已通过该延期申请' : '已驳回该延期申请')
      actionVisible.value = false
      if (extends_.value.length === 1 && extendPage.value > 1) {
        extendPage.value -= 1
      }
      await loadExtends()
    }
  } catch {
    // 非当前节点 / 已被他人处理等冲突由请求层统一提示
  } finally {
    acting.value = false
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
      <div class="ts-flex-between ts-oa__head">
        <div>
          <h3 class="ts-oa__title">审批待办</h3>
          <p class="ts-text-secondary ts-oa__desc">
            仅展示当前轮到您审批的记录。借用审批按原审批链路推进；延期审批针对「延期子工单」，通过后主单计划归还时间自动更新（ / ）。
          </p>
        </div>
        <el-button :loading="activeTab === 'order' ? loading : extendLoading" @click="activeTab === 'order' ? load() : loadExtends()">
          刷新
        </el-button>
      </div>

      <el-tabs v-model="activeTab" class="ts-mt-16" @tab-change="handleTabChange">
        <el-tab-pane label="借用审批" name="order">
          <TablePage
            :columns="columns"
            :rows="records"
            :loading="loading"
            route-path="/order/approval"
            :page="page"
            :size="size"
            :total="total"
            empty-text="暂无待您审批的工单"
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
            </template>
            <template #cell-currentStepOrder="{ row }">
              <span v-if="(row as OrderItem).currentStepOrder != null">
                第 {{ (row as OrderItem).currentStepOrder }} 步
              </span>
              <span v-else class="ts-text-hint">-</span>
            </template>
            <template #cell-action="{ row }">
              <el-button link type="primary" size="small" @click="openDetail(row as OrderItem)">详情</el-button>
              <el-button link type="success" size="small" @click="openAction(row as OrderItem, true)">通过</el-button>
              <el-button link type="danger" size="small" @click="openAction(row as OrderItem, false)">驳回</el-button>
            </template>

            <!-- 移动端卡片保留改造前结构（行与 PC 列并非一一对应），见 order/pending 的同类说明 -->
            <template #mobile>
              <div v-loading="loading" class="ts-oa__cards">
                <div v-for="row in records" :key="row.id" class="ts-oa__card">
                  <div class="ts-flex-between">
                    <strong class="ts-oa__card-title">{{ deviceText(row) }}</strong>
                    <el-tag :type="orderStatusTagType(row.status)" size="small" effect="plain">
                      {{ row.statusLabel }}
                    </el-tag>
                  </div>
                  <div class="ts-oa__card-row">
                    <span class="ts-text-hint">工单编号</span>
                    <span class="ts-oa__card-value">{{ row.orderNo }}</span>
                  </div>
                  <div class="ts-oa__card-row">
                    <span class="ts-text-hint">申请人</span>
                    <span>{{ row.applicantName || '-' }}</span>
                  </div>
                  <div class="ts-oa__card-row">
                    <span class="ts-text-hint">借用类型</span>
                    <el-tag :type="useTypeTagType(row)" size="small" effect="plain">{{ row.useTypeLabel }}</el-tag>
                  </div>
                  <div class="ts-oa__card-row">
                    <span class="ts-text-hint">我的步骤</span>
                    <span>{{ row.currentStepOrder != null ? '第 ' + row.currentStepOrder + ' 步' : '-' }}</span>
                  </div>
                  <div class="ts-oa__card-row">
                    <span class="ts-text-hint">提交时间</span>
                    <span>{{ row.createdAt }}</span>
                  </div>
                  <div class="ts-oa__card-actions">
                    <el-button size="small" @click="openDetail(row)">详情</el-button>
                    <el-button size="small" type="success" plain @click="openAction(row, true)">通过</el-button>
                    <el-button size="small" type="danger" plain @click="openAction(row, false)">驳回</el-button>
                  </div>
                </div>
                <el-empty
                  v-if="!loading && records.length === 0"
                  :image-size="70"
                  description="暂无待您审批的工单"
                />
              </div>
            </template>
          </TablePage>
        </el-tab-pane>

        <el-tab-pane label="延期审批" name="extend">
          <TablePage
            :columns="extendColumns"
            :rows="extends_"
            :loading="extendLoading"
            route-path="/order/approval/extend"
            :page="extendPage"
            :size="extendSize"
            :total="extendTotal"
            empty-text="暂无待您审批的延期申请"
            @update:page="handleExtendPageChange"
            @update:size="handleExtendSizeChange"
          >
            <template #cell-originalEndTime="{ row }">
              <span class="ts-text-hint">{{ (row as OrderExtendItem).originalEndTime || '-' }}</span>
            </template>
            <template #cell-newEndTime="{ row }">
              <strong>{{ (row as OrderExtendItem).newEndTime }}</strong>
            </template>
            <template #cell-statusLabel="{ row }">
              <el-tag :type="extendStatusTagType((row as OrderExtendItem).status)" size="small" effect="plain">
                {{ (row as OrderExtendItem).statusLabel }}
              </el-tag>
            </template>
            <template #cell-action="{ row }">
              <el-button
                link
                type="success"
                size="small"
                @click="openExtendAction(row as OrderExtendItem, true)"
              >
                通过
              </el-button>
              <el-button
                link
                type="danger"
                size="small"
                @click="openExtendAction(row as OrderExtendItem, false)"
              >
                驳回
              </el-button>
            </template>

            <!-- 移动端卡片保留改造前结构（标题固定为「延期申请」而非关联工单号） -->
            <template #mobile>
              <div v-loading="extendLoading" class="ts-oa__cards">
                <div v-for="row in extends_" :key="row.id" class="ts-oa__card">
                  <div class="ts-flex-between">
                    <strong class="ts-oa__card-title">延期申请</strong>
                    <el-tag :type="extendStatusTagType(row.status)" size="small" effect="plain">
                      {{ row.statusLabel }}
                    </el-tag>
                  </div>
                  <div class="ts-oa__card-row">
                    <span class="ts-text-hint">关联工单</span>
                    <span class="ts-oa__card-value">{{ row.orderNo || '-' }}</span>
                  </div>
                  <div class="ts-oa__card-row">
                    <span class="ts-text-hint">申请人</span>
                    <span>{{ row.applicantName || '-' }}</span>
                  </div>
                  <div class="ts-oa__card-row">
                    <span class="ts-text-hint">原定结束</span>
                    <span>{{ row.originalEndTime || '-' }}</span>
                  </div>
                  <div class="ts-oa__card-row">
                    <span class="ts-text-hint">申请延长至</span>
                    <strong>{{ row.newEndTime }}</strong>
                  </div>
                  <div class="ts-oa__card-row">
                    <span class="ts-text-hint">延期原因</span>
                    <span class="ts-oa__card-value">{{ row.reason }}</span>
                  </div>
                  <div class="ts-oa__card-actions">
                    <el-button size="small" type="success" plain @click="openExtendAction(row, true)">通过</el-button>
                    <el-button size="small" type="danger" plain @click="openExtendAction(row, false)">驳回</el-button>
                  </div>
                </div>
                <el-empty
                  v-if="!extendLoading && extends_.length === 0"
                  :image-size="70"
                  description="暂无待您审批的延期申请"
                />
              </div>
            </template>
          </TablePage>
        </el-tab-pane>
      </el-tabs>
    </section>

    <OrderDetailDialog v-model="detailVisible" :order-id="detailId">
      <template #actions="{ detail }">
        <template v-if="detail.actionable">
          <el-button type="danger" plain @click="openAction(detail, false)">驳回</el-button>
          <el-button type="success" @click="openAction(detail, true)">通过</el-button>
        </template>
      </template>
    </OrderDetailDialog>

    <!-- 审批操作（主单 / 延期共用） -->
    <el-dialog
      v-model="actionVisible"
      :title="actionApproved ? '审批通过' : '审批驳回'"
      :width="isMobile ? '94%' : '480px'"
    >
      <p class="ts-text-secondary ts-oa__action-hint">
        <template v-if="actionKind === 'order'">
          工单：{{ actionOrder?.orderNo }}（设备：{{ actionOrder ? deviceText(actionOrder) : '-' }}）
        </template>
        <template v-else>
          延期申请：关联工单 {{ actionExtend?.orderNo || '-' }}，申请延长至 {{ actionExtend?.newEndTime }}
        </template>
      </p>
      <el-form label-width="80px" :label-position="isMobile ? 'top' : 'right'">
        <el-form-item :label="actionApproved ? '审批意见' : '驳回原因'" :required="!actionApproved">
          <el-input
            v-model="actionComment"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            :placeholder="actionApproved ? '选填' : '必填，请说明驳回原因'"
          />
        </el-form-item>
        <!-- ：驳回附件（，仅主单驳回场景，可选） -->
        <el-form-item v-if="actionKind === 'order' && !actionApproved" label="驳回附件">
          <PendingAttachmentPicker v-model="rejectFiles" biz-type="REJECT_ATTACHMENT" label="补充说明材料" />
        </el-form-item>
        <!--  / ：下一步骤是「上一节点指定审批人」时，通过前必须点名 -->
        <el-form-item
          v-if="actionKind === 'order' && actionApproved && assignRequired > 0"
          :label="assignNodeName ? `下一节点审批人（${assignNodeName}）` : '下一节点审批人'"
          required
        >
          <el-select
            v-model="assignSelected"
            multiple
            filterable
            :loading="assignLoading"
            :multiple-limit="assignRequired"
            class="ts-oa__assign"
            :placeholder="assignCandidates.length === 0 ? '当前范围内没有可选人员' : '请选择审批人姓名'"
          >
            <el-option
              v-for="user in assignCandidates"
              :key="user.id"
              :label="user.departmentName ? `${user.displayName}（${user.departmentName}）` : user.displayName"
              :value="user.id"
            />
          </el-select>
          <div class="ts-text-hint">
            必须选择 <b>{{ assignRequired }}</b> 位（已选 {{ assignSelected.length }} 位）。
            可选范围：<b>{{ assignScopeLabel || (assignRestricted ? '受限' : '不限制') }}</b>。
            该步骤由你指定审批人；指定后若此人离职或停用，轮到该步骤时会自动改由超级管理员兜底。
          </div>
          <!-- 候选为空是「配置未就绪」而不是「系统出错」：给出可执行的下一步，
               否则审批人只看到空下拉，会以为页面坏了而放弃审批。 -->
          <div v-if="!assignLoading && assignCandidates.length === 0" class="ts-oa__assign-empty">
            当前可选范围内没有在职人员。请先到「组织与人员」把执行人加入 IT运维组
            或授予「IT执行人」角色，再回到这里审批。
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="actionVisible = false">取消</el-button>
        <el-button
          :type="actionApproved ? 'success' : 'danger'"
          :loading="acting"
          @click="submitAction"
        >
          {{ actionApproved ? '确认通过' : '确认驳回' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.ts-oa__assign {
  width: 100%;
}

/* 候选为空的提示：用警告色而不是默认灰，避免被当成普通说明扫过去 */
.ts-oa__assign-empty {
  margin-top: 6px;
  color: var(--el-color-warning);
  font-size: 13px;
  line-height: 1.6;
}

.ts-oa__head {
  gap: 16px;
  align-items: flex-start;
}

.ts-oa__title {
  margin: 0;
  font-size: 16px;
  font-weight: 500;
}

.ts-oa__desc {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.6;
}

.ts-oa__cards {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.ts-oa__card {
  padding: 12px;
  border: 1px solid var(--ts-border);
  border-radius: 10px;
  background: #fbfcfe;
}

.ts-oa__card-title {
  word-break: break-all;
}

.ts-oa__card-value {
  word-break: break-all;
  text-align: right;
}

.ts-oa__card-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 3px 0;
  font-size: 13px;
}

.ts-oa__card-actions {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}

.ts-oa__action-hint {
  margin: 0 0 12px;
  font-size: 12px;
  line-height: 1.6;
}

@media (max-width: 767px) {
  .ts-oa__head {
    flex-direction: column;
  }

  .ts-oa__head .el-button {
    width: 100%;
  }

  .ts-oa__card-actions .el-button {
    flex: 1 1 0;
    margin-left: 0;
  }
}
</style>
