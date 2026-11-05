/**
 * 设备标签打印· 纯函数内核
 *
 * <h2>为什么单独抽一层「纯函数」</h2>
 * 标签这个功能里有三处**必须逐字一致**的几何与文案，一旦各写一份就会漂移：
 * <ol>
 *   <li><b>屏幕预览</b>（弹窗里给用户看的）</li>
 *   <li><b>浏览器打印</b>（真正印出来的）</li>
 *   <li><b>蓝牙 TSPL 指令</b>（发给标签机的坐标）</li>
 * </ol>
 * 用户的原话是「样式需要调整：标签纸的尺寸和内容尽量铺满标签纸，用充足空间」——
 * 若预览与打印各算一套尺寸，就会出现「屏幕上刚好、印出来溢出」这类没法排查的问题。
 * 因此三处都从**同一个 {@link labelLayout}** 取几何，只有「单位换算」各不同
 * （预览/打印用 mm，TSPL 用点）。
 *
 * <h2>二维码内容为什么带 ASSET: 前缀</h2>
 * 后端 {@code ScanCodeParser} 的白名单前缀里有 {@code ASSET}：
 * 带上它，服务端**只查设备表**，不会再拿这串去工单表试一次；
 * 且它与「纯编号」「URL」两种形态一样能被解析 —— 即标签换了写法，扫码页也不用改。
 */

/**
 * 标签纸预设（mm）。
 *
 * <p>默认 **60×30**：需求方给的实拍图上，标签长宽比约 2.2:1，最接近这一档；
 * 且 60mm 的宽度才放得下「左侧二维码 + 右侧两行字」，40×30 会让 16 位的资产编号贴边。
 */
export interface LabelSize {
  key: string
  label: string
  widthMm: number
  heightMm: number
}

export const LABEL_SIZES: LabelSize[] = [
  { key: '25x60', label: '25 × 60 mm（竖版）', widthMm: 25, heightMm: 60 },
  { key: '60x25', label: '60 × 25 mm', widthMm: 60, heightMm: 25 },
  { key: '50x30', label: '50 × 30 mm', widthMm: 50, heightMm: 30 },
  { key: '40x30', label: '40 × 30 mm', widthMm: 40, heightMm: 30 },
  { key: '60x30', label: '60 × 30 mm', widthMm: 60, heightMm: 30 },
  { key: '60x40', label: '60 × 40 mm', widthMm: 60, heightMm: 40 },
  { key: '40x60', label: '40 × 60 mm', widthMm: 40, heightMm: 60 }
]

/**
 * 默认纸型 **25×60（竖版）**，2026-11-05 按实物照片定。
 *
 * 之前我按横版做（左码右字），照片对不上：实物是**竖版**标签 ——
 * 二维码在上部、横向占满整宽，下面两列**竖排**文字：
 * 左列设备类型、右列资产编号。见 {@link verticalLabelLayout}。
 */
export const DEFAULT_LABEL_SIZE_KEY = '25x60'

/**
 * 标签纸尺寸的记忆键（需求：弹窗内选预设并记住上次）。
 *
 * 加了 `:v2` —— 2026-11-05 规格从 60×30 改为 60×25 时升版：
 * 旧键里存着 60×30，不升版的话用户打开弹窗仍是被记忆的旧规格，会以为改动没生效。
 */
export const LABEL_SIZE_STORAGE_KEY = 'ticket:label-size:v2'

/**
 * 预览缩放比例的存储键与取值范围（%）。
 *
 * 浏览器不暴露屏幕物理 PPI，CSS 的 mm 只是「96px = 1in」的换算 ⇒ 必须让用户校准一次。
 * 范围取 40%~200%：覆盖从 4K 高分屏到大屏低缩放的各种组合。
 */
export const LABEL_PREVIEW_SCALE_KEY = 'ticket:label-preview-scale'
export const PREVIEW_SCALE_MIN = 40
export const PREVIEW_SCALE_MAX = 200
export const PREVIEW_SCALE_DEFAULT = 100

