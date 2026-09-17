/**
 * 字标（wordmark）的排版：把一串字母排成**带投影**的点阵。
 *
 * 关键在于**不是按字符格排版，而是按半格（像素）排版**。
 *
 * `tiny` 字模全部由 `█ ▀ ▄` 和空格组成，也就是说一个字符格其实装着**上下两个像素**。
 * 把它们拆开来算，再用「上像素当前景色、下像素当背景色」的办法编码回去，
 * 就能做到半格精度。投影因此只沉**一个像素**：
 * 按字符格排的话它得沉一整行，那是本体的等大复制品，比字本身还抢眼。
 *
 * 投影本身没有现成开关——同一份字形往下挪一点、用暗色再画一遍，本体盖在上面。
 * 这是平面设计里最常规的 drop shadow，落到像素网格上就是「两层合成，本体优先」。
 *
 * 倾斜（斜体）这一档**主动放弃了**，理由见 `SHEAR`。
 *
 * 为什么不用 OpenTUI 的 `<ascii-font>`：那个组件把字模直接画进 framebuffer，
 * 中途插不进第二层，而投影要的正是这一步。
 * 招牌 `Banner.tsx` 用的是手写字模（6 行），那是主视觉；这里只要「比正文大一点」。
 *
 * 排版是纯函数，渲染在 `components/Wordmark.tsx`——网格算法可以单测，JSX 不好测。
 */

import { fonts } from "@opentui/core"

/**
 * 整体倾斜量，单位是**字符格**（自下而上累计）。0 = 不倾斜。
 *
 * 为什么是 0：字符网格上挪不了半格，所以最小的非零倾斜就是「顶上的像素行比
 * 最底下的右移一格」。`tiny` 总共才 4 个像素行高，1 格摊在 4 个像素行上约 26°，
 * 而常见的印刷斜体只有 12° 左右——在这个尺寸下它不像斜体，像上下两半错位了。
 *
 * 要柔和的斜体，唯一的办法是换更高的字模（`pallet`/`grid` 起步 6 行，
 * 把 2 格位移摊成两级台阶）；但那就和 `Banner.tsx` 的招牌一样高了，
 * 而这里要的是「比正文大一点」。所以这一档主动不做。
 *
 * 留着这个常数（而不是把错切代码删掉）是因为改字模时它就有意义了。
 */
const SHEAR = 0

/**
 * 投影右移几格。
 *
 * 0 = 光源正上方，投影只落在笔画正下方。
 * 给 1 的话每道竖笔右边都会挂一整列暗色——横向没有半格可用，一挪就是满格，
 * 投影面积反而盖过了字。
 */
const SHADOW_DX = 0

/** 投影下沉几个**像素行**——注意单位是半格，不是字符行。 */
const SHADOW_DY = 1

/**
 * 在字模自带的字距之外再加几格。
 *
 * `tiny` 自带的字距只有 1 格，那是正文尺寸下的设定；当成字标放大来看太挤，
 * 字与字容易连读。多留一格，"WELCOME" 才像个标志而不是一串字符。
 */
const TRACKING = 1

/** 一个像素属于哪一层。本体亮、投影暗，立体感全靠这个深浅差。 */
export type Layer = "main" | "shadow"

/** `null` = 这半格没有笔画。 */
export type Pixel = Layer | null

/** 一个字符格：上半格画 `fg`，下半格画 `bg`（半块字符自带这个分工）。 */
export type Cell = { char: string; fg: Layer | null; bg: Layer | null }

/** 同色连续段。一行几十格逐格建节点太碎，先并成段再渲染。 */
export type Run = { text: string; fg: Layer | null; bg: Layer | null }

/** 半块字符 → 上下两个像素。`tiny` 字模只用得到这四个字符。 */
const PIXELS: Record<string, readonly [boolean, boolean]> = {
  "█": [true, true],
  "▀": [true, false],
  "▄": [false, true],
  " ": [false, false],
}

/**
 * 用 `tiny` 字模把文本排成几行字符。
 *
 * `tiny` 只有大写字形，所以传什么都按大写画——"Welcome" 出来是 "WELCOME"。
 */
