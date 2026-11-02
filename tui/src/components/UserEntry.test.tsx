/**
 * 用户发言整行铺灰底（对齐 Claude Code）。底色是这条唯一的特征，
 * 所以要钉住两件事：铺满整行而不是只铺字底下；上面那行空隙不能跟着变灰。
 */

import { expect, test } from "bun:test"
import { RGBA } from "@opentui/core"
import { testRender } from "@opentui/solid"
import { EntryView } from "./EntryView.tsx"
import { dark } from "../theme.ts"

test("用户发言整行灰底，上方空行不染色", async () => {
  const width = 40
  const { renderOnce, captureSpans } = await testRender(
    () => (
      <box flexDirection="column" width={width}>
        <EntryView entry={{ kind: "user", key: "u1", text: "好的" }} />
      </box>
    ),
    { width, height: 3 },
  )
  await renderOnce()
  const lines = captureSpans().lines
  const gray = RGBA.fromHex(dark.userBg)
  const bgOf = (row: number) => lines[row].spans.flatMap((s) => Array(s.width).fill(s.bg))
  const isGray = (c: RGBA) => c.equals(gray)

  expect(bgOf(0).some(isGray)).toBe(false)
  expect(bgOf(1)).toHaveLength(width)
  expect(bgOf(1).every(isGray)).toBe(true)
})
