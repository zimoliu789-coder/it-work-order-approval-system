<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Refresh, View } from '@element-plus/icons-vue'
import { messageApi } from '@/api/message'
import TablePage from '@/components/TablePage.vue'
import { useResponsive } from '@/composables/useResponsive'
import {
  messageTagType,
  messageTargetRoute,
  messageTypeLabel,
  messageTypeOptions,
  type MessageItem,
  type MessageTypeCode
} from '@/types/message'
import type { ColumnDef } from '@/types/table'

/**
 * 消息中心（ 站内消息通知系统）
 *
 * 铃铛面板只展示最近若干条；本页提供完整能力：
 * 分页浏览、按「未读 / 类型 / 关键词」筛选、单条已读、全部已读、删除、跳转处理页，
 * 以及需求方三波·第二波· 的<b>批量删除</b>。
 *
 * 说明：后端端点只操作「当前登录者自己的消息」，前端不传也无法指定 userId；
 * 跳转目标复用 `messageTargetRoute` 纯函数（与铃铛同源），避免两处映射漂移。
 * 消息类型下拉取自后端元数据（见 types/message.ts 的 messageTypeOptions），
 * 元数据未到达时回退内置表。
 *
 * ---
 *  · W4-E：**T2 档** —— 只迁 `TablePage` 骨架（分页 / 空态 / 移动卡片一致化），
 * **列保持硬编码、不接筛选持久化**（本页的筛选态是「一眼看清、随手重置」的类型，
 * 记住它反而容易让人误以为消息变少了）。
 *
 * 本页是 T2 里唯一用到三处「表格本体能力」的页面，正好构成了对 `TablePage` 的三次扩充
 * （全部为纯增量，既有页面零影响）：
 * 1. **多选列**：`{ selection: true }` ⇒ 渲染 `type="selection"`（批量删除的前提）；
 * 2. **实例方法**：`@selection-change` 能靠 `$attrs` 透传，但 `clearSelection()` 不能 ⇒
 *    `TablePage` 经 `defineExpose` 暴露同名转发方法（页面不穿透组件内部）；
 * 3. **分页器左侧附加信息**：`#pager-extra` 插槽（「本页未读 N 条」）。
 */

const router = useRouter()
const { isMobile } = useResponsive()

/** 列定义（代码为事实源；T2 不启用列设置，故不提供可配置列） */
const columns: ColumnDef[] = [
  { key: 'selection', label: '选择', width: 46, selection: true },
  { key: 'readState', label: '状态', width: 70, align: 'center' },
  { key: 'message', label: '消息', minWidth: 320 },
  { key: 'messageType', label: '类型', width: 120, align: 'center' },
  { key: 'createdAt', label: '时间', width: 170, showOverflowTooltip: true },
  { key: 'action', label: '操作', width: 200, fixed: 'right', configurable: false }
]

const loading = ref(false)
const acting = ref(false)
const records = ref<MessageItem[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)

/** 勾选的消息 ID（PC 表勾选 / 移动端逐条勾选都写入这里，统一驱动批量操作） */
const selectedIds = ref<number[]>([])
/** 指向 TablePage 暴露的 `clearSelection()`（移动端无 el-table，故为可选调用） */
const tablePageRef = ref<{ clearSelection?: () => void } | null>(null)

const query = reactive({
  unreadOnly: false,
  messageType: null as MessageTypeCode | null,
  keyword: ''
})

/** 消息类型筛选项：优先后端元数据，未到达时用内置兜底（挂载后若元数据已到再刷新一次） */
const typeOptions = ref(messageTypeOptions())

const unreadCount = computed(() => records.value.filter((item) => item.isRead !== true).length)

onMounted(() => {
  void load()
})

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await messageApi.mine({
      page: page.value,
      size: size.value,
      unreadOnly: query.unreadOnly ? true : null,
      messageType: query.messageType,
      keyword: query.keyword.trim() || null
    })
    records.value = result.records
    total.value = result.total
    // 翻页/筛选后清空勾选，避免「选中项还停留在上一页」造成误删
    clearSelection()
  } catch {
    records.value = []
    total.value = 0
  } finally {
    loading.value = false
    // 元数据可能在本页加载期间才到达，重新取一次（函数式取值，非响应式）
    typeOptions.value = messageTypeOptions()
  }
}

function clearSelection(): void {
  selectedIds.value = []
  // 双可选链：`tablePageRef` 可能为 null（未挂载），`clearSelection` 也可能不存在（移动端无 el-table）
  tablePageRef.value?.clearSelection?.()
}

function handleSelectionChange(rows: MessageItem[]): void {
  selectedIds.value = rows.map((row) => row.id)
}

function isSelected(id: number): boolean {
  return selectedIds.value.includes(id)
}

function toggleSelect(id: number): void {
  const index = selectedIds.value.indexOf(id)
  if (index >= 0) {
    selectedIds.value.splice(index, 1)
  } else {
    selectedIds.value.push(id)
  }
}

