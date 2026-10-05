<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ArrowDown, Delete, Plus, Promotion } from '@element-plus/icons-vue'
import UserSelectDialog from '@/components/UserSelectDialog.vue'
import { useResponsive } from '@/composables/useResponsive'
import { useFlowCanvas, type NodeRef } from '@/composables/useFlowCanvas'
import { useFlowDisplay } from '@/composables/useFlowDisplay'
import { useFlowGraphEditor } from '@/composables/useFlowGraphEditor'
import {
  useFlowConditionTree,
  type ConditionPath
} from '@/composables/useFlowConditionTree'
import type { UserOption } from '@/types/user'
import {
  APPROVER_RULE_TYPE_OPTIONS,
  ASSIGN_SCOPE_OPTIONS,
  CHOOSE_SCOPE_OPTIONS,
  FLOW_OPERATOR_OPTIONS,
  REJECT_ACTION_OPTIONS,
  SIGN_TYPE_OPTIONS,
  TIMEOUT_ACTION_OPTIONS,
  flowHasRuntimeFeature,
  isElseBranch,
  nodeTypeLabel,
  validateFlowForPublish,
  type ApproverRule,
  type ApproverRuleTypeCode,
  type AssignScopeCode,
  type ChooseScopeCode,
  type FlowBranch,
  type FlowCondition,
  type FlowDefinition,
  type FlowFieldOption,
  type FlowNode,
  type FlowNodeTypeCode,
  type RejectActionCode,
  type TimeoutActionCode
} from '@/types/approvalFlow'

/**
 * 审批流程设计器（ · ）
 *
 * <h2>为什么手写、不引入 X6 / G6 / LogicFlow</h2>
 * 与一期 `FormDesigner.vue` 保持同一取向：新增重依赖会破坏 tree-shaking，
 * 且"自由拖拽坐标"对绝大多数审批流程（一条主干 + 少数条件分支）是过设计。
 * 这里只做**树形画布**：主干自上而下线性排布，条件节点的每个出口缩进成一条支链。
 *
 * <h2>编辑器直接编辑后端那一份 JSON（无中间模型）</h2>
 * `v-model` 绑定的是与后端 `common/flow` POJO 同构的 {@link FlowDefinition}（`nodes[]` +
 * 显式的 `next` / `branches[].next`）。所有增删改都**就地改这份结构**，不做
 * "编辑模型 ↔ 存储模型"的双向转换 —— 少一层转换就少一类"改了这边忘了那边"的裂缝。
 *
 * <h2>为什么画布按 DFS 展开而不是平铺所有节点</h2>
 * 流程是 DAG：条件分支会**汇合**（回归用例里 `n2.next` 与 `b2.next` 都指向 `n3`）。
 * 平铺无法表达"从哪来、到哪去"；DFS 展开成树则一眼看清路径，而**汇合点第二次出现时
 * 渲染成「汇合到 …」引用块**，既不重复展示，也诚实标出这是一次汇合。
 *
 * <h2>模板里不写 TS 断言</h2>
 * 模板表达式的能力集比 `<script>` 窄（一期 `FormDesigner` 已踩过 `selectedIndex as number`
 * 的坑）。因此这里把"选中节点"相关的写操作全部包成**收 `string | null` 的处理器**，
 * 模板只做最朴素的调用，类型收窄与 `as` 转换全部留在脚本里。
 *
 * <h2>设计器只负责编辑，不负责保存</h2>
 * 与 `FormDesigner` 一致：保存 / 发布由页面（持有流程 id）发起。
 */
const props = withDefaults(
  defineProps<{
    modelValue: FlowDefinition
    /** 只读（仅 approval_flow:view，如 admin）：可看不可改 */
    readonly?: boolean
    /** 保存 / 发布中（禁用底部按钮） */
    saving?: boolean
    /** 角色下拉（来源 = 指定角色） */
    roleOptions?: Array<{ roleCode: string; roleName: string }>
    /** 部门下拉（申请人自选的范围 = 指定部门； 起部门是唯一的组织实体） */
    departmentOptions?: Array<{ id: number; deptName: string }>
    /** 最终处理部门下拉（来源 = 最终处理部门成员；全系统只有一个 IT运维组） */
    handlerDepartmentOptions?: Array<{ id: number; deptName: string }>
    /** 参考表单字段（条件字段 / 表单人员字段的候选项；为空则退化为手填 key） */
    fieldOptions?: FlowFieldOption[]
    /** 已知用户姓名（用于回显已选人员；与本地缓存合并） */
    userNameMap?: Record<number, string>
    /**
     * 当前校验域下不可用的审批人来源（ · W4-D / C8）。
     *
     * 传空数组（默认）= 全部可用 —— 因此不传的既有调用方行为**逐字节不变**。
     * 采用「置灰」而非「从列表移除」：切域后已被配置来源需要仍然可见、有名字，
     * 否则 el-select 会退化成显示原始编码，配置者看不懂自己配了什么。
     */
    forbiddenRuleTypes?: ApproverRuleTypeCode[]
  }>(),
  {
    readonly: false,
    saving: false,
    roleOptions: () => [],
    departmentOptions: () => [],
    handlerDepartmentOptions: () => [],
    fieldOptions: () => [],
    userNameMap: () => ({}),
    forbiddenRuleTypes: () => []
  }
)

const emit = defineEmits<{
  'update:modelValue': [FlowDefinition]
  save: []
  publish: []
}>()

const { isMobile } = useResponsive()

/** 移动端：在「图例 / 画布 / 属性」之间切换 */
const mobilePane = ref<'palette' | 'canvas' | 'props'>('canvas')

// ----------------------------------------------------------------------
// 画布渲染 + 选中态（W4-A3：已抽为 composables/useFlowCanvas.ts）
// ----------------------------------------------------------------------

const {
  nodeList,
  nodeIndex,
  canvas,
  selectedKey,
  selectedBranchKey,
  selectedNode,
  select: selectCanvasNode
} = useFlowCanvas(() => props.modelValue)

/**
 * 选中节点 / 条件分支，并切到属性面板。
 *
 * <p>「切面板」是移动端三栏布局的视图策略，不属于画布模块 —— 画布只负责"谁是选中项"，
 * 因此这里包一层，把视图副作用留在组件里。
 */
function select(nodeKey: string, branchKey?: string): void {
  selectCanvasNode(nodeKey, branchKey)
  mobilePane.value = 'props'
}

// ----------------------------------------------------------------------
// 展示辅助（W4-A3：已抽为 composables/useFlowDisplay.ts）
// ----------------------------------------------------------------------

const {
  localUserNames,
  userNameOf,
  formFieldOptions,
  processFieldCandidates,
  userFieldOptions,
  conditionValueOptions,
  nodeNameOf,
  nodeTypeTag,
  nodeSummary,
  branchSummary
} = useFlowDisplay({
  fieldOptions: () => props.fieldOptions ?? [],
  roleOptions: () => props.roleOptions,
  handlerDepartmentOptions: () => props.handlerDepartmentOptions,
  userNameMap: () => props.userNameMap,
  nodeIndex
})

// ----------------------------------------------------------------------
// 结构变更（W4-A3：已抽为 composables/useFlowGraphEditor.ts）
// ----------------------------------------------------------------------

const {
  updateNode,
  addNodeAt,
  insertNodeAfter,
  handleAddCommand,
  confirmRemoveNode,
  uniqueBranchKey,
  existingDialogVisible,
  existingPick,
  existingCandidates,
  chooseExisting
} = useFlowGraphEditor({
  readonly: () => props.readonly,
  modelValue: () => props.modelValue,
  nodeList,
  nodeIndex,
  selectedKey,
  select,
  // 唯一写出口：所有结构变更都产出新对象并交给父组件，组件内不做就地修改
  commitUpdate: (definition) => emit('update:modelValue', definition)
})

// ----------------------------------------------------------------------
// 审批人规则
// ----------------------------------------------------------------------

function addRule(nodeKey: string): void {
  const node = nodeIndex.value.get(nodeKey)
  if (!node) {
    return
  }
  updateNode(nodeKey, {
    approverRules: [...(node.approverRules ?? []), { type: 'SPECIFIC_USER', userIds: [] }]
  })
}

function removeRule(nodeKey: string, ruleIndex: number): void {
  const node = nodeIndex.value.get(nodeKey)
  if (!node) {
    return
  }
  updateNode(nodeKey, {
    approverRules: (node.approverRules ?? []).filter((_, index) => index !== ruleIndex)
  })
}

