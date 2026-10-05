import { http } from '@/api/request'
import type { UserOption } from '@/types/user'
import type {
  DepartmentDeleteResult,
  DepartmentNode,
  DepartmentOption,
  DepartmentSavePayload
} from '@/types/department'

/**
 * 部门（组织与人员）接口 —— ，对应后端 `module/department` 的 8 个端点。
 *
 * <h2>与旧接口的关系</h2>
 * 取代原 `api/group.ts`（`/biz-groups`、`/handler-groups`）。旧表已由 V31 物理删除，
 * 旧接口不再存在，调用方必须全部改到本文件 —— 保留兼容层只会让「已经删掉的概念」
 * 继续在代码里流通。
 *
 * <h2>权限</h2>
 * - 读（tree / options / members）：`staff:view`（能进「组织与人员」即可看）；
 * - 写（create / update / move / setManagers / remove）：`department:manage`（默认仅超管）。
 *
 * 前端隐藏只是体验，后端每个端点都独立校验（越权返回 403）。
 */
export const departmentApi = {
  /** 部门树（含每个节点的人数与主管；树的层级由后端物化路径决定） */
  tree() {
    return http.get<DepartmentNode[]>('/departments/tree')
  },

  /**
   * 部门扁平选项（带 `displayPath`）。
   *
   * 各类「选一个部门」的下拉统一走这里，不要各自去 tree 里递归摊平 ——
   * 摊平口径（是否含停用节点、显示名怎么拼）必须只有一份。
   */
  options() {
    return http.get<DepartmentOption[]>('/departments/options')
  },

  /** 某部门的直属成员（用于部门成员预览；分页列表请用 userApi.page 带 departmentId） */
  members(id: number) {
    return http.get<UserOption[]>(`/departments/${id}/members`)
  },

  /** 新增部门（返回新部门 id） */
  create(data: DepartmentSavePayload) {
    return http.post<number>('/departments', data)
  },

  /**
   * 编辑部门（名称 / 排序 / 绑定流程 / 备注；**不含父子关系** —— 改层级要走 `move`）。
   *
   * ⚠️ **整体替换语义**：`approvalFlowVersionId` / `remark` 传 `null` 就是「清空」。
   * 调用方若只想改部门名，必须把当前绑定值**原样回传** —— 漏传等于顺手解绑该部门的审批流程。
   * 后端刻意**不**把 `null` 兜底成「不改动」：那样「解绑」将永远无法完成，
   * 且与改造前 `PUT /biz-groups/{id}/config` 的契约不一致（  回归 / 守着它）。
   */
  update(id: number, data: DepartmentSavePayload) {
    return http.put<void>(`/departments/${id}`, data)
  },

  /**
   * 移动部门（调整上下级）。
   *
   * 后端会整体重写受影响子树的 `path`/`depth`，并拒绝「移到自己或自己的子孙下」
   * （会成环）。前端拖拽失败时必须回滚本地树 —— 否则界面显示的层级与库里的不一致。
   */
  move(id: number, parentId: number | null) {
    return http.put<void>(`/departments/${id}/parent`, { parentId })
  },

  /** 设置部门主管（整体替换；传空数组表示清空） */
  setManagers(id: number, userIds: number[]) {
    return http.put<void>(`/departments/${id}/managers`, { userIds })
  },

  /**
   * 删除部门（**仅空部门可删**）。
   *
   * 后端会校验：部门下仍有成员、或仍有子部门时一律拒绝（返回 `DEPARTMENT_HAS_MEMBERS` /
   * `DEPARTMENT_HAS_CHILDREN`）。因此 `movedMemberCount` 恒为 0 —— 该字段仅为兼容旧响应契约保留。
   */
  remove(id: number) {
    return http.delete<DepartmentDeleteResult>(`/departments/${id}`)
  }
}

export default departmentApi
