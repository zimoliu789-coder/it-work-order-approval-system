/**
 * 借用申请表单的纯逻辑（ 引入 Vitest 的首批被测对象）
 *
 * 为什么单独抽成纯函数： 的 Critical 缺陷（编辑弹窗二级分类被联动静默清空）
 * 正落在**表单联动与校验**上，而当时前端没有任何自动化测试，回归只能靠人工点。
 * 把「日期 → 合法性」「锁 → 倒计时」等规则抽离出组件，
 * 既能被单测覆盖，也让组件只负责把事件接到这些规则上。
 *
 * <h2> 的收缩：三项化</h2>
 * 需求文档要求员工只填「借什么设备 / 还的日期 / 用途」三项，**借用类型从表单上拿掉**
 * （固定按短期借用处理，见 `types/order.ts` 的 `DEFAULT_USE_TYPE`）。由此产生三处删除：
 * <ul>
 *   <li>{@link OrderFormState} 去掉 `useType` —— 没有控件写它，唯一读者是「归还日期是否必填」，
 *       而该问题现在有了恒定答案；</li>
 *   <li>删除 `isExpectedReturnRequired(useType)` —— 恒为 true 的谓词只会让人以为还有分支；</li>
 *   <li>删除 `normalizeExpectedReturn(useType, value)` —— 「长期领用丢弃日期」的清理场景
 *       随长期领用入口一起消失，该函数退化为恒等映射。</li>
 * </ul>
 * <b>归还日期由「短期借用才必填」升级为「一律必填」</b>：后端
 * `OrderCreateRequest#expectedReturnDate` 已是 `@NotNull`，前端在这里同步收紧。
 */

/** 用途最大长度（与后端 `OrderCreateRequest#reason` 的 @Size 对齐） */
export const REASON_MAX_LENGTH = 500

export interface OrderFormState {
  /** 借什么设备（原「申请设备」； 的大白话标签） */
  deviceId: number | null
  /** 用途（原「借用原因」）：选填，一句话说明即可 */
  reason: string
  /** 还的日期（原「期望归还日期」）：必填 */
  expectedReturnDate: string | null
}

/** 本地「今天」，格式 yyyy-MM-dd（不使用 toISOString：那是 UTC，会跨时区偏一天） */
export function todayString(now: Date = new Date()): string {
  const year = now.getFullYear()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

/** yyyy-MM-dd 字符串比较即等价于日期先后比较（同格式、零填充），无需构造 Date */
export function isExpectedReturnBeforeToday(value: string, today: string = todayString()): boolean {
  return value < today
}

/**
 * 表单校验：返回错误信息列表（空数组表示通过）
 *
 * 前端校验只是体验优化，后端仍会重复校验一遍（ / ：
 * 权限与业务规则必须由后端强制，前端不构成防线）。
 */
export function validateOrderForm(form: OrderFormState, today: string = todayString()): string[] {
  const errors: string[] = []
  if (form.deviceId == null) {
    errors.push('请选择要借用的设备')
  }
  // 用途为选填：空白一律放行，只拦「超长」（后端也会拦一次，此处是即时反馈）
  const reason = (form.reason ?? '').trim()
  if (reason.length > REASON_MAX_LENGTH) {
    errors.push(`用途长度不能超过 ${REASON_MAX_LENGTH} 个字符`)
  }
  // 归还日期一律必填（ 起借用类型固定为短期借用，没有「无归还日期」这一支）
  if (!form.expectedReturnDate) {
    errors.push('请选择归还日期')
  } else if (isExpectedReturnBeforeToday(form.expectedReturnDate, today)) {
    errors.push('归还日期不能早于今天')
  }
  return errors
}

export function isOrderFormValid(form: OrderFormState, today: string = todayString()): boolean {
  return validateOrderForm(form, today).length === 0
}

/**
 * 解析后端返回的日期时间字符串
 *
 * 后端 `@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")` 输出的是 `2026-09-17 21:30:00`，
 * 这种带空格的形式在部分浏览器（Safari / 部分 iOS WebView）下 `new Date()` 会得到 Invalid Date，
 * 因此统一替换为 ISO 的 `T` 分隔形式再解析。
 */
export function parseDateTime(value: string | null | undefined): number {
  if (!value) {
    return Number.NaN
  }
  return new Date(value.replace(' ', 'T')).getTime()
}

/** 临时锁剩余秒数；已过期或无法解析返回 0（ 倒计时展示） */
export function remainingLockSeconds(expiresAt: string | null | undefined, now: number = Date.now()): number {
  const expires = parseDateTime(expiresAt)
  if (Number.isNaN(expires)) {
    return 0
  }
  return Math.max(0, Math.floor((expires - now) / 1000))
}

/** 倒计时文案：`m:ss`（如 4:05）；不足 1 分钟仍按 0:xx 显示，便于用户感知迫近 */
export function formatCountdown(totalSeconds: number): string {
  const safe = Math.max(0, Math.floor(totalSeconds))
  const minutes = Math.floor(safe / 60)
  const seconds = safe % 60
  return `${minutes}:${String(seconds).padStart(2, '0')}`
}

/** 锁是否已生效超时（倒计时归零即视为失效，前端应重新选设备） */
export function isLockExpired(expiresAt: string | null | undefined, now: number = Date.now()): boolean {
  return remainingLockSeconds(expiresAt, now) === 0
}