function updateRule(nodeKey: string, ruleIndex: number, patch: Partial<ApproverRule>): void {
  const node = nodeIndex.value.get(nodeKey)
  if (!node) {
    return
  }
  updateNode(nodeKey, {
    approverRules: (node.approverRules ?? []).map((rule, index) =>
      index === ruleIndex ? { ...rule, ...patch } : rule
    )
  })
}

/**
 * 切换来源类型时**重建**规则对象（而非在原对象上打补丁）。
 * 若沿用旧对象，`roleCode` / `userIds` 等来自上一种来源的残留字段会一起提交 ——
 * 后端虽只读"当前类型需要的那几个字段"，但 JSON 里留着无意义的脏值是隐患。
 */
function changeRuleType(nodeKey: string, ruleIndex: number, value: unknown): void {
  const type = String(value) as ApproverRuleTypeCode
  if (!APPROVER_RULE_TYPE_OPTIONS.some((option) => option.value === type)) {
    return
  }
  const fresh: ApproverRule = { type }
  if (type === 'SPECIFIC_USER') {
    fresh.userIds = []
  }
  if (type === 'PREV_ASSIGN') {
    // 人数默认 1：与后端「缺省视为 1」一致，避免设计器里显示空输入框而后端按 1 处理的口径分裂
    fresh.assignCount = 1
    // 范围默认 ALL（不限制）：与存量流程的缺省口径一致。
    // 不默认成 IT_EXECUTOR —— 那会让"新建一个上一节点指定的节点"悄悄多出一条限制，
    // 设计者没选过任何范围，却会在提交时被服务端拒绝指派。
    fresh.assignScope = 'ALL'
  }
  if (type === 'APPLICANT_CHOOSE') {
    fresh.scope = 'ALL'
    fresh.minCount = 1
    fresh.maxCount = 1
  }
  // 「上一节点指定」只支持或签：切到它时把签署方式拉回 ANY_SIGN，
  // 否则用户会看到「已选会签」却被发布校验拒绝，得自己回头找原因
  if (type === 'PREV_ASSIGN') {
    const node = nodeIndex.value.get(nodeKey)
    const rules = (node?.approverRules ?? []).map((rule, index) =>
      index === ruleIndex ? fresh : rule
    )
    updateNode(nodeKey, { approverRules: rules, signType: 'ANY_SIGN' })
    return
  }
  updateRule(nodeKey, ruleIndex, fresh)
}

// 人员选择弹窗（SPECIFIC_USER）
const userPickerVisible = ref(false)
const userPickerTarget = ref<{ nodeKey: string; ruleIndex: number } | null>(null)

function openUserPicker(nodeKey: string, ruleIndex: number): void {
  userPickerTarget.value = { nodeKey, ruleIndex }
  userPickerVisible.value = true
}

function onUsersPicked(users: UserOption[]): void {
  const target = userPickerTarget.value
  if (!target) {
    return
  }
  for (const user of users) {
    localUserNames[user.id] = user.displayName
  }
  updateRule(target.nodeKey, target.ruleIndex, { userIds: users.map((user) => user.id) })
}

// ----------------------------------------------------------------------
// 条件分支
// ----------------------------------------------------------------------

function addBranch(condKey: string): void {
  const node = nodeIndex.value.get(condKey)
  if (!node) {
    return
  }
  const branch: FlowBranch = {
    key: uniqueBranchKey(node),
    name: `分支${(node.branches ?? []).length + 1}`,
    else: false,
    condition: { logic: 'AND', rules: [{ field: '', op: 'EQ' }] },
    next: null
  }
  updateNode(condKey, { branches: [...(node.branches ?? []), branch] })
  selectedBranchKey.value = branch.key
}

function removeBranch(condKey: string, branchKey: string): void {
  const node = nodeIndex.value.get(condKey)
  if (!node) {
    return
  }
  const branch = (node.branches ?? []).find((item) => item.key === branchKey)
  if (!branch) {
    return
  }
  if (isElseBranch(branch)) {
    ElMessage.warning('默认出口不可删除：条件全不满足时需要它兜底')
    return
  }
  if ((node.branches ?? []).length <= 1) {
    ElMessage.warning('条件节点至少需要一个分支')
    return
  }
  updateNode(condKey, {
    branches: (node.branches ?? []).filter((item) => item.key !== branchKey)
  })
}

function updateBranch(condKey: string, branchKey: string, patch: Partial<FlowBranch>): void {
  const node = nodeIndex.value.get(condKey)
  if (!node) {
    return
  }
  updateNode(condKey, {
    branches: (node.branches ?? []).map((branch) =>
      branch.key === branchKey ? { ...branch, ...patch } : branch
    )
  })
}

/** 把某分支设为默认出口（其余分支自动取消 else，保证"恰好一个"） */
function setElseBranch(condKey: string, branchKey: string, isElse: boolean): void {
  const node = nodeIndex.value.get(condKey)
  if (!node) {
    return
  }
  updateNode(condKey, {
    branches: (node.branches ?? []).map((branch) => {
      if (branch.key === branchKey) {
        return isElse
          ? { ...branch, else: true, condition: null }
          : {
              ...branch,
              else: false,
              condition: branch.condition ?? { logic: 'AND', rules: [{ field: '', op: 'EQ' }] }
            }
      }
      return isElse ? { ...branch, else: false } : branch
    })
  })
}

function branchCondition(branch: FlowBranch): FlowCondition {
  return branch.condition ?? { logic: 'AND', rules: [] }
}

function findBranch(condKey: string, branchKey: string): FlowBranch | undefined {
  return (nodeIndex.value.get(condKey)?.branches ?? []).find((item) => item.key === branchKey)
}

function updateConditionLogic(condKey: string, branchKey: string, value: unknown): void {
  const branch = findBranch(condKey, branchKey)
  if (!branch) {
    return
  }
  const logic = value === 'OR' ? 'OR' : 'AND'
  updateBranch(condKey, branchKey, { condition: { ...branchCondition(branch), logic } })
}

// ----------------------------------------------------------------------
// 条件树（M3-A）：按「路径」寻址（W4-A3：已抽为 composables/useFlowConditionTree.ts）
// ----------------------------------------------------------------------

const {
  appendRule,
  appendSubGroup,
  removeNodeAt,
  patchRuleAt,
  setGroupLogicAt,
  changeOperatorAt,
  canAppendSubGroup,
  subGroupHint,
  conditionRowsOf
} = useFlowConditionTree({
  findBranch,
  updateBranch,
  branchCondition,
  conditionValueOptions
})
// ----------------------------------------------------------------------
// 模板用处理器：收 `string | null` 与 `unknown`，把类型收窄 / 断言留在脚本里
// ----------------------------------------------------------------------

function pickNode(nodeKey: string | undefined): void {
  if (nodeKey) {
    select(nodeKey)
  }
}

function selectNodeBranch(nodeKey: string, branchKey: string, isElse: boolean): void {
  selectedKey.value = nodeKey
  selectedBranchKey.value = isElse ? null : branchKey
  mobilePane.value = 'props'
}

function onAddCommand(ref: NodeRef | undefined, command: unknown): void {
  if (ref) {
    handleAddCommand(ref, String(command))
  }
}

function onInsertCommand(nodeKey: string | undefined, command: unknown): void {
  const type = String(command)
  if (nodeKey && (type === 'APPROVAL' || type === 'CC' || type === 'CONDITION')) {
    insertNodeAfter(nodeKey, type as FlowNodeTypeCode)
  }
}

/**
 * 抄送节点上语义矛盾的两种规则。
 *
 * 抄送要在**提交那一刻**就能算出对象并发消息；「申请人自选」要等申请人提交时选、
 * 「上一节点指定」要等上一节点通过时点 —— 两者都把"定人"推到了提交之后。
 * 因此不在这里提供它们（后端发布校验同样会拒），把错误挡在配置阶段。
 */
const CC_FORBIDDEN_RULE_TYPES: ApproverRuleTypeCode[] = ['APPLICANT_CHOOSE', 'PREV_ASSIGN']

/** 当前选中节点可选的审批人来源（抄送节点过滤掉上面两类） */
const ruleOptionsForNode = computed(() =>
  selectedNode.value?.type === 'CC'
    ? APPROVER_RULE_TYPE_OPTIONS.filter((option) => !CC_FORBIDDEN_RULE_TYPES.includes(option.value))
    : APPROVER_RULE_TYPE_OPTIONS
)

function updateSelectedNode(patch: Partial<FlowNode>): void {
  if (selectedKey.value) {
    updateNode(selectedKey.value, patch)
  }
}

function setSelectedName(value: unknown): void {
  updateSelectedNode({ name: String(value) })
}

function setSelectedSignType(value: unknown): void {
  updateSelectedNode({ signType: String(value) === 'ALL_SIGN' ? 'ALL_SIGN' : 'ANY_SIGN' })
}

