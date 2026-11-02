import { describe, expect, test } from "bun:test"
import { testRender } from "@opentui/solid"
import { TrustDialog } from "./TrustDialog.tsx"

async function mount(props: { risk?: string | null; remembered?: boolean } = {}) {
  const answers: string[] = []
  const setup = await testRender(
    () => (
      <TrustDialog
        workspace="/w/proj"
        risk={props.risk ?? null}
        remembered={props.remembered ?? true}
        onAccept={() => answers.push("accept")}
        onDecline={() => answers.push("decline")}
      />
    ),
    { width: 120, height: 20 },
  )
  await setup.renderOnce()
  return { ...setup, answers }
}

describe("信任确认框", () => {
  test("写明路径、两个选项；风险有就显示", async () => {
    const { captureCharFrame } = await mount({ risk: "这是你的家目录" })
    const frame = captureCharFrame()
    expect(frame).toContain("信任这个目录吗")
    expect(frame).toContain("/w/proj")
    expect(frame).toContain("信任，继续")
    expect(frame).toContain("不信任，退出")
    expect(frame).toContain("这是你的家目录")
  })

  test("不记住的时候说清下次还会问", async () => {
    const { captureCharFrame } = await mount({ remembered: false })
    expect(captureCharFrame()).toContain("下次启动还会问")
  })

  test("默认停在「信任」，回车就进", async () => {
    const { mockInput, answers } = await mount()
    mockInput.pressEnter()
    expect(answers).toEqual(["accept"])
  })

  test("挪到「不信任」再回车，或者按 Esc，都是退出", async () => {
    const first = await mount()
    first.mockInput.pressArrow("down")
    first.mockInput.pressEnter()
    expect(first.answers).toEqual(["decline"])

    const second = await mount()
    second.mockInput.pressEscape()
    await new Promise((r) => setTimeout(r, 50))   // Esc 要等一下才能和 Alt 组合键区分开
    expect(second.answers).toEqual(["decline"])
  })

  test("直接按数字", async () => {
    const { mockInput, answers } = await mount()
    mockInput.pressKey("2")
    expect(answers).toEqual(["decline"])
  })
})
