import { http } from '@/api/request'
import type { FlowMonitorFlow, FlowMonitorNode } from '@/types/flowMonitor'

/**
 * 流程监控接口（ · M7）
 *
 * 两个端点都只读，均需权限码 `flow_monitor:view`（admin 默认持有，超管恒定放行）。
 * 前端菜单隐藏只是体验，后端 `@PreAuthorize` 会二次校验 —— 直调接口必须被拦。
 */
export const flowMonitorApi = {
  /**
   * 模板维度汇总。
   *
   * 返回值恒包含「未归属」行（flowId = 0）并排在最后：前端不需要自己判断
   * "要不要补一行未归属"，后端已经把它当成一个正常分组给出。
   */
  flows() {
    return http.get<FlowMonitorFlow[]>('/flow-monitor/flows')
  },

  /**
   * 某模板的节点维度明细（按平均耗时降序）。
   *
   * @param flowId 模板 id；传 0（`UNATTRIBUTED_FLOW_ID`）取未归属桶
   */
  nodes(flowId: number) {
    return http.get<FlowMonitorNode[]>(`/flow-monitor/flows/${flowId}/nodes`)
  }
}

export default flowMonitorApi
