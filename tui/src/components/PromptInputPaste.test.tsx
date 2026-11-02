/**
 * 真走一遍终端的括号粘贴：大段折成占位符，提交时模型拿全文、转录拿折起来的那份。
 */

import { expect, test } from "bun:test"
import { testRender } from "@opentui/solid"
import { PromptInput } from "./PromptInput.tsx"

async function setup() {
  const submitted: { value: string; display: string }[] = []
  const r = await testRender(
    () => <PromptInput placeholder="" onSubmit={(value, display) => submitted.push({ value, display })} />,
    { width: 80, height: 10 },
  )
  await r.renderOnce()
  return { ...r, submitted }
}

const LOG = "PS E:\\> npm run dev\n无法将“VITE_DEMO=1”项识别为 cmdlet\n所在位置 行:1 字符: 1\n+ VITE_DEMO=1 npm run dev"

test("大段粘贴在输入框里只剩一个占位符", async () => {
  const { mockInput, renderOnce, captureCharFrame } = await setup()
  await mockInput.typeText("这个报错 ")
  await mockInput.pasteBracketedText(LOG)
  await renderOnce()

  const frame = captureCharFrame()
  expect(frame).toContain("这个报错 [粘贴的文本 #1 +4 行]")
  expect(frame).not.toContain("所在位置")
})

test("提交：发出去的是全文，转录画折起来的那份", async () => {
  const { mockInput, submitted } = await setup()
  await mockInput.typeText("这个报错 ")
  await mockInput.pasteBracketedText(LOG)
  mockInput.pressEnter()

  expect(submitted).toHaveLength(1)
  expect(submitted[0].value).toBe(`这个报错 ${LOG}`)
  expect(submitted[0].display).toBe("这个报错 [粘贴的文本 #1 +4 行]")
})

test("两行以内的粘贴照常插进去，不折", async () => {
  const { mockInput, renderOnce, captureCharFrame, submitted } = await setup()
  await mockInput.pasteBracketedText("npm run dev")
  await renderOnce()
  expect(captureCharFrame()).toContain("npm run dev")
  mockInput.pressEnter()
  expect(submitted[0]).toEqual({ value: "npm run dev", display: "npm run dev" })
})
