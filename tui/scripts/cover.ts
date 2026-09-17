/**
 * 生成 README 的封面图 `docs/cover.svg`。
 *
 * 画的就是首屏那两样东西：`Welcome` 字标 + MYAGENT 招牌。
 * 数据直接从界面代码里取（`buildPixels` / `LOGO_LINES` / `theme`），
 * 所以招牌改了、配色改了，重跑一次封面就跟着变，不会和实际界面对不上。
 *
 * **不用 `<text>`，全部画成矢量图形。**
 * 这是整个脚本唯一需要解释的决定：招牌是 `█ ║ ═ ╔ ╗ ╚ ╝` 拼出来的，
 * 这些字符在不同机器上宽高比、线宽都不一样，用 `<text>` 渲染等于把版式
 * 交给看图人装了什么字体——GitHub 上十有八九会错位。
 * 换成矩形和线段，出来的图到哪都一模一样。
 *
 * 跑: bun run cover（从 tui/ 目录）
 */

import { mkdirSync, writeFileSync } from "node:fs"
import { dirname, resolve } from "node:path"
import { LOGO_LINES } from "../src/format.ts"
import { dark } from "../src/theme.ts"
import { buildPixels } from "../src/wordmark.ts"

/** 一个字符格的像素尺寸。1:2 是终端里字符格的常见比例。 */
const CW = 10
const CH = 20

/** 描边字符（`═║╔╗╚╝`）的线宽。 */
const STROKE = 2

const PADDING = 44
/** 字标和招牌之间空几行。 */
const GAP_ROWS = 2

const OUT = resolve(import.meta.dir, "../../docs/cover.svg")

// --- 画字符 -----------------------------------------------------------------

type Segment = { x1: number; y1: number; x2: number; y2: number }

/**
 * 描边字符 → 线段。坐标是格子内的相对值，单位像素。
 *
 * `═║╔╗╚╝` 在 Unicode 里是**双线**制表符，但这里一律画成单线。
 * 终端里那两根线各自不到 1 像素、挨得极近，看上去就是一道细边；
 * 照着画成双线，图放大到 README 的尺寸后两根线会分开，
 * 每个字母外面套一圈空心轮廓，比招牌本身还抢眼——试过了，很吵。
 */
function segmentsOf(char: string): Segment[] {
  const cx = CW / 2
  const cy = CH / 2

  switch (char) {
    case "║":
      return [{ x1: cx, y1: 0, x2: cx, y2: CH }]
    case "═":
      return [{ x1: 0, y1: cy, x2: CW, y2: cy }]
    case "╔": // 往右、往下
      return [
        { x1: cx, y1: cy, x2: CW, y2: cy },
        { x1: cx, y1: cy, x2: cx, y2: CH },
      ]
    case "╗": // 往左、往下
      return [
        { x1: 0, y1: cy, x2: cx, y2: cy },
        { x1: cx, y1: cy, x2: cx, y2: CH },
      ]
    case "╚": // 往右、往上
      return [
        { x1: cx, y1: cy, x2: CW, y2: cy },
        { x1: cx, y1: 0, x2: cx, y2: cy },
      ]
    case "╝": // 往左、往上
      return [
        { x1: 0, y1: cy, x2: cx, y2: cy },
        { x1: cx, y1: 0, x2: cx, y2: cy },
      ]
    default:
      return []
  }
}

const rect = (x: number, y: number, w: number, h: number, fill: string): string =>
  `<rect x="${x}" y="${y}" width="${w}" height="${h}" fill="${fill}"/>`

const line = (s: Segment, x: number, y: number, stroke: string): string =>
  `<line x1="${x + s.x1}" y1="${y + s.y1}" x2="${x + s.x2}" y2="${y + s.y2}" stroke="${stroke}"/>`

// --- 拼图 -------------------------------------------------------------------

const pixels = buildPixels("Welcome")
const markWidth = pixels[0]?.length ?? 0
/** 字标按半格排版，所以高度算的是像素行，不是字符行。 */
const markHeight = (pixels.length * CH) / 2

const logoWidth = Math.max(...LOGO_LINES.map((row) => row.length))
const logoHeight = LOGO_LINES.length * CH

const contentWidth = Math.max(markWidth, logoWidth) * CW
const width = contentWidth + PADDING * 2
const height = PADDING * 2 + markHeight + GAP_ROWS * CH + logoHeight

const body: string[] = []

// 字标：每个像素一个矩形，半格高
const markX = PADDING + (contentWidth - markWidth * CW) / 2
pixels.forEach((row, y) => {
  row.forEach((pixel, x) => {
    if (pixel === null) return
    body.push(
      rect(
        markX + x * CW,
        PADDING + (y * CH) / 2,
        CW,
        CH / 2,
        pixel === "main" ? dark.text : dark.border,
      ),
    )
  })
})

// 招牌：实心块画矩形，描边字符画线
const logoX = PADDING + (contentWidth - logoWidth * CW) / 2
const logoY = PADDING + markHeight + GAP_ROWS * CH
LOGO_LINES.forEach((row, y) => {
  Array.from(row).forEach((char, x) => {
    const px = logoX + x * CW
    const py = logoY + y * CH
    if (char === "█") body.push(rect(px, py, CW, CH, dark.brand))
    else for (const segment of segmentsOf(char)) body.push(line(segment, px, py, dark.brandDim))
  })
})

const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" role="img" aria-label="myagent">
  <rect width="${width}" height="${height}" rx="12" fill="${dark.panel}"/>
  <rect x="0.5" y="0.5" width="${width - 1}" height="${height - 1}" rx="12" fill="none" stroke="${dark.border}"/>
  <g stroke-width="${STROKE}" stroke-linecap="square" shape-rendering="crispEdges">
    ${body.join("\n    ")}
  </g>
</svg>
`

mkdirSync(dirname(OUT), { recursive: true })
writeFileSync(OUT, svg)
console.log(`${OUT}  ${width}×${height}  ${(svg.length / 1024).toFixed(1)} KB`)
