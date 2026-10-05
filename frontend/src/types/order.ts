/**
 * 借用工单类型（ /  / ，需求方  的两种工单类型 +  归还与顺延 +  延期 +  转交与催办 +  自定义申请）
 */

import type { FlowNodeTypeCode } from '@/types/approvalFlow'
import type { FormData, FormSchema } from '@/types/form'
import type { UserOption } from '@/types/user'

/** 工单状态码（；BORROWED 保留原始编码，对外展示文案为「使用中」） */
export type OrderStatusCode =
  | 'PENDING_APPROVAL'
  | 'PENDING_DELIVERY'
  | 'BORROWED'
  | 'PENDING_RETURN'
  | 'RETURNED'
  | 'REJECTED'
  | 'CANCELLED'
  /** 已终止（超管强制终止后的终态，区别于 CANCELLED「已撤回」） */
  | 'TERMINATED'
  /**
   * 已完成：自定义申请的终态。
   *
   * 自定义申请无「交付」环节，审批通过（或无审批模式提交）即进入已完成；
   * 与借用单的 RETURNED（已归还）语义不同，故不复用。
   */
  | 'COMPLETED'

/** 借用类型（需求方 ）：短期借用 / 长期领用 */
export type OrderUseType = 'SHORT_TERM' | 'LONG_TERM'

/**
 * 借用类型默认值。
 *
 * <p>需求文档「三·」要求把「借用类型」从员工表单上拿掉，员工只填三项。
 * 前端因此不再有类型选择器，**一律按短期借用提交**；后端 `UseType` 语义
 * （长期领用不催还 / 不支持延期）与存量工单的 `use_type` 值原样保留，不需要数据迁移。
 *
 * <p><b>为什么仍显式回传，而不是省略让服务端兜底：</b>
 * 服务端确实把「不传」解释为 SHORT_TERM，但 `useType` 参与流程条件求值
 * （`borrow.useType`）。显式传值让「这笔单被当成什么类型」写在请求里、可被日志与
 * 回归脚本直接断言，而不是靠读服务端源码才能知道。
 */
export const DEFAULT_USE_TYPE: OrderUseType = 'SHORT_TERM'

/** 工单类型（； 追加 CUSTOM 自定义申请） */
export type OrderTypeCode = 'BORROW' | 'RETURN' | 'REPAIR' | 'EXCHANGE' | 'CUSTOM'

/** 归还触发来源（， / ） */
export type OrderReturnTrigger = 'USER_INITIATED' | 'DIMISSION' | 'ADMIN_FORCE'

/**
 * 归还检查结果（ 引入，P0 改为四值结构化检查）
 *
 * <ul>
 *   <li>{@code GOOD} 完好 —— 设备回「可用」</li>
 *   <li>{@code DAMAGED} 损坏 —— 设备进「维修中」，并生成故障记录</li>
 *   <li>{@code MISSING_PARTS} 缺配件 —— 设备主体回「可用」（配件缺失不是设备故障），并通知管理员追回</li>
 *   <li>{@code LOST} 丢失 —— 设备置为「已丢失」（不可再被申请），并通知管理员查找</li>
 * </ul>
 *
 * ⚠️ 旧值 {@code MINOR_DAMAGE} / {@code FAULT} 已由后端 V39 迁移掉
 * （FAULT → DAMAGED、MINOR_DAMAGE → GOOD），此处不要再出现。
 */
export type ReturnConditionCode = 'GOOD' | 'DAMAGED' | 'MISSING_PARTS' | 'LOST'

/** 延期子工单状态（，） */
export type ExtendStatusCode = 'PENDING_APPROVAL' | 'APPROVED' | 'REJECTED'

/**
 * 审批快照节点状态（； 追加 `CC_NOTIFIED` / `INACTIVE`）
 *
 * <ul>
 *   <li>{@link 'CC_NOTIFIED'}：抄送节点的终态。抄送不是审批，但它同样落一行快照，
 *       状态就是这个值；它**不阻塞**流程推进（服务端视其为已完成）。</li>
 *   <li>{@link 'INACTIVE'}：未激活。条件引用了「审批过程中才产生」
 *       的运行期字段（上一节点结果 / 已耗时等）时，其下游节点在提交时先落此状态，
 *       等前置节点完成后再由服务端重算收敛为 PENDING 或 SKIPPED。
 *       它与 `PENDING` 一样**不算完成**，因此不会被误判为「流程已走完」。</li>
 * </ul>
 */
export type ApprovalNodeStatusCode =
  | 'PENDING'
  | 'APPROVED'
  | 'REJECTED'
  | 'SKIPPED'
  | 'CANCELLED'
  | 'CC_NOTIFIED'
  | 'INACTIVE'

