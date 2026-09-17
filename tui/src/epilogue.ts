/**
 * 退出时留在终端上的那几行。
 *
 * 为什么需要它：界面活在备用屏里，退出后整段对话随屏幕一起消失（和 vim 一样，
 * 这是刻意的选择）。代价是用户回到 shell 时手上什么都没有——刚才那半小时
 * 烧了多少 token、跑了多少工具、记录在哪儿，全凭记忆。这几行是唯一的回执。
 *
 * 纯函数放在这里而不是 main.tsx：那边全是 createCliRenderer 这种碰真终端的东西，
 * 测不了；这里 `bun test` 直接能跑。
 */

import { formatDuration, formatTokens } from "./format.ts"
import { tildify } from "./path.ts"
import type { SessionState } from "./store.ts"

/**
 * 算出收尾信息。**没有值得说的东西就返回空数组**，调用方什么都不打。
 *
 * @param elapsedMs 整场会话的时长。注意不能用 `state.startedAt`——那是**每一轮**的
 *                  起点（busy 时设、闲时清 null），算不出整场。
 */
export function buildEpilogue(state: SessionState, elapsedMs: number): string[] {
  const rounds = state.entries.filter((entry) => entry.kind === "user").length

  // 一句话都没说过就退出（起错目录、看一眼工具列表就走）——这时打什么都是打扰。
  // 判据用「有没有对话」而不是「有没有 entries」：连不上后端时会留一条 notice，
  // 那条不该把一场没发生过的会话说成发生过。
  if (rounds === 0) return []

  const toolCalls = state.entries.reduce(
    (sum, entry) => (entry.kind === "tools" ? sum + entry.calls.length : sum),
    0,
  )

  // 分隔符沿用 summarizeTools 的 ' · '，用户在界面里已经看惯了
  const parts = [`${rounds} 轮对话`]
  if (toolCalls > 0) parts.push(`${toolCalls} 次工具调用`)
  if (state.tokens > 0) parts.push(`${formatTokens(state.tokens)} tokens`)
  parts.push(formatDuration(elapsedMs))

  const lines = [`myagent 会话结束 · ${parts.join(" · ")}`]

  // 审计日志的位置。这是唯一一条「退出之后还能继续查」的线索，
  // 所以哪怕只有一行也值得占一行。
  const audit = state.ready?.auditLog
  if (audit) lines.push(`审计日志：${tildify(audit)}`)

  return lines
}
