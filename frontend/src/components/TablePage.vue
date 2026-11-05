<script setup lang="ts" generic="T extends object">
import { computed, ref } from 'vue'
import type { TableInstance } from 'element-plus'
import ColumnConfigDialog from '@/components/ColumnConfigDialog.vue'
import { useColumnConfig } from '@/composables/useColumnConfig'
import { useResponsive } from '@/composables/useResponsive'
import type { ColumnDef } from '@/types/table'

/**
 * 公共表格层（-B）
 *
 * 一个页面里的记录列表，改造前是「硬编码 N 个 `el-table-column` + 手写分页 + 移动端卡片」
 * 三块重复代码。本组件把这套结构收敛成**列数组驱动**：
 *
 * ```vue
 * <TablePage :columns="columns" :rows="records" :loading="loading" route-path="/order/mine"
 *            v-model:page="page" v-model:size="size" :total="total" @sort-change="load">
 *   <template #bar> ...页面自己的筛选冲突区... </template>
 *   <template #cell-orderNo="{ row }">{{ row.orderNo }}</template>
 *   <template #cell-status="{ row }"> ...标签渲染... </template>
 *   <template #mobile> ...该页原有的移动端卡片（可选）... </template>
 * </TablePage>
 * ```
 *
 * **单元格渲染**：`#cell-<key>` 具名插槽按列 key 分派；未提供时回落「原值（数组用「、」连接，
 * 空值显示 `-`）」。这样页面只把「有自定义渲染的列」写成插槽，其余列零样板。
 *
 * **列顺序与显隐**：由 `useColumnConfig` 驱动（localStorage 按 `route-path` 隔离）。
 * 无偏好时 = `columns` 的声明顺序与默认可见性 ⇒ **与改造前逐列一致**。
 *
 * **移动端**（本组件最需要说清楚的一处设计）：
 * 组件的选择是「`#mobile` 插槽优先，通用卡片兜底」。
 * - 迁移的 6 个页面**全部使用 `#mobile` 插槽**保留各自原有的卡片结构。原因不是偷懒：
 *   这些页面的卡片行与 PC 列**并非一一对应**（例如「归还信息」在 PC 是一列，
 *   在卡片里是「归还时间 / 实际收回人」两行且仅在已归还时出现；员工管理卡片还有
 *   邮箱 / 部门等 PC 表上根本没有的字段）。由列定义强行生成卡片一定会改变既有呈现，
 *   与本次「零回归」的硬约束冲突。
 * - 通用卡片（列定义里的 `card` / `cardHideOnEmpty`）仍然实现并被单测覆盖，
 *   供  迁移「卡片行恰好等于可见列」的简单页面时零成本复用。
 *
 * **操作列不可隐藏**：`columns` 里把操作列的 `configurable` 设为 `false`，
 * 它会固定显示、不可勾除、不可拖动（用户关掉它 = 页面直接坏掉）。
 *
 * 其余透传：`$attrs` 原样落到 `el-table` 上（`@sort-change` / `row-class-name` / `size` 等
 * 仍然按 Element Plus 的原生写法使用）。
 */

