<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import TablePage from '@/components/TablePage.vue'
import exceptionLogApi from '@/api/exceptionLog'
import type {
  ExceptionLogDetail,
  ExceptionLogItem,
  ExceptionLogOptions,
  ExceptionLogQuery,
  ExceptionLogStats
} from '@/types/exceptionLog'
import { alertStateHint, alertStateLabel, severityTagType } from '@/types/exceptionLog'
import type { ColumnDef } from '@/types/table'

/**
 * 异常日志—— 未预期异常的检索页。
 *
 * <h2>它和「操作日志」的区别（决定了本页的形态）</h2>
 * 操作日志记「谁做了什么」（审计面，管理员要看）；本页记「系统因为什么坏了」
 * （运维面，堆栈是给能改代码的人看的）。因此本页刻意**只归超管**
 * （权限码 `exception:view` 不在 admin 的默认集合里）。
 *
 * <h2>为什么只有查询、没有删除</h2>
 * 异常日志是取证数据 —— 能删就等于能销毁证据。超期行由每日清理任务按
 * `exception_log_retention_days` 统一删除，那是「策略」而不是「手滑」
 * （与操作日志同一取向）。
 *
 * <h2>堆栈只在详情里给</h2>
 * 一次事故可能上万行，把堆栈塞进列表响应会让接口变成几十 MB，而用户只会点开其中一行看。
 *
 * <h2>「未告警」必须能解释</h2>
 * 顶部概览里 `suppressed` 那一格如果只写个数字，管理员第一反应是「是不是漏发了」。
 * 因此每行都有状态提示（见 `alertStateHint`），把「按策略不告警」与「漏发」区分开。
 */

const loading = ref(false)
const list = ref<ExceptionLogItem[]>([])
const total = ref(0)
const stats = ref<ExceptionLogStats | null>(null)
const options = ref<ExceptionLogOptions>({ categories: [], severities: [], alertStates: [] })

/** 列定义（代码为事实源；本页筛选是排查型的，不接列配置持久化） */
const columns: ColumnDef[] = [
  { key: 'occurredAt', label: '发生时间', minWidth: 170, card: 'title' },
  { key: 'severity', label: '级别', width: 90, align: 'center' },
  { key: 'categoryLabel', label: '分类', width: 100, align: 'center' },
  { key: 'module', label: '模块', minWidth: 100, showOverflowTooltip: true },
  { key: 'exceptionClass', label: '异常类', minWidth: 240, showOverflowTooltip: true },
  { key: 'message', label: '消息', minWidth: 260, showOverflowTooltip: true },
  { key: 'requestUri', label: '接口', minWidth: 200, showOverflowTooltip: true },
  { key: 'ip', label: '来源 IP', width: 130, showOverflowTooltip: true },
  { key: 'alertState', label: '告警状态', width: 100, align: 'center' },
  // 操作列不可隐藏：隐藏它等于本页失去唯一的「看堆栈」入口
  { key: 'action', label: '操作', width: 90, fixed: 'right', configurable: false }
]

const query = reactive<ExceptionLogQuery>({
  page: 1,
  size: 20,
  category: '',
  severity: '',
  alertState: '',
  keyword: '',
  startTime: '',
  endTime: ''
})

/** 时间范围仅用于前端控件，提交时转为 startTime/endTime（yyyy-MM-dd HH:mm:ss） */
const timeRange = ref<[string, string] | null>(null)

const detailVisible = ref(false)
const detail = ref<ExceptionLogDetail | null>(null)
const detailLoading = ref(false)

/** 概览：积压多少条待告警是最该被看见的数字（它是「有没有漏发」的直接证据） */
const statCards = computed(() => {
  const current = stats.value
  return [
    { key: 'pending', label: '待告警', value: current?.pending ?? 0, hint: '等待下一轮汇总发送' },
    { key: 'sent', label: '已告警', value: current?.sent ?? 0, hint: '已通知超管' },
    { key: 'suppressed', label: '未告警', value: current?.suppressed ?? 0, hint: '按策略静默（开关关闭或分类被忽略）' },
    { key: 'total', label: '总计', value: current?.total ?? 0, hint: '保留期内全部记录' }
  ]
})

async function loadOptions(): Promise<void> {
  try {
    options.value = await exceptionLogApi.options()
  } catch {
    // 选项加载失败不阻塞列表：筛选降级为「只有全部」，用户仍能靠关键词与时间检索
    options.value = { categories: [], severities: [], alertStates: [] }
  }
}

