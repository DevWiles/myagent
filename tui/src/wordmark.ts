/**
 * 字标（wordmark）的排版：把一串字母排成**斜体 + 带投影**的点阵。
 *
 * 终端里做这两个效果都得自己画，没有现成开关：
 *
 * - **斜体**：`TextAttributes.ITALIC` 对 `█▀▄` 这类方块字形不起作用（终端要么忽略，
 *   要么按普通字符去斜切，出来是歪的边框线）。真正管用的是**错切**——
 *   上面的行整体右移，下面的行不动，字形就朝右倾了。移 1 格已经很明显，不用更多。
 * - **投影**：把同一份字形往右下各挪一格、用暗色再画一遍，本体盖在上面。
 *   这是平面设计里最常规的 drop shadow，落到字符网格上就是「两层合成，本体优先」。
 *
 * 字模直接用 OpenTUI 自带的 `tiny`（2 行高）。为什么不用它的 `<ascii-font>`：
 * 那个组件把字模直接画进 framebuffer，中途插不进错切和第二层，而这里要的正是这两步。
 * 招牌 `Banner.tsx` 用的是手写字模（6 行），那是主视觉；这里只要「比正文大一点」，
 * 2 行的 tiny 加上投影一共 3 行，正好。
 *
 * 排版是纯函数，渲染在 `components/Wordmark.tsx`——网格算法可以单测，JSX 不好测。
 */

import { fonts } from "@opentui/core"

/** 上一行相对下一行右移几格。1 就够斜了，2 会散架。 */
const SHEAR = 1

/** 投影相对本体的偏移（右 1 下 1）。 */
const SHADOW_DX = 1
const SHADOW_DY = 1

/**
 * 在字模自带的字距之外再加几格。
 *
 * 不加会出事：`tiny` 的字距正好 1 格，而投影也正好右移 1 格，
 * 于是每个字母的投影把它右边那道缝**严丝合缝地堵上**，整行糊成一条实心带子。
 * 多留一格，投影落进缝里之后仍剩一格空白，字与字才分得开。
 */
const TRACKING = 1

/** 一个格子属于哪一层。本体亮、投影暗，立体感全靠这个深浅差。 */
export type Layer = "main" | "shadow"

/** 有笔画的格子。空格不占格子，用 `null` 表示，省得渲染时还要判断内容。 */
export type Cell = { char: string; layer: Layer }

/** 同色连续段。一行几十格逐格建节点太碎，先并成段再渲染。 */
export type Run = { text: string; layer: Layer | null }

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

/**
 * 排出字标网格：`grid[行][列]`，`null` 是空白。
 *
 * 先铺投影再盖本体——重叠处本体赢，这就是「本体在前、投影在后」的层次。
 */
export function buildWordmark(text: string): (Cell | null)[][] {
  const glyphs = glyphRows(text)
  const height = glyphs.length + SHADOW_DY
  const width = Math.max(0, ...glyphs.map((row) => row.length)) + SHEAR + SHADOW_DX

  const grid: (Cell | null)[][] = Array.from({ length: height }, () =>
    Array<Cell | null>(width).fill(null),
  )

  const paint = (layer: Layer, dx: number, dy: number): void => {
    glyphs.forEach((row, y) => {
      // 越靠上移得越多，底行不动——错切的斜度就是这么来的
      const shear = (glyphs.length - 1 - y) * SHEAR
      Array.from(row).forEach((char, x) => {
        if (char === " ") return
        const line = grid[y + dy]
        if (line) line[x + shear + dx] = { char, layer }
      })
    })
  }

  paint("shadow", SHADOW_DX, SHADOW_DY)
  paint("main", 0, 0)
  return grid
}

/** 字标占几列。外层要据此决定画得下画不下。 */
export function wordmarkWidth(text: string): number {
  return Math.max(0, ...glyphRows(text).map((row) => row.length)) + SHEAR + SHADOW_DX
}

/** 把一行格子并成同色连续段。 */
export function toRuns(row: (Cell | null)[]): Run[] {
  const runs: Run[] = []
  for (const cell of row) {
    const layer = cell?.layer ?? null
    const last = runs[runs.length - 1]
    if (last && last.layer === layer) last.text += cell?.char ?? " "
    else runs.push({ text: cell?.char ?? " ", layer })
  }
  return runs
}
