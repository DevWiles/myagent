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

import {
  StyledText,
  TextAttributes,
  TextRenderable,
  type Renderable,
  type RenderNodeContext,
} from "@opentui/core"
import { createMemo, createSignal, For, Match, onCleanup, Show, Switch, untrack } from "solid-js"
import { summarizeTools } from "../format.ts"
import { isListItem, splitBlocks } from "../markdown-blocks.ts"
import type { MemoryRow } from "../protocol.ts"
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
      <Match when={props.entry.kind === "compacting" ? props.entry : null}>
        {(entry) => (
          <CompactingEntry
            phase={entry().phase}
            done={entry().done}
            total={entry().total}
            percent={entry().percent}
          />
        )}
      </Match>
      <Match when={props.entry.kind === "compacted" ? props.entry : null}>
        {(entry) => <CompactedEntry before={entry().before} after={entry().after} summarized={entry().summarized} />}
      </Match>
      <Match when={props.entry.kind === "memory" ? props.entry : null}>
        {(entry) => <MemoryEntryView rows={entry().rows} />}
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

/** 无序列表的符号。OpenTUI 把它硬编码成 ASCII 的 `-`，看起来像没渲染。 */
const BULLET = "•"

/** 默认渲染出来的列表标记长这样：`"- "`（marker 宽度 1 + 一个空格）。 */
const HYPHEN_MARKER = /^( *)- $/

/**
 * 把无序列表的 `-` 换成 `•`。
 *
 * ## 为什么要自己动手
 *
 * OpenTUI 把列表符号写死在 `Markdown.ts` 里（`token.ordered ? \`${n}.\` : "-"`），
 * 没有配置项，0.4.5 和 0.5.11 都一样。而渲染出来的 `-` 和原始 markdown
 * 长得一模一样，看着就像整个列表压根没被解析。
 *
 * 好在不用自己重写列表布局：`renderNode` 的上下文给了 `defaultRender()`，
 * 能拿到官方排好版的结果，只把标记那一个字符换掉即可。
 *
 * ## 这次为什么能成
 *
 * 之前试过一次，失败了——改完会被库的更新逻辑写回去，
 * 因为 `applyListItemMarker` 每次更新都会检查「标记文字是不是我心里的那个」，
 * 不是就重写。当时我加了个 setter 补丁去硬抗，结果流式期间列表正文整片消失。
 *
 * 现在的结构下这个问题不存在了：**每个块的 markdown 节点在创建时把 content
 * 定死，之后再也不更新**（块写完才创建）。没有更新，就没有那趟写回。
 * 所以这里只要在创建那一刻改一次就够，不需要任何补丁。
 *
 * 有序列表的 `1. ` 不匹配，保持原样。
 */
function bulletize(node: Renderable | null): Renderable | null {
  if (!node) return null

  const visit = (renderable: Renderable): void => {
    if (renderable instanceof TextRenderable && renderable.chunks.length === 1) {
      const chunk = renderable.chunks[0]!
      if (HYPHEN_MARKER.test(chunk.text)) patchMarker(renderable)
    }
    for (const child of renderable.getChildren()) visit(child)
  }

  visit(node)
  return node
}

/**
 * 在这个标记节点上接管 `content` 的 setter，把写进来的 `"- "` 改写成 `"• "`。
 *
 * 为什么不能直接赋值了事：`defaultRender()` 返回之后，库还会再走一趟
 * `applyListItemMarker` 把标记按它心里的样子写回去——直接改会被覆盖
 * （上次就是这么失败的）。接管 setter 之后不管它什么时候写、写几次，
 * 结果都是 `•`。
 *
 * 之前这个做法引起过「列表正文整片消失」，那是因为当时每个 token 都会触发
 * 一轮更新，反复重写把行内容也带塌了。现在块创建后再无更新，触发不了。
 */
