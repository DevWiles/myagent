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
import { isListItem, splitBlocks } from "../markdown-blocks.ts"
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
 * 第 `index` 块前面要不要留一个空行。
 *
 * 规则只有一条：**上下都是列表项就贴着，否则留一行**。
 * 列表项之间塞空行会把紧凑列表撑成松散列表，观感差很多。
 *
 * `text` 是给「正在写的那块」用的——它不在 complete 数组里，得单独把内容传进来。
 */
function gapBefore(complete: string[], index: number, text?: string): number {
  if (index === 0) return 0
  const previous = complete[index - 1]
  const self = text ?? complete[index]
  if (!previous || !self) return 0
  return isListItem(previous) && isListItem(self) ? 0 : 1
}

/**
 * 模型的回复，边流边渲染 Markdown。
 *
 * ## 不是「每来一个 token 就把全文重新解析一遍」
 *
 * 最省事的写法是 `buffer += chunk; render(parse(buffer))`。它能跑，但等于把
 * 已经解析过的内容反复重算——第 1000 个 token 时在 parse 1000 个字。
 * 在 TUI 里这还不只是慢：重新解析意味着**重建整棵渲染树**，
 * 屏幕上已经画好的内容被整片重画，这就是闪烁的来源。
 *
 * 所以这里按 **block 粒度** 渲染：
 *
 * ```
 * ┌─────────┐ ┌──────────┐ ┌───────────┐
 * │ 标题 ✓  │ │ 段落 ✓   │ │ 代码块 ⏳ │ ← 只有它在动
 * └─────────┘ └──────────┘ └───────────┘
 *   不再解析     不再解析      持续更新
 * ```
 *
 * 每个写完的块**各自一个 `<markdown>` 节点**，content 在创建时定下来之后
 * 再也不变，于是 Solid 的 `<For>` 会原样复用它、根本不会去碰。
 * 正在写的那一块用纯文本顶着，等它写完再固化成一个新的 markdown 节点。
 *
 * ## 几个绕了很久才明白的点
 *
 * - **`<For>` 靠值相等复用**：已完成的块必须逐字不变（`splitBlocks` 保证了
 *   这一点，有测试盯着），否则 For 会认为是新块而重建，前功尽弃。
 * - **不要用 `<Show>` 包这些节点**：实测包上之后内部绑定不再跟着更新，
 *   表现是流式期间正文区一片空白，直到整轮结束才一次性出现。
 * - **正在写的那块只能用纯文本**。OpenTUI 的 `streaming` 模式对未定稿尾块
 *   是「整个不画」而不是宽容渲染（实测 1500 字长回复：屏幕空 11 秒然后砸出
 *   64 行）。所以会看到当前这一两行露出 `##`、`**` 标记，写完就变正体——
 *   Claude Code 这里用的是一个宽容的流式解析器，我们暂时没有对应物。
 */
function AssistantEntry(props: { text: string; streaming: boolean }) {
  const theme = useTheme()
  const blocks = createMemo(() =>
    props.streaming ? splitBlocks(props.text) : { complete: splitBlocks(props.text + "\n\n").complete, current: "" },
  )

  return (
    <box flexDirection="column" paddingTop={1}>
      <For each={blocks().complete}>
        {(block, index) => (
          // ⚠️ syntaxStyle 必须写在 content 前面：Solid 按书写顺序调 setter，
          // 而设置 content 会立刻触发解析；syntaxStyle 还没赋值的话那一趟解析
          // 查不到任何配色，整段退回默认色——不报错不崩，只是颜色全错。
          // （这个顺序依赖是 OpenTUI 源码注释里写明的。）
          //
          // marginTop 得自己加：块间距本来是 markdown 渲染器在一整段里算的，
          // 拆成一块一个节点之后没人管了，不补的话标题、段落会全挤在一起。
          // 但**连续的列表项之间不能加**——列表本来就是紧挨着的，
          // 每项之间塞一个空行会把一张紧凑列表撑成松散列表。
          <markdown
            syntaxStyle={syntaxStyleFor(theme)}
            marginTop={gapBefore(blocks().complete, index())}
            content={block}
          />
        )}
      </For>
      <text fg={theme.text} marginTop={gapBefore(blocks().complete, blocks().complete.length, blocks().current)}>
        {blocks().current}
      </text>
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
