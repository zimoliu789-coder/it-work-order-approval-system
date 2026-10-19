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
 * 设备分类下拉项（V59：普通员工借用单要选分类）。
 *
 * <p>与 {@link DeviceCategory} 的区别：**没有 deviceCount 与 remark** ——
 * 那两个是管理面信息，提交页不该看到「这个分类下有多少台设备」。
 */
export interface DeviceCategoryOption {
  id: number
  categoryName: string
  /** 父分类ID；0 表示一级分类 */
  parentId: number
  /** 1 一级 / 2 二级 */
  level: 1 | 2
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
  /** 型号原始文本（历史/可读原文；V56 起不参与任何比对） */
  model?: string | null
  /** 型号字典 id（V56 / R3）；为空表示「型号待整理」 */
  modelId?: number | null
  /** 型号字典名称（modelId 有值时才下发） */
  modelName?: string | null
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
  /**
   * 负责人（V57 / 第 10 项）：资产归属责任人，长期固定，由资产管理员维护。
   *
   * ⚠️ 与「借用人」是两件事：借用人见 `usage.userName`（来自占用中的工单）。
   * 报修页「我名下的设备」按 `ownerId` 过滤；为空表示未指定，只能走「公共设备」入口。
   */
  ownerId?: number | null
  ownerName?: string | null
  status: DeviceStatusCode
  statusLabel: string
  /** 是否可被新建借用申请（仅 AVAILABLE 为 true） */
  applicable: boolean
  /** 当前使用信息（占用中的工单） */
  usage?: DeviceUsage | null
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
  /** 型号原始文本（历史字段；新逻辑请用 modelId） */
  model: string | null
  /** 型号字典 id（V56 / R3）；传了就以字典为准，服务端会把 model 文本一并写成字典名 */
  modelId?: number | null
  serialNo: string | null
  storageLocation: string | null
  purchaseDate: string | null
  /** 设备金额（元）；null = 未录入（服务端按不超过阈值处理） */
  amount: number | null
  /** 负责人 user_id（V57 / 第 10 项）；null = 未指定 */
  ownerId?: number | null
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

// ---------------------------------------------------------------------------
// 设备型号字典（V56 / R3）
// ---------------------------------------------------------------------------

/** 型号状态（停用 ≠ 删除：既有设备与历史工单的关联保持不变） */
export type DeviceModelStatusCode = 'ENABLED' | 'DISABLED'

/** 型号管理列表项 */
export interface DeviceModelItem {
  id: number
  modelName: string
  brand?: string | null
  status: DeviceModelStatusCode
  statusLabel: string
  /** 当前关联该型号的设备台数（停用前的影响面参考，不构成阻断） */
  deviceCount?: number
  remark?: string | null
  createdAt?: string
  updatedAt?: string
}

/** 型号下拉项（只含启用型号；借用表单与台账共用） */
export interface DeviceModelOption {
  id: number
  modelName: string
  brand?: string | null
}

/** 型号新增 / 编辑请求（不含状态，启停走独立接口） */
export interface DeviceModelPayload {
  modelName: string
  brand: string | null
  remark: string | null
}

/** 「从台账补齐型号字典」的执行结果 */
export interface DeviceModelSyncResult {
  createdCount: number
  boundCount: number
  skippedCount: number
}

/** 型号状态筛选选项 */
export const DEVICE_MODEL_STATUS_OPTIONS: Array<{ value: DeviceModelStatusCode; label: string }> = [
  { value: 'ENABLED', label: '启用' },
  { value: 'DISABLED', label: '停用' }
]

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

/**
 * 故障记录状态码（V50 起为处理流程五态）。
 *
 * ⚠️ 取值是**后端已落库的字符串**，与中文标签不是一回事：
 * `PENDING_REPAIR` 界面上叫「待处理」（V50 之前叫「待维修」），
 * 改名要写数据迁移去扫历史行，收益只是名字好看 —— 因此枚举名一字未动。
 *
 * 处理链路：`PENDING_REPAIR`（待处理）→ `IN_PROGRESS`（处理中）
 * → `REPAIRED`（已修复）/ `UNREPAIRABLE`（无法修复）；
 * `SCRAPPED`（已报废）是**独立的终态**，与「无法修复」不是一回事
 * （前者是资产决策、后者是维修结论，见后端 FaultStatus 的类注释）。
 */
export type FaultStatusCode =
  | 'PENDING_REPAIR'
  | 'IN_PROGRESS'
  | 'REPAIRED'
  | 'UNREPAIRABLE'
  | 'SCRAPPED'

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

/**
 * 报修设备的「能不能报 / 关联哪张单」统一答案（V57 / 第 10 项）。
 *
 * <h2>为什么两个填报模式共用一个类型</h2>
 * 「我的设备」下拉与「公共设备」扫码看着不同，要回答的问题完全一样：
 * 这台设备叫什么、属于什么分类、当前能不能报修、报修时绑定哪张工单。
 * 各写一份类型，两边的「可报修」判定迟早分叉 ——
 * 表现是「下拉里能选、提交却被拒」这种最难解释的错。
 *
 * <h2>三个字段决定表单怎么走</h2>
 * - `reportable === false` ⇒ 直接禁用提交并展示 `reason`（后端已给出能照做的原因）；
 * - `orderId != null` ⇒ 我在用这台设备，提交时带上它走工单路径；
 * - `found === false` ⇒ 仅扫码查询会为 false（编号输错 / 扫空），不是错误。
 */
export interface FaultDeviceLookup {
  /** 是否查到设备（仅按资产编号查询时可能为 false） */
  found: boolean
  deviceId: number | null
  deviceName: string | null
  assetNo: string | null
  /** 一级分类名（确认「扫到的是不是那一类东西」的最短依据） */
  primaryCategoryName: string | null
  secondaryCategoryName: string | null
  deviceStatus: DeviceStatusCode | null
  deviceStatusLabel: string | null
  /** 只在「该设备正被我本人使用中」时有值 */
  orderId: number | null
  orderNo: string | null
  /** 当前登录用户能否就此设备提交报修 */
  reportable: boolean
  /** 不可报修的原因（可直接展示；可报修时为 null） */
  reason: string | null
}

/** 故障上报请求 */export interface DeviceFaultPayload {
  deviceId: number
  /** 工单内上报时携带；台账直接登记传 null */
  orderId: number | null
  faultDescription: string
  /** yyyy-MM-dd HH:mm:ss（不得晚于当前时间） */
  occurredAt: string
  /**
   * 当前所在分部 id（V54 / D5）。
   *
   * <p>报修<b>不产生工单</b>，分部只能落在故障记录上 —— 否则「各分部报修量」
   * 这类统计永远没有数据源。传 null / 不传 = 用服务端预填；
   * 传值必须是启用中的一级分部。来源由服务端判定，不要在前端自报。
   */
  currentDivisionId?: number | null
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
  /**
   * 「待我处理」：只看未完结、且没人接单或由我接单的报修。
   *
   * 由**服务端**过滤。本页是服务端分页的，前端过滤只能筛出「当前这一页里符合的几条」，
   * 总数与分页会一起失真（第 1 页 2 条、第 2 页 3 条，翻页时像在随机跳）。
   */
  onlyMine?: boolean
}

/** 故障状态筛选选项（顺序即处理流程的推进顺序） */
export const FAULT_STATUS_OPTIONS: Array<{ value: FaultStatusCode; label: string }> = [
  { value: 'PENDING_REPAIR', label: '待处理' },
  { value: 'IN_PROGRESS', label: '处理中' },
  { value: 'REPAIRED', label: '已修复' },
  { value: 'UNREPAIRABLE', label: '无法修复' },
  { value: 'SCRAPPED', label: '已报废' }
]

/** 未完结的故障状态（待处理 / 处理中）——「还能不能动手处理」的唯一判据 */
export const OPEN_FAULT_STATUSES: ReadonlyArray<FaultStatusCode> = ['PENDING_REPAIR', 'IN_PROGRESS']

/** 该故障是否仍未完结（可接单 / 可维修 / 可判定无法修复） */
export function isOpenFault(status: FaultStatusCode): boolean {
  return OPEN_FAULT_STATUSES.includes(status)
}

/**
 * 故障状态标签样式（：移动端与 PC 共用同一套语义色）。
 *
 * 配色语义：
 * - 「待处理」用警告色 —— 它是**需要有人动手**的状态，安静下来就等于没人管；
 * - 「处理中」用主色 —— 已有人接手，不需要再催；
 * - 「已修复」成功色；
 * - 「无法修复」中性灰 —— 它是一个**收尾结论**（这单修不了），不是错误，也不是报废；
 * - 「已报废」危险色（沿用 V50 之前的取值，避免既有呈现变化）。
 */
export function faultStatusTagType(
  status: FaultStatusCode
): 'primary' | 'success' | 'info' | 'warning' | 'danger' {
  switch (status) {
    case 'REPAIRED':
      return 'success'
    case 'PENDING_REPAIR':
      return 'warning'
    case 'IN_PROGRESS':
      return 'primary'
    case 'UNREPAIRABLE':
      return 'info'
    case 'SCRAPPED':
      return 'danger'
    default:
      return 'info'
  }
}
