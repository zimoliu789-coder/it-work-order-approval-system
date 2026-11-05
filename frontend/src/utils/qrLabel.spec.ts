import { describe, expect, it } from 'vitest'
import { create as qrCreate } from 'qrcode'
import {
  buildPrintDocument,
  buildTspl,
  DEFAULT_LABEL_SIZE_KEY,
  effectiveCharCount,
  findLabelSize,
  LABEL_CSS,
  LABEL_SIZES,
  mmToDots,
  verticalAdvanceEm,
  nearestTsplFont,
  pageSizeCss,
  qrPayload,
  renderLabelHtml,
  toPrintableDevices,
  tsplCellWidth,
  labelLayout
} from './qrLabel'

const SIZE_60x30 = findLabelSize('60x30')
const SIZE_40x30 = findLabelSize('40x30')

describe('qrPayload', () => {
  it('带上 ASSET: 前缀 —— 与后端 ScanCodeParser 的白名单前缀一致', () => {
    expect(qrPayload('IT-2024-0001')).toBe('ASSET:IT-2024-0001')
  })

  it('两侧空白会被去掉（台账里手输编号常带空格）', () => {
    expect(qrPayload('  A1  ')).toBe('ASSET:A1')
  })

  it('空 / null / 空白 → 空串（调用方据此跳过该设备，不印空码）', () => {
    expect(qrPayload('')).toBe('')
    expect(qrPayload(null)).toBe('')
    expect(qrPayload('   ')).toBe('')
    expect(qrPayload(undefined)).toBe('')
  })
})

describe('mmToDots', () => {
  it('203dpi 标签机是 8 点/mm', () => {
    expect(mmToDots(1)).toBe(8)
    expect(mmToDots(30)).toBe(240)
    expect(mmToDots(2.5)).toBe(20)
  })
})

describe('effectiveCharCount', () => {
  it('中文按 1.7 个拉丁字符宽计', () => {
    // 'PC' = 2 个拉丁字符；'一体机' = 3 个汉字 × 1.7
    expect(effectiveCharCount('PC一体机')).toBeCloseTo(2 + 1.7 * 3, 5)
  })

  it('空串按 1 算，避免后面除以 0', () => {
    expect(effectiveCharCount('')).toBe(1)
  })
})

describe('labelLayout · 按实拍图校准的口径', () => {
  it('二维码取「高度 86%」与「宽度 34%」中的较小者 —— 60×30 上是后者（占高度约 68%）', () => {
    const L = labelLayout(SIZE_60x30, '50003PD20180218', 'PC一体机')
    // 宽度 34% = 20.4mm < 高度 30 × 86% = 25.8mm
    expect(L.qrSideMm).toBeCloseTo(20.4, 5)
    expect(L.qrSideMm / L.heightMm).toBeGreaterThan(0.65)
    expect(L.qrSideMm / L.heightMm).toBeLessThan(0.72)
  })

  it('二维码垂直居中', () => {
    const L = labelLayout(SIZE_60x30, 'A1', '设备')
    expect(L.qrYMm).toBeCloseTo((L.heightMm - L.qrSideMm) / 2, 5)
  })

  it('文字区在二维码右侧，且宽度为正', () => {
    const L = labelLayout(SIZE_60x30, 'A1', '设备')
    expect(L.textXMm).toBeGreaterThan(L.qrXMm + L.qrSideMm)
    expect(L.textWidthMm).toBeGreaterThan(10)
  })

  it('资产编号字号不溢出文字区（这是「长编号也不贴边」的保证）', () => {
    const assetNo = '50003PD20180218'
    const L = labelLayout(SIZE_60x30, assetNo, 'PC一体机')
    const estimated = effectiveCharCount(assetNo) * L.assetFontMm * 0.62
    expect(estimated).toBeLessThanOrEqual(L.textWidthMm + 0.01)
  })

  it('编号越长字号越小（同样宽度下必须让位）', () => {
    const short = labelLayout(SIZE_60x30, 'A1', '设备')
    const long = labelLayout(SIZE_60x30, 'A'.repeat(24), '设备')
    expect(long.assetFontMm).toBeLessThan(short.assetFontMm)
  })

  it('设备名称比资产编号小（维持「上行大字、下行小字」）', () => {
    const L = labelLayout(SIZE_60x30, 'A1', '设备')
    expect(L.nameFontMm).toBeLessThan(L.assetFontMm)
  })

  it('两行文字整体在标签内（不越界）', () => {
    const L = labelLayout(SIZE_60x30, '50003PD20180218', 'PC一体机')
    expect(L.assetYMm).toBeGreaterThanOrEqual(0)
    expect(L.nameYMm + L.nameFontMm * 1.35).toBeLessThanOrEqual(L.heightMm + 0.01)
  })

  it('小标签（40×30）不再按宽度 42% 放码 —— 码边由高度决定，文字区仍有可用宽度', () => {
    const L = labelLayout(SIZE_40x30, 'A1', '设备')
    expect(L.qrSideMm).toBeCloseTo(13.6, 5) // 40 * 0.3
    expect(L.textWidthMm).toBeGreaterThan(20)
  })
})

