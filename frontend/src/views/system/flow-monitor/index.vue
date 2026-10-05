<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { flowMonitorApi } from '@/api/flowMonitor'
import TablePage from '@/components/TablePage.vue'
import { useResponsive } from '@/composables/useResponsive'
import {
  BOTTLENECK_MODE_OPTIONS,
  EMPTY_PLACEHOLDER,
  UNATTRIBUTED_FLOW_ID,
  bottleneckText,
  formatMonitorHours,
  formatRate,
  nodeDisplayName,
  nodeTypeLabel,
  overdueRateHint,
  runtimeActivatedHint,
  sampleHint,
  type BottleneckMode,
  type FlowMonitorFlow,
  type FlowMonitorNode
} from '@/types/flowMonitor'
import type { ColumnDef } from '@/types/table'

/**
 * 流程监控（ · M7，仅 super_admin / admin）
 *
 * 回答三个问题：**每个流程模板跑了多少单 / 平均审批多久 / 卡在哪个节点**。
 *
 * 三条设计取向：
 *
 * 1. **不在前端重算任何口径。** 工单量、平均时长、平均节点耗时、超时率全部由后端聚合 SQL
 *    算好后下发，本页只做格式化与排序。前端一旦自己 group by，就会出现"页面数字"与
 *    "接口数字"两套来源 —— 项目在 M4a 已经为这类漂移付过一次代价。
 *
 * 2. **瓶颈给两个口径、让用户切。** 「平均耗时最大」与「超时率最高」经常不是同一个节点：
 *    一个可能单次都快但偶尔严重超时，另一个可能次次都要等半天但从不越线。
 *    默认按平均耗时（更直观），但切换按钮必须显眼，否则用户会把一个口径的答案当成全部。
 *
 * 3. **「无法判定」与「0」严格区分。** 超时率的分母是"有时限的轮次数"，
 *    没设时限的节点算不出超时率 —— 后端返回 null，这里显示「—」并用 tooltip 说明原因。
 *    把它显示成 0% 会把"没人给节点设时限"说成"流程很健康"，是最危险的一类误读。
 *
 * 关于「未归属」行：后端恒返回一行 flowId = 0 的分组，收纳走分组固定审批人表 / 借用单内置流程 /
 * 存量历史 / 模板已删除的工单。本页**不做过滤**，只把它排在最后并加标注 ——
 * 静默丢弃会让"工单总量"对不上账。
 *
 * ## 为什么要一层「视图模型」
 * `el-table` 的插槽 `row` 在类型上是宽松的记录类型，不能直接喂给 `bottleneckText(row, mode)`
 * 这类具体类型的函数；而本项目**禁止在模板里写 TS 断言**（`!` / `as`），
 * 所以类型收窄必须留在 `<script>`。做法是把每行要显示的文案在 computed 里算好，
 * 模板只渲染字符串 —— 顺带避免了每次重渲染都重算一遍格式化。
 *
 * ---
 *  · W4-E：**T2 档** —— 只迁 `TablePage` 骨架（主表 + 抽屉内的节点明细表），
 * **不接筛选持久化**（本页无筛选控件）。
 *
 * ⚠️ 一处**刻意未做**的事，在此留证以免被当成遗漏：
 * 评审挂账 R6 提到「监控指标没有时间范围，全部是全历史口径」，本批**不改**。原因是它不是一个
 * 样式问题：要加时间范围必须同时改后端聚合 SQL（`flowSummary` / `nodeSummary` 的 WHERE）
 * 与 `FlowMonitorController` 的出参，并连带解决 R1「`flowSummary` 无索引支撑」——
 * 那需要一次新的 Flyway 迁移。而 R6 原文（`phase16-wave2b-code-review.md`）本身是 **FYI** 级，
 * 且明确建议「**与 M6 仪表盘一起排期**，届时统一给监控页与仪表盘加时间选择器，R1 的索引问题也会一并解决」。
 * 方案 也已把 W4-E 定义为**纯前端**批次。因此该挂账保持打开，建议单独立项。
 */

const { isMobile } = useResponsive()