const props = withDefaults(
  defineProps<{
    /** 列定义（代码为事实源） */
    columns: ColumnDef[]
    /** 数据行 */
    rows: T[]
    /** 列偏好隔离键（= 路由路径） */
    routePath: string
    loading?: boolean
    /** 行唯一键字段名（缺省 `id`） */
    rowKey?: string
    emptyText?: string
    /**
     * 页面标题（2026-11 统一页头）。
     *
     * <h2>为什么把页头收进 TablePage</h2>
     * 改造前 18 个列表页各写各的 `<div class="ts-flex-between ts-xx__head">`，
     * 标题字号、按钮位置、与表格的间距全靠各页自觉 —— 截图里就是
     * 「打印标签孤零零在左、其余三个按钮在右」「列设置单独一行挂在右下角」。
     * 传了 title 就由组件统一渲染页头，页面只负责 #actions 里的按钮。
     */
    title?: string
    /** 标题下的一行说明（回答「这页是干什么的」，不要写操作指引） */
    subtitle?: string
    /**
     * 列宽是否随容器拉伸（缺省 true）。
     *
     * <p>列多到总宽超过容器时，`fit: true` 会**压缩每列**换取"不出现横向滚动"，
     * 结果是资产编号这种长文本被折成三行、状态标签被截成「审批中 …」——
     * 看起来像排版坏了，其实是列被压窄。这种页面传 `false`：
     * 列按设定宽度排布、超出部分横向滚动，纵向行数不膨胀。
     */
    fit?: boolean
    page?: number
    size?: number
    total?: number
    pageSizes?: number[]
    /** 是否渲染分页器（缺省 true） */
    showPagination?: boolean
    /** 是否提供「列设置」入口（缺省 true；可配置列 ≤1 时自动隐藏） */
    showColumnConfig?: boolean
    /**
     * 分页器是否带「跳至 N 页」输入框（缺省 false）。
     *
     * W4-E 新增：既有 6 个已迁页面改造前都没有 jumper，故默认值与他们保持一致；
     * 导出记录页改造前带 jumper（记录随时间无限累积，跳页是高频操作），
     * 去掉它属于**功能退化**而不是「样式统一」，因此保留为逐页可选。
     */
    showJumper?: boolean
  }>(),
  {
    loading: false,
    rowKey: 'id',
    emptyText: '暂无数据',
    title: '',
    subtitle: '',
    fit: true,
    page: 1,
    size: 10,
    total: 0,
    pageSizes: () => [10, 20, 50],
    showPagination: true,
    showColumnConfig: true,
    showJumper: false
  }
)

const emit = defineEmits<{
  (e: 'update:page', value: number): void
  (e: 'update:size', value: number): void
}>()

defineOptions({ name: 'TablePage', inheritAttrs: false })

const { isMobile } = useResponsive()

const { configRows, visibleKeys, apply } = useColumnConfig({
  routePath: props.routePath,
  columns: () => props.columns
})

const dialogVisible = ref(false)

/**
 * 内部 `el-table` 实例（W4-E 新增，经 `defineExpose` 暴露）。
 *
 * 为什么需要：消息中心的「翻页后清空勾选」要调 `clearSelection()`。
 * 事件（`@selection-change`）能靠 `$attrs` 透传到 `el-table`，**实例方法却不行**。
 * 这里暴露的就是 Element Plus 原生的 `TableInstance` —— 页面拿到的能力与改造前
 * 直接持有 `el-table` 的 `ref` 完全一致，不额外发明一层包装。
 *
 * 移动端不渲染 `el-table`（走卡片），此时为 `null`，调用方需用可选链
 * （既有调用点已如此，见 `message/index.vue` 的 `clearSelection`）。
 */
const tableRef = ref<TableInstance>()

/** 语义化转发：调用方不必知道内部用的是哪个表格实现（也不该依赖） */
function clearSelection(): void {
  tableRef.value?.clearSelection()
}

defineExpose({ tableRef, clearSelection })

/** 可配置列（不含操作列这类锁定列）≤1 时，列设置没有意义，入口直接隐藏 */
const canConfig = computed(
  () => props.showColumnConfig && configRows.value.filter((row) => !row.locked).length > 1
)

/** 当前应渲染的列（按偏好顺序取回定义；`table === false` 的列不进表格） */
const visibleColumns = computed<ColumnDef[]>(() => {
  const byKey = new Map(props.columns.map((column) => [column.key, column]))
  const result: ColumnDef[] = []
  for (const key of visibleKeys.value) {
    const column = byKey.get(key)
    if (column && column.table !== false) {
      result.push(column)
    }
  }
  return result
})

/** 通用卡片：标题列（每页最多一个） */
const cardTitleColumn = computed<ColumnDef | null>(
  () => props.columns.find((column) => column.card === 'title') ?? null
)

/** 通用卡片：字段行 */
const cardColumns = computed<ColumnDef[]>(() =>
  visibleKeys.value
    .map((key) => props.columns.find((column) => column.key === key))
    .filter(
      (column): column is ColumnDef =>
        column != null &&
        column.card !== false &&
        column.card !== 'title' &&
        // 多选列不进卡片：移动端卡片没有勾选框，渲染出来只是一行无意义的「选择 → -」
        column.selection !== true
    )
)

