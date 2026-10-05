import type { LogOption } from '@/types/system'

/**
 * 异常日志—— 与后端 `module/system` 的 ExceptionLogVO / ExceptionLogDetailVO 逐字镜像。
 *
 * <p>分类与分级都是「编码 + 中文标签」成对下发：编码给筛选值与颜色映射用，
 * 标签给用户看。只下发编码会逼前端复制一份中文映射表，而两份映射必然漂移
 * （后端新增一个分类后，前端下拉里没有它，用户以为不存在这类异常）。
 */
export interface ExceptionLogItem {
  id: number
  /** 请求链路 id，与响应体 traceId 同源 */
  traceId: string | null
  /** 分类编码：DATABASE / NETWORK / THIRD_PARTY / PARAM / BUSINESS / UNKNOWN */
  category: string
  categoryLabel: string
  /** 分级短码：P0 / P1 / P2 */
  severity: string
  severityLabel: string
  module: string | null
  exceptionClass: string
  message: string | null
  requestUri: string | null
  httpMethod: string | null
  userId: number | null
  ip: string | null
  occurredAt: string
  /** PENDING 待告警 / SENT 已告警 / SUPPRESSED 未告警 */
  alertState: string
  alertedAt: string | null
}

/** 详情 = 列表行 + 完整堆栈（列表刻意不带堆栈：一次事故上万行，接口会变成几十 MB） */
export interface ExceptionLogDetail {
  item: ExceptionLogItem
  stackTrace: string | null
}

export interface ExceptionLogQuery {
  page: number
  size: number
  category: string
  severity: string
  alertState: string
  keyword: string
  startTime: string
  endTime: string
}

/** 顶部概览计数 */
export interface ExceptionLogStats {
  pending: number
  sent: number
  suppressed: number
  total: number
}

/** 筛选下拉选项（由后端枚举生成，前端不硬编码） */
export interface ExceptionLogOptions {
  categories: LogOption[]
  severities: LogOption[]
  alertStates: LogOption[]
}

/**
 * 分级 → Element Plus 标签色。
 *
 * <p>抽成纯函数是为了能被单测覆盖：分级色是「一眼看出严重程度」的唯一视觉线索，
 * 写错（例如把 P0 也映射成灰色）不会报错，只会让最该被看到的那些行看起来最不起眼。
 *
 * <p>刻意**不用**红色表示 P2：真正的红色必须稀缺，否则到处都是红的，红色就失去了意义。
 */
export function severityTagType(severity: string | null | undefined): 'danger' | 'warning' | 'info' {
  if (severity === 'P0') {
    return 'danger'
  }
  if (severity === 'P1') {
    return 'warning'
  }
  return 'info'
}

/** 告警状态 → 中文标签（后端下发的是编码，这里只做展示翻译） */
export function alertStateLabel(state: string | null | undefined): string {
  switch (state) {
    case 'PENDING':
      return '待告警'
    case 'SENT':
      return '已告警'
    case 'SUPPRESSED':
      return '未告警'
    default:
      return state ?? '-'
  }
}

/**
 * 「未告警」的原因说明。
 *
 * <p>「未告警」这三个字本身会让人不安（是不是漏发了？），因此把它拆成可解释的两种：
 * 总开关关闭 / 分类被忽略 ⇒ 策略如此；其余 ⇒ 静默期内合并（次数照常累计）。
 * 不给说明的话，管理员只能靠猜。
 */
export function alertStateHint(state: string | null | undefined): string {
  switch (state) {
    case 'PENDING':
      return '等待汇总发送'
    case 'SENT':
      return '已通过站内消息与邮件通知超管'
    case 'SUPPRESSED':
      return '按策略未告警（总开关关闭或该分类被忽略）'
    default:
      return ''
  }
}
