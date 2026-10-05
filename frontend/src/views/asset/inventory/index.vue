<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import TablePage from '@/components/TablePage.vue'
import { inventoryApi } from '@/api/inventory'
import {
  INVENTORY_SCOPE_OPTIONS,
  INVENTORY_STATUS_OPTIONS,
  inventoryStatusTagType,
  isInventoryInProgress
} from '@/types/inventory'
import type {
  InventoryReport,
  InventoryScopeCode,
  InventoryScopeOptions,
  InventoryTaskCreatePayload,
  InventoryTaskItem
} from '@/types/inventory'
import type { ColumnDef } from '@/types/table'

/**
 * 设备盘点 · 任务列表（P2）
 *
 * 一个任务的生命周期：**新建（按范围快照明细）→ 扫码核对 → 完成 → 出报告**。
 * 本页负责「建」与「看」，逐台核对在 `/asset/inventory/:id/check`（手机上身临现场用）。
 *
 * 两条与后端一致的取向（页面文案上也保持同一口径，避免两处说法不同）：
 * 1. **明细在创建时快照**：任务建好后，之后谁改了设备名 / 搬了位置都不影响本次盘点结果；
 * 2. **不自动改设备状态**：盘到「缺失」只进报告，不会把设备自动标成「已丢失」——
 *    缺失可能是放错地方或被临时拿走，确认找不回再由管理员手动标记。
 */

const router = useRouter()

const loading = ref(false)
const list = ref<InventoryTaskItem[]>([])
const total = ref(0)
const query = reactive({ page: 1, size: 20, status: '', keyword: '' })

const columns: ColumnDef[] = [
  { key: 'taskNo', label: '任务编号', minWidth: 150, card: 'title' },
  { key: 'taskName', label: '任务名称', minWidth: 180, showOverflowTooltip: true },
  { key: 'scopeLabel', label: '盘点范围', minWidth: 170, showOverflowTooltip: true },
  { key: 'statusLabel', label: '状态', minWidth: 90 },
  { key: 'progress', label: '盘点进度', minWidth: 190 },
  { key: 'abnormal', label: '异常', minWidth: 130 },
  { key: 'createdAt', label: '创建时间', minWidth: 160 },
  { key: 'actions', label: '操作', minWidth: 240 }
]

// ---------------------------------------------------------------------------
// 列表
// ---------------------------------------------------------------------------

