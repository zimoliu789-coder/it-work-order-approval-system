import { http } from '@/api/request'

/**
 * 初始化向导接口（本次新增）。
 *
 * 两个端点都**免认证**：它们的意义就是「系统还没有任何账号时先把第一个超管建出来」。
 * 安全性由服务端规则保证 —— `/initialize` 仅在库中不存在 super_admin 时生效。
 */
export interface SetupStatus {
  /** 系统是否已完成初始化（库中是否已有超级管理员） */
  initialized: boolean
}

export interface SetupInitializePayload {
  /** 超管账号名（字母 / 数字 / . _ -，3~32 位） */
  username: string
  password: string
  confirmPassword: string
  /** 展示名（可空，默认与账号名相同） */
  displayName?: string
}

export const setupApi = {
  /** 查询初始化状态 */
  status() {
    return http.get<SetupStatus>('/setup/status')
  },

  /** 完成初始化，创建超级管理员 */
  initialize(data: SetupInitializePayload) {
    return http.post<void>('/setup/initialize', data)
  }
}

export default setupApi
