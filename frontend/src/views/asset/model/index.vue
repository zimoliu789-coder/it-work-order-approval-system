<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { deviceModelApi } from '@/api/device'
import TablePage from '@/components/TablePage.vue'
import { useResponsive } from '@/composables/useResponsive'
import { useUserStore } from '@/store/user'
import {
  DEVICE_MODEL_STATUS_OPTIONS,
  type DeviceModelItem,
  type DeviceModelStatusCode
} from '@/types/device'
import type { ColumnDef } from '@/types/table'

/**
 * 设备型号字典管理（V56 / R3）
 *
 * <h2>为什么要有这一页</h2>
 * <p>改造前型号是台账里的一个自由文本框：同一种型号被写成
 * 「ThinkPad X1 / thinkpad x1 / X1 Carbon」，交付时无法按型号核对
 * （只能按分类），申请人也说不清「我要的到底是哪一种」。
 * 建字典后：型号有唯一 id，借用单按 id 申请，交付按 id 比对。
 *
 * <h2>为什么没有「删除」</h2>
 * <p>型号被台账（`device.model_id`）与历史工单（`borrow_order.model_id`）引用，
 * 删掉会留下悬空 id（台账里那一行会变成「型号未知」且无从修复）。
 * 要「清理」录错的型号，正确做法是<b>停用后新建一条</b> ——
 * 停用只影响「新增时还能不能选」，既有引用一字不动。
 * 后端也确实没有 DELETE 端点，这里不要补。
 *
 * <h2>「从台账补齐」是干什么的</h2>
 * <p>V56 迁移已经用台账里的文本建过一轮字典，但那之后新导入的设备可能又带着
 * 文本型号进来（导入模板暂时还是文本列）。这个按钮把 `model_id IS NULL` 的行
 * 再收敛一遍，可反复执行、幂等。
 *
 * 权限：列表 `device:ledger:view`；增改 / 启停 / 补齐 `device:ledger:manage`。
 * 与「设备台账」同一套权限码，不额外造新码（避免又一轮授权迁移）。
 */

const { isMobile } = useResponsive()
const userStore = useUserStore()

/** 能否维护（与后端 `device:ledger:manage` 同码；super_admin 由 hasPerm 短路放行） */
const canManage = computed(() => userStore.hasPerm('device:ledger:manage'))

const columns: ColumnDef[] = [
  { key: 'modelName', label: '型号', minWidth: 180, showOverflowTooltip: true },
  { key: 'brand', label: '品牌', minWidth: 120, showOverflowTooltip: true },
  { key: 'statusLabel', label: '状态', width: 100, align: 'center' },
  { key: 'deviceCount', label: '关联设备', width: 100, align: 'center' },
  { key: 'remark', label: '备注', minWidth: 160, showOverflowTooltip: true },
  { key: 'action', label: '操作', width: 150, fixed: 'right', configurable: false }
]

const loading = ref(false)
const records = ref<DeviceModelItem[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)

const query = reactive<{ keyword: string; status: DeviceModelStatusCode | null }>({
  keyword: '',
  status: null
})

const dialogVisible = ref(false)
const saving = ref(false)
const editingId = ref<number | null>(null)
const form = reactive({ modelName: '', brand: '', remark: '' })

const dialogTitle = computed(() => (editingId.value == null ? '新增型号' : '编辑型号'))

const syncing = ref(false)

