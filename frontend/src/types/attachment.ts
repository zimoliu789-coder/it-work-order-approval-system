/**
 * 附件通用能力类型（ 附件上传通用能力）
 *
 * 与后端 `com.enterprise.ticket.common.constant.AttachmentBizType` 一一对应。
 *
 * <b>职责边界</b>：前端只负责「即时提示 + 交互」，真正的类型 / 大小 / 数量 / 权限校验
 * 一律以后端为准。此处的 accept 常量与大小上限仅用于在用户选错文件的当下就给出反馈，
 * 避免「上传 → 等待 → 被后端拒绝」的糟糕体验；即便前端被绕过，后端仍会二次拦截。
 */

/** 附件关联业务类型码（与后端枚举同名） */
export type AttachmentBizTypeCode =
  | 'APPLY_ATTACHMENT'
  | 'REJECT_ATTACHMENT'
  | 'RETURN_PHOTO'
  | 'FAULT_PHOTO'
  /** 自定义工单附件：自定义申请表单里「附件上传 / 图片上传」字段随单上传的材料 */
  | 'CUSTOM_ORDER'

/** 附件列表项（对应后端 AttachmentVO；不含磁盘路径等实现细节） */
export interface AttachmentItem {
  id: number
  bizType: AttachmentBizTypeCode | string
  bizTypeLabel?: string | null
  bizId: number
  fileName: string
  /** 文件大小（字节） */
  fileSize?: number | null
  contentType?: string | null
  uploaderId?: number | null
  uploaderName?: string | null
  createdAt?: string | null
  /** 是否图片（后端判定，前端据此决定渲染缩略图还是文件卡片） */
  image?: boolean | null
  /** 下载地址（后端鉴权；前端图片预览会再拼 inline=true） */
  downloadUrl?: string | null
}

/**
 * 文档类白名单（含图片），与后端 `app.attachment.allowed-extensions` 保持一致（小写、不含点）。
 *
 * 后端可通过环境变量覆盖该列表；前端此处只作默认镜像，用于即时校验与提示。
 */
export const ATTACHMENT_ALLOWED_EXTS: readonly string[] = [
  'pdf',
  'doc',
  'docx',
  'xls',
  'xlsx',
  'ppt',
  'pptx',
  'txt',
  'csv',
  'png',
  'jpg',
  'jpeg',
  'gif',
  'bmp',
  'webp',
  'zip',
  'rar',
  '7z'
]

/** 图片扩展名，与后端 `app.attachment.image-extensions` 保持一致 */
export const ATTACHMENT_IMAGE_EXTS: readonly string[] = ['png', 'jpg', 'jpeg', 'gif', 'bmp', 'webp']

/** 附件业务类型配置 */
export interface AttachmentBizConfig {
  code: AttachmentBizTypeCode
  label: string
  /** 仅允许图片（归还照片 / 故障照片） */
  imageOnly: boolean
  /** input accept 值 */
  accept: string
}

function toAccept(exts: readonly string[]): string {
  return exts.map((ext) => `.${ext}`).join(',')
}

/** 四类附件业务配置：与后端 AttachmentBizType 的 label / imageOnly 语义完全对齐 */
export const ATTACHMENT_BIZ_CONFIG: Record<AttachmentBizTypeCode, AttachmentBizConfig> = {
  APPLY_ATTACHMENT: {
    code: 'APPLY_ATTACHMENT',
    label: '申请附件',
    imageOnly: false,
    accept: toAccept(ATTACHMENT_ALLOWED_EXTS)
  },
  REJECT_ATTACHMENT: {
    code: 'REJECT_ATTACHMENT',
    label: '驳回附件',
    imageOnly: false,
    accept: toAccept(ATTACHMENT_ALLOWED_EXTS)
  },
  RETURN_PHOTO: {
    code: 'RETURN_PHOTO',
    label: '归还照片',
    imageOnly: true,
    accept: toAccept(ATTACHMENT_IMAGE_EXTS)
  },
  FAULT_PHOTO: {
    code: 'FAULT_PHOTO',
    label: '故障照片',
    imageOnly: true,
    accept: toAccept(ATTACHMENT_IMAGE_EXTS)
  },
  // ：自定义工单附件（biz_id = orders.id）。与「申请附件」同为文档类白名单，
  // 但独立成类，便于详情页把自定义表单的附件与借用单的申请附件区分开。
  CUSTOM_ORDER: {
    code: 'CUSTOM_ORDER',
    label: '自定义工单附件',
    imageOnly: false,
    accept: toAccept(ATTACHMENT_ALLOWED_EXTS)
  }
}

const BIZ_LABELS: Record<string, string> = Object.fromEntries(
  Object.values(ATTACHMENT_BIZ_CONFIG).map((cfg) => [cfg.code, cfg.label])
)

/** 取附件业务配置；未知类型返回 null（调用方据此降级为不限制 accept） */
export function attachmentBizConfig(code?: string | null): AttachmentBizConfig | null {
  if (!code) {
    return null
  }
  return (ATTACHMENT_BIZ_CONFIG as Record<string, AttachmentBizConfig>)[code] ?? null
}

/** 取附件业务中文名；未知类型回落原值，避免展示层出现 undefined */
export function attachmentBizLabel(code?: string | null): string {
  if (!code) {
    return '附件'
  }
  return BIZ_LABELS[code] ?? code
}

/** 取小写扩展名（不含点）；无扩展名返回空串 */
export function attachmentExt(fileName?: string | null): string {
  if (!fileName) {
    return ''
  }
  const dot = fileName.lastIndexOf('.')
  if (dot < 0 || dot === fileName.length - 1) {
    return ''
  }
  return fileName.slice(dot + 1).toLowerCase()
}

/** 文件名扩展名是否在允许范围内（imageOnly=true 时只认图片扩展名） */
export function isAllowedAttachment(fileName: string, imageOnly: boolean): boolean {
  const ext = attachmentExt(fileName)
  if (!ext) {
    return false
  }
  const pool = imageOnly ? ATTACHMENT_IMAGE_EXTS : ATTACHMENT_ALLOWED_EXTS
  return pool.includes(ext)
}

/**
 * 人类可读的文件大小（B / KB / MB / GB / TB）。
 *
 * 用于附件列表展示；非法输入（null / 负数 / NaN）统一回落 '-'，避免出现 'NaN KB' 这类脏文案。
 */
export function formatFileSize(bytes?: number | null): string {
  if (bytes == null || Number.isNaN(bytes) || bytes < 0) {
    return '-'
  }
  if (bytes < 1024) {
    return `${bytes} B`
  }
  const units = ['KB', 'MB', 'GB', 'TB']
  let value = bytes / 1024
  let index = 0
  while (value >= 1024 && index < units.length - 1) {
    value /= 1024
    index += 1
  }
  let text = value.toFixed(value >= 100 ? 0 : 1)
  if (text.endsWith('.0')) {
    text = text.slice(0, -2)
  }
  return `${text} ${units[index]}`
}