/** 签署模式 */
export type SignTypeCode = 'ALL_SIGN' | 'ANY_SIGN'

/** 转交类型（， + 需求方） */
export type TransferTypeCode = 'MANUAL' | 'AUTO_DIMISSION' | 'FORCE_ADMIN'

/** 催办类型（，需求方） */
export type UrgeTypeCode = 'APPROVAL' | 'RETURN'

/**
 * 工单列表项
 *
 * 后端全局 `default-property-inclusion: non_null`：值为 null 的字段整条省略，
 * 因此可空字段一律声明为可选（与 types/device.ts 保持一致的处理方式）。
 */
export interface OrderItem {
  id: number
  orderNo: string
  /**
   * 申请设备 id。
   *
   *  起自定义申请（orderType=CUSTOM）不占用设备，该字段为 null ——
   * 后端用 NULL 表达「不适用」，所有既有 JOIN 设备 的统计会自动排除这类工单。
   */
  deviceId?: number | null
  /** "设备名（资产编号）"；自定义申请为 null */
  deviceName?: string | null
  applicantId?: number | null
  applicantName?: string | null
  departmentName?: string | null
  /** 借用类型；自定义申请为 null */
  useType?: OrderUseType | null
  useTypeLabel?: string | null
  /** 用途（原「借用原因」）；自定义申请为 null（内容在表单数据里） */
  reason?: string | null
  /** 期望归还日期；仅短期借用有值 */
  expectedReturnDate?: string | null
  status: OrderStatusCode
  statusLabel: string
  /**
   * 最终处理部门名称。
   *
   * 字段名沿用后端 `OrderVO` 的既有契约（改它要动快照与导出），
   * 但语义已经是**部门名** ——  起「最终处理小组」并入部门体系。
   */
  handlerGroupName?: string | null
  actualFinalHandlerId?: number | null
  actualFinalHandlerName?: string | null
  plannedEndTime?: string | null
  deliveredAt?: string | null
  createdAt: string
  updatedAt: string
  /** 待我处理的步骤序号（审批待办列表有值） */
  currentStepOrder?: number | null
  /** 当前步骤的审批人姓名（「全部工单」全局视图展示「第几步 · 由谁审批」） */
  currentApproverName?: string | null
  /** 当前步骤是否允许「我」操作 */
  actionable?: boolean | null

  // ------------------------------------------------------------------
  // ：归还与顺延（ /  /  / ）
  // ------------------------------------------------------------------

  /** 工单类型（ 起可能是 CUSTOM 自定义申请） */
  orderType?: OrderTypeCode | null
  orderTypeLabel?: string | null
  /**
   * 自定义申请类型 id；非自定义工单为 null。
   *
   * 该字段有值即代表这是一笔自定义申请，列表据此展示「申请类型」列与筛选。
   */
  applyTypeId?: number | null
  /** 自定义申请类型名称（列表展示；非自定义工单为 null） */
  applyTypeName?: string | null
  /** 归还触发来源：申请人发起 / 离职联动 / 管理员强制 */
  returnTrigger?: OrderReturnTrigger | null
  returnTriggerLabel?: string | null
  /** 申请人归还说明（第一步，可选） */
  returnNote?: string | null
  /** 收回时登记的设备状态 */
  returnCondition?: ReturnConditionCode | null
  returnConditionLabel?: string | null
  /** 收回备注（执行人填写） */
  returnRemark?: string | null
  returnedById?: number | null
  /** 实际收回人姓名 */
  returnedByName?: string | null
  /** 实际归还时间 */
  actualEndTime?: string | null
  /** 是否借用超时（标记位，不改变主状态） */
  borrowTimeout?: boolean | null
  /**
   * 当前**审批步骤**是否已超过约定审批时限。
   *
   * 与 {@link borrowTimeout} 刻意分开：后者是「设备该还了还没还」，
   * 前者是「这一步审批卡太久」。两者可以同时为真，含义完全不同，
   * 合成一个字段会让前端无法区分该提示哪件事。
   */
  approvalOverdue?: boolean | null
  /** 已超过审批时限的小时数（未超时为空） */
  approvalOverdueHours?: number | null
  /** 已自动顺延次数（最多 2 次） */
  autoExtendCount?: number | null
  lastTimeoutAlertAt?: string | null
  /** 当前登录者能否「发起归还」（服务端判定，前端只渲染） */
  canRequestReturn?: boolean | null
  /** 当前登录者能否「确认收回」（服务端判定，前端只渲染） */
  canConfirmReturn?: boolean | null

  // ------------------------------------------------------------------
  // ：借用延期
  // ------------------------------------------------------------------