describe('findLabelSize', () => {
  it('已知 key 返回对应档', () => {
    expect(findLabelSize('40x60').heightMm).toBe(60)
  })

  it('未知 / 空 key 回落到默认档（25×60 竖版，实物照片确认）', () => {
    expect(findLabelSize('nope').key).toBe(DEFAULT_LABEL_SIZE_KEY)
    expect(findLabelSize(null).key).toBe(DEFAULT_LABEL_SIZE_KEY)
    expect(findLabelSize(undefined).widthMm).toBe(25)
    expect(findLabelSize(undefined).heightMm).toBe(60)
  })

  it('60×25 这一档：二维码与两行文字都在纸内，且文字区不被压没', () => {
    const size = findLabelSize('60x25')
    const L = labelLayout(size, '50008PD20190003', 'PC一体机')
    // 二维码完整在纸内
    expect(L.qrXMm).toBeGreaterThanOrEqual(0)
    expect(L.qrYMm).toBeGreaterThanOrEqual(0)
    expect(L.qrXMm + L.qrSideMm).toBeLessThanOrEqual(size.widthMm)
    expect(L.qrYMm + L.qrSideMm).toBeLessThanOrEqual(size.heightMm)
    // 文字区足够放下 16 位编号（字号不低于 3mm，约 8.5pt）
    expect(L.textWidthMm).toBeGreaterThan(30)
    expect(L.assetFontMm).toBeGreaterThan(3)
    // 两行文字不越过下边界
    expect(L.nameYMm + L.nameFontMm * 1.35).toBeLessThanOrEqual(size.heightMm)
  })

  it('竖版 25×60 按需求方给定坐标排：码 3/4/20，文字区从 27 开始、长 30', () => {
    const size = findLabelSize('25x60')
    const L = labelLayout(size, '50008PD20190003', 'PC一体机')

    expect(L.mode).toBe('vertical')

    // 二维码：左边距 3、上边距 4、边长 20 → 右边缘距纸右 2mm
    expect(L.qrXMm).toBeCloseTo(3, 5)
    expect(L.qrYMm).toBeCloseTo(4, 5)
    expect(L.qrSideMm).toBeCloseTo(20, 5)
    expect(size.widthMm - (L.qrXMm + L.qrSideMm)).toBeCloseTo(2, 5)

    // 文字区：4 + 20 + 3 = 27 起，60 − 27 − 3 = 30 长
    expect(L.textTopMm).toBeCloseTo(27, 5)
    expect(L.textHeightMm).toBeCloseTo(30, 5)

    // 左列设备名贴左边距 3，右列编号贴右边距 3（两列不重叠）
    expect(L.nameXMm).toBeCloseTo(3, 5)
    expect(L.assetXMm + L.assetFontMm).toBeCloseTo(22, 5)
    expect(L.assetXMm).toBeGreaterThan(L.nameXMm)
    expect(L.nameXMm + L.nameFontMm * 1.15).toBeLessThan(L.assetXMm)
  })

  it('竖版字号按「旋转后沿文字方向的占位」反算，长编号不冲出去', () => {
    const size = findLabelSize('25x60')
    const L = labelLayout(size, '50008PD20190003', 'PC一体机')

    // 15 位数字 × 0.63em ≈ 9.45em，30mm 长的文字区反算出约 3.1mm（≈9pt）
    expect(verticalAdvanceEm('50008PD20190003')).toBeCloseTo(15 * 0.63, 5)
    expect(L.assetFontMm).toBeGreaterThan(2.9)
    expect(L.assetFontMm).toBeLessThan(3.4)
    // 排出来必须**留有余量**：超一点点会被 overflow:hidden 直接切掉末位
    expect(L.assetFontMm * verticalAdvanceEm('50008PD20190003')).toBeLessThan(L.textHeightMm * 0.995)

    // 设备名「PC一体机」= 0.62×2 + 1×3 = 4.24em，但被编号的 1.35 倍封顶
    expect(verticalAdvanceEm('PC一体机')).toBeCloseTo(0.63 * 2 + 3, 5)
    expect(L.nameFontMm).toBeGreaterThan(L.assetFontMm)
    expect(L.nameFontMm).toBeLessThanOrEqual(L.assetFontMm * 1.35 + 1e-9)
    expect(L.nameFontMm * verticalAdvanceEm('PC一体机')).toBeLessThan(L.textHeightMm * 0.995)
  })

  it('竖版渲染：左列贴 left、右列贴 right，字符是转 90°（sideways）不是正立', () => {
    const html = renderLabelHtml(
      { id: 1, assetNo: '50008PD20190003', deviceName: 'PC一体机' },
      findLabelSize('25x60'),
      '<svg></svg>'
    )
    expect(html).toContain('is-vertical')
    expect(html).toContain('is-vtext')
    // 设备名靠 left、资产编号靠 right —— 编号再长也不会顶出右边缘
    expect(html).toContain('left:3mm')
    expect(html).toContain('right:3mm')
    expect(html).toContain('top:27mm')

    // 竖排样式在共用的 CSS 常量里（预览与打印共用同一份）
    expect(LABEL_CSS).toContain('writing-mode: vertical-rl')
    expect(LABEL_CSS).toContain('text-orientation: sideways')
    expect(LABEL_CSS).not.toContain('text-orientation: upright')
  })

  it('预设里确实有 60×30 这一档', () => {
    expect(LABEL_SIZES.some((s) => s.key === '60x30')).toBe(true)
  })
})