async function loadStats(): Promise<void> {
  try {
    stats.value = await exceptionLogApi.stats()
  } catch {
    stats.value = null
  }
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await exceptionLogApi.page(query)
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
  // 日期控件是日期级，提交时补齐时分秒以对齐后端 yyyy-MM-dd HH:mm:ss
  if (timeRange.value) {
    query.startTime = `${timeRange.value[0]} 00:00:00`
    query.endTime = `${timeRange.value[1]} 23:59:59`
  } else {
    query.startTime = ''
    query.endTime = ''
  }
  query.page = 1
  void load()
}

function handleReset(): void {
  query.page = 1
  query.category = ''
  query.severity = ''
  query.alertState = ''
  query.keyword = ''
  query.startTime = ''
  query.endTime = ''
  timeRange.value = null
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

async function openDetail(row: ExceptionLogItem): Promise<void> {
  detailVisible.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await exceptionLogApi.detail(row.id)
  } catch {
    // 详情加载失败时把弹窗关掉 —— 留一个空弹窗比什么都不显示更让人困惑
    detailVisible.value = false
  } finally {
    detailLoading.value = false
  }
}

/** 模板里不做类型断言（项目约定），收窄放在 script */
function sevType(row: ExceptionLogItem): 'danger' | 'warning' | 'info' {
  return severityTagType(row.severity)
}

function stateLabel(row: ExceptionLogItem): string {
  return alertStateLabel(row.alertState)
}

function stateHint(row: ExceptionLogItem): string {
  return alertStateHint(row.alertState)
}

onMounted(() => {
  void loadOptions()
  void loadStats()
  void load()
})
</script>

