import { http } from '@/api/request'
import type { UpgradeOverview, UpgradeTaskItem } from '@/types/upgrade'

/**
 * 在线升级接口
 *
 * 三个写操作（上传 / 应用 / 回滚）都只有超管有权限码，且后端另有
 * `app.upgrade.enabled` 总开关（生产默认关闭）—— 两层都过才能真正执行。
 */
export const upgradeApi = {
  /** 能力总览：开关 / 当前版本 / 是否配置外部应用命令 / 进行中的任务 */
  overview() {
    return http.get<UpgradeOverview>('/system/upgrade/overview')
  },

  /** 升级历史（倒序） */
  tasks() {
    return http.get<UpgradeTaskItem[]>('/system/upgrade/tasks')
  },

  /** 单条任务详情（轮询进度用） */
  detail(taskNo: string) {
    return http.get<UpgradeTaskItem>(`/system/upgrade/tasks/${encodeURIComponent(taskNo)}`)
  },

  /**
   * 上传升级包。
   *
   * 走裸字节流而不是 multipart：见 `postBinary` 的注释（全局 multipart 上限与超时两项）。
   * 文件名通过查询参数传给后端（仅用于展示与留痕，服务端绝不拿它拼路径）。
   */
  upload(file: File, onProgress?: (percent: number) => void) {
    const url = `/system/upgrade/package?fileName=${encodeURIComponent(file.name)}`
    return http.postBinary<UpgradeTaskItem>(url, file, onProgress)
  },

  /** 发起应用（调用外部编排脚本完成替换与重启） */
  apply(taskNo: string) {
    return http.post<UpgradeTaskItem>(`/system/upgrade/tasks/${encodeURIComponent(taskNo)}/apply`)
  },

  /** 回滚到该任务备份的版本 */
  rollback(taskNo: string) {
    return http.post<UpgradeTaskItem>(`/system/upgrade/tasks/${encodeURIComponent(taskNo)}/rollback`)
  }
}