describe('renderLabelHtml', () => {
  const device = { id: 1, assetNo: 'IT-2024-0001', deviceName: '笔记本电脑' }

  it('包含二维码 SVG 与两行文字', () => {
    const html = renderLabelHtml(device, SIZE_60x30, '<svg id="q"></svg>')
    expect(html).toContain('<svg id="q"></svg>')
    expect(html).toContain('IT-2024-0001')
    expect(html).toContain('笔记本电脑')
  })

  it('标签尺寸写成 mm（预览与打印共用同一份，才能所见即所得）', () => {
    const html = renderLabelHtml(device, SIZE_60x30, '')
    expect(html).toContain('width:60mm')
    expect(html).toContain('height:30mm')
  })

  it('设备名称里的 HTML 会被转义（不能因为名字里带尖括号就破坏排版）', () => {
    const html = renderLabelHtml({ id: 2, assetNo: 'A1', deviceName: '<img src=x onerror=1>' }, SIZE_60x30, '')
    expect(html).not.toContain('<img src=x')
    expect(html).toContain('&lt;img src=x onerror=1&gt;')
  })

  it('资产编号为空时不抛异常（由调用方过滤，这里只保证不炸）', () => {
    expect(() => renderLabelHtml({ id: 3, assetNo: null, deviceName: null }, SIZE_60x30, '')).not.toThrow()
  })
})

