/**
 * 设备台账表单的纯逻辑（ 引入 Vitest 后的回归保护对象）
 *
 * 背景： 的 Critical 缺陷正是「一级分类变化时二级分类被联动 watch 静默清空，
 * 编辑场景下回填的值被重置」。当时的修复把级联清理从 watch 改为 el-select 的 @change，
 * 但规则本身没有单测。此处把「一级切换后二级应如何取值」抽成纯函数并加测试，
 * 让同类缺陷再次出现时能被立即发现。
 *
 * 说明：入参用结构化类型 {@link CategoryNodeLike} 而非直接依赖 `types/device.ts` 的
 * `DeviceCategory` —— 后者字段更多，按 TS 结构化子类型规则可直接传入，
 * 而本文件只关心分类树的最小形状（这也是它易于单测的原因）。
 */

/** 分类树节点（`DeviceCategory` 的最小结构子集） */
export interface CategoryNodeLike {
  id: number
  parentId: number
  level: 1 | 2
  children?: CategoryNodeLike[]
}

/** 取某个一级分类下的二级分类列表（无匹配返回空数组） */
export function childrenOf(
  primaryId: number | null | undefined,
  categories: CategoryNodeLike[]
): CategoryNodeLike[] {
  if (primaryId == null) {
    return []
  }
  const matched = categories.find((category) => category.id === primaryId)
  return matched?.children ?? []
}

/**
 * 在两层分类树中按 id 查找节点（**同时搜索一级与二级**）
 *
 * 关键：`normalizeSecondaryForEdit` 的入参可能是「一级节点数组 + 内嵌 children」的树形结构，
 * 也可能是不带 children 的扁平数组。若只 `find` 顶层，二级分类在树形结构下永远查不到，
 * 就会退化成「一律返回原值」，导致一级/二级矛盾的脏数据被放行（ C1 缺陷同族）。
 */
function findCategoryById(id: number, categories: CategoryNodeLike[]): CategoryNodeLike | undefined {
  for (const category of categories) {
    if (category.id === id) {
      return category
    }
    const child = category.children?.find((item) => item.id === id)
    if (child) {
      return child
    }
  }
  return undefined
}

/**
 * 一级分类变化后，二级分类的最终取值
 *
 * 规则：若当前二级分类仍属于新的一级分类，则保留；否则清空为 null。
 * 返回 null 表示「必须清空」——调用方据此清掉选中值，避免提交一个与一级分类矛盾的二级分类。
 */
export function resolveSecondaryAfterPrimaryChange(
  currentSecondaryId: number | null | undefined,
  newPrimaryId: number | null | undefined,
  categories: CategoryNodeLike[]
): number | null {
  if (currentSecondaryId == null || newPrimaryId == null) {
    return null
  }
  const allowed = childrenOf(newPrimaryId, categories)
  return allowed.some((child) => child.id === currentSecondaryId) ? currentSecondaryId : null
}

/**
 * 编辑回填时的二级分类校正
 *
 * 与上者的区别：回填发生在「程序化赋值」而非用户交互之后，
 * 校验依据是**数据一致性**（二级分类的 parentId 是否等于一级分类），
 * 而不是依赖分类树里是否恰好挂着 children —— 列表接口未返回 children 时也能正确判断。
 */
export function normalizeSecondaryForEdit(
  primaryId: number | null | undefined,
  secondaryId: number | null | undefined,
  categories: CategoryNodeLike[]
): number | null {
  if (secondaryId == null || primaryId == null) {
    return null
  }
  const secondary = findCategoryById(secondaryId, categories)
  if (!secondary) {
    return secondaryId
  }
  return secondary.parentId === primaryId ? secondaryId : null
}
