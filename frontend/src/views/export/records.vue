<template>
  <div class="ts-page">
    <el-card shadow="never" class="ts-card">
      <template #header>
        <div class="ts-card__header">
          <span class="ts-card__title">导出记录</span>
          <div class="ts-card__actions">
            <el-select v-model="query.type" placeholder="全部类型" clearable class="ts-filter" @change="handleTypeChange">
              <el-option v-for="item in EXPORT_TYPE_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
            <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
          </div>
        </div>
      </template>

      <el-alert
        v-if="isAdmin"
        type="info"
        :closable="false"
        show-icon
        title="管理员可见全部成员的导出记录"
        description="用于排查「谁导出了什么数据」。普通员工仅能看到自己发起的导出。"
        class="ts-alert"
      />

      <ListQueryNotice :visible="remembered" @clear="handleClearMemory" />

      <TablePage
        :columns="columns"
        :rows="tasks"
        :loading="loading"
        route-path="/export/records"
        :page="page"
        :size="size"
        :total="total"
        empty-text="暂无导出记录"
        show-jumper
        @update:page="handlePageChange"
        @update:size="handleSizeChange"
      >
        <template #cell-requesterName="{ row }">{{ (row as ExportTaskItem).requesterName ?? '-' }}</template>
        <template #cell-statusLabel="{ row }">
          <el-tag :type="exportStatusTagType((row as ExportTaskItem).status)" size="small">
            {{ (row as ExportTaskItem).statusLabel }}
          </el-tag>
        </template>
        <template #cell-totalRows="{ row }">{{ (row as ExportTaskItem).totalRows ?? '-' }}</template>
        <template #cell-fileSize="{ row }">{{ formatFileSize((row as ExportTaskItem).fileSize) }}</template>
        <template #cell-expireAt="{ row }">{{ (row as ExportTaskItem).expireAt ?? '-' }}</template>
        <template #cell-action="{ row }">
          <el-button
            v-if="(row as ExportTaskItem).downloadUrl"
            type="primary"
            link
            :loading="downloadingId === (row as ExportTaskItem).id"
            @click="download(row as ExportTaskItem)"
          >
            下载
          </el-button>
          <el-tooltip
            v-else-if="(row as ExportTaskItem).status === 'FAILED'"
            :content="(row as ExportTaskItem).errorMessage || '生成失败'"
            placement="top"
          >
            <span class="ts-muted">失败原因</span>
          </el-tooltip>
          <span v-else class="ts-muted">生成中…</span>
          <el-button
            type="danger"
            link
            :loading="deletingId === (row as ExportTaskItem).id"
            @click="remove(row as ExportTaskItem)"
          >
            删除
          </el-button>
        </template>

        <!--
          移动端：走 TablePage 的**通用卡片**（本页改造前在窄屏直接渲染 el-table，靠横向滚动查看）。

          这是 W4-E 唯一一处**有意**的呈现变更，理由不是「顺手改好看一点」：
           明确要求移动端「表格转卡片」，本页此前是漏网的一页；
          且本页的卡片行恰好等于可见列（没有 PC 独有/移动独有字段的不对称），
          正是 TablePage 注释里说的「通用卡片零成本复用的简单页面」。
          `fileName` 作为卡片标题（最能标识一条导出记录），操作列 `card: false` 改走 #card-actions。
        -->
        <template #card-actions="{ row }">
          <el-button
            v-if="(row as ExportTaskItem).downloadUrl"
            size="small"
            type="primary"
            plain
            :loading="downloadingId === (row as ExportTaskItem).id"
            @click="download(row as ExportTaskItem)"
          >
            下载
          </el-button>
          <span v-else-if="(row as ExportTaskItem).status === 'FAILED'" class="ts-muted">
            {{ (row as ExportTaskItem).errorMessage || '生成失败' }}
          </span>
          <span v-else class="ts-muted">生成中…</span>
          <el-button
            size="small"
            type="danger"
            plain
            :loading="deletingId === (row as ExportTaskItem).id"
            @click="remove(row as ExportTaskItem)"
          >
            删除
          </el-button>
        </template>
      </TablePage>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { exportApi } from '@/api/export'
import ListQueryNotice from '@/components/ListQueryNotice.vue'
import TablePage from '@/components/TablePage.vue'
import { useListQuery } from '@/composables/useListQuery'
import { useUserStore } from '@/store/user'
import {
  EXPORT_TYPE_OPTIONS,
  exportStatusTagType,
  formatFileSize,
  type ExportTaskItem,
  type ExportTypeCode
} from '@/types/export'
import type { ColumnDef } from '@/types/table'