/**
 * 审批时限。空 / 0 / 负数一律写 null（= 不限时）——
 * 与后端 `deadlineOf` 的判定同口径（`hours == null || hours <= 0` 即不限时），
 * 避免前端存了 0 而后端按"不限时"处理、两边对「配了没有」的理解不一致。
 */
function setSelectedTimeLimit(value: unknown): void {
  if (value === null || value === undefined || value === '') {
    updateSelectedNode({ timeLimitHours: null })
    return
  }
  const hours = Number(value)
  updateSelectedNode({ timeLimitHours: Number.isFinite(hours) && hours > 0 ? hours : null })
}

// ----------------------------------------------------------------------
// 运行期动作（M2）：驳回处理 / 超时处理
// ----------------------------------------------------------------------

/**
 * 可作为「改道目标」的节点：审批 / 抄送节点，且排除本节点。
 *
 * <p>为什么排除 END 与 CONDITION：改道到 END 等于换个方式终止整单、改道到 CONDITION
 * 会立刻再次分叉 —— 两者都不是"改道"的语义。后端 `checkActionTarget` 同样只接受这两类，
 * 前端先过滤掉，避免让人配出一个必然被拒的目标。
 */
const gotoNodeOptions = computed(() =>
  nodeList.value
    .filter(
      (node) => (node.type === 'APPROVAL' || node.type === 'CC') && node.key !== selectedKey.value
    )
    .map((node) => ({ value: node.key, label: `${node.name || node.key}（${node.key}）` }))
)

function setRejectAction(value: unknown): void {
  const action: RejectActionCode = String(value) === 'GOTO' ? 'GOTO' : 'TERMINATE'
  // 回到 TERMINATE 时把 target 一并清掉：留着它会让定义里出现"终止动作 + 改道目标"
  // 这种自相矛盾的残留，日后排查时极具误导性。
  updateSelectedNode({
    onReject:
      action === 'GOTO'
        ? { action, target: selectedNode.value?.onReject?.target ?? null }
        : { action, target: null }
  })
}

function setRejectTarget(value: unknown): void {
  updateSelectedNode({ onReject: { action: 'GOTO', target: value ? String(value) : null } })
}

function setTimeoutAction(value: unknown): void {
  const raw = String(value)
  const action: TimeoutActionCode =
    raw === 'ADD_SIGN' ? 'ADD_SIGN' : raw === 'GOTO' ? 'GOTO' : 'NOTIFY'
  const current = selectedNode.value?.onTimeout
  updateSelectedNode({
    onTimeout: {
      action,
      afterHours: current?.afterHours ?? null,
      target: action === 'GOTO' ? current?.target ?? null : null,
      // 加签人留空 = **继承原节点的审批人规则**（后端已支持，也是推荐默认值）。
      // 「显式指定加签人」不在 W2a 的界面范围内，故这里恒写空数组。
      approvers: action === 'ADD_SIGN' ? current?.approvers ?? [] : []
    }
  })
}

/** 超时阈值：空 / 0 / 负数一律写 null（与后端 `afterHours > 0` 的判定同口径） */
function setTimeoutAfterHours(value: unknown): void {
  const current = selectedNode.value?.onTimeout
  const hours = value === null || value === undefined || value === '' ? null : Number(value)
  updateSelectedNode({
    onTimeout: {
      action: current?.action ?? 'NOTIFY',
      afterHours: hours != null && Number.isFinite(hours) && hours > 0 ? hours : null,
      target: current?.target ?? null,
      approvers: current?.approvers ?? []
    }
  })
}

function setTimeoutTarget(value: unknown): void {
  const current = selectedNode.value?.onTimeout
  updateSelectedNode({
    onTimeout: {
      action: current?.action ?? 'NOTIFY',
      afterHours: current?.afterHours ?? null,
      target: value ? String(value) : null,
      approvers: current?.approvers ?? []
    }
  })
}

function addSelectedRule(): void {
  if (selectedKey.value) {
    addRule(selectedKey.value)
  }
}

function removeSelectedRule(ruleIndex: number): void {
  if (selectedKey.value) {
    removeRule(selectedKey.value, ruleIndex)
  }
}

function setRuleType(ruleIndex: number, value: unknown): void {
  if (selectedKey.value) {
    changeRuleType(selectedKey.value, ruleIndex, value)
  }
}

function setRuleRoleCode(ruleIndex: number, value: unknown): void {
  if (selectedKey.value) {
    updateRule(selectedKey.value, ruleIndex, { roleCode: String(value) })
  }
}

function setRuleHandlerGroup(ruleIndex: number, value: unknown): void {
  if (selectedKey.value) {
    updateRule(selectedKey.value, ruleIndex, { handlerGroupId: value == null ? null : Number(value) })
  }
}

function setRuleFieldKey(ruleIndex: number, value: unknown): void {
  if (selectedKey.value) {
    updateRule(selectedKey.value, ruleIndex, { fieldKey: String(value) })
  }
}

/** 「上一节点指定审批人」的人数；空值回落到 1，与后端缺省口径一致 */
function setRuleAssignCount(ruleIndex: number, value: unknown): void {
  if (!selectedKey.value) {
    return
  }
  const count = value == null ? 1 : Number(value)
  updateRule(selectedKey.value, ruleIndex, {
    assignCount: Number.isFinite(count) && count >= 1 ? Math.floor(count) : 1
  })
}

/**
 * 「上一节点指定审批人」的可选范围。
 *
 * 与后端 `AssignScope` 的两种取值一一对应；非法值一律回落 `ALL` ——
 * 这与后端 `AssignScope.of(未知值)` 的行为一致（不限制），
 * 保守方向一致才不会出现"设计器存了 A、服务端读成 B"。
 */
function setRuleAssignScope(ruleIndex: number, value: unknown): void {
  if (!selectedKey.value) {
    return
  }
  const scope: AssignScopeCode = value === 'IT_EXECUTOR' ? 'IT_EXECUTOR' : 'ALL'
  updateRule(selectedKey.value, ruleIndex, { assignScope: scope })
}

function pickUsersForRule(ruleIndex: number): void {
  if (selectedKey.value) {
    openUserPicker(selectedKey.value, ruleIndex)
  }
}

function setChooseScope(ruleIndex: number, value: unknown): void {
  if (!selectedKey.value) {
    return
  }
  const scope: ChooseScopeCode = value === 'ROLE' ? 'ROLE' : value === 'GROUP' ? 'GROUP' : 'ALL'
  updateRule(selectedKey.value, ruleIndex, { scope, scopeValue: null })
}

function setChooseScopeValue(ruleIndex: number, value: unknown): void {
  if (selectedKey.value) {
    updateRule(selectedKey.value, ruleIndex, { scopeValue: String(value) })
  }
}

function setChooseMin(ruleIndex: number, value: unknown): void {
  if (selectedKey.value) {
    updateRule(selectedKey.value, ruleIndex, { minCount: value == null ? 1 : Number(value) })
  }
}

function setChooseMax(ruleIndex: number, value: unknown): void {
  if (selectedKey.value) {
    updateRule(selectedKey.value, ruleIndex, { maxCount: value == null ? 1 : Number(value) })
  }
}

function addSelectedBranch(): void {
  if (selectedKey.value) {
    addBranch(selectedKey.value)
  }
}

function removeSelectedBranch(branchKey: string): void {
  if (selectedKey.value) {
    removeBranch(selectedKey.value, branchKey)
  }
}

function setBranchName(branchKey: string, value: unknown): void {
  if (selectedKey.value) {
    updateBranch(selectedKey.value, branchKey, { name: String(value) })
  }
}

function toggleBranchElse(branchKey: string, value: unknown): void {
  if (selectedKey.value) {
    setElseBranch(selectedKey.value, branchKey, value === true)
  }
}

function setBranchLogic(branchKey: string, value: unknown): void {
  if (selectedKey.value) {
    updateConditionLogic(selectedKey.value, branchKey, value)
  }
}

// 下面这组是模板直连的入口：模板只传 `path`，由脚本层解析出当前选中的条件节点。
// 路径而非下标是 M3-A 的硬要求 —— 嵌套组下同一个下标会同时指向树里多个位置。

function addSelectedConditionRule(branchKey: string, path: ConditionPath): void {
  if (selectedKey.value) {
    appendRule(selectedKey.value, branchKey, path)
  }
}

function addSelectedSubGroup(branchKey: string, path: ConditionPath): void {
  if (selectedKey.value) {
    appendSubGroup(selectedKey.value, branchKey, path)
  }
}

