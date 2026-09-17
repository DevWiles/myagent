import { describe, expect, test } from "bun:test"
import { completedBlocks } from "./EntryView.tsx"

describe("completedBlocks", () => {
  test("还没出现空行时什么都不给——整段都还没写完", () => {
    expect(completedBlocks("## 标")).toBe("")
    expect(completedBlocks("一段还没写完的话")).toBe("")
  })

  test("只给到最后一个空行为止，尾块丢掉", () => {
    expect(completedBlocks("第一段\n\n第二段还没写完")).toBe("第一段\n\n")
  })

  test("多个完整块一起给", () => {
    const text = "## 标题\n\n- 项一\n- 项二\n\n收尾的半句"
    expect(completedBlocks(text)).toBe("## 标题\n\n- 项一\n- 项二\n\n")
  })

  test("切口落在未闭合的代码围栏里时，退到围栏之前", () => {
    // ```java 已经开了但还没收尾：整块都不能画，否则会先被当成
    // 「包住剩余内容的代码块」，等收尾围栏到了又变回去
    const text = "说明文字\n\n```java\nint x = 1;\n\n还在写"
    expect(completedBlocks(text)).toBe("说明文字\n\n")
  })

  test("代码块闭合之后就可以整块给出去", () => {
    const text = "说明\n\n```java\nint x = 1;\n```\n\n后续"
    expect(completedBlocks(text)).toBe("说明\n\n```java\nint x = 1;\n```\n\n")
  })
})