async function load(): Promise<void> {
  loading.value = true
  try {
    const res = await inventoryApi.page({
      page: query.page,
      size: query.size,
      status: query.status || undefined,
      keyword: query.keyword.trim() || undefined
    })
    list.value = res.records
    total.value = res.total
  } catch {
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function handleSearch(): void {
  query.page = 1
  void load()
}

function handleReset(): void {
  query.page = 1
  query.status = ''
  query.keyword = ''
  void load()
}

function handlePageChange(next: number): void {
  query.page = next
  void load()
}

function handleSizeChange(next: number): void {
  query.size = next
  query.page = 1
  void load()
}

// ---------------------------------------------------------------------------
// 新建
// ---------------------------------------------------------------------------

const createVisible = ref(false)
const creating = ref(false)
const scopeOptions = ref<InventoryScopeOptions>({ categories: [], locations: [] })
const form = reactive<{ taskName: string; scopeType: InventoryScopeCode; scopeValue: string; remark: string }>({
  taskName: '',
  scopeType: 'ALL',
  scopeValue: '',
  remark: ''
})

/** 范围值选项：按范围类型显示对应下拉（ALL 不需要取值） */
const scopeValueOptions = computed(() =>
  form.scopeType === 'CATEGORY' ? scopeOptions.value.categories : scopeOptions.value.locations
)
const scopeValueLabel = computed(() =>
  form.scopeType === 'CATEGORY' ? '设备分类' : form.scopeType === 'LOCATION' ? '存放位置' : ''
)

async function openCreate(): Promise<void> {
  createVisible.value = true
  form.taskName = ''
  form.scopeType = 'ALL'
  form.scopeValue = ''
  form.remark = ''
  if (scopeOptions.value.categories.length === 0 && scopeOptions.value.locations.length === 0) {
    try {
      scopeOptions.value = await inventoryApi.scopeOptions()
    } catch {
      // 请求层已提示；下拉为空时用户仍可选「全部设备」创建
    }
  }
}

/** 切换范围类型时清空已选值 —— 分类 id 与位置名不是一回事，留着会提交出错误的范围 */
function handleScopeTypeChange(): void {
  form.scopeValue = ''
}

async function submitCreate(): Promise<void> {
  if (!form.taskName.trim()) {
    ElMessage.warning('请填写盘点任务名称')
    return
  }
  if (form.scopeType !== 'ALL' && !form.scopeValue) {
    ElMessage.warning(`请选择${scopeValueLabel.value}`)
    return
  }
  const payload: InventoryTaskCreatePayload = {
    taskName: form.taskName.trim(),
    scopeType: form.scopeType,
    scopeValue: form.scopeType === 'ALL' ? null : form.scopeValue,
    remark: form.remark.trim() || null
  }
  creating.value = true
  try {
    const id = await inventoryApi.create(payload)
    ElMessage.success('盘点任务已创建，设备明细已按当前台账快照')
    createVisible.value = false
    await load()
    // 直接进核对页：建完就是要盘，多一步点击只会让人觉得「是不是还得手动开始」
    void router.push(`/asset/inventory/${id}/check`)
  } catch {
    // 请求层已提示（范围内无设备 / 设备过多等都有明确文案）
  } finally {
    creating.value = false
  }
}

// ---------------------------------------------------------------------------
// 核对 / 报告 / 结束
// ---------------------------------------------------------------------------

function goCheck(task: InventoryTaskItem): void {
  void router.push(`/asset/inventory/${task.id}/check`)
}

const reportVisible = ref(false)
const reportLoading = ref(false)
const report = ref<InventoryReport | null>(null)

async function openReport(task: InventoryTaskItem): Promise<void> {
  reportVisible.value = true
  reportLoading.value = true
  report.value = null
  try {
    report.value = await inventoryApi.report(task.id)
  } catch {
    reportVisible.value = false
  } finally {
    reportLoading.value = false
  }
}

async function handleComplete(task: InventoryTaskItem): Promise<void> {
  const rest = task.totalCount - task.checkedCount
  let remark = ''
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
        inputPlaceholder: '盘点结论（选填，会记入报告备注）',
        inputValidator: () => true
      }
    )
    remark = res.value ?? ''
  } catch {
    return
  }
  try {
    await inventoryApi.complete(task.id, remark.trim() || null)
    ElMessage.success('盘点已完成')
    await load()
  } catch {
    // 请求层已提示
  }
}

