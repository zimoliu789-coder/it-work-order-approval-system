import { http } from '@/api/request'
import type { PageResult } from '@/types/api'
import type { BackupOverview, BackupRecordItem } from '@/types/backup'

/**
 * 数据库备份接口（P0）
 *
 * <h2>权限：两个码，均只归超管</h2>
 * <p>`backup:view` 看（概览 + 记录列表），`backup:manage` 做（立即备份）。
 * 两者都不下发给业务管理员 —— 排查备份失败要的基础设施权限（改 .env、进 NAS、看容器日志）
 * 普通业务管理员并不具备，而备份页上的失败记录对他们只是「收到但处理不了」的噪音。
 *
 * <h2>⚠️ 「备份失败」不是请求失败</h2>
 * <p>手动触发时，若 mysqldump 因环境问题失败（目录不可写 / 磁盘满 / 未装客户端），
 * 后端<b>不会抛异常</b>：它返回一条 `status=FAILED` 的记录，并把确切原因放在 `errorMessage`。
 * 调用方必须把这个结果<b>展示出来</b>（warning 提示 + 记录行），
 * 而不能只看 HTTP 是否 2xx —— 那样会把「一个可用归档都没做出来」当成成功。
 * 真正会抛错的只有一种：已有备份正在执行（`BACKUP_ALREADY_RUNNING`）。
 */
export const backupApi = {
  /** 概览：是否启用 / 备份时刻 / 保留天数 / 目录可用性 / 最后一次成功备份 */
  overview() {
    return http.get<BackupOverview>('/system/backups/overview')
  },

  /** 备份记录分页列表（按开始时间倒序） */
  page(page = 1, size = 20) {
    return http.get<PageResult<BackupRecordItem>>('/system/backups', { params: { page, size } })
  },

  /** 立即备份（同步执行；环境类失败返回 status=FAILED 的记录而不是抛错） */
  run() {
    return http.post<BackupRecordItem>('/system/backups')
  }
}
