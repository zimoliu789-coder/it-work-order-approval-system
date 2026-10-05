import { http } from '@/api/request'
import type { RoleItem, RolePermissionPayload, RoleSavePayload } from '@/types/permission'

/**
 * 角色与权限接口（需求方三波·第一波·）
 *
 * 权限：查看需 role:view、写操作需 role:manage，默认只授予 super_admin。
 */
export const roleApi = {
  /** 角色列表（含使用人数 / 权限数量） */
  list() {
    return http.get<RoleItem[]>('/roles')
  },

  /** 可分配角色下拉项（员工管理分配角色用；需 staff:view） */
  options() {
    return http.get<RoleItem[]>('/roles/options')
  },

  /** 角色详情（含权限明细） */
  detail(code: string) {
    return http.get<RoleItem>(`/roles/${code}`)
  },

  /** 新建角色 */
  create(data: RoleSavePayload) {
    return http.post<RoleItem>('/roles', data)
  },

  /** 编辑角色（内置角色不可改编码；super_admin 权限不可改） */
  update(code: string, data: RoleSavePayload) {
    return http.put<RoleItem>(`/roles/${code}`, data)
  },

  /** 覆盖式授予权限 */
  setPermissions(code: string, payload: RolePermissionPayload) {
    return http.put<{ affected: number }>(`/roles/${code}/permissions`, payload)
  },

  /** 删除角色（内置角色 / 仍被员工使用的角色不可删除） */
  remove(code: string) {
    return http.delete<void>(`/roles/${code}`)
  }
}

export default roleApi
