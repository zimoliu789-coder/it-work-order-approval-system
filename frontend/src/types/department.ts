import type { UserOption } from '@/types/user'

/**
 * 部门（组织与人员）相关类型 —— 。
 *
 * <h2>与 `types/group.ts` 的关系</h2>
 * 本文件承接原 `types/group.ts` 中**仍然存活**的部分。改造前系统有两套并列的组织概念：
 * 「业务分组 biz_group」（扁平、绑定审批流程）与「最终处理小组 handler_group」；
 *  把它们统一成**一棵部门树** `departments`，旧的业务分组与处理小组表已由
 * Flyway V31 **物理删除**。因此：
 * - `BizGroupListItem` / `BizGroupDetail` / `BizGroupConfigPayload`
 *   / `HandlerGroupItem` / `MemberChange` —— **已删除**，承载它们的页面（部门管理 /
 *   最终处理部门管理）同步下线，能力并入「组织与人员」。
 * - `BorrowFlowPreview` 系列与 `FlowVersionOption` 系列（与「组织」无关的前端纯逻辑）
 *   原地保留，避免为了搬家而让调用方改两遍 import；后续批次重构表单/流程时再归位。
 */

// ---------------------------------------------------------------------
// 部门（后端 module/department）
// ---------------------------------------------------------------------

/**
 * 部门树节点（对应后端 `DepartmentNodeVO`）。
 *
 * `depth` 与 `path` 是后端维护的**物化路径**（`/1/10/`），前端只用它们做展示与排序；
 * 任何父子关系的变更都必须走后端接口，前端不得自行拼算 path —— 一旦与库里的
 * 树结构不一致，所有 `path LIKE` 的子树查询会**静默**多算或少算人。
 */
export interface DepartmentNode {
  id: number
  deptName: string
  parentId: number | null
  /** 物化路径，形如 `/1/10/` */
  path: string
  /** 根节点为 0 */
  depth: number
  sortOrder: number
  /**
   * 是否为「最终处理部门」（本系统里是唯一的 **IT运维组**）。
   *
   * 改造前每个业务分组各绑一个处理小组；改造后全系统只有一个最终处理部门，
   * 由它统一承接「发设备」这一步。因此这个标记**全表最多一行为 true**。
   */
  handlerGroup: boolean
  /** 绑定的借用审批流程版本 id；null = 该部门没有专属流程，走系统默认 */
  approvalFlowVersionId: number | null
  /** 是否启用 */
  status: boolean
  remark: string | null
  /** 本部门**直属**成员数（不含下级部门） */
  memberCount: number
  /** 本部门**及全部下级**成员数（树节点上显示的是这个） */
  totalMemberCount: number
  /** 部门主管（可多人）；成员未手工指定直属主管时默认取这里 */
  managers: UserOption[]
  children: DepartmentNode[]
}

/**
 * 部门扁平选项（对应后端 `DepartmentOptionVO`）。
 *
 * 用于各种「选一个部门」的下拉（员工所属部门、流程里的指定部门、工单筛选），
 * 与树结构分开：下拉需要的是**带层级语义的可读名称**，而不是可展开的节点。
 */
export interface DepartmentOption {
  id: number
  deptName: string
  parentId: number | null
  depth: number
  /** 是否为最终处理部门（发设备环节的执行部门） */
  handlerGroup: boolean
  /** 带层级缩进的完整显示名，如 `公司 / 研发部 / 前端一组` */
  displayPath: string
}

/** 新增 / 编辑部门请求体 */
export interface DepartmentSavePayload {
  deptName: string
  /** 父部门 id；null 表示挂在根节点下 */
  parentId: number | null
  sortOrder?: number | null
  /**
   * 绑定的借用审批流程版本；**`null` = 解绑**（不是「不改动」）。
   *
   * ⚠️ 该接口是整体替换语义：编辑部门时必须把当前绑定值原样回传，
   * 否则「改个部门名」会把该部门的审批流程绑定一起清掉（静默解绑，无任何提示）。
   * 只写非空版本；不存在的版本、草稿版本都会被后端拒绝
   * （`FLOW_VERSION_NOT_FOUND` / `FLOW_NO_PUBLISHED_VERSION`）。
   */
  approvalFlowVersionId?: number | null
  /** 备注；`null` 或空白 = 清空 */
  remark?: string | null
}

