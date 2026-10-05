import { describe, expect, it, vi } from 'vitest'
import { CHOOSE_CANDIDATE_PAGE_SIZE, useChooseCandidates, type CandidatePage } from '@/composables/useChooseCandidates'
import type { FlowChooseRequirement } from '@/types/approvalFlow'

/**
 * 申请人自选审批人 · 候选池单测（ · W4-D · D-2）
 *
 * 这一层出错的**表现形式都很隐蔽**，因此每条都按「会坏成什么样」写：
 * - 只截断不搜索 → 首屏之后的人**真的选不到**（功能回归，用户不会报错、只会说"搜不到"）；
 * - 远程搜索换掉 options 却不补齐已选项 → 选中的人标签退化成数字 id（用户不知自己选了谁）；
 * - 无请求序号守卫 → 慢的旧响应覆盖新响应，下拉显示与输入框内容不一致；
 * - 搜索失败清空列表 → 被读成"没有匹配的人"（比"列表没变"更误导）；
 * - seed 打断检索 → 用户改一个表单字段，正在输入的搜索结果被无声重置。
 */

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0))
}

function requirement(overrides: Partial<FlowChooseRequirement> = {}): FlowChooseRequirement {
  return {
    nodeKey: 'node-a',
    nodeName: '部门经理审批',
    stepOrder: 1,
    scope: 'ALL',
    scopeValue: null,
    minCount: 1,
    maxCount: 2,
    candidates: [
      { id: 1, name: '张三' },
      { id: 2, name: '李四' }
    ],
    ...overrides
  }
}

function page(records: CandidatePage['records'], total: number): CandidatePage {
  return { records, total }
}

describe('useChooseCandidates · seed（首屏即用，不为首屏多发请求）', () => {
  it('首屏直接用预览下发的子集，并如实反映总数与截断标记', () => {
    const fetchCandidates = vi.fn()
    const choose = useChooseCandidates({ applyTypeId: () => 5, fetchCandidates })

    choose.seed([requirement({ candidates: [{ id: 1, name: '张三' }], candidateTotal: 900, candidatesTruncated: true })])

    const slot = choose.slotOf('node-a')
    expect(slot.options.map((item) => item.id)).toEqual([1])
    expect(slot.total).toBe(900)
    expect(slot.truncated).toBe(true)
    expect(fetchCandidates).not.toHaveBeenCalled()
  })

  it('老后端不返回 total/truncated 时按首屏长度兜底（不误报"还有更多"）', () => {
    const choose = useChooseCandidates({ applyTypeId: () => 5, fetchCandidates: vi.fn() })

    choose.seed([requirement()])

    const slot = choose.slotOf('node-a')
    expect(slot.total).toBe(2)
    expect(slot.truncated).toBe(false)
  })

  it('未 seed 过的节点返回空槽，不抛异常', () => {
    const choose = useChooseCandidates({ applyTypeId: () => 5, fetchCandidates: vi.fn() })
    expect(choose.slotOf('不存在').options).toEqual([])
    expect(choose.optionsFor('不存在', [1])).toEqual([{ id: 1, name: '#1' }])
  })
})

describe('useChooseCandidates · optionsFor（已选项永不丢标签）', () => {
  it('已选项不在当前结果里时补回到列表，姓名取自历史见过的缓存', async () => {
    const choose = useChooseCandidates({
      applyTypeId: () => 5,
      fetchCandidates: async () => page([{ id: 2, name: '李四' }], 1)
    })
    choose.seed([requirement()]) // 缓存里因此有 1→张三

    await choose.search('node-a', '李')

    const options = choose.optionsFor('node-a', [1])
    expect(options[0]).toEqual({ id: 1, name: '张三' })
    expect(options.map((item) => item.id)).toContain(2)
  })

  it('已选项从未见过姓名时退化为 #id（明确不可读，而不是静默空白）', () => {
    const choose = useChooseCandidates({ applyTypeId: () => 5, fetchCandidates: vi.fn() })
    choose.seed([requirement()])
    expect(choose.optionsFor('node-a', [777])[0]).toEqual({ id: 777, name: '#777' })
  })

  it('没有已选项时原样返回当前结果（不复制、不重排）', () => {
    const choose = useChooseCandidates({ applyTypeId: () => 5, fetchCandidates: vi.fn() })
    choose.seed([requirement()])
    const options = choose.optionsFor('node-a', [])
    expect(options).toEqual([
      { id: 1, name: '张三' },
      { id: 2, name: '李四' }
    ])
  })
})

