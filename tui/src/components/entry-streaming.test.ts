import { describe, expect, test } from "bun:test"
import { settledMarkdown } from "./EntryView.tsx"

describe("settledMarkdown", () => {
  test("还没换行时什么都不给——这一行还没定型", () => {
    expect(settledMarkdown("## 标")).toBe("")
    expect(settledMarkdown("一句还没写完的话")).toBe("")
  })

  test("只给到最后一个换行为止，半行丢掉", () => {
    expect(settledMarkdown("## 标题\n正文还没写完")).toBe("## 标题\n")
  })

  test("标题带紧挨着的列表逐行落下，不用等整张列表写完", () => {
    // 这是真实场景：一个小标题带九条嵌套列表，中间一个空行都没有。
    // 用空行做边界的话整坨算一个块，要等全部写完才「啪」地出现一大片。
    const text = "**核心组成**\n- Java 后端\n  - agent/ — 主循环\n  - llm/ — 模型客户"
    expect(settledMarkdown(text)).toBe("**核心组成**\n- Java 后端\n  - agent/ — 主循环\n")
  })

  test("块内的空行不影响——边界是行不是空行", () => {
    expect(settledMarkdown("第一段\n\n第二段\n第三行没写完")).toBe("第一段\n\n第二段\n")
  })

  test("未闭合的代码围栏照常逐行给出：此刻它后面的内容本来就都是代码", () => {
    const text = "说明\n\n```java\nint x = 1;\nint y = 2"
    expect(settledMarkdown(text)).toBe("说明\n\n```java\nint x = 1;\n")
  })
})