function handleSearch(): void {
  page.value = 1
  void load()
}

function handleReset(): void {
  query.unreadOnly = false
  query.messageType = null
  query.keyword = ''
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

function typeText(item: MessageItem): string {
  return item.messageTypeLabel || messageTypeLabel(item.messageType)
}

/** 打开消息：标记已读后跳转到对应处理页 */
async function openMessage(item: MessageItem): Promise<void> {
  if (item.isRead !== true) {
    try {
      await messageApi.markRead(item.id)
      item.isRead = true
    } catch {
      // 已读失败不阻断跳转
    }
  }
  void router.push(messageTargetRoute(item.messageType))
}

async function markRead(item: MessageItem): Promise<void> {
  if (item.isRead === true) {
    return
  }
  try {
    await messageApi.markRead(item.id)
    item.isRead = true
  } catch {
    // 请求层统一提示
  }
}

async function markAllRead(): Promise<void> {
  acting.value = true
  try {
    await messageApi.markAllRead()
    ElMessage.success('已全部标记为已读')
    records.value = records.value.map((item) => ({ ...item, isRead: true }))
    if (query.unreadOnly) {
      await load()
    }
  } catch {
    // 请求层统一提示
  } finally {
    acting.value = false
  }
}

async function removeMessage(item: MessageItem): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认删除消息「${item.title}」？`, '删除消息', { type: 'warning' })
  } catch {
    return
  }
  try {
    await messageApi.remove(item.id)
    ElMessage.success('消息已删除')
    // 删除最后一条时回退一页，避免停留在空页
    if (records.value.length === 1 && page.value > 1) {
      page.value -= 1
    }
    await load()
  } catch {
    // 请求层统一提示
  }
}

/** 批量删除勾选的消息（） */
async function batchDelete(): Promise<void> {
  const count = selectedIds.value.length
  if (count === 0) {
    ElMessage.info('请先勾选要删除的消息')
    return
  }
  try {
    await ElMessageBox.confirm(`确认删除选中的 ${count} 条消息？删除后不可恢复。`, '批量删除', {
      type: 'warning',
      confirmButtonText: '确定删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  acting.value = true
  try {
    const result = await messageApi.batchDelete(selectedIds.value)
    ElMessage.success(`已删除 ${result.affected} 条消息`)
    // 整页被删光时回退一页
    if (result.affected >= records.value.length && page.value > 1) {
      page.value -= 1
    }
    await load()
  } catch {
    // 越权 / 超上限等由请求层统一提示
  } finally {
    acting.value = false
  }
}
</script>

<template>
  <div class="ts-page">
    <section class="ts-card">
      <div class="ts-flex-between ts-mc__head">
        <div>
          <h3 class="ts-mc__title">消息中心</h3>
          <p class="ts-text-secondary ts-mc__desc">
            待办、审批结果、到期预警、转交与催办等通知汇总于此，点击消息可跳转到对应处理页。
          </p>
        </div>
        <div class="ts-mc__head-actions">
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
          <el-button type="primary" plain :loading="acting" @click="markAllRead">全部已读</el-button>
        </div>
      </div>

      <div class="ts-mc__filters ts-mt-16">
        <el-switch v-model="query.unreadOnly" active-text="仅看未读" @change="handleSearch" />
        <el-select v-model="query.messageType" class="ts-mc__filter-item" placeholder="消息类型" clearable @change="handleSearch">
          <el-option v-for="opt in typeOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>
        <el-input
          v-model="query.keyword"
          class="ts-mc__filter-keyword"
          placeholder="标题 / 内容关键词"
          clearable
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
        <div class="ts-mc__filter-actions">
          <el-button type="primary" :loading="loading" @click="handleSearch">查询</el-button>
          <el-button @click="handleReset">重置</el-button>
        </div>
      </div>

      <!-- 批量操作条 -->
      <div class="ts-mc__bulk ts-mt-16">
        <span class="ts-text-hint">已选 {{ selectedIds.length }} 条</span>
        <el-button
          type="danger"
          plain
          size="small"
          :disabled="selectedIds.length === 0"
          :loading="acting"
          @click="batchDelete"
        >
          批量删除
        </el-button>
        <el-button v-if="selectedIds.length > 0" size="small" @click="clearSelection">取消选择</el-button>
      </div>

      <TablePage
        ref="tablePageRef"
        :columns="columns"
        :rows="records"
        :loading="loading"
        route-path="/message"
        row-key="id"
        :page="page"
        :size="size"
        :total="total"
        empty-text="暂无消息"
        :show-column-config="false"
        class="ts-mt-16"
        @update:page="handlePageChange"
        @update:size="handleSizeChange"
        @selection-change="handleSelectionChange"
      >
        <template #cell-readState="{ row }">
          <el-badge v-if="(row as MessageItem).isRead !== true" is-dot />
          <span v-else class="ts-text-hint">已读</span>
        </template>
        <template #cell-message="{ row }">
          <div
            class="ts-mc__msg"
            :class="{ 'is-unread': (row as MessageItem).isRead !== true }"
            @click="openMessage(row as MessageItem)"
          >
            <span class="ts-mc__msg-title">{{ (row as MessageItem).title }}</span>
            <p class="ts-mc__msg-content ts-text-secondary">{{ (row as MessageItem).content }}</p>
          </div>
        </template>
        <template #cell-messageType="{ row }">
          <el-tag size="small" effect="plain" :type="messageTagType((row as MessageItem).messageType)">
            {{ typeText(row as MessageItem) }}
          </el-tag>
        </template>
        <template #cell-action="{ row }">
          <el-button link type="primary" size="small" @click="openMessage(row as MessageItem)">
            <el-icon><View /></el-icon>
            <span>查看</span>
          </el-button>
          <el-button
            v-if="(row as MessageItem).isRead !== true"
            link
            type="success"
            size="small"
            @click="markRead(row as MessageItem)"
          >
            标为已读
          </el-button>
          <el-button link type="danger" size="small" @click="removeMessage(row as MessageItem)">
            <el-icon><Delete /></el-icon>
          </el-button>
        </template>

        <template #pager-extra>
          <span v-if="!isMobile" class="ts-text-hint">本页未读 {{ unreadCount }} 条</span>
        </template>

        <!-- 移动端卡片：保留改造前结构（逐条勾选，同样支持批量删除） -->
        <template #mobile>
          <div class="ts-mc__cards">
            <div
              v-for="row in records"
              :key="row.id"
              class="ts-mc__card"
              :class="{ 'is-unread': row.isRead !== true }"
            >
              <div class="ts-flex-between">
                <div class="ts-mc__card-left">
                  <el-checkbox :model-value="isSelected(row.id)" @change="toggleSelect(row.id)" />
                  <strong class="ts-mc__card-title">{{ row.title }}</strong>
                </div>
                <el-tag size="small" effect="plain" :type="messageTagType(row.messageType)">{{ typeText(row) }}</el-tag>
              </div>
              <p class="ts-text-secondary ts-mc__card-content">{{ row.content }}</p>
              <div class="ts-text-hint ts-mc__card-time">{{ row.createdAt }}</div>
              <div class="ts-mc__card-actions">
                <el-button size="small" type="primary" plain @click="openMessage(row)">查看</el-button>
                <el-button v-if="row.isRead !== true" size="small" type="success" plain @click="markRead(row)">已读</el-button>
                <el-button size="small" type="danger" plain @click="removeMessage(row)">删除</el-button>
              </div>
            </div>
            <el-empty v-if="!loading && records.length === 0" :image-size="70" description="暂无消息" />
          </div>
        </template>
      </TablePage>
    </section>
  </div>
</template>

<style scoped>
.ts-mc__head {
  gap: 16px;
  align-items: flex-start;
}

.ts-mc__title {
  margin: 0;
  font-size: 16px;
  font-weight: 500;
}

.ts-mc__desc {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.6;
}

.ts-mc__head-actions {
  display: flex;
  gap: 8px;
}

.ts-mc__filters {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.ts-mc__bulk {
  display: flex;
  align-items: center;
  gap: 12px;
}

.ts-mc__filter-item {
  width: 160px;
}

.ts-mc__filter-keyword {
  width: 240px;
}

.ts-mc__filter-actions {
  display: flex;
  gap: 8px;
}

.ts-mc__msg {
  cursor: pointer;
}

.ts-mc__msg-title {
  font-size: 13px;
}

.ts-mc__msg.is-unread .ts-mc__msg-title {
  font-weight: 600;
}

.ts-mc__msg-content {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.6;
  word-break: break-all;
}

.ts-mc__cards {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.ts-mc__card {
  padding: 12px;
  border: 1px solid var(--ts-border);
  border-radius: 10px;
  background: #fbfcfe;
}

.ts-mc__card.is-unread {
  border-color: var(--el-color-primary-light-5);
}

.ts-mc__card-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.ts-mc__card-title {
  word-break: break-all;
}

.ts-mc__card-content {
  margin: 6px 0;
  font-size: 12px;
  line-height: 1.6;
  word-break: break-all;
}

.ts-mc__card-time {
  font-size: 12px;
}

.ts-mc__card-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
}

@media (max-width: 767px) {
  .ts-mc__head {
    flex-direction: column;
  }

  .ts-mc__head-actions {
    width: 100%;
  }

  .ts-mc__head-actions .el-button {
    flex: 1 1 0;
  }

  .ts-mc__filter-item,
  .ts-mc__filter-keyword {
    width: 100%;
  }

  .ts-mc__filter-actions .el-button {
    flex: 1 1 0;
  }

  .ts-mc__card-actions .el-button {
    flex: 1 1 0;
    margin-left: 0;
  }
}
</style>
