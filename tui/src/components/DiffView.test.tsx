import { expect, test } from "bun:test"
import { testRender } from "@opentui/solid"
import { DiffView } from "./DiffView.tsx"
import { dark, ThemeProvider } from "../theme.ts"

/**
 * 行号和底色必须**真的画在屏幕上**。
 *
 * <p>这一组全部断言最终画面，不看中间结构——理由和 `CodeBlock.test.tsx` 一样：
 * 上一次翻车就是单测全绿而屏幕上一片 `[object Object]`。
 * 底色尤其只能这么测：它不体现在字符里，只在 span 的 bg 上。
 */

const DIFF = ["@@ -14 +15 @@", "  没变的一行", "- 删掉这行", "+ 换成这行"].join("\n")

async function render(diff: string, columns = 40) {
  const setup = await testRender(
    () => (
      <ThemeProvider value={dark}>
        <DiffView diff={diff} columns={columns} budget={20} />
      </ThemeProvider>
    ),
    { width: columns, height: 10 },
  )
  await setup.renderOnce()
  return setup
}

/**
 * 把一行的 bg 取出来，转成 "r,g,b,a"，没有底色的是全 0。
 *
 * <p>类型从 `captureSpans()` 的返回值上推，不自己手写——手写一份等于
 * 给 OpenTUI 的内部结构又立了一份会过期的副本。
 */
type Captured = Awaited<ReturnType<typeof testRender>>
type CapturedLine = ReturnType<Captured["captureSpans"]>["lines"][number]

const bgOf = (line: CapturedLine): string => {
  const bg = line.spans[0]?.bg
  return bg ? Array.from(bg.buffer).join(",") : ""
}

test("行号画出来了，而且删除行用旧号、新增行用新号", async () => {
  const { captureCharFrame } = await render(DIFF)
  const lines = captureCharFrame().split("\n")

  expect(lines[1]).toContain("15")          // 上下文：新文件第 15 行（旧的是 14）
  expect(lines[2]).toContain("15")          // 删除：旧文件第 15 行
  expect(lines[3]).toContain("16")          // 新增：新文件第 16 行
})

test("上下文行显示的是新文件的行号", async () => {
  const { captureCharFrame } = await render(DIFF)
  // 旧 14 / 新 15，显示新的那个
  expect(captureCharFrame().split("\n")[1]).toContain("15")
})

test("增删两行有底色，上下文没有——这是这次改动的重点", async () => {
  const { captureSpans } = await render(DIFF)
  const lines = captureSpans().lines

  expect(bgOf(lines[1]!)).toBe("0,0,0,0")   // 上下文：透明
  expect(bgOf(lines[2]!)).not.toBe("0,0,0,0")  // 删除：有底色
  expect(bgOf(lines[3]!)).not.toBe("0,0,0,0")  // 新增：有底色
})

test("增和删的底色不一样，不然分不出哪行是哪种", async () => {
  const { captureSpans } = await render(DIFF)
  const lines = captureSpans().lines
  expect(bgOf(lines[2]!)).not.toBe(bgOf(lines[3]!))
})

test("底色铺满整行——只给文字上色的话，右边会缺一块", async () => {
  const columns = 40
  const { captureSpans } = await render(DIFF, columns)
  const added = captureSpans().lines[3]!
  // 整行只有一个 span，且宽度等于可用列数：说明尾部空白也带着底色
  const total = added.spans.reduce((sum, s) => sum + s.width, 0)
  expect(total).toBe(columns)
  expect(added.spans).toHaveLength(1)
})

test("中文行也铺满——按字符数补空格的话这里会短一截", async () => {
  const columns = 40
  const { captureSpans } = await render("@@ -1 +1 @@\n+ 全是中文的一行内容", columns)
  const added = captureSpans().lines[1]!
  const total = added.spans.reduce((sum, s) => sum + s.width, 0)
  expect(total).toBe(columns)
})

test("hunk 抬头不带行号也不铺底色——它是分段标记，不是一行代码", async () => {
  const { captureCharFrame, captureSpans } = await render(DIFF)
  expect(captureCharFrame().split("\n")[0]).toContain("@@")
  expect(bgOf(captureSpans().lines[0]!)).toBe("0,0,0,0")
})

test("超出预算要说还剩多少行", async () => {
  const many = ["@@ -1 +1 @@", ...Array.from({ length: 30 }, (_, i) => `+ 第 ${i} 行`)].join("\n")
  const setup = await testRender(
    () => (
      <ThemeProvider value={dark}>
        <DiffView diff={many} columns={40} budget={5} />
      </ThemeProvider>
    ),
    { width: 40, height: 10 },
  )
  await setup.renderOnce()
  expect(setup.captureCharFrame()).toContain("还有")
})

test("估宽超出实际宽度时也不折行——折了每行 diff 会变两行，看着像一倍行距", async () => {
  // columns 故意报大：真实场景里它是上层逐层扣 padding 估出来的，总会有偏差。
  // 这个偏差的代价必须是「行尾裁掉几个空格」，不能是「整行折下去」
  const setup = await testRender(
    () => (
      <ThemeProvider value={dark}>
        <DiffView diff={DIFF} columns={46} budget={20} />
      </ThemeProvider>
    ),
    { width: 40, height: 8 },
  )
  await setup.renderOnce()
  const lines = setup.captureCharFrame().split("\n")

  // 四行 diff 紧挨着占前四行，中间没有空行
  expect(lines[0]).toContain("@@")
  expect(lines[1]).toContain("没变的一行")
  expect(lines[2]).toContain("删掉这行")
  expect(lines[3]).toContain("换成这行")
})
