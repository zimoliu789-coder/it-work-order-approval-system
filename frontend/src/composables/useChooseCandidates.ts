import { ref } from 'vue'
import { applyTypeApi } from '@/api/applyType'
import type { FlowChooseRequirement, FlowPreviewCandidate } from '@/types/approvalFlow'

/**
 * 「申请人自选审批人」候选池状态（ · W4-D · D-2）
 *
 * <h2>为什么需要它</h2>
 * 候选池在「范围 = 全部员工」时会随组织规模线性膨胀，后端因此把随预览下发的
 * `candidates` **截断为首屏子集**（`ApplyTypeServiceImpl.CANDIDATE_PREVIEW_LIMIT`），
 * 完整列表改由 `GET /apply-types/{id}/choose-candidates` 分页搜索提供。
 *
 * **截断与搜索必须同时到位**：只截断会让排在第一屏之后的人*真的选不到* —— 那是功能回归，不是优化。
 *
 * <h2>它负责的三件容易做错的事</h2>
 * 1. **首屏即用**：`seed()` 直接用预览下发的子集填充下拉，不为首屏多发一次请求；
 * 2. **键入即搜**：`search()` 走分页搜索接口，并做**请求序号守卫** ——
 *    键盘输入会连发多次请求，慢的旧响应绝不能覆盖快的新响应；
 * 3. **已选项永不丢标签**：`el-select` 的 remote 模式只在「当前 options 里找得到」时才显示 label，
 *    否则标签会退化成数字 id。因此 `optionsFor()` 会把已选项（从历史见过的姓名缓存里）补回列表。
 *
 * <h2>刻意不做的事</h2>
 * 不在这里做「所选人是否仍在范围内」的判定 —— 截断后前端根本无法知道成员关系，
 * 服务端在提交时会重算并给出准确提示（前端只是体验，服务端是唯一事实来源）。
 */

/** 一次搜索展示的候选人数（后端 size 上限 100，取一个「扫一眼」的值） */
export const CHOOSE_CANDIDATE_PAGE_SIZE = 20

/** 分页搜索结果的最小形状（只取用得到的两个字段，便于测试注入） */
export interface CandidatePage {
  records: FlowPreviewCandidate[]
  total: number
}

/** 某个节点的候选池状态 */
export interface CandidateSlot {
  /** 当前下拉里可见的候选（首屏 = 预览子集；搜索后 = 该页结果） */
  options: FlowPreviewCandidate[]
  /** 该范围的可选总人数；首屏时是「全范围人数」，搜索后是「命中人数」 */
  total: number
  /** 尚有未列出的候选人（true 时才提示「键入姓名搜索」） */
  truncated: boolean
  loading: boolean
  /** 最近一次搜索关键字（trim 后）；空串 = 首屏 */
  keyword: string
}

export interface UseChooseCandidatesOptions {
  /** 当前申请类型 id（搜索时随请求带走） */
  applyTypeId: () => number
  /** 分页搜索实现；默认走真实接口，测试可注入假实现 */
  fetchCandidates?: (
    id: number,
    nodeKey: string,
    keyword: string,
    page: number,
    size: number
  ) => Promise<CandidatePage>
}

/** 未 seed 过的节点（理论上不会出现）返回的空槽；只为避免调用方空判 */
const EMPTY_SLOT: CandidateSlot = { options: [], total: 0, truncated: false, loading: false, keyword: '' }

