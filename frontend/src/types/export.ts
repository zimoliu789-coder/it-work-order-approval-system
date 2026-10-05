/** 导出类型（ /  / ，与后端 ExportType 枚举一一对应） */
export type ExportTypeCode =
  | 'DEVICE'
  | 'ORDER'
  | 'USAGE'
  | 'CUSTOM_FORM'
  | 'LOG'
  | 'REPORT_DEVICE_USAGE'
  | 'REPORT_APPROVAL_EFFICIENCY'
  | 'REPORT_DEVICE_FAULT'

/** 导出任务状态（与后端 ExportStatus 一致） */
export type ExportStatusCode = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED'

/** 导出受理方式：同步=文件已生成，异步=后台生成完再通知 */
export type ExportMode = 'SYNC' | 'ASYNC'

/** 设备台账导出筛选（与设备列表接口同口径） */
export interface ExportDeviceFilter {
  keyword?: string
  primaryCategoryId?: number
  secondaryCategoryId?: number
  status?: string
}

/** 工单导出筛选（与工单列表接口同口径） */
export interface ExportOrderFilter {
  status?: string
  /** 工单号模糊（「我的工单」范围使用） */
  keyword?: string
  /** 申请人姓名模糊（「全部工单」范围使用，仅管理员） */
  applicantKeyword?: string
  /** 设备名称 / 资产编号模糊（「全部工单」范围使用，仅管理员） */
  deviceKeyword?: string
  useType?: string
  submitTimeFrom?: string
  submitTimeTo?: string
  departmentId?: number
  borrowTimeout?: boolean
  transferred?: boolean
  /** 自定义申请类型 id（，仅自定义工单有值） */
  applyTypeId?: number
}

/**
 * 使用记录导出筛选（与「使用记录」列表接口同口径）
 *
 * 注意：这里的 scope 是「查看维度」（DEVICE / USER），
 * 与顶层 ExportRequest.scope 的「导出范围」（MINE / ALL）不是一回事，切勿混用。
 */
export interface ExportUsageFilter {
  /** 视角：DEVICE 按设备 / USER 按员工；为空表示全部 */
  scope?: string
  /** 目标 ID：scope=DEVICE 时为设备 ID，scope=USER 时为员工 ID */
  targetId?: number
  keyword?: string
  status?: string
  useType?: string
  /** 提交时间起（含），形如 2026-09-01 00:00:00 */
  startTime?: string
  /** 提交时间止（含），形如 2026-09-30 23:59:59 */
  endTime?: string
}

/**
 * 操作日志导出筛选（P2）
 *
 * 字段与「操作日志」列表页的筛选逐个对应（模块 / 动作 / 结果 / 操作人 / 时间范围），
 * 后端由**同一处条件构造**（`OperationLogQuerySupport`）消费 ——
 * 这样「页面筛出来的」与「导出文件里的」必然是同一批数据，
 * 不会出现「导出比列表多几行」这种只能靠猜的问题。
 */
export interface ExportLogFilter {
  module?: string
  action?: string
  result?: string
  operatorName?: string
  /** 起始时间（含），形如 2026-09-01 00:00:00 */
  startTime?: string
  /** 结束时间（含），形如 2026-09-30 23:59:59 */
  endTime?: string
}

/**
 * 自定义表单数据导出筛选（ · M6）
 *
 * `applyTypeId` **必填**：一次只导一个申请类型。这不是为了简化实现，
 * 而是列爆炸的唯一有效闸门 —— 导出的列 = 该类型下历史表单版本的字段并集，
 * 跨类型导出会把租户里所有类型的字段累加成一宽表。后端缺省时直接拒绝
 * （`EXPORT_QUERY_INVALID`），前端因此也把它当作必填来校验。
 */
export interface ExportCustomFormFilter {
  /** 申请类型 id（必填） */
  applyTypeId: number
  /** 工单状态码，空表示不限 */
  status?: string
  /** 提交时间起（含） */
  submitTimeFrom?: string
  /** 提交时间止（含当天） */
  submitTimeTo?: string
}

/** 导出请求体：导出类型 + 条件（与后端 ExportRequest 结构一致） */
export interface ExportRequest {
  type: ExportTypeCode
  /** MINE 我的 / ALL 全部；非管理员时后端强制按 MINE 处理（仅工单导出使用） */
  scope?: 'MINE' | 'ALL'
  device?: ExportDeviceFilter
  order?: ExportOrderFilter
  usage?: ExportUsageFilter
  customForm?: ExportCustomFormFilter
  /** 操作日志筛选（P2，仅导出类型为 LOG 时使用） */
  log?: ExportLogFilter
  /** 报表导出的年份 */
  year?: number
  /** 报表导出的月份 1-12 */
  month?: number
}

/** 导出受理结果 */
export interface ExportResult {
  mode: ExportMode
  taskId: number
  exportType: ExportTypeCode
  exportTypeLabel: string
  totalRows: number
  fileName?: string
  fileSize?: number
  downloadUrl?: string
  message?: string
}

/** 导出记录 */
export interface ExportTaskItem {
  id: number
  /** 发起人 ID（管理员查看全部记录时区分归属） */
  requesterId?: number
  /** 发起人显示名 */
  requesterName?: string
  exportType: ExportTypeCode
  exportTypeLabel: string
  status: ExportStatusCode
  statusLabel: string
  fileName?: string
  fileSize?: number
  totalRows?: number
  errorMessage?: string
  expireAt?: string
  finishedAt?: string
  createdAt?: string
  downloadUrl?: string
}

/** 导出类型下拉项 */
export const EXPORT_TYPE_OPTIONS: ReadonlyArray<{ value: ExportTypeCode; label: string }> = [
  { value: 'DEVICE', label: '设备台账' },
  { value: 'ORDER', label: '工单记录' },
  { value: 'USAGE', label: '使用记录' },
  { value: 'CUSTOM_FORM', label: '自定义表单数据' },
  { value: 'LOG', label: '操作日志' },
  { value: 'REPORT_DEVICE_USAGE', label: '借用频次报表' },
  { value: 'REPORT_APPROVAL_EFFICIENCY', label: '审批时效报表' },
  { value: 'REPORT_DEVICE_FAULT', label: '故障统计报表' }
]

/** 导出类型中文名（未知类型原样返回，避免界面出现空白） */
export function exportTypeLabel(code?: string | null): string {
  if (!code) {
    return ''
  }
  return EXPORT_TYPE_OPTIONS.find((item) => item.value === code)?.label ?? code
}

/** 导出状态标签色（Element Plus tag type） */
export function exportStatusTagType(status?: string | null): 'success' | 'info' | 'warning' | 'danger' {
  switch (status) {
    case 'SUCCESS':
      return 'success'
    case 'FAILED':
      return 'danger'
    case 'RUNNING':
      return 'warning'
    default:
      return 'info'
  }
}

/** 文件大小可读化（导出记录里展示，避免一串字节数） */
export function formatFileSize(bytes?: number | null): string {
  if (bytes == null || bytes <= 0) {
    return '-'
  }
  if (bytes < 1024) {
    return `${bytes} B`
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`
  }
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`
}

/** 是否报表类导出（报表不需要 scope / 记录类筛选条件） */
export function isReportExport(code?: string | null): boolean {
  return !!code && code.startsWith('REPORT_')
}

/**
 * 是否为「一次只导一个申请类型」的导出
 *
 * 供界面判断是否必须打开专用对话框（要选类型）而不是直接带条件发起。
 */
export function requiresApplyType(code?: string | null): boolean {
  return code === 'CUSTOM_FORM'
}