function removeSelectedConditionNode(branchKey: string, path: ConditionPath): void {
  if (selectedKey.value) {
    removeNodeAt(selectedKey.value, branchKey, path)
  }
}

function setSelectedGroupLogic(branchKey: string, path: ConditionPath, value: unknown): void {
  if (selectedKey.value) {
    setGroupLogicAt(selectedKey.value, branchKey, path, value)
  }
}

function setConditionField(branchKey: string, path: ConditionPath, value: unknown): void {
  if (selectedKey.value) {
    patchRuleAt(selectedKey.value, branchKey, path, { field: String(value) })
  }
}

function setConditionOp(branchKey: string, path: ConditionPath, value: unknown): void {
  if (selectedKey.value) {
    changeOperatorAt(selectedKey.value, branchKey, path, value)
  }
}

function setConditionValue(branchKey: string, path: ConditionPath, value: unknown): void {
  if (selectedKey.value) {
    patchRuleAt(selectedKey.value, branchKey, path, { value: String(value) })
  }
}

// ----------------------------------------------------------------------
// 保存 / 发布
// ----------------------------------------------------------------------

const publishProblems = computed(() => validateFlowForPublish(props.modelValue, props.fieldOptions))

/**
 * 本定义是否携带「运行期特性」（M2）：驳回改道 / 超时升级 / 条件引用 process.* 字段。
 *
 * <p>只用于**提示**，不参与本地的通过与否判定 —— 「总开关是否开启」是服务端的环境状态，
 * 前端不镜像它（否则就会出现"前端认为能发、后端拒绝"的第二套真相）。
 * 真正的判定在后端：发布前预检由 `designer.vue` 调 `/approval-flows/validate` 拿到全部问题。
 */
const runtimeFeature = computed(() => flowHasRuntimeFeature(props.modelValue))

function requestSave(): void {
  // 保存草稿刻意宽松：只拦"一旦发布必然失败且改起来不费力"的结构错误
  const blocking = publishProblems.value.filter(
    (problem) => problem.includes('标识不合法') || problem.includes('标识重复') || problem.includes('类型不合法')
  )
  if (blocking.length > 0) {
    ElMessage.warning(blocking[0])
    return
  }
  emit('save')
}

function requestPublish(): void {
  if (publishProblems.value.length > 0) {
    ElMessage.warning(publishProblems.value[0])
    return
  }
  emit('publish')
}

defineExpose({ publishProblems })
</script>

