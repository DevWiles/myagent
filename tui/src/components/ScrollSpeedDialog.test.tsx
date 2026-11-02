import { expect, test } from "bun:test"
import type { ScrollBoxRenderable } from "@opentui/core"
import { testRender } from "@opentui/solid"
import { For } from "solid-js"
import { ScrollSpeedDialog } from "./ScrollSpeedDialog.tsx"

async function frameOf(speed: number, envOverride = false): Promise<string> {
  const { renderOnce, captureCharFrame } = await testRender(
    () => <ScrollSpeedDialog speed={speed} envOverride={envOverride} />,
    { width: 80, height: 10 },
  )
  await renderOnce()
  return captureCharFrame()
}

test("弹窗写清当前速度和怎么调", async () => {
  const frame = await frameOf(0.25)
  expect(frame).toContain("0.25")
  expect(frame).toContain("←→ 调")
  expect(frame).toContain("Enter 保存")
  expect(frame).not.toContain("MYAGENT_SCROLL_SPEED")
})

test("环境变量压着时要说：存了下次启动也不生效", async () => {
  expect(await frameOf(3, true)).toContain("MYAGENT_SCROLL_SPEED")
})

test("对话区的滚动条关掉之后，内容再多也不会被自动打开", async () => {
  let box: ScrollBoxRenderable | undefined
  const { renderOnce } = await testRender(
    () => (
      <scrollbox
        height={5}
        ref={(r: ScrollBoxRenderable) => {
          box = r
          r.verticalScrollBar.visible = false
        }}
      >
        <For each={Array.from({ length: 50 }, (_, i) => i)}>{(i) => <text>{`第 ${i} 行`}</text>}</For>
      </scrollbox>
    ),
    { width: 30, height: 5 },
  )
  await renderOnce()
  await renderOnce()
  expect(box!.scrollHeight).toBeGreaterThan(5)
  expect(box!.verticalScrollBar.visible).toBe(false)
})
