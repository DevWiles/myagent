import { describe, expect, test } from "bun:test"
import {
  diffBudgetFor,
  diffLineKind,
  editFeedback,
  MAX_DIFF_LINES,
  MAX_FEEDBACK,
  MIN_DIFF_LINES,
  nextRuleIndex,
} from "./approval.ts"


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

describe("一组里多个 diff 分预算", () => {
  test("只有一个就给足——单独一次写入，该看多少看多少", () => {
    expect(diffBudgetFor(1)).toBe(MAX_DIFF_LINES)
    expect(diffBudgetFor(0)).toBe(MAX_DIFF_LINES)
  })

  test("文件一多就每个少露几行，总量不失控", () => {
    // 新建工程一次写七八个文件，不收着的话九十行 diff 把上面的对话整个顶出屏幕
    expect(diffBudgetFor(7)).toBeLessThan(MAX_DIFF_LINES)
    expect(diffBudgetFor(7) * 7).toBeLessThanOrEqual(40)
  })

  test("再多也不低于下限——只剩个 @@ 抬头不如不画", () => {
    expect(diffBudgetFor(50)).toBe(MIN_DIFF_LINES)
  })

  test("单调不增：文件越多，每个分到的不会变多", () => {
    const budgets = [1, 2, 3, 5, 10, 30].map(diffBudgetFor)
    for (let i = 1; i < budgets.length; i++) {
      expect(budgets[i]!).toBeLessThanOrEqual(budgets[i - 1]!)
    }
  })
})
