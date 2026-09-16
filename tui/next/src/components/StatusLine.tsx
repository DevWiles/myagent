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
import { formatTokens } from "../../../src/format.ts"
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
  tokens?: number
  contextLimit?: number
}

const SEPARATOR = "  │  "

export function StatusLine(props: StatusLineProps) {
  const theme = useTheme()

  const usage = createMemo(() => {
    if (!props.contextLimit || !props.tokens) return ""
    const percent = Math.round((props.tokens / props.contextLimit) * 100)
    return `  ${formatTokens(props.tokens)} (${percent}%)`
  })

  // 路径能占的列数 = 总宽 - 左右内边距 - 各段固定内容。
  // 每一项都用 width() 量，不能用 .length：模型名常含中文，一个字占两列。
  const workspace = createMemo(() => {
    const model = `[${props.model}]`
    const branch = props.branch ? ` git:(${props.branch})` : ""
    const budget =
      props.columns - 2 - width(model) - width(SEPARATOR) - width(branch) - width(usage()) - 2
    return truncateStart(props.workspace, budget)
  })

  return (
    <box flexDirection="row" flexShrink={0} paddingLeft={1} paddingRight={1}>
      <text fg={props.busy ? theme.spinner : theme.user}>{props.busy ? "● " : ""}</text>
      <text fg={theme.user}>[{props.model}]</text>
      <text fg={theme.inactive}>{SEPARATOR}</text>
      <text fg={theme.subtle}>{workspace()}</text>
      {props.branch ? <text fg={theme.thought}> git:({props.branch})</text> : null}
      {usage() ? <text fg={theme.inactive}>{usage()}</text> : null}
    </box>
  )
}
