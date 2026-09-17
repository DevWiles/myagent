import { describe, expect, test } from "bun:test"
import { splitBlocks } from "./markdown-blocks.ts"

describe("splitBlocks", () => {
  test("半行标题还在写，不算完成", () => {
    expect(splitBlocks("## 安")).toEqual({ complete: [], current: "## 安" })
  })

  test("标题写完但还没空行，仍算正在写——后面可能还有内容要接上", () => {
    expect(splitBlocks("## 安装\n")).toEqual({ complete: [], current: "## 安装" })
  })

  test("空行一到，前面的块就固化", () => {
    expect(splitBlocks("## 安装\n\n首先执行：")).toEqual({
      complete: ["## 安装"],
      current: "首先执行：",
    })
  })

  test("多个块依次固化，只留最后一块在写", () => {
    const text = "## 安装\n\n首先执行：\n\n然后运行"
    expect(splitBlocks(text)).toEqual({
      complete: ["## 安装", "首先执行："],
      current: "然后运行",
    })
  })

  test("代码围栏没闭合时整块都算正在写", () => {
    const text = "## 安装\n\n```bash\nnpm install xxx"
    expect(splitBlocks(text)).toEqual({
      complete: ["## 安装"],
      current: "```bash\nnpm install xxx",
    })
  })

  test("围栏闭合且后面来了空行，代码块才固化", () => {
    const text = "## 安装\n\n```bash\nnpm install xxx\n```\n\n然后运行："
    expect(splitBlocks(text)).toEqual({
      complete: ["## 安装", "```bash\nnpm install xxx\n```"],
      current: "然后运行：",
    })
  })

  test("围栏内部的空行不是块边界", () => {
    // 这是朴素「按空行切」会切坏的情况
    const text = "```java\nint a = 1;\n\nint b = 2;\n```\n\n后续"
    expect(splitBlocks(text)).toEqual({
      complete: ["```java\nint a = 1;\n\nint b = 2;\n```"],
      current: "后续",
    })
  })

  test("正文和紧随其后的围栏之间没有空行也能分开", () => {
    const text = "说明文字\n```bash\nls\n```\n\n尾巴"
    expect(splitBlocks(text)).toEqual({
      complete: ["说明文字", "```bash\nls\n```"],
      current: "尾巴",
    })
  })

  test("列表整体算一块——列表项之间没有空行", () => {
    const text = "- 项一\n- 项二\n- 项三\n\n收尾"
    expect(splitBlocks(text)).toEqual({
      complete: ["- 项一\n- 项二\n- 项三"],
      current: "收尾",
    })
  })

  test("刚好停在块边界上时 current 为空", () => {
    expect(splitBlocks("## 安装\n\n")).toEqual({ complete: ["## 安装"], current: "" })
  })

  test("空输入", () => {
    expect(splitBlocks("")).toEqual({ complete: [], current: "" })
  })

  test("已完成的块在后续增量中保持逐字不变（这是不重渲染的前提）", () => {
    const a = splitBlocks("## 安装\n\n首先执行：\n\n```bash\nnpm i")
    const b = splitBlocks("## 安装\n\n首先执行：\n\n```bash\nnpm install xxx\n```\n\n然后")
    // 前两块在两次快照里必须完全一样，否则 <For> 会把它们当成新块重建
    expect(b.complete.slice(0, 2)).toEqual(a.complete)
  })
})
