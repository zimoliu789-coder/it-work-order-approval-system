import { http } from '@/api/request'
import type {
  ApplicableRiskMap,
  MyPermissionItem,
  PermissionPolicyItem,
  PermissionPolicyUpdate
} from '@/types/permissionPolicy'

/**
 * 权限申请策略与「我的权限」—— 对应后端 `PermissionPolicyController`。
 *
 * <h2>权限落点</h2>
 * - 读策略 / 改策略：`role:view` / `role:manage`（它就是「角色与权限」页的一块配置区）；
 * - 「码 → 风险等级」映射与「我的权限」：只要登录 ——
 *   普通员工没有 `role:view`，却需要在申请表单里看到高危提示。
 */
export const permissionPolicyApi = {
  /** 全部权限码的可申请性与风险等级（配置界面用） */
  list() {
    return http.get<PermissionPolicyItem[]>('/permission-policies')
  },

  /**
   * 可申请权限的「码 → 风险等级」映射（申请表单用）。
   *
   * <p>与 {@link list} 分开是因为权限不同：本方法只要求登录，
   * 让普通员工也能在申请表单里看到「高危 · 需两级审批」的提示。
   */
  applicableRisks() {
    return http.get<ApplicableRiskMap>('/permission-policies/applicable-risks')
  },

  /** 我的附加权限（含已撤销的历史行） */
  mine() {
    return http.get<MyPermissionItem[]>('/permission-policies/mine')
  },

  /** 修改某个权限码的可申请性 / 风险等级（仅 role:manage） */
  update(code: string, payload: PermissionPolicyUpdate) {
    return http.put<void>(`/permission-policies/${encodeURIComponent(code)}`, payload)
  }
}

export default permissionPolicyApi
