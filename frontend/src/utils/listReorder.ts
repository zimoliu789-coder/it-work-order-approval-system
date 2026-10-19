/**
 * 列表重排：拖拽排序与「上移 / 下移」按钮共用同一条纯函数。
 *
 * <h2>为什么单独抽一个文件</h2>
 * 拖拽排序的正确性全在「从 from 取出、插到 to」这一步上，而它藏在 .vue 的
 * script setup 里没法单测。抽成纯函数后，越界、原地、边界值都能在 vitest 里固化 ——
 * 顺序错乱这类问题不会报错，只会让页面「看起来随机」，必须靠断言守住。
 *
 * <h2>为什么返回新数组</h2>
 * 调用方拿着的是 `ref` 里的同一份数组。若原地 splice，拖拽失败回滚
 * （重新 loadTypes）之前，本地顺序与请求结果会互相覆盖，表现为「卡片跳两次」。
 */

/**
 * 把 {@code from} 位置的元素移到 {@code to} 位置，返回新数组。
 *
 * <p>{@code from} / {@code to} 任一越界或相等时，原样返回副本（不抛错）——
 * 拖拽松手的位置可能是网格边缘的空隙，越界是正常输入而不是异常。
 */
export function reorderList<T>(items: readonly T[], from: number, to: number): T[] {
  const next = [...items]
  if (from < 0 || from >= next.length || to < 0 || to >= next.length || from === to) {
    return next
  }
  const [moved] = next.splice(from, 1)
  next.splice(to, 0, moved)
  return next
}
