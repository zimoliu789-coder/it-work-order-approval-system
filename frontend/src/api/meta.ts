import { http } from '@/api/request'
import type { DataScopeOption, MessageTypeMeta, PermNode } from '@/types/permission'

/**
 * 元数据接口（需求方三波·第三波·；第一波·）
 *
 * 后端 `MetaController` 提供三份「静态字典」：
 * - 消息类型（编码 → 中文名）：把前后端手工同步的映射收敛到后端一处；
 * - 权限目录树：角色授权界面的勾选树；
 * - 数据权限范围：角色编辑表单的下拉项。
 */
export const metaApi = {
  /** 消息类型元数据（仅需登录） */
  messageTypes() {
    return http.get<MessageTypeMeta[]>('/meta/message-types')
  },

  /** 权限目录树（需 role:view） */
  permissions() {
    return http.get<PermNode[]>('/meta/permissions')
  },

  /** 数据权限范围字典（需 role:view） */
  dataScopes() {
    return http.get<DataScopeOption[]>('/meta/data-scopes')
  }
}

export default metaApi