<template>
  <div class="ts-flow-designer" :class="{ 'is-mobile': isMobile }">
    <div v-if="isMobile" class="ts-flow-designer__tabs">
      <el-radio-group v-model="mobilePane" size="small">
        <el-radio-button value="palette">图例</el-radio-button>
        <el-radio-button value="canvas">画布</el-radio-button>
        <el-radio-button value="props">属性</el-radio-button>
      </el-radio-group>
    </div>

    <div class="ts-flow-designer__body">
      <!-- 左：图例 + 参考表单字段 -->
      <aside v-show="!isMobile || mobilePane === 'palette'" class="ts-flow-designer__panel">
        <h4 class="ts-flow-designer__panel-title">节点类型</h4>
        <ul class="ts-flow__legend">
          <li><el-tag size="small" type="primary" effect="plain">审批节点</el-tag> 需要有人审批，可配置多个审批人来源</li>
          <li><el-tag size="small" type="warning" effect="plain">条件分支</el-tag> 按表单字段分流，必须有默认出口</li>
          <li><el-tag size="small" type="info" effect="plain">结束</el-tag> 流程终点</li>
        </ul>

        <template v-if="fieldOptions.length > 0">
          <h4 class="ts-flow-designer__panel-title">参考表单字段</h4>
          <ul class="ts-flow__fields">
            <li v-for="field in fieldOptions" :key="field.value">
              <span class="ts-flow__field-label">{{ field.label }}</span>
              <span class="ts-text-hint">{{ field.value }}</span>
            </li>
          </ul>
        </template>
        <p v-else class="ts-text-hint">
          未选择参考表单时，条件字段可在下拉里直接手输字段 key
          （字段是否存在，将在「申请类型」绑定本流程时由后端强校验）。
        </p>

        <h4 class="ts-flow-designer__panel-title">运行期字段</h4>
        <ul class="ts-flow__fields">
          <li v-for="field in processFieldCandidates" :key="field.value">
            <span class="ts-flow__field-label">{{ field.label }}</span>
            <span class="ts-text-hint">{{ field.value }}</span>
          </li>
        </ul>
        <p class="ts-text-hint">
          这些值在<b>提交时还不存在</b>：引用它们的条件判不了，其下游节点会先落「未激活」，
          等前置节点完成后再自动收敛为「走 / 不走」。
          因此这类条件必须放在<b>至少一个审批节点之后</b>，否则提交后没有任何待办能推动它。
          配置后整个流程需先开启「运行时条件引擎」总开关才能发布。
        </p>
      </aside>

      <!-- 中：树形画布 -->
      <section v-show="!isMobile || mobilePane === 'canvas'" class="ts-flow-designer__panel">
        <div class="ts-flow-designer__canvas-head">
          <span class="ts-flow-designer__canvas-title">流程画布</span>
          <span class="ts-text-hint">{{ nodeList.length }} / 50 个节点</span>
        </div>

        <div v-if="nodeList.length === 0" class="ts-flow__empty">
          <el-empty :image-size="70" description="流程还没有节点">
            <el-button v-if="!readonly" type="primary" :icon="Plus" @click="addNodeAt({ kind: 'start' }, 'APPROVAL')">
              添加第一个审批节点
            </el-button>
          </el-empty>
        </div>

        <div v-else class="ts-flow__canvas">
          <template v-for="(item, index) in canvas.items" :key="`item-${index}`">
            <!-- 节点卡片 -->
            <div
              v-if="item.kind === 'node'"
              class="ts-flow__card"
              :class="{ 'is-active': selectedKey === item.key }"
              :style="{ marginLeft: `${item.depth * 18}px` }"
              @click="pickNode(item.key)"
            >
              <div class="ts-flow__card-main">
                <div class="ts-flow__card-head">
                  <el-tag size="small" effect="plain" :type="nodeTypeTag(item.node)">
                    {{ nodeTypeLabel(item.node.type) }}
                  </el-tag>
                  <span class="ts-flow__card-name">{{ item.node.name || '未命名节点' }}</span>
                  <span class="ts-text-hint">{{ item.key }}</span>
                </div>
                <span class="ts-text-hint ts-flow__card-summary">{{ nodeSummary(item.node) }}</span>
              </div>
              <div v-if="!readonly" class="ts-flow__card-actions">
                <el-dropdown @command="(command) => onInsertCommand(item.key, command)">
                  <el-button link size="small">
                    插入<el-icon class="ts-flow__caret"><ArrowDown /></el-icon>
                  </el-button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item command="APPROVAL">在下方插入审批节点</el-dropdown-item>
                      <el-dropdown-item command="CC">在下方插入抄送节点</el-dropdown-item>
                      <el-dropdown-item command="CONDITION">在下方插入条件分支</el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
                <el-button link type="danger" size="small" @click.stop="confirmRemoveNode(item.key)">
                  <el-icon><Delete /></el-icon>
                </el-button>
              </div>
            </div>

            <!-- 分支头 -->
            <div
              v-else-if="item.kind === 'branch'"
              class="ts-flow__branch"
              :style="{ marginLeft: `${item.depth * 18}px` }"
              @click="selectNodeBranch(item.nodeKey, item.branch.key, isElseBranch(item.branch))"
            >
              <el-tag size="small" effect="plain" :type="isElseBranch(item.branch) ? 'info' : 'warning'">
                {{ isElseBranch(item.branch) ? '默认出口' : `分支 ${item.branchIndex + 1}` }}
              </el-tag>
              <span class="ts-flow__branch-name">{{ item.branch.name || '未命名分支' }}</span>
              <span class="ts-text-hint ts-flow__branch-cond">{{ branchSummary(item.branch) }}</span>
            </div>

            <!-- 汇合引用 -->
            <div v-else-if="item.kind === 'jump'" class="ts-flow__jump" :style="{ marginLeft: `${item.depth * 18}px` }">
              <span class="ts-flow__jump-arrow">↳</span>
              汇合到
              <el-tag size="small" effect="plain">{{ item.name }}</el-tag>
            </div>

            <!-- 断链提示 -->
            <div v-else-if="item.kind === 'missing'" class="ts-flow__missing" :style="{ marginLeft: `${item.depth * 18}px` }">
              指向了不存在的节点：{{ item.text }}
            </div>

            <!-- 挂载点 -->
            <div v-else class="ts-flow__add" :style="{ marginLeft: `${item.depth * 18}px` }">
              <el-dropdown v-if="!readonly" @command="(command) => onAddCommand(item.ref, command)">
                <el-button size="small" plain :icon="Plus">添加节点</el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="APPROVAL">审批节点</el-dropdown-item>
                    <el-dropdown-item command="CC">抄送节点</el-dropdown-item>
                    <el-dropdown-item command="CONDITION">条件分支</el-dropdown-item>
                    <el-dropdown-item command="END">结束节点</el-dropdown-item>
                    <el-dropdown-item command="EXISTING" divided>指向已有节点…</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </template>
        </div>

        <!-- 孤岛节点 -->
        <div v-if="canvas.orphans.length > 0" class="ts-flow__orphans">
          <el-alert
            type="warning"
            :closable="false"
            show-icon
            title="以下节点未接入流程（发布校验会因「不可达」拒绝）"
          />
          <div v-for="node in canvas.orphans" :key="node.key" class="ts-flow__orphan">
            <span>{{ node.name || '未命名节点' }}</span>
            <span class="ts-text-hint">{{ node.key }} · {{ nodeTypeLabel(node.type) }}</span>
            <el-button v-if="!readonly" link type="danger" size="small" @click="confirmRemoveNode(node.key)">
              删除
            </el-button>
          </div>
        </div>
      </section>

      <!-- 右：属性 -->
      <aside v-show="!isMobile || mobilePane === 'props'" class="ts-flow-designer__panel">
        <template v-if="selectedNode">
          <h4 class="ts-flow-designer__props-title">
            节点属性
            <el-tag size="small" effect="plain">{{ nodeTypeLabel(selectedNode.type) }}</el-tag>
          </h4>

          <el-form label-width="86px" size="small" :label-position="isMobile ? 'top' : 'right'">
            <el-form-item label="节点名称" required>
              <el-input
                :model-value="selectedNode.name"
                :disabled="readonly"
                maxlength="64"
                @update:model-value="setSelectedName"
              />
            </el-form-item>
            <el-form-item label="节点标识">
              <el-input :model-value="selectedNode.key" disabled />
              <div class="ts-text-hint">标识由系统生成，用于定位节点，不可修改。</div>
            </el-form-item>

            <!-- 审批节点 / 抄送节点（ 起共用同一套「审批人来源」编辑器） -->
            <template v-if="selectedNode.type === 'APPROVAL' || selectedNode.type === 'CC'">
              <!-- 签署方式只对审批节点有意义：抄送是知会，没有或签/会签的概念 -->
              <el-form-item v-if="selectedNode.type === 'APPROVAL'" label="签署方式">
                <el-radio-group
                  :model-value="selectedNode.signType ?? 'ANY_SIGN'"
                  :disabled="readonly"
                  @update:model-value="setSelectedSignType"
                >
                  <el-radio-button v-for="option in SIGN_TYPE_OPTIONS" :key="option.value" :value="option.value">
                    {{ option.label }}
                  </el-radio-button>
                </el-radio-group>
                <div class="ts-text-hint">
                  {{ SIGN_TYPE_OPTIONS.find((option) => option.value === (selectedNode?.signType ?? 'ANY_SIGN'))?.desc }}
                </div>
              </el-form-item>

              <!-- 审批时限：只对审批节点有意义，抄送/条件/结束都没有"限时"的语义 -->
              <el-form-item v-if="selectedNode.type === 'APPROVAL'" label="审批时限">
                <el-input-number
                  :model-value="selectedNode.timeLimitHours ?? null"
                  :disabled="readonly"
                  :min="1"
                  :max="720"
                  size="small"
                  controls-position="right"
                  placeholder="不限时"
                  @update:model-value="setSelectedTimeLimit"
                />
                <span class="ts-text-hint">小时（留空 = 不限时）</span>
                <div class="ts-text-hint">
                  提交时会把「截止时间」快照到工单节点上；超过后每天提醒当前审批人与申请人各一次，
                  但不会改变工单状态。
                </div>
              </el-form-item>

              <!-- 运行期动作（M2）：驳回处理。默认「终止整单」= 第二期口径 -->
              <el-form-item v-if="selectedNode.type === 'APPROVAL'" label="驳回处理">
                <el-radio-group
                  :model-value="selectedNode.onReject?.action ?? 'TERMINATE'"
                  :disabled="readonly"
                  @update:model-value="setRejectAction"
                >
                  <el-radio-button
                    v-for="option in REJECT_ACTION_OPTIONS"
                    :key="option.value"
                    :value="option.value"
                  >
                    {{ option.label }}
                  </el-radio-button>
                </el-radio-group>
                <div class="ts-text-hint">
                  {{
                    REJECT_ACTION_OPTIONS.find(
                      (option) => option.value === (selectedNode?.onReject?.action ?? 'TERMINATE')
                    )?.desc
                  }}
                </div>
                <el-select
                  v-if="selectedNode.onReject?.action === 'GOTO'"
                  :model-value="selectedNode.onReject?.target ?? null"
                  :disabled="readonly"
                  size="small"
                  class="ts-flow__full"
                  filterable
                  placeholder="选择改道目标节点"
                  @update:model-value="setRejectTarget"
                >
                  <el-option
                    v-for="option in gotoNodeOptions"
                    :key="option.value"
                    :value="option.value"
                    :label="option.label"
                  />
                </el-select>
              </el-form-item>

              <!-- 运行期动作（M2）：超时处理。默认「仅提醒」= 第二期口径 -->
              <el-form-item v-if="selectedNode.type === 'APPROVAL'" label="超时处理">
                <el-radio-group
                  :model-value="selectedNode.onTimeout?.action ?? 'NOTIFY'"
                  :disabled="readonly"
                  @update:model-value="setTimeoutAction"
                >
                  <el-radio-button
                    v-for="option in TIMEOUT_ACTION_OPTIONS"
                    :key="option.value"
                    :value="option.value"
                  >
                    {{ option.label }}
                  </el-radio-button>
                </el-radio-group>
                <div class="ts-text-hint">
                  {{
                    TIMEOUT_ACTION_OPTIONS.find(
                      (option) => option.value === (selectedNode?.onTimeout?.action ?? 'NOTIFY')
                    )?.desc
                  }}
                </div>
                <div
                  v-if="selectedNode.onTimeout?.action === 'ADD_SIGN' || selectedNode.onTimeout?.action === 'GOTO'"
                  class="ts-flow__rule-head"
                >
                  <span class="ts-text-hint">超时阈值</span>
                  <el-input-number
                    :model-value="selectedNode.onTimeout?.afterHours ?? null"
                    :disabled="readonly"
                    :min="1"
                    :max="720"
                    size="small"
                    controls-position="right"
                    placeholder="小时"
                    @update:model-value="setTimeoutAfterHours"
                  />
                  <span class="ts-text-hint">小时（留空则回落本节点审批时限，再没有则用全局阈值）</span>
                </div>
                <el-select
                  v-if="selectedNode.onTimeout?.action === 'GOTO'"
                  :model-value="selectedNode.onTimeout?.target ?? null"
                  :disabled="readonly"
                  size="small"
                  class="ts-flow__full"
                  filterable
                  placeholder="选择改道目标节点"
                  @update:model-value="setTimeoutTarget"
                >
                  <el-option
                    v-for="option in gotoNodeOptions"
                    :key="option.value"
                    :value="option.value"
                    :label="option.label"
                  />
                </el-select>
                <div v-if="selectedNode.onTimeout?.action === 'ADD_SIGN'" class="ts-text-hint">
                  加签人留空 = <b>继承本节点的审批人规则</b>（推荐）。
                  加签后原审批人仍会收到提醒 —— 提醒与动作不互斥。
                </div>
                <div
                  v-if="
                    selectedNode.onTimeout?.action === 'ADD_SIGN' ||
                    selectedNode.onTimeout?.action === 'GOTO' ||
                    selectedNode.onReject?.action === 'GOTO'
                  "
                  class="ts-text-hint"
                >
                  提示：本节点配置了「加签 / 改道」后，整个流程即被视为包含<b>运行期特性</b>，
                  发布前需先在「系统设置 → 系统参数」开启「运行时条件引擎」总开关。
                </div>
              </el-form-item>

              <el-form-item :label="selectedNode.type === 'CC' ? '抄送对象' : '审批人'">
                <div class="ts-flow__rules">
                  <p v-if="selectedNode.type === 'CC'" class="ts-text-hint">
                    抄送在<b>提交时</b>就按下面的来源解析出对象并立即发站内消息（不等流程走到这里），
                    因此不能使用「申请人自选」「上一节点指定」—— 那两类都要等到提交之后才定人。
                    解析不出人时静默跳过（抄送是知会，套超管兜底没有意义）。
                  </p>
                  <div
                    v-for="(rule, ruleIndex) in selectedNode.approverRules ?? []"
                    :key="`rule-${ruleIndex}`"
                    class="ts-flow__rule"
                  >
                    <div class="ts-flow__rule-head">
                      <el-select
                        :model-value="rule.type"
                        :disabled="readonly"
                        size="small"
                        class="ts-flow__rule-type"
                        @change="(v) => setRuleType(ruleIndex, v)"
                      >
                        <el-option
                          v-for="option in ruleOptionsForNode"
                          :key="option.value"
                          :label="option.label"
                          :value="option.value"
                          :disabled="forbiddenRuleTypes.includes(option.value)"
                        />
                      </el-select>
                      <el-button v-if="!readonly" link type="danger" size="small" @click="removeSelectedRule(ruleIndex)">
                        <el-icon><Delete /></el-icon>
                      </el-button>
                    </div>

                    <div class="ts-flow__rule-body">
                      <!-- 指定人员 -->
                      <template v-if="rule.type === 'SPECIFIC_USER'">
                        <div class="ts-flow__chips">
                          <el-tag v-for="id in rule.userIds ?? []" :key="id" size="small" type="info" effect="plain">
                            {{ userNameOf(id) }}
                          </el-tag>
                          <span v-if="(rule.userIds ?? []).length === 0" class="ts-text-hint">尚未选择人员</span>
                        </div>
                        <el-button v-if="!readonly" size="small" plain @click="pickUsersForRule(ruleIndex)">
                          选择人员
                        </el-button>
                      </template>

                      <!-- 指定角色 -->
                      <template v-else-if="rule.type === 'ROLE'">
                        <el-select
                          :model-value="rule.roleCode ?? ''"
                          :disabled="readonly"
                          size="small"
                          class="ts-flow__full"
                          placeholder="选择角色"
                          @update:model-value="(v) => setRuleRoleCode(ruleIndex, v)"
                        >
                          <el-option
                            v-for="role in roleOptions"
                            :key="role.roleCode"
                            :label="role.roleName"
                            :value="role.roleCode"
                          />
                        </el-select>
                      </template>

                      <!-- 部门审批人 -->
                      <template v-else-if="rule.type === 'BIZ_GROUP_APPROVERS'">
                        <p class="ts-text-hint">
                          运行时取<b>申请人所属部门</b>的审批人；申请人未分组或分组无审批人时由超管兜底。
                        </p>
                      </template>

                      <!-- 最终处理部门成员 -->
                      <template v-else-if="rule.type === 'HANDLER_GROUP'">
                        <el-select
                          :model-value="rule.handlerGroupId ?? null"
                          :disabled="readonly"
                          size="small"
                          class="ts-flow__full"
                          placeholder="选择最终处理部门"
                          @update:model-value="(v) => setRuleHandlerGroup(ruleIndex, v)"
                        >
                          <el-option
                            v-for="dept in handlerDepartmentOptions"
                            :key="dept.id"
                            :label="dept.deptName"
                            :value="dept.id"
                          />
                        </el-select>
                      </template>

                      <!-- 表单人员字段 -->
                      <template v-else-if="rule.type === 'FORM_USER_FIELD'">
                        <el-select
                          v-if="userFieldOptions.length > 0"
                          :model-value="rule.fieldKey ?? ''"
                          :disabled="readonly"
                          size="small"
                          class="ts-flow__full"
                          placeholder="选择表单中的人员字段"
                          @update:model-value="(v) => setRuleFieldKey(ruleIndex, v)"
                        >
                          <el-option
                            v-for="field in userFieldOptions"
                            :key="field.value"
                            :label="`${field.label}（${field.value}）`"
                            :value="field.value"
                          />
                        </el-select>
                        <el-input
                          v-else
                          :model-value="rule.fieldKey ?? ''"
                          :disabled="readonly"
                          size="small"
                          placeholder="填写表单中人员选择字段的 key"
                          @update:model-value="(v) => setRuleFieldKey(ruleIndex, v)"
                        />
                      </template>

                      <!-- 申请人直属领导：无参数，只有行为说明 -->
                      <template v-else-if="rule.type === 'LEADER'">
                        <p class="ts-text-hint">
                          运行时取<b>申请人的直属领导</b>（员工管理中维护）。
                          未配置 / 已离职 / 账号停用 / 领导就是本人时，本节点由超级管理员兜底，
                          并分别通知申请人、超管与管理员去维护。
                        </p>
                      </template>

                      <!-- 上一节点指定审批人（； 增加「指定范围」） -->
                      <template v-else-if="rule.type === 'PREV_ASSIGN'">
                        <div class="ts-flow__choose-row">
                          <span class="ts-flow__choose-label">指定人数</span>
                          <el-input-number
                            :model-value="rule.assignCount ?? 1"
                            :disabled="readonly"
                            :min="1"
                            :max="20"
                            size="small"
                            controls-position="right"
                            @update:model-value="(v) => setRuleAssignCount(ruleIndex, v)"
                          />
                          <span class="ts-text-hint">人</span>
                        </div>
                        <div class="ts-flow__choose-row">
                          <span class="ts-flow__choose-label">指定范围</span>
                          <el-select
                            :model-value="rule.assignScope ?? 'ALL'"
                            :disabled="readonly"
                            size="small"
                            @update:model-value="(v) => setRuleAssignScope(ruleIndex, v)"
                          >
                            <el-option
                              v-for="item in ASSIGN_SCOPE_OPTIONS"
                              :key="item.value"
                              :label="item.label"
                              :value="item.value"
                            />
                          </el-select>
                        </div>
                        <p class="ts-text-hint">
                          提交时人员未知，本节点先落「待指定」占位；上一节点点「通过」时<b>必须点名
                          同样数量的人</b>（多一个少一个都会被服务端拒绝）。指定后若该人离职/停用，
                          轮到本节点时会替换为超管兜底。
                        </p>
                        <p class="ts-text-hint">
                          「指定范围」限定上一节点能挑的人，<b>由服务端强制校验</b>
                          ——不配这一项就是「全部在职员工」（存量流程的旧口径）。
                        </p>
                      </template>

                      <!-- 申请人自选 -->
                      <template v-else-if="rule.type === 'APPLICANT_CHOOSE'">
                        <div class="ts-flow__choose-row">
                          <span class="ts-flow__choose-label">可选范围</span>
                          <el-select
                            :model-value="rule.scope ?? 'ALL'"
                            :disabled="readonly"
                            size="small"
                            @update:model-value="(v) => setChooseScope(ruleIndex, v)"
                          >
                            <el-option
                              v-for="option in CHOOSE_SCOPE_OPTIONS"
                              :key="option.value"
                              :label="option.label"
                              :value="option.value"
                            />
                          </el-select>
                        </div>
                        <div v-if="rule.scope === 'ROLE'" class="ts-flow__choose-row">
                          <span class="ts-flow__choose-label">指定角色</span>
                          <el-select
                            :model-value="rule.scopeValue ?? ''"
                            :disabled="readonly"
                            size="small"
                            placeholder="选择角色"
                            @update:model-value="(v) => setChooseScopeValue(ruleIndex, v)"
                          >
                            <el-option
                              v-for="role in roleOptions"
                              :key="role.roleCode"
                              :label="role.roleName"
                              :value="role.roleCode"
                            />
                          </el-select>
                        </div>
                        <div v-if="rule.scope === 'GROUP'" class="ts-flow__choose-row">
                          <span class="ts-flow__choose-label">指定分组</span>
                          <el-select
                            :model-value="rule.scopeValue ?? ''"
                            :disabled="readonly"
                            size="small"
                            placeholder="选择部门"
                            @update:model-value="(v) => setChooseScopeValue(ruleIndex, v)"
                          >
                            <el-option
                              v-for="dept in departmentOptions"
                              :key="dept.id"
                              :label="dept.deptName"
                              :value="String(dept.id)"
                            />
                          </el-select>
                        </div>
                        <div class="ts-flow__choose-row">
                          <span class="ts-flow__choose-label">人数</span>
                          <el-input-number
                            :model-value="rule.minCount ?? 1"
                            :disabled="readonly"
                            :min="1"
                            :max="20"
                            size="small"
                            controls-position="right"
                            @update:model-value="(v) => setChooseMin(ruleIndex, v)"
                          />
                          <span class="ts-text-hint">至</span>
                          <el-input-number
                            :model-value="rule.maxCount ?? 1"
                            :disabled="readonly"
                            :min="1"
                            :max="20"
                            size="small"
                            controls-position="right"
                            @update:model-value="(v) => setChooseMax(ruleIndex, v)"
                          />
                        </div>
                        <p class="ts-text-hint">
                          申请人提交时自行选择；若条件分支绕过了本节点，提交页不会要求选择。
                        </p>
                      </template>
                    </div>
                  </div>
                  <span v-if="(selectedNode.approverRules ?? []).length === 0" class="ts-text-hint">
                    {{ selectedNode.type === 'CC' ? '至少配置一条抄送来源' : '至少配置一条审批人来源' }}
                  </span>
                  <el-button v-if="!readonly" size="small" plain :icon="Plus" @click="addSelectedRule">
                    {{ selectedNode.type === 'CC' ? '添加抄送来源' : '添加审批人来源' }}
                  </el-button>
                </div>
              </el-form-item>

              <el-form-item label="下一节点">
                <span>{{ nodeNameOf(selectedNode.next) || '未指定（发布前必须指定）' }}</span>
              </el-form-item>
            </template>

            <!-- 条件分支 -->
            <template v-else-if="selectedNode.type === 'CONDITION'">
              <el-form-item label="分支">
                <div class="ts-flow__branches">
                  <div
                    v-for="(branch, branchIndex) in selectedNode.branches ?? []"
                    :key="branch.key"
                    class="ts-flow__branch-editor"
                    :class="{ 'is-active': selectedBranchKey === branch.key }"
                    @click="selectedBranchKey = branch.key"
                  >
                    <div class="ts-flow__branch-editor-head">
                      <el-tag size="small" effect="plain" :type="isElseBranch(branch) ? 'info' : 'warning'">
                        分支 {{ branchIndex + 1 }}
                      </el-tag>
                      <el-checkbox
                        :model-value="isElseBranch(branch)"
                        :disabled="readonly"
                        @update:model-value="(v) => toggleBranchElse(branch.key, v)"
                      >
                        默认出口
                      </el-checkbox>
                      <el-button
                        v-if="!readonly"
                        link
                        type="danger"
                        size="small"
                        class="ts-flow__branch-remove"
                        @click="removeSelectedBranch(branch.key)"
                      >
                        <el-icon><Delete /></el-icon>
                      </el-button>
                    </div>

                    <el-input
                      :model-value="branch.name"
                      :disabled="readonly"
                      size="small"
                      maxlength="64"
                      placeholder="分支名称，如「金额大于 5000」"
                      class="ts-flow__branch-name-input"
                      @update:model-value="(v) => setBranchName(branch.key, v)"
                    />

                    <template v-if="!isElseBranch(branch)">
                      <div class="ts-flow__logic">
                        <span class="ts-flow__choose-label">条件关系</span>
                        <el-radio-group
                          :model-value="branchCondition(branch).logic"
                          :disabled="readonly"
                          size="small"
                          @update:model-value="(v) => setBranchLogic(branch.key, v)"
                        >
                          <el-radio-button value="AND">同时满足</el-radio-button>
                          <el-radio-button value="OR">任一满足</el-radio-button>
                        </el-radio-group>
                      </div>

                      <!--
                        条件树按「摊平的行 + 缩进」渲染（M3-A）。
                        嵌套结构本可以用递归组件，但那样每一层都要重新声明一遍字段/运算符/比较值
                        三件套；摊平之后模板里只有**一份**编辑器代码，嵌套只是"多了一行组头"。
                        行的 path 决定改哪一份数据 —— 嵌套下同一个下标会同时指向树里多个位置，
                        所以下标必须换成路径。
                      -->
                      <div
                        v-for="row in conditionRowsOf(branch)"
                        :key="row.key"
                        class="ts-flow__cond-rule"
                        :class="{ 'ts-flow__cond-rule--group': row.group }"
                        :style="{ paddingLeft: (row.depth - 1) * 16 + 'px' }"
                      >
                        <template v-if="row.group">
                          <span class="ts-flow__cond-group-tag">条件组</span>
                          <el-radio-group
                            :model-value="row.logic"
                            :disabled="readonly"
                            size="small"
                            @update:model-value="(v) => setSelectedGroupLogic(branch.key, row.path, v)"
                          >
                            <el-radio-button value="AND">组内同时满足</el-radio-button>
                            <el-radio-button value="OR">组内任一满足</el-radio-button>
                          </el-radio-group>
                          <el-button
                            v-if="!readonly"
                            size="small"
                            plain
                            :icon="Plus"
                            @click="addSelectedConditionRule(branch.key, row.path)"
                          >
                            添加条件
                          </el-button>
                          <el-button
                            v-if="!readonly"
                            size="small"
                            plain
                            :icon="Plus"
                            :disabled="!row.canAddGroup"
                            @click="addSelectedSubGroup(branch.key, row.path)"
                          >
                            添加条件组
                          </el-button>
                          <el-button
                            v-if="!readonly"
                            link
                            type="danger"
                            size="small"
                            @click="removeSelectedConditionNode(branch.key, row.path)"
                          >
                            <el-icon><Delete /></el-icon>
                          </el-button>
                          <!-- 上限提示直接显示而不是塞进 tooltip：
                               "为什么按钮是灰的"必须是可读的一句话，而不是要用户悬停去猜 -->
                          <span v-if="!row.canAddGroup" class="ts-text-hint ts-flow__cond-limit">
                            {{ row.addGroupHint }}
                          </span>
                        </template>

                        <template v-else>
                          <el-select
                            :model-value="row.field"
                            :disabled="readonly"
                            size="small"
                            filterable
                            allow-create
                            default-first-option
                            placeholder="字段 / 手输 key"
                            class="ts-flow__cond-field"
                            @update:model-value="(v) => setConditionField(branch.key, row.path, v)"
                          >
                            <el-option-group v-if="formFieldOptions.length > 0" label="表单字段">
                              <el-option
                                v-for="field in formFieldOptions"
                                :key="field.value"
                                :label="field.label"
                                :value="field.value"
                              />
                            </el-option-group>
                            <el-option-group label="运行期字段（审批过程中才产生）">
                              <el-option
                                v-for="field in processFieldCandidates"
                                :key="field.value"
                                :label="field.label"
                                :value="field.value"
                              />
                            </el-option-group>
                          </el-select>
                          <el-select
                            :model-value="row.op"
                            :disabled="readonly"
                            size="small"
                            class="ts-flow__cond-op"
                            @update:model-value="(v) => setConditionOp(branch.key, row.path, v)"
                          >
                            <el-option
                              v-for="operator in FLOW_OPERATOR_OPTIONS"
                              :key="operator.value"
                              :label="operator.label"
                              :value="operator.value"
                            />
                          </el-select>
                          <!-- 枚举类字段（运行期字段）用下拉选值，避免把 APPROVED 打成 APPROVE
                               这类"不报错、但运行期永不命中"的静默错配 -->
                          <el-select
                            v-if="row.requiresValue && row.valueOptions.length > 0"
                            :model-value="row.value"
                            :disabled="readonly"
                            size="small"
                            placeholder="比较值"
                            class="ts-flow__cond-value"
                            @update:model-value="(v) => setConditionValue(branch.key, row.path, v)"
                          >
                            <el-option
                              v-for="option in row.valueOptions"
                              :key="option.value"
                              :label="option.label"
                              :value="option.value"
                            />
                          </el-select>
                          <el-input
                            v-else-if="row.requiresValue"
                            :model-value="row.value"
                            :disabled="readonly"
                            size="small"
                            placeholder="比较值"
                            class="ts-flow__cond-value"
                            @update:model-value="(v) => setConditionValue(branch.key, row.path, v)"
                          />
                          <el-button
                            v-if="!readonly"
                            link
                            type="danger"
                            size="small"
                            @click="removeSelectedConditionNode(branch.key, row.path)"
                          >
                            <el-icon><Delete /></el-icon>
                          </el-button>
                        </template>
                      </div>

                      <div v-if="!readonly" class="ts-flow__cond-actions">
                        <el-button
                          size="small"
                          plain
                          :icon="Plus"
                          @click="addSelectedConditionRule(branch.key, [])"
                        >
                          添加条件
                        </el-button>
                        <el-button
                          size="small"
                          plain
                          :icon="Plus"
                          :disabled="!canAppendSubGroup(branchCondition(branch), [])"
                          @click="addSelectedSubGroup(branch.key, [])"
                        >
                          添加条件组
                        </el-button>
                        <span
                          v-if="!canAppendSubGroup(branchCondition(branch), [])"
                          class="ts-text-hint ts-flow__cond-limit"
                        >
                          {{ subGroupHint(branchCondition(branch), []) }}
                        </span>
                      </div>
                    </template>
                    <p v-else class="ts-text-hint">无需条件：以上分支都不满足时走这里。</p>

                    <p class="ts-text-hint ts-flow__branch-target">
                      去向：{{ nodeNameOf(branch.next) || '未指定（发布前必须指定）' }}
                    </p>
                  </div>

                  <el-button v-if="!readonly" size="small" plain :icon="Plus" @click="addSelectedBranch">
                    添加分支
                  </el-button>
                </div>
              </el-form-item>
            </template>

            <!-- 结束 -->
            <template v-else>
              <p class="ts-text-hint">流程终点，无需配置。所有分支都必须（直接或间接）到达某个结束节点。</p>
            </template>
          </el-form>
        </template>

        <el-empty v-else :image-size="60" description="在画布中选中一个节点以编辑属性" />
      </aside>
    </div>

    <!-- 底部操作 -->
    <div class="ts-flow-designer__footer">
      <span class="ts-text-hint">
        <template v-if="readonly">只读模式：你没有审批流程的维护权限（approval_flow:manage）</template>
        <template v-else-if="publishProblems.length > 0">发布前需完善：{{ publishProblems[0] }}</template>
        <template v-else-if="runtimeFeature">
          流程校验通过，但本流程包含<b>运行期条件</b>（驳回改道 / 超时升级 / 引用上一节点结果等）：
          需先在「系统设置 → 系统参数」开启「运行时条件引擎」总开关，否则发布会被拒绝
        </template>
        <template v-else>流程校验通过，可以保存草稿或直接发布</template>
      </span>
      <div class="ts-flow-designer__footer-actions">
        <el-button :disabled="readonly" :loading="saving" @click="requestSave">保存草稿</el-button>
        <el-button
          type="primary"
          :disabled="readonly || publishProblems.length > 0"
          :loading="saving"
          @click="requestPublish"
        >
          <el-icon><Promotion /></el-icon>
          发布版本
        </el-button>
      </div>
    </div>

    <!-- 人员选择 -->
    <UserSelectDialog v-model="userPickerVisible" title="选择审批人" multiple @confirm="onUsersPicked" />

    <!-- 指向已有节点（分支汇合） -->
    <el-dialog v-model="existingDialogVisible" title="指向已有节点" :width="isMobile ? '94%' : '480px'">
      <p class="ts-text-hint">
        选择后，当前挂载点将直接连到该节点 —— 这样多条分支可以汇合到同一个后续节点。
      </p>
      <el-select v-model="existingPick" class="ts-flow__full" filterable placeholder="选择一个节点">
        <el-option
          v-for="candidate in existingCandidates"
          :key="candidate.key"
          :label="`${candidate.name}（${candidate.key} · ${nodeTypeLabel(candidate.type)}）`"
          :value="candidate.key"
        />
      </el-select>
      <template #footer>
        <el-button @click="existingDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="chooseExisting">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.ts-flow-designer {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.ts-flow-designer__body {
  display: grid;
  grid-template-columns: 220px minmax(0, 1fr) 360px;
  gap: 12px;
  align-items: start;
}