/**
 * 校准参照物：银行卡 / 身份证（ISO/IEC 7810 ID-1）。
 *
 * 选它而不是让用户找尺子：几乎人人手边都有，且尺寸有国际标准（85.60 × 53.98 mm），
 * 比对结果的误差只取决于卡片本身（批量印刷品，误差远小于「目测」）。
 */
export const REFERENCE_CARD = { widthMm: 85.6, heightMm: 53.98, label: '银行卡 / 身份证' }

export function findLabelSize(key: string | null | undefined): LabelSize {
  return LABEL_SIZES.find((s) => s.key === key) ?? LABEL_SIZES.find((s) => s.key === DEFAULT_LABEL_SIZE_KEY)!
}

/** 203dpi 标签机的点密度：8 点/mm（TSPL 的坐标单位） */
export const DOTS_PER_MM = 8

export function mmToDots(mm: number): number {
  return Math.round(mm * DOTS_PER_MM)
}

/**
 * 二维码里写什么。
 *
 * <p>资产编号为空时返回空串 —— 调用方据此跳过该设备（印一个没有内容的二维码等于废纸）。
 */
export function qrPayload(assetNo: string | null | undefined): string {
  const v = (assetNo ?? '').trim()
  return v ? `ASSET:${v}` : ''
}

/** 标签上要显示的三要素（需求方明确了：只用二维码 + 资产编号 + 设备名称，**不放部门/责任人**） */
export interface LabelDevice {
  id: number | string
  assetNo: string | null
  deviceName: string | null
}

/**
 * 标签几何（全部单位 mm）。
 *
 * 数值来自**需求方给的实拍图**：左侧二维码约占标签高度的六成、垂直居中；右侧两行文字左对齐，
 * 资产编号在上（加粗）、设备名称在下（小一号）；标签长宽比约 2.2:1，因此默认档取 60×30。
 *
 * 规则（就是为了「尽量铺满、别浪费」，同时保证「长编号不溢出」）：
 * · 四周留白 = 高度的 5%，上限 2mm（小标签上固定留白会吃掉太多空间）；
 * · 二维码是**正方形**，边长取「高度的 86%」与「宽度的 34%」中的**较小者** ——
 *   2026-11-05 按实物照片校准：照片里码占高度约 82~85%、几乎顶满，文字让到右侧两行；
 * · 文字区宽度 = 总宽 − 留白 − 码边 − 间隙 − 留白；
 * · 资产编号字号按「文字区宽度 ÷ 字符数」反算并设上限，保证**再长的编号也不溢出**；
 * · 设备名称字号 = 资产编号的 62%，且不超过高度的 16%（保持「上行大字、下行小字」的层级）；
 * · 两行文字作为整体在剩余高度里垂直居中，与实拍图一致。
 */
export interface LabelLayout {
  /** 版式：horizontal = 码在左、两行字在右；vertical = 码在上、两列竖排字在下 */
  mode: 'horizontal' | 'vertical'
  widthMm: number
  heightMm: number
  padMm: number
  qrSideMm: number
  qrXMm: number
  qrYMm: number
  /** 横版：文字区左边缘与宽度（竖版下 textXMm 等于类型列左边缘，textWidthMm 为整宽） */
  textXMm: number
  textWidthMm: number
  /** 竖版：文字列的顶部与可用高度（横版下分别是 0 与整高） */
  textTopMm: number
  textHeightMm: number
  assetFontMm: number
  nameFontMm: number
  /** 号码 / 类型各自所在列的位置（横版下同一列，值相同） */
  assetXMm: number
  nameXMm: number
  assetYMm: number
  nameYMm: number
}

/** 单个字符的占宽系数（等宽粗体的经验值：字宽 ≈ 0.62 × 字号） */
const CHAR_WIDTH_RATIO = 0.62
/** 一个汉字按 1 个全角宽度算（相对拉丁字符的等效字符数） */
const CJK_WIDTH = 1.7
/** 竖排（旋转 90°）时数字/字母沿文字方向的占位（em）；实测粗体数字约 0.628，取 0.63 */
const V_LATIN_ADVANCE = 0.63
/**
 * 竖排字号的余量系数（2026-11-05 实测得出）。
 *
 * <p>按理论占位算出来的字号，实测仍会超出 1%~2%（字距、hinting、字体回退都会加一点），
 * 而 `overflow: hidden` 会把超出的那点**直接切掉**——18 位编号实测超出 0.4mm，末位数字被削平。
 * 留 2% 余量后实测落在 29.2mm / 30mm，安全。
 */
