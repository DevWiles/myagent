import { describe, expect, test } from "bun:test"
import {
  buildPixels,
  buildWordmark,
  glyphRows,
  toPixels,
  toRuns,
  wordmarkWidth,
  type Cell,
} from "./wordmark.ts"

/** 像素网格画成示意图，断言比对一堆枚举值直观得多。 */
function sketch(text: string): string[] {
  return buildPixels(text).map((row) =>
    row.map((pixel) => (pixel === null ? " " : pixel === "main" ? "#" : ".")).join(""),
  )
}

describe("glyphRows", () => {
  test("tiny 字模是两行高", () => {
    expect(glyphRows("I")).toHaveLength(2)
  })

  test("只有大写字形，小写按大写画", () => {
    expect(glyphRows("welcome")).toEqual(glyphRows("WELCOME"))
  })

  test("行尾不留多余字距——留着会让外层居中偏左", () => {
    for (const row of glyphRows("Welcome")) {
      expect(row).toBe(row.trimEnd())
    }
  })

  test("不认识的字符不会崩，退回空格字形", () => {
    expect(() => glyphRows("★")).not.toThrow()
  })
})

describe("toPixels", () => {
  test("半块字符拆成上下两个像素，高度翻倍", () => {
    expect(toPixels(["█▀▄ "])).toEqual([
      [true, true, false, false],
      [true, false, true, false],
    ])
  })

  test("认不出的字符按空白处理，不会变成实心块", () => {
    expect(toPixels(["╬"])).toEqual([[false], [false]])
  })
})

describe("buildPixels", () => {
  test("投影只沉一个像素行——这是半格精度的全部意义", () => {
    // 按字符格排的话要沉两个像素行，投影就成了本体的等大复制品
    expect(buildPixels("I")).toHaveLength(toPixels(glyphRows("I")).length + 1)
  })

  test("最上面一个像素行没有投影，最下面一个只有投影", () => {
    const grid = buildPixels("Welcome")
    expect(grid[0]!.some((pixel) => pixel === "shadow")).toBe(false)
    expect(grid[grid.length - 1]!.every((pixel) => pixel !== "main")).toBe(true)
  })

  test("重叠处本体压住投影，不是反过来", () => {
    // 竖笔连着两个像素行，投影下沉一格后必定和本体重叠
    const grid = buildPixels("I")
    const x = grid[0]!.findIndex((pixel) => pixel === "main")
    expect(grid[1]![x]).toBe("main")
  })

  test("投影不横向铺开——每一列要么跟本体同列，要么空着", () => {
    // SHADOW_DX = 0 的意思：光源在正上方。一旦给横向偏移，
    // 横向没有半格可用，一挪就是满格，投影面积会盖过字本身。
    const grid = buildPixels("Welcome")
    const columnsWithMain = new Set<number>()
    grid.forEach((row) => row.forEach((pixel, x) => pixel === "main" && columnsWithMain.add(x)))
    grid.forEach((row) =>
      row.forEach((pixel, x) => {
        if (pixel === "shadow") expect(columnsWithMain.has(x)).toBe(true)
      }),
    )
  })

  test("每一行等长，渲染时不用补齐", () => {
    const width = wordmarkWidth("Welcome")
    for (const row of buildPixels("Welcome")) expect(row).toHaveLength(width)
  })

  test("字与字之间留得住缝", () => {
    // TRACKING 的作用：tiny 自带的 1 格字距是正文尺寸下的设定，
    // 放大成字标后太挤，字母会连读。
    expect(sketch("WW")[0]).toContain("  ")
  })
})

describe("buildWordmark", () => {
  test("像素行两两压成一行字符", () => {
    const pixels = buildPixels("Welcome")
    expect(buildWordmark("Welcome")).toHaveLength(Math.ceil(pixels.length / 2))
  })

  test("上下同层用整块，只有一半用半块", () => {
    const cells = buildWordmark("Welcome").flat()
    for (const cell of cells) {
      if (cell.char === "█") expect(cell.bg).toBeNull()
      if (cell.char === " ") expect(cell.fg).toBeNull()
    }
  })

  test("上半本体、下半投影的格子用 ▀ 加一个背景色——半格精度全靠这一种", () => {
    const mixed = buildWordmark("Welcome")
      .flat()
      .filter((cell) => cell.bg !== null)
    expect(mixed.length).toBeGreaterThan(0)
    for (const cell of mixed) {
      expect(cell.char).toBe("▀")
      expect(cell.fg).toBe("main")
      expect(cell.bg).toBe("shadow")
    }
  })
})

describe("toRuns", () => {
  test("同色连续格并成一段", () => {
    const main: Cell = { char: "█", fg: "main", bg: null }
    const blank: Cell = { char: " ", fg: null, bg: null }
    expect(toRuns([main, main, blank, main])).toEqual([
      { text: "██", fg: "main", bg: null },
      { text: " ", fg: null, bg: null },
      { text: "█", fg: "main", bg: null },
    ])
  })

  test("背景色不同就得断开——否则投影会糊到相邻格子上", () => {
    const solid: Cell = { char: "█", fg: "main", bg: null }
    const mixed: Cell = { char: "▀", fg: "main", bg: "shadow" }
    expect(toRuns([solid, mixed])).toHaveLength(2)
  })

  test("并段不改变这一行的字符内容", () => {
    for (const row of buildWordmark("Welcome")) {
      const joined = toRuns(row)
        .map((run) => run.text)
        .join("")
      expect(joined).toBe(row.map((cell) => cell.char).join(""))
    }
  })
})
