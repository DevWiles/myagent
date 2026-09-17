import { describe, expect, test } from "bun:test"
import { buildWordmark, glyphRows, toRuns, wordmarkWidth, type Cell } from "./wordmark.ts"

/** 把网格画成示意图，断言比对一堆 Cell 对象直观得多。 */
function sketch(text: string): string[] {
  return buildWordmark(text).map((row) =>
    row.map((cell) => (cell === null ? " " : cell.layer === "main" ? "#" : ".")).join(""),
  )
}

/** 某一层在这一行里最左边的位置，-1 表示这行没有这一层。 */
function firstOf(row: (Cell | null)[], layer: "main" | "shadow"): number {
  return row.findIndex((cell) => cell?.layer === layer)
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

describe("buildWordmark", () => {
  test("比字模高一行——多出来的那行是投影", () => {
    expect(buildWordmark("I")).toHaveLength(glyphRows("I").length + 1)
  })

  test("最上面一行只有本体，最下面一行只有投影", () => {
    const grid = buildWordmark("Welcome")
    const top = grid[0]!
    const bottom = grid[grid.length - 1]!
    expect(top.some((cell) => cell?.layer === "shadow")).toBe(false)
    expect(bottom.some((cell) => cell?.layer === "main")).toBe(false)
  })

  test("上面的行整体右移——错切就是斜体的来源", () => {
    const grid = buildWordmark("I")
    expect(firstOf(grid[0]!, "main")).toBeGreaterThan(firstOf(grid[1]!, "main"))
  })

  test("重叠处本体压住投影，不是反过来", () => {
    // "I" 两行都在同一列有笔画，投影右下各挪一格后必定和本体重叠
    const grid = buildWordmark("I")
    const x = firstOf(grid[1]!, "main")
    expect(grid[1]![x]!.layer).toBe("main")
  })

  test("每一行等长，渲染时不用补齐", () => {
    const grid = buildWordmark("Welcome")
    const width = wordmarkWidth("Welcome")
    for (const row of grid) expect(row).toHaveLength(width)
  })

  test("字与字之间留得住缝——投影不把字距堵死", () => {
    // 这是 TRACKING 存在的唯一理由：字模自带的字距是 1 格、投影也正好右移 1 格，
    // 不额外留白的话每道缝都被投影填满，中间那行会糊成 "#####.#####" 一条实心带子。
    // 断言中间那行里存在「本体后面紧跟空白」，就是在守这条缝。
    expect(sketch("WW")[1]).toContain("# ")
  })
})

describe("toRuns", () => {
  test("同层连续格并成一段", () => {
    const main: Cell = { char: "█", layer: "main" }
    expect(toRuns([main, main, null, main])).toEqual([
      { text: "██", layer: "main" },
      { text: " ", layer: null },
      { text: "█", layer: "main" },
    ])
  })

  test("并段不改变这一行的字符内容", () => {
    for (const row of buildWordmark("Welcome")) {
      const joined = toRuns(row)
        .map((run) => run.text)
        .join("")
      expect(joined).toBe(row.map((cell) => cell?.char ?? " ").join(""))
    }
  })

  test("空行并成一段空白", () => {
    expect(toRuns([null, null])).toEqual([{ text: "  ", layer: null }])
  })
})