export function useChooseCandidates(options: UseChooseCandidatesOptions) {
  const fetchCandidates =
    options.fetchCandidates ??
    ((id: number, nodeKey: string, keyword: string, page: number, size: number) =>
      applyTypeApi.chooseCandidates(id, nodeKey, keyword, page, size))

  const slots = ref<Record<string, CandidateSlot>>({})
  /** 见过的 id → 姓名：保证已选项在「options 被搜索结果整体替换」之后仍能显示姓名 */
  const nameCache = new Map<number, string>()
  /** 每个节点最近一次搜索的序号，用于丢弃过期响应 */
  const searchSeq: Record<string, number> = {}

  function remember(list: FlowPreviewCandidate[] | undefined | null): void {
    if (!list) {
      return
    }
    for (const item of list) {
      if (item && typeof item.id === 'number') {
        nameCache.set(item.id, item.name)
      }
    }
  }

  /**
   * 用最新预览结果刷新各节点的首屏候选池。
   *
   * <p>对**正在检索中**（关键字非空）的节点：保留其关键字，并在本次刷新后按新范围**重跑一次搜索**。
   * 若只保留旧结果，用户改一个表单字段导致范围变化后，下拉会一直停留在旧范围的结果上；
   * 若直接重置，则搜索输入会被后台预览无声打断。
   */
  function seed(requirements: FlowChooseRequirement[]): void {
    const next: Record<string, CandidateSlot> = {}
    const needReSearch: string[] = []
    for (const requirement of requirements) {
      remember(requirement.candidates)
      const total = requirement.candidateTotal ?? requirement.candidates.length
      const truncated = requirement.candidatesTruncated ?? total > requirement.candidates.length
      const previous = slots.value[requirement.nodeKey]
      if (previous && previous.keyword !== '') {
        next[requirement.nodeKey] = { ...previous, total, truncated }
        needReSearch.push(requirement.nodeKey)
      } else {
        next[requirement.nodeKey] = {
          options: requirement.candidates,
          total,
          truncated,
          loading: false,
          keyword: ''
        }
      }
    }
    slots.value = next
    for (const nodeKey of needReSearch) {
      const current = slots.value[nodeKey]
      if (current) {
        void search(nodeKey, current.keyword)
      }
    }
  }

  function slotOf(nodeKey: string): CandidateSlot {
    return slots.value[nodeKey] ?? EMPTY_SLOT
  }

  /**
   * 下拉可见的候选 = 当前结果 + 已选项补齐。
   *
   * <p>补齐是**必须**的：远程搜索会把 `options` 整体换成本页结果，
   * 若用户先选了张三、再输入「李」把列表搜窄，`el-select` 在 options 里找不到张三，
   * 标签就会显示成 `1024` 这种数字 id（用户没法确认自己选的是谁）。
   */
  function optionsFor(nodeKey: string, selected: number[]): FlowPreviewCandidate[] {
    const base = slotOf(nodeKey).options
    if (selected.length === 0) {
      return base
    }
    const known = new Set(base.map((item) => item.id))
    const extra: FlowPreviewCandidate[] = []
    for (const id of selected) {
      if (!known.has(id)) {
        known.add(id)
        extra.push({ id, name: nameCache.get(id) ?? `#${id}` })
      }
    }
    return extra.length > 0 ? [...extra, ...base] : base
  }

  /** 按关键字搜索该节点的候选池；关键字为空 = 回到首屏 */
  async function search(nodeKey: string, keyword: string): Promise<void> {
    if (!slots.value[nodeKey]) {
      return
    }
    const trimmed = keyword.trim()
    const seq = (searchSeq[nodeKey] ?? 0) + 1
    searchSeq[nodeKey] = seq
    slots.value[nodeKey].loading = true
    try {
      const page = await fetchCandidates(options.applyTypeId(), nodeKey, trimmed, 1, CHOOSE_CANDIDATE_PAGE_SIZE)
      if (searchSeq[nodeKey] !== seq) {
        return
      }
      remember(page.records)
      const target = slots.value[nodeKey]
      if (!target) {
        return
      }
      target.options = page.records
      target.total = page.total
      target.truncated = page.total > page.records.length
      target.keyword = trimmed
    } catch {
      // 搜索失败保留原选项：清空会被用户读成「没有匹配的人」，比"列表没变"更误导
    } finally {
      if (searchSeq[nodeKey] === seq) {
        const target = slots.value[nodeKey]
        if (target) {
          target.loading = false
        }
      }
    }
  }

  /** 离开页面 / 切换申请类型时清空，避免把上一个类型的候选与姓名缓存带过去 */
  function reset(): void {
    slots.value = {}
    nameCache.clear()
    for (const key of Object.keys(searchSeq)) {
      delete searchSeq[key]
    }
  }

  return { slots, slotOf, optionsFor, seed, search, reset }
}
