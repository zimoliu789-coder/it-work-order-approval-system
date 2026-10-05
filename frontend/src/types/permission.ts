/**
 * 角色与权限相关类型（需求方三波·第一波·）
 *
 * 与后端 `com.enterprise.ticket.common.permission.PermissionCatalog` /
 * `module.role.dto.vo.RoleVO` 对应。
 */

/** 权限节点类型：MENU 菜单权限 / ACTION 操作权限 */
export type PermType = 'MENU' | 'ACTION'

/** 权限目录节点（`GET /api/meta/permissions` 返回的树） */
export interface PermNode {
  /** 权限码，如 `device:ledger:view`；分组节点为 `group:xxx`（不可授予） */
  code: string
  name: string
  type: PermType
  /** 菜单路径（仅菜单权限有值，操作权限为空串） */
  menuPath: string
  children: PermNode[]
}

/** 数据权限范围选项（`GET /api/meta/data-scopes`） */
export interface DataScopeOption {
  code: 'ALL' | 'GROUP' | 'SELF'
  label: string
  description: string
}

/** 数据权限范围码 */
export type DataScopeCode = 'ALL' | 'GROUP' | 'SELF'

/** 角色（列表 / 详情） */
export interface RoleItem {
  roleCode: string
  roleName: string
  dataScope: DataScopeCode
  dataScopeLabel: string
  remark?: string | null
  /** 是否内置角色（内置不可删除、不可改编码；super_admin 权限恒全量且不可编辑） */
  builtin: boolean
  enabled: boolean
  sortNo: number
  /** 使用该角色的员工数（>0 时不允许删除） */
  userCount: number
  /** 该角色的权限码数量 */
  permissionCount: number
  /** 权限码列表（详情接口返回） */
  permissions?: string[]
}

/** 角色下拉选项（分配角色用） */
export interface RoleOption {
  roleCode: string
  roleName: string
}

/** 新建 / 编辑角色请求体 */
export interface RoleSavePayload {
  roleCode: string
  roleName: string
  dataScope: DataScopeCode
  remark?: string | null
  enabled?: boolean
  sortNo?: number
}

/** 覆盖式保存角色权限 */
export interface RolePermissionPayload {
  permissions: string[]
}

/** 消息类型元数据（`GET /api/meta/message-types`） */
export interface MessageTypeMeta {
  code: string
  label: string
}