function patchMarker(marker: TextRenderable): void {
  let proto: object | null = Object.getPrototypeOf(marker)
  let descriptor: PropertyDescriptor | undefined
  while (proto && !descriptor) {
    descriptor = Object.getOwnPropertyDescriptor(proto, "content")
    proto = Object.getPrototypeOf(proto)
  }
  if (!descriptor?.get || !descriptor.set) return

  const { get, set } = descriptor
  const rewrite = (value: StyledText | string): StyledText | string => {
    if (typeof value === "string" || value.chunks.length !== 1) return value
    const chunk = value.chunks[0]!
    const match = HYPHEN_MARKER.exec(chunk.text)
    if (!match) return value
    // 连同颜色和属性一起复制，只换文字。宽度不变（都占 1 列），对齐不受影响
    return new StyledText([{ ...chunk, text: `${match[1]}${BULLET} ` }])
  }

  Object.defineProperty(marker, "content", {
    configurable: true,
    get(this: TextRenderable) {
      return get.call(this)
    },
    set(this: TextRenderable, value: StyledText | string) {
      set.call(this, rewrite(value))
    },
  })
  marker.content = marker.content
}

/**
 * 块首的缩进宽度。
 *
 * 嵌套列表项被切成独立的块之后，`  - 子项` 单独交给 marked 解析时，
 * 两个空格不足以让它认成嵌套（它看不到父级列表），缩进就丢了。
 * 所以缩进改由我们自己用 marginLeft 补。
 */
