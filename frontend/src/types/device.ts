/**
 * 设备分类与设备台账类型（ / ， 起含临时锁与使用信息）
 */

import type { OrderStatusCode, OrderUseType } from './order'

/**
 * 设备状态码（ 设备状态机；原 BORROWED 已按需求方约定改名 IN_USE）
 *
 * <p>{@code LOST}（已丢失）为 P0 新增：丢失与报废是两件事 —— 丢失是暂时找不到，
 * 报废是确定不要了。混用会让「资产丢失率」永远算不出来。
 */
export type DeviceStatusCode =
  | 'AVAILABLE'
  | 'LOCKED'
  | 'IN_APPROVAL'
  | 'IN_USE'
  | 'MAINTENANCE'
  | 'LOST'
  | 'SCRAPPED'

/** 设备分类（一级 + 二级，） */
export interface DeviceCategory {
  id: number
  categoryName: string
  /** 父分类ID；0 表示一级分类 */
  parentId: number
  /** 1 一级 / 2 二级 */
  level: 1 | 2
  sortOrder: number
  remark: string | null
  /** 分类下有效设备数 */
  deviceCount: number
  /** 二级分类列表（仅一级分类有值） */
  children?: DeviceCategory[]
}

/**
 * 设备当前使用信息（需求方 ）
 *
 * 数据来自「占用中的工单」，因此 `orderStatus` 可能处于审批中/待交付/使用中。
 */
export interface DeviceUsage {
  orderId: number
  orderNo: string
  userId: number
  userName?: string | null
  useType: OrderUseType
  useTypeLabel: string
  /** 期望归还日期；仅短期借用有值（长期领用无固定归还日期） */
  expectedReturnDate?: string | null
  orderStatus: OrderStatusCode
  orderStatusLabel: string
}

/**
 * 设备台账（ / ）
 *
 * 注意：后端全局开启 `default-property-inclusion: non_null`，值为 null 的字段会被
 * **整条省略**，因此可空字段在前端表现为「键缺失」而非 null，统一声明为可选。
 */
export interface DeviceItem {
  id: number
  deviceName: string
  assetNo: string
  primaryCategoryId: number
  /** 一级分类名（外键保证存在；异常数据下可能缺失，展示层已做兜底） */
  primaryCategoryName?: string | null
  secondaryCategoryId?: number | null
  secondaryCategoryName?: string | null
  brand?: string | null
  model?: string | null
  serialNo?: string | null
  storageLocation?: string | null
  /** yyyy-MM-dd */
  purchaseDate?: string | null
  /**
   * 设备金额（元，选填）—— 借用审批「金额分档」的依据。
   *
   * 未录入时为 `null`，服务端按「不超过阈值」处理（即走三级流程），
   * 而不是当作 0 或无穷大：这是需求的明确口径，不要在前端把 null 兜成 0。
   */
  amount?: number | null
  status: DeviceStatusCode
  statusLabel: string
  /** 是否可被新建借用申请（仅 AVAILABLE 为 true） */
  applicable: boolean
  /** 当前使用信息（占用中的工单） */
  usage?: DeviceUsage | null
  /** 临时锁持有人姓名（仅 LOCKED 状态有值） */
  lockedByName?: string | null
  /** 临时锁到期时间（仅 LOCKED 状态有值） */
  lockExpiresAt?: string | null
  remark?: string | null
  createdAt: string
  updatedAt: string
}

/** 设备台账保存请求（不含状态，状态变更走独立接口） */
export interface DevicePayload {
  deviceName: string
  assetNo: string
  primaryCategoryId: number
  secondaryCategoryId: number | null
  brand: string | null
  model: string | null
  serialNo: string | null
  storageLocation: string | null
  purchaseDate: string | null
  /** 设备金额（元）；null = 未录入（服务端按不超过阈值处理） */
  amount: number | null
  remark: string | null
}

/** 设备台账查询条件 */
export interface DeviceQuery {
  page?: number
  size?: number
  keyword?: string
  primaryCategoryId?: number | null
  secondaryCategoryId?: number | null
  status?: DeviceStatusCode | null
}

/** 分类保存请求 */
export interface DeviceCategoryPayload {
  categoryName: string
  parentId: number | null
  remark: string | null
}

/** 临时锁信息 */
export interface DeviceLockInfo {
  deviceId: number
  deviceName: string
  lockToken: string
  lockedAt: string
  expiresAt: string
  timeoutMinutes: number
}

