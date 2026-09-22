import { describe, expect, test } from "bun:test"
import { gutterWidth, lineNoOf, parseUnified, visibleRows } from "./diff-view.ts"

/** 后端 TextDiff.unified 的真实格式：抬头 `@@ -旧 +新 @@`，正文两字符前缀。 */
const SAMPLE = [
  "@@ -14 +15 @@",
  "  第一行没变",
  "- 删掉这行",
  "+ 换成这行",
  "+ 还多加一行",
  "  最后一行没变",
].join("\n")

describe("解析行号", () => {
  const rows = parseUnified(SAMPLE)

  test("抬头单独成行，不占行号", () => {
    expect(rows[0]).toMatchObject({ kind: "hunk", oldNo: null, newNo: null })
    expect(lineNoOf(rows[0]!)).toBe("")
  })

  test("上下文行两边一起走", () => {
    expect(rows[1]).toMatchObject({ kind: "context", oldNo: 14, newNo: 15, text: "第一行没变" })
  })

  test("删除行只走旧文件的计数", () => {
    expect(rows[2]).toMatchObject({ kind: "del", oldNo: 15, newNo: null, text: "删掉这行" })
  })

  test("新增行只走新文件的计数", () => {
    expect(rows[3]).toMatchObject({ kind: "add", oldNo: null, newNo: 16, text: "换成这行" })
    expect(rows[4]).toMatchObject({ kind: "add", oldNo: null, newNo: 17, text: "还多加一行" })
  })

  test("一增一删之后，两边的计数各自对得上", () => {
    // 旧文件：14 上下文、15 删除 → 下一个上下文是 16
    // 新文件：15 上下文、16/17 新增 → 下一个上下文是 18
    expect(rows[5]).toMatchObject({ kind: "context", oldNo: 16, newNo: 18 })
  })

  test("删除行显示旧行号，其余显示新行号——要跳去改代码，跳的是新文件", () => {
    expect(lineNoOf(rows[2]!)).toBe("15")   // 删除 → 旧
    expect(lineNoOf(rows[3]!)).toBe("16")   // 新增 → 新
    expect(lineNoOf(rows[5]!)).toBe("18")   // 上下文 → 新
  })

  test("多个 hunk：第二段从它自己的抬头重新起算", () => {
    const rows2 = parseUnified("@@ -1 +1 @@\n  a\n@@ -50 +60 @@\n  b")
    expect(rows2[1]).toMatchObject({ oldNo: 1, newNo: 1 })
    expect(rows2[3]).toMatchObject({ oldNo: 50, newNo: 60 })
  })

  test("带行数的抬头也认（@@ -1,5 +1,7 @@）——万一后端换了格式", () => {
    const rows2 = parseUnified("@@ -1,5 +2,7 @@\n  a")
    expect(rows2[1]).toMatchObject({ oldNo: 1, newNo: 2 })
  })

  test("正文原样保留，前缀剥干净", () => {
    expect(rows.map((r) => r.text)).not.toContain("- 删掉这行")
    expect(rows[2]!.text).toBe("删掉这行")
  })

  test("空 diff 不炸，返回空", () => {
    expect(parseUnified("")).toEqual([])
  })
})

describe("行号列宽", () => {
  test("最少三位——宽度跟着行号变的话，代码起始列会抖", () => {
    expect(gutterWidth(parseUnified("@@ -1 +1 @@\n  a"))).toBe(3)
  })

  test("行号更长就跟着加宽", () => {
    expect(gutterWidth(parseUnified("@@ -1 +1234 @@\n  a"))).toBe(4)
  })

  test("没有行的时候也给个宽度，别返回 0", () => {
    expect(gutterWidth([])).toBe(3)
  })
})

describe("按预算裁行", () => {
  test("放得下就全给", () => {
    const rows = parseUnified(SAMPLE)
    expect(visibleRows(rows, 100)).toEqual({ rows, hidden: 0 })
  })

  test("放不下要报还剩多少——悄悄少给的话，人以为自己看全了", () => {
    const rows = parseUnified(SAMPLE)
    const cut = visibleRows(rows, 3)
    expect(cut.rows).toHaveLength(2)          // 留一行给「还有 N 行」那句话
    expect(cut.hidden).toBe(rows.length - 2)
  })

  test("预算为 0 也至少给一行，不留一块空白", () => {
    expect(visibleRows(parseUnified(SAMPLE), 0).rows.length).toBeGreaterThan(0)
  })
})
