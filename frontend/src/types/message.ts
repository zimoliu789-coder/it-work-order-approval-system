/**
 * 站内消息类型（ 站内消息通知系统）
 *
 * 与后端 `com.enterprise.ticket.common.constant.MessageType` 一一对应；
 * 类型决定铃铛面板里消息的图标颜色与点击后的跳转目标。
 */

/** 消息类型码 */
export type MessageTypeCode =
  | 'APPROVAL_TODO'
  | 'APPROVAL_PASSED'
  | 'APPROVAL_REJECTED'
  | 'DELIVERY_TODO'
  | 'ORDER_CANCELLED'
  | 'ORDER_TRANSFERRED'
  | 'RETURN_REQUESTED'
  | 'RETURN_CONFIRMED'
  | 'DIMISSION_RETURN'
  | 'BORROW_EXPIRE_WARNING'
  | 'BORROW_DUE_REMINDER'
  | 'BORROW_AUTO_EXTEND'
  | 'BORROW_TIMEOUT'
  | 'EXTEND_RESULT'
  | 'PASSWORD_RESET'
  //  新增（与后端 MessageType.URGE_APPROVAL / URGE_RETURN 对应）
  | 'URGE_APPROVAL'
  | 'URGE_RETURN'
  // b 新增（与后端 MessageType.FORCE_* 对应）：超管强制干预结果通知。
  //  收尾优化·：由单一 FORCE_OPERATION 拆成四个子类型 ——
  // 原先四类强制操作（驳回 / 终止 / 转交审批 / 转交执行人）共用一种类型，
  // 消息列表无法区分「我的单是被驳回了还是被终止了」，而这两者的后续动作完全不同。
  | 'FORCE_REJECT'
  | 'FORCE_TERMINATE'
  | 'FORCE_TRANSFER_APPROVAL'
  | 'FORCE_TRANSFER_HANDLER'
  //  新增（与后端 MessageType.EXPORT_READY 对应）：异步导出完成通知
  | 'EXPORT_READY'
  // 三波补做·第二波新增（定时任务产生的系统消息）
  | 'APPROVAL_TIMEOUT_REMIND'
  | 'DEVICE_RECONCILE_ALERT'
  // P0 新增（与后端 MessageType.RETURN_RECOVERY_ALERT 对应）：
  // 归还检查登记「缺配件 / 丢失」时通知管理员去追回或查找，接收人为 admin + super_admin
  | 'RETURN_RECOVERY_ALERT'
  // Docker 部署 + 限流加固新增（与后端 MessageType.OPS_BACKUP_ALERT 对应）：
  // 备份作业失败告警，接收人为 super_admin
  | 'OPS_BACKUP_ALERT'
  // -G 补登记（后端 MessageType.HA_* 当时已加、前端漏了 ⇒ 消息中心里
  // 这三个类型的标签会回落成英文码，颜色也落默认灰）
  | 'HA_NODE_DISCONNECTED'
  | 'HA_SYNC_FAILED'
  | 'HA_SWITCHOVER'
  //  新增（与后端 MessageType.EXCEPTION_ALERT 对应）：未预期异常告警，接收人为超管
  | 'EXCEPTION_ALERT'
  //  新增（与后端 MessageType.SECURITY_ALERT 对应）：账号锁定 / IP 封禁 / 提权尝试
  | 'SECURITY_ALERT'

/** 消息列表项 */
export interface MessageItem {
  id: number
  title: string
  content: string
  /** 关联工单 id，空表示该消息无跳转目标 */
  orderId?: number | null
  /** 关联工单编号 */
  orderNo?: string | null
  messageType?: MessageTypeCode | string | null
  messageTypeLabel?: string | null
  isRead?: boolean | null
  readAt?: string | null
  createdAt: string
}

/** 消息查询条件 */
export interface MessageQuery {
  page?: number
  size?: number
  /** null=全部、true=仅未读、false=仅已读 */
  unreadOnly?: boolean | null
  /** 按消息类型过滤（下拉筛选），空=全部 */
  messageType?: MessageTypeCode | string | null
  /** 标题 / 内容关键词，空=全部 */
  keyword?: string | null
}

/** 全部已读返回体 */
export interface MarkAllReadResult {
  affected: number
}

