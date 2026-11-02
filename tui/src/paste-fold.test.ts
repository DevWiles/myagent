import { describe, expect, test } from "bun:test"
import { lineCount, normalizePaste, PasteStore, pastePlaceholder, shouldFold } from "./paste-fold.ts"

describe("什么样的粘贴要折", () => {
  test("三行起折；两行以内照常插", () => {
    expect(shouldFold("a\nb")).toBe(false)
    expect(shouldFold("a\nb\nc")).toBe(true)
  })

  test("一行但很长（压成一行的 JSON）也折", () => {
    expect(shouldFold("x".repeat(801))).toBe(true)
    expect(shouldFold("x".repeat(800))).toBe(false)
  })

  test("CRLF 统一成 LF；末尾的空行不算一行", () => {
    expect(normalizePaste("a\r\nb\rc")).toBe("a\nb\nc")
    expect(lineCount("a\nb\n\n")).toBe(2)
  })
})

describe("占位符", () => {
  test("多行写行数，一行写字数", () => {
    expect(pastePlaceholder(1, "a\nb\nc")).toBe("[粘贴的文本 #1 +3 行]")
    expect(pastePlaceholder(2, "x".repeat(900))).toBe("[粘贴的文本 #2 900 字]")
  })

  test("提交时换回原文，前后自己打的字原样留着；两段各换各的", () => {
    const store = new PasteStore()
    const a = store.add("第一段\n1\n2")
    const b = store.add("第二段\n3\n4")
    expect(store.expand(`看下这个 ${a} 和 ${b} 哪里错了`)).toBe("看下这个 第一段\n1\n2 和 第二段\n3\n4 哪里错了")
  })

  test("占位符被改坏了就不展开——删了就是不要了", () => {
    const store = new PasteStore()
    const p = store.add("a\nb\nc")
    expect(store.expand(p.slice(0, -1))).toBe(p.slice(0, -1))
  })

  test("清空之后编号从头来、旧的不再展开", () => {
    const store = new PasteStore()
    const p = store.add("a\nb\nc")
    store.clear()
    expect(store.expand(p)).toBe(p)
    expect(store.add("d\ne\nf")).toBe("[粘贴的文本 #1 +3 行]")
  })
})
