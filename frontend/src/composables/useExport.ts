import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { exportApi } from '@/api/export'
import type { ExportRequest, ExportResult } from '@/types/export'

/**
 * 导出交互编排
 *
 * 把「发起导出 → 同步就下载 / 异步就提示等消息」这条分支收敛到一个地方：
 * 列表页（设备台账 / 我的工单 / 全部工单）与报表页的按钮行为因此完全一致，
 * 不会出现「某个页面忘记处理异步分支，用户点了导出却什么都没发生」。
 */
export function useExport() {
  const loading = ref(false)

  /**
   * 发起导出
   *
   * @param payload 导出类型与筛选条件
   * @param fallbackName 响应头缺文件名时的兜底名
   * @returns 后端受理结果；失败时抛出（由请求层统一提示）
   */
  async function runExport(payload: ExportRequest, fallbackName = '导出文件.xlsx'): Promise<ExportResult> {
    loading.value = true
    try {
      const result = await exportApi.create(payload)
      if (result.mode === 'SYNC' && result.downloadUrl) {
        // 同步：文件已生成，直接触发下载，用户不需要再去记录页找
        await exportApi.download(result.taskId, result.fileName ?? fallbackName)
        ElMessage.success(result.message ?? '导出完成，已开始下载')
      } else {
        // 异步：不阻塞用户，完成后由站内消息通知（点击消息进入导出记录）
        ElMessage.info(result.message ?? '数据量较大，已转为后台生成，完成后将在消息中心通知您')
      }
      return result
    } finally {
      loading.value = false
    }
  }

  return { loading, runExport }
}
