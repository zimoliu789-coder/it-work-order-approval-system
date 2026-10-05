import { http } from '@/api/request'
import type { PageResult } from '@/types/api'
import type { FlowPreview, FlowPreviewCandidate } from '@/types/approvalFlow'
import type { ApplyTypeItem, ApplyConfigCreatePayload, ApplyTypeOption, ApplyTypePayload, ApplyTypeStatusCode } from '@/types/applyType'

/**
 * 申请类型接口
 *
 * 权限分两档：
 * - 管理类（列表 / 新建 / 修改 / 删除 / 启停）：`apply_type:view|manage`，admin 默认只有 view（能看不能改）；
 * - 提交类（启用列表 / 详情）：仅要求已登录 —— 普通员工用的接口，
 *   可见性（能否提交）由后端按类型的提交权限在服务端过滤。
 */
export const applyTypeApi = {
  /** 管理列表（全部状态，不含 schema） */
  list() {
    return http.get<ApplyTypeItem[]>('/apply-types')
  },

  /** 当前用户可提交的类型（启用 + 提交权限过滤），供提交申请页卡片区 */
  enabled() {
    return http.get<ApplyTypeOption[]>('/apply-types/enabled')
  },

  /** 类型详情（含 schema，提交页据此渲染动态表单） */
  detail(id: number) {
    return http.get<ApplyTypeItem>(`/apply-types/${id}`)
  },

  /** 新建 */
  create(data: ApplyTypePayload) {
    return http.post<number>('/apply-types', data)
  },

  /**
   * 一步创建：表单字段 + 审批流程 + 申请类型，一个事务建齐。
   *
   * 与 {@link create} 的分工：`create` 用于「已经手工建好表单/流程版本，只挑版本」的老路径
   * （界面上已不再推荐，保留给高级编辑）；本接口是「新建申请」三步向导的唯一提交口。
   */
  createFull(data: ApplyConfigCreatePayload) {
    return http.post<number>('/apply-types/full', data)
  },

  /** 修改 */
  update(id: number, data: ApplyTypePayload) {
    return http.put<void>(`/apply-types/${id}`, data)
  },

  /** 启用 / 停用 */
  updateStatus(id: number, status: ApplyTypeStatusCode) {
    return http.put<void>(`/apply-types/${id}/status`, { status })
  },

  /** 删除（已被工单使用时后端拒绝，只能停用） */
  remove(id: number) {
    return http.delete<void>(`/apply-types/${id}`)
  },

  /**
   * 审批流程预览：按当前表单数据算出命中路径 + 申请人自选要求。
   *
   * 提交页在表单变化时（防抖）调用，据此**只对命中路径上的节点**渲染选人区 ——
   * 被条件分支绕开的节点不必让用户挑人。它只是 UI 便利，
   * 真正的人数/范围校验在提交时由服务端重算。
   */
  flowPreview(id: number, formData: Record<string, unknown>) {
    return http.post<FlowPreview>(`/apply-types/${id}/flow-preview`, { formData })
  },

  /**
   * 「申请人自选」候选人的分页搜索（ · W4-D）。
   *
   * 预览下发给的 `candidates` 只是**首屏子集**（「全部员工」范围下不可能整份下发），
   * 因此选择器需要能按关键字翻查完整名册 —— 截断与搜索必须同时存在，
   * 只截断会让排在第一屏之后的人**真的选不到**（是功能回归，不是优化）。
   *
   * 权限与预览一致（仅需登录）；`nodeKey` 已失效（流程被改并重发布）时后端返回空页而非报错。
   */
  chooseCandidates(id: number, nodeKey: string, keyword: string, page: number, size: number) {
    return http.get<PageResult<FlowPreviewCandidate>>(`/apply-types/${id}/choose-candidates`, {
      nodeKey,
      keyword,
      page,
      size
    })
  }
}

export default applyTypeApi
