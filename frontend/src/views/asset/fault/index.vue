<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { deviceFaultApi } from '@/api/device'
import ListQueryNotice from '@/components/ListQueryNotice.vue'
import TablePage from '@/components/TablePage.vue'
import UserSelectDialog from '@/components/UserSelectDialog.vue'
import { useListQuery } from '@/composables/useListQuery'
import { useResponsive } from '@/composables/useResponsive'
import { FAULT_STATUS_OPTIONS, faultStatusTagType, deviceStatusTagType } from '@/types/device'
import type { DeviceFaultHandlePayload, DeviceFaultItem, FaultStatusCode } from '@/types/device'
import type { UserOption } from '@/types/user'
import type { ColumnDef } from '@/types/table'

/**
 * 设备故障记录（，仅 super_admin / admin）
 *
 * 来源三合一：借用人在使用中上报、管理员在台账直接登记、归还时登记为故障。
 * 处理动作：
 * - 维修完成：设备「维修中 → 可用」（要求设备当前处于维修中）；
 * - 标记报废：设备 → 已报废（使用中禁止； 状态机）。
 *
 * 说明：使用中上报的故障记录，其设备仍为「使用中」，需先归还并登记为故障，
 * 设备进入「维修中」后，此处才可登记维修完成或报废。
 *
 * ---
 *  · W4-E：迁入公共表格层（`TablePage` + 列自定义）并接入筛选记忆（`useListQuery`）。
 *
 * 三条零回归铁律的落点：
 * 1. **无列偏好时 = 原硬编码列**：`columns` 逐列照抄改造前 `el-table-column` 的 label 与宽度
 *    （宽度一律用 `min-width`，与改造前一致 —— Element Plus 的 `width` 会被弹性布局压缩）；
 * 2. **移动端保留原卡片**：走 `#mobile` 插槽。本页卡片行与 PC 列**并非一一对应**
 *    （PC 的「处理信息」是「时间 + 人」两行合一格，卡片里拆成「处理时间」一行且**仅在已处理时出现**），
 *    由列定义强行生成卡片一定会改变既有呈现；
 * 3. **操作列锁定**：`configurable: false` —— 用户关掉它等于页面直接坏掉。
 *
 * 筛选记忆（决策 D4 第 1 条铁律）：`defaults` 逐字段等于改造前首次进入的取值
 * （`deviceKeyword` 空串、`status` 为 `null` = 未筛选），因此**无偏好时首次请求的参数与改造前完全一致**。
 */

const { isMobile } = useResponsive()

/** 列定义（代码为事实源；无列偏好时逐列等于改造前渲染） */
const columns: ColumnDef[] = [
  { key: 'device', label: '设备', minWidth: 180, showOverflowTooltip: true },
  { key: 'orderNo', label: '关联工单', minWidth: 150, showOverflowTooltip: true },
  { key: 'faultDescription', label: '故障描述', minWidth: 200, showOverflowTooltip: true },
  { key: 'occurredAt', label: '发生时间', minWidth: 160, showOverflowTooltip: true },
  { key: 'reporterName', label: '上报人', width: 100 },
  { key: 'statusLabel', label: '故障状态', width: 100, align: 'center' },
  { key: 'deviceStatusLabel', label: '设备状态', width: 100, align: 'center' },
  { key: 'handledInfo', label: '处理信息', minWidth: 170, showOverflowTooltip: true },
  // P2：维修过程三项（人 / 费用 / 配件）。与「处理信息」（登记人 + 时间）分开两列 ——
  // 「谁登记的」与「谁动手修的」在维修外包场景下是两个人，合在一格里必然被读错。
  { key: 'repairInfo', label: '维修信息', minWidth: 190, showOverflowTooltip: true },
  { key: 'action', label: '操作', width: 180, fixed: 'right', configurable: false }
]

const loading = ref(false)
const records = ref<DeviceFaultItem[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)

