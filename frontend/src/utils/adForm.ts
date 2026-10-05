/**
 * AD 域控配置表单的纯逻辑（；需求文档 五·）
 *
 * 为什么抽成纯函数：本次的全部价值在于「把 11 个技术字段压成 4 个必填项」，
 * 而压缩过程中最容易出错的两件事都落在**表单状态与载荷组装**上：
 * <ul>
 *   <li>「留空」与「填了」的语义必须精确 —— {@code baseDn} 留空表示「请系统推导」，
 *       若组装载荷时把空串当值传下去，服务端就再也不会推导，界面显示的值与落库的值当场分家；</li>
 *   <li>「自动推导出来的基础 DN 不算手工指定」—— 这条规则决定维护人员改了域地址之后
 *       基础 DN 会不会跟着变。它必须有单测钉住，否则只会在「换域控」那天才被发现。</li>
 * </ul>
 * 与 `utils/orderForm.ts`、`utils/deviceForm.ts` 同一约定：组件只负责把事件接到这些规则上。
 */
import { AD_DEFAULTS, type AdConfigPayload, type AdConfigVO } from '@/types/ad'

/** 用户搜索过滤器默认值（与后端 `AdConnection.DEFAULT_FILTER` 一致） */
export const DEFAULT_AD_USER_FILTER = '(&(objectClass=user)(sAMAccountName={0}))'

/**
 * 表单状态。
 *
 * 刻意<b>不复用</b> `AdConfigPayload`：后者的 `baseDn` / `bindDn` / `attrPhone` 允许 `null`
 * （那是「与后端约定的空值语义」），而表单控件只接受字符串 ——
 * 两者的差异正是 {@link buildAdPayload} 存在的理由，混用会让「空串 vs null」的语义在组件里飘。
 */
export interface AdFormState {
  enabled: boolean
  serverUrls: string
  serverPort: number
  useSsl: boolean
  strictCert: boolean
  /** 自定义基础 DN：留空 = 由域地址自动推导（主流程不走这里） */
  baseDn: string
  /** 域管理员账号 / 自定义绑定 DN：同一份值，主表单与高级选项两处视图 */
  bindDn: string
  /** 绑定密码；留空 = 保持原值不变 */
  bindPassword: string
  userFilter: string
  attrLogin: string
  attrName: string
  attrEmail: string
  /** 手机号属性映射；留空 = 不同步手机号 */
  attrPhone: string
  attrDept: string
  attrStatus: string
  defaultRole: string
  connectTimeoutSeconds: number
  syncEnabled: boolean
  syncHour: number
}

/** 表单初始值（未加载配置前的默认态） */
export function emptyAdForm(): AdFormState {
  return {
    enabled: false,
    serverUrls: '',
    serverPort: AD_DEFAULTS.port,
    useSsl: false,
    strictCert: true,
    baseDn: '',
    bindDn: '',
    bindPassword: '',
    userFilter: DEFAULT_AD_USER_FILTER,
    attrLogin: AD_DEFAULTS.attrLogin,
    attrName: AD_DEFAULTS.attrName,
    attrEmail: AD_DEFAULTS.attrEmail,
    attrPhone: AD_DEFAULTS.attrPhone,
    attrDept: AD_DEFAULTS.attrDept,
    attrStatus: AD_DEFAULTS.attrStatus,
    defaultRole: 'user',
    connectTimeoutSeconds: AD_DEFAULTS.timeoutSeconds,
    syncEnabled: false,
    syncHour: AD_DEFAULTS.syncHour
  }
}

/**
 * 用服务端配置回填表单。
 *
 * @param vo            服务端配置（`GET /api/ad/config`）
 * @param derivedBaseDn **仅**由域地址推导出来的基础 DN（不含任何人工值），
 *                      用于识别「库里那个值是系统填的还是人工填的」
 * @returns 全新的表单状态（不修改入参，便于单测）
 */