<template>
  <div class="ts-page">
    <!-- 概览：先回答「现在积了多少」，再让人去翻明细 -->
    <section class="ts-card ts-mb-16 ts-exlog__stats">
      <div v-for="card in statCards" :key="card.key" class="ts-exlog__stat">
        <span class="ts-exlog__stat-label">{{ card.label }}</span>
        <strong class="ts-exlog__stat-value">{{ card.value }}</strong>
        <span class="ts-text-hint ts-exlog__stat-hint">{{ card.hint }}</span>
      </div>
    </section>

    <section class="ts-card ts-mb-16">
      <div class="ts-exlog__filters">
        <el-select v-model="query.category" placeholder="分类" clearable class="ts-exlog__field">
          <el-option label="全部分类" value="" />
          <el-option v-for="item in options.categories" :key="item.code" :label="item.label" :value="item.code" />
        </el-select>

        <el-select v-model="query.severity" placeholder="级别" clearable class="ts-exlog__field">
          <el-option label="全部级别" value="" />
          <el-option v-for="item in options.severities" :key="item.code" :label="item.label" :value="item.code" />
        </el-select>

        <el-select v-model="query.alertState" placeholder="告警状态" clearable class="ts-exlog__field">
          <el-option label="全部状态" value="" />
          <el-option v-for="item in options.alertStates" :key="item.code" :label="item.label" :value="item.code" />
        </el-select>

        <el-input
          v-model="query.keyword"
          placeholder="异常类 / 消息 / 接口"
          clearable
          class="ts-exlog__field ts-exlog__field--wide"
          @keyup.enter="handleSearch"
        />

        <el-date-picker
          v-model="timeRange"
          class="ts-exlog__field"
          type="daterange"
          value-format="YYYY-MM-DD"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          unlink-panels
        />

        <div class="ts-exlog__filter-actions">
          <el-button type="primary" :loading="loading" @click="handleSearch">查询</el-button>
          <el-button @click="handleReset">重置</el-button>
        </div>
      </div>
    </section>

    <section class="ts-card">
      <TablePage
        :columns="columns"
        :rows="list"
        :loading="loading"
        route-path="/system/exception-log"
        :page="query.page"
        :size="query.size"
        :total="total"
        empty-text="暂无异常记录"
        :show-column-config="false"
        show-jumper
        @update:page="handlePageChange"
        @update:size="handleSizeChange"
      >
        <template #cell-severity="{ row }">
          <el-tag :type="sevType(row)" size="small" effect="plain">{{ row.severity }}</el-tag>
        </template>

        <template #cell-alertState="{ row }">
          <el-tooltip :content="stateHint(row)" placement="top">
            <el-tag size="small" effect="plain">{{ stateLabel(row) }}</el-tag>
          </el-tooltip>
        </template>

        <template #cell-action="{ row }">
          <el-button link type="primary" size="small" @click="openDetail(row)">查看堆栈</el-button>
        </template>

        <!-- 手机：卡片列表（保留改造前结构；堆栈入口同样给到） -->
        <template #mobile>
          <div class="ts-exlog__cards">
            <div v-for="row in list" :key="row.id" class="ts-exlog__card">
              <div class="ts-flex-between ts-exlog__card-head">
                <strong>{{ row.categoryLabel }}</strong>
                <el-tag :type="sevType(row)" size="small" effect="plain">{{ row.severity }}</el-tag>
              </div>
              <div class="ts-exlog__card-row"><span class="ts-text-hint">时间</span><span>{{ row.occurredAt }}</span></div>
              <div class="ts-exlog__card-row"><span class="ts-text-hint">异常类</span><span>{{ row.exceptionClass }}</span></div>
              <div class="ts-exlog__card-row"><span class="ts-text-hint">接口</span><span>{{ row.requestUri ?? '-' }}</span></div>
              <div class="ts-exlog__card-row"><span class="ts-text-hint">告警</span><span>{{ stateLabel(row) }}</span></div>
              <p class="ts-exlog__card-msg">{{ row.message ?? '-' }}</p>
              <el-button link type="primary" size="small" @click="openDetail(row)">查看堆栈</el-button>
            </div>
            <el-empty v-if="!loading && list.length === 0" :image-size="70" description="暂无异常记录" />
          </div>
        </template>
      </TablePage>
    </section>

    <!-- 详情：堆栈是等宽字体 + 可换行，长行不横向滚动 -->
    <el-dialog v-model="detailVisible" title="异常详情" width="760px" append-to-body>
      <div v-loading="detailLoading">
        <template v-if="detail">
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="发生时间">{{ detail.item.occurredAt }}</el-descriptions-item>
            <el-descriptions-item label="分类">
              {{ detail.item.categoryLabel }}（{{ detail.item.category }}）
            </el-descriptions-item>
            <el-descriptions-item label="级别">
              <el-tag :type="severityTagType(detail.item.severity)" size="small" effect="plain">
                {{ detail.item.severity }} {{ detail.item.severityLabel }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="异常类">{{ detail.item.exceptionClass }}</el-descriptions-item>
            <el-descriptions-item label="消息">{{ detail.item.message ?? '-' }}</el-descriptions-item>
            <el-descriptions-item label="请求">
              {{ detail.item.httpMethod ?? '-' }} {{ detail.item.requestUri ?? '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="来源 IP">{{ detail.item.ip ?? '-' }}</el-descriptions-item>
            <el-descriptions-item label="traceId">{{ detail.item.traceId ?? '-' }}</el-descriptions-item>
            <el-descriptions-item label="告警状态">
              {{ alertStateLabel(detail.item.alertState) }} —— {{ alertStateHint(detail.item.alertState) }}
              <span v-if="detail.item.alertedAt" class="ts-text-hint">（{{ detail.item.alertedAt }}）</span>
            </el-descriptions-item>
          </el-descriptions>

          <h4 class="ts-exlog__stack-title">异常堆栈</h4>
          <pre class="ts-exlog__stack">{{ detail.stackTrace ?? '（无堆栈信息）' }}</pre>
        </template>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.ts-exlog__stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.ts-exlog__stat {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px 12px;
  border-radius: 6px;
  background: var(--el-fill-color-light);
}

.ts-exlog__stat-label {
  font-size: 13px;
  color: var(--ts-text-secondary);
}

.ts-exlog__stat-value {
  font-size: 22px;
  line-height: 1.2;
}

.ts-exlog__stat-hint {
  font-size: 12px;
}

.ts-exlog__filters {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.ts-exlog__field {
  width: 160px;
}

.ts-exlog__field--wide {
  width: 240px;
}

.ts-exlog__filter-actions {
  display: flex;
  gap: 8px;
  margin-left: auto;
}

.ts-exlog__cards {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.ts-exlog__card {
  padding: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
}

.ts-exlog__card-head {
  margin-bottom: 6px;
}

.ts-exlog__card-row {
  display: flex;
  gap: 8px;
  font-size: 13px;
  line-height: 1.9;
}

.ts-exlog__card-row > .ts-text-hint {
  flex: 0 0 56px;
}

.ts-exlog__card-msg {
  margin: 6px 0;
  font-size: 13px;
  color: var(--ts-text-secondary);
  word-break: break-all;
}

.ts-exlog__stack-title {
  margin: 16px 0 8px;
  font-size: 14px;
}

.ts-exlog__stack {
  max-height: 380px;
  margin: 0;
  padding: 12px;
  overflow: auto;
  font-family: Consolas, Monaco, 'Courier New', monospace;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
  background: var(--el-fill-color-light);
  border-radius: 6px;
}

@media (max-width: 768px) {
  .ts-exlog__stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .ts-exlog__field,
  .ts-exlog__field--wide {
    width: 100%;
  }

  .ts-exlog__filter-actions {
    margin-left: 0;
  }
}
</style>
