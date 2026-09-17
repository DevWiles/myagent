/**
 * 对话流里的一条记录。
 *
 * Entry 的种类由 `tui/src/store.ts` 定义（和 Ink 版共用同一套），
 * 这里只负责把每一种画成什么样子。
 *
 * 这一步先把七种 entry 都画出来但都画得朴素——
 * 工具调用的展开/折叠、Markdown 渲染、计划图都是后面单独的步骤。
 * 宁可先全部可见且难看，也不要漏掉某一种让人以为事件丢了。
 */

import { TextAttributes } from "@opentui/core"
import { For, Match, Show, Switch } from "solid-js"
import { summarizeTools } from "../format.ts"
import type { Entry, ToolCall } from "../store.ts"
import { syntaxStyleFor, useTheme } from "../theme.ts"

export type EntryViewProps = {
  entry: Entry
  /**
   * 这条是不是正在流式接收。
   *
   * 由上层判断而不是组件自己猜：判据是「它是最后一条 + 会话还忙着」，
   * 这两个条件组件都看不到。
   */
  streaming?: boolean
}

export function EntryView(props: EntryViewProps) {
  return (
    <Switch>
      <Match when={props.entry.kind === "user" ? props.entry : null}>
        {(entry) => <UserEntry text={entry().text} />}
      </Match>
      <Match when={props.entry.kind === "assistant" ? props.entry : null}>
        {(entry) => <AssistantEntry text={entry().text} streaming={props.streaming ?? false} />}
      </Match>
      <Match when={props.entry.kind === "thought" ? props.entry : null}>
        {(entry) => <ThoughtEntry text={entry().text} />}
      </Match>
      <Match when={props.entry.kind === "tools" ? props.entry : null}>
        {(entry) => <ToolsEntry calls={entry().calls} />}
      </Match>
      <Match when={props.entry.kind === "notice" ? props.entry : null}>
        {(entry) => <NoticeEntry level={entry().level} text={entry().text} />}
      </Match>
      <Match when={props.entry.kind === "compacted" ? props.entry : null}>
        {(entry) => <CompactedEntry before={entry().before} after={entry().after} />}
      </Match>
      <Match when={props.entry.kind === "plan" ? props.entry : null}>
        {(entry) => <PlanEntry goal={entry().plan.goal} status={entry().plan.status} />}
      </Match>
    </Switch>
  )
}

function UserEntry(props: { text: string }) {
  const theme = useTheme()
  return (
    <box flexDirection="row" paddingTop={1}>
      <text fg={theme.brand}>{"› "}</text>
      <text fg={theme.user}>{props.text}</text>
    </box>
  )
}

/**
 * 模型的回复。
 *
 * **流式期间是纯文本，收完再整体渲染成 Markdown。**
 *
 * 为什么不边流边渲染：`<markdown>` 每次 content 变化都要重新解析整段文本、
 * 重建整棵渲染树。不管把更新粒度调到多粗（试过逐字符、整块、整行三档），
 * 只要还在流式期间反复重建，屏幕就会闪——粒度只改变闪的频率，不改变它闪。
 *
 * 所以干脆分成两个阶段：
 * - 流式中：一个 `<text>`，内容只增不改，没有解析也没有树重建，滚动平顺
 * - 流完了：换成 `<markdown>`，一次成型
 *
 * 代价是流式期间能看见 `##`、`**` 这些源码标记，结束时有一次「变身」。
 * 这是刻意换来的：源码标记只是不好看，闪烁是没法看。
 */
function AssistantEntry(props: { text: string; streaming: boolean }) {
  const theme = useTheme()

  return (
    <box paddingTop={1}>
      <Show
        when={!props.streaming}
        fallback={<text fg={theme.text}>{props.text}</text>}
      >
        {/* ⚠️ syntaxStyle 必须写在 content **前面**。
            Solid 的 reconciler 按 JSX 里的书写顺序依次调 setter，而设置 content 会
            立刻触发一次解析；syntaxStyle 还没赋值的话，那一趟解析查不到任何配色，
            整段文字统统退回默认色。它不会报错也不会崩——标题照样是标题、加粗照样
            去掉星号，**只是颜色全错**，非常难发现。
            （这个顺序依赖是 OpenTUI 自己源码注释里写明的。） */}
        <markdown syntaxStyle={syntaxStyleFor(theme)} content={props.text} streaming={false} />
      </Show>
    </box>
  )
}

function ThoughtEntry(props: { text: string }) {
  const theme = useTheme()
  return (
    <box paddingTop={1}>
      <text fg={theme.thought} attributes={TextAttributes.ITALIC}>
        {props.text}
      </text>
    </box>
  )
}

/**
 * 一组工具调用，**只画一行摘要**：「读取 3 个文件 · 执行 1 条命令」。
 *
 * 为什么不列每一条：工具名和参数是 agent 的内部动作，不是用户要读的东西。
 * 一轮下来七八条 `✓ read_file {"path": ...}` 会把真正有信息量的模型回复挤到屏幕外，
 * 用户得往回滚才能看到结论——代价远大于收益。
 *
 * 但有两件事不能藏，否则这行摘要会骗人：
 * - **还在跑**：末尾加省略号，不然一条慢命令看起来像已经跑完了
 * - **有失败**：单独标出来。失败会改变后面所有事情的含义，
 *   只说「执行 1 条命令」而不说它失败了，用户会以为事情办成了
 *
 * 明细留给将来的 Ctrl-O 展开。
 */
function ToolsEntry(props: { calls: ToolCall[] }) {
  const theme = useTheme()
  const running = () => props.calls.some((call) => call.running)
  const failed = () => props.calls.filter((call) => call.ok === false).length

  return (
    <box flexDirection="row" paddingTop={1}>
      <text fg={theme.subtle}>
        {summarizeTools(props.calls)}
        {running() ? "…" : ""}
      </text>
      <Show when={failed() > 0}>
        <text fg={theme.error}> · {failed()} 个失败</text>
      </Show>
    </box>
  )
}

function NoticeEntry(props: { level: "info" | "warn" | "error"; text: string }) {
  const theme = useTheme()
  const color = () =>
    props.level === "error" ? theme.error : props.level === "warn" ? theme.warning : theme.subtle
  return (
    <box paddingTop={1}>
      <text fg={color()}>{props.text}</text>
    </box>
  )
}

function CompactedEntry(props: { before: number; after: number }) {
  const theme = useTheme()
  return (
    <box paddingTop={1}>
      <text fg={theme.subtle}>
        — 上下文已压缩 {props.before} → {props.after} tokens —
      </text>
    </box>
  )
}

function PlanEntry(props: { goal: string; status: string }) {
  const theme = useTheme()
  return (
    <box flexDirection="column" paddingTop={1} border={["left"]} borderColor={theme.border} paddingLeft={1}>
      <text fg={theme.heading} attributes={TextAttributes.BOLD}>
        计划：{props.goal}
      </text>
      <text fg={theme.subtle}>状态 {props.status}（计划面板是后面的步骤）</text>
    </box>
  )
}