/**
 * 删除部门的结果。
 *
 * 删除**不级联删人**：该部门下的成员会被上移到父部门，并在返回里告知数量 ——
 * 界面上必须先把这个数字摆给管理员看（「该部门下有 X 人，删除后移到上级」），
 * 否则「删除」会被误当成「连同人员一起删掉」。
 */
export interface DepartmentDeleteResult {
  deptName: string
  /** 被上移到父部门的成员数。**当前恒为 0**（仅空部门可删），字段为兼容旧契约保留 */
  movedMemberCount: number
  /** 上移到的目标部门（根部门的父为 null 时为 null） */
  movedToDepartmentId: number | null
  movedToDepartmentName: string | null
}

// ---------------------------------------------------------------------
// 借用单审批流程预览（原 M1）
// ---------------------------------------------------------------------

/** 借用单审批流程预览的单个节点 */
export interface BorrowFlowPreviewNode {
  nodeKey: string | null
  nodeName: string
  /** APPROVAL 审批 / CC 抄送 */
  nodeType: string
  /** 已解析出的审批人姓名；为空表示将由超管兜底或提交后才确定 */
  approverNames: string[]
  /** 「为什么走了这条分支」的说明；无条件路径上为空 */
  conditionDesc: string | null
  timeLimitHours: number | null
}

/** 借用单审批流程预览 */
export interface BorrowFlowPreview {
  /** 是否绑定了自定义流程；false 时前端应回退展示固定审批人列表 */
  bound: boolean
  flowVersionLabel: string | null
  nodes: BorrowFlowPreviewNode[]
  /** 本单不会经过的节点（条件分支未命中） */
  skippedNodes: BorrowFlowPreviewNode[]
}

/** 可绑定的审批流程版本选项 */
export interface FlowVersionOption {
  value: number
  label: string
}

/** 供构造选项用的最小流程信息（与 approvalFlow.ts 的 ApprovalFlowItem 结构兼容） */
export interface FlowVersionSource {
  flowName: string
  status?: string | null
  latestPublishedVersionId?: number | null
  latestPublishedVersionNo?: number | null
}

/**
 * 可绑定的流程版本选项（各流程的「最新已发布版本」）。
 *
 * 只有**已发布**的版本能被引用：草稿改来改去，绑上去会让流程在提交时才暴露问题。
 * 已停用的流程仍列出（并标注），因为可能已有部门绑定它的版本；
 * 后端会再校验一次「版本已发布」，这里只是提前过滤掉必然失败的选项。
 */
export function flowVersionOptions(flows: FlowVersionSource[]): FlowVersionOption[] {
  return flows
    .filter((flow) => flow.latestPublishedVersionId != null)
    .map((flow) => ({
      value: flow.latestPublishedVersionId as number,
      label: `${flow.flowName} · v${flow.latestPublishedVersionNo ?? '-'}${
        flow.status === 'DISABLED' ? '（已停用）' : ''
      }`
    }))
}

/**
 * 已绑定的版本是否「不在可选列表中」（流程被删 / 版本回滚等异常态）。
 *
 * 为什么要单独判断：此时 el-select 会显示空白，管理员会以为「没绑定」而随手保存 ——
 * 反而把绑定清掉了。识别出来后界面可以给出明确提示。
 */
export function isFlowBindingOrphaned(
  boundVersionId: number | null | undefined,
  options: FlowVersionOption[]
): boolean {
  return boundVersionId != null && !options.some((option) => option.value === boundVersionId)
}

/**
 * 借用申请页是否需要展示「审批路径预览」。
 *
 * 三个条件缺一不可：绑定了流程、路径解析出节点、或者存在被跳过的节点。
 * 若三者皆空（流程里全是条件分支且全部未命中），展示一个空壳路径块
 * 不如不展示 —— 界面会自己渲染「无需审批」的空状态文案。
 */
export function shouldShowBorrowPath(preview: BorrowFlowPreview | null | undefined): boolean {
  if (!preview || !preview.bound) {
    return false
  }
  return preview.nodes.length > 0 || preview.skippedNodes.length > 0
}

/** 节点类型展示标签（APPROVAL 审批 / CC 抄送） */
export function borrowNodeTypeLabel(nodeType: string): string {
  if (nodeType === 'CC') {
    return '抄送'
  }
  if (nodeType === 'APPROVAL') {
    return '审批'
  }
  return nodeType
}

/** 节点类型标签配色：抄送用信息色，审批用主色 */
export function borrowNodeTypeTagType(nodeType: string): 'primary' | 'info' {
  return nodeType === 'CC' ? 'info' : 'primary'
}
