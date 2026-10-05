import { http } from '@/api/request'
import type { PageResult } from '@/types/api'
import type { BorrowFlowPreview } from '@/types/department'
import type { ScanLookupResult } from '@/types/scan'
import type {
  AssignCandidate,
  CustomOrderPayload,
  DeviceOption,
  OrderAllQuery,
  OrderDetail,
  OrderExtendItem,
  OrderExtendPayload,
  OrderForceOperationItem,
  OrderForcePayload,
  OrderFormData,
  OrderItem,
  OrderPayload,
  OrderQuery,
  OrderTransferItem,
  OrderTransferPayload,
  OrderUrgeItem,
  ReturnConditionCode,
  TransferCandidate
} from '@/types/order'

/**
 * 借用工单接口（ /  /  / ，需求方  追加转交与催办）
 *
 * 权限：全部端点仅要求已登录；数据可见性与操作权由后端按
 * 「申请人 / 审批人 / 实际执行人 / super_admin / admin」强制校验。
 */
export const orderApi = {
  /** 可申请设备选项（申请页「选择设备」下拉；仅普通员工可见的轻量数据） */
  selectableDevices(keyword?: string, limit?: number) {
    const params: Record<string, unknown> = {}
    if (keyword) params.keyword = keyword
    if (limit != null) params.limit = limit
    return http.get<DeviceOption[]>('/orders/selectable-devices', params)
  },

  /**
   * 扫码查询（P1 扫码借还）。
   *
   * 一次请求回答「这个码是什么 + 我现在能对它做什么 + 该跳哪」——
   * 判定全在服务端（依赖当前登录人、设备状态、锁定超时、我的在借工单四个事实），
   * 前端只按返回的 action 分流，不再本地推断，避免列表数据过期导致误判。
   */
  scanLookup(code: string) {
    return http.get<ScanLookupResult>('/orders/scan-lookup', { code })
  },

  /** 提交借用申请（需先调用 deviceApi.lock 拿到 lockToken） */
  create(data: OrderPayload) {
    return http.post<number>('/orders', data)
  },

  /**
   * 借用单审批路径预览（M1）。
   *
   * 借用的审批路径取决于「申请人所属部门是否绑定了流程模板」，
   * 而申请人自己并不知道这一点 —— 所以由服务端按当前登录用户反查其分组后计算。
   * 未绑定流程时返回 `bound=false`，前端应回退展示固定审批人列表。
   *
   * 只读、不落库；调用失败不应阻断提交（真正的闸门在 `create`）。
   */
  borrowFlowPreview(query: {
    useType?: string
    expectedReturnDate?: string | null
    deviceId?: number | null
  }) {
    return http.get<BorrowFlowPreview>('/orders/borrow-flow-preview', {
      useType: query.useType,
      expectedReturnDate: query.expectedReturnDate ?? undefined,
      deviceId: query.deviceId ?? undefined
    })
  },

  // ------------------------------------------------------------------
  // 自定义申请
  // ------------------------------------------------------------------

  /**
   * 提交自定义申请（无设备、表单结构由申请类型绑定的已发布版本决定）。
   *
   * 后端强制：类型已启用 + 当前用户在提交权限范围内 + 表单数据按 schema 逐字段校验
   * （含 USER / DEVICE / BIZ_GROUP 引用类查库）。
   */
  createCustom(data: CustomOrderPayload) {
    return http.post<number>('/orders/custom', data)
  },

  /**
   * 获取自定义工单的表单数据（含「当初那版」schema）。
   *
   * 可见性判定与工单详情一致（申请人 / 审批人 / 执行人 / super_admin / admin）；
   * 非自定义工单后端返回 ORDER_NOT_CUSTOM。
   */
  formData(id: number) {
    return http.get<OrderFormData>(`/orders/${id}/form-data`)
  },

  /** 我的工单（我提交的申请） */
  mine(query: OrderQuery = {}) {
    const params: Record<string, unknown> = {}
    if (query.page != null) params.page = query.page
    if (query.size != null) params.size = query.size
    if (query.status) params.status = query.status
    if (query.keyword) params.keyword = query.keyword
    return http.get<PageResult<OrderItem>>('/orders/mine', params)
  },

  /** 审批待办（当前轮到我审批的工单） */
  pendingApproval(page = 1, size = 10) {
    return http.get<PageResult<OrderItem>>('/orders/todo/approval', { page, size })
  },

  /** 我的待处理（我是实际执行人：待交付 / 使用中） */
  myHandling(page = 1, size = 10) {
    return http.get<PageResult<OrderItem>>('/orders/todo/handling', { page, size })
  },

  /**
   * 抄送我的：我被抄送过的工单。
   *
   * 与其余待办列表不同，这里**不限定状态**：抄送是「知会」，
   * 被抄送人理应看到该工单的最终结果（哪怕已通过 / 已驳回 / 已撤回）。
   */
  ccOrders(page = 1, size = 10) {
    return http.get<PageResult<OrderItem>>('/orders/todo/cc', { page, size })
  },

  /**
   * 全部工单（全局视图，仅 super_admin / admin， 工单管理）
   *
   * 服务层会二次校验角色：前端菜单隐藏只是体验，直调接口仍返回 403。
   */
  allOrders(query: OrderAllQuery = {}) {
    const params: Record<string, unknown> = {}
    if (query.page != null) params.page = query.page
    if (query.size != null) params.size = query.size
    if (query.status) params.status = query.status
    if (query.applicantKeyword) params.applicantKeyword = query.applicantKeyword
    if (query.deviceKeyword) params.deviceKeyword = query.deviceKeyword
    if (query.useType) params.useType = query.useType
    if (query.submitTimeFrom) params.submitTimeFrom = query.submitTimeFrom
    if (query.submitTimeTo) params.submitTimeTo = query.submitTimeTo
    if (query.departmentId != null) params.departmentId = query.departmentId
    if (query.sortBy) params.sortBy = query.sortBy
    if (query.sortOrder) params.sortOrder = query.sortOrder
    // 超时是标记位而非状态，独立筛选（ ）
    if (query.borrowTimeout != null) params.borrowTimeout = query.borrowTimeout
    // 已转交是「存在转交记录」的派生条件，同样独立筛选（ ）
    if (query.transferred != null) params.transferred = query.transferred
    // 自定义申请类型筛选：仅自定义工单有该列，天然只命中自定义申请
    if (query.applyTypeId != null) params.applyTypeId = query.applyTypeId
    return http.get<PageResult<OrderItem>>('/orders/all', params)
  },

  /** 工单详情（含审批快照链路 +  转交/催办时间线） */
  detail(id: number) {
    return http.get<OrderDetail>(`/orders/${id}`)
  },

  /**
   * 「上一节点指定审批人」的候选名单
   *
   * 审批弹窗点「通过」时调用。刻意不复用 `/users/options`：那个接口挂 `staff:view`，
   * 普通审批人（直属主管 / IT主管）会 403，候选被吞成空数组后
   * 「必须指定才能通过」就成了无法满足的前置条件。本接口与工单详情同权限口径，
   * 并且顺带把「要指定几人 / 为什么只有这些人」一次说清。
   *
   * 无待指派节点时返回 `requiredCount: 0`（正常情形，不是错误）。
   */
  assignCandidates(id: number) {
    return http.get<AssignCandidate>(`/orders/${id}/assign-candidates`)
  },

  /** 审批通过 / 驳回（驳回必须携带 comment，后端强制校验） */
  /**
   * 审批（通过 / 驳回）
   *
   * @param nextApproverIds ：当**下一步骤**是「上一节点指定审批人」时，通过的人
   *   必须在这里点名同样数量的人（多一个少一个都会被服务端 400 拒绝）。
   *   没有待指派节点时不要传 —— 传了同样会被拒绝，而不是被静默忽略
   *   （静默忽略会让审批人以为自己指派成功了）。
   */
  approve(id: number, approved: boolean, comment?: string | null, nextApproverIds?: number[] | null) {
    return http.put<void>(`/orders/${id}/approve`, {
      approved,
      comment: comment ?? null,
      nextApproverIds: nextApproverIds && nextApproverIds.length > 0 ? nextApproverIds : null
    })
  },

  /** 申请人撤回（PENDING_APPROVAL / PENDING_DELIVERY 可撤回，撤回后设备释放） */
  cancel(id: number, reason?: string | null) {
    return http.put<void>(`/orders/${id}/cancel`, { reason: reason ?? null })
  },

  /** 实际执行人确认交付（工单→使用中，设备→使用中） */
  deliver(id: number) {
    return http.put<void>(`/orders/${id}/deliver`)
  },

  /**
   * 申请人发起归还（ 第一步，需求方  ）
   *
   * 工单 → 待收回，**设备状态保持不变**（仍为使用中，防止归还途中被他人申请）。
   */
  requestReturn(id: number, returnNote?: string | null) {
    return http.put<void>(`/orders/${id}/return`, { returnNote: returnNote?.trim() || null })
  },

  /**
   * 实际执行人确认收回（ 第二步，需求方  /4）
   *
   * 收回时登记设备状态：完好 / 轻微损坏 → 设备回到可用；故障 → 设备进入维修中。
   */
  confirmReturn(id: number, condition: ReturnConditionCode, remark?: string | null) {
    return http.put<void>(`/orders/${id}/confirm-return`, {
      condition,
      remark: remark?.trim() || null
    })
  },

  // ------------------------------------------------------------------
  // 借用延期（，）
  // ------------------------------------------------------------------

  /**
   * 申请人发起延期申请
   *
   * 仅「使用中」的短期借用可申请；新结束时间必须晚于当前时间且晚于原计划。
   * 延期数量用尽 / 已有审批中的延期 / 长期领用，后端一律拒绝并返回规范错误码。
   */
  requestExtend(id: number, data: OrderExtendPayload) {
    return http.post<number>(`/orders/${id}/extends`, data)
  },

  /** 某工单的全部延期记录（含审批链路快照，工单详情「延期时间线」） */
  listExtends(id: number) {
    return http.get<OrderExtendItem[]>(`/orders/${id}/extends`)
  },

  /** 延期审批待办（当前轮到我审批的延期申请） */
  myExtendApproval(page = 1, size = 10) {
    return http.get<PageResult<OrderExtendItem>>('/order-extends/todo/approval', { page, size })
  },

  /** 延期审批通过 / 驳回（驳回必须携带 comment，后端强制校验） */
  approveExtend(id: number, approved: boolean, comment?: string | null) {
    return http.put<void>(`/order-extends/${id}/approve`, { approved, comment: comment ?? null })
  },

  // ------------------------------------------------------------------
  // 工单转交（， + 需求方）
  // ------------------------------------------------------------------

  /**
   * 转交工单给同组其他在职成员
   *
   * 后端强制：仅 PENDING_DELIVERY / BORROWED / PENDING_RETURN 可转；
   * 普通执行人限同组在职成员且目标不能是申请人本人；super_admin 不受小组限制。
   * 转交后执行人变更，超时告警 / 归还确认权限 / 待办列表全部跟随新执行人。
   */
  transfer(id: number, data: OrderTransferPayload) {
    return http.post<void>(`/orders/${id}/transfer`, data)
  },

  /** 某工单的转交历史（工单详情「转交时间线」，按时间升序） */
  listTransfers(id: number) {
    return http.get<OrderTransferItem[]>(`/orders/${id}/transfers`)
  },

  /** 转交候选对象（弹窗下拉）：普通执行人=同组其他在职成员；super_admin=全部有效用户 */
  transferCandidates(id: number) {
    return http.get<TransferCandidate[]>(`/orders/${id}/transfer-candidates`)
  },

  // ------------------------------------------------------------------
  // 工单催办（，需求方）
  // ------------------------------------------------------------------

  /**
   * 审批催办（申请人 → 当前审批节点审批人）
   *
   * 冷却键＝「工单 + 当前节点」，冷却时长由 `system_config.urge_cooldown_minutes` 决定（默认 60 分钟）。
   * 催办不改变工单状态，仅发站内消息 + 留痕。
   */
  urgeApproval(id: number) {
    return http.post<void>(`/orders/${id}/urge-approval`)
  },

  /** 归还催办（实际执行人 → 借用人），仅「使用中且已到期/超时」可发起 */
  urgeReturn(id: number) {
    return http.post<void>(`/orders/${id}/urge-return`)
  },

  /** 某工单的催办历史（工单详情「催办时间线」，按时间升序） */
  listUrges(id: number) {
    return http.get<OrderUrgeItem[]>(`/orders/${id}/urges`)
  },

  // ------------------------------------------------------------------
  // 超管强制干预（仅 super_admin；后端按角色 + 状态双重校验）
  // ------------------------------------------------------------------

  /**
   * 强制干预（驳回 / 终止 / 强制转交审批 / 强制转交执行人）
   *
   * 后端强制：reason 必填（缺失返回 FORCE_REASON_REQUIRED，非 400 param 校验，
   * 故前端亦做必填校验提升体验）；转交类需对应 target*Id；不做「强制通过」。
   */
  force(id: number, data: OrderForcePayload) {
    return http.post<void>(`/orders/${id}/force`, data)
  },

  /** 某工单的强制干预记录（工单详情「强制干预记录」，按时间升序） */
  listForceOperations(id: number) {
    return http.get<OrderForceOperationItem[]>(`/orders/${id}/force`)
  }
}

export default orderApi
