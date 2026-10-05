import { describe, expect, it } from 'vitest'
import {
  ATTACHMENT_BIZ_CONFIG,
  attachmentBizConfig,
  attachmentBizLabel,
  attachmentExt,
  formatFileSize,
  isAllowedAttachment
} from '@/types/attachment'

/**
 * 附件展示与本地校验规则单测（，）
 *
 * 为什么值得单测：这些纯函数决定「用户在选文件的当下看到什么」——
 * 提示的格式清单、大小文案、类型判定若与后端不一致，用户会先被前端放行、
 * 再被后端拒绝（或反之），体验割裂且很难定位。把口径固化为断言，
 * 新增 / 调整附件类型时能立刻暴露「前端白名单没跟上」的问题。
 */
describe('附件业务配置（与后端 AttachmentBizType 同名）', () => {
  it('五类业务配置齐全，中文名与后端一致', () => {
    expect(Object.keys(ATTACHMENT_BIZ_CONFIG).sort()).toEqual([
      'APPLY_ATTACHMENT',
      'CUSTOM_ORDER',
      'FAULT_PHOTO',
      'REJECT_ATTACHMENT',
      'RETURN_PHOTO'
    ])
    expect(attachmentBizLabel('APPLY_ATTACHMENT')).toBe('申请附件')
    expect(attachmentBizLabel('REJECT_ATTACHMENT')).toBe('驳回附件')
    expect(attachmentBizLabel('RETURN_PHOTO')).toBe('归还照片')
    expect(attachmentBizLabel('FAULT_PHOTO')).toBe('故障照片')
    expect(attachmentBizLabel('CUSTOM_ORDER')).toBe('自定义工单附件')
  })

  it('照片类（归还 / 故障）仅图片；文档类允许文档', () => {
    expect(attachmentBizConfig('RETURN_PHOTO')?.imageOnly).toBe(true)
    expect(attachmentBizConfig('FAULT_PHOTO')?.imageOnly).toBe(true)
    expect(attachmentBizConfig('APPLY_ATTACHMENT')?.imageOnly).toBe(false)
    expect(attachmentBizConfig('REJECT_ATTACHMENT')?.imageOnly).toBe(false)
    // 自定义工单附件：文档类（含 pdf / 图片 / 压缩包）
    expect(attachmentBizConfig('CUSTOM_ORDER')?.imageOnly).toBe(false)
  })

  it('accept 与类型绑定：图片类只含图片扩展名，文档类含 pdf', () => {
    expect(attachmentBizConfig('FAULT_PHOTO')?.accept).toContain('.png')
    expect(attachmentBizConfig('FAULT_PHOTO')?.accept).not.toContain('.pdf')
    expect(attachmentBizConfig('APPLY_ATTACHMENT')?.accept).toContain('.pdf')
  })

  it('未知类型：配置返回 null，中文名回落原值（不抛错、不显示 undefined）', () => {
    expect(attachmentBizConfig('NOT_A_TYPE')).toBeNull()
    expect(attachmentBizConfig(null)).toBeNull()
    expect(attachmentBizLabel('NOT_A_TYPE')).toBe('NOT_A_TYPE')
    expect(attachmentBizLabel(null)).toBe('附件')
  })
})

describe('扩展名解析 attachmentExt', () => {
  it('取最后一段并转小写', () => {
    expect(attachmentExt('材料.pdf')).toBe('pdf')
    expect(attachmentExt('a.PNG')).toBe('png')
    expect(attachmentExt('/tmp/x/y.JPEG')).toBe('jpeg')
  })

  it('无扩展名 / 以点结尾 / 空值 → 空串', () => {
    expect(attachmentExt('noext')).toBe('')
    expect(attachmentExt('trailing.')).toBe('')
    expect(attachmentExt('')).toBe('')
    expect(attachmentExt(null)).toBe('')
  })
})

describe('类型允许判定 isAllowedAttachment', () => {
  it('文档类：pdf/doc/图片/压缩包均可，图片类：仅图片', () => {
    expect(isAllowedAttachment('a.pdf', false)).toBe(true)
    expect(isAllowedAttachment('a.zip', false)).toBe(true)
    expect(isAllowedAttachment('a.png', false)).toBe(true)
    expect(isAllowedAttachment('a.pdf', true)).toBe(false)
    expect(isAllowedAttachment('a.png', true)).toBe(true)
  })

  it('大小写不敏感；无扩展名一律拒绝', () => {
    expect(isAllowedAttachment('A.PDF', false)).toBe(true)
    expect(isAllowedAttachment('A.JPG', true)).toBe(true)
    expect(isAllowedAttachment('noext', false)).toBe(false)
  })
})

describe('文件大小展示 formatFileSize', () => {
  it('B / KB / MB / GB 逐级换算，去掉多余的 .0', () => {
    expect(formatFileSize(0)).toBe('0 B')
    expect(formatFileSize(512)).toBe('512 B')
    expect(formatFileSize(1024)).toBe('1 KB')
    expect(formatFileSize(1536)).toBe('1.5 KB')
    expect(formatFileSize(1024 * 1024)).toBe('1 MB')
    expect(formatFileSize(10 * 1024 * 1024)).toBe('10 MB')
    expect(formatFileSize(1024 * 1024 * 1024)).toBe('1 GB')
  })

  it('非法输入回落 "-"（避免出现 NaN 单位）', () => {
    expect(formatFileSize(null)).toBe('-')
    expect(formatFileSize(undefined)).toBe('-')
    expect(formatFileSize(-1)).toBe('-')
    expect(formatFileSize(Number.NaN)).toBe('-')
  })
})