/**
 * 消息类型 → 标签色。
 *
 * 语义分组：待办类=warning（要动手）、完成类=success、退回/超时类=danger、其余=info。
 *
 *  的催办提醒归入 warning：它要求接收者「动手」（审批人去审批、借用人去归还），
 * 与「审批待办 / 到期预警」同类；若回落到 info 灰，会和纯通知类消息混在一起看不出轻重。
 */
export function messageTagType(type?: string | null): 'success' | 'info' | 'warning' | 'danger' {
  switch (type) {
    // 待办类：都要求接收者「动手」——审批人去审批 / 借用人去归还 / 超时提醒去处理，统一 warning。
    // 若回落到 info 灰，会和纯通知类消息混在一起看不出轻重。
    // （APPROVAL_TIMEOUT_REMIND 为三波补做·第二波新增的审批超时提醒，语义同催办。）
    case 'APPROVAL_TODO':
    case 'DELIVERY_TODO':
    case 'RETURN_REQUESTED':
    case 'BORROW_EXPIRE_WARNING':
    case 'BORROW_DUE_REMINDER':
    case 'URGE_APPROVAL':
    case 'URGE_RETURN':
    case 'APPROVAL_TIMEOUT_REMIND':
      return 'warning'
    // 完成类：审批通过 / 归还完成 / 导出就绪（接收者只需知悉并去下载），归 success。
    case 'APPROVAL_PASSED':
    case 'RETURN_CONFIRMED':
    case 'EXPORT_READY':
      return 'success'
    // 异常 / 越权类：退回、超时、离职归还需立刻关注；
    // 超管强制干预（强制驳回 / 终止 / 改派）属越权级非常规动作，避免用户漏看；
    // 设备工单对账告警（第二波新增）表征数据不一致，同属异常；
    // 备份失败告警（本轮新增）意味着「数据安全网已破」，必须用最醒目的颜色，
    // 一旦被当成普通通知略过，代价是灾难发生时无备份可恢复。统一 danger。
    // 四个强制干预子类型（ 拆分）同样保持 danger：按子类型区分只影响
    // 「能看出是哪一种」，不影响轻重级别，因此与上面同组。
    //
    // -G 补登记：主备节点断连 / 同步失败 / 切换 —— 基础设施级事件，同样必须醒目。
    //  / ：未预期异常与安全事件同属「必须立刻看」的那一类。
    // ⚠️ 上面这几行说明必须放在**这一组 case 之前**，不能夹在两个 `case` 标签之间 ——
    // ESLint 的 no-fallthrough 会把「只有注释的 case 子句」当成非空子句而报错。
    case 'APPROVAL_REJECTED':
    case 'BORROW_TIMEOUT':
    case 'DIMISSION_RETURN':
    case 'FORCE_REJECT':
    case 'FORCE_TERMINATE':
    case 'FORCE_TRANSFER_APPROVAL':
    case 'FORCE_TRANSFER_HANDLER':
    case 'DEVICE_RECONCILE_ALERT':
    case 'OPS_BACKUP_ALERT':
    case 'RETURN_RECOVERY_ALERT':
    case 'HA_NODE_DISCONNECTED':
    case 'HA_SYNC_FAILED':
    case 'HA_SWITCHOVER':
    case 'EXCEPTION_ALERT':
    case 'SECURITY_ALERT':
      return 'danger'
    default:
      return 'info'
  }
}

/**
 * 消息类型 → 点击后应跳转的页面（ 消息铃铛）。
 *
 * 抽成纯函数（而不是留在 `MessageBell.vue` 内的局部函数）是为了能被单测覆盖 ——
 * 跳错页面属于「点一下就发现」的低频缺陷，但一旦新增消息类型而映射忘记补，
 * 用户会被静默带到无关页面。集中在此处 + `.spec.ts` 固化，新增类型时能立刻发现漏配。
 */