.ts-flow-designer__panel {
  padding: 12px;
  border: 1px solid var(--ts-border);
  border-radius: 8px;
  background: #fff;
}

.ts-flow-designer__panel-title {
  margin: 0 0 8px;
  font-size: 13px;
  font-weight: 500;
}

.ts-flow-designer__canvas-head {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
}

.ts-flow-designer__canvas-title {
  font-size: 14px;
  font-weight: 500;
}

.ts-flow-designer__props-title {
  display: flex;
  gap: 8px;
  align-items: center;
  margin: 0 0 12px;
  font-size: 14px;
  font-weight: 500;
}

.ts-flow__legend,
.ts-flow__fields {
  padding: 0;
  margin: 0;
  list-style: none;
}

.ts-flow__legend li {
  margin-bottom: 8px;
  font-size: 12px;
  line-height: 1.6;
}

.ts-flow__fields li {
  display: flex;
  flex-direction: column;
  padding: 4px 0;
  font-size: 12px;
  border-bottom: 1px dashed var(--ts-border);
}

.ts-flow__field-label {
  font-weight: 500;
}

.ts-flow__canvas {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-height: 120px;
}

.ts-flow__empty {
  display: flex;
  justify-content: center;
}

.ts-flow__card {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 8px 10px;
  border: 1px solid var(--ts-border);
  border-left: 3px solid var(--el-color-primary);
  border-radius: 6px;
  background: #fbfcfe;
  cursor: pointer;
}

