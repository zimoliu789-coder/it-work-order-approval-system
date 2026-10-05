import type { ColumnConfigDef } from '@/composables/useColumnConfig'

/**
 * 公共表格层的列定义（-B）
 *
 * 之所以放在 `types/` 而不是塞进 `TablePage.vue`：
 * 列定义是**页面与公共层之间的契约**（6 个页面都要 import 它），
 * 让契约住在组件文件里会让「改一行列宽」变成「打开一个 SFC 去看 props」。
 *
 * 字段分三组：
 * 1. 偏好组（继承 `ColumnConfigDef`）：`key` / `label` / `configurable` / `defaultVisible`
 *    —— 决定它在列配置弹窗里怎么出现；
 * 2. 渲染组（`width` / `minWidth` / `fixed` / `align` / `sortable` / `showOverflowTooltip`）
 *    —— 与 `el-table-column` 的 props 一一对应，**不做自定义转换**，
 *    这样迁移后的首帧渲染与改造前逐字段一致（宽度语义也一样，见下）；
 * 3. 移动端组（`table` / `card` / `cardHideOnEmpty`）—— 仅通用卡片回落使用。
 *
 * ⚠️ 宽度语义：项目既有页面**一律使用 `min-width` 而非 `width`**（Element Plus 的
 * `width` 会被表格弹性布局压缩，长文本硬截断；见项目记忆里的既有教训）。
 * 迁移时逐列照抄原写法，不要顺手把 `min-width` 改成 `width`。
 */
export interface ColumnDef extends ColumnConfigDef {
  /**
   * 是否为多选列（对应 `el-table-column type="selection"`，W4-E 新增）。
   *
   * 消息中心的批量删除依赖它。注意这一列**没有「key → 行字段」的映射**：
   * `key` 仍然必填（它同时是表格内的稳定标识与 `#cell-<key>` 插槽名），
   * 但 `selection` 为真时 TablePage 只渲染 `type="selection"`，不再渲染 `prop` / `label`。
   */
  selection?: boolean
  /** 对应 el-table-column 的 width（会被弹性布局压缩，长内容列请用 minWidth） */
  width?: number | string
  /** 对应 el-table-column 的 min-width（不被压缩，长内容列首选） */
  minWidth?: number | string
  fixed?: boolean | 'left' | 'right'
  align?: 'left' | 'center' | 'right'
  /** `'custom'` = 由父组件监听 `sort-change` 服务端排序（照抄原页面写法） */
  sortable?: boolean | 'custom'
  showOverflowTooltip?: boolean
  /**
   * 是否在 PC 表格中渲染（缺省 `true`）。
   * `false` 的列只可能出现在移动端通用卡片里 —— 用于「表上放不下、卡片上却需要」的字段。
   */
  table?: boolean
  /**
   * 移动端**通用卡片**中的角色：
   * - `'title'`：作为卡片标题（每页最多一个）；
   * - `true` / 缺省：作为一行「标签 → 值」；
   * - `false`：不出现在卡片里。
   *
   * 注意：提供了 `#mobile` 具名插槽的页面会用**自己的卡片实现**，
   * 本字段只对走通用回落的页面生效（见 TablePage 组件注释）。
   */
  card?: boolean | 'title'
  /** 通用卡片：值为空（null / undefined / '' / 空数组）时隐藏该行，避免一片 `-` */
  cardHideOnEmpty?: boolean
}
