<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import ListQueryNotice from '@/components/ListQueryNotice.vue'
import TablePage from '@/components/TablePage.vue'
import { useExport } from '@/composables/useExport'
import { useListQuery } from '@/composables/useListQuery'
import usageApi from '@/api/usage'
import deviceApi from '@/api/device'
import userApi from '@/api/user'
import type { DeviceItem } from '@/types/device'
import type { UserOption } from '@/types/user'
import { USAGE_STATUS_OPTIONS, USAGE_TYPE_OPTIONS, type UsageRecordItem, type UsageScope } from '@/types/usage'
import type { ColumnDef } from '@/types/table'

/**
 * 使用记录（需求方三波·第一波·； 菜单 + 「形成完整使用记录」）
 *
 * 替代  遗留的占位页。一行 = 一笔借用工单，覆盖「谁 / 借什么 / 何时→何时」，
 * 支持「按设备」「按员工」两个维度回溯某台设备 / 某个人历史上的全部借用。
 *
 * 数据范围：由后端按当前角色的 data_scope 自动收窄（SELF / GROUP / ALL），
 * 前端不参与范围判定，因此 URL 传参无法越权。
 *
 * 导出（ / ）：走与设备台账 / 工单同一个导出框架 ——
 * 数据量小当场下载、超过阈值转后台生成，完成后站内消息通知；导出条件与列表筛选同源。
 *
 * ---
 *  · W4-E：迁入公共表格层 + 接入筛选记忆（`useListQuery`）。
 *
 * ## 本页是 `whitelist` 选项的首个真实用例（值得单独说明）
 *
 * `targetId`（「按设备 / 按员工」选中的**具体对象**）**刻意不持久化**：
 * 它的显示名称来自**远程检索的候选集**，而偏好回填发生在页面挂载时 —— 那时候选集是空的，
 * 于是下拉框会把一个裸数字 id 显示成「5」而不是「ThinkPad X1」。
 * 这不是「少记了一个字段」，而是**记住了一个读不懂的值**：用户看到筛选框里有东西，
 * 却不知道筛的是谁，且点开列表重选之前也无法清除（`clearable` 的 × 只在有值时可用）。
 * 同类问题在 W4-D 的候选池里已经踩过一次（`optionsFor` 补齐已选项标签），这里是同一课。
 *
 * 折中口径：**记维度、不记对象**。「按设备」这个维度（`scope`）会被记住，
 * 具体是哪台设备需要重选一次。提示条上用 `hint` 把这条规则讲给用户，避免被当成 bug。
 *
 * ## 第二个坑：日期区间是数组
 *
 * `timeRange` 是 `[string, string] | null` —— 「默认 null、有值时是数组」。
 * 它撞上了 `useListQuery#isCompatibleValue` 的一个真实缺陷：原实现里「默认值为 null」的分支
 * **只接受标量**，数组会被白名单静默丢弃 ⇒ 区间筛选**永远无法回填**（不报错、只是没记住）。
 * W4-E 已修正该函数（放宽为接受任意 JSON 值），本页是它的验证用例。
 */

/** 导出交互（同步 = 直接下载；异步 = 提示等消息通知，完成后到「导出记录」下载） */
const { loading: exporting, runExport } = useExport()

/** 列定义（代码为事实源；无列偏好时逐列等于改造前渲染） */
const columns: ColumnDef[] = [
  { key: 'orderNo', label: '工单号', minWidth: 150, showOverflowTooltip: true },
  { key: 'deviceName', label: '设备', minWidth: 140, showOverflowTooltip: true },
  { key: 'assetNo', label: '资产编号', minWidth: 130, showOverflowTooltip: true },
  { key: 'applicantName', label: '借用人', minWidth: 100, showOverflowTooltip: true },
  { key: 'departmentName', label: '部门', minWidth: 120, showOverflowTooltip: true },
  { key: 'useTypeLabel', label: '借用类型', minWidth: 100 },
  { key: 'statusLabel', label: '状态', minWidth: 100 },
  { key: 'createdAt', label: '提交时间', minWidth: 170 },
  { key: 'plannedEndTime', label: '计划归还', minWidth: 170 },
  { key: 'actualEndTime', label: '实际归还', minWidth: 170 },
  // 虚拟列：由 autoExtendCount + borrowTimeout 两个字段合成，没有对应的行字段
  { key: 'extendAndTimeout', label: '顺延 / 超时', minWidth: 110 }
]

const loading = ref(false)
const list = ref<UsageRecordItem[]>([])
const total = ref(0)
const page = ref(1)
/** 改造前默认每页 20 条（页面级约定，与其它列表页的 10 不同，迁移时逐字段照抄） */
const size = ref(20)

/**
 * 筛选条件（默认值逐字段 = 改造前首屏取值）。
 *
 * `whitelist` 显式排除 `targetId`（见文件头）；`scope` / `keyword` / `status` / `useType` / `timeRange`
 * 都是「值本身就能读懂」的筛选，正常记忆。
 */