/**
 * 读单元格原始值。
 *
 * 参数刻意声明为 `unknown` 而不是 `T`：模板里 `el-table` 的插槽 `scope.row` 是宽松类型，
 * 传进来正好是 `unknown`；把断言收在本文件内，页面模板就不必写 `row as X`。
 */
function readCell(row: unknown, key: string): unknown {
  if (row == null || typeof row !== 'object') {
    return undefined
  }
  return (row as Record<string, unknown>)[key]
}

function keyOf(row: unknown, index: number): string | number {
  const value = readCell(row, props.rowKey)
  return typeof value === 'string' || typeof value === 'number' ? value : index
}

/** 未提供 `#cell-<key>` 插槽时的回落展示 */
function cellText(row: unknown, key: string): string {
  const value = readCell(row, key)
  if (value == null || value === '') {
    return '-'
  }
  if (Array.isArray(value)) {
    return value.join('、')
  }
  return String(value)
}

function isEmptyCell(row: unknown, key: string): boolean {
  const value = readCell(row, key)
  return value == null || value === '' || (Array.isArray(value) && value.length === 0)
}

function showCardRow(column: ColumnDef, row: unknown): boolean {
  return !(column.cardHideOnEmpty === true && isEmptyCell(row, column.key))
}

/** 列 → el-table-column 的 attrs（一一对应，不做自定义转换） */
function columnAttrs(column: ColumnDef): Record<string, unknown> {
  // 多选列（`el-table-column type="selection"`）：只有 type，没有 prop / label；
  // 其 `key` 仍用于表格内的稳定标识与 `#cell-<key>` 插槽名。
  const attrs: Record<string, unknown> =
    column.selection === true ? { type: 'selection' } : { prop: column.key, label: column.label }
  if (column.width != null) {
    attrs.width = column.width
  }
  if (column.minWidth != null) {
    attrs.minWidth = column.minWidth
  }
  if (column.fixed != null) {
    attrs.fixed = column.fixed
  }
  if (column.align != null) {
    attrs.align = column.align
  }
  if (column.sortable != null) {
    attrs.sortable = column.sortable
  }
  if (column.showOverflowTooltip != null) {
    attrs.showOverflowTooltip = column.showOverflowTooltip
  }
  return attrs
}

const pageLayout = computed(() => {
  if (isMobile.value) {
    return 'prev, pager, next'
  }
  return props.showJumper ? 'total, sizes, prev, pager, next, jumper' : 'total, sizes, prev, pager, next'
})

function handlePageChange(next: number): void {
  emit('update:page', next)
}

function handleSizeChange(next: number): void {
  emit('update:size', next)
}
</script>