export function glyphRows(text: string): string[] {
  const font = fonts.tiny
  const chars = font.chars as unknown as Record<string, string[] | undefined>
  const rows: string[] = Array.from({ length: font.lines }, () => "")

  for (const ch of text.toUpperCase()) {
    const glyph = chars[ch] ?? chars[" "]
    if (!glyph) continue
    for (let i = 0; i < font.lines; i++) {
      rows[i] += (glyph[i] ?? "") + (font.letterspace[i] ?? "") + " ".repeat(TRACKING)
    }
  }

  // 末尾那一份字距是字母之间的间隔，排到最后就成了多余的右留白，
  // 留着会让外层的居中偏左半格。
  return rows.map((row) => row.trimEnd())
}

/** 把字符行拆成像素行：每个字符格摊成上下两格，高度翻倍。 */
export function toPixels(rows: string[]): boolean[][] {
  const width = Math.max(0, ...rows.map((row) => row.length))
  const pixels: boolean[][] = Array.from({ length: rows.length * 2 }, () =>
    Array<boolean>(width).fill(false),
  )

  rows.forEach((row, y) => {
    Array.from(row).forEach((char, x) => {
      const [upper, lower] = PIXELS[char] ?? PIXELS[" "]!
      pixels[y * 2]![x] = upper
      pixels[y * 2 + 1]![x] = lower
    })
  })
  return pixels
}

/**
 * 第 `y` 个像素行该右移几格。
 *
 * 越靠上移得越多，最底下不动——错切的斜度就是这么来的。
 * 总位移只有 `SHEAR` 格，按高度线性摊开再取整。
 */
function shearAt(y: number, height: number): number {
  if (height <= 1) return 0
  return Math.round(((height - 1 - y) / (height - 1)) * SHEAR)
}

/**
 * 排出字标的像素网格：`grid[像素行][列]`。
 *
 * 先铺投影再盖本体——重叠处本体赢，这就是「本体在前、投影在后」的层次。
 */
export function buildPixels(text: string): Pixel[][] {
  const glyphs = toPixels(glyphRows(text))
  const height = glyphs.length + SHADOW_DY
  const width = Math.max(0, ...glyphs.map((row) => row.length)) + SHEAR + SHADOW_DX

  const grid: Pixel[][] = Array.from({ length: height }, () => Array<Pixel>(width).fill(null))

  const paint = (layer: Layer, dx: number, dy: number): void => {
    glyphs.forEach((row, y) => {
      const shear = shearAt(y, glyphs.length)
      row.forEach((ink, x) => {
        if (!ink) return
        const line = grid[y + dy]
        if (line) line[x + shear + dx] = layer
      })
    })
  }

  paint("shadow", SHADOW_DX, SHADOW_DY)
  paint("main", 0, 0)
  return grid
}

/**
 * 像素网格 → 字符网格：每两个像素行压成一行半块字符。
 *
 * 上下两半同色就用 `█`，只有一半有就用 `▀`/`▄`，
 * 一半本体一半投影就 `▀` 配一个背景色——半格精度全靠最后这一种。
 */
export function buildWordmark(text: string): Cell[][] {
  const pixels = buildPixels(text)
  const width = pixels[0]?.length ?? 0
  const rows: Cell[][] = []

  for (let y = 0; y < pixels.length; y += 2) {
    const upperRow = pixels[y]!
    const lowerRow = pixels[y + 1]
    rows.push(
      Array.from({ length: width }, (_, x) => {
        const upper = upperRow[x] ?? null
        const lower = lowerRow?.[x] ?? null
        if (upper === null && lower === null) return { char: " ", fg: null, bg: null }
        if (lower === null) return { char: "▀", fg: upper, bg: null }
        if (upper === null) return { char: "▄", fg: lower, bg: null }
        if (upper === lower) return { char: "█", fg: upper, bg: null }
        return { char: "▀", fg: upper, bg: lower }
      }),
    )
  }
  return rows
}

/** 字标占几列。外层要据此决定画得下画不下。 */
export function wordmarkWidth(text: string): number {
  return Math.max(0, ...glyphRows(text).map((row) => row.length)) + SHEAR + SHADOW_DX
}

/** 把一行格子并成同色连续段。 */
export function toRuns(row: Cell[]): Run[] {
  const runs: Run[] = []
  for (const cell of row) {
    const last = runs[runs.length - 1]
    if (last && last.fg === cell.fg && last.bg === cell.bg) last.text += cell.char
    else runs.push({ text: cell.char, fg: cell.fg, bg: cell.bg })
  }
  return runs
}