const { query, remembered, persist, reset } = useListQuery({
  routePath: '/usage/records',
  defaults: {
    scope: '' as '' | UsageScope,
    targetId: null as number | null,
    keyword: '',
    status: '',
    useType: '',
    timeRange: null as [string, string] | null
  },
  whitelist: ['scope', 'keyword', 'status', 'useType', 'timeRange']
})

// 按设备 / 按员工 的候选集（远程搜索）
const deviceOptions = ref<DeviceItem[]>([])
const userOptions = ref<UserOption[]>([])
const optionLoading = ref(false)

const targetPlaceholder = computed(() => (query.scope === 'DEVICE' ? '输入设备名称 / 资产编号检索' : '输入员工姓名检索'))

async function searchDevices(keyword: string): Promise<void> {
  optionLoading.value = true
  try {
    const result = await deviceApi.page({ keyword: keyword || undefined, page: 1, size: 20 })
    deviceOptions.value = result.records
  } catch {
    deviceOptions.value = []
  } finally {
    optionLoading.value = false
  }
}

async function searchUsers(keyword: string): Promise<void> {
  optionLoading.value = true
  try {
    userOptions.value = await userApi.options({ keyword: keyword || undefined })
  } catch {
    userOptions.value = []
  } finally {
    optionLoading.value = false
  }
}

function searchTargets(keyword: string): void {
  if (query.scope === 'DEVICE') {
    void searchDevices(keyword)
  } else if (query.scope === 'USER') {
    void searchUsers(keyword)
  }
}