/** 主表列定义（逐列照抄改造前 el-table-column） */
const flowColumns: ColumnDef[] = [
  { key: 'flowName', label: '流程模板', minWidth: 200, showOverflowTooltip: true, card: 'title' },
  { key: 'orderCount', label: '工单量', minWidth: 160, align: 'right', sortable: true },
  { key: 'avgApprovalHours', label: '平均审批时长', minWidth: 130, align: 'right', sortable: true },
  { key: 'bottleneckText', label: '瓶颈节点', minWidth: 200, showOverflowTooltip: true },
  { key: 'versionLabels', label: '版本', minWidth: 110 },
  { key: 'action', label: '操作', minWidth: 120, align: 'center', fixed: 'right', configurable: false, card: false }
]

/** 抽屉内节点明细表列定义 */
const nodeColumns: ColumnDef[] = [
  { key: 'nameText', label: '节点', minWidth: 170, showOverflowTooltip: true, card: 'title' },
  { key: 'typeText', label: '类型', minWidth: 80, align: 'center' },
  { key: 'sampleCount', label: '样本', minWidth: 90, align: 'right' },
  { key: 'avgHours', label: '平均耗时', minWidth: 110, align: 'right', sortable: true },
  { key: 'overdueRate', label: '超时率', minWidth: 130, align: 'right', sortable: true }
]

const loading = ref(false)
const flows = ref<FlowMonitorFlow[]>([])

/** 瓶颈口径（见文件头第 2 条） */
const bottleneckMode = ref<BottleneckMode>('duration')

/** 节点明细抽屉 */
const detailVisible = ref(false)
const detailFlowName = ref('')
const detailNodes = ref<FlowMonitorNode[]>([])
const detailLoading = ref(false)
/**
 * 抽屉请求序号：用户快速连点不同模板时，先发的请求可能后到。
 * 没有这个守卫，抽屉里会显示上一个模板的节点 —— 而且看不出来是错的。
 */
let detailSeq = 0

async function load(): Promise<void> {
  loading.value = true
  try {
    flows.value = await flowMonitorApi.flows()
  } catch {
    // 请求层已统一提示；保留上一次结果，避免刷新失败时页面清空造成误判
  } finally {
    loading.value = false
  }
}

async function openDetail(flowId: number, flowName: string): Promise<void> {
  detailFlowName.value = flowName
  detailNodes.value = []
  detailVisible.value = true
  const seq = ++detailSeq
  detailLoading.value = true
  try {
    const nodes = await flowMonitorApi.nodes(flowId)
    if (seq === detailSeq) {
      detailNodes.value = nodes
    }
  } catch {
    // 请求层已提示
  } finally {
    if (seq === detailSeq) {
      detailLoading.value = false
    }
  }
}

/** 汇总表视图模型：把逐行文案预先算好，模板里只渲染字符串 */
interface FlowRow extends FlowMonitorFlow {
  sampleText: string
  avgText: string
  bottleneckText: string
}

const flowRows = computed<FlowRow[]>(() =>
  flows.value.map((row) => ({
    ...row,
    sampleText: sampleHint(row),
    avgText: formatMonitorHours(row.avgApprovalHours),
    bottleneckText: bottleneckText(row, bottleneckMode.value)
  }))
)

/** 节点明细视图模型，同上 */
interface NodeRow extends FlowMonitorNode {
  nameText: string
  typeText: string
  avgText: string
  rateText: string
  rateHint: string
  runtimeHint: string | null
}

const nodeRows = computed<NodeRow[]>(() =>
  detailNodes.value.map((row) => ({
    ...row,
    nameText: nodeDisplayName(row),
    typeText: nodeTypeLabel(row.nodeType),
    avgText: formatMonitorHours(row.avgHours),
    rateText: formatRate(row.overdueRate),
    rateHint: overdueRateHint(row),
    runtimeHint: runtimeActivatedHint(row)
  }))
)

/** 统计卡：全部为「对后端结果的直接汇总」，不含任何需要再定义的指标 */
const stats = computed(() => {
  const rows = flows.value
  const unattributed = rows.find((row) => row.flowId === UNATTRIBUTED_FLOW_ID)
  const totalOrders = rows.reduce((sum, row) => sum + row.orderCount, 0)
  // 平均审批时长最长的模板：只比较「有样本」的行（null 表示没有完成的审批，不可比）
  const slowest = rows
    .filter((row) => row.avgApprovalHours != null && row.approvedOrderCount > 0)
    .reduce<FlowMonitorFlow | null>(
      (best, row) =>
        best == null || (row.avgApprovalHours ?? 0) > (best.avgApprovalHours ?? 0) ? row : best,
      null
    )
  return {
    flowCount: rows.filter((row) => row.flowId !== UNATTRIBUTED_FLOW_ID).length,
    totalOrders,
    unattributedOrders: unattributed?.orderCount ?? 0,
    slowestName: slowest?.flowName ?? null,
    slowestHours: slowest ? formatMonitorHours(slowest.avgApprovalHours) : ''
  }
})

