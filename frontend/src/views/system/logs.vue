<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import TablePage from '@/components/TablePage.vue'
import systemApi from '@/api/system'
import { useExport } from '@/composables/useExport'
import { useUserStore } from '@/store/user'
import type { LogOption, OperationLogItem } from '@/types/system'
import type { ColumnDef } from '@/types/table'

/**
 * 操作日志（：只有 super_admin 可以查看日志）
 *
 * 响应式处理：
 * - PC / 平板：表格 + 分页
 * - 手机：表格转为卡片列表，字段纵向排布，避免横向滚动
 *
 * 后端已对齐中文化（见 backend GET /api/logs）：
 * 模块 / 动作 / 结果 / 风险均直接展示后端给的中文标签；
 * 列表「详情」列展示人类可读的 summary，失败原因已包含在 summary 中（不含堆栈）；
 * 原始技术详情 details 仅在「查看详情」对话框里展开。
 *
 * ---
 *  · W4-E：**T2 档** —— 只迁 `TablePage` 骨架，**不接筛选持久化**。
 *
 * 本页的筛选是**排查型**的（模块 / 动作 / 结果 / 操作人 / 日期区间，一年也用不了几次），
 * 把它持久化只会让「下次进来看到的是一份被过滤过的历史」成为常态。
 *
 * 分页样式：改造前桌面端是 `total, prev, pager, next, jumper`（带跳页、无每页条数），
 * 迁移后统一为 `TablePage` 的骨架并保留 `show-jumper` —— 即多出「每页条数」选择器。
 * 这是 T2「分页一致化」的应有结果（本页原本固定 20 条/页，用户无从调整）。
 */

const userStore = useUserStore()

/**
 * 是否允许导出：**仅超管**（P2 用户拍板 + ）。
 *
 * 注意这比「能看日志」更严一档：日志列表的 `log:view` 实际已授权给 admin（V34 补的），
 * 而导出会把日志整份带出系统 —— 「能在线看」与「能整体带走」本就是两种风险级别。
 * 按钮是**不显示**而不是置灰：与项目「无权限的入口不出现」的既有取向一致
 * （后端仍会独立校验，前端隐藏只是体验）。
 */
const canExport = computed(() => userStore.role === 'super_admin')

/** 导出交互（同步 = 直接下载；异步 = 提示等消息通知，完成后到「导出记录」下载） */
const { loading: exporting, runExport } = useExport()

const loading = ref(false)
const list = ref<OperationLogItem[]>([])
const total = ref(0)

// 模块 / 动作筛选项来自后端 GET /api/logs/options（值用 code、显示用 label）
const moduleOptions = ref<LogOption[]>([])
const actionOptions = ref<LogOption[]>([])

/** 列定义（代码为事实源；T2 不启用列设置） */
const columns: ColumnDef[] = [
  { key: 'operationTime', label: '操作时间', minWidth: 170, card: 'title' },
  { key: 'operatorName', label: '操作人', minWidth: 110, showOverflowTooltip: true },
  { key: 'moduleLabel', label: '模块', minWidth: 120, showOverflowTooltip: true },
  { key: 'actionLabel', label: '动作', minWidth: 150, showOverflowTooltip: true },
  { key: 'resultLabel', label: '结果', minWidth: 90 },
  { key: 'riskLabel', label: '风险', minWidth: 90 },
  { key: 'ip', label: 'IP', minWidth: 130, showOverflowTooltip: true },
  { key: 'detail', label: '详情', minWidth: 280 }
]

const query = reactive({
  page: 1,
  size: 20,
  module: '',
  action: '',
  operatorName: '',
  result: '',
  // 时间范围仅用于前端筛选控件，提交时转为 startTime/endTime（yyyy-MM-dd HH:mm:ss）
  timeRange: null as [string, string] | null,
  startTime: '',
  endTime: ''
})

// 结果筛选项：值 SUCCESS/FAILED，显示 成功/失败（失败行在结果列标红）
const RESULT_OPTIONS = [
  { label: '全部结果', value: '' },
  { label: '成功', value: 'SUCCESS' },
  { label: '失败', value: 'FAILED' }
]

// 「查看详情」对话框状态
const detailVisible = ref(false)
const currentLog = ref<OperationLogItem | null>(null)

async function loadOptions(): Promise<void> {
  try {
    const options = await systemApi.getLogOptions()
    moduleOptions.value = options.modules
    actionOptions.value = options.actions
  } catch {
    // 选项加载失败不影响列表：筛选降级为展示空（仅全部），且不阻塞主流程
    moduleOptions.value = []
    actionOptions.value = []
  }
}