<template>
  <div class="ts-table-page">
    <!--
      统一页头（2026-11）：标题 + 一行说明 + 右侧操作区。
      为什么收进组件而不是继续各页自己写：18 个列表页各写一份的结果是
      标题字号有三种、按钮位置有两种（有的按钮组跑到筛选区、有的散在两侧），
      改一次规范要改 18 处且必然漏掉几处。现在规范只有这一个实现。
    -->
    <header v-if="title || $slots.actions" class="ts-table-page__head">
      <div class="ts-table-page__head-main">
        <h1 v-if="title" class="ts-table-page__title">{{ title }}</h1>
        <p v-if="subtitle" class="ts-table-page__subtitle">{{ subtitle }}</p>
      </div>
      <div class="ts-table-page__actions">
        <slot name="actions" />
        <!--
          列设置归位（2026-11）：跟着页头按钮组走，不再单独占一行。
          它曾是筛选条右侧的独立按钮，而筛选条本身会换行（组织与人员页 5 个控件），
          一换行按钮就掉到第二行左端，看起来像没用完的控件。
          现在只在与页面按钮同排的位置出现；没传标题也没传按钮的老页面才回退到筛选条。
        -->
        <el-button
          v-if="canConfig && (title || $slots.actions)"
          @click="dialogVisible = true"
        >
          列设置
        </el-button>
      </div>
    </header>

    <div v-if="$slots.bar || (canConfig && !(title || $slots.actions))" class="ts-table-page__bar">
      <div class="ts-table-page__bar-main">
        <slot name="bar" />
      </div>
      <el-button
        v-if="canConfig && !(title || $slots.actions)"
        class="ts-table-page__cols-btn"
        @click="dialogVisible = true"
      >
        列设置
      </el-button>
    </div>

    <!--
      PC：表格
      ────────────────────────────────────────────────────────────
      外面这层 __surface 是 2026-11 加的：改造前表格直接躺在页面底色上，
      既没有卡片的边界，表头灰底与页面底又接近，整块数据区"浮"在半空。
      包一层白底 + 描边 + 圆角后，表格成为页面上一个明确的块；
      overflow: hidden 让 EP 自带的外边框被容器吃掉，不会出现双线。
    -->
    <div v-if="!isMobile" class="ts-table-page__surface">
      <el-table
        ref="tableRef"
        v-bind="$attrs"
        v-loading="loading"
        :data="rows"
        :fit="fit"
        border
        stripe
        size="default"
        class="ts-table-page__table"
      >
      <!--
        ⚠️ 多选列**不能**给它 `#default` 插槽：`el-table-column type="selection"` 的勾选框由
        Element Plus 内置渲染，一旦被自定义插槽接管，整列就只剩一个空值占位（`-`），勾选框
        随之消失。P3 就是这么发现的 —— 台账页勾不上，追下去发现消息中心的「批量删除」
        同样点不动，属**既有缺陷**。故 selection 列走自闭合写法，其余列才给插槽。
      -->
      <template v-for="column in visibleColumns" :key="column.key">
        <el-table-column v-if="column.selection" v-bind="columnAttrs(column)" />
        <el-table-column v-else v-bind="columnAttrs(column)">
          <template #default="scope">
            <slot :name="`cell-${column.key}`" v-bind="scope">{{ cellText(scope.row, column.key) }}</slot>
          </template>
        </el-table-column>
      </template>
      <template #empty>
        <el-empty :image-size="70" :description="emptyText" />
      </template>
      </el-table>
    </div>

    <!-- 移动端：页面自带卡片优先，否则用列定义生成通用卡片 -->
    <div v-else v-loading="loading" class="ts-table-page__mobile">
      <slot name="mobile">
        <div v-for="(row, index) in rows" :key="keyOf(row, index)" class="ts-table-page__card">
          <div class="ts-table-page__card-head">
            <strong class="ts-table-page__card-title">
              {{ cardTitleColumn ? cellText(row, cardTitleColumn.key) : '' }}
            </strong>
            <div class="ts-table-page__card-tags">
              <slot name="card-tags" :row="row" />
            </div>
          </div>
          <template v-for="column in cardColumns" :key="column.key">
            <div v-if="showCardRow(column, row)" class="ts-table-page__card-row">
              <span class="ts-text-hint">{{ column.label }}</span>
              <span class="ts-table-page__card-value">
                <slot :name="`cell-${column.key}`" :row="row" :index="index">
                  {{ cellText(row, column.key) }}
                </slot>
              </span>
            </div>
          </template>
          <div class="ts-table-page__card-actions">
            <slot name="card-actions" :row="row" />
          </div>
        </div>
        <el-empty v-if="!loading && rows.length === 0" :image-size="70" :description="emptyText" />
      </slot>
    </div>

    <div
      v-if="showPagination"
      class="ts-table-page__pager"
      :class="{ 'ts-table-page__pager--with-extra': $slots['pager-extra'] != null }"
    >
      <div v-if="$slots['pager-extra']" class="ts-table-page__pager-extra">
        <slot name="pager-extra" />
      </div>
      <el-pagination
        :current-page="page"
        :page-size="size"
        :total="total"
        :page-sizes="pageSizes"
        :layout="pageLayout"
        background
        @current-change="handlePageChange"
        @size-change="handleSizeChange"
      />
    </div>

    <ColumnConfigDialog v-model="dialogVisible" :columns="configRows" @confirm="apply" />
  </div>
</template>

<style scoped>
.ts-table-page {
  width: 100%;
}

/* ---------- 统一页头（2026-11）----------
   align-items: flex-start 而不是 center：标题两行（有说明）时，
   右侧按钮组必须与标题第一行对齐，否则按钮会掉到说明文字旁边。 */
.ts-table-page__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--ts-sp-4);
  margin-bottom: var(--ts-sp-4);
  flex-wrap: wrap;
}

.ts-table-page__head-main {
  min-width: 0;
  flex: 1 1 auto;
}

