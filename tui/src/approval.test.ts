import { describe, expect, test } from "bun:test"
import { diffLineKind, editFeedback, MAX_FEEDBACK, nextRuleIndex, visibleDiff } from "./approval.ts"

describe("visibleDiff", () => {
  test("放得下就全给", () => {
    expect(visibleDiff("@@ -1 +1 @@\n- a\n+ b", 10)).toEqual({
      lines: ["@@ -1 +1 @@", "- a", "+ b"],
      hidden: 0,
    })
  })

  test("放不下要裁，并且报出裁了多少——悄悄少给的时候人会以为看到了全部", () => {
    const preview = Array.from({ length: 20 }, (_, i) => `+ line ${i}`).join("\n")
    const got = visibleDiff(preview, 5)

    expect(got.lines.length).toBe(4) // 留一行给「还有多少」那句话
    expect(got.hidden).toBe(16)
    expect(got.lines.length + got.hidden).toBe(20)
  })

  test("预算再小也至少给一行", () => {
    expect(visibleDiff("+ a\n+ b\n+ c", 0).lines.length).toBe(1)
  })

  test("空的 preview 不产生空行", () => {
    expect(visibleDiff("", 10)).toEqual({ lines: [], hidden: 0 })
    expect(visibleDiff("\n\n", 10)).toEqual({ lines: [], hidden: 0 })
  })
})

describe("diffLineKind", () => {
  test("认得出四种行", () => {
    expect(diffLineKind("@@ -1 +1 @@")).toBe("hunk")
    expect(diffLineKind("+ 新的")).toBe("add")
    expect(diffLineKind("- 旧的")).toBe("del")
    expect(diffLineKind("  没动")).toBe("context")
  })

  test("空行算上下文，不算删除", () => {
    expect(diffLineKind("")).toBe("context")
  })
})

describe("nextRuleIndex", () => {
  test("撞到两头就停住，不绕回去", () => {
    // 绕回去的话，用户一路按 → 会从最松的那档回到最紧的，
    // 而他此刻正盯着「以后都同意」几个字
    expect(nextRuleIndex(0, -1, 3)).toBe(0)
    expect(nextRuleIndex(2, 1, 3)).toBe(2)
  })

  test("中间正常走", () => {
    expect(nextRuleIndex(1, 1, 3)).toBe(2)
    expect(nextRuleIndex(1, -1, 3)).toBe(0)
  })

  test("一档都没有时不崩", () => {
    expect(nextRuleIndex(0, 1, 0)).toBe(0)
  })
})

describe("editFeedback", () => {
  test("可打印字符往后加", () => {
    expect(editFeedback("别用", { sequence: "c" })).toBe("别用c")
  })

  test("退格往回删", () => {
    expect(editFeedback("abc", { name: "backspace" })).toBe("ab")
    expect(editFeedback("", { name: "backspace" })).toBe("")
  })

  test("方向键之类的与我无关，返回 null 让上层继续处理", () => {
    expect(editFeedback("a", { name: "left", sequence: "\x1b[D" })).toBeNull()
    expect(editFeedback("a", { name: "return", sequence: "\r" })).toBeNull()
    expect(editFeedback("a", { name: "escape", sequence: "\x1b" })).toBeNull()
  })

  test("满了就不再加——这句话要进提示词，不能让人粘一整篇", () => {
    const full = "长".repeat(MAX_FEEDBACK)
    expect(editFeedback(full, { sequence: "x" })).toBe(full)
  })
})