describe('useChooseCandidates · search（键入即搜）', () => {
  it('按 trim 后的关键字走分页搜索，参数与页大小正确', async () => {
    const fetchCandidates = vi.fn(async () => page([{ id: 3, name: '王五' }], 1))
    const choose = useChooseCandidates({ applyTypeId: () => 9, fetchCandidates })
    choose.seed([requirement()])

    await choose.search('node-a', '  王  ')

    expect(fetchCandidates).toHaveBeenCalledWith(9, 'node-a', '王', 1, CHOOSE_CANDIDATE_PAGE_SIZE)
    const slot = choose.slotOf('node-a')
    expect(slot.options.map((item) => item.id)).toEqual([3])
    expect(slot.keyword).toBe('王')
    expect(slot.loading).toBe(false)
  })

  it('搜索结果仍有剩余时保持 truncated（提示继续缩小范围）', async () => {
    const choose = useChooseCandidates({
      applyTypeId: () => 5,
      fetchCandidates: async () => page([{ id: 3, name: '王五' }], 40)
    })
    choose.seed([requirement()])

    await choose.search('node-a', '王')

    expect(choose.slotOf('node-a').total).toBe(40)
    expect(choose.slotOf('node-a').truncated).toBe(true)
  })

  it('丢弃过期响应：慢的旧请求不能覆盖快的新请求', async () => {
    const first = deferred<CandidatePage>()
    const second = deferred<CandidatePage>()
    const choose = useChooseCandidates({
      applyTypeId: () => 5,
      fetchCandidates: (_id, _nodeKey, keyword) => (keyword === '张' ? first.promise : second.promise)
    })
    choose.seed([requirement()])

    const p1 = choose.search('node-a', '张')
    const p2 = choose.search('node-a', '李')
    second.resolve(page([{ id: 7, name: '李四' }], 1))
    await p2
    first.resolve(page([{ id: 9, name: '张三' }], 1))
    await p1

    expect(choose.slotOf('node-a').options.map((item) => item.id)).toEqual([7])
    expect(choose.slotOf('node-a').keyword).toBe('李')
  })

  it('搜索失败保留原选项（清空会被读成"没有匹配的人"）', async () => {
    const choose = useChooseCandidates({
      applyTypeId: () => 5,
      fetchCandidates: async () => {
        throw new Error('boom')
      }
    })
    choose.seed([requirement()])

    await choose.search('node-a', '张')

    const slot = choose.slotOf('node-a')
    expect(slot.options.map((item) => item.id)).toEqual([1, 2])
    expect(slot.loading).toBe(false)
  })

  it('未 seed 的节点不发请求', async () => {
    const fetchCandidates = vi.fn()
    const choose = useChooseCandidates({ applyTypeId: () => 5, fetchCandidates })

    await choose.search('不存在', '张')

    expect(fetchCandidates).not.toHaveBeenCalled()
  })

  it('关键字清空 = 回到首屏（仍然以服务端结果为准，而不是本地过滤）', async () => {
    const fetchCandidates = vi.fn(async (_id: number, _nodeKey: string, keyword: string) =>
      keyword === '' ? page([{ id: 1, name: '张三' }], 100) : page([{ id: 3, name: '王五' }], 1)
    )
    const choose = useChooseCandidates({ applyTypeId: () => 5, fetchCandidates })
    choose.seed([requirement()])

    await choose.search('node-a', '王')
    await choose.search('node-a', '')

    expect(fetchCandidates).toHaveBeenLastCalledWith(5, 'node-a', '', 1, CHOOSE_CANDIDATE_PAGE_SIZE)
    expect(choose.slotOf('node-a').options.map((item) => item.id)).toEqual([1])
    expect(choose.slotOf('node-a').keyword).toBe('')
  })
})

describe('useChooseCandidates · seed 与检索的相处（不打断输入、也不停留旧范围）', () => {
  it('正在检索的节点：保留关键字，并按新范围重跑一次搜索', async () => {
    const fetchCandidates = vi.fn(async () => page([{ id: 3, name: '王五' }], 1))
    const choose = useChooseCandidates({ applyTypeId: () => 5, fetchCandidates })
    choose.seed([requirement()])
    await choose.search('node-a', '王')

    choose.seed([requirement({ scope: 'ROLE', scopeValue: 'admin', candidateTotal: 30, candidatesTruncated: true })])
    await flush()

    expect(choose.slotOf('node-a').keyword).toBe('王')
    expect(fetchCandidates).toHaveBeenLastCalledWith(5, 'node-a', '王', 1, CHOOSE_CANDIDATE_PAGE_SIZE)
    expect(choose.slotOf('node-a').total).toBe(1)
  })

  it('未检索的节点：seed 用新的首屏整体替换（不会残留上一个范围的选项）', () => {
    const choose = useChooseCandidates({ applyTypeId: () => 5, fetchCandidates: vi.fn() })
    choose.seed([requirement()])

    choose.seed([requirement({ candidates: [{ id: 8, name: '赵六' }] })])

    expect(choose.slotOf('node-a').options.map((item) => item.id)).toEqual([8])
  })

  it('新预览里已消失的节点被整体清除（不再出现幽灵选择器状态）', () => {
    const choose = useChooseCandidates({ applyTypeId: () => 5, fetchCandidates: vi.fn() })
    choose.seed([requirement(), requirement({ nodeKey: 'node-b', nodeName: '总监审批' })])

    choose.seed([requirement()])

    expect(choose.slotOf('node-b').options).toEqual([])
    expect(Object.keys(choose.slots.value)).toEqual(['node-a'])
  })
})

describe('useChooseCandidates · reset', () => {
  it('清空槽位与姓名缓存，避免把上一个类型的人带过去', async () => {
    const choose = useChooseCandidates({ applyTypeId: () => 5, fetchCandidates: vi.fn() })
    choose.seed([requirement()])

    choose.reset()

    expect(Object.keys(choose.slots.value)).toEqual([])
    // 姓名缓存也清掉：否则换了类型后，同 id 的人会显示出上一个类型的姓名
    expect(choose.optionsFor('node-a', [1])).toEqual([{ id: 1, name: '#1' }])
  })
})