onMounted(load)

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await deviceModelApi.page({
      page: page.value,
      size: size.value,
      keyword: query.keyword.trim() || undefined,
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

function handleSearch(): void {
  page.value = 1
  void load()
}

function handleReset(): void {
  query.keyword = ''
  query.status = null
  page.value = 1
  void load()
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

function openCreate(): void {
  editingId.value = null
  form.modelName = ''
  form.brand = ''
  form.remark = ''
  dialogVisible.value = true
}

function openEdit(row: DeviceModelItem): void {
  editingId.value = row.id
  form.modelName = row.modelName
  form.brand = row.brand ?? ''
  form.remark = row.remark ?? ''
  dialogVisible.value = true
}

async function handleSubmit(): Promise<void> {
  const modelName = form.modelName.trim()
  if (!modelName) {
    ElMessage.warning('请填写型号名称')
    return
  }
  const payload = {
    modelName,
    brand: form.brand.trim() ? form.brand.trim() : null,
    remark: form.remark.trim() ? form.remark.trim() : null
  }
  saving.value = true
  try {
    if (editingId.value == null) {
      await deviceModelApi.create(payload)
      ElMessage.success('型号已新增')
    } else {
      await deviceModelApi.update(editingId.value, payload)
      ElMessage.success('型号已更新')
    }
    dialogVisible.value = false
    await load()
  } catch {
    // 重名（大小写不敏感）等冲突由请求层统一提示
  } finally {
    saving.value = false
  }
}

/**
 * 启用 / 停用
 *
 * <p>停用前把影响面说清楚：该型号下仍有 N 台设备在用 —— 停用**不会**拆掉这层关联，
 * 只是新增时选不到它。真正需要提醒的是「我停用后，申请页里就没有这个型号了」。
 */
async function toggleStatus(row: DeviceModelItem): Promise<void> {
  const next: DeviceModelStatusCode = row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
  const actionText = next === 'DISABLED' ? '停用' : '启用'
  const effect =
    next === 'DISABLED'
      ? `停用后，新增设备与借用申请的下拉里将不再出现该型号；已关联的 ${
          row.deviceCount ?? 0
        } 台设备与历史工单不受影响。`
      : '启用后，该型号会重新出现在设备台账与借用申请的下拉里。'
  try {
    await ElMessageBox.confirm(`确认${actionText}型号「${row.modelName}」？${effect}`, `${actionText}型号`, {
      type: 'warning',
      confirmButtonText: `确认${actionText}`,
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await deviceModelApi.changeStatus(row.id, next)
    ElMessage.success(`型号已${actionText}`)
    await load()
  } catch {
    // 错误由请求层统一提示
  }
}

/** 从台账补齐：可反复执行，返回本次新建 / 回填 / 跳过的数量 */
async function handleSync(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '将扫描台账中「型号已填文本、但尚未归入字典」的设备，按文本去重后自动新建型号并回填关联。可重复执行，不会覆盖已归类的结果。',
      '从台账补齐型号',
      { type: 'info', confirmButtonText: '开始补齐', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  syncing.value = true
  try {
    const result = await deviceModelApi.syncFromLedger()
    ElMessage.success(
      `补齐完成：新建 ${result.createdCount} 个型号，回填 ${result.boundCount} 台设备` +
        (result.skippedCount > 0 ? `，跳过 ${result.skippedCount} 台（已有型号或文本为空）` : '')
    )
    await load()
  } catch {
    // 错误由请求层统一提示
  } finally {
    syncing.value = false
  }
}

function statusTagType(status: DeviceModelStatusCode): 'success' | 'info' {
  return status === 'ENABLED' ? 'success' : 'info'
}

function deviceCountText(row: DeviceModelItem): string {
  const count = row.deviceCount ?? 0
  return count > 0 ? `${count} 台` : '未使用'
}
</script>

<template>
  <div class="ts-page">
    <section class="ts-card">
      <div class="ts-flex-between ts-model__head">
        <div>
          <h3 class="ts-model__title">设备型号管理</h3>
          <p class="ts-text-secondary ts-model__desc">
            型号字典供「设备台账」与「借用申请」共用。型号被台账与历史工单引用，因此<b>只可停用、不可删除</b>；
            录错的型号请停用后新建一条。
          </p>
        </div>
        <div v-if="canManage" class="ts-model__head-actions">
          <el-button :loading="syncing" @click="handleSync">从台账补齐</el-button>
          <el-button type="primary" @click="openCreate">+ 新增型号</el-button>
        </div>
      </div>

      <div class="ts-model__filters ts-mt-16">
        <el-input
          v-model="query.keyword"
          class="ts-model__filter"
          placeholder="型号名称 / 品牌关键字"
          clearable
          @keyup.enter="handleSearch"
        />
        <el-select v-model="query.status" class="ts-model__filter" placeholder="全部状态" clearable>
          <el-option
            v-for="item in DEVICE_MODEL_STATUS_OPTIONS"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
        <el-button type="primary" @click="handleSearch">查询</el-button>
        <el-button @click="handleReset">重置</el-button>
      </div>

      <TablePage
        :columns="columns"
        :rows="records"
        :loading="loading"
        route-path="/asset/model"
        :page="page"
        :size="size"
        :total="total"
        :page-sizes="[20, 50, 100]"
        empty-text="暂无型号，可点「从台账补齐」或手动新增"
        class="ts-mt-16"
        @update:page="handlePageChange"
        @update:size="handleSizeChange"
      >
        <template #cell-brand="{ row }">
          <span v-if="(row as DeviceModelItem).brand">{{ (row as DeviceModelItem).brand }}</span>
          <span v-else class="ts-text-hint">-</span>
        </template>
        <template #cell-statusLabel="{ row }">
          <el-tag
            :type="statusTagType((row as DeviceModelItem).status)"
            size="small"
            effect="plain"
          >
            {{ (row as DeviceModelItem).statusLabel }}
          </el-tag>
        </template>
        <template #cell-deviceCount="{ row }">
          <span :class="{ 'ts-text-hint': (row as DeviceModelItem).deviceCount === 0 }">
            {{ deviceCountText(row as DeviceModelItem) }}
          </span>
        </template>
        <template #cell-remark="{ row }">
          <span v-if="(row as DeviceModelItem).remark">{{ (row as DeviceModelItem).remark }}</span>
          <span v-else class="ts-text-hint">-</span>
        </template>
        <template #cell-action="{ row }">
          <el-button link type="primary" size="small" @click="openEdit(row as DeviceModelItem)">详情</el-button>
          <el-button
            v-if="canManage"
            link
            :type="(row as DeviceModelItem).status === 'ENABLED' ? 'warning' : 'success'"
            size="small"
            @click="toggleStatus(row as DeviceModelItem)"
          >
            {{ (row as DeviceModelItem).status === 'ENABLED' ? '停用' : '启用' }}
          </el-button>
        </template>

        <!-- 移动端卡片：型号信息量小，直接由字段拼行比列生成更清晰 -->
        <template #mobile>
          <div v-loading="loading" class="ts-model__cards">
            <div v-for="row in records" :key="row.id" class="ts-model__card">
              <div class="ts-flex-between">
                <strong class="ts-model__card-title">{{ row.modelName }}</strong>
                <el-tag :type="statusTagType(row.status)" size="small" effect="plain">
                  {{ row.statusLabel }}
                </el-tag>
              </div>
              <div class="ts-model__card-row">
                <span class="ts-text-hint">品牌</span>
                <span>{{ row.brand || '-' }}</span>
              </div>
              <div class="ts-model__card-row">
                <span class="ts-text-hint">关联设备</span>
                <span>{{ deviceCountText(row) }}</span>
              </div>
              <div v-if="row.remark" class="ts-model__card-row">
                <span class="ts-text-hint">备注</span>
                <span>{{ row.remark }}</span>
              </div>
              <div class="ts-model__card-actions">
                <el-button size="small" @click="openEdit(row)">详情</el-button>
                <el-button
                  v-if="canManage"
                  size="small"
                  :type="row.status === 'ENABLED' ? 'warning' : 'success'"
                  plain
                  @click="toggleStatus(row)"
                >
                  {{ row.status === 'ENABLED' ? '停用' : '启用' }}
                </el-button>
              </div>
            </div>
            <el-empty
              v-if="!loading && records.length === 0"
              :image-size="70"
              description="暂无型号"
            />
          </div>
        </template>
      </TablePage>

      <p v-if="!canManage" class="ts-text-hint ts-mt-16">
        当前账号可查看型号字典，维护（新增 / 编辑 / 启停）需要「设备台账管理」权限
      </p>
    </section>

    <!-- 新增 / 编辑 -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" :width="isMobile ? '94%' : '460px'">
      <el-form label-width="80px" :label-position="isMobile ? 'top' : 'right'">
        <el-form-item label="型号名称" required>
          <el-input v-model="form.modelName" maxlength="128" show-word-limit placeholder="例如：ThinkPad X1 Carbon Gen11" />
          <div class="ts-text-hint">大小写不同会被视为同一型号（「X1」与「x1」不能并存）</div>
        </el-form-item>
        <el-form-item label="品牌">
          <el-input v-model="form.brand" maxlength="64" show-word-limit placeholder="选填，例如：联想" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="255" show-word-limit placeholder="选填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.ts-model__head {
  gap: 16px;
  align-items: flex-start;
}

.ts-model__title {
  margin: 0;
  font-size: 16px;
  font-weight: 500;
}

.ts-model__desc {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.6;
}

.ts-model__head-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.ts-model__filters {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.ts-model__filter {
  width: 200px;
}

.ts-model__cards {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.ts-model__card {
  padding: 12px;
  border: 1px solid var(--ts-border);
  border-radius: 10px;
  background: #fbfcfe;
}

.ts-model__card-title {
  word-break: break-all;
}

.ts-model__card-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 3px 0;
  font-size: 13px;
}

.ts-model__card-actions {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}

@media (max-width: 767px) {
  .ts-model__head {
    flex-direction: column;
  }

  .ts-model__head-actions {
    width: 100%;
  }

  .ts-model__head-actions :deep(.el-button) {
    flex: 1 1 0;
    margin-left: 0;
  }

  .ts-model__filters {
    flex-direction: column;
  }

  .ts-model__filter {
    width: 100%;
  }

  .ts-model__filters :deep(.el-button) {
    width: 100%;
    margin-left: 0;
  }

  .ts-model__card-actions :deep(.el-button) {
    flex: 1 1 0;
    margin-left: 0;
  }
}
</style>