  /** 已发起的延期次数（不含被驳回的） */
  extendUsedCount?: number | null
  /** 延期次数上限（可配置，默认 2） */
  extendMaxCount?: number | null
  /** 是否存在审批中的延期申请 */
  extendPending?: boolean | null
  /** 当前登录者能否「申请延期」（服务端判定，前端只渲染） */
  canRequestExtend?: boolean | null

  // ------------------------------------------------------------------
  // ：工单转交 + 催办（ + 需求方   / 二）
  // ------------------------------------------------------------------

  /** 该工单是否发生过转交（列表「已转交」标记） */
  transferred?: boolean | null
  /** 转交次数（transferred 为 true 时 ≥ 1） */
  transferCount?: number | null
  /**
   * 当前登录者能否「转交」（服务端判定）。
   *
   * 服务端条件：可转交状态 && 已分配执行人 &&（本人是执行人 或 super_admin）。
   * 注意：该字段为 false 不等于「一定不能转」——只是服务端此刻认定不可转，
   * 前端一律以它为准渲染按钮，避免权限规则在前端再实现一遍而漂移。
   */
  canTransfer?: boolean | null
  /** 当前登录者能否「催办审批」（服务端判定：本人是申请人 && 审批中 && 有当前节点 && 不在冷却期） */
  canUrgeApproval?: boolean | null
  /** 审批催办冷却剩余秒数；0 表示可立即催办 */
  approvalUrgeCooldownSeconds?: number | null
  /** 当前登录者能否「催还」（服务端判定：本人是执行人或超管 && 使用中 && 已到期/超时 && 不在冷却期） */
  canUrgeReturn?: boolean | null
  /** 归还催办冷却剩余秒数；0 表示可立即催还 */
  returnUrgeCooldownSeconds?: number | null
  /** 当前登录者能否「强制操作」（服务端判定：super_admin 且工单非终态，前端只渲染按钮） */
  canForceOperate?: boolean | null
}

/** 审批快照节点 */
export interface ApprovalNode {
  id: number
  stepOrder: number
  /**
   * 审批人 user_id。
   *
   * <p> 起可为空：FLOW 模式下「条件分支未命中」的节点以 SKIPPED 落库，
   * 它从未指派过审批人 —— 用 null 表达，而不是伪造一个「系统」账号。
   */
  approverId?: number | null
  approverName?: string | null
  signType: SignTypeCode
  signTypeLabel: string
  status: ApprovalNodeStatusCode
  statusLabel: string
  actionTime?: string | null
  actionComment?: string | null
  /** 流程节点稳定标识（仅 FLOW 模式有值，） */
  nodeKey?: string | null
  /** 流程节点名（仅 FLOW 模式有值；有值时用它代替「第 N 步」展示） */
  nodeName?: string | null
  /** 分支说明：为何走到 / 为何跳过本节点（仅 FLOW 模式有值） */
  conditionDesc?: string | null
  /**
   * 节点类型：`APPROVAL` / `CC`。
   *
   * 借用单与 GROUP 自定义单为空（它们的快照节点没有「类型」这个概念）——
   * 因此判空即代表「非 FLOW 单」，前端展示时不要把空值当成异常。
   */
  nodeType?: FlowNodeTypeCode | null
  /** 节点类型中文标签（审批 / 抄送），服务端下发 */
  nodeTypeLabel?: string | null
  /** 审批时限截止时间快照（；null = 不限时） */
  deadlineAt?: string | null
  /**
   * 是否已超过审批时限（仅 PENDING 节点有意义）。
   *
   * 由**服务端**计算而非前端拿 `deadlineAt` 与本地时间比较：本地时钟可能不准，
   * 而「是否超时」是一个业务判定，理应在服务端有唯一定论。
   */
  overdue?: boolean | null
  /** 已超时小时数（未超时为空），供展示「已超时 3 小时」 */
  overdueHours?: number | null
  /**
   * 是否「待上一节点指定审批人」：
   * 状态为 PENDING 且审批人为空（PREV_ASSIGN 的占位节点）。
   *
   * 审批弹窗据此判断「下一步是否需要我先点名」。
   */
  pendingAssign?: boolean | null
  /** 该节点审批人由谁指定（指定者的展示名） */
  assignedByName?: string | null
  /** 该节点是否为「由上一节点指定审批人」产生的节点 */
  assignedByPrev?: boolean | null
  /**
   * 待指派节点的「可选范围」代码：`ALL` / `IT_EXECUTOR`。
   *
   * 仅 `pendingAssign === true` 时有值。与 {@link assignScopeLabel} 一起下发，
   * 让选择器展示的候选与服务端肯收的人一致 —— 否则用户选完才被拒，
   * 而"为什么这个人不行"在选择阶段无从得知。
   */
  assignScope?: string | null
  /** 可选范围的中文说明（服务端下发，与详情同源，避免两套说法） */
  assignScopeLabel?: string | null
  /** 是否 super_admin 兜底审批节点 */
  superBackup: boolean
  /** 是否因原审批人离职/禁用替换为兜底 */
  fallback: boolean
  /**
   * 该节点被「激活」的时刻。
   *
   * <p><b>不要把它当作「经历过未激活」的判据</b>：提交时就已确定的节点同样会写这个值
   * （服务端把「提交即物化」与「运行期激活」统一走同一套物化逻辑），因此它近似于创建时间。
   * 真正表示「曾经未激活」的是 `status === 'INACTIVE'` 与 {@link runtimeReason}。
   */
  activatedAt?: string | null
  /**
   * 运行期激活 / 未激活的原因说明。
   *
   * <p>典型文案如「等待前置节点结果」「前置节点通过，按条件激活」——
   * 它是排查「这个节点为什么没轮到我」的唯一线索，也是激活史卡片的主文案。
   */
  runtimeReason?: string | null
}