function indentOf(text: string): number {
  return /^( *)/.exec(text)?.[1]?.length ?? 0
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
        {(block, index) => {
          // ⚠️ 这里必须 untrack。
          //
          // gapBefore 要读 blocks()，而 blocks() 每来一个 token 就变一次。
          // 直接写在 JSX 属性里的话，Solid 会为**每一个已完成块**都建一个 effect，
          // 于是每个字符到达时所有块的 marginTop 都被重新赋值一遍——
          // 一条长回复就是「块数 × 每秒几十次」的属性写入，
          // 每次写入都让 Yoga 重新布局整条消息。屏幕就是这么闪起来的。
          //
          // 已完成的块不会再变，它前面该留多少空行在创建那一刻就定死了，
          // 算一次就够。
          const gap = untrack(() => gapBefore(blocks().complete, index()))
          const indent = untrack(() => indentOf(block))
          return (
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
              renderNode={(token: { type: string }, ctx: RenderNodeContext) =>
                token.type === "list" ? bulletize(ctx.defaultRender()) : null
              }
              marginTop={gap}
              marginLeft={indent}
              content={block}
            />
          )
        }}
      </For>
      {/* 正在写的那一块**不画**。
          以前这里挂一个跟着 token 长的 <text>，那等于每帧都有东西在变，
          而 OpenTUI 的 <markdown> 一旦有邻居更新就会被拖着整片重画
          （实测：内容永不改变的 markdown 节点被重写 71 次，换成 text 只写 1 次）。
          只在块写完时吐一整块，markdown 的重绘次数就从「每帧」降到「每块一次」。 */}
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

/**
 * 长期记忆清单。
 *
 * <h3>被取代的条目要显示，但要弱化</h3>
 * 它们已经不再注入提示词、也 recall 不到，但仍留在盘上。隐藏掉的话，
 * 「它为什么突然改口了」就永远查不清——那正是这个清单存在的理由之一。
 * 所以画出来，但画得暗，并且直说是被谁顶掉的。
 */
function MemoryEntryView(props: { rows: MemoryRow[] }) {
  const theme = useTheme()
  const superseded = () => props.rows.filter((r) => r.supersededBy).length

  return (
    <box flexDirection="column" paddingTop={1}>
      <Show
        when={props.rows.length > 0}
        fallback={<text fg={theme.subtle}>还没有任何长期记忆。说一句「记住…」就会有。</text>}
      >
        <text fg={theme.heading} attributes={TextAttributes.BOLD}>
          长期记忆 {props.rows.length} 条
          {superseded() > 0 ? ` · ${superseded()} 条已被取代，不再注入` : ""}
        </text>
        <For each={props.rows}>
          {(row) => {
            const dim = () => row.supersededBy != null
            const meta = () =>
              [
                row.topicKey ? `主题 ${row.topicKey}` : null,
                row.scope === "global" ? "全局" : "本项目",
                row.created,
                row.turnId ? `来自 ${row.turnId}` : null,
              ]
                .filter(Boolean)
                .join(" · ")
            return (
              <box flexDirection="column" paddingTop={1}>
                <box flexDirection="row">
                  <text fg={dim() ? theme.inactive : theme.brand}>
                    {dim() ? `[已被 ${row.supersededBy} 取代] ` : `[${row.sourceLabel}] `}
                  </text>
                  <text
                    fg={dim() ? theme.inactive : theme.heading}
                    attributes={dim() ? undefined : TextAttributes.BOLD}
                  >
                    {row.name}
                  </text>
                  <text fg={theme.subtle}> — {row.description}</text>
                </box>
                <text fg={theme.inactive}>{"      " + meta()}</text>
              </box>
            )
          }}
        </For>
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

/**
 * 两种压缩看着像，实际差得很远，必须分开说（protocol.md §3.11）。
 *
 * - `summarized` 为 true：早先的对话被摘要顶替了，原文还在存档里。
 * - `summarized` 为 false：**只是把过长的工具输出裁了**。只有一两轮对话时压不出摘要
 *   （最近几轮是硬约束），但读进来的大文件照样会被削成一行。
 *
 * 后一种尤其不能一声不吭：用户看到的现象是「它刚读过的文件，怎么下一句就不记得了」，
 * 而界面上什么都没有，没人会想到是上下文在背后被削了。
 */
/** 进度条有多宽（格）。窄一点，免得在 80 列的终端里把百分比挤下去。 */
const BAR_WIDTH = 28
/** 走过的格 / 还没走的格。用两种方块而不是 `#` 和 `-`，等宽终端里对齐更稳。 */
const BAR_FILLED = "▰"
const BAR_EMPTY = "▱"
/** 转圈的四帧。转得慢一点（见 SPIN_MS）——终端里高频重绘会有肉眼可见的撕裂。 */
const SPIN_FRAMES = ["✻", "✼", "✽", "✼"]
const SPIN_MS = 240

/**
 * 正在压缩。
 *
 * <h3>为什么非有这个不可</h3>
 * 压缩是一次完整的模型调用，几十秒起步。在这之前，用户敲完 `/compact` 面对的是
 * 一块完全不动的屏幕——按键丢了、后端挂了、还是在干活，三种情况长得一模一样。
 *
 * <h3>那个百分比的内层是估的，所以要标出来</h3>
 * 外层（第几块 / 共几块）是准的。内层是「摘要已经吐了多少字 ÷ 按输入规模估的预期长度」，
 * 因为 1M 窗口下整段会话通常只切出**一块**，纯按块数报的话条会在 0% 上干等几十秒
 * 再直接跳 100%。代价是条可能提前走完或者顶在 99%，所以那一行写的是「估算」——
 * 让人以为这是精确进度，比没有进度条更糟。
 */
function CompactingEntry(props: {
  phase: "map" | "reduce"
  done: number
  total: number
  percent: number
}) {
  const theme = useTheme()
  const [frame, setFrame] = createSignal(0)
  const timer = setInterval(() => setFrame(f => (f + 1) % SPIN_FRAMES.length), SPIN_MS)
  onCleanup(() => clearInterval(timer))

  const filled = () => Math.round((Math.max(0, Math.min(100, props.percent)) / 100) * BAR_WIDTH)
  const bar = () => BAR_FILLED.repeat(filled()) + BAR_EMPTY.repeat(BAR_WIDTH - filled())
  const where = () =>
    props.phase === "reduce"
      ? "合并分段摘要"
      : props.total > 1
        ? `摘要第 ${props.done + 1}/${props.total} 块`
        : "摘要早先的对话"

  return (
    <box paddingTop={1}>
      <box flexDirection="row">
        <text fg={theme.spinner}>{SPIN_FRAMES[frame()]} </text>
        <text fg={theme.text}>正在压缩对话…</text>
      </box>
      <box flexDirection="row">
        <text fg={theme.brand}>{"  " + bar()}</text>
        <text fg={theme.subtle}>{` ${props.percent}%　${where()}（进度为估算）`}</text>
      </box>
    </box>
  )
}

function CompactedEntry(props: { before: number; after: number; summarized: boolean }) {
  const theme = useTheme()
  const what = () => (props.summarized ? "早先的对话已摘要" : "较早的工具输出已裁剪")
  return (
    <box paddingTop={1}>
      <text fg={theme.subtle}>
        — {what()} {props.before} → {props.after} tokens（原文都在存档里） —
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
