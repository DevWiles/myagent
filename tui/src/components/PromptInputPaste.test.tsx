/**
 * 真走一遍终端的括号粘贴：大段折成占位符，提交时模型拿全文、转录拿折起来的那份。
 */

import { expect, test } from "bun:test"
import { mkdtempSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { testRender } from "@opentui/solid"
import { PromptInput } from "./PromptInput.tsx"

async function setup(imagesBlocked?: string) {
  const submitted: { value: string; display: string; images?: number }[] = []
  const notices: string[] = []
  const r = await testRender(
    () => (
      <PromptInput
        placeholder=""
        imagesBlocked={imagesBlocked}
        onNotice={(_, text) => notices.push(text)}
        onSubmit={(value, display, images) =>
          submitted.push(images.length > 0 ? { value, display, images: images.length } : { value, display })}
      />
    ),
    { width: 80, height: 10 },
  )
  await r.renderOnce()
  return { ...r, submitted, notices }
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

const PNG = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0])

function pngFile(): string {
  const p = join(mkdtempSync(join(tmpdir(), "myagent-drop-")), "shot.png")
  writeFileSync(p, PNG)
  return p
}

test("把图片文件拖进终端：输入框里是 [图片 #1]，提交时图跟着走", async () => {
  const { mockInput, renderOnce, captureCharFrame, submitted } = await setup()
  await mockInput.typeText("这个报错 ")
  await mockInput.pasteBracketedText(`"${pngFile()}"`)
  await renderOnce()
  expect(captureCharFrame()).toContain("这个报错 [图片 #1]")

  mockInput.pressEnter()
  expect(submitted).toEqual([{ value: "这个报错 [图片 #1]", display: "这个报错 [图片 #1]", images: 1 }])
})

test("计划模式下带图：拦下、说明原因、输入框原样留着", async () => {
  const { mockInput, renderOnce, captureCharFrame, submitted, notices } = await setup("计划模式还不支持带图")
  await mockInput.pasteBracketedText(pngFile())
  mockInput.pressEnter()
  await renderOnce()

  expect(submitted).toEqual([])
  expect(notices[0]).toContain("计划模式")
  expect(captureCharFrame()).toContain("[图片 #1]")
})