/**
 * 未归属行弱化显示。
 *
 * 必须定义在 `<script setup>`：模板表达式解析的是 setup 作用域，
 * 写在普通 `<script>` 块的 `methods` 上模板根本取不到（会静默变成 undefined）。
 * 迁移后它由 `$attrs` 透传到 `TablePage` 内部的 `el-table`，行为不变。
 */
function rowClassName({ row }: { row: FlowMonitorFlow }): string {
  return row.unattributed ? 'ts-fm__row-unattributed' : ''
}

function handleRefresh(): void {
  void load()
}

onMounted(load)
</script>

<template>
  <div class="ts-page">
    <section class="ts-card">
      <div class="ts-flex-between ts-fm__head">
        <div>
          <h3 class="ts-fm__title">流程监控</h3>
          <p class="ts-text-secondary ts-fm__desc">
            按流程模板统计工单量、平均审批时长与瓶颈节点；数据取自各工单提交时冻结的流程快照，
            模板后续改动不会改写历史统计。
          </p>
        </div>
        <div class="ts-fm__head-actions">
          <el-radio-group v-model="bottleneckMode" size="small">
            <el-radio-button
              v-for="option in BOTTLENECK_MODE_OPTIONS"
              :key="option.value"
              :value="option.value"
            >
              {{ option.label }}
            </el-radio-button>
          </el-radio-group>
          <el-button :loading="loading" @click="handleRefresh">刷新</el-button>
        </div>
      </div>

      <div class="ts-fm__stats ts-mt-16">
        <div class="ts-fm__stat">
          <span class="ts-text-hint">流程模板数</span>
          <strong class="ts-fm__stat-value">{{ stats.flowCount }}</strong>
        </div>
        <div class="ts-fm__stat">
          <span class="ts-text-hint">走流程工单总量</span>
          <strong class="ts-fm__stat-value">{{ stats.totalOrders }}</strong>
        </div>
        <div class="ts-fm__stat">
          <span class="ts-text-hint">未归属工单</span>
          <strong class="ts-fm__stat-value">
            {{ stats.unattributedOrders }}
            <span v-if="stats.unattributedOrders > 0" class="ts-fm__stat-hint">未走自定义流程模板</span>
          </strong>
        </div>
        <div class="ts-fm__stat">
          <span class="ts-text-hint">平均耗时最长的模板</span>
          <strong class="ts-fm__stat-value">{{ stats.slowestName ?? EMPTY_PLACEHOLDER }}</strong>
          <span v-if="stats.slowestName" class="ts-fm__stat-hint">{{ stats.slowestHours }}</span>
        </div>
      </div>

      <h4 class="ts-fm__section">模板维度</h4>
      <TablePage
        :columns="flowColumns"
        :rows="flowRows"
        :loading="loading"
        route-path="/system/flow-monitor"
        :show-pagination="false"
        :show-column-config="false"
        empty-text="暂无走过流程的工单"
        :row-class-name="rowClassName"
      >
        <template #cell-flowName="{ row }">
          <span :class="{ 'ts-fm__unattributed': (row as FlowRow).unattributed }">
            {{ (row as FlowRow).flowName }}
          </span>
          <el-tag v-if="(row as FlowRow).unattributed" size="small" type="info" effect="plain" class="ts-fm__tag">
            未归属
          </el-tag>
        </template>
        <template #cell-orderCount="{ row }">
          <span>{{ (row as FlowRow).orderCount }}</span>
          <div class="ts-text-hint ts-fm__cell-hint">{{ (row as FlowRow).sampleText }}</div>
        </template>
        <template #cell-avgApprovalHours="{ row }">{{ (row as FlowRow).avgText }}</template>
        <template #cell-bottleneckText="{ row }">{{ (row as FlowRow).bottleneckText }}</template>
        <template #cell-versionLabels="{ row }">
          {{ (row as FlowRow).versionLabels || EMPTY_PLACEHOLDER }}
        </template>
        <template #cell-action="{ row }">
          <el-button
            link
            type="primary"
            size="small"
            @click.stop="openDetail((row as FlowRow).flowId, (row as FlowRow).flowName)"
          >
            节点明细
          </el-button>
        </template>

        <!-- 移动端：走通用卡片，操作走 #card-actions -->
        <template #card-actions="{ row }">
          <el-button size="small" type="primary" plain @click="openDetail((row as FlowRow).flowId, (row as FlowRow).flowName)">
            节点明细
          </el-button>
        </template>
      </TablePage>

      <p class="ts-text-hint ts-fm__note">
        点击任意行的「节点明细」查看该模板各节点的平均耗时与超时率。平均审批时长 = 从工单提交到最后一个
        审批节点作出结论（通过或驳回）之间的时间；仍在审批中的工单不计入平均值，其数量见「工单量」列的补充说明。
      </p>
    </section>

    <!-- 节点明细 -->
    <el-drawer
      v-model="detailVisible"
      :size="isMobile ? '100%' : '720px'"
      :title="detailFlowName ? `节点明细 · ${detailFlowName}` : '节点明细'"
    >
      <div>
        <TablePage
          :columns="nodeColumns"
          :rows="nodeRows"
          :loading="detailLoading"
          route-path="/system/flow-monitor/nodes"
          :show-pagination="false"
          :show-column-config="false"
          empty-text="该模板暂无已作出结论的审批节点"
        >
          <template #cell-nameText="{ row }">
            <span>{{ (row as NodeRow).nameText }}</span>
            <div class="ts-text-hint ts-fm__cell-hint">
              key：{{ (row as NodeRow).nodeKey || EMPTY_PLACEHOLDER }}
            </div>
          </template>
          <template #cell-typeText="{ row }">{{ (row as NodeRow).typeText }}</template>
          <template #cell-sampleCount="{ row }">
            <span>{{ (row as NodeRow).sampleCount }}</span>
            <div
              v-if="(row as NodeRow).runtimeHint"
              class="ts-text-hint ts-fm__cell-hint ts-fm__runtime"
              :title="(row as NodeRow).runtimeHint ?? ''"
            >
              运行期
            </div>
          </template>
          <template #cell-avgHours="{ row }">{{ (row as NodeRow).avgText }}</template>
          <template #cell-overdueRate="{ row }">
            <span
              :class="{ 'ts-text-hint': (row as NodeRow).overdueRate == null }"
              :title="(row as NodeRow).rateHint"
            >
              {{ (row as NodeRow).rateText }}
            </span>
            <div class="ts-text-hint ts-fm__cell-hint">{{ (row as NodeRow).rateHint }}</div>
          </template>
        </TablePage>

        <p class="ts-text-hint ts-fm__note">
          样本按「审批人轮次」计数：会签节点一张单会落多行，因此样本数可能大于工单量。
          超时率的分母只统计<strong>配置了时限</strong>的轮次；没有节点配置时限时该列为「—」，
          这不是 0%，而是无法判定。
        </p>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.ts-fm__head {
  gap: 16px;
  align-items: flex-start;
}

