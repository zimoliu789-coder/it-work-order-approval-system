import { describe, expect, it, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import TransferButton from '@/components/TransferButton.vue'
import { epStubs } from '@/test/stubs'
import { elMessage, elMessageBox } from '@/test/setup'
import type { OrderItem, TransferCandidate } from '@/types/order'
// 放在 import 区（vitest 会把 vi.mock 提升到所有 import 之前），
// 使此处拿到的是被 mock 的 orderApi，同时满足「import 集中在顶部」的 lint 约定。
import { orderApi } from '@/api/order'

/**
 * TransferButton 组件级测试（三波补做·第三波·）
 *
 * 转交是**改派执行人**的敏感动作：选错人会让工单交付/收回权限落到错误的人手里，
 * 因此「必选对象 + 必填原因 + 二次确认 + 成功后通知父组件刷新」缺一不可。
 * 这些约束散落在挂载后的交互流程里，纯函数单测覆盖不到。
 *
 * 覆盖点：
 * 1. 打开时按工单 id 拉取候选人；
 * 2. 未选对象 / 未填原因 → 拦截并提示，不发起请求；
 * 3. 二次确认框被取消 → 不发起请求；
 * 4. 正常提交 → 携带 (id, { newHandlerId, comment }) 调接口并 emit done；
 * 5. 无候选人 → 展示告警且下拉禁用。
 */

vi.mock('@/api/order', () => ({
  orderApi: {
    transferCandidates: vi.fn(),
    transfer: vi.fn()
  }
}))

const ORDER = {
  id: 77,
  orderNo: 'WO-2026-0001',
  deviceId: 5,
  deviceName: '戴尔笔记本（PC-0005）',
  applicantName: '张伟'
} as unknown as OrderItem

const CANDIDATES: TransferCandidate[] = [
  { userId: 12, displayName: '李四', inFlightCount: 1 },
  { userId: 13, displayName: '王五', inFlightCount: 3 }
]

const transferCandidates = vi.mocked(orderApi.transferCandidates)
const transfer = vi.mocked(orderApi.transfer)

/** 挂载结果类型别名，避免在辅助函数上重复书写泛型 */
type Wrapper = ReturnType<typeof mount>

function mountButton() {
  return mount(TransferButton, {
    props: { order: ORDER },
    global: { components: epStubs }
  })
}

function buttonByText(wrapper: Wrapper, text: string) {
  const found = wrapper.findAll('button').find((b) => b.text().includes(text))
  if (!found) {
    throw new Error(`未找到文本包含「${text}」的按钮`)
  }
  return found
}

async function openDialog(wrapper: Wrapper): Promise<void> {
  await buttonByText(wrapper, '转交').trigger('click')
  await flushPromises()
}

beforeEach(() => {
  transferCandidates.mockResolvedValue(CANDIDATES)
  transfer.mockResolvedValue(undefined)
  vi.mocked(elMessageBox.confirm).mockResolvedValue('confirm')
})

describe('TransferButton', () => {
  it('点击打开弹窗并按工单 id 拉取候选执行人', async () => {
    const wrapper = mountButton()
    await openDialog(wrapper)

    expect(transferCandidates).toHaveBeenCalledWith(77)
    expect(wrapper.text()).toContain('李四（在办 1 单）')
    expect(wrapper.text()).toContain('王五（在办 3 单）')
  })

  it('未选择转交对象时拦截并提示，不发请求', async () => {
    const wrapper = mountButton()
    await openDialog(wrapper)

    await buttonByText(wrapper, '确认转交').trigger('click')
    await flushPromises()

    expect(transfer).not.toHaveBeenCalled()
    expect(elMessage.warning).toHaveBeenCalledWith('请选择转交对象')
  })

  it('未填写转交原因时拦截并提示，不发请求', async () => {
    const wrapper = mountButton()
    await openDialog(wrapper)

    await wrapper.find('select.el-select').setValue('12')
    await buttonByText(wrapper, '确认转交').trigger('click')
    await flushPromises()

    expect(transfer).not.toHaveBeenCalled()
    expect(elMessage.warning).toHaveBeenCalledWith('请填写转交原因')
  })

  it('用户在二次确认中取消时不发请求', async () => {
    vi.mocked(elMessageBox.confirm).mockRejectedValueOnce(new Error('cancel'))
    const wrapper = mountButton()
    await openDialog(wrapper)

    await wrapper.find('select.el-select').setValue('12')
    await wrapper.find('textarea.el-input').setValue('临时顶班')
    await buttonByText(wrapper, '确认转交').trigger('click')
    await flushPromises()

    expect(elMessageBox.confirm).toHaveBeenCalledTimes(1)
    expect(transfer).not.toHaveBeenCalled()
  })

  it('校验通过后携带 id 与载荷提交，并 emit done', async () => {
    const wrapper = mountButton()
    await openDialog(wrapper)

    await wrapper.find('select.el-select').setValue('13')
    await wrapper.find('textarea.el-input').setValue('  岗位交接  ')
    await buttonByText(wrapper, '确认转交').trigger('click')
    await flushPromises()

    expect(transfer).toHaveBeenCalledWith(77, { newHandlerId: 13, comment: '岗位交接' })
    expect(elMessage.success).toHaveBeenCalledTimes(1)
    expect(wrapper.emitted('done')).toHaveLength(1)
  })

  it('无候选人时展示告警且下拉禁用', async () => {
    transferCandidates.mockResolvedValueOnce([])
    const wrapper = mountButton()
    await openDialog(wrapper)

    expect(wrapper.text()).toContain('暂无可转交对象')
    expect(wrapper.find('select.el-select').attributes('disabled')).toBeDefined()
  })

  it('候选接口失败时按空列表处理，不抛错', async () => {
    transferCandidates.mockRejectedValueOnce(new Error('boom'))
    const wrapper = mountButton()
    await openDialog(wrapper)

    expect(wrapper.text()).toContain('暂无可转交对象')
  })
})