export function messageTargetRoute(type?: string | null): string {
  switch (type) {
    // 审批类：审批人去「工单审批 → 借用审批」处理（催办审批、超时提醒同样落在这里）
    case 'APPROVAL_TODO':
    case 'URGE_APPROVAL':
    case 'APPROVAL_TIMEOUT_REMIND':
      return '/order/approval'
    // 执行人类：待交付 / 待收回 / 被转交的工单都在「我的待处理」
    case 'DELIVERY_TODO':
    case 'RETURN_REQUESTED':
    case 'BORROW_TIMEOUT':
    case 'DIMISSION_RETURN':
    case 'ORDER_TRANSFERRED':
      return '/order/pending'
    // 超管强制干预（ 拆分为四个子类型）：收件人身份随子类型不同，
    // 因此跳转目标按「谁必须立刻动手」来选，而不是一律回落到某个「大家都有」的页面。
    //
    //   FORCE_REJECT / FORCE_TERMINATE  →  申请人（以及被终止时的执行人）：只需知悉，
    //                                       工单本身在「我的工单」里，故落该页；
    //   FORCE_TRANSFER_APPROVAL         →  新审批人必须立刻审批，落「审批待办」。
    //                                       旧的审批人与申请人也会收到同类型消息，
    //                                       他们落在该页只会看到空队列 —— 无害，
    //                                       且强于让真正要办事的人找不到入口；
    //   FORCE_TRANSFER_HANDLER          →  新执行人必须交付 / 回收，落「我的待处理」，同上。
    case 'FORCE_REJECT':
    case 'FORCE_TERMINATE':
      return '/order/mine'
    case 'FORCE_TRANSFER_APPROVAL':
      return '/order/approval'
    case 'FORCE_TRANSFER_HANDLER':
      return '/order/pending'
    // 导出完成：落「导出记录」页下载文件 —— 该页正是收到消息后要找回文件的地方
    case 'EXPORT_READY':
      return '/export/records'
    // 设备工单对账告警（第二波）：接收人是管理员，落「设备台账」以便核对状态不一致的设备
    case 'DEVICE_RECONCILE_ALERT':
      return '/asset/ledger'
    // 归还缺件/丢失告警（P0）：接收人是管理员，去「全部工单」按工单号回溯是谁借的
    // —— 消息正文里给了工单号与资产编号，这里给出能立刻查到的落点
    case 'RETURN_RECOVERY_ALERT':
      return '/order/all'
    // 备份失败告警（本轮新增）：接收人是 super_admin，落「系统参数设置」——
    // 备份的保留天数、清理策略等参数正在该页，处理告警时通常要顺带核对配置
    case 'OPS_BACKUP_ALERT':
      return '/system/config'
    // -G 补：主备告警落「主备配置」页 —— 那里能看到节点状态与心跳，是处理它的落点
    case 'HA_NODE_DISCONNECTED':
    case 'HA_SYNC_FAILED':
    case 'HA_SWITCHOVER':
      return '/system/ha'
    // ：异常告警落「异常日志」页 —— 邮件里只给摘要，完整堆栈在这里
    case 'EXCEPTION_ALERT':
      return '/system/exception-log'
    // ：安全事件落「安全日志」页（当前封禁列表与手动解封也在这里）
    case 'SECURITY_ALERT':
      return '/system/security-log'
    // 密码变更（新建账号 / 管理员重置密码）与工单无关，落「个人中心」
    case 'PASSWORD_RESET':
      return '/profile'
    // 其余（审批结果 / 归还完成 / 到期预警 / 催还提醒…）：当事人自己名下的工单在「我的工单」
    default:
      return '/order/mine'
  }
}

/**
 * 消息类型下拉筛选项（消息中心「类型」筛选）。
 *
 * `messageTypeLabel` 也取自此处，保证「下拉里显示什么，列表标签就显示什么」——
 * 两处若各写一份中文名，新增类型时极易只改一处，出现下拉与列表明明是同一类型却叫法不同的诡异现象。
 */
