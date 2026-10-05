<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import TablePage from '@/components/TablePage.vue'
import securityLogApi from '@/api/securityLog'
import type {
  IpBlockItem,
  SecurityEventItem,
  SecurityLogOptions,
  SecurityLogQuery,
  SecurityOverview
} from '@/types/securityLog'
import { blockRemainText, blockSourceLabel, securityTagType } from '@/types/securityLog'
import type { ColumnDef } from '@/types/table'

/**
 * 安全日志—— 登录失败 / 账号锁定 / IP 封禁的检索与人工解封。
 *
 * <h2>为什么「当前封禁」放在最上面</h2>
 * 管理员打开这一页，九成是为了回答一个问题：**「是不是有人被挡住了？」**
 * （通常是同事打电话来说登不进来）。因此当前封禁列表必须在首屏，
 * 而不是藏在某个筛选条件后面。
 *
 * <h2>为什么「登录失败」是灰色</h2>
 * 它是最频繁的一类（正常用户也会输错密码）。染成红色会让整页变红，
 * 反而看不出「IP 封禁」这种真正表示「防护已介入」的行。见 `securityTagType`。
 *
 * <h2>为什么没有删除</h2>
 * 安全事件是取证数据，能删就等于能销毁证据。超期行由每日清理任务按
 * `security_event_retention_days` 统一删除。
 */

const loading = ref(false)
const list = ref<SecurityEventItem[]>([])
const total = ref(0)
const overview = ref<SecurityOverview | null>(null)
const options = ref<SecurityLogOptions>({ eventTypes: [] })
const acting = ref(false)

/** 列定义（代码为事实源；本页筛选是排查型的，不接列配置持久化） */
const columns: ColumnDef[] = [
  { key: 'occurredAt', label: '发生时间', minWidth: 170, card: 'title' },
  { key: 'eventTypeLabel', label: '事件类型', width: 120, align: 'center' },
  { key: 'username', label: '账号', minWidth: 120, showOverflowTooltip: true },
  { key: 'ip', label: '来源 IP', minWidth: 140, showOverflowTooltip: true },
  { key: 'detail', label: '说明', minWidth: 280, showOverflowTooltip: true },
  { key: 'userAgent', label: 'User-Agent', minWidth: 220, showOverflowTooltip: true }
]

const query = reactive<SecurityLogQuery>({
  page: 1,
  size: 20,
  eventType: '',
  ip: '',
  username: '',
  startTime: '',
  endTime: ''
})

const timeRange = ref<[string, string] | null>(null)

async function loadOptions(): Promise<void> {
  try {
    options.value = await securityLogApi.options()
  } catch {
    options.value = { eventTypes: [] }
  }
}

