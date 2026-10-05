import { describe, expect, it } from 'vitest'
import { FLOW_CODE_MAX_LENGTH, suggestCopyCode } from '@/utils/approvalFlowCode'

/**
 * 复制编码预填建议的单测（ · W4-B）。
 *
 * <p>锁定的是一条会被用户直接看见的失败模式：预填的编码**超过 32 位**时，
 * 用户点"复制"会被后端以「编码格式非法」拒绝 —— 而用户根本没改过这个字段，
 * 只会觉得系统莫名其妙。因此"截断"必须有断言守着。
 */
describe('suggestCopyCode', () => {
  it('普通编码 → 追加 _COPY', () => {
    expect(suggestCopyCode('PURCHASE_FLOW')).toBe('PURCHASE_FLOW_COPY')
  })

  it('短编码同样可用（不因过短而截掉自己）', () => {
    expect(suggestCopyCode('AB')).toBe('AB_COPY')
  })

  it('源编码已用满 32 位 → 结果仍不超过上限，且以 _COPY 结尾', () => {
    const source = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ123456'
    expect(source).toHaveLength(FLOW_CODE_MAX_LENGTH)

    const suggestion = suggestCopyCode(source)

    expect(suggestion.length).toBeLessThanOrEqual(FLOW_CODE_MAX_LENGTH)
    expect(suggestion.length).toBe(FLOW_CODE_MAX_LENGTH)
    expect(suggestion.endsWith('_COPY')).toBe(true)
    expect(suggestion).toBe('ABCDEFGHIJKLMNOPQRSTUVWXYZ1_COPY')
  })

  it('源编码本身以 _COPY 结尾 → 建议值不变形（仍只是"截断 + 加后缀"，不做去重猜测）', () => {
    // 这种命名的去重是**后端**的事（唯一索引 + 派生循环），前端不去猜"是不是要加 2"
    expect(suggestCopyCode('PURCHASE_COPY')).toBe('PURCHASE_COPY_COPY')
  })

  it('空 / 空白源编码 → 返回空串（交给后端派生，而不是预填一个必然非法的值）', () => {
    expect(suggestCopyCode('')).toBe('')
    expect(suggestCopyCode('   ')).toBe('')
  })

  it('建议值始终满足后端的编码格式（字母开头 + 字母数字下划线）', () => {
    const pattern = /^[A-Za-z][A-Za-z0-9_]{1,31}$/
    const samples = ['AB', 'PURCHASE_FLOW', 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789']
    for (const sample of samples) {
      const suggestion = suggestCopyCode(sample)
      expect(suggestion).toMatch(pattern)
    }
  })
})
