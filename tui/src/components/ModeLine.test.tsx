/**
 * 模式行是 `/plan`、`/team` 删掉之后唯一的发现入口，所以它的内容有硬要求：
 * **三样东西缺一不可**——当前模式、这个模式干什么、怎么切。
 * 少了最后一样，新用户永远不会知道 Shift+Tab 能干什么。
 */

import { describe, expect, test } from "bun:test"
import { testRender } from "@opentui/solid"
import { ModeLine } from "./ModeLine.tsx"
import type { Mode } from "../store.ts"

async function frameOf(mode: Mode, columns = 80): Promise<string> {
  const { renderOnce, captureCharFrame } = await testRender(
    () => <ModeLine mode={mode} columns={columns} />,
    { width: columns, height: 3 },
  )
  await renderOnce()
  return captureCharFrame()
}

describe("模式行", () => {
  test("normal 下也一直在——它是「还有别的模式」唯一的告示", async () => {
    const frame = await frameOf("normal")

    expect(frame).toContain("normal")
    expect(frame).toContain("shift+tab")
  })

  test("每个模式都写清自己是干什么的，不只是一个名字", async () => {
    expect(await frameOf("plan")).toContain("先拆计划")
    expect(await frameOf("team")).toContain("施工")
  })

  test("只占一行——它贴在状态栏下面，折行会把输入框顶上去", async () => {
    for (const mode of ["normal", "plan", "team"] as const) {
      const rows = (await frameOf(mode, 60)).split("\n").filter((l) => l.trim())
      expect(rows).toHaveLength(1)
    }
  })

  test("切换提示在每个模式下都在，不是只在 normal 下教一次", async () => {
    for (const mode of ["normal", "plan", "team"] as const) {
      expect(await frameOf(mode)).toContain("shift+tab")
    }
  })
})