/** 切换维度时清空已选目标，避免「设备 ID」被当成「员工 ID」提交 */
function onScopeChange(): void {
  query.targetId = null
  void searchTargets('')
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await usageApi.records({
      page: page.value,
      size: size.value,
      scope: query.scope || null,
      targetId: query.scope ? query.targetId : null,
      keyword: query.keyword || null,
      status: query.status || null,
      useType: query.useType || null,
      startTime: query.timeRange ? `${query.timeRange[0]} 00:00:00` : null,
      endTime: query.timeRange ? `${query.timeRange[1]} 23:59:59` : null
    })
    list.value = result.records
    total.value = result.total
  } catch {
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function handleSearch(): void {
  page.value = 1
  void persist()
  void load()
}

/** 重置：恢复默认筛选 + 清除本页持久化偏好 */
async function handleReset(): Promise<void> {
  await reset()
  page.value = 1
  deviceOptions.value = []
  userOptions.value = []
  await load()
}

function handlePageChange(next: number): void {
  page.value = next
  void load()
}

/** 每页条数不属于筛选项，因此改它**不触发**持久化（只有翻页边界回到第 1 页是需要处理的） */
function handleSizeChange(next: number): void {
  size.value = next
  page.value = 1
  void load()
}

/**
 * 导出当前筛选条件下的全部使用记录（ / ）
 *
 * 「导出 = 当前列表视图」：把与列表查询同名的筛选参数原样透传（含「按设备 / 按员工」维度），
 * 后端复用同一套使用记录查询构造导出数据，避免「页面筛出来的」与「导出文件里的」对不上。
 * 列与表格逐列一致（见后端 UsageExportExcel）。
 */
async function handleExport(): Promise<void> {
  await runExport({
    type: 'USAGE',
    usage: {
      scope: query.scope || undefined,
      targetId: query.scope ? query.targetId ?? undefined : undefined,
      keyword: query.keyword.trim() || undefined,
      status: query.status || undefined,
      useType: query.useType || undefined,
      startTime: query.timeRange ? `${query.timeRange[0]} 00:00:00` : undefined,
      endTime: query.timeRange ? `${query.timeRange[1]} 23:59:59` : undefined
    }
  })
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="ts-page">
    <section class="ts-card ts-mb-16">
      <div class="ts-usage__filters">
        <el-radio-group v-model="query.scope" @change="onScopeChange">
          <el-radio-button value="">全部</el-radio-button>
          <el-radio-button value="DEVICE">按设备</el-radio-button>
          <el-radio-button value="USER">按员工</el-radio-button>
        </el-radio-group>

        <el-select
          v-if="query.scope"
          v-model="query.targetId"
          class="ts-usage__field"
          filterable
          remote
          clearable
          reserve-keyword
          :remote-method="searchTargets"
          :loading="optionLoading"
          :placeholder="targetPlaceholder"
        >
          <el-option
            v-for="item in query.scope === 'DEVICE' ? deviceOptions : userOptions"
            :key="item.id"
            :label="query.scope === 'DEVICE'
              ? `${(item as DeviceItem).deviceName}（${(item as DeviceItem).assetNo}）`
              : `${(item as UserOption).displayName}`"
            :value="item.id"
          />
        </el-select>

        <el-input
          v-model="query.keyword"
          placeholder="工单号 / 设备 / 资产编号 / 借用人"
          clearable
          class="ts-usage__field"
          @keyup.enter="handleSearch"
        />

        <el-select v-model="query.status" placeholder="工单状态" clearable class="ts-usage__field">
          <el-option v-for="item in USAGE_STATUS_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>

        <el-select v-model="query.useType" placeholder="借用类型" clearable class="ts-usage__field">
          <el-option v-for="item in USAGE_TYPE_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>

        <el-date-picker
          v-model="query.timeRange"
          class="ts-usage__field"
          type="daterange"
          value-format="YYYY-MM-DD"
          range-separator="至"
          start-placeholder="提交开始"
          end-placeholder="提交结束"
          unlink-panels
        />

        <div class="ts-usage__filter-actions">
          <el-button type="primary" :loading="loading" @click="handleSearch">查询</el-button>
          <el-button @click="handleReset">重置</el-button>
          <el-button :loading="exporting" @click="handleExport">导出 Excel</el-button>
        </div>
      </div>

      <ListQueryNotice
        :visible="remembered"
        hint="「按设备 / 按员工」选中的具体对象不记忆，需重新选择。"
        class="ts-mt-16"
        @clear="handleReset"
      />
    </section>

    <section class="ts-card">
      <TablePage
        :columns="columns"
        :rows="list"
        :loading="loading"
        route-path="/usage/records"
        row-key="orderId"
        :page="page"
        :size="size"
        :total="total"
        empty-text="暂无使用记录"
        show-jumper
        @update:page="handlePageChange"
        @update:size="handleSizeChange"
      >
        <template #cell-useTypeLabel="{ row }">{{ (row as UsageRecordItem).useTypeLabel ?? '-' }}</template>
        <template #cell-statusLabel="{ row }">
          <el-tag size="small" effect="plain">
            {{ (row as UsageRecordItem).statusLabel ?? (row as UsageRecordItem).status }}
          </el-tag>
        </template>
        <template #cell-plannedEndTime="{ row }">
          {{ (row as UsageRecordItem).plannedEndTime ?? '长期领用' }}
        </template>
        <template #cell-actualEndTime="{ row }">{{ (row as UsageRecordItem).actualEndTime ?? '-' }}</template>
        <template #cell-extendAndTimeout="{ row }">
          <span>{{ (row as UsageRecordItem).autoExtendCount ?? 0 }} 次</span>
          <el-tag
            v-if="(row as UsageRecordItem).borrowTimeout"
            type="danger"
            size="small"
            effect="plain"
            class="ts-usage__timeout"
          >
            超时
          </el-tag>
        </template>

        <!-- 移动端卡片：保留改造前结构（设备名 + 状态为标题行，其余逐项列表） -->
        <template #mobile>
          <div class="ts-usage__cards">
            <div v-for="row in list" :key="row.orderId" class="ts-usage__card">
              <div class="ts-flex-between ts-usage__card-head">
                <strong>{{ row.deviceName }}</strong>
                <el-tag size="small" effect="plain">{{ row.statusLabel ?? row.status }}</el-tag>
              </div>
              <div class="ts-usage__card-row"><span class="ts-text-hint">工单号</span><span>{{ row.orderNo }}</span></div>
              <div class="ts-usage__card-row"><span class="ts-text-hint">资产编号</span><span>{{ row.assetNo }}</span></div>
              <div class="ts-usage__card-row"><span class="ts-text-hint">借用人</span><span>{{ row.applicantName }}</span></div>
              <div class="ts-usage__card-row"><span class="ts-text-hint">借用类型</span><span>{{ row.useTypeLabel ?? '-' }}</span></div>
              <div class="ts-usage__card-row"><span class="ts-text-hint">提交时间</span><span>{{ row.createdAt ?? '-' }}</span></div>
              <div class="ts-usage__card-row"><span class="ts-text-hint">计划归还</span><span>{{ row.plannedEndTime ?? '长期领用' }}</span></div>
              <div class="ts-usage__card-row"><span class="ts-text-hint">实际归还</span><span>{{ row.actualEndTime ?? '-' }}</span></div>
              <div class="ts-usage__card-row">
                <span class="ts-text-hint">顺延 / 超时</span>
                <span>
                  {{ row.autoExtendCount ?? 0 }} 次
                  <el-tag v-if="row.borrowTimeout" type="danger" size="small" effect="plain">超时</el-tag>
                </span>
              </div>
            </div>
            <el-empty v-if="!loading && list.length === 0" :image-size="70" description="暂无使用记录" />
          </div>
        </template>
      </TablePage>
    </section>
  </div>
</template>

<style scoped>
.ts-mb-16 {
  margin-bottom: 16px;
}

.ts-usage__filters {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
}

.ts-usage__field {
  width: 200px;
}

.ts-usage__filter-actions {
  display: flex;
  gap: 8px;
}

.ts-usage__timeout {
  margin-left: 6px;
}

.ts-usage__cards {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.ts-usage__card {
  padding: 12px;
  border: 1px solid var(--ts-border);
  border-radius: 10px;
  background: #fbfcfe;
}

.ts-usage__card-head {
  margin-bottom: 8px;
  font-size: 14px;
}

.ts-usage__card-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 3px 0;
  font-size: 13px;
}

@media (max-width: 767px) {
  .ts-usage__field {
    width: 100%;
  }

  .ts-usage__filter-actions {
    width: 100%;
  }

  .ts-usage__filter-actions .el-button {
    flex: 1 1 0;
    margin-left: 0;
  }
}
</style>
