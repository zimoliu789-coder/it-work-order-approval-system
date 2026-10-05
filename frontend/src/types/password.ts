import type { AccountAuthType } from '@/types/ad'

/**
 * 密码策略说明（ 收尾优化·）
 *
 * 对应后端 `GET /api/auth/password-policy`。
 *
 * 为什么由后端下发：策略阈值（最小长度 / 最少字符类数）来自可热改的系统参数，
 * 前端若写死一份说明，超管调整参数后页面就会「教一条已经过时的规则」——
 * 用户按提示输入仍被拒绝，只会得出「系统坏了」的结论。
 */
export interface PasswordPolicy {
  minLength: number
  maxLength: number
  minCharTypes: number
  weakPasswordChecked: boolean
  usernameRule: boolean
  /** 当前登录用户的账号来源；LDAP 时整页切换为「请在 AD 中修改密码」提示 */
  authType: AccountAuthType
  /** 逐条中文说明，直接渲染成列表 */
  rules: string[]
}