/**
 * 「上一节点指定审批人」的候选项与原因说明。
 *
 * 审批弹窗在「通过」时调用 `orderApi.assignCandidates(orderId)` 得到本对象，
 * 一次拿到「要指定几个人 / 能从哪些人里指 / 为什么只有这些人」。
 *
 * 改造前这三件事是拼出来的：人数来自详情节点、候选来自 `userApi.options()`
 * ——而后者挂 `staff:view`，普通审批人（直属主管 / IT主管）请求 403，
 * 候选被前端 catch 吞成空数组，「必须指定才能通过」于是成了无法满足的前置条件。
 */
export interface AssignCandidate {
  /** 需要指定的人数；0 = 本单当前步骤之后没有待指派节点，选择器应整体隐藏 */
  requiredCount: number
  /** 待指派节点的流程 key（无待指派时为 null） */
  nodeKey?: string | null
  /** 待指派节点名称（如「IT执行人处理」） */
  nodeName?: string | null
  /** 待指派节点所在步骤号 */
  stepOrder?: number | null
  /** 可选范围代码：`ALL` / `IT_EXECUTOR` */
  assignScope?: string | null
  /** 可选范围的中文说明（服务端下发，直接用于提示） */
  assignScopeLabel?: string | null
  /** 是否限定了范围；false 表示「全部在职员工」 */
  restricted: boolean
  /** 候选人员（在职启用，服务端已排除申请人本人） */
  candidates: UserOption[]
}

/** 工单详情（含审批快照链路） */
export interface OrderDetail extends OrderItem {
  nodes: ApprovalNode[]
  /** 当前登录者是否为本工单申请人 */
  applicantSelf: boolean
  /** 当前登录者是否为本工单实际执行人 */
  handlerSelf: boolean
  /**
   * 当前登录者是否为本工单的**抄送人**。
   *
   * 抄送人是「可只读查看」的旁路角色：能看到完整详情与附件，但不能执行任何写操作。
   * 前端据此渲染只读提示并隐藏全部操作按钮 —— 后端才是事实源（写接口对抄送人自然拒绝），
   * 这里只是提前不给入口，避免用户点了才发现被拒。
   */
  ccSelf?: boolean | null
  /** 转交历史（，按时间升序） */
  transfers?: OrderTransferItem[]
  /** 催办历史（，按时间升序） */
  urges?: OrderUrgeItem[]
  /** 强制干预记录（超管强制驳回 / 终止 / 转交，按时间升序） */
  forceOperations?: OrderForceOperationItem[]
}

/**
 * 延期子工单（，）
 *
 * 用于工单详情「延期时间线」与「延期审批」待办列表。
 */
export interface OrderExtendItem {
  id: number
  orderId: number
  orderNo?: string | null
  applicantId?: number | null
  applicantName?: string | null
  /** 发起时的原计划结束时间（审批人据此对比「原定 → 新定」） */
  originalEndTime?: string | null
  /** 申请延长到的新结束时间 */
  newEndTime: string
  reason: string
  status: ExtendStatusCode
  statusLabel: string
  actionTime?: string | null
  actionComment?: string | null
  createdAt: string
  /** 延期审批链路快照 */
  nodes: ApprovalNode[]
}

/**
 * 工单转交记录（，）
 *
 * 用于工单详情「转交时间线」与「全部工单」列表的「已转交」标记。
 */
export interface OrderTransferItem {
  id: number
  orderId: number
  orderNo?: string | null
  oldHandlerId?: number | null
  oldHandlerName?: string | null
  newHandlerId?: number | null
  newHandlerName?: string | null
  transferOperatorId?: number | null
  transferOperatorName?: string | null
  /** 转交原因 / 备注 */
  transferComment?: string | null
  transferType?: TransferTypeCode | null
  transferTypeLabel?: string | null
  createdAt: string
}

