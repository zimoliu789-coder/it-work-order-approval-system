/**
 * 借用申请表单的纯逻辑（ 引入 Vitest 的首批被测对象）
 *
 * 为什么单独抽成纯函数： 的 Critical 缺陷（编辑弹窗二级分类被联动静默清空）
 * 正落在**表单联动与校验**上，而当时前端没有任何自动化测试，回归只能靠人工点。
 * 把「日期 → 合法性」等规则抽离出组件，
 * 既能被单测覆盖，也让组件只负责把事件接到这些规则上。
 * （原先还有一组「临时锁 → 倒计时」的规则，已随 V56 删锁一并退役，见文末说明。）
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
  /**
   * 借哪一类设备（V56 / 第 9 项：由「挑一台具体设备」改为「挑一个类别」）。
   *
   * <p>申请人只声明「借哪一类 + 什么型号」，具体资产由 IT 交付扫码绑定 ——
   * 这样审批期间不会占住设备，也不会出现「锁了设备却没提交」的资源浪费。
   */
  primaryCategoryId: number | null
  /**
   * 二级分类（选填）。
   *
   * <p>**不进校验**：它是否必须归属所选一级分类，只有服务端知道（要查库）。
   * 前端只负责「一级变了就清空二级」，不在这里编造归属规则。
   */
  secondaryCategoryId?: number | null
  /**
   * 型号字典 id（选填）。
   *
   * <p>同样不进校验：留空的语义是「同类别内哪台都行」，是合法选择而非缺填。
   */
  modelId?: number | null
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
  if (form.primaryCategoryId == null) {
    errors.push('请选择要借用的设备类别')
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

// ----------------------------------------------------------------------
// 临时锁相关的纯函数已随 V56（第 9 项）整体删除
//
// 原来这里有 parseDateTime / remainingLockSeconds / formatCountdown / isLockExpired
// 四个函数，服务的是「选中设备 → 加 5 分钟临时锁 → 倒计时」这条链路。
// V56 起申请人不再挑具体资产（设备绑定延后到 IT 交付扫码），后端连
// locked_by / locked_at / lock_token 三列与 DeviceLockReleaseJob 都删了，
// 前端留着一套算不出东西的倒计时只会误导后来人。
//
// 注意：`parseDateTime` 的 Safari 兼容处理（空格换 T）在别处若需要，
// 请重新实现而不是从历史里翻 —— 它当时是专为锁到期时间写的。
// ----------------------------------------------------------------------