async function handleCancel(task: InventoryTaskItem): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认取消盘点任务「${task.taskName}」？取消后不能再核对，已核对的结果会保留在任务里。`,
      '取消盘点',
      { type: 'warning', confirmButtonText: '确认取消', cancelButtonText: '再想想' }
    )
  } catch {
    return
  }
  try {
    await inventoryApi.cancel(task.id)
    ElMessage.success('盘点已取消')
    await load()
  } catch {
    // 请求层已提示
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="ts-page ts-inventory">
    <section class="ts-card ts-inventory__filter">
      <el-select v-model="query.status" placeholder="状态" clearable class="ts-inventory__field">
        <el-option label="全部状态" value="" />
        <el-option
          v-for="item in INVENTORY_STATUS_OPTIONS"
          :key="item.value"
          :label="item.label"
          :value="item.value"
        />
      </el-select>
      <el-input
        v-model="query.keyword"
        placeholder="任务编号 / 名称"
        clearable
        class="ts-inventory__field"
        @keyup.enter="handleSearch"
      />
      <div class="ts-inventory__filter-actions">
        <el-button type="primary" :loading="loading" @click="handleSearch">查询</el-button>
        <el-button @click="handleReset">重置</el-button>
        <el-button type="primary" plain @click="openCreate">新建盘点任务</el-button>
      </div>
    </section>

    <section class="ts-card">
      <TablePage
        :columns="columns"
        :rows="list"
        :loading="loading"
        route-path="/asset/inventory"
        :page="query.page"
        :size="query.size"
        :total="total"
        empty-text="暂无盘点任务"
        :show-column-config="false"
        show-jumper
        @update:page="handlePageChange"
        @update:size="handleSizeChange"
      >
        <template #cell-statusLabel="{ row }">
          <el-tag :type="inventoryStatusTagType((row as InventoryTaskItem).status)" size="small" effect="plain">
            {{ (row as InventoryTaskItem).statusLabel }}
          </el-tag>
        </template>

        <template #cell-progress="{ row }">
          <div class="ts-inventory__progress">
            <el-progress
              :percentage="(row as InventoryTaskItem).progressPercent"
              :stroke-width="10"
              :show-text="false"
              class="ts-inventory__bar"
            />
            <span class="ts-text-hint">
              {{ (row as InventoryTaskItem).checkedCount }} / {{ (row as InventoryTaskItem).totalCount }}
            </span>
          </div>
        </template>

        <template #cell-abnormal="{ row }">
          <span v-if="(row as InventoryTaskItem).missingCount + (row as InventoryTaskItem).wrongLocationCount === 0"
                class="ts-text-hint">—</span>
          <span v-else>
            <el-tag v-if="(row as InventoryTaskItem).missingCount > 0" type="danger" size="small" effect="plain">
              缺失 {{ (row as InventoryTaskItem).missingCount }}
            </el-tag>
            <el-tag
              v-if="(row as InventoryTaskItem).wrongLocationCount > 0"
              type="warning"
              size="small"
              effect="plain"
              class="ts-inventory__gap"
            >
              位置不符 {{ (row as InventoryTaskItem).wrongLocationCount }}
            </el-tag>
          </span>
        </template>

        <template #cell-actions="{ row }">
          <el-button
            v-if="isInventoryInProgress((row as InventoryTaskItem).status)"
            link
            type="primary"
            @click="goCheck(row as InventoryTaskItem)"
          >
            扫码核对
          </el-button>
          <el-button link type="primary" @click="openReport(row as InventoryTaskItem)">查看报告</el-button>
          <el-button
            v-if="isInventoryInProgress((row as InventoryTaskItem).status)"
            link
            type="primary"
            @click="handleComplete(row as InventoryTaskItem)"
          >
            完成
          </el-button>
          <el-button
            v-if="isInventoryInProgress((row as InventoryTaskItem).status)"
            link
            type="danger"
            @click="handleCancel(row as InventoryTaskItem)"
          >
            取消
          </el-button>
        </template>
      </TablePage>
    </section>

    <!-- 新建任务 -->
    <el-dialog v-model="createVisible" title="新建盘点任务" width="520px">
      <el-form label-width="96px" :label-position="'top'">
        <el-form-item label="任务名称" required>
          <el-input v-model="form.taskName" maxlength="100" placeholder="如：2026 Q4 研发部盘点" />
        </el-form-item>
        <el-form-item label="盘点范围" required>
          <el-radio-group v-model="form.scopeType" @change="handleScopeTypeChange">
            <el-radio-button v-for="item in INVENTORY_SCOPE_OPTIONS" :key="item.value" :value="item.value">
              {{ item.label }}
            </el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.scopeType !== 'ALL'" :label="scopeValueLabel" required>
          <el-select v-model="form.scopeValue" filterable :placeholder="`请选择${scopeValueLabel}`">
            <el-option v-for="item in scopeValueOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="500" show-word-limit />
        </el-form-item>
      </el-form>
      <p class="ts-text-hint">
        创建后系统会按**当前台账**把范围内设备的资产编号 / 名称 / 位置 / 状态一次性快照为明细；
        之后设备改名、搬位置都不影响本次盘点结果。
      </p>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">创建并开始盘点</el-button>
      </template>
    </el-dialog>

    <!-- 盘点报告 -->
    <el-dialog v-model="reportVisible" title="盘点报告" width="760px" top="6vh">
      <div v-loading="reportLoading">
        <template v-if="report">
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="任务">{{ report.taskNo }} · {{ report.taskName }}</el-descriptions-item>
            <el-descriptions-item label="范围">{{ report.scopeLabel }}</el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag :type="inventoryStatusTagType(report.status)" size="small" effect="plain">
                {{ report.statusLabel }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="进度">
              {{ report.checkedCount }} / {{ report.totalCount }}（{{ report.progressPercent }}%）
            </el-descriptions-item>
            <el-descriptions-item label="在库">{{ report.inPlaceCount }}</el-descriptions-item>
            <el-descriptions-item label="未核对">{{ report.uncheckedCount }}</el-descriptions-item>
            <el-descriptions-item label="缺失">
              <span :class="{ 'ts-inventory__danger': report.missingCount > 0 }">{{ report.missingCount }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="位置不符">{{ report.wrongLocationCount }}</el-descriptions-item>
          </el-descriptions>

          <template v-if="report.missingItems.length">
            <h4 class="ts-inventory__section">缺失设备（{{ report.missingItems.length }}）</h4>
            <el-table :data="report.missingItems" size="small" border>
              <el-table-column prop="assetNo" label="资产编号" min-width="140" />
              <el-table-column prop="deviceName" label="设备" min-width="160" show-overflow-tooltip />
              <el-table-column prop="storageLocation" label="台账位置" min-width="140" show-overflow-tooltip />
              <el-table-column prop="checkedByName" label="核对人" min-width="90" />
              <el-table-column prop="remark" label="说明" min-width="140" show-overflow-tooltip />
            </el-table>
          </template>

          <template v-if="report.wrongLocationItems.length">
            <h4 class="ts-inventory__section">位置不符（{{ report.wrongLocationItems.length }}）</h4>
            <el-table :data="report.wrongLocationItems" size="small" border>
              <el-table-column prop="assetNo" label="资产编号" min-width="140" />
              <el-table-column prop="deviceName" label="设备" min-width="160" show-overflow-tooltip />
              <el-table-column prop="storageLocation" label="台账位置" min-width="140" show-overflow-tooltip />
              <el-table-column prop="checkedByName" label="核对人" min-width="90" />
              <el-table-column prop="remark" label="说明" min-width="140" show-overflow-tooltip />
            </el-table>
          </template>

          <p v-if="!report.missingItems.length && !report.wrongLocationItems.length" class="ts-text-hint">
            本次盘点未发现缺失或位置不符的设备。
          </p>

          <p class="ts-text-hint ts-inventory__note">
            提示：盘到「缺失」**不会**自动把设备标记为「已丢失」——缺失可能是放错地方或被临时拿走。
            确认找不回时，请到设备台账手动标记为「已丢失」。
          </p>
        </template>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.ts-inventory__filter {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
}

.ts-inventory__field {
  width: 200px;
}

.ts-inventory__filter-actions {
  display: flex;
  gap: 8px;
  margin-left: auto;
}

.ts-inventory__progress {
  display: flex;
  align-items: center;
  gap: 8px;
}

.ts-inventory__bar {
  flex: 1;
  min-width: 70px;
}

.ts-inventory__gap {
  margin-left: 4px;
}

.ts-inventory__section {
  margin: 16px 0 8px;
  font-size: 14px;
}

.ts-inventory__danger {
  color: var(--el-color-danger);
  font-weight: 600;
}

.ts-inventory__note {
  margin-top: 16px;
}

/* 手机端（ / -531）：筛选项铺满整行，按钮换行不溢出 */
@media (max-width: 767px) {
  .ts-inventory__field {
    width: 100%;
  }

  .ts-inventory__filter-actions {
    width: 100%;
    margin-left: 0;
  }

  .ts-inventory__filter-actions :deep(.el-button) {
    flex: 1;
    margin-left: 0;
  }
}
</style>
