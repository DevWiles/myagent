import { expect, test } from "bun:test"
import { testRender } from "@opentui/solid"
import { EntryView } from "./EntryView.tsx"
import { ThemeProvider, dark } from "../theme.ts"
import type { Entry } from "../store.ts"

/**
 * 代码块要**真的画在屏幕上**。
 *
 * <h3>为什么非要渲染一遍，不能只测纯函数</h3>
 * 这个文件是补出来的，起因是一次实打实的翻车：`code-highlight.ts` 的 23 条单测全绿、
 * 类型检查也过，而屏幕上那一整块代码是 <b>11 行 `[object Object]`</b>。
 *
 * <p>原因是 `<text content={styledText}/>` 这个写法——类型上完全合法
 * （`TextOptions.content?: StyledText | string`），但 Solid 那层不认，直接字符串化了。
 * <b>纯函数测的是「算出了什么」，渲染测的是「画出了什么」，这次错在后者。</b>
 *
 * <p>所以这里断言的是最终画面里的字符，而不是中间结构。
 */

const assistant = (text: string): Entry => ({ kind: "assistant", key: "a1", text })

async function frameOf(text: string): Promise<string> {
  const { captureCharFrame, flush } = await testRender(
    () => (
      <ThemeProvider value={dark}>
        <EntryView entry={assistant(text)} streaming={false} />
      </ThemeProvider>
    ),
    { width: 60, height: 20 },
  )
  // flush 而不是 renderOnce：<markdown> 的解析是异步的，只画一帧的话
  // 散文那部分还是空白，断言会红在一个和被测行为无关的地方
  await flush()
  return captureCharFrame()
}

test("代码块画出来的是代码，不是 [object Object]", async () => {
  const frame = await frameOf("```java\npublic class A {\n}\n```")

  expect(frame).not.toContain("[object Object]")
  expect(frame).toContain("public class A {")
})

test("没写语言标记的代码块照样画得出内容", async () => {
  // 模型很爱直接写 ```，而这一路走的是「退回纯文本」那条分支——
  // 翻车那次的截图里就是这种块（一棵文件树）
  const frame = await frameOf("```\nmyapp/\n  pom.xml\n  src/\n```")

  expect(frame).not.toContain("[object Object]")
  expect(frame).toContain("myapp/")
  expect(frame).toContain("pom.xml")
})

test("认不出的语言不影响内容显示", async () => {
  const frame = await frameOf("```mermaid\ngraph TD\n  A-->B\n```")

  expect(frame).not.toContain("[object Object]")
  expect(frame).toContain("graph TD")
})

test("代码块里的空行保留，缩进不错位", async () => {
  const frame = await frameOf("```python\na = 1\n\n    b = 2\n```")
  const lines = frame.split("\n").map((l) => l.trimEnd())

  const first = lines.findIndex((l) => l.includes("a = 1"))
  expect(first).toBeGreaterThanOrEqual(0)
  expect(lines[first + 1]).toBe("")                       // 空行还在
  expect(lines[first + 2]).toContain("    b = 2")          // 缩进原样
})

/**
 * 散文走的是 `<markdown>`，而它要一个 tree-sitter 客户端，
 * 这个测试环境里没有（{@code @opentui/core/testing} 专门提供了
 * `mock-tree-sitter-client` 就是为了这个）——所以画面上是空白。
 *
 * <p>**这里只断言代码块那半边**，不去断言散文。硬测散文的话，
 * 红的会是一个和被测行为毫无关系的地方，而这种测试迟早被人注释掉。
 */
test("和散文混排时，代码块那半边照样画得出来", async () => {
  const frame = await frameOf("先看这段：\n\n```java\nint x = 1;\n```\n\n就这些。")

  expect(frame).not.toContain("[object Object]")
  expect(frame).toContain("int x = 1;")
})
