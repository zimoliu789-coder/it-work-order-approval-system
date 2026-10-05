import { ref, type Ref } from 'vue'

/**
 * 轻量拖拽排序（：分组列表与审批节点卡片均支持拖拽排序）
 *
 * 说明：使用原生 HTML5 drag-and-drop，不引入额外依赖。
 * 原生 DnD 在触摸屏上支持有限，因此移动端不承载排序操作（：
 * 配置操作以 PC 为主）；节点卡片另提供「上移/下移」按钮作为替代操作方式。
 *
 * @param list     被排序的响应式数组（原地 splice 修改）
 * @param onSorted 排序完成回调（用于触发 step_order 重排 / 后端保存）
 */
export function useDragSort<T>(list: Ref<T[]>, onSorted?: () => void) {
  const dragIndex = ref(-1)
  const overIndex = ref(-1)

  function onStart(index: number): void {
    dragIndex.value = index
    overIndex.value = index
  }

  function onOver(index: number): void {
    if (dragIndex.value < 0) {
      return
    }
    overIndex.value = index
  }

  function onDrop(): void {
    const from = dragIndex.value
    const to = overIndex.value
    if (from >= 0 && to >= 0 && from !== to) {
      const moved = list.value.splice(from, 1)[0]
      list.value.splice(to, 0, moved)
      onSorted?.()
    }
    reset()
  }

  /** 上移/下移：移动端与键盘操作的替代方式 */
  function move(index: number, offset: number): void {
    const target = index + offset
    if (target < 0 || target >= list.value.length) {
      return
    }
    const moved = list.value.splice(index, 1)[0]
    list.value.splice(target, 0, moved)
    onSorted?.()
  }

  function reset(): void {
    dragIndex.value = -1
    overIndex.value = -1
  }

  return { dragIndex, overIndex, onStart, onOver, onDrop, move, reset }
}
