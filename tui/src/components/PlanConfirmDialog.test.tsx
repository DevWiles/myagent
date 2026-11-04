/**
 * plan 模式确认框：两种问法各画什么、计划正文露多少、补话那一步长什么样。
 * 后端此刻正卡在等回答上，框里少一个选项或者计划没画出来，用户就只能瞎按。
 */

import { describe, expect, test } from "bun:test"
import { testRender } from "@opentui/solid"
import { PlanConfirmDialog, planPreview, planPreviewLines } from "./PlanConfirmDialog.tsx"
import type { PlanConfirm } from "../store.ts"

async function frameOf(confirm: PlanConfirm, rows = 40): Promise<string> {
  const { flush, captureCharFrame } = await testRender(
    () => <PlanConfirmDialog confirm={confirm} columns={80} rows={rows} />,
    { width: 80, height: rows },
  )
  // flush 而不是 renderOnce：计划正文走 <markdown>，解析是异步的，只画一帧看不到字
  await flush()
  return captureCharFrame()
}

const exit = (patch: Partial<PlanConfirm> = {}): PlanConfirm => ({
  id: 1,
  ask: "exit",
  plan: "## 改动\n1. 改 store.ts\n2. 跑 bun test",
  choice: 0,
  feedback: null,
  ...patch,
})

describe("plan 模式确认框", () => {
  test("交计划：计划正文画出来，三个选项都在，光标在批准上", async () => {
    const frame = await frameOf(exit())
    expect(frame).toContain("批准这份计划")
    expect(frame).toContain("改 store.ts")
    expect(frame).toContain("跑 bun test")
    expect(frame).toContain("批准，照计划动手")
    expect(frame).toContain("不批准，我说说要改什么")
    const marked = frame.split("\n").filter((l) => l.includes("❯"))
    expect(marked).toHaveLength(1)
    expect(marked[0]).toContain("批准，照计划动手")
  })

  test("提议进 plan 模式：是非题，没有补话那一项", async () => {
    const frame = await frameOf({ id: 2, ask: "enter", plan: "", choice: 1, feedback: null })
    expect(frame).toContain("先进 plan 模式")
    expect(frame).toContain("不用，直接做")
    expect(frame).not.toContain("我说说要改什么")
  })

  test("补话那一步：选项换成输入行，敲的字看得见", async () => {
    const frame = await frameOf(exit({ feedback: "别动数据库那层" }))
    expect(frame).toContain("要改的是")
    expect(frame).toContain("别动数据库那层")
    expect(frame).not.toContain("批准，照计划动手")
  })

  test("长计划只露前面一截，并说清往上滚有全文", async () => {
    const long = Array.from({ length: 50 }, (_, i) => `第 ${i + 1} 步`).join("\n")
    const frame = await frameOf(exit({ plan: long }), 40)
    expect(frame).toContain("第 1 步")
    expect(frame).not.toContain("第 50 步")
    expect(frame).toContain("往上滚看完整计划")
  })
})

describe("计划预览（纯函数）", () => {
  test("短计划原样给，不报藏了几行", () => {
    expect(planPreview("a\nb", 5)).toEqual({ text: "a\nb", hidden: 0 })
  })

  test("长计划截到上限，报出剩下几行", () => {
    expect(planPreview("1\n2\n3\n4\n5", 3)).toEqual({ text: "1\n2\n3", hidden: 2 })
  })

  test("预览行数随屏幕高度走，两头夹住", () => {
    expect(planPreviewLines(5)).toBe(4)
    expect(planPreviewLines(40)).toBe(12)
    expect(planPreviewLines(200)).toBe(20)
  })
})