/** 状态筛选与展示选项（ 六态） */
export const DEVICE_STATUS_OPTIONS: Array<{ value: DeviceStatusCode; label: string }> = [
  { value: 'AVAILABLE', label: '可用' },
  { value: 'LOCKED', label: '临时锁定' },
  { value: 'IN_APPROVAL', label: '审批中' },
  { value: 'IN_USE', label: '使用中' },
  { value: 'MAINTENANCE', label: '维修中' },
  { value: 'LOST', label: '已丢失' },
  { value: 'SCRAPPED', label: '已报废' }
]

/**
 * 可**手工置入**的设备状态（P3 批量改状态的选项来源）
 *
 * 与 `DEVICE_STATUS_OPTIONS`（筛选 / 展示用，全 7 态）刻意不同：这里只放后端
 * `DeviceStatus.canManualTransfer` 白名单里允许作为目标的 4 个。
 *
 * **被排除的三态才是重点**：`LOCKED` / `IN_APPROVAL` / `IN_USE` 必须由工单流程驱动，
 * 手工置入等于绕过审批链路（例如把设备直接标成「使用中」，那台设备的工单永远收不回）。
 * 后端会逐台二次校验；前端只给这 4 个选项，是为了让用户少选错，不是安全边界。
 */
export const MANUAL_DEVICE_STATUS_OPTIONS: Array<{ value: DeviceStatusCode; label: string }> = [
  { value: 'AVAILABLE', label: '可用（维修完成 / 找回）' },
  { value: 'MAINTENANCE', label: '维修中' },
  { value: 'LOST', label: '已丢失' },
  { value: 'SCRAPPED', label: '已报废' }
]

/** 状态标签样式（：移动端与 PC 共用同一套语义色） */
export function deviceStatusTagType(status: DeviceStatusCode): 'success' | 'info' | 'warning' | 'danger' {
  switch (status) {
    case 'AVAILABLE':
      return 'success'
    case 'LOCKED':
      return 'warning'
    case 'MAINTENANCE':
      return 'warning'
    case 'LOST':
      // 丢失与报废同为「最需要被看见」的异常态，用同一语义色
      return 'danger'
    case 'SCRAPPED':
      return 'danger'
    default:
      return 'info'
  }
}

/**
 * 设备金额展示（元）—— 固定两位小数并加千分位。
 *
 * <p>刻意不用 `String(amount)`：后端返回的是数值，`6999.5` 直接渲染成「6999.5」，
 * 而台账里的金额应当与财务口径一致（两位小数）。未录入（null）由调用方决定文案 ——
 * 本函数只负责「有值时怎么显示」，避免在这里替业务决定 null 该叫「-」还是「未录入」。
 */