/** 筛选条件（默认值 = 改造前首屏取值；仅这两个字段参与记忆，`page` / `size` 刻意不持久化） */
const { query, remembered, persist, reset } = useListQuery({
  routePath: '/asset/fault',
  defaults: { deviceKeyword: '', status: null as FaultStatusCode | null }
})

// 处理弹窗（维修完成 / 报废共用）
const handleVisible = ref(false)
const handleKind = ref<'repair' | 'scrap'>('repair')
const handleTarget = ref<DeviceFaultItem | null>(null)
const handleRemark = ref('')
const submitting = ref(false)

onMounted(load)

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await deviceFaultApi.page({
      page: page.value,
      size: size.value,
      deviceKeyword: query.deviceKeyword.trim() || undefined,
      status: query.status
    })
    records.value = result.records
    total.value = result.total
  } catch {
    records.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

/** 用户主动查询 = 唯一的落盘时机（D4 第 2 条：只有用户存了偏好才覆盖） */
function handleSearch(): void {
  page.value = 1
  void persist()
  void load()
}

/** 重置：恢复默认值 + 清除本页持久化偏好（D4 第 4 条：既有「重置」按钮同时清空持久化） */
async function handleReset(): Promise<void> {
  await reset()
  page.value = 1
  await load()
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

/** 待维修且设备处于维修中时才可登记维修完成 */
function canRepair(item: DeviceFaultItem): boolean {
  return item.status === 'PENDING_REPAIR' && item.deviceStatus === 'MAINTENANCE'
}

/** 待维修且设备为维修中 / 可用时可标记报废（使用中禁止，） */
function canScrap(item: DeviceFaultItem): boolean {
  return item.status === 'PENDING_REPAIR' && (item.deviceStatus === 'MAINTENANCE' || item.deviceStatus === 'AVAILABLE')
}

/** 待维修但设备仍在使用中：提示需先归还登记故障 */
function waitingReturn(item: DeviceFaultItem): boolean {
  return item.status === 'PENDING_REPAIR' && item.deviceStatus === 'IN_USE'
}

// P2 维修过程（仅「维修完成」填）：维修人可以留空（外送维修没有系统账号）
const repairerId = ref<number | null>(null)
const repairerName = ref('')
const repairCost = ref('')
const replacedParts = ref('')
const pickerVisible = ref(false)

function openHandle(item: DeviceFaultItem, kind: 'repair' | 'scrap'): void {
  handleKind.value = kind
  handleTarget.value = item
  handleRemark.value = ''
  // 每次打开都清空：上一次填的维修人/费用绝不能被带到下一台设备上 ——
  // 这类「表单残留」造成的错账（把 A 设备的维修费记到 B 上）事后极难发现
  repairerId.value = null
  repairerName.value = ''
  repairCost.value = ''
  replacedParts.value = ''
  handleVisible.value = true
}

function handleRepairerPicked(picked: UserOption[]): void {
  const first = picked[0]
  repairerId.value = first ? first.id : null
  repairerName.value = first ? first.displayName || first.username : ''
}

function clearRepairer(): void {
  repairerId.value = null
  repairerName.value = ''
}

/**
 * 维修费用输入 → 数值。
 *
 * 空串按**未填**处理（返回 null），而不是 0：
 * 「0 元维修」（保修期内免费）与「没填维修费」在统计里是两件事，
 * 混成一个 0 会让「平均维修成本」被大量无意义的 0 拉低。
 */
function parseCost(): number | null {
  const raw = repairCost.value.trim()
  if (!raw) {
    return null
  }
  const value = Number(raw)
  return Number.isFinite(value) ? value : null
}

async function submitHandle(): Promise<void> {
  const target = handleTarget.value
  if (!target) {
    return
  }
  submitting.value = true
  try {
    const isRepair = handleKind.value === 'repair'
    const payload: DeviceFaultHandlePayload = {
      remark: handleRemark.value.trim() || null,
      // 三项只在「维修完成」时提交：报废不涉及维修人/费用/配件，
      // 传过去后端也不会落库（scrap 分支根本不 set 这三列）
      repairerId: isRepair ? repairerId.value : null,
      repairCost: isRepair ? parseCost() : null,
      replacedParts: isRepair ? replacedParts.value.trim() || null : null
    }
    if (handleKind.value === 'repair') {
      await deviceFaultApi.repair(target.id, payload)
      ElMessage.success('已登记维修完成，设备回到可用')
    } else {
      await deviceFaultApi.scrap(target.id, payload)
      ElMessage.success('设备已标记报废')
    }
    handleVisible.value = false
    await load()
  } catch {
    // 设备状态已变化等冲突由请求层统一提示
  } finally {
    submitting.value = false
  }
}

function deviceText(item: DeviceFaultItem): string {
  if (!item.deviceName) {
    return '-'
  }
  return item.assetNo ? `${item.deviceName}（${item.assetNo}）` : item.deviceName
}
</script>

<template>
  <div class="ts-page">
    <section class="ts-card">
      <div class="ts-flex-between ts-df__head">
        <div>
          <h3 class="ts-df__title">设备故障记录</h3>
          <p class="ts-text-secondary ts-df__desc">
            汇总借用人上报、台账登记、归还登记的故障。设备处于「维修中」时可登记维修完成或标记报废；使用中上报的故障需先归还并登记为故障。
          </p>
        </div>
        <el-button :loading="loading" @click="load">刷新</el-button>
      </div>

      <ListQueryNotice :visible="remembered" class="ts-mt-16" @clear="handleReset" />

      <!-- 查询条件 -->
      <div class="ts-df__filters ts-mt-16">
        <el-input
          v-model="query.deviceKeyword"
          class="ts-df__filter-keyword"
          placeholder="设备名称 / 资产编号"
          clearable
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
        <el-select v-model="query.status" class="ts-df__filter-item" placeholder="故障状态" clearable>
          <el-option v-for="item in FAULT_STATUS_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <div class="ts-df__filter-actions">
          <el-button type="primary" :loading="loading" @click="handleSearch">查询</el-button>
          <el-button @click="handleReset">重置</el-button>
        </div>
      </div>

      <TablePage
        :columns="columns"
        :rows="records"
        :loading="loading"
        route-path="/asset/fault"
        :page="page"
        :size="size"
        :total="total"
        empty-text="暂无故障记录"
        class="ts-mt-16"
        @update:page="handlePageChange"
        @update:size="handleSizeChange"
      >
        <template #cell-device="{ row }">{{ deviceText(row as DeviceFaultItem) }}</template>
        <template #cell-orderNo="{ row }">
          <span v-if="(row as DeviceFaultItem).orderNo">{{ (row as DeviceFaultItem).orderNo }}</span>
          <span v-else class="ts-text-hint">台账登记</span>
        </template>
        <template #cell-faultDescription="{ row }">{{ (row as DeviceFaultItem).faultDescription }}</template>
        <template #cell-occurredAt="{ row }">{{ (row as DeviceFaultItem).occurredAt }}</template>
        <template #cell-reporterName="{ row }">{{ (row as DeviceFaultItem).reporterName || '-' }}</template>
        <template #cell-statusLabel="{ row }">
          <el-tag :type="faultStatusTagType((row as DeviceFaultItem).status)" size="small" effect="plain">
            {{ (row as DeviceFaultItem).statusLabel }}
          </el-tag>
        </template>
        <template #cell-deviceStatusLabel="{ row }">
          <el-tag
            v-if="(row as DeviceFaultItem).deviceStatus"
            :type="deviceStatusTagType((row as DeviceFaultItem).deviceStatus!)"
            size="small"
            effect="plain"
          >
            {{ (row as DeviceFaultItem).deviceStatusLabel }}
          </el-tag>
          <span v-else class="ts-text-hint">-</span>
        </template>
        <template #cell-handledInfo="{ row }">
          <template v-if="(row as DeviceFaultItem).handledAt">
            <div>{{ (row as DeviceFaultItem).handledAt }}</div>
            <div class="ts-text-hint">{{ (row as DeviceFaultItem).handledByName || '-' }}</div>
          </template>
          <span v-else class="ts-text-hint">待处理</span>
        </template>
        <template #cell-repairInfo="{ row }">
          <template v-if="(row as DeviceFaultItem).repairerName || (row as DeviceFaultItem).repairCost != null || (row as DeviceFaultItem).replacedParts">
            <div v-if="(row as DeviceFaultItem).repairerName">维修人：{{ (row as DeviceFaultItem).repairerName }}</div>
            <div v-if="(row as DeviceFaultItem).repairCost != null" class="ts-text-hint">
              费用：¥{{ (row as DeviceFaultItem).repairCost }}
            </div>
            <div v-if="(row as DeviceFaultItem).replacedParts" class="ts-text-hint">
              配件：{{ (row as DeviceFaultItem).replacedParts }}
            </div>
          </template>
          <span v-else class="ts-text-hint">—</span>
        </template>
        <template #cell-action="{ row }">
          <el-button
            v-if="canRepair(row as DeviceFaultItem)"
            link
            type="success"
            size="small"
            @click="openHandle(row as DeviceFaultItem, 'repair')"
          >
            维修完成
          </el-button>
          <el-button
            v-if="canScrap(row as DeviceFaultItem)"
            link
            type="danger"
            size="small"
            @click="openHandle(row as DeviceFaultItem, 'scrap')"
          >
            报废
          </el-button>
          <span v-if="waitingReturn(row as DeviceFaultItem)" class="ts-text-hint">待归还登记故障</span>
          <span
            v-if="
              !canRepair(row as DeviceFaultItem) &&
              !canScrap(row as DeviceFaultItem) &&
              !waitingReturn(row as DeviceFaultItem)
            "
            class="ts-text-hint"
          >
            已处理
          </span>
        </template>

        <!-- 移动端卡片：保留改造前结构（「处理时间」仅在已处理时出现） -->
        <template #mobile>
          <div class="ts-df__cards">
            <div v-for="row in records" :key="row.id" class="ts-df__card">
              <div class="ts-flex-between">
                <strong class="ts-df__card-title">{{ deviceText(row) }}</strong>
                <el-tag :type="faultStatusTagType(row.status)" size="small" effect="plain">{{ row.statusLabel }}</el-tag>
              </div>
              <div class="ts-df__card-row">
                <span class="ts-text-hint">关联工单</span>
                <span class="ts-df__card-value">{{ row.orderNo || '台账登记' }}</span>
              </div>
              <div class="ts-df__card-row">
                <span class="ts-text-hint">故障描述</span>
                <span class="ts-df__card-value">{{ row.faultDescription }}</span>
              </div>
              <div class="ts-df__card-row">
                <span class="ts-text-hint">发生时间</span>
                <span>{{ row.occurredAt }}</span>
              </div>
              <div class="ts-df__card-row">
                <span class="ts-text-hint">上报人</span>
                <span>{{ row.reporterName || '-' }}</span>
              </div>
              <div class="ts-df__card-row">
                <span class="ts-text-hint">设备状态</span>
                <el-tag v-if="row.deviceStatus" :type="deviceStatusTagType(row.deviceStatus)" size="small" effect="plain">
                  {{ row.deviceStatusLabel }}
                </el-tag>
                <span v-else>-</span>
              </div>
              <div v-if="row.handledAt" class="ts-df__card-row">
                <span class="ts-text-hint">处理时间</span>
                <span>{{ row.handledAt }}</span>
              </div>
              <div class="ts-df__card-actions">
                <el-button v-if="canRepair(row)" size="small" type="success" plain @click="openHandle(row, 'repair')">
                  维修完成
                </el-button>
                <el-button v-if="canScrap(row)" size="small" type="danger" plain @click="openHandle(row, 'scrap')">
                  报废
                </el-button>
                <span v-if="waitingReturn(row)" class="ts-text-hint">待归还登记故障后可处理</span>
              </div>
            </div>
            <el-empty v-if="!loading && records.length === 0" :image-size="70" description="暂无故障记录" />
          </div>
        </template>
      </TablePage>
    </section>

    <!-- 维修人选择（P2）：复用通用用户选择器，单选取第一人 -->
    <UserSelectDialog v-model="pickerVisible" :multiple="false" @confirm="handleRepairerPicked" />

    <!-- 处理弹窗（维修完成 / 报废） -->
    <el-dialog
      v-model="handleVisible"
      :title="handleKind === 'repair' ? '登记维修完成' : '故障设备报废'"
      :width="isMobile ? '94%' : '460px'"
    >
      <p class="ts-text-secondary ts-df__hint">
        设备：{{ handleTarget ? deviceText(handleTarget) : '-' }}
      </p>
      <el-form label-width="90px" :label-position="isMobile ? 'top' : 'right'">
        <el-form-item :label="handleKind === 'repair' ? '维修说明' : '报废原因'">
          <el-input
            v-model="handleRemark"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            :placeholder="handleKind === 'repair' ? '选填，如：更换屏幕后恢复正常' : '选填，如：主板损坏无法修复'"
          />
        </el-form-item>

        <!-- P2：维修过程三项，仅「维修完成」需要 -->
        <el-form-item v-if="handleKind === 'repair'" label="实际维修人">
          <el-input :model-value="repairerName" readonly placeholder="选填；外送维修可留空">
            <template #append>
              <el-button @click="pickerVisible = true">选择</el-button>
            </template>
          </el-input>
          <el-button v-if="repairerId != null" link type="danger" @click="clearRepairer">清除</el-button>
        </el-form-item>
        <el-form-item v-if="handleKind === 'repair'" label="维修费用">
          <el-input v-model="repairCost" placeholder="元，如 320（可留空；保修内免费填 0）" />
        </el-form-item>
        <el-form-item v-if="handleKind === 'repair'" label="更换配件">
          <el-input v-model="replacedParts" maxlength="200" placeholder="选填，如：屏幕总成 ×1" />
        </el-form-item>
      </el-form>
      <p v-if="handleKind === 'scrap'" class="ts-text-secondary ts-df__warn">
        报废后设备进入「已报废」，不可再被借用；资产编号不可复用。
      </p>
      <template #footer>
        <el-button @click="handleVisible = false">取消</el-button>
        <el-button
          :type="handleKind === 'repair' ? 'success' : 'danger'"
          :loading="submitting"
          @click="submitHandle"
        >
          {{ handleKind === 'repair' ? '确认维修完成' : '确认报废' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.ts-df__head {
  gap: 16px;
  align-items: flex-start;
}

.ts-df__title {
  margin: 0;
  font-size: 16px;
  font-weight: 500;
}

.ts-df__desc {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.6;
}

.ts-df__filters {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.ts-df__filter-item {
  width: 170px;
}

.ts-df__filter-keyword {
  width: 240px;
}

.ts-df__filter-actions {
  display: flex;
  gap: 8px;
}

.ts-df__hint {
  margin: 0 0 12px;
  font-size: 12px;
  line-height: 1.6;
}

.ts-df__warn {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-color-danger);
}

.ts-df__cards {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.ts-df__card {
  padding: 12px;
  border: 1px solid var(--ts-border);
  border-radius: 10px;
  background: #fbfcfe;
}

.ts-df__card-title {
  word-break: break-all;
}

.ts-df__card-value {
  word-break: break-all;
  text-align: right;
}

.ts-df__card-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 3px 0;
  font-size: 13px;
}

.ts-df__card-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
}

@media (max-width: 767px) {
  .ts-df__head {
    flex-direction: column;
  }

  .ts-df__head .el-button {
    width: 100%;
  }

  .ts-df__filter-item,
  .ts-df__filter-keyword {
    width: 100%;
  }

  .ts-df__filter-actions .el-button {
    flex: 1 1 0;
  }

  .ts-df__card-actions .el-button {
    flex: 1 1 0;
    margin-left: 0;
  }
}
</style>