/**
 * 导出记录页
 *
 * 为什么需要这个页面：规范要求「超过 10000 条时异步生成文件，完成后站内消息通知下载」。
 * 用户收到消息时可能已经离开原列表页，因此必须有一个稳定的落点能找回自己的导出文件；
 * 同时它也是「同步导出时用户没点下载」的补救入口。
 *
 * 分页 + 删除：记录会随时间累积，一次性拉全量既慢又难定位；删除用于清理失败的、过期的历史记录。
 * 后端强制「仅本人或管理员」可删，前端不做唯一防线。
 *
 * ---
 *  · W4-E：迁入公共表格层 + 接入筛选记忆（`useListQuery`）。
 *
 * 本页有两处与其它 T1 页面不同的地方，都在这里说清楚：
 *
 * 1. **「发起人」是条件列**（`v-if="isAdmin"`）。迁移时不能把它做成「默认隐藏的列」——
 *    隐藏与不存在是两件事：普通员工在列设置里**不该看到**一个必然为空、且暴露「别人有额外列」的开关。
 *    因此 `columns` 用 `computed` 按角色动态生成；`useColumnConfig` 的列为函数入参，天然支持。
 *    该列在移动端卡片上配了 `cardHideOnEmpty`（普通员工看不到它，管理员看到空的也没意义）。
 *
 * 2. **分页器带「跳至 N 页」**（`show-jumper`）。本页记录随时间无限累积，跳页是高频操作；
 *    去掉它属于功能退化。为此给 `TablePage` 加了可选的 `showJumper`（默认 false ⇒ 既有 6 页零影响）。
 *
 * 筛选记忆：`defaults.type` = `undefined`（= 全部类型），与改造前 `ref<…|undefined>(undefined)` 一致。
 * `persist` 在「与默认值无差异」时**不会**留下记录，因此「什么都没筛」不会显示记忆提示条。
 */
const userStore = useUserStore()
const isAdmin = computed(() => userStore.role === 'super_admin' || userStore.role === 'admin')

const columns = computed<ColumnDef[]>(() => {
  const adminColumns: ColumnDef[] = isAdmin.value
    ? [{ key: 'requesterName', label: '发起人', minWidth: 110, cardHideOnEmpty: true }]
    : []
  return [
    { key: 'id', label: '任务号', minWidth: 80 },
    ...adminColumns,
    { key: 'exportTypeLabel', label: '导出类型', minWidth: 120 },
    { key: 'statusLabel', label: '状态', minWidth: 100 },
    { key: 'totalRows', label: '数据行数', minWidth: 100, cardHideOnEmpty: true },
    { key: 'fileSize', label: '文件大小', minWidth: 100, cardHideOnEmpty: true },
    { key: 'fileName', label: '文件名', minWidth: 200, showOverflowTooltip: true, card: 'title' },
    { key: 'createdAt', label: '发起时间', minWidth: 170 },
    { key: 'expireAt', label: '过期时间', minWidth: 170, cardHideOnEmpty: true },
    // 操作列锁定常显；`card: false` 表示移动端改走 #card-actions，不占一行
    { key: 'action', label: '操作', minWidth: 160, fixed: 'right', configurable: false, card: false }
  ]
})

const tasks = ref<ExportTaskItem[]>([])
const loading = ref(false)
const downloadingId = ref<number | null>(null)
const deletingId = ref<number | null>(null)

const page = ref(1)
const size = ref(10)
const total = ref(0)

/** 筛选条件（默认值 = 改造前首屏取值；仅 `type` 参与记忆） */
const { query, remembered, persist, reset } = useListQuery({
  routePath: '/export/records',
  defaults: { type: undefined as ExportTypeCode | undefined }
})

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await exportApi.page({
      type: query.type,
      page: page.value,
      size: size.value
    })
    tasks.value = result.records
    total.value = result.total
  } catch {
    // 请求层已统一提示
  } finally {
    loading.value = false
  }
}

/** 条件变化后回到第一页再查，否则会停留在越界页码上（列表空白） */
function handleTypeChange(): void {
  page.value = 1
  void persist()
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

/** 「清除记忆」：恢复默认筛选（全部类型）+ 清除持久化，并重新查询 */
async function handleClearMemory(): Promise<void> {
  await reset()
  page.value = 1
  await load()
}

async function download(row: ExportTaskItem): Promise<void> {
  downloadingId.value = row.id
  try {
    await exportApi.download(row.id, row.fileName ?? '导出文件.xlsx')
    ElMessage.success('已开始下载')
  } catch {
    // 已过期 / 文件缺失等明确原因由请求层提示
    await load()
  } finally {
    downloadingId.value = null
  }
}

async function remove(row: ExportTaskItem): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定删除导出记录「${row.fileName ?? '#' + row.id}」吗？删除后对应的导出文件也将一并删除，且不可恢复。`,
      '删除导出记录',
      { type: 'warning', confirmButtonText: '确定删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  deletingId.value = row.id
  try {
    await exportApi.remove(row.id)
    ElMessage.success('已删除')
    // 删掉当前页最后一条时回退一页，避免停在空页
    if (tasks.value.length === 1 && page.value > 1) {
      page.value -= 1
    }
    await load()
  } catch {
    // 越权 / 不存在等由请求层提示
  } finally {
    deletingId.value = null
  }
}

onMounted(load)
</script>

<style scoped>
.ts-alert {
  margin-bottom: 12px;
}
.ts-muted {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  margin-right: 8px;
}
.ts-card__actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.ts-filter {
  width: 160px;
}
@media (max-width: 768px) {
  .ts-card__actions {
    width: 100%;
  }
  .ts-filter {
    width: 100%;
  }
}
</style>