const V_FONT_SAFETY = 1.02

/** 粗略估算文本的「等效字符数」：中文按 1.7 个拉丁字符宽算 */
export function effectiveCharCount(text: string): number {
  let n = 0
  for (const ch of text) {
    n += /[\u4e00-\u9fa5\u3000-\u303f\uff00-\uffef]/.test(ch) ? CJK_WIDTH : 1
  }
  return Math.max(n, 1)
}

/**
 * 竖排（旋转 90° 顺时针）时每个字符沿文字方向的占位，单位 em。
 *
 * <p>和 {@link effectiveCharCount} 不是一回事：那个算的是「横向并排时占多宽」，
 * 汉字按 1.7 个拉丁字符算；竖排旋转 90° 后，文字方向变成**纵向**，
 * 汉字的占位就是它自己的字宽（1.0 em），数字字母约 0.62 em。
 * 用错会把字号算大一倍，15 位编号直接冲出纸外。
 */
export function verticalAdvanceEm(text: string): number {
  let n = 0
  for (const ch of text) {
    n += /[\u4e00-\u9fa5\u3000-\u303f\uff00-\uffef]/.test(ch) ? 1 : V_LATIN_ADVANCE
  }
  return Math.max(n, 1)
}

export function labelLayout(size: LabelSize, assetNo: string, deviceName: string): LabelLayout {
  // 高 > 宽 = 竖版（25×60）：码在上、两列竖排字在下
  if (size.heightMm > size.widthMm) {
    return verticalLabelLayout(size, assetNo, deviceName)
  }
  return horizontalLabelLayout(size, assetNo, deviceName)
}

/**
 * 竖版布局（25 × 60 mm，需求方给定坐标，2026-11-05）。
 *
 * <p>几何是**定死的毫米值**，不是按比例猜的：
 * <pre>
 *   画布 25 × 60
 *   ├─ 二维码：左 3、上 4、边长 20   → 右边缘距纸右 2mm（25 − 3 − 20）
 *   └─ 文字区：起点 y = 4 + 20 + 3 = 27，可用长度 = 60 − 27 − 3 = 30mm
 *        ├─ 左列（设备名）：左边距 3，旋转 90° 顺时针
 *        └─ 右列（资产编号）：右边距 3，旋转 90° 顺时针
 * </pre>
 *
 * <p>比例形式（0.12 / 0.0667 / 0.8 / 0.05）在 25×60 上精确等于上面这些毫米值，
 * 换成别的竖版纸型也仍能按同一套规矩排。
 *
 * <p>字号靠「文字区长度 ÷ 竖排占位」反算：15 位编号占 9.3 em，30mm 长度反算出 ~3.2mm
 * （约 9pt）；设备名占 4.2 em，取编号的 1.35 倍封顶，避免左列比右列粗一倍。
 */
function verticalLabelLayout(size: LabelSize, assetNo: string, deviceName: string): LabelLayout {
  const { widthMm, heightMm } = size

  const padXMm = widthMm * 0.12            // 25 → 3.0
  const padTopMm = heightMm * (4 / 60)     // 60 → 4.0
  const qrSideMm = Math.min(widthMm * 0.8, heightMm * 0.5) // 25 → 20.0
  const gapMm = heightMm * 0.05            // 60 → 3.0
  const padBottomMm = heightMm * 0.05      // 60 → 3.0

  const qrXMm = padXMm
  const qrYMm = padTopMm
  const textTopMm = qrYMm + qrSideMm + gapMm
  const textHeightMm = Math.max(heightMm - textTopMm - padBottomMm, 6)

  const assetAdv = verticalAdvanceEm(assetNo || '0000000000')
  const assetFontMm = Math.min(textHeightMm / (assetAdv * V_FONT_SAFETY), widthMm * 0.17)

  const nameAdv = verticalAdvanceEm(deviceName || '设备名称')
  const nameFontMm = Math.min(
    textHeightMm / (nameAdv * V_FONT_SAFETY),
    assetFontMm * 1.35,
    widthMm * 0.2
  )

  return {
    mode: 'vertical',
    widthMm,
    heightMm,
    // 竖版用 padMm 表示「左右一致的边距」，渲染时右列靠 right 定位
    padMm: padXMm,
    qrSideMm,
    qrXMm,
    qrYMm,
    textXMm: padXMm,
    textWidthMm: widthMm - padXMm * 2,
    textTopMm,
    textHeightMm,
    assetFontMm,
    nameFontMm,
    assetXMm: widthMm - padXMm - assetFontMm,
    nameXMm: padXMm,
    assetYMm: textTopMm,
    nameYMm: textTopMm
  }
}