export function formatAmount(amount: number): string {
  return amount.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

// ---------------------------------------------------------------------------
// 设备批量导入（管理端）
// ---------------------------------------------------------------------------

/**
 * 导入行（与模板列一一对应）
 *
 * 注意：分类按「名称」填写，后端按名称匹配现有分类；日期为 yyyy-MM-dd 字符串。
 */
export interface DeviceImportRow {
  /** 源文件行号（1 基，用于失败明细定位） */
  rowNo: number
  deviceName: string
  assetNo: string
  /** 一级分类名称（必填，按名匹配） */
  primaryCategoryName: string
  /** 二级分类名称（选填） */
  secondaryCategoryName: string
  brand: string
  model: string
  serialNo: string
  storageLocation: string
  /** yyyy-MM-dd */
  purchaseDate: string
  /** 设备金额（元，选填；按原始文本回显，解析失败由服务端给出中文原因） */
  amount: string
  remark: string
}

/** 逐行校验结果（预览与失败明细复用） */
export interface DeviceImportRowResult extends DeviceImportRow {
  valid: boolean
  /** 校验失败原因（valid=false 时有值） */
  reason?: string | null
}

/** 上传校验预览（不做导入，供用户确认） */
export interface DeviceImportPreview {
  fileName: string
  totalCount: number
  successCount: number
  failCount: number
  rows: DeviceImportRowResult[]
}

/** 确认导入结果（失败行可下载明细后重导） */
export interface DeviceImportResult {
  importedCount: number
  failedCount: number
  failures: DeviceImportRowResult[]
}

/** 确认导入请求体 */
export interface DeviceImportExecutePayload {
  fileName: string
  rows: DeviceImportRow[]
}

/** 失败明细下载请求体 */
export interface DeviceImportFailureReportPayload {
  fileName: string
  rows: DeviceImportRowResult[]
}

// ---------------------------------------------------------------------------
// 设备故障上报（，）
// ---------------------------------------------------------------------------

/** 故障记录状态码：待维修 / 维修完成 / 已报废 */
export type FaultStatusCode = 'PENDING_REPAIR' | 'REPAIRED' | 'SCRAPPED'

/**
 * 故障记录
 *
 * 三个阶段共用同一张记录：借用人使用中上报、管理员台账直接登记、归还时登记故障。
 * 图片附件不在本阶段交付（随 通用附件能力统一实现）。
 */
export interface DeviceFaultItem {
  id: number
  deviceId: number
  deviceName?: string | null
  assetNo?: string | null
  /** 当前设备状态（列表直接判断设备能否再用） */
  deviceStatus?: DeviceStatusCode | null
  deviceStatusLabel?: string | null
  /** 关联工单；无工单直接登记时为空 */
  orderId?: number | null
  orderNo?: string | null
  reporterId?: number | null
  reporterName?: string | null
  faultDescription: string
  /** yyyy-MM-dd HH:mm:ss */
  occurredAt: string
  status: FaultStatusCode
  statusLabel: string
  handledBy?: number | null
  handledByName?: string | null
  handledAt?: string | null
  /** 处理说明（维修结果 / 报废原因） */
  handleRemark?: string | null
  // ---- P2 维修过程记录（仅「维修完成」有值）----
  /** 实际维修人用户 id；与 handledBy（登记人）区分，外送维修时为空 */
  repairerId?: number | null
  repairerName?: string | null
  /** 维修费用（元） */
  repairCost?: number | null
  /** 更换配件说明 */
  replacedParts?: string | null
  createdAt: string
}

/**
 * 故障可选设备（GET /api/device-faults/selectable-devices）
 *
 * 普通用户只拿到「本人使用中工单对应设备」（orderId 非空）；
 * admin / super_admin 额外拿到「可用设备」（orderId 为空）。
 * `label` 为后端拼好的下拉展示文案，前端直接用于 el-option 的 label。
 */
export interface DeviceFaultSelectable {
  deviceId: number
  deviceName: string
  assetNo: string
  deviceStatus: DeviceStatusCode
  deviceStatusLabel: string
  /** 关联工单ID；可用设备（台账直接登记）为 null */
  orderId: number | null
  orderNo: string | null
  /** 借用类型；可用设备为 null */
  useType: OrderUseType | null
  useTypeLabel: string | null
  /** 使用人姓名；可用设备为 null */
  applicantName: string | null
  /** 后端拼好的下拉展示文案，如「设备名（资产编号） · 使用中：张三」 */
  label: string
}

/** 故障上报请求 */
export interface DeviceFaultPayload {
  deviceId: number
  /** 工单内上报时携带；台账直接登记传 null */
  orderId: number | null
  faultDescription: string
  /** yyyy-MM-dd HH:mm:ss（不得晚于当前时间） */
  occurredAt: string
}

/** 故障处理请求（维修完成 / 报废共用） */
export interface DeviceFaultHandlePayload {
  remark: string | null
  // ---- P2：仅「维修完成」使用；报废不涉及这三项（后端也只在该分支落库）----
  repairerId?: number | null
  repairCost?: number | null
  replacedParts?: string | null
}

/** 故障记录查询条件 */
export interface DeviceFaultQuery {
  page?: number
  size?: number
  status?: FaultStatusCode | null
  /** 设备名称 / 资产编号（模糊） */
  deviceKeyword?: string
}

/** 故障状态筛选选项 */
export const FAULT_STATUS_OPTIONS: Array<{ value: FaultStatusCode; label: string }> = [
  { value: 'PENDING_REPAIR', label: '待维修' },
  { value: 'REPAIRED', label: '维修完成' },
  { value: 'SCRAPPED', label: '已报废' }
]

/** 故障状态标签样式（：移动端与 PC 共用同一套语义色） */
export function faultStatusTagType(status: FaultStatusCode): 'success' | 'info' | 'warning' | 'danger' {
  switch (status) {
    case 'REPAIRED':
      return 'success'
    case 'PENDING_REPAIR':
      return 'warning'
    case 'SCRAPPED':
      return 'danger'
    default:
      return 'info'
  }
}
