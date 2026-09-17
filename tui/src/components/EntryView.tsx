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
import { createMemo, For, Match, Show, Switch } from "solid-js"
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
 * 取出这段文本里**已经定型**的部分，没写完的最后半行丢掉。
 *
 * ## 边界为什么是「行」而不是「空行」
 *
 * 一度用空行做边界（markdown 里空行分隔块），但那个粒度太粗：
 * 一个标题带九条嵌套列表，中间一个空行都没有，于是整坨算一个块，
 * 要等全部写完才「啪」地落下来一大片。
 *
 * Notion 那种「一个 block 渲染完才出现」里的 block，其实是**每个列表项
 * 各自一个 block**，不是整张列表一个。对应到这里就是：以完整的行为单位。
 *
 * 行一写完就定型了：`## 标题` 是标题、`- 项一` 是列表项、代码行是代码行，
 * 都不会因为后面来了什么而改变身份。没写完的半行才是会骗人的那个
 * （`## 标` 不是合法标题，只能先当普通文字画出来，`##` 就露出来了）。
 *
 * ## 未闭合的代码围栏不用特殊处理
 *
 * ```` ```java ```` 开了还没收尾时，marked 会把后面已有的内容全当代码——
 * 而此刻「后面已有的内容」本来就都是代码，所以这个判断是对的。
 * 之后每来一行，代码块只是往下长，不会发生「先是代码、后来又不是」的变身。
 */
export function settledMarkdown(text: string): string {
  const lastBreak = text.lastIndexOf("\n")
  return lastBreak < 0 ? "" : text.slice(0, lastBreak + 1)
}

/**
 * 模型的回复，按 Markdown 渲染。
 *
 * 用 OpenTUI 内置的 `<markdown>`，不自己解析：它内部走 TreeSitter，
 * 连代码块的语法高亮一起给了，自己写一个解析器只会得到一个更差的版本。
 *
 * ## 流式按「行」推，不按字符推
 *
 * text_delta 是一个字一个字过来的，直接喂给渲染器有两个毛病：
 *
 * 1. **会看见 markdown 源码**。半行 `## 标` 还不是合法标题，
 *    渲染器只能先当普通文字画出来，`##` 就露在屏幕上了。
 *    （OpenTUI 的 `streaming` 选项正是这个行为：最后一块按未定稿处理。）
 * 2. **抖得厉害**。每来一个字符就重排一次版面，一秒几十次。
 *
 * 所以这里只把**已经写完的整行**推给渲染器（见 settledMarkdown）。
 * 屏幕上出现的每一行都已经是最终形态：标题就是标题、列表项就是列表项，
 * 不会先露出源码再变身。以行为单位出现，而不是逐字打字机。
 */
function AssistantEntry(props: { text: string; streaming: boolean }) {
  const theme = useTheme()

  const content = createMemo(() => (props.streaming ? settledMarkdown(props.text) : props.text))

  return (
    <box paddingTop={1}>
      {/* ⚠️ syntaxStyle 必须写在 content **前面**。
          Solid 的 reconciler 按 JSX 里的书写顺序依次调 setter，而设置 content 会
          立刻触发一次解析；syntaxStyle 还没赋值的话，那一趟解析查不到任何配色，
          整段文字统统退回默认色。它不会报错也不会崩——标题照样是标题、加粗照样
          去掉星号，**只是颜色全错**，非常难发现。
          （这个顺序依赖是 OpenTUI 自己源码注释里写明的。）

          streaming 恒为 false：我们喂进去的内容总是若干个完整的行，
          不需要它再替我们保留一个「未定稿的尾块」——那正是会露出源码的东西。 */}
      <markdown syntaxStyle={syntaxStyleFor(theme)} content={content()} streaming={false} />
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