/** 横版布局（码在左、两行字在右）*/
function horizontalLabelLayout(size: LabelSize, assetNo: string, deviceName: string): LabelLayout {
  const { widthMm, heightMm } = size
  // 四周留白从「高度的 7%」收到 5%（上限 2mm）：小标签上 7% 会把码挤小一圈
  const padMm = Math.min(heightMm * 0.05, 2)
  const gapMm = Math.max(padMm * 0.75, 1)

  /*
   * 二维码边长（2026-11-05 按实物照片校准）：
   * 照片里二维码约占标签高度的 82~85%，上下只留一条窄边；原先是 min(高−留白, 宽×30%)，
   * 在 60×25 上只有 18mm（72%），看着比实物小一圈。现在按「高度的 86%」与「宽度的 34%」取小。
   */
  const qrSideMm = Math.min(heightMm * 0.86, widthMm * 0.34)
  const qrXMm = padMm
  const qrYMm = (heightMm - qrSideMm) / 2

  const textXMm = qrXMm + qrSideMm + gapMm
  const textWidthMm = Math.max(widthMm - textXMm - padMm, 4)

  const assetChars = effectiveCharCount(assetNo || '0000000000')
  const assetFontMm = Math.min(heightMm * 0.28, textWidthMm / (assetChars * CHAR_WIDTH_RATIO))

  const nameChars = effectiveCharCount(deviceName || '设备名称')
  const nameFontByWidth = (textWidthMm * 2) / (nameChars * CHAR_WIDTH_RATIO)
  const nameFontMm = Math.min(assetFontMm * 0.62, heightMm * 0.16, nameFontByWidth)

  const blockMm = assetFontMm * 1.25 + nameFontMm * 1.35
  const blockTop = Math.max((heightMm - blockMm) / 2, padMm)

  return {
    mode: 'horizontal',
    widthMm,
    heightMm,
    padMm,
    qrSideMm,
    qrXMm,
    qrYMm,
    textXMm,
    textWidthMm,
    textTopMm: 0,
    textHeightMm: heightMm,
    assetFontMm,
    nameFontMm,
    assetXMm: textXMm,
    nameXMm: textXMm,
    assetYMm: blockTop,
    nameYMm: blockTop + assetFontMm * 1.25
  }
}

