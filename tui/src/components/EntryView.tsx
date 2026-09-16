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

/** 无序列表的符号。OpenTUI 硬编码成 ASCII 的 `-`，看起来像没渲染。 */
const BULLET = "•"

/** 默认渲染出来的列表标记长这样：`"- "`（marker 宽度 1 + 一个空格）。 */
const HYPHEN_MARKER = /^( *)- $/

/** 已经装过改写钩子的 marker，别重复装。 */
const PATCHED = Symbol("bullet-patched")

/**
 * 把一次 content 赋值里的 `"- "` 改写成 `"• "`，其余原样放行。
 *
 * 颜色和属性从原 chunk 复制，只换文字；宽度不变（都占 1 列），缩进对齐不受影响。
 */
function rewriteMarker(value: StyledText | string): StyledText | string {
  if (typeof value === "string") return value
  if (value.chunks.length !== 1) return value
  const chunk = value.chunks[0]!
  const match = HYPHEN_MARKER.exec(chunk.text)
  if (!match) return value
  return new StyledText([{ ...chunk, text: `${match[1]}${BULLET} ` }])
}

/**
 * 把列表符号从 `-` 换成 `•`。
 *
 * ## 为什么要这么绕
 *
 * OpenTUI 把无序列表的符号写死在 `Markdown.ts` 里（`token.ordered ? \`${n}.\` : "-"`），
 * 没有任何配置项。而渲染出来的 `-` 和原始 markdown 长得一模一样，
 * 看着就像整个列表压根没被解析。
 *
 * 好在不用自己重写列表：`renderNode` 的上下文给了 `defaultRender()`，
 * 能拿到官方排好版的结果（标记宽度、嵌套缩进、多行项的悬挂缩进都算好了）。
 *
 * ## 为什么是装 setter 而不是直接改
 *
 * 直接改**改不住**。实测在一次流式回复里 renderNode 被调 6 次、替换命中 9 次，
 * 屏幕上仍然是 `-`：库在 renderNode 返回之后还有一趟更新，
 * `applyListItemMarker` 看到标记文字不等于它心里的 `"- "` 就会写回去。
 *
 * 所以改成在 marker 实例上接管 `content` 的 setter：之后不管库什么时候、
 * 写多少次 `"- "`，都会被就地改写成 `"• "`。自愈，不用关心它的更新时序。
 *
 * ## 边界
 *
 * - 递归遍历，因为嵌套列表是在默认渲染内部展开的，renderNode 不会为它们再回调
 * - 只认完全匹配 `"- "` 的单 chunk 文本，不会误伤正文——正文走 CodeRenderable，
 *   压根不是 TextRenderable
 * - 有序列表的 `1. ` 不匹配，保持原样
 *
 * 这段代码依赖 OpenTUI 的内部结构（列表行的第一个子节点是 marker、content 是访问器），
 * 升级 OpenTUI 时如果列表符号变了样，第一个该怀疑的就是这里。
 */
function bulletizeList(node: Renderable | null): Renderable | null {
  if (!node) return null

  const visit = (renderable: Renderable): void => {
    if (renderable instanceof TextRenderable && !(PATCHED in renderable)) {
      const chunk = renderable.chunks.length === 1 ? renderable.chunks[0] : undefined
      if (chunk && HYPHEN_MARKER.test(chunk.text)) {
        patchMarkerContent(renderable)
      }
    }
    for (const child of renderable.getChildren()) visit(child)
  }

  visit(node)
  return node
}

/** 在这个 TextRenderable 上接管 content 的 setter，并立刻把当前值改写一遍。 */
function patchMarkerContent(marker: TextRenderable): void {
  // content 是定义在原型上的访问器，得沿原型链找到它才能复用原来的读写逻辑
  let proto: object | null = Object.getPrototypeOf(marker)
  let descriptor: PropertyDescriptor | undefined
  while (proto && !descriptor) {
    descriptor = Object.getOwnPropertyDescriptor(proto, "content")
    proto = Object.getPrototypeOf(proto)
  }
  if (!descriptor?.get || !descriptor.set) return

  const { get, set } = descriptor
  Object.defineProperty(marker, "content", {
    configurable: true,
    get(this: TextRenderable) {
      return get.call(this)
    },
    set(this: TextRenderable, value: StyledText | string) {
      set.call(this, rewriteMarker(value))
    },
  })
  Object.defineProperty(marker, PATCHED, { value: true })

  // 装完先把当前这一份改掉，别等下一次赋值
  marker.content = marker.content
}

/**
 * 模型的回复，按 Markdown 渲染。
 *
 * 用 OpenTUI 内置的 `<markdown>`，不自己解析：它内部走 TreeSitter，
 * 连代码块的语法高亮一起给了，自己写一个解析器只会得到一个更差的版本。
 *
 * `streaming` 这个 prop 是关键。text_delta 是一个字一个字推过来的，
 * 中途的文本必然有半截语法——`**加粗` 还没收尾、代码围栏只开了没关。
 * 打开 streaming 时它会把**最后一个块**当作未定稿处理，不急着解析成型；
 * 流完再置回 false 定稿。不这么做的话，每来一个字符末尾就会在
 * 「普通文本」和「加粗/代码块」之间反复横跳，看起来像屏幕在抽搐。
 */
function AssistantEntry(props: { text: string; streaming: boolean }) {
  const theme = useTheme()
  return (
    <box paddingTop={1}>
      {/* ⚠️ syntaxStyle 必须写在 content **前面**。
          Solid 的 reconciler 按 JSX 里的书写顺序依次调 setter，而设置 content 会
          立刻触发一次解析；syntaxStyle 还没赋值的话，那一趟解析查不到任何配色，
          整段文字统统退回默认色。它不会报错也不会崩——标题照样是标题、加粗照样
          去掉星号，**只是颜色全错**，非常难发现。
          （这个顺序依赖是 OpenTUI 自己源码注释里写明的。） */}
      <markdown
        syntaxStyle={syntaxStyleFor(theme)}
        // token 的完整类型来自 marked（OpenTUI 的传递依赖）。只用到 type 一个字段，
        // 不值得为它把 marked 提成直接依赖，这里用结构类型接住
        renderNode={(token: { type: string }, ctx: RenderNodeContext) =>
          token.type === "list" ? bulletizeList(ctx.defaultRender()) : null
        }
        content={props.text}
        streaming={props.streaming}
      />
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