describe('buildPrintDocument', () => {
  it('@page 必须带标签纸尺寸且 margin 为 0（否则会按 A4 排版、标签缩到纸中间）', () => {
    const doc = buildPrintDocument(['<div>x</div>'], SIZE_60x30, '设备标签')
    expect(doc).toContain('@page { size: 60mm 30mm; margin: 0; }')
  })

  it('每个标签之后分页，最后一个不再分页（避免多吐一张空白标签纸）', () => {
    const doc = buildPrintDocument(['<div>a</div>', '<div>b</div>'], SIZE_60x30, 't')
    expect(doc).toContain('page-break-after: always')
    expect(doc).toContain('.ts-label:last-child { page-break-after: auto')
  })

  it('标题也会转义', () => {
    expect(buildPrintDocument([], SIZE_60x30, '<b>')).toContain('&lt;b&gt;')
  })
})

describe('pageSizeCss', () => {
  it('拼成 CSS 的 size 语法', () => {
    expect(pageSizeCss(SIZE_60x30)).toBe('60mm 30mm')
  })
})

describe('tsplCellWidth', () => {
  it('由「可用点数 ÷ 模块数」反算', () => {
    expect(tsplCellWidth(18, 25)).toBe(5) // 144 / 25 = 5.76 → 5
  })

  it('夹在 1~10（太小扫不出、太大溢出标签纸）', () => {
    expect(tsplCellWidth(18, 200)).toBe(1)
    expect(tsplCellWidth(40, 3)).toBe(10)
  })

  it('模块数为 0 时也不除零', () => {
    expect(tsplCellWidth(18, 0)).toBe(10)
  })
})

describe('nearestTsplFont', () => {
  it('按目标点高就近选档并给出倍率', () => {
    // 2mm = 16 点，正好是 3 号字（16 点）
    expect(nearestTsplFont(2)).toEqual({ font: '3', mul: 1 })
    // 4mm = 32 点，正好是 5 号字（32 点）—— 就近命中，倍率仍是 1
    expect(nearestTsplFont(4)).toEqual({ font: '5', mul: 1 })
    // 8mm = 64 点，超出最大字号（5 号 = 32 点）⇒ 用 5 号字放大 2 倍
    expect(nearestTsplFont(8)).toEqual({ font: '5', mul: 2 })
  })

  it('倍率夹在 1~6（TSPL 倍率上限就是 6）', () => {
    expect(nearestTsplFont(20).mul).toBe(5) // 160 点 ÷ 32 = 5
    expect(nearestTsplFont(30).mul).toBe(6) // 240 点 ÷ 32 = 7.5 → 夹到 6
  })
})