/** HTML 转义：设备名称来自数据库，虽然是我们自己录的，也不能直接拼进 innerHTML */
export function escapeHtml(value: string | null | undefined): string {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

const fmt = (n: number): string => `${Math.round(n * 100) / 100}mm`

/**
 * 生成**一个标签**的 HTML。
 *
 * <p>屏幕预览与浏览器打印共用这一份 —— 这是「所见即所得」的唯一保证。
 *
 * @param scale 预览缩放系数（1 = 浏览器按 CSS 的 96dpi 换算，即未校准）。
 *              **打印必须传 1**：打印走 printer driver 的真实毫米，跟着预览缩放会把标签打大。
 *              只有预览才传用户的屏幕校准系数，用来抵消「CSS mm ≠ 物理 mm」。
 */
export function renderLabelHtml(
  device: LabelDevice,
  size: LabelSize,
  qrSvg: string,
  scale = 1
): string {
  const assetNo = (device.assetNo ?? '').trim()
  const deviceName = (device.deviceName ?? '').trim()
  const L = labelLayout(size, assetNo, deviceName)
  // 所有长度统一乘系数：布局不变，只是整体放大/缩小到与物理尺寸一致
  const s = (v: number): string => fmt(v * scale)

  const qr =
    `<div class="ts-label__qr" style="left:${s(L.qrXMm)};top:${s(L.qrYMm)};` +
    `width:${s(L.qrSideMm)};height:${s(L.qrSideMm)};">${qrSvg}</div>`

  if (L.mode === 'vertical') {
    /*
     * 竖版（25×60）：二维码在上，下面两列竖排、都**顺时针旋转 90°**：
     *   · 左列设备名 —— 距左边缘 3mm，向下排；
     *   · 右列资产编号 —— 距右边缘 3mm，向下排（用 right 定位，编号长短不影响贴边）。
     * 旋转靠 CSS 的 writing-mode: vertical-rl + text-orientation: sideways，
     * sideways 才是「整体转 90°」，upright 是「字正立、只为竖着叠」，两者观感完全不同。
     */
    return (
      `<div class="ts-label is-vertical" style="width:${s(L.widthMm)};height:${s(L.heightMm)};">` +
      qr +
      `<div class="ts-label__name is-vtext" style="left:${s(L.nameXMm)};top:${s(L.nameYMm)};` +
      `height:${s(L.textHeightMm)};font-size:${s(L.nameFontMm)};">${escapeHtml(deviceName)}</div>` +
      `<div class="ts-label__asset is-vtext" style="right:${s(L.padMm)};top:${s(L.assetYMm)};` +
      `height:${s(L.textHeightMm)};font-size:${s(L.assetFontMm)};">${escapeHtml(assetNo)}</div>` +
      `</div>`
    )
  }

  return (
    `<div class="ts-label" style="width:${s(L.widthMm)};height:${s(L.heightMm)};">` +
    qr +
    `<div class="ts-label__asset" style="left:${s(L.textXMm)};top:${s(L.assetYMm)};` +
    `width:${s(L.textWidthMm)};font-size:${s(L.assetFontMm)};">${escapeHtml(assetNo)}</div>` +
    `<div class="ts-label__name" style="left:${s(L.textXMm)};top:${s(L.nameYMm)};` +
    `width:${s(L.textWidthMm)};font-size:${s(L.nameFontMm)};">${escapeHtml(deviceName)}</div>` +
    `</div>`
  )
}

/** 标签样式：预览与打印共用（打印时还要靠它把尺寸钉死在标签纸上） */
export const LABEL_CSS = `
.ts-label { position: relative; box-sizing: border-box; overflow: hidden; background: #fff; color: #000; }
.ts-label__qr { position: absolute; }
.ts-label__qr svg { width: 100%; height: 100%; display: block; }
.ts-label__asset {
  position: absolute; font-weight: 700; line-height: 1.15; letter-spacing: 0;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.ts-label__name {
  position: absolute; line-height: 1.25; color: #333;
  display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 2; overflow: hidden;
}
/*
 * 竖版的两列文字：整体顺时针旋转 90°，从上往下读。
 * ⚠️ 必须是 sideways，不是 upright —— upright 只是「字正立着竖着叠」，
 * 不转；需求方的实物是每个字都转了 90°。
 * display 要显式给 block：.ts-label__name 上面那条规则里有 -webkit-box + 行数截断，
 * 在竖排里会把文字直接裁没。
 */
.ts-label .is-vtext {
  display: block; writing-mode: vertical-rl; text-orientation: sideways;
  line-height: 1; white-space: nowrap; overflow: hidden; text-overflow: clip;
  font-weight: 400; color: #000;
}
.ts-label .ts-label__asset.is-vtext { font-weight: 700; color: #000; }
`

/** @page 的尺寸字符串 */
export function pageSizeCss(size: LabelSize): string {
  return `${size.widthMm}mm ${size.heightMm}mm`
}

/**
 * 打印用的完整 HTML 文档。
 *
 * <p>`@page { size: <标签纸尺寸>; margin: 0 }` 是「一张标签纸打一个标签」的关键：
 * 不写 size，打印机浏览器会按 A4 排版、把标签打在纸中间并浪费大量空白。
 * `margin: 0` 同理 —— 有边距时浏览器会缩放到可打印区域，标签就不是原尺寸了。
 */
export function buildPrintDocument(labelHtmlList: string[], size: LabelSize, title: string): string {
  return (
    `<!DOCTYPE html><html><head><meta charset="utf-8"><title>${escapeHtml(title)}</title><style>` +
    `@page { size: ${pageSizeCss(size)}; margin: 0; }` +
    `html, body { margin: 0; padding: 0; background: #fff; }` +
    `.ts-label { page-break-after: always; break-after: page; }` +
    `.ts-label:last-child { page-break-after: auto; break-after: auto; }` +
    LABEL_CSS +
    `</style></head><body>${labelHtmlList.join('')}</body></html>`
  )
}

/** 标签机的 TSPL 指令（通用 BLE 标签机；服务 0xFF00 / 写特征 0xFF02） */
export interface TsplOptions {
  /** 每个标签打印份数 */
  copies?: number
  /** 标签之间的间隙（mm），多数成品标签纸是 2mm */
  gapMm?: number
  /** 二维码纠错等级：L/M/Q/H，默认 L（内容最短、码最稀疏，小标签上更好扫） */
  ecc?: 'L' | 'M' | 'Q' | 'H'
}

/**
 * 生成 TSPL 指令。
 *
 * <p>两点必须写进注释，否则以后一定有人改错：
 * <ol>
 *   <li>坐标单位是**点**（203dpi 机型为 8 点/mm），不是 mm —— 传 mm 会打成一团。</li>
 *   <li>二维码的「cell width」必须由**实际模块数**反算：固定值会让长内容溢出标签纸。
 *       {@code moduleCount} 由调用方用 QR 库算出（{@link tsplCellWidth} 负责夹取范围）。</li>
 * </ol>
 */
export function buildTspl(
  device: LabelDevice,
  size: LabelSize,
  moduleCount: number,
  options: TsplOptions = {}
): string {
  const { copies = 1, gapMm = 2, ecc = 'L' } = options
  const assetNo = (device.assetNo ?? '').trim()
  const deviceName = (device.deviceName ?? '').trim()
  const L = labelLayout(size, assetNo, deviceName)

  const cell = tsplCellWidth(L.qrSideMm, moduleCount)
  const qrX = mmToDots(L.qrXMm)
  const qrY = mmToDots(L.qrYMm)
  // TSPL 字号只有 1~5 五档（8/12/16/24/32 点），按目标字号就近选档，再用倍率补齐
  const assetFont = nearestTsplFont(L.assetFontMm)
  const nameFont = nearestTsplFont(L.nameFontMm)

  const lines = [
    `SIZE ${size.widthMm} mm,${size.heightMm} mm`,
    `GAP ${gapMm} mm,0 mm`,
    'DIRECTION 1',
    'CLS',
    // ⚠️ 二维码内容也要过 safeTspl：资产编号里出现双引号会从那里截断整条指令
    `QRCODE ${qrX},${qrY},${ecc},${cell},A,0,M2,S7,"${assetNo ? safeTspl(qrPayload(assetNo)) : ''}"`
  ]

  if (L.mode === 'vertical') {
    /*
     * 竖版：两列文字都转 90°（TSPL 的 rotation=90，顺时针，字符从上往下排）。
     * 转 90° 后**行高方向变成 -x**，文字盒从锚点往左铺，所以：
     *   · 左列（设备名）左边距 3mm → 锚点 x = 3mm + 一行厚度；
     *   · 右列（资产编号）右边距 3mm → 锚点 x 就是右边缘 22mm。
     * ⚠️ 少数机型把 90 解释成逆时针，若实物方向反了改成 270，坐标不用动。
     */
    const lineBox = (fontMm: number): number => mmToDots(fontMm * 1.15)
    const nameFontFinal = tsplFontFor(deviceName, nameFont)
    if (deviceName) {
      const x = mmToDots(L.nameXMm) + lineBox(L.nameFontMm)
      lines.push(
        `TEXT ${x},${mmToDots(L.nameYMm)},"${nameFontFinal.font}",90,` +
          `${nameFontFinal.mul},${nameFontFinal.mul},"${safeTspl(deviceName)}"`
      )
    }
    if (assetNo) {
      const x = mmToDots(L.widthMm - L.padMm)
      lines.push(
        `TEXT ${x},${mmToDots(L.assetYMm)},"${assetFont.font}",90,${assetFont.mul},${assetFont.mul},"${safeTspl(assetNo)}"`
      )
    }
  } else {
    // 横版：二维码与文字都从同一个左留白起步，与屏幕预览对齐
    const textX = mmToDots(L.textXMm)
    const assetY = mmToDots(L.assetYMm)
    const nameY = mmToDots(L.nameYMm)
    if (assetNo) {
      lines.push(`TEXT ${textX},${assetY},"${assetFont.font}",0,${assetFont.mul},${assetFont.mul},"${safeTspl(assetNo)}"`)
    }
    const nameFontFinal = tsplFontFor(deviceName, nameFont)
    if (deviceName) {
      lines.push(
        `TEXT ${textX},${nameY},"${nameFontFinal.font}",0,` +
          `${nameFontFinal.mul},${nameFontFinal.mul},"${safeTspl(deviceName)}"`
      )
    }
  }

  lines.push(`PRINT 1,${Math.max(copies, 1)}`)
  return lines.join('\r\n') + '\r\n'
}

/**
 * 按内容选字库：含汉字时**必须**用外挂中文字库 TSS24.BF2。
 *
 * <p>内建 1~5 号是单字节点阵英文字库，把中文丢进去打出来是一串乱码方块。
 * TSS24 的点高固定 24 点（3mm），所以倍率按 24 点反算，而不是按目标字号选档。
 */
export function tsplFontFor(text: string, fallback: { font: string; mul: number }): { font: string; mul: number } {
  if (!/[\u4e00-\u9fa5]/.test(text)) {
    return fallback
  }
  return { font: TSPL_CJK_FONT, mul: Math.max(1, Math.min(6, fallback.mul)) }
}

/** TSPL 内建字体的点高（1~5 档）；中文标签机另需 TSS24.BF2 之类的外挂字体 */
const TSPL_FONTS: Array<{ name: string; dots: number }> = [
  { name: '1', dots: 8 },
  { name: '2', dots: 12 },
  { name: '3', dots: 16 },
  { name: '4', dots: 24 },
  { name: '5', dots: 32 }
]

/** 中文优先用 TSS24.BF2（24 点）；绝大多数支持中文的 TSPL 机型都带这套字库 */
export const TSPL_CJK_FONT = 'TSS24.BF2'
export const TSPL_CJK_FONT_DOTS = 24

/** 目标字号（mm）→ TSPL 字体与倍率 */
export function nearestTsplFont(targetMm: number): { font: string; mul: number } {
  const targetDots = mmToDots(targetMm)
  let best = TSPL_FONTS[0]
  for (const f of TSPL_FONTS) {
    if (Math.abs(f.dots - targetDots) < Math.abs(best.dots - targetDots)) {
      best = f
    }
  }
  return { font: best.name, mul: Math.max(1, Math.min(6, Math.round(targetDots / best.dots) || 1)) }
}

/**
 * 二维码的 cell width（1~10）。
 *
 * <p>由「可用点数 ÷ 模块数」反算 —— 固定值会在长内容（模块多）时溢出标签纸。
 */
export function tsplCellWidth(qrSideMm: number, moduleCount: number): number {
  const dots = mmToDots(qrSideMm)
  const raw = Math.floor(dots / Math.max(moduleCount, 1))
  return Math.max(1, Math.min(10, raw))
}

/** TSPL 字符串里的双引号会截断指令（资产编号理论上不含，但不能靠假设） */
function safeTspl(value: string): string {
  return value.replace(/"/g, "'")
}

/** 把设备列表转成「可打印项」：资产编号为空的直接剔除（印空码等于废纸） */
export function toPrintableDevices(devices: LabelDevice[]): LabelDevice[] {
  return devices.filter((d) => !!qrPayload(d.assetNo))
}
