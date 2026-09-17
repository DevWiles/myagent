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
 * 把流式文本切成「已成块的部分」和「还在写的尾巴」。
 *
 * 边界是空行——markdown 里空行分隔块，所以前半截永远是若干个完整的块。
 */
function splitSettled(text: string): { settled: string; tail: string } {
  const cut = text.lastIndexOf("\n\n")
  if (cut < 0) return { settled: "", tail: text }
  return { settled: text.slice(0, cut + 2), tail: text.slice(cut + 2) }
}

/**
 * 模型的回复，边流边渲染 Markdown。
 *
 * ## 不闪的关键是「不重建」，不是「少更新」
 *
 * 这里绕了很久：逐字符、整块、整行三档更新粒度都试过，都会闪。
 * 后来去实测 Claude Code 才想明白——它每秒都在更新画面，一样不闪，
 * 因为**已经画出来的内容从不被重建**（实测 15 次追加、0 次改写）。
 * 粒度只改变闪的频率，改变不了「每次更新都全量重建整棵树」这件事。
 *
 * ## 结构：已定稿的交给 markdown，尾巴用纯文本补
 *
 * ```
 * <markdown>   ← 已经成块的部分。只在「又写完一个块」时才变，
 *                  一次回复也就变几次，而且重建出来的像素和上一版一样，
 *                  原生层差分后没有输出，看不见任何动静
 * <text>       ← 还在长的那一块。每个 token 都在变，但它只是一个文本节点，
 *                  没有解析、没有树重建，更新代价近乎零
 * ```
 *
 * 这是 Claude Code 那套「已定稿内容 + 宽容渲染的尾巴」在 OpenTUI 上的对应物。
 * 差别在于 Claude Code 的尾巴由一个宽容的 markdown 解析器渲染（半截语法也能画），
 * 我们的尾巴就是纯文本——所以**正在写的那一两行会露出 `##`、`**` 标记**，
 * 写完成块之后立刻变成正体。
 *
 * ## 为什么尾巴要自己补，不能全交给 markdown
 *
 * OpenTUI 内部确实有按块复用的机制（`getStableBlockCount()` 就是干这个的，
 * 注意它只在 `internalBlockMode: "top-level"` 下才生效，默认的 "coalesced"
 * 会直接返回 0）。但它对**未定稿尾块的处理是整个不画**，而不是宽容地画出来。
 * 实测 1500 字的长回复：屏幕空了 11 秒，然后一次性砸出 64 行——不闪，但也没有反馈。
 * 所以尾巴得我们自己用纯文本补上。
 *
 * ## streaming 必须传 true，不能写死 false
 *
 * 已定稿的那段虽然全是完整的块，`streaming` 仍要跟着流走。
 * 写死 false 时 `createInitialStyledText()` 直接返回 undefined，走的是另一条
 * 渲染路径，实测流式期间这段**完全不出现**，要等整轮结束才一次性画出来。
 */
function AssistantEntry(props: { text: string; streaming: boolean }) {
  const theme = useTheme()
  const parts = createMemo(() =>
    props.streaming ? splitSettled(props.text) : { settled: props.text, tail: "" },
  )

  return (
    <box flexDirection="column" paddingTop={1}>
      {/* 这两个节点都**无条件存在**，内容为空时自然什么都不画。
          不要用 <Show> 去包：实测包上之后，内部对 parts() 的绑定就不再跟着更新了，
          表现是流式期间正文区一片空白，直到整轮结束才一次性出现。 */}

      {/* ⚠️ 所有配置项都要写在 content **前面**。
          Solid 的 reconciler 按 JSX 里的书写顺序依次调 setter，而设置 content 会
          立刻触发一次解析；配置还没赋值的话，那一趟解析用的就是默认值。
          syntaxStyle 没赋值时整段文字会退回默认色——不报错也不崩，
          标题照样是标题、加粗照样去掉星号，**只是颜色全错**，非常难发现。
          （这个顺序依赖是 OpenTUI 自己源码注释里写明的。） */}
      <markdown
        syntaxStyle={syntaxStyleFor(theme)}
        streaming={props.streaming}
        content={parts().settled}
      />
      <text fg={theme.text}>{parts().tail}</text>
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
