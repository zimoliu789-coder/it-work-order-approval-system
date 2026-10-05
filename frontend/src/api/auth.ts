import { http } from '@/api/request'
import type {
  BindContactRequest,
  BindContactSendCodeRequest,
  BindContactSendCodeResult,
  ChangePasswordRequest,
  CsrfHeaderInfo,
  ForgotPasswordChannels,
  ForgotPasswordMeta,
  ForgotPasswordResetRequest,
  ForgotPasswordSendCodeRequest,
  ForgotPasswordSendCodeResult,
  HealthInfo,
  LoginRequest,
  LoginUserInfo
} from '@/types/api'
import type { PasswordPolicy } from '@/types/password'
import type { UserPasswordResetVO } from '@/types/user'

/**
 * 认证相关接口（ 菜单：提交申请前需登录、个人中心、修改密码、退出登录）
 */
export const authApi = {
  /**
   * 登录。账号字段同时接受「5 位以上纯数字登录名」与「中文姓名」（）。
   */
  login(payload: LoginRequest) {
    return http.post<LoginUserInfo>('/auth/login', payload)
  },

  /** 退出登录（服务端清除 HttpOnly Cookie） */
  logout() {
    return http.post<null>('/auth/logout')
  },

  /** 获取当前登录用户信息 */
  me() {
    return http.get<LoginUserInfo>('/auth/me')
  },

  /** 修改当前用户密码 */
  changePassword(payload: ChangePasswordRequest) {
    return http.post<null>('/auth/change-password', payload)
  },

  /**
   * 管理员重置指定员工密码（仅 super_admin）：临时口令由服务端生成并回传。
   *
   * 2026-09-20 ：不再由调用方指定口令（后端 `ResetPasswordRequest`
   * 的 `newPassword` 字段已移除），改为接收服务端生成的临时口令并展示给管理员。
   */
  resetPassword(payload: { userId: number }) {
    return http.post<UserPasswordResetVO>('/auth/reset-password', payload)
  },

  // ----------------------------------------------------------------
  // 找回密码（上线前需求 二）：三步走 —— meta → channels → sendCode → reset
  // ----------------------------------------------------------------

  /**
   * 找回密码功能元信息（**免鉴权**）。
   *
   * 登录页据此决定是否显示「无法登录？」入口：两个验证开关都关时 `enabled=false`，
   * 此时入口必须隐藏 —— 让用户点进一个永远发不出验证码的页面，比不给入口更糟。
   */
  forgotPasswordMeta() {
    return http.get<ForgotPasswordMeta>('/auth/forgot-password/meta')
  },

  /** 第一步：查询该账号可用的接收渠道（已按系统开关 + 绑定情况过滤） */
  forgotPasswordChannels(account: string) {
    return http.post<ForgotPasswordChannels>('/auth/forgot-password/channels', { account })
  },

  /** 第二步：按所选渠道下发验证码 */
  forgotPasswordSendCode(payload: ForgotPasswordSendCodeRequest) {
    return http.post<ForgotPasswordSendCodeResult>('/auth/forgot-password/send-code', payload)
  },

  /** 第三步：校验验证码并重置密码 */
  forgotPasswordReset(payload: ForgotPasswordResetRequest) {
    return http.post<null>('/auth/forgot-password/reset', payload)
  },

  /** 获取 CSRF 请求头规范（联调自检用） */
  csrf() {
    return http.get<CsrfHeaderInfo>('/auth/csrf')
  },

  /**
   * 当前生效的密码策略说明（）
   *
   * 密码管理页与强制改密页都用它渲染策略清单，保证「提示的规则」与「校验的规则」同源。
   */
  passwordPolicy() {
    return http.get<PasswordPolicy>('/auth/password-policy')
  },

  /**
   * 绑定 / 修改**自己**的手机号与邮箱（ 首次引导 / 四.3 个人中心自助改绑）。
   *
   * 走 `/api/profile` 前缀而不是 `/api/users`：后者整个前缀都要求管理权限，
   * 而「改自己的联系方式」是每个员工都必须能做、且不该依赖任何管理权限的事。
   * 入参里没有 userId —— 越权无从表达。
   *
   *  起，`phone` / `email` **相对库中值发生变化**时，必须同时带上对应渠道的验证码。
   */
  bindContact(payload: BindContactRequest) {
    return http.put<LoginUserInfo>('/profile/bind-contact', payload)
  },

  /**
   * 向「准备绑定的号码」发送验证码（ ）。
   *
   * 两步走的第一步：先证明新号码在当前用户手上，`bindContact` 提交时才需要带上验证码。
   * 目标值随码一起存在服务端，提交时逐字比对 —— 防止「用发给自己的码绑定别人的号码」。
   */
  sendBindContactCode(payload: BindContactSendCodeRequest) {
    return http.post<BindContactSendCodeResult>('/profile/bind-contact/send-code', payload)
  },

  /** 健康检查（用于确认前后端连通） */
  health() {
    return http.get<HealthInfo>('/health')
  }
}

export default authApi
