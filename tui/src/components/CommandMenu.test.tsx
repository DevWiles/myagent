/**
 * 命令菜单的渲染测试——这是这个项目第一个真渲染的组件测试。
 *
 * <p>为什么值得为它破例（别的组件都靠肉眼）：这块东西的价值全在**细节**上——
 * 选中的是哪一行、用不了的那条有没有写明原因、说明挤不挤得下。
 * 这几样错了界面照样画得出来，只是变得没用，而肉眼扫一眼恰恰看不出「少了个理由」。
 */

import { describe, expect, test } from "bun:test"
import { testRender } from "@opentui/solid"
import { CommandMenu } from "./CommandMenu.tsx"
import { listCommands, type CommandContext } from "../commands.ts"

/** 后端什么都支持，但只有没跑完的计划、没有存下来的会话。 */
const ctx: CommandContext = {
  features: ["plan", "resume_plan", "session", "sessions"],
  hasContinuable: false,
  hasResumablePlan: true,
}

async function frameOf(selected: number, columns = 80): Promise<string> {
  const entries = listCommands(ctx)
  const { renderOnce, captureCharFrame } = await testRender(
    () => <CommandMenu entries={entries} selected={selected} columns={columns} />,
    { width: columns, height: 14 },
  )
  await renderOnce()
  return captureCharFrame()
}

describe("命令菜单", () => {
  test("每条命令都画出来，带说明", async () => {
    const frame = await frameOf(0)
    for (const name of ["/help", "/continue", "/resume", "/resume-plan", "/compact", "/clear"]) {
      expect(frame).toContain(name)
    }
    expect(frame).toContain("从会话历史里挑一段接着聊")
  })

  test("选中的那一行有指针，别的行没有", async () => {
    const frame = await frameOf(1)
    const lines = frame.split("\n").filter((l) => l.includes("/"))
    const marked = lines.filter((l) => l.includes("❯"))
    expect(marked).toHaveLength(1)
    expect(marked[0]).toContain("/continue")
  })

  test("用不了的命令写明原因，不是悄悄消失", async () => {
    const frame = await frameOf(0)
    expect(frame).toContain("/continue")
    expect(frame).toContain("还没有存下来的会话")
  })

  test("用不了的行只说原因，不把说明和原因套成两层括号", async () => {
    const frame = await frameOf(0, 120)
    // 这个 fixture 里没有存档，所以 /continue 是灰的；/resume-plan 反倒是可用的
    const row = frame.split("\n").find((l) => l.includes("/continue"))!
    expect(row).toContain("⊘")
    expect(row).toContain("还没有存下来的会话")
    expect(row).not.toContain("不用挑")
  })

  test("名字和说明之间一定有空格——最长那条曾经正好顶满列宽，挤成一团", async () => {
    const frame = await frameOf(0, 120)
    for (const name of ["/resume-plan", "/compact", "/help", "/clear"]) {
      const row = frame.split("\n").find((l) => l.includes(name))!
      const after = row.slice(row.indexOf(name) + name.length)
      expect(after.startsWith("  ")).toBe(true)
    }
  })

  test("窄屏下说明被截断，但一行都不许折行——折行会顶得输入框位置乱跳", async () => {
    const frame = await frameOf(0, 46)
    const rows = frame.split("\n").filter((l) => l.trim())

    // 每条命令都得在**同一行**上完整出现。折行的表现是 /resume-plan 被劈成
    // 「/resume-」和「plan」两行，而按列数断言是抓不到的——折行之后每行反而更短了
    for (const name of ["/help", "/continue", "/resume", "/resume-plan", "/memory", "/clear"]) {
      expect(rows.filter((l) => l.includes(name)).length).toBeGreaterThan(0)
    }
    // 八条命令 + 一行键位提示 = 九行，多出来的行就是折行折出来的
    expect(rows).toHaveLength(9)
    expect(frame).toContain("…")
  })

  test("没有候选时一个字都不画", async () => {
    const { renderOnce, captureCharFrame } = await testRender(
      () => <CommandMenu entries={[]} selected={0} columns={80} />,
      { width: 80, height: 6 },
    )
    await renderOnce()
    expect(captureCharFrame().trim()).toBe("")
  })
})