/**
 * 导出当前筛选条件下的操作日志（P2）
 *
 * 「导出 = 当前列表视图」：把与列表查询同名的筛选参数原样透传，
 * 后端复用**同一处条件构造**（OperationLogQuerySupport）取数 ——
 * 这样「页面筛出来的」与「导出文件里的」必然是同一批数据。
 * 列与表格对齐，另外多带一列「技术详情」（列表页要点开才看得到，导出给审计用）。
 */
async function handleExport(): Promise<void> {
  await runExport({
    type: 'LOG',
    log: {
      module: query.module || undefined,
      action: query.action || undefined,
      result: query.result || undefined,
      operatorName: query.operatorName.trim() || undefined,
      startTime: query.startTime || undefined,
      endTime: query.endTime || undefined
    }
  })
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await systemApi.getOperationLogs(query)
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
  // 时间范围控件为日期级，提交时补齐时分秒以对齐后端 yyyy-MM-dd HH:mm:ss
  if (query.timeRange) {
    query.startTime = `${query.timeRange[0]} 00:00:00`
    query.endTime = `${query.timeRange[1]} 23:59:59`
  } else {
    query.startTime = ''
    query.endTime = ''
  }
  query.page = 1
  void load()
}

function handleReset(): void {
  query.page = 1
  query.module = ''
  query.action = ''
  query.operatorName = ''
  query.result = ''
  query.timeRange = null
  query.startTime = ''
  query.endTime = ''
  void load()
}

function handlePageChange(page: number): void {
  query.page = page
  void load()
}

function handleSizeChange(size: number): void {
  query.size = size
  query.page = 1
  void load()
}

function openDetail(row: OperationLogItem): void {
  currentLog.value = row
  detailVisible.value = true
}

onMounted(() => {
  void loadOptions()
  void load()
})
</script>

