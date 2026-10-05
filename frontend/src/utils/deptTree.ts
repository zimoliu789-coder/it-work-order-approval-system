/**
 * 部门树拖拽内核—— 纯函数。
 *
 * <h2>为什么把判定抽成纯函数</h2>
 * Element Plus 的 `allow-drag` / `allow-drop` 回调只在**真实拖拽**时触发，
 * 而 HTML5 拖拽事件链在 happy-dom 里驱动不起来 ⇒ 规则若写在组件里就只能靠人工点。
 * 抽出来之后，后端的三条硬约束可以在单测里逐条钉死：
 *
 * 1. **根节点（公司）不可移动** —— 后端 `move` 对 `parentId == null` 的节点直接抛
 *    `DEPARTMENT_ROOT_PROTECTED`（根是全树的锚，动它会破坏物化路径的起点）。
 * 2. **不能拖到自己或自己的子孙下** —— 会成环，`path` 拼接无限增长且树渲染栈溢出，
 *    后端 `DEPARTMENT_MOVE_INVALID`。Element Plus 自带默认实现会挡这条，但一旦我们
 *    提供 `allow-drop`，默认实现就被**整体替换**掉了 ⇒ 必须自己实现。
 * 3. **本系统只有一个根（公司）** ⇒「把节点变成根的同级」在数据模型里**不存在**：
 *    后端把 `parentId = null` 一律解析回根（`resolveParent`）。因此「拖到根的前/后」
 *    必须在前端就禁掉 —— 否则用户会拖出一个「看起来在顶层、刷新后又回到根下」的假象。
 *
 * 另外，「拖拽落点 → 新父部门 id」这一步是**拖拽语义的核心**（`prev`/`next` 取落点的父、
 * `inner` 取落点本身），也是最容易在重构时改错的一行，因此单独成函数并配断言。
 */

/** 拖拽落点类型（Element Plus `allow-drop` 的第三个参数 / `node-drop` 事件） */
export type DeptDropType = 'prev' | 'inner' | 'next'

/**
 * 参与拖拽判定所需的最小节点形状。
 *
 * 刻意不直接依赖 `DepartmentNode`：本模块是纯逻辑，只关心「父子关系」，
 * 用最小形状可以让单测造夹具时不必凑齐 15 个字段，也避免类型层反向耦合到业务 VO。
 */
export interface DeptTreeLike {
  id: number
  parentId: number | null
  children?: DeptTreeLike[]
}

/**
 * `targetId` 是否是 `root` 自身或 `root` 的子孙。
 *
 * 用于「不能拖到自己的子孙下」这条防环判定 —— 注意方向：判断的是
 * **落点是否在拖动节点的子树里**，而不是反过来（落点是祖先时是合法的，
 * 那只是「移回原来的上级」，属于无害操作）。
 */
export function isSelfOrDescendant(root: DeptTreeLike, targetId: number): boolean {
  if (root.id === targetId) {
    return true
  }
  for (const child of root.children ?? []) {
    if (isSelfOrDescendant(child, targetId)) {
      return true
    }
  }
  return false
}

/**
 * 该节点能否被拖动。
 *
 * 只有「根节点」（没有上级）不可拖 —— 与后端 `DEPARTMENT_ROOT_PROTECTED` 一一对应。
 * 其余节点都可拖：层级不限（用户已拍板「不限层级，只防环」）。
 */
export function canDragDeptNode(node: DeptTreeLike): boolean {
  return node.parentId != null
}

/**
 * 拖拽落点 → 新的父部门 id。
 *
 * 返回 `undefined` = **这次拖拽不合法**（界面应拒绝，不发出请求）；
 * 返回 `null` 目前不会出现（根的同级不被允许），保留在签名里是为了与
 * `departmentApi.move(id, parentId | null)` 的契约对齐，便于日后若允许多根时扩展。
 *
 * <h2>三种落点语义</h2>
 * - `inner`：落在某节点**内部** ⇒ 新父 = 落点本身；
 * - `prev` / `next`：落在某节点的**前/后**（同级）⇒ 新父 = 落点的父部门。
 */
export function resolveDropParentId(
  drag: DeptTreeLike,
  drop: DeptTreeLike,
  dropType: DeptDropType
): number | null | undefined {
  // 防环：不能落到自己或自己的子孙上（含拖回原位这种无意义操作）
  if (isSelfOrDescendant(drag, drop.id)) {
    return undefined
  }
  if (dropType === 'inner') {
    return drop.id
  }
  // prev / next ⇒ 与落点同级
  // 落点是根（没有上级）⇒ 目标是「根的同级」，数据模型里不存在 ⇒ 拒绝
  if (drop.parentId == null) {
    return undefined
  }
  return drop.parentId
}

/** 该落点是否允许（`resolveDropParentId` 的布尔包装，供 `allow-drop` 直接使用） */
export function canDropDeptNode(
  drag: DeptTreeLike,
  drop: DeptTreeLike,
  dropType: DeptDropType
): boolean {
  return resolveDropParentId(drag, drop, dropType) !== undefined
}

/**
 * `node-drop` 事件的落点类型 → `allow-drop` 的落点类型。
 *
 * <h2>⚠️ Element Plus 用了两套词汇（极易踩）</h2>
 * - `allow-drop(draggingNode, dropNode, type)` 的 `type`：`'prev' | 'inner' | 'next'`；
 * - `node-drop(draggingNode, dropNode, dropType, evt)` 的 `dropType`：`'before' | 'after' | 'inner'`。
 *
 * 语义一一对应（`before`↔`prev`、`after`↔`next`），但**字符串不同**。
 * 把事件值直接喂给 `resolveDropParentId` 会掉进「非 inner、非 prev/next」的默认分支，
 * 表现是**「拖到部门内部，结果变成了它的兄弟节点」** —— 层级错了但界面不报错，
 * 只有重新拉树才发现层级没变。所以这里显式翻译一次，并配单测钉住。
 */
export function normalizeNodeDropType(dropType: 'before' | 'after' | 'inner'): DeptDropType {
  if (dropType === 'inner') {
    return 'inner'
  }
  return dropType === 'before' ? 'prev' : 'next'
}

/**
 * 部门名是否命中搜索关键词。
 *
 * 规则刻意简单：**忽略大小写与首尾空白的子串匹配**。不做拼音、不做路径匹配 ——
 * 树本身已经表达了层级，搜索的用途是「在几十个部门里快速定位」，
 * 匹配面越窄越不容易出现「明明搜的是财务、结果整个研发部都亮着」的困惑。
 */
export function deptNameMatches(name: string | null | undefined, keyword: string): boolean {
  const kw = keyword.trim().toLowerCase()
  if (!kw) {
    return true
  }
  return (name ?? '').toLowerCase().includes(kw)
}