/**
 * 工单催办记录（，需求方）
 *
 * 用于工单详情「催办时间线」。催办不改变工单状态，仅留痕 + 发消息。
 */
export interface OrderUrgeItem {
  id: number
  orderId: number
  orderNo?: string | null
  urgeType?: UrgeTypeCode | null
  urgeTypeLabel?: string | null
  /** 审批催办对应的审批节点 id（归还催办为 null） */
  nodeId?: number | null
  /** 该节点所属步骤序号（归还催办为 null） */
  nodeStepOrder?: number | null
  /** 被催办人（审批催办=审批人；归还催办=借用人） */
  targetUserId?: number | null
  targetUserName?: string | null
  /** 发起催办的人 */
  operatorId?: number | null
  operatorName?: string | null
  createdAt: string
}

/**
 * 强制干预操作类型（超管强制操作，仅 super_admin）
 *
 * 不做「强制通过」：规范与需求均未赋予超管绕过审批直接放行的能力，
 * 强制操作仅用于驳回 / 终止 / 改派（转交），避免权力边界被无限放大。
 */
export type ForceOperationTypeCode =
  | 'FORCE_REJECT'
  | 'FORCE_TERMINATE'
  | 'FORCE_TRANSFER_APPROVAL'
  | 'FORCE_TRANSFER_HANDLER'

/** 强制干预记录项（工单详情「强制干预记录」，按时间升序） */
export interface OrderForceOperationItem {
  id: number
  orderId: number
  /** 操作类型编码（FORCE_REJECT / FORCE_TERMINATE / FORCE_TRANSFER_APPROVAL / FORCE_TRANSFER_HANDLER） */
  operationType: ForceOperationTypeCode
  /** 操作类型中文文案 */
  operationTypeLabel: string
  operatorId: number
  operatorName?: string | null
  /** 强制原因（必填，后端缺失会返回 FORCE_REASON_REQUIRED） */
  reason: string
  oldStatus?: OrderStatusCode | null
  oldStatusLabel?: string | null
  newStatus?: OrderStatusCode | null
  newStatusLabel?: string | null
  oldApproverId?: number | null
  oldApproverName?: string | null
  newApproverId?: number | null
  newApproverName?: string | null
  oldHandlerId?: number | null
  oldHandlerName?: string | null
  newHandlerId?: number | null
  newHandlerName?: string | null
  createdAt: string
}

/** 强制操作提交载荷（POST /orders/{id}/force） */
export interface OrderForcePayload {
  operationType: ForceOperationTypeCode
  /** 强制原因，必填 */
  reason: string
  /** 强制转交审批时必填 */
  targetApproverId?: number | null
  /** 强制转交执行人时必填（可跨组） */
  targetHandlerId?: number | null
}

/** 强制干预操作选项（弹窗下拉，按工单状态动态可用子集） */
export const FORCE_OPERATION_OPTIONS: Array<{ value: ForceOperationTypeCode; label: string }> = [
  { value: 'FORCE_REJECT', label: '强制驳回' },
  { value: 'FORCE_TERMINATE', label: '强制终止' },
  { value: 'FORCE_TRANSFER_APPROVAL', label: '强制转交审批' },
  { value: 'FORCE_TRANSFER_HANDLER', label: '强制转交执行人' }
]

/**
 * 按工单状态给出可用的强制操作子集（便于扩展：新增状态只需在此映射补项）
 *
 * - 审批中：可驳回 / 终止 / 转交审批 / 转交执行人
 * - 待交付 / 使用中 / 待收回：可终止 / 转交执行人
 * - 终态（已归还 / 已驳回 / 已撤回 / 已终止）：无可用强制操作
 */
export function availableForceOperations(status: OrderStatusCode): ForceOperationTypeCode[] {
  const APPROVING: ForceOperationTypeCode[] = [
    'FORCE_REJECT',
    'FORCE_TERMINATE',
    'FORCE_TRANSFER_APPROVAL',
    'FORCE_TRANSFER_HANDLER'
  ]
  const HANDLING: ForceOperationTypeCode[] = ['FORCE_TERMINATE', 'FORCE_TRANSFER_HANDLER']
  switch (status) {
    case 'PENDING_APPROVAL':
      return APPROVING
    case 'PENDING_DELIVERY':
    case 'BORROWED':
    case 'PENDING_RETURN':
      return HANDLING
    default:
      return []
  }
}