.ts-fm__title {
  margin: 0;
  font-size: 16px;
  font-weight: 500;
}

.ts-fm__desc {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.6;
}

.ts-fm__head-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.ts-fm__stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 12px;
}

.ts-fm__stat {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px 14px;
  background: #f7f9fc;
  border-radius: 8px;
}

.ts-fm__stat-value {
  font-size: 20px;
  font-weight: 600;
  color: var(--ts-text-primary, #303133);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ts-fm__stat-hint {
  font-size: 12px;
  font-weight: 400;
  color: var(--el-text-color-secondary);
}

.ts-fm__section {
  margin: 16px 0 8px;
  font-size: 14px;
  font-weight: 500;
}

.ts-fm__cell-hint {
  font-size: 11px;
  line-height: 1.4;
}

.ts-fm__runtime {
  color: var(--el-color-warning);
}

.ts-fm__tag {
  margin-left: 6px;
}

.ts-fm__unattributed {
  color: var(--el-text-color-secondary);
}

.ts-fm__note {
  margin: 12px 0 0;
  font-size: 12px;
  line-height: 1.6;
}

/* 未归属行弱化：用 :deep 穿透 scoped，与 el-table 的 row-class-name 配合 */
:deep(.ts-fm__row-unattributed) {
  --el-table-tr-bg-color: var(--el-fill-color-lighter);
}

@media (max-width: 767px) {
  .ts-fm__head {
    flex-direction: column;
  }

  .ts-fm__head-actions {
    width: 100%;
  }

  .ts-fm__head-actions .el-button {
    flex: 1 1 0;
  }
}
</style>