export function fillAdForm(vo: AdConfigVO, derivedBaseDn: string | null): AdFormState {
  const base = emptyAdForm()
  // 「自动推导出来的值不算手工指定」：库里的基础 DN 恰好等于当前域地址的推导结果时，
  // 表单留空 —— 于是维护人员把域地址从 dc01 改成 dc02，基础 DN 会跟着重新推导。
  // 反过来说，人工在高级选项里填过的值（与推导结果不同）必须原样保留、绝不被改写。
  const storedBaseDn = (vo.baseDn ?? '').trim()
  const autoDerived = derivedBaseDn == null ? '' : derivedBaseDn.trim()
  const isCustomBaseDn = storedBaseDn !== '' && storedBaseDn !== autoDerived

  return {
    enabled: vo.enabled === true,
    serverUrls: vo.serverUrls ?? '',
    serverPort: vo.serverPort ?? base.serverPort,
    useSsl: vo.useSsl === true,
    strictCert: vo.strictCert !== false,
    baseDn: isCustomBaseDn ? storedBaseDn : '',
    bindDn: vo.bindDn ?? '',
    // 服务端只回掩码，绝不回明文：表单留空即表示「保持原值」
    bindPassword: '',
    userFilter: vo.userFilter ?? DEFAULT_AD_USER_FILTER,
    attrLogin: vo.attrLogin ?? base.attrLogin,
    attrName: vo.attrName ?? base.attrName,
    attrEmail: vo.attrEmail ?? base.attrEmail,
    attrPhone: vo.attrPhone ?? '',
    attrDept: vo.attrDept ?? base.attrDept,
    attrStatus: vo.attrStatus ?? base.attrStatus,
    defaultRole: vo.defaultRole ?? base.defaultRole,
    connectTimeoutSeconds: vo.connectTimeoutSeconds ?? base.connectTimeoutSeconds,
    syncEnabled: vo.syncEnabled === true,
    syncHour: vo.syncHour ?? base.syncHour
  }
}

/**
 * 组装保存 / 测试请求的载荷。
 *
 * <p><b>空白一律落 {@code null}</b>：这是「留空 = 交给系统推导」的物理载体。
 * 若把空串原样传下去，服务端会认为「维护人员明确要求基础 DN 为空」，
 * 推导就不会发生 —— 而界面上的预览行显示的是推导结果，两边当场对不上。
 */
export function buildAdPayload(form: AdFormState): AdConfigPayload {
  return {
    enabled: form.enabled,
    serverUrls: form.serverUrls.trim(),
    serverPort: form.serverPort,
    useSsl: form.useSsl,
    strictCert: form.strictCert,
    baseDn: form.baseDn.trim() || null,
    bindDn: form.bindDn.trim() || null,
    bindPassword: form.bindPassword,
    userFilter: form.userFilter.trim(),
    attrLogin: form.attrLogin.trim(),
    attrName: form.attrName.trim(),
    attrEmail: form.attrEmail.trim(),
    attrPhone: form.attrPhone.trim() || null,
    attrDept: form.attrDept.trim(),
    attrStatus: form.attrStatus.trim(),
    defaultRole: form.defaultRole,
    connectTimeoutSeconds: form.connectTimeoutSeconds,
    syncEnabled: form.syncEnabled,
    syncHour: form.syncHour
  }
}

/**
 * 启用状态下的必填项校验。
 *
 * <p>刻意<b>不含</b>基础 DN 与绑定 DN：它们由系统推导，要求维护人员填等于把
 * 「简化成 4 项」又还回去。真正推不出来时（域地址是 IP），由服务端的完整性校验
 * 给出「基础 DN 缺失 + 去高级选项填」的明确提示。
 *
 * @param passwordConfigured 库中是否已有绑定密码（留空表单不代表没配）
 * @returns 第一处缺失项的提示文案；全部齐备时返回 {@code null}
 */
export function firstMissingField(form: AdFormState, passwordConfigured: boolean): string | null {
  if (!form.serverUrls.trim()) {
    return '请填写「域服务器地址」，例如 dc01.company.com 或 192.168.1.10'
  }
  if (!form.bindDn.trim()) {
    return '请填写「域管理员账号」，例如 company\\query 或 query@company.com'
  }
  if (!form.bindPassword && !passwordConfigured) {
    return '请填写「域管理员密码」'
  }
  return null
}

/**
 * 「上次同步结果」留痕文本是否表示成功。
 *
 * <p>留痕文本形如「共同步 12 个域账号：新建 1 个、更新 2 个、禁用 0 个、无变化 9 个」，
 * 有失败时末尾追加「，失败 N 个（详见服务端日志）」。
 * 之所以要解析文本而不是让后端多返回一个字段：该文本是<b>历史数据的唯一载体</b>
 * （同步发生在过去的某次请求里，结果只落在 `ad_config.last_sync_result`），
 * 补一个结构化列属于为展示改表，代价与收益不成比例。
 *
 * @returns 无记录时返回 {@code null}（界面上显示「尚未同步」）
 */
export function lastSyncSucceeded(text: string | null | undefined): boolean | null {
  if (!text) {
    return null
  }
  return !/失败\s*[1-9]/.test(text)
}