/** 强制干预操作标签样式：驳回 / 终止=危险，改派转交=警告 */
export function forceOperationTagType(type: ForceOperationTypeCode): 'success' | 'info' | 'warning' | 'danger' {
  if (type === 'FORCE_REJECT' || type === 'FORCE_TERMINATE') {
    return 'danger'
  }
  return 'warning'
}

/** 转交候选对象（转交弹窗下拉，） */
export interface TransferCandidate {
  userId: number
  displayName?: string | null
  /** 该候选人名下在办工单数（负载，越低越空闲） */
  inFlightCount?: number | null
}

/** 转交提交载荷（，） */
export interface OrderTransferPayload {
  newHandlerId: number
  /** 转交原因，必填（后端 @NotBlank） */
  comment: string
}

/** 借用申请提交载荷（，按需求方确认的简化模型） */
export interface OrderPayload {
  deviceId: number
  /** 临时锁令牌，提交时原样回传 */
  lockToken: string
  useType: OrderUseType
  /** 用途（原「借用原因」）：选填，一句话说明即可 */
  reason?: string | null
  /** 短期借用必填；长期领用传 null */
  expectedReturnDate: string | null
}

/** 延期申请提交载荷（，） */
export interface OrderExtendPayload {
  /** 申请延长到的结束时间，格式 yyyy-MM-dd HH:mm:ss（后端 @Future 校验） */
  newEndTime: string
  reason: string
}

/** 工单查询条件 */
export interface OrderQuery {
  page?: number
  size?: number
  status?: OrderStatusCode | null
  keyword?: string
}

/**
 * 「全部工单」全局视图查询条件（仅 super_admin / admin）
 *
 * 排序字段与方向在后端做白名单校验，前端只传受限枚举值。
 */
export interface OrderAllQuery {
  page?: number
  size?: number
  status?: OrderStatusCode | null
  /** 申请人姓名（模糊） */
  applicantKeyword?: string
  /** 设备名称 / 资产编号（模糊） */
  deviceKeyword?: string
  /** 借用类型 */
  useType?: OrderUseType | null
  /** 提交时间（含）起始日 yyyy-MM-dd */
  submitTimeFrom?: string | null
  /** 提交时间（含）截止日 yyyy-MM-dd */
  submitTimeTo?: string | null
  /** 部门 */
  departmentId?: number | null
  /** 排序字段：createdAt（默认）/ expectedReturnDate / id */
  sortBy?: 'createdAt' | 'expectedReturnDate' | 'id'
  /** 排序方向：asc / desc */
  sortOrder?: 'asc' | 'desc'
  /**
   * 只看超时工单（ ）。
   *
   * 超时是标记位而非工单状态，不能复用 status 表达，故独立成筛选条件。
   */
  borrowTimeout?: boolean | null
  /**
   * 只看发生过转交的工单（，需求方「全部工单：增加『已转交』筛选」）。
   */
  transferred?: boolean | null
  /**
   * 按自定义申请类型筛选。
   *
   * 该列仅自定义工单有值，因此这个筛选天然只命中自定义申请；普通借用单传该条件会得到空集。
   */
  applyTypeId?: number | null
}

/** 可申请设备选项（申请页「选择设备」） */
export interface DeviceOption {
  id: number
  deviceName: string
  assetNo: string
  primaryCategoryName?: string | null
  secondaryCategoryName?: string | null
  brand?: string | null
  model?: string | null
  storageLocation?: string | null
  status: string
  statusLabel: string
}

/** 借用类型选项 */
export const USE_TYPE_OPTIONS: Array<{ value: OrderUseType; label: string; description: string }> = [
  { value: 'SHORT_TERM', label: '短期借用', description: '有明确归还日期，到期会提醒并自动顺延（最多 2 次）' },
  { value: 'LONG_TERM', label: '长期领用', description: '无固定归还日期，系统不催还，离职或设备故障时归还' }
]

/** 工单状态筛选与展示选项 */
export const ORDER_STATUS_OPTIONS: Array<{ value: OrderStatusCode; label: string }> = [
  { value: 'PENDING_APPROVAL', label: '审批中' },
  { value: 'PENDING_DELIVERY', label: '待交付' },
  { value: 'BORROWED', label: '使用中' },
  { value: 'PENDING_RETURN', label: '待收回' },
  { value: 'RETURNED', label: '已归还' },
  { value: 'REJECTED', label: '已驳回' },
  { value: 'CANCELLED', label: '已撤回' },
  { value: 'TERMINATED', label: '已终止' },
  // ：自定义申请的终态（无交付环节，审批通过/免审批即完成）
  { value: 'COMPLETED', label: '已完成' }
]

/**
 * 收回登记选项（，）
 *
 * deviceEffect 用于在收回弹窗里向执行人说明「这次登记会让设备去哪儿」，
 * 避免执行人只看到「完好 / 轻微损坏 / 故障」而不知道后续影响。
 */
