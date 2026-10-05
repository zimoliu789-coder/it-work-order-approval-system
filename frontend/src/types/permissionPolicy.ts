/**
 * 权限申请策略与「我的权限」—— 与后端 `PermissionPolicyController` 逐字镜像。
 */

/** 策略行：全部权限码的可申请性与风险等级 */
export interface PermissionPolicyItem {
  code: string
  name: string
  /** 是否开放申请 */
  applicable: boolean
  /** NORMAL 一级审批 / HIGH 需超管多走一级 */
  riskLevel: string
  /** 是否被表覆盖（界面据此提示「已自定义」） */
  riskLevelOverridden: boolean
  applicableOverridden: boolean
}

/** 我的附加权限（用户级授权） */
export interface MyPermissionItem {
  permCode: string
  /** APPLY 审批自动开通 / MANUAL 手工 */
  source: string
  orderId: number | null
  grantedAt: string
  revokedAt: string | null
  active: boolean
}

/** 更新请求（两个字段都可空 = 只改其中一个） */
export interface PermissionPolicyUpdate {
  applicable?: boolean
  riskLevel?: string
}

/** 码 → 风险等级（申请表单用来给高危项加提示） */
export type ApplicableRiskMap = Record<string, string>

/**
 * 风险等级的中文标签。
 *
 * <p>抽成纯函数是为了能被单测覆盖：这一列决定「这条要不要多走一级超管」，
 * 显示错了会让管理员以为某个高危码是普通的，从而把它放开申请。
 */
export function riskLevelLabel(riskLevel: string | null | undefined): string {
  return riskLevel === 'HIGH' ? '高危（两级）' : '普通（一级）'
}

/** 风险等级 → 标签色（只有高危用红色，红色必须稀缺） */
export function riskLevelTagType(riskLevel: string | null | undefined): 'danger' | 'info' {
  return riskLevel === 'HIGH' ? 'danger' : 'info'
}

/**
 * 给权限选项文案加高危标记。
 *
 * <p>申请人是在下拉里做选择的，提示必须出现在**做决定的那一刻** ——
 * 提交后才知道要多走一级审批，体验上是「被罚」，而不是「被提醒」。
 *
 * <p>用文案标记而不是颜色：下拉选项逐个染色需要自定义插槽，
 * 而文案标记在 PC / 移动端 / 只读回显里都成立（移动端的 Vant 下拉也一样）。
 */
export function decoratePermissionLabel(label: string, code: string, risks: ApplicableRiskMap): string {
  return risks[code] === 'HIGH' ? `${label}（高危 · 需两级审批）` : label
}
