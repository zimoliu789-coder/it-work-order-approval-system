import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import PendingAttachmentPicker from '@/components/PendingAttachmentPicker.vue'
import { epStubs } from '@/test/stubs'
import { elMessage } from '@/test/setup'
import { ATTACHMENT_BIZ_CONFIG } from '@/types/attachment'

/**
 * PendingAttachmentPicker 组件级测试（三波补做·第三波·）
 *
 * 这是「随单提交」的关键交互件：用户在主操作成功前挑选本地附件，
 * 组件负责**即时**的大小 / 类型 / 数量 / 去重校验并 emit 结果数组。
 * 校验口径若有偏差，用户会等到上传阶段才被后端拒绝 —— 属体验级 Critical。
 *
 * 覆盖点：
 * 1. 提示文案随 bizType（是否仅图片）与 maxSizeMb / maxCount 变化；
 * 2. accept 取自同源附件配置；
 * 3. 选择合法文件 → emit update:modelValue；
 * 4. 超大小 / 类型不符 / 同名同大小重复 → 拦截或去重，不产生脏数据；
 * 5. 到达数量上限 → 提示且不再追加；
 * 6. 列表项删除 → emit 缩减后的数组。
 */

function mountPicker(props: Record<string, unknown> = {}) {
  return mount(PendingAttachmentPicker, {
    props: {
      modelValue: [] as File[],
      bizType: 'APPLY_ATTACHMENT',
      ...props
    },
    global: { components: epStubs }
  })
}

/** 用指定文件名 / 字节数构造一个 File */
function makeFile(name: string, bytes = 16): File {
  return new File([new Uint8Array(bytes)], name)
}

/** 模拟用户在原生 file input 上选中文件并触发 change */
async function pickFiles(wrapper: ReturnType<typeof mountPicker>, files: File[]): Promise<void> {
  const input = wrapper.find('input[type="file"]')
  Object.defineProperty(input.element, 'files', { value: files, configurable: true })
  await input.trigger('change')
}

describe('PendingAttachmentPicker', () => {
  it('文档类业务提示「支持文档 / 图片 / 压缩包」并带上限与数量', () => {
    const wrapper = mountPicker({ bizType: 'APPLY_ATTACHMENT', maxSizeMb: 10, maxCount: 10 })
    const hint = wrapper.find('.ts-pick__hint').text()
    expect(hint).toContain('支持文档 / 图片 / 压缩包')
    expect(hint).toContain('10MB')
    expect(hint).toContain('10 个')
  })

  it('图片类业务提示「仅支持图片」', () => {
    const wrapper = mountPicker({ bizType: 'RETURN_PHOTO' })
    expect(wrapper.find('.ts-pick__hint').text()).toContain('仅支持图片')
  })

  it('accept 与标签取自同源附件配置', () => {
    const wrapper = mountPicker({ bizType: 'RETURN_PHOTO' })
    const input = wrapper.find('input[type="file"]')
    expect(input.attributes('accept')).toBe(ATTACHMENT_BIZ_CONFIG.RETURN_PHOTO.accept)
    // 未传 label 时使用默认文案
    expect(wrapper.find('.ts-pick__label').text()).toBe('附件（可选）')
  })

  it('选择合法文件后 emit update:modelValue', async () => {
    const wrapper = mountPicker()
    const file = makeFile('说明.pdf', 128)
    await pickFiles(wrapper, [file])

    const emitted = wrapper.emitted('update:modelValue')
    expect(emitted).toHaveLength(1)
    const result = emitted?.[0]?.[0] as File[]
    expect(result).toHaveLength(1)
    expect(result[0].name).toBe('说明.pdf')
  })

  it('超过大小上限的文件被拦截且给出提示', async () => {
    // maxSizeMb=0.001 → 约 1048 字节上限
    const wrapper = mountPicker({ maxSizeMb: 0.001 })
    await pickFiles(wrapper, [makeFile('big.pdf', 4096)])

    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    expect(elMessage.warning).toHaveBeenCalledTimes(1)
  })

  it('图片类业务下非图片扩展名被拦截', async () => {
    const wrapper = mountPicker({ bizType: 'FAULT_PHOTO' })
    await pickFiles(wrapper, [makeFile('report.pdf', 16)])

    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    expect(elMessage.warning).toHaveBeenCalledTimes(1)
  })

  it('同名同大小的重复文件被去重（不产生新增，故不回传）', async () => {
    const existing = makeFile('photo.png', 32)
    const wrapper = mountPicker({ bizType: 'RETURN_PHOTO', modelValue: [existing] })
    await pickFiles(wrapper, [makeFile('photo.png', 32)])

    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })

  it('单次选择多个文件时逐个校验，合法者进入结果、非法者跳过', async () => {
    const wrapper = mountPicker({ bizType: 'RETURN_PHOTO', maxSizeMb: 5 })
    await pickFiles(wrapper, [
      makeFile('ok.png', 64),
      makeFile('bad.pdf', 64), // 图片类不允许 pdf
      makeFile('ok2.jpg', 64)
    ])

    const result = wrapper.emitted('update:modelValue')?.[0]?.[0] as File[]
    expect(result.map((f) => f.name)).toEqual(['ok.png', 'ok2.jpg'])
    expect(elMessage.warning).toHaveBeenCalledTimes(1)
  })

  it('达到数量上限时提示且不再触发选择', async () => {
    const wrapper = mountPicker({ maxCount: 1, modelValue: [makeFile('a.pdf', 8)] })
    await wrapper.find('.ts-pick__head .el-button').trigger('click')

    expect(elMessage.warning).toHaveBeenCalledTimes(1)
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })

  it('删除列表项后 emit 缩减后的数组', async () => {
    const first = makeFile('one.pdf', 8)
    const second = makeFile('two.pdf', 8)
    const wrapper = mountPicker({ modelValue: [first, second] })

    const rows = wrapper.findAll('.ts-pick__item')
    expect(rows).toHaveLength(2)
    await rows[0].find('button').trigger('click')

    const result = wrapper.emitted('update:modelValue')?.[0]?.[0] as File[]
    expect(result.map((f) => f.name)).toEqual(['two.pdf'])
  })
})