export const RETURN_CONDITION_OPTIONS: Array<{
  value: ReturnConditionCode
  label: string
  deviceEffect: string
  /** 说明是否必填 —— 与服务端同一口径，前端只做「提前拦住」 */
  remarkRequired: boolean
  /** 该结果下说明栏的提示语（必填时直接告诉用户写什么，而不是一句「必填」） */
  remarkPlaceholder: string
}> = [
  {
    value: 'GOOD',
    label: '完好',
    deviceEffect: '设备回到「可用」，可再次被申请',
    remarkRequired: false,
    remarkPlaceholder: '选填，如外观情况说明'
  },
  {
    value: 'DAMAGED',
    label: '损坏',
    deviceEffect: '设备进入「维修中」，同时生成故障记录，修好后由管理员恢复可用',
    remarkRequired: true,
    remarkPlaceholder: '必填：损坏情况说明（例如「屏幕碎裂」）'
  },
  {
    value: 'MISSING_PARTS',
    label: '缺配件',
    deviceEffect: '设备主体回到「可用」（配件缺失不是设备故障），并通知管理员追回缺失配件',
    remarkRequired: true,
    remarkPlaceholder: '必填：缺了哪些配件（例如「缺鼠标、缺拓展坞」）'
  },
  {
    value: 'LOST',
    label: '丢失',
    deviceEffect: '设备置为「已丢失」且不可再被申请，并通知管理员查找；找回后可手动改回「可用」',
    remarkRequired: true,
    remarkPlaceholder: '必填：丢失经过说明（例如「出差途中遗失」）'
  }
]

/** 该检查结果是否必须填写说明（与服务端 ReturnCondition#isRemarkRequired 同一口径） */
export function returnConditionRequiresRemark(code: ReturnConditionCode): boolean {
  return RETURN_CONDITION_OPTIONS.find((item) => item.value === code)?.remarkRequired ?? false
}

/** 该检查结果的说明栏提示语 */
export function returnConditionPlaceholder(code: ReturnConditionCode): string {
  return RETURN_CONDITION_OPTIONS.find((item) => item.value === code)?.remarkPlaceholder ?? '选填'
}

/** 工单状态标签样式 */
export function orderStatusTagType(status: OrderStatusCode): 'success' | 'info' | 'warning' | 'danger' {
  switch (status) {
    case 'PENDING_APPROVAL':
      return 'warning'
    case 'PENDING_DELIVERY':
      return 'warning'
    case 'BORROWED':
      return 'success'
    case 'PENDING_RETURN':
      return 'warning'
    case 'RETURNED':
      return 'info'
    case 'REJECTED':
      return 'danger'
    case 'CANCELLED':
      return 'info'
    case 'TERMINATED':
      return 'info'
    // ：自定义申请完成用成功色（与借用单「已归还」同为正向终态）
    case 'COMPLETED':
      return 'success'
    default:
      return 'info'
  }
}

/** 审批节点状态标签样式 */
export function approvalNodeTagType(
  status: ApprovalNodeStatusCode
): 'success' | 'info' | 'warning' | 'danger' {
  switch (status) {
    case 'APPROVED':
      return 'success'
    case 'REJECTED':
      return 'danger'
    case 'PENDING':
      return 'warning'
    // 抄送：不是审批动作，用成功色表示「已送达」而不是「已通过」的实心色
    case 'CC_NOTIFIED':
      return 'success'
    // 未激活：尚未发生，用灰色待定色。它既不是失败也不是等待某人处理，
    // 若与 PENDING 同用 warning 会让人误以为「已轮到我，但我没处理」
    case 'INACTIVE':
      return 'info'
    default:
      return 'info'
  }
}

/**
 * 节点是否「未激活」。
 *
 * <p>抽成函数而不是到处写 `status === 'INACTIVE'`：语义名比取值名更难写错，
 * 且将来若新增「未激活」的邻近状态（如条件不可判定），只需改这一处。
 */
export function isInactiveNode(node: { status: ApprovalNodeStatusCode }): boolean {
  return node.status === 'INACTIVE'
}

/**
 * 按「是否展示完整骨架」过滤快照节点（ 激活史）。
 *
 * <p>默认**隐藏** `INACTIVE` 节点：它们代表「流程还没走到这里」，对普通查看者而言
 * 与 `SKIPPED`（走过但没命中）完全不同 —— 后者是历史事实，前者是尚未发生。
 * 管理角色可显式打开骨架视图看完整链路（含每个未激活节点的原因）。
 *
 * @param nodes 原始快照节点（服务端返回顺序即展示顺序）
 * @param showSkeleton 是否展示完整骨架（含未激活节点）
 */
