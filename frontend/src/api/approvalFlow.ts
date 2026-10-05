import { http } from '@/api/request'
import type {
  ApprovalFlowDetail,
  ApprovalFlowDuplicatePayload,
  ApprovalFlowItem,
  ApprovalFlowPayload,
  ApprovalFlowStatusCode,
  ApprovalFlowVersionItem,
  FlowDefinition,
  FlowDesignMeta,
  FlowScopeCode,
  FlowValidateResult
} from '@/types/approvalFlow'

/**
 * 审批流程模板接口
 *
 * 权限：查询 `approval_flow:view`（admin 默认有，只读）、
 * 变更 `approval_flow:manage`（按需求仅 super_admin）。
 *
 * 与表单模板接口同构：**草稿可随意保存，发布是唯一的严格校验点**。
 * 已发布的版本不可修改，改流程要「保存草稿 → 发布」出一版新的，
 * 这样引用旧版本的申请类型与历史工单都不会被改写。
 */
export const approvalFlowApi = {
  /** 流程列表（含最新已发布版本号、是否有草稿、节点数、被引用次数） */
  list() {
    return http.get<ApprovalFlowItem[]>('/approval-flows')
  },

  /**
   * 设计器元数据（ · W4-D / C8）——「条件树深度上限 + 各域禁用来源 + 各来源参数槽位」。
   *
   * 它是这三份约束的**唯一事实源**：前端只保留一份「接口不可用时的兜底默认」
   * （见 `types/approvalFlow.ts` 的 `applyFlowDesignMeta`），两端兜底值由共享金样例
   * `test-fixtures/golden/flow-design-meta.json` 钉死。
   *
   * 权限是 `approval_flow:view`（只读约束说明，不含配置数据），admin 也能拿到 ——
   * 否则会出现「设计器可用但即时提示全部失效」的半残状态。
   */
  designMeta() {
    return http.get<FlowDesignMeta>('/approval-flows/design-meta')
  },

  /** 流程详情（含定义：有草稿给草稿，无草稿给最新已发布版本） */
  detail(id: number) {
    return http.get<ApprovalFlowDetail>(`/approval-flows/${id}`)
  },

  /** 新建流程（同时创建 v1 草稿，返回流程 id） */
  create(data: ApprovalFlowPayload) {
    return http.post<number>('/approval-flows', data)
  },

  /** 保存草稿（整体覆盖；已发布版本不受影响，会自动另开一版草稿） */
  update(id: number, data: ApprovalFlowPayload) {
    return http.put<void>(`/approval-flows/${id}`, data)
  },

  /**
   * 另存为 / 复制流程（ · W4-B），返回**新流程**的 id。
   *
   * 后端语义：内容取源模板「当前可编辑定义」（草稿优先，无草稿取最新已发布版本）；
   * 产出一定是 DRAFT（不会"复制即生效"）；申请类型 / 部门的绑定**不跟随**。
   */
  duplicate(id: number, data: ApprovalFlowDuplicatePayload) {
    return http.post<number>(`/approval-flows/${id}/duplicate`, data)
  },

  /** 发布当前草稿为新版本，返回新版本 id（申请类型随后引用它） */
  publish(id: number) {
    return http.post<number>(`/approval-flows/${id}/publish`)
  },

  /**
   * 服务端权威校验（M4a）。
   *
   * 把当前设计器里的定义发到后端，拿回**全部**问题清单（不落库）。
   * 发布前应调用它，并以它的结果作为最终准入 —— 本地 `validateFlowForPublish`
   * 只是"即时预检"，两侧规则虽保持同构，但**以后端为准**才能根治漂移。
   *
   * @param definition  待校验的流程定义（草稿亦可）
   * @param formVersionId 绑定表单的版本 id；不传表示无表单上下文（如借用单流程）
   * @param scope       业务域：'CUSTOM'（缺省）/ 'BORROW'（M1）。
   *                    借用域禁用「表单人员字段」「申请人自选」，且条件字段来自借用内置字段清单，
   *                    因此发布借用流程时必须显式传 'BORROW'，否则预检会说"通过"而发布被拒。
   */
  validate(definition: FlowDefinition | null, formVersionId?: number | null, scope?: FlowScopeCode) {
    return http.post<FlowValidateResult>('/approval-flows/validate', {
      definition,
      formVersionId: formVersionId ?? null,
      scope: scope ?? null
    })
  },

  /** 版本列表（倒序，含草稿） */
  versions(id: number) {
    return http.get<ApprovalFlowVersionItem[]>(`/approval-flows/${id}/versions`)
  },

  /** 某版本详情（含完整定义，用于版本历史预览） */
  version(versionId: number) {
    return http.get<ApprovalFlowVersionItem>(`/approval-flows/versions/${versionId}`)
  },

  /** 启用 / 停用（被申请类型引用时不能删除，只能停用） */
  updateStatus(id: number, status: ApprovalFlowStatusCode) {
    return http.put<void>(`/approval-flows/${id}/status`, { status })
  },

  /** 删除流程（被申请类型引用时后端拒绝） */
  remove(id: number) {
    return http.delete<void>(`/approval-flows/${id}`)
  }
}

export default approvalFlowApi
