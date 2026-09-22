import { describe, expect, test } from "bun:test"
import {
  askChoices,
  diffBudgetFor,
  diffLineKind,
  digitIndex,
  editFeedback,
  MAX_DIFF_LINES,
  MAX_FEEDBACK,
  MIN_DIFF_LINES,
  moveSelection,
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

describe("moveSelection", () => {
  test("撞到两头就停住，不绕回去", () => {
    // 绕回去的话，用户一路按 ↓ 会从最松的那档回到最紧的，
    // 而他此刻正盯着「以后都同意」几个字；表态那一列更糟，会从拒绝转回同意
    expect(moveSelection(0, -1, 3)).toBe(0)
    expect(moveSelection(2, 1, 3)).toBe(2)
  })

  test("中间正常走", () => {
    expect(moveSelection(1, 1, 3)).toBe(2)
    expect(moveSelection(1, -1, 3)).toBe(0)
  })

  test("一档都没有时不崩", () => {
    expect(moveSelection(0, 1, 0)).toBe(0)
  })
})

describe("askChoices", () => {
  test("有可存的规则时三项，顺序是同意 / 以后都同意 / 拒绝", () => {
    // 默认光标停在 0，所以第一项是什么等于「回车下去会发生什么」
    expect(askChoices(true).map((c) => c.key)).toEqual(["yes", "always", "no"])
  })

  test("后端没提议规则时不画「以后都同意」——那是个进不去的死选项", () => {
    expect(askChoices(false).map((c) => c.key)).toEqual(["yes", "no"])
  })

  test("每一项都有给人看的字，光标选靠的就是它", () => {
    for (const choice of askChoices(true)) {
      expect(choice.label.length).toBeGreaterThan(0)
    }
  })
})

describe("digitIndex", () => {
  test("1 对应第 0 项", () => {
    expect(digitIndex({ sequence: "1" })).toBe(0)
    expect(digitIndex({ sequence: "3" })).toBe(2)
  })

  test("0 不收：选项从 1 编号，按 0 什么也不该发生", () => {
    expect(digitIndex({ sequence: "0" })).toBeNull()
  })

  test("字母和转义序列都不是数字键", () => {
    expect(digitIndex({ sequence: "y" })).toBeNull()
    expect(digitIndex({ name: "down", sequence: "\x1b[B" })).toBeNull()
    expect(digitIndex({})).toBeNull()
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
