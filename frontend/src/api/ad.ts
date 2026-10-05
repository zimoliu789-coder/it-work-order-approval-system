import { http } from '@/api/request'
import type {
  AdAccountConvertVO,
  AdConfigPayload,
  AdConfigVO,
  AdDnPreviewPayload,
  AdDnPreviewVO,
  AdSyncResultVO,
  AdTestResultVO
} from '@/types/ad'

/**
 * AD 域控接口（； / 一.2 / 一.4）
 *
 * 全部端点仅 super_admin（后端权限码 `ad:view` / `ad:manage`，且这两个码
 * 刻意不授予 admin 角色）。前端隐藏菜单只是 UI 体验，越权直调一律 403。
 *
 * 注意「测试连接」的语义：它**不抛异常**，而是返回 `ok=false` + 失败原因 ——
 * 失败原因是这个工具最有价值的输出，用异常表达会丢掉那些细节。
 */
export const adApi = {
  /** 读取 AD 配置（绑定密码脱敏为 `****`） */
  config() {
    return http.get<AdConfigVO>('/ad/config')
  },

  /** 保存 AD 配置（绑定密码留空表示保持不变） */
  save(payload: AdConfigPayload) {
    return http.put<AdConfigVO>('/ad/config', payload)
  },

  /** 测试连接（连通性 + 绑定认证 + 基础 DN 下探测一条） */
  testConnection(payload: AdConfigPayload) {
    return http.post<AdTestResultVO>('/ad/test-connection', payload)
  },

  /**
   * 预览「基础 DN / 绑定 DN 自动推导」的结果（；）
   *
   * 纯计算、无副作用：与保存走**同一份后端规则**，因此界面显示的推导结果
   * 就是最终落库的值。主表单只需填「域服务器地址 / 域管理员账号」两项，
   * 剩下的交给它显示出来给维护人员过目。
   */
  deriveDn(payload: AdDnPreviewPayload) {
    return http.post<AdDnPreviewVO>('/ad/derive-dn', payload)
  },

  /** 立即同步域用户（增量） */
  sync() {
    return http.post<AdSyncResultVO>('/ad/sync')
  },

  /**
   * AD 域账号 → 本地账号
   *
   * 返回体里的临时口令只出现这一次，页面必须把它显式展示给管理员（不可静默关闭）。
   */
  convertToLocal(userId: number) {
    return http.post<AdAccountConvertVO>(`/ad/accounts/${userId}/convert-to-local`)
  },

  /** 本地账号 → AD 域账号（转换前会真的去域控确认该登录名存在） */
  convertToLdap(userId: number) {
    return http.post<AdAccountConvertVO>(`/ad/accounts/${userId}/convert-to-ldap`)
  }
}

export default adApi