.ts-flow__card.is-active {
  border-color: var(--el-color-primary);
  box-shadow: 0 0 0 1px var(--el-color-primary) inset;
}

.ts-flow__card-main {
  display: flex;
  flex: 1 1 auto;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}

.ts-flow__card-head {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}

.ts-flow__card-name {
  font-size: 13px;
  font-weight: 500;
}

.ts-flow__card-summary {
  font-size: 12px;
}

.ts-flow__card-actions {
  display: flex;
  flex: 0 0 auto;
  gap: 2px;
  align-items: center;
}

.ts-flow__caret {
  margin-left: 2px;
}

.ts-flow__branch {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  padding: 5px 8px;
  font-size: 12px;
  border-left: 2px dashed var(--el-color-warning);
  cursor: pointer;
}

.ts-flow__branch-name {
  font-weight: 500;
}

.ts-flow__branch-cond {
  font-size: 12px;
}

.ts-flow__jump {
  display: flex;
  gap: 6px;
  align-items: center;
  padding: 4px 8px;
  font-size: 12px;
  color: var(--ts-text-secondary, #909399);
}

.ts-flow__jump-arrow {
  color: var(--el-color-primary);
}

.ts-flow__missing {
  padding: 4px 8px;
  font-size: 12px;
  color: var(--el-color-danger);
}

.ts-flow__add {
  padding: 2px 0 2px 8px;
}

.ts-flow__orphans {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px dashed var(--ts-border);
}

.ts-flow__orphan {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 6px 0;
  font-size: 12px;
}

.ts-flow__rules,
.ts-flow__branches {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}

.ts-flow__rule,
.ts-flow__branch-editor {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 8px;
  border: 1px solid var(--ts-border);
  border-radius: 6px;
  background: #fbfcfe;
}

.ts-flow__branch-editor.is-active {
  border-color: var(--el-color-primary);
}

.ts-flow__rule-head {
  display: flex;
  gap: 6px;
  align-items: center;
}

.ts-flow__rule-type {
  flex: 1 1 auto;
}

.ts-flow__rule-body {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.ts-flow__chips {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.ts-flow__choose-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}

.ts-flow__choose-label {
  font-size: 12px;
  color: var(--ts-text-secondary, #909399);
}

.ts-flow__branch-editor-head {
  display: flex;
  gap: 8px;
  align-items: center;
}

.ts-flow__branch-remove {
  margin-left: auto;
}

.ts-flow__branch-name-input {
  width: 100%;
}

.ts-flow__logic {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}

.ts-flow__cond-rule {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  align-items: center;
}

.ts-flow__cond-field {
  flex: 1 1 110px;
  min-width: 90px;
}

.ts-flow__cond-op {
  flex: 1 1 90px;
  min-width: 80px;
}

.ts-flow__cond-value {
  flex: 1 1 90px;
  min-width: 70px;
}

/* 嵌套条件组：组头整行用主色左侧竖线 + 轻底色，缩进由行内 paddingLeft 提供
   （缩进量必须跟着数据里的层级走，所以只能在模板里算，不能写死在这里） */
.ts-flow__cond-rule--group {
  border-left: 3px solid var(--el-color-primary);
  background: var(--el-fill-color-lighter);
  border-radius: 2px;
  padding-top: 4px;
  padding-bottom: 4px;
}

.ts-flow__cond-group-tag {
  font-size: 12px;
  font-weight: 600;
  color: var(--el-color-primary);
  white-space: nowrap;
}

.ts-flow__cond-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  align-items: center;
  margin-top: 4px;
}

.ts-flow__cond-limit {
  margin: 0;
  color: var(--el-color-warning);
}

.ts-flow__branch-target {
  margin: 2px 0 0;
}

.ts-flow__full {
  width: 100%;
}

.ts-flow-designer__footer {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  border: 1px solid var(--ts-border);
  border-radius: 8px;
  background: #fbfcfe;
}

.ts-flow-designer__footer-actions {
  display: flex;
  gap: 8px;
}

.ts-flow-designer__tabs {
  display: flex;
  justify-content: center;
}

@media (max-width: 1279px) {
  .ts-flow-designer__body {
    grid-template-columns: 180px minmax(0, 1fr) 320px;
  }
}

@media (max-width: 1023px) {
  .ts-flow-designer__body {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
