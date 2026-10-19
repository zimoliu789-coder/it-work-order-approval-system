import { describe, expect, it } from 'vitest'
import { reorderList } from '@/utils/listReorder'

/**
 * 列表重排单测
 *
 * <h2>为什么这些断言必须存在</h2>
 * 拖拽排序有三类不会报错、只会「看起来不对」的失效方式：
 * ① 原地改数组 —— 拖拽失败回滚时本地顺序被覆盖，卡片跳两次；
 * ② 越界下标 —— 松手位置在网格边缘空隙时静默出错，顺序变成随机；
 * ③ 相邻交换方向反了 —— 「上移」实际下移，纯看界面很难第一时间发现。
 * 三类各自钉一条。
 */
describe('reorderList', () => {
  const ids = [1, 2, 3, 4]

  it('把元素后移：从 0 移到 2', () => {
    expect(reorderList(ids, 0, 2)).toEqual([2, 3, 1, 4])
  })

  it('把元素前移：从 3 移到 1', () => {
    expect(reorderList(ids, 3, 1)).toEqual([1, 4, 2, 3])
  })

  it('移到首位 / 末位', () => {
    expect(reorderList(ids, 2, 0)).toEqual([3, 1, 2, 4])
    expect(reorderList(ids, 0, 3)).toEqual([2, 3, 4, 1])
  })

  it('from === to 时原样返回（拖到自己身上不该有副作用）', () => {
    expect(reorderList(ids, 2, 2)).toEqual([1, 2, 3, 4])
  })

  it('下标越界时原样返回，不抛错（松手位置可能在网格边缘）', () => {
    expect(reorderList(ids, -1, 2)).toEqual([1, 2, 3, 4])
    expect(reorderList(ids, 0, 99)).toEqual([1, 2, 3, 4])
    expect(reorderList(ids, 99, 0)).toEqual([1, 2, 3, 4])
  })

  it('空数组 / 单元素数组安全', () => {
    expect(reorderList([], 0, 0)).toEqual([])
    expect(reorderList([7], 0, 0)).toEqual([7])
  })

  it('★ 不修改原数组（否则拖拽失败回滚会互相覆盖）', () => {
    const source = [1, 2, 3]
    reorderList(source, 0, 2)
    expect(source).toEqual([1, 2, 3])
  })

  it('保留元素引用（对象数组不做深拷贝，identity 不能丢）', () => {
    const a = { id: 'a' }
    const b = { id: 'b' }
    const c = { id: 'c' }
    const moved = reorderList([a, b, c], 2, 0)
    expect(moved).toEqual([c, a, b])
    expect(moved[0]).toBe(c)
    expect(moved[1]).toBe(a)
  })
})