<template>
  <div class="ts-page">
    <section class="ts-card ts-mb-16">
      <div class="ts-logs__filters">
        <!-- 模块筛选：选项来自后端 /logs/options，值用 code、显示用中文 label -->
        <el-select v-model="query.module" placeholder="模块" clearable class="ts-logs__field">
          <el-option label="全部模块" value="" />
          <el-option v-for="item in moduleOptions" :key="item.code" :label="item.label" :value="item.code" />
        </el-select>

        <!-- 动作筛选：后端 /logs/options 未提供「模块→动作」映射关系，难以按模块联动过滤，
             故此处展示全部动作（值用 code、显示用中文 label），便于用户检索 -->
        <el-select v-model="query.action" placeholder="动作" clearable filterable class="ts-logs__field">
          <el-option label="全部动作" value="" />
          <el-option v-for="item in actionOptions" :key="item.code" :label="item.label" :value="item.code" />
        </el-select>

        <el-select v-model="query.result" placeholder="结果" clearable class="ts-logs__field">
          <el-option v-for="item in RESULT_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>

        <el-input
          v-model="query.operatorName"
          placeholder="操作人姓名"
          clearable
          class="ts-logs__field"
          @keyup.enter="handleSearch"
        />

        <!-- 时间范围筛选：保留原有能力，提交时转为 startTime/endTime -->
        <el-date-picker
          v-model="query.timeRange"
          class="ts-logs__field"
          type="daterange"
          value-format="YYYY-MM-DD"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          unlink-panels
        />

        <div class="ts-logs__filter-actions">
          <el-button type="primary" :loading="loading" @click="handleSearch">查询</el-button>
          <el-button @click="handleReset">重置</el-button>
          <!-- 仅超管（见 canExport 注释）：导出会把日志整份带出系统 -->
          <el-button v-if="canExport" :loading="exporting" @click="handleExport">导出 Excel</el-button>
        </div>
      </div>
    </section>

    <section class="ts-card">
      <TablePage
        :columns="columns"
        :rows="list"
        :loading="loading"
        route-path="/system/logs"
        :page="query.page"
        :size="query.size"
        :total="total"
        empty-text="暂无操作日志"
        :show-column-config="false"
        show-jumper
        class="ts-logs__table"
        @update:page="handlePageChange"
        @update:size="handleSizeChange"
      >
        <template #cell-resultLabel="{ row }">
          <el-tag
            :type="(row as OperationLogItem).result === 'SUCCESS' ? 'success' : 'danger'"
            size="small"
            effect="plain"
          >
            {{ (row as OperationLogItem).resultLabel }}
          </el-tag>
        </template>
        <template #cell-riskLabel="{ row }">
          <el-tag
            :type="(row as OperationLogItem).riskLevel === 'HIGH' ? 'warning' : 'info'"
            size="small"
            effect="plain"
          >
            {{ (row as OperationLogItem).riskLabel }}
          </el-tag>
        </template>
        <template #cell-detail="{ row }">
          <span class="ts-logs__summary" :title="(row as OperationLogItem).summary">
            {{ (row as OperationLogItem).summary }}
          </span>
          <el-button link type="primary" size="small" @click="openDetail(row as OperationLogItem)">查看详情</el-button>
        </template>

        <!-- 手机：卡片列表（保留改造前结构） -->
        <template #mobile>
          <div class="ts-logs__cards">
            <div v-for="row in list" :key="row.id" class="ts-logs__card">
              <div class="ts-flex-between ts-logs__card-head">
                <strong>{{ row.actionLabel }}</strong>
                <el-tag :type="row.result === 'SUCCESS' ? 'success' : 'danger'" size="small" effect="plain">
                  {{ row.resultLabel }}
                </el-tag>
              </div>
              <div class="ts-logs__card-row"><span class="ts-text-hint">时间</span><span>{{ row.operationTime }}</span></div>
              <div class="ts-logs__card-row"><span class="ts-text-hint">操作人</span><span>{{ row.operatorName ?? '-' }}</span></div>
              <div class="ts-logs__card-row"><span class="ts-text-hint">模块</span><span>{{ row.moduleLabel }}</span></div>
              <div class="ts-logs__card-row"><span class="ts-text-hint">风险</span><span>{{ row.riskLabel }}</span></div>
              <div class="ts-logs__card-row"><span class="ts-text-hint">IP</span><span>{{ row.ip ?? '-' }}</span></div>
              <p class="ts-logs__card-detail">{{ row.summary ?? '-' }}</p>
              <el-button link type="primary" size="small" @click="openDetail(row)">查看详情</el-button>
            </div>
            <el-empty v-if="!loading && list.length === 0" :image-size="70" description="暂无操作日志" />
          </div>
        </template>
      </TablePage>
    </section>

    <!-- 查看详情：展开原始技术信息，等宽字体 + 可换行，清晰可读 -->
    <el-dialog v-model="detailVisible" title="操作详情" width="640px" append-to-body>
      <template v-if="currentLog">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="操作人">{{ currentLog.operatorName ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="操作时间">{{ currentLog.operationTime }}</el-descriptions-item>
          <el-descriptions-item label="模块">{{ currentLog.moduleLabel }}（{{ currentLog.module }}）</el-descriptions-item>
          <el-descriptions-item label="动作">{{ currentLog.actionLabel }}（{{ currentLog.action }}）</el-descriptions-item>
          <el-descriptions-item label="结果">
            <el-tag :type="currentLog.result === 'SUCCESS' ? 'success' : 'danger'" size="small" effect="plain">
              {{ currentLog.resultLabel }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="风险级别">{{ currentLog.riskLabel }}（{{ currentLog.riskLevel }}）</el-descriptions-item>
          <el-descriptions-item label="IP">{{ currentLog.ip ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="TraceId">{{ currentLog.traceId ?? '-' }}</el-descriptions-item>
        </el-descriptions>

        <div class="ts-logs__detail-block">
          <div class="ts-logs__detail-title">技术详情（原始）</div>
          <pre class="ts-logs__raw">{{ currentLog.details ?? '无' }}</pre>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.ts-logs__filters {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
}

.ts-logs__field {
  width: 180px;
}

.ts-logs__filter-actions {
  display: flex;
  gap: 8px;
}

/* 详情列：摘要单行省略 + 原生 tooltip，右侧挂「查看详情」链接 */
.ts-logs__summary {
  display: inline-block;
  max-width: 200px;
  margin-right: 8px;
  overflow: hidden;
  vertical-align: middle;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 技术详情：等宽字体 + 保留换行与空格 */
.ts-logs__detail-block {
  margin-top: 16px;
}

.ts-logs__detail-title {
  margin-bottom: 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--ts-text-secondary);
}

.ts-logs__raw {
  margin: 0;
  padding: 12px;
  max-height: 320px;
  overflow: auto;
  border: 1px solid var(--ts-border);
  border-radius: 8px;
  background: #f7f8fa;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}

.ts-logs__cards {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.ts-logs__card {
  padding: 12px;
  border: 1px solid var(--ts-border);
  border-radius: 10px;
  background: #fbfcfe;
}

.ts-logs__card-head {
  margin-bottom: 8px;
  font-size: 14px;
}

.ts-logs__card-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 3px 0;
  font-size: 13px;
}

.ts-logs__card-detail {
  margin: 8px 0 4px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--ts-text-secondary);
  word-break: break-all;
}

@media (max-width: 767px) {
  .ts-logs__field {
    width: 100%;
  }

  .ts-logs__filter-actions {
    width: 100%;
  }

  .ts-logs__filter-actions .el-button {
    flex: 1 1 0;
    margin-left: 0;
  }
}
</style>
