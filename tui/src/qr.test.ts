import { describe, expect, test } from "bun:test"
import { qrText, renderHalfBlocks } from "./qr.ts"

describe("renderHalfBlocks", () => {
  test("两个模块摞进一个字符：亮走前景、暗走空格", () => {
    // 前景画的是亮处：暗上亮下 → 只画下半（▄）；亮上暗下 → 只画上半（▀）
    const matrix = [
      [true, false],
      [false, true],
    ]
    expect(renderHalfBlocks(matrix)).toBe("▄▀")
  })

  test("全亮是实心块，全暗是空格", () => {
    expect(renderHalfBlocks([[false, false], [false, false]])).toBe("██")
    expect(renderHalfBlocks([[true, true], [true, true]])).toBe("  ")
  })

  test("奇数行时越界的下半格当亮处理——底色本来就是亮的", () => {
    expect(renderHalfBlocks([[true]])).toBe("▄")
    expect(renderHalfBlocks([[false]])).toBe("█")
  })

  test("整码：行数是模块数的一半（向上取整），每行等宽", () => {
    const text = qrText("ws://192.168.1.7:61772/?token=0123456789abcdef0123456789abcdef")
    const lines = text.split("\n")
    const widths = new Set(lines.map((l) => [...l].length))
    expect(widths.size).toBe(1)
    expect(lines.length).toBeGreaterThan(10)
    // 静区：四角必须是亮的（前景块），否则贴边的码扫不出来
    expect(text.startsWith("██")).toBe(true)
    expect(text.endsWith("██")).toBe(true)
  })
})