export const MESSAGE_TYPE_OPTIONS: ReadonlyArray<{ value: MessageTypeCode; label: string }> = [
  { value: 'APPROVAL_TODO', label: '审批待办' },
  { value: 'APPROVAL_PASSED', label: '审批通过' },
  { value: 'APPROVAL_REJECTED', label: '审批驳回' },
  { value: 'DELIVERY_TODO', label: '待交付' },
  { value: 'ORDER_CANCELLED', label: '工单取消' },
  { value: 'ORDER_TRANSFERRED', label: '工单转交' },
  { value: 'RETURN_REQUESTED', label: '待收回' },
  { value: 'RETURN_CONFIRMED', label: '归还完成' },
  { value: 'DIMISSION_RETURN', label: '离职归还' },
  { value: 'BORROW_EXPIRE_WARNING', label: '到期预警' },
  { value: 'BORROW_DUE_REMINDER', label: '催还提醒' },
  { value: 'BORROW_AUTO_EXTEND', label: '自动顺延' },
  { value: 'BORROW_TIMEOUT', label: '超时告警' },
  { value: 'EXTEND_RESULT', label: '延期结果' },
  { value: 'URGE_APPROVAL', label: '审批催办' },
  { value: 'URGE_RETURN', label: '归还催办' },
  // 超管强制干预四子类型（）：标签必须能一眼区分是哪一种强制操作
  { value: 'FORCE_REJECT', label: '强制驳回' },
  { value: 'FORCE_TERMINATE', label: '强制终止' },
  { value: 'FORCE_TRANSFER_APPROVAL', label: '强制改派审批' },
  { value: 'FORCE_TRANSFER_HANDLER', label: '强制转交执行' },
  { value: 'EXPORT_READY', label: '导出完成' },
  { value: 'APPROVAL_TIMEOUT_REMIND', label: '审批超时提醒' },
  { value: 'DEVICE_RECONCILE_ALERT', label: '设备对账告警' },
  { value: 'RETURN_RECOVERY_ALERT', label: '归还缺件/丢失告警' },
  { value: 'OPS_BACKUP_ALERT', label: '备份失败告警' },
  { value: 'HA_NODE_DISCONNECTED', label: '主备节点断连' },
  { value: 'HA_SYNC_FAILED', label: '主备同步失败' },
  { value: 'HA_SWITCHOVER', label: '主备切换' },
  { value: 'EXCEPTION_ALERT', label: '系统异常告警' },
  { value: 'SECURITY_ALERT', label: '安全事件告警' },
  { value: 'PASSWORD_RESET', label: '密码变更' }
]

const MESSAGE_TYPE_LABELS: Record<string, string> = Object.fromEntries(
  MESSAGE_TYPE_OPTIONS.map((opt) => [opt.value, opt.label])
)

/**
 * 运行时元数据（需求方三波·第三波· 消息类型元数据接口）。
 *
 * 由 `GET /api/meta/message-types` 拉取后覆盖内置表：把「后端枚举 → 前端中文名」的映射
 * 收敛到后端一处，根除「后端新增类型、前端忘记同步」这一反复复发的缺陷。
 * 内置兜底表仍保留 —— 元数据接口不可用时界面照常渲染，不会因一个辅助接口白屏。
 */
let runtimeTypeOptions: ReadonlyArray<{ value: MessageTypeCode; label: string }> | null = null
let runtimeTypeLabels: Record<string, string> | null = null

/** 用后端元数据覆盖内置消息类型表（空列表视为无效，保留兜底） */
export function applyMessageTypeMeta(list: Array<{ code: string; label: string }> | null | undefined): void {
  if (!list || list.length === 0) {
    return
  }
  const options: Array<{ value: MessageTypeCode; label: string }> = []
  const labels: Record<string, string> = {}
  for (const item of list) {
    options.push({ value: item.code as MessageTypeCode, label: item.label })
    labels[item.code] = item.label
  }
  runtimeTypeOptions = options
  runtimeTypeLabels = labels
}

/**
 * 消息类型下拉筛选项：优先用后端元数据（若已拉取），否则回退内置兜底表。
 *
 * 注意是<b>函数</b>而非常量：元数据在运行时异步到达，若导出常量会导致
 * 「元数据到了但下拉仍是旧的内置表」。
 */
export function messageTypeOptions(): ReadonlyArray<{ value: MessageTypeCode; label: string }> {
  return runtimeTypeOptions ?? MESSAGE_TYPE_OPTIONS
}

/** 取消息类型中文名；后端已给 label 时优先用后端的，此处仅作兜底 */
export function messageTypeLabel(type?: string | null): string {
  if (!type) {
    return '消息'
  }
  return runtimeTypeLabels?.[type] ?? MESSAGE_TYPE_LABELS[type] ?? type
}
