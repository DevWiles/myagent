/**
 * 最底下那行状态栏：模型、工作区、分支、上下文用量。
 *
 * 只读、不可交互，存在的意义是**回答「我现在在哪、用的什么、还剩多少上下文」**——
 * 前两个问题在 agent 改了你的文件之后会变得非常重要，第三个决定你还能聊几轮。
 *
 * 它必须**永远只占一行**。路径是不可控长度，放任它折行会把整个界面顶起来，
 * 所以这里自己算预算、自己截断，不指望终端帮忙。
 */

import { createMemo } from "solid-js"
import { formatTokens } from "../format.ts"
import { truncateStart, width } from "../path.ts"
import { useTheme } from "../theme.ts"

export type StatusLineProps = {
  model: string
  /** 已经 tildify 过的路径 */
  workspace: string
  branch?: string
  /** 终端列数，用来算路径能占多宽 */
  columns: number
  busy?: boolean
  /**
   * 连接不正常时顶在最左边的那句话（「重连中（第 3 次）」）。
   * 空就是连着，什么都不画。它比 busy 优先：断着的时候「正在跑」没有意义。
   */
  link?: string
  tokens?: number
  /** 累计真正发出去的提示词。 */
  sentTokens?: number
  contextLimit?: number
}

const SEPARATOR = "  │  "

export function StatusLine(props: StatusLineProps) {
  const theme = useTheme()

  const percent = createMemo(() =>
    !props.contextLimit || !props.tokens
      ? 0
      : Math.round((props.tokens / props.contextLimit) * 100),
  )

  /**
   * 窗口占用 + 累计发出。
   *
   * 两个数都要，因为它们不是一回事：前者是「这一份窗口有多大」的瞬时快照，
   * 后者是把窗口重发了几十遍之后的总和。只显示前者，用户会拿它当花销，
   * 而真实数字可能是它的十倍；只显示后者，又看不出离上限还有多远。
   */
  const usage = createMemo(() => {
    const parts: string[] = []
    if (props.contextLimit && props.tokens) {
      parts.push(`${formatTokens(props.tokens)} (${percent()}%)`)
    }
    if (props.sentTokens) parts.push(`已发 ${formatTokens(props.sentTokens)}`)
    return parts.length === 0 ? "" : `  ${parts.join("  ·  ")}`
  })

  /** 过半转黄、八成转红。和终端版 ContextBar 是同一组阈值，两边不一致会很怪。 */
  const usageColor = createMemo(() => {
    if (percent() >= 80) return theme.error
    if (percent() >= 50) return theme.warning
    return theme.inactive
  })

  // 路径能占的列数 = 总宽 - 左右内边距 - 各段固定内容。
  // 每一项都用 width() 量，不能用 .length：模型名常含中文，一个字占两列。
  const workspace = createMemo(() => {
    const model = `[${props.model}]`
    const branch = props.branch ? ` git:(${props.branch})` : ""
    const budget =
      props.columns - 2 - width(model) - width(SEPARATOR) - width(branch) - width(usage()) -
      width(linkText()) - 2
    return truncateStart(props.workspace, budget)
  })

  const linkText = createMemo(() => (props.link ? `⟳ ${props.link}${SEPARATOR}` : ""))

  return (
    <box flexDirection="row" flexShrink={0} paddingLeft={1} paddingRight={1}>
      {linkText() ? <text fg={theme.warning}>{linkText()}</text> : null}
      <text fg={props.busy ? theme.spinner : theme.user}>{props.busy && !props.link ? "● " : ""}</text>
      <text fg={theme.user}>[{props.model}]</text>
      <text fg={theme.inactive}>{SEPARATOR}</text>
      <text fg={theme.subtle}>{workspace()}</text>
      {props.branch ? <text fg={theme.thought}> git:({props.branch})</text> : null}
      {usage() ? <text fg={usageColor()}>{usage()}</text> : null}
    </box>
  )
}