export function visibleApprovalNodes<T extends { status: ApprovalNodeStatusCode }>(
  nodes: readonly T[] | null | undefined,
  showSkeleton: boolean
): T[] {
  const list = [...(nodes ?? [])]
  return showSkeleton ? list : list.filter((node) => !isInactiveNode(node))
}

/** 未激活节点数量（供「显示未激活节点（N）」这类开关文案使用） */
export function countInactiveNodes(nodes: readonly { status: ApprovalNodeStatusCode }[] | null | undefined): number {
  return (nodes ?? []).filter((node) => isInactiveNode(node)).length
}

/** 延期状态标签样式 */
export function extendStatusTagType(status: ExtendStatusCode): 'success' | 'info' | 'warning' | 'danger' {
  switch (status) {
    case 'APPROVED':
      return 'success'
    case 'REJECTED':
      return 'danger'
    case 'PENDING_APPROVAL':
      return 'warning'
    default:
      return 'info'
  }
}

/** 转交类型标签样式：人工转交=主色，离职自动转交=警示，强制转交=危险 */
export function transferTypeTagType(type?: TransferTypeCode | null): 'success' | 'info' | 'warning' | 'danger' {
  if (type === 'FORCE_ADMIN') {
    return 'danger'
  }
  return type === 'AUTO_DIMISSION' ? 'warning' : 'info'
}

/**
 * 是否展示「已超时」红标。
 *
 * 超时是标记位而非状态：工单可能同时处于「使用中」且「已超时」，
 * 因此红标与状态标签并列展示，而不是互相替换。
 */
export function isTimeout(order: Pick<OrderItem, 'borrowTimeout'>): boolean {
  return order.borrowTimeout === true
}

/**
 * 是否已到期或已超时（催还的适用条件，）。
 *
 * 与后端 `OrderServiceImpl#dueOrTimeout` / `OrderUrgeServiceImpl#isDueOrTimeout` 同一判定：
 * ① 超时标记为真；或 ② 计划归还时间已过。用于决定「催还」按钮是否出现
 * （冷却中的按钮仍要显示以便展示倒计时，故不能直接用服务端 canUrgeReturn）。
 *
 * 后端返回的时间格式为 `yyyy-MM-dd HH:mm:ss`，部分 JS 引擎不认空格分隔，
 * 统一替换为 `T` 再解析（本地时区）。
 */
export function isDueOrTimeout(
  order: Pick<OrderItem, 'borrowTimeout' | 'plannedEndTime'>,
  now: number = Date.now()
): boolean {
  if (order.borrowTimeout === true) {
    return true
  }
  if (!order.plannedEndTime) {
    return false
  }
  const timestamp = Date.parse(order.plannedEndTime.replace(' ', 'T'))
  return Number.isFinite(timestamp) && timestamp <= now
}

// ----------------------------------------------------------------------
// ：自定义申请
// ----------------------------------------------------------------------

/** 是否为自定义申请工单（有 applyTypeId 即自定义） */
export function isCustomOrder(order: Pick<OrderItem, 'orderType' | 'applyTypeId'>): boolean {
  return order.orderType === 'CUSTOM' || order.applyTypeId != null
}

/** 提交自定义申请请求载荷（POST /orders/custom） */
export interface CustomOrderPayload {
  applyTypeId: number
  /** 表单数据：字段 key → 值（结构由该类型绑定的已发布版本 schema 决定） */
  formData: FormData
  /**
   * 申请人自选审批人：流程节点 key → 所选审批人 user_id 列表。
   *
   * 仅在审批方式为 FLOW、且节点配置了「申请人自选」时需要。它不是表单字段 ——
   * 同样一份表单可以被多套流程复用，而「谁来审」属于流程，不属于表单，
   * 所以走独立字段而不是塞进 formData。
   *
   * 服务端会重新校验「人在可选范围内」「人数在 [min, max] 内」，
   * 前端这里的选择器只是体验优化。
   */
  approverSelections?: Record<string, number[]> | null
}

/**
 * 工单自定义表单数据（GET /orders/{id}/form-data）
 *
 * schema 与 data 一起返回：详情页要渲染「有标签、有值」的表，若分开取，
 * 普通员工没有表单模板权限根本拿不到字段定义。schema 是<b>这笔工单当初用的那一版</b>，
 * 不受模板后续改版影响（快照语义）。
 */
export interface OrderFormData {
  orderId: number
  applyTypeId?: number | null
  applyTypeName?: string | null
  formTemplateVersionId?: number | null
  formTemplateVersionNo?: number | null
  schema?: FormSchema | null
  data: FormData
  createdAt?: string | null
}