describe('buildTspl', () => {
  const device = { id: 1, assetNo: 'IT-2024-0001', deviceName: '笔记本' }
  const moduleCount = qrCreate(qrPayload(device.assetNo), { errorCorrectionLevel: 'L' }).modules.size

  it('指令包含尺寸、清屏、二维码、两行文字与打印份数', () => {
    const tspl = buildTspl(device, SIZE_60x30, moduleCount, { copies: 2 })
    expect(tspl).toContain('SIZE 60 mm,30 mm')
    expect(tspl).toContain('GAP 2 mm,0 mm')
    expect(tspl).toContain('CLS')
    expect(tspl).toContain('QRCODE ')
    expect(tspl).toContain('"ASSET:IT-2024-0001"')
    expect(tspl).toContain('TEXT ')
    expect(tspl).toContain('PRINT 1,2')
  })

  it('坐标为整数点（不是 mm）—— 传 mm 会打成一团', () => {
    const tspl = buildTspl(device, SIZE_60x30, moduleCount)
    const m = /^QRCODE (\d+),(\d+),/m.exec(tspl)
    expect(m).not.toBeNull()
    expect(Number(m![1])).toBeGreaterThan(0)
    expect(Number.isInteger(Number(m![1]))).toBe(true)
  })

  it('资产编号为空时不写二维码内容与编号行', () => {
    const tspl = buildTspl({ id: 9, assetNo: '', deviceName: '设备' }, SIZE_60x30, 25)
    expect(tspl).toContain('QRCODE ')
    expect(tspl).toContain(',""')
    expect(tspl.split('TEXT ').length - 1).toBe(1) // 只剩设备名称那一行
  })

  it('双引号被替换，避免截断指令', () => {
    const tspl = buildTspl({ id: 10, assetNo: 'A"1', deviceName: '设"备' }, SIZE_60x30, 25)
    expect(tspl).not.toContain('A"1')
    expect(tspl).toContain("A'1")
  })

  it('未指定份数时默认 1 份', () => {
    expect(buildTspl(device, SIZE_60x30, moduleCount)).toContain('PRINT 1,1')
  })

  it('纠错等级默认 L（内容最短、码最稀疏，小标签上更好扫）', () => {
    expect(buildTspl(device, SIZE_60x30, moduleCount)).toContain(',L,')
    expect(buildTspl(device, SIZE_60x30, moduleCount, { ecc: 'M' })).toContain(',M,')
  })
})

describe('renderLabelHtml · 预览缩放（校准）', () => {
  const device = { id: 1, assetNo: 'IT-2024-0001', deviceName: '笔记本' }
  const SIZE_60x30 = findLabelSize('60x30')

  it('默认不缩放 —— 调用方不传就是真实 mm', () => {
    const html = renderLabelHtml(device, SIZE_60x30, '')
    expect(html).toContain('width:60mm')
    expect(html).toContain('height:30mm')
  })

  it('传 0.5 时所有长度等比减半（布局不变，只是整体缩放）', () => {
    const html = renderLabelHtml(device, SIZE_60x30, '', 0.5)
    expect(html).toContain('width:30mm')
    expect(html).toContain('height:15mm')
  })

  it('缩放是等比的：二维码边长与字号同步缩放（只缩外框会错版）', () => {
    const base = labelLayout(SIZE_60x30, device.assetNo, device.deviceName)
    const html = renderLabelHtml(device, SIZE_60x30, '', 1.5)
    expect(html).toContain(`width:${Math.round(base.qrSideMm * 1.5 * 100) / 100}mm`)
    expect(html).toContain(`font-size:${Math.round(base.assetFontMm * 1.5 * 100) / 100}mm`)
  })

  it('打印路径必须用真实尺寸 —— 缩放系数不能带进打印', () => {
    // 打印文档只接收调用方生成的标签 HTML；这里钉住「传 1 时是真实尺寸」这条契约
    const printed = renderLabelHtml(device, SIZE_60x30, '', 1)
    const doc = buildPrintDocument([printed], SIZE_60x30, '设备标签')
    expect(doc).toContain('@page { size: 60mm 30mm; margin: 0; }')
    expect(doc).toContain('width:60mm')
    expect(doc).not.toContain('width:30mm')
  })
})

describe('toPrintableDevices', () => {
  it('剔除没有资产编号的设备（印空码等于废纸）', () => {
    const out = toPrintableDevices([
      { id: 1, assetNo: 'A1', deviceName: 'x' },
      { id: 2, assetNo: null, deviceName: 'y' },
      { id: 3, assetNo: '  ', deviceName: 'z' },
      { id: 4, assetNo: 'A4', deviceName: 'w' }
    ])
    expect(out.map((d) => d.id)).toEqual([1, 4])
  })
})