.ts-table-page__title {
  margin: 0;
  font-size: var(--ts-fs-page);
  font-weight: 600;
  line-height: var(--ts-lh-tight);
  color: var(--ts-text);
}

.ts-table-page__subtitle {
  margin: var(--ts-sp-1) 0 0;
  max-width: 72ch; /* 说明文字别横贯整行，读起来太长 */
  font-size: var(--ts-fs-caption);
  line-height: var(--ts-lh-body);
  color: var(--ts-text-secondary);
}

/* 操作区：按钮永远在同一行、间距一致、换行时右对齐 */
.ts-table-page__actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--ts-sp-2);
  flex: none;
  flex-wrap: wrap;
}

.ts-table-page__bar {
  display: flex;
  align-items: center;
  gap: var(--ts-sp-3);
  /* 工具条与表格块之间留出呼吸：原来是 12px 紧贴，筛选区看着像表格的一部分 */
  margin-bottom: var(--ts-sp-3);
  min-height: 34px;
  flex-wrap: wrap;
}

.ts-table-page__bar-main {
  flex: 1 1 auto;
  min-width: 0;
}

.ts-table-page__cols-btn {
  flex: none;
}

/* 表格块：白底 + 描边 + 圆角，让数据区在页面上有明确边界 */
.ts-table-page__surface {
  background: var(--ts-surface);
  border: 1px solid var(--ts-border);
  border-radius: var(--ts-r-md);
  overflow: hidden;
}

.ts-table-page__table {
  /* 外边框由 __surface 提供，EP 的 border 只保留内部网格线 */
  border-radius: 0;
}

.ts-table-page__mobile {
  display: flex;
  flex-direction: column;
  gap: var(--ts-sp-3);
}

.ts-table-page__card {
  padding: var(--ts-sp-4);
  border: 1px solid var(--ts-border);
  border-radius: var(--ts-r-md);
  background: var(--ts-surface);
  box-shadow: var(--ts-shadow-sm);
}

.ts-table-page__card-head {
  display: flex;
  justify-content: space-between;
  gap: var(--ts-sp-2);
  align-items: flex-start;
}

.ts-table-page__card-title {
  word-break: break-all;
  font-size: var(--ts-fs-card);
  line-height: var(--ts-lh-tight);
  color: var(--ts-text);
}

.ts-table-page__card-tags {
  display: flex;
  gap: var(--ts-sp-1);
  flex-wrap: wrap;
  justify-content: flex-end;
}

.ts-table-page__card-row {
  display: flex;
  justify-content: space-between;
  gap: var(--ts-sp-3);
  padding: var(--ts-sp-1) 0;
  font-size: var(--ts-fs-caption);
  line-height: var(--ts-lh-body);
}

.ts-table-page__card-value {
  word-break: break-all;
  text-align: right;
  color: var(--ts-text);
}

.ts-table-page__card-actions {
  display: flex;
  gap: var(--ts-sp-2);
  flex-wrap: wrap;
  margin-top: var(--ts-sp-3);
  padding-top: var(--ts-sp-3);
  border-top: 1px solid var(--ts-border);
}

.ts-table-page__pager {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  margin-top: var(--ts-sp-4);
}

/* 带左侧附加信息时改为两端对齐（W4-E：消息中心的「本页未读 N 条」） */
.ts-table-page__pager--with-extra {
  justify-content: space-between;
}

.ts-table-page__pager-extra {
  flex: 1 1 auto;
  min-width: 0;
}

@media (max-width: 767px) {
  /* 手机：标题与操作区上下排，按钮靠左（拇指区），不强行撑满 —— 
     撑满会让「刷新」这类次要按钮占掉半屏。 */
  .ts-table-page__head {
    flex-direction: column;
    align-items: stretch;
    gap: var(--ts-sp-3);
    margin-bottom: var(--ts-sp-3);
  }

  .ts-table-page__actions {
    justify-content: flex-start;
  }

  .ts-table-page__bar {
    flex-direction: column;
    align-items: stretch;
  }

  .ts-table-page__card-actions .el-button {
    flex: 1 1 0;
    margin-left: 0;
  }

  .ts-table-page__pager {
    justify-content: center;
  }
}
</style>