async function loadOverview(): Promise<void> {
  try {
    overview.value = await securityLogApi.overview(24)
  } catch {
    overview.value = null
  }
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await securityLogApi.page(query)
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
  query.eventType = ''
  query.ip = ''
  query.username = ''
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

/** 用被封的 IP 直接筛事件 —— 「他为什么被封」是这一页最常见的追问 */
function filterByIp(ip: string): void {
  query.ip = ip
  query.page = 1
  void load()
}

/**
 * 人工解封（二次确认）。
 *
 * <p>确认文案里刻意带上「永久」与否：永久封禁解掉之后不会自己回来，
 * 这是一个不可撤销的动作，必须让人看清。
 *
 * <p>形参用 `Record<string, any>` 而不是 `IpBlockItem`：Element Plus 的
 * `el-table` 插槽把行类型标注成它内部的 `DefaultRow`，写成具体类型会在
 * vue-tsc 下报「参数不可赋值」。类型收窄放在函数体第一行，模板里不做断言
 * （项目约定：模板禁 TS 断言）。
 */
async function handleUnblock(row: Record<string, any>): Promise<void> {
  const item = row as IpBlockItem
  const scope = item.permanent ? '这是**永久封禁**，解除后不会自动恢复。' : '该封禁到期后本会自动解除。'
  try {
    await ElMessageBox.confirm(
      `确定解除对 ${item.ip} 的封禁吗？\n${scope}\n原因：${item.reason ?? '-'}`,
      '解除 IP 封禁',
      { type: 'warning', confirmButtonText: '确定解除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  acting.value = true
  try {
    await securityLogApi.unblock(item.id)
    ElMessage.success(`已解除对 ${item.ip} 的封禁`)
    await Promise.all([loadOverview(), load()])
  } catch {
    // 失败提示已由请求层统一弹出
  } finally {
    acting.value = false
  }
}

function tagType(row: SecurityEventItem): 'danger' | 'warning' | 'info' {
  return securityTagType(row.eventType)
}

function remainText(row: Record<string, any>): string {
  return blockRemainText(row.expireAt as string | null)
}

onMounted(() => {
  void loadOptions()
  void loadOverview()
  void load()
})
</script>

<template>
  <div class="ts-page">
    <!-- 概览：当前封禁在最上面（管理员九成是为了回答「是不是有人被挡住了」） -->
    <section class="ts-card ts-mb-16">
      <div class="ts-seclog__head">
        <h3 class="ts-seclog__title">当前封禁</h3>
        <div class="ts-seclog__meta">
          <el-tag v-if="overview" :type="overview.ipBlockEnabled ? 'success' : 'info'" size="small" effect="plain">
            IP 封禁{{ overview.ipBlockEnabled ? '已启用' : '未启用' }}
          </el-tag>
          <span v-if="overview?.whitelist" class="ts-text-hint">白名单：{{ overview.whitelist }}</span>
          <span v-else class="ts-text-hint">未配置白名单</span>
        </div>
      </div>

      <el-table v-if="overview && overview.blocks.length > 0" :data="overview.blocks" size="small" border>
        <el-table-column prop="ip" label="IP" min-width="140" />
        <el-table-column label="来源" width="90">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ blockSourceLabel(row.source) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="reason" label="原因" min-width="200" show-overflow-tooltip />
        <el-table-column label="解封时间" min-width="180">
          <template #default="{ row }">
            <span :class="{ 'ts-seclog__permanent': row.permanent }">{{ remainText(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" :loading="acting" @click="handleUnblock(row)">
              解除封禁
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-else :image-size="60" description="当前没有被封禁的 IP" />

      <div v-if="overview && overview.topFailIps.length > 0" class="ts-seclog__top">
        <span class="ts-text-hint">近 {{ overview.windowHours }} 小时失败最多的 IP：</span>
        <el-tag
          v-for="(item, index) in overview.topFailIps"
          :key="index"
          class="ts-seclog__top-tag"
          size="small"
          effect="plain"
          type="warning"
          @click="filterByIp(String(item.ip))"
        >
          {{ item.ip }}（{{ item.total }} 次）
        </el-tag>
      </div>
    </section>

    <section class="ts-card ts-mb-16">
      <div class="ts-seclog__filters">
        <el-select v-model="query.eventType" placeholder="事件类型" clearable class="ts-seclog__field">
          <el-option label="全部类型" value="" />
          <el-option v-for="item in options.eventTypes" :key="item.code" :label="item.label" :value="item.code" />
        </el-select>

        <el-input
          v-model="query.ip"
          placeholder="来源 IP（精确）"
          clearable
          class="ts-seclog__field"
          @keyup.enter="handleSearch"
        />

        <el-input
          v-model="query.username"
          placeholder="账号名（精确）"
          clearable
          class="ts-seclog__field"
          @keyup.enter="handleSearch"
        />

        <el-date-picker
          v-model="timeRange"
          class="ts-seclog__field"
          type="daterange"
          value-format="YYYY-MM-DD"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          unlink-panels
        />

        <div class="ts-seclog__filter-actions">
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
        route-path="/system/security-log"
        :page="query.page"
        :size="query.size"
        :total="total"
        empty-text="暂无安全事件"
        :show-column-config="false"
        show-jumper
        @update:page="handlePageChange"
        @update:size="handleSizeChange"
      >
        <template #cell-eventTypeLabel="{ row }">
          <el-tag :type="tagType(row)" size="small" effect="plain">{{ row.eventTypeLabel }}</el-tag>
        </template>

        <!-- 手机：卡片列表 -->
        <template #mobile>
          <div class="ts-seclog__cards">
            <div v-for="row in list" :key="row.id" class="ts-seclog__card">
              <div class="ts-flex-between ts-seclog__card-head">
                <strong>{{ row.eventTypeLabel }}</strong>
                <el-tag :type="tagType(row)" size="small" effect="plain">{{ row.occurredAt }}</el-tag>
              </div>
              <div class="ts-seclog__card-row"><span class="ts-text-hint">账号</span><span>{{ row.username ?? '-' }}</span></div>
              <div class="ts-seclog__card-row"><span class="ts-text-hint">IP</span><span>{{ row.ip ?? '-' }}</span></div>
              <p class="ts-seclog__card-detail">{{ row.detail ?? '-' }}</p>
            </div>
            <el-empty v-if="!loading && list.length === 0" :image-size="70" description="暂无安全事件" />
          </div>
        </template>
      </TablePage>
    </section>
  </div>
</template>

<style scoped>
.ts-seclog__head {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.ts-seclog__title {
  margin: 0;
  font-size: 15px;
  font-weight: 500;
}

.ts-seclog__meta {
  display: flex;
  gap: 12px;
  align-items: center;
  font-size: 12px;
}

.ts-seclog__permanent {
  font-weight: 600;
  color: var(--el-color-danger);
}

.ts-seclog__top {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  margin-top: 12px;
}

.ts-seclog__top-tag {
  cursor: pointer;
}

.ts-seclog__filters {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.ts-seclog__field {
  width: 180px;
}

.ts-seclog__filter-actions {
  display: flex;
  gap: 8px;
  margin-left: auto;
}

.ts-seclog__cards {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.ts-seclog__card {
  padding: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
}

.ts-seclog__card-head {
  margin-bottom: 6px;
}

.ts-seclog__card-row {
  display: flex;
  gap: 8px;
  font-size: 13px;
  line-height: 1.9;
}

.ts-seclog__card-row > .ts-text-hint {
  flex: 0 0 48px;
}

.ts-seclog__card-detail {
  margin: 6px 0 0;
  font-size: 13px;
  color: var(--ts-text-secondary);
  word-break: break-all;
}

@media (max-width: 768px) {
  .ts-seclog__field {
    width: 100%;
  }

  .ts-seclog__filter-actions {
    margin-left: 0;
  }
}
</style>
