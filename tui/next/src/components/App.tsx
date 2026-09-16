/**
 * 顶层界面。
 *
 * 布局自上而下：欢迎框 → 对话区 → 输入框 → 状态栏。
 * 伸缩交给 Yoga 算，不自己数行数——自己算视口高度是 Ink 版早期踩过的坑，
 * 终端一缩放就错位。
 *
 * 状态全部来自 `createSession()`：那是 Ink 版那套 SessionStore 的 Solid 封装，
 * 事件规约逻辑两版共用，这里只管画。
 */

import { useTerminalDimensions } from "@opentui/solid"
import { createEffect, For, Show } from "solid-js"
import type { BackendOptions } from "../../../src/backend.ts"
import { tildify } from "../path.ts"
import { createSession } from "../session.ts"
import { dark, ThemeProvider } from "../theme.ts"
import { EntryView } from "./EntryView.tsx"
import { PromptInput } from "./PromptInput.tsx"
import { StatusLine } from "./StatusLine.tsx"
import { Welcome } from "./Welcome.tsx"

export type AppProps = {
  options: BackendOptions
  branch?: string
  /** 后端没了就调它，由入口决定怎么退出（要还原终端）。 */
  onClosed: (reason: string) => void
}

export function App(props: AppProps) {
  const dimensions = useTerminalDimensions()
  const session = createSession(props.options)
  const state = session.state

  // 后端挂了就退出。不能在这儿直接 process.exit：终端还在 altscreen 模式里，
  // 得让入口先把 renderer destroy 掉，否则用户的终端会留在没有回显的状态
  createEffect(() => {
    const closed = state().closed
    if (closed) props.onClosed(closed)
  })

  const workspace = () => tildify(state().ready?.workspace ?? props.options.workspace)

  return (
    <ThemeProvider value={dark}>
      <box flexDirection="column" width="100%" height="100%" paddingTop={1}>
        <Welcome
          info={{
            version: state().ready?.version ?? "…",
            model: state().ready?.model ?? "连接后端中…",
            toolCount: state().ready?.tools.length ?? 0,
            workspace: workspace(),
            user: "Chris",
          }}
          columns={dimensions().width}
        />

        {/* 对话区。flexGrow 吃掉剩余高度，把输入框压到底部。
            scrollbox 是 OpenTUI 的原生滚动容器——Ink 版这里要自己算视口切片，
            这套不用，滚动在原生层做。 */}
        <box flexGrow={1} minHeight={0} paddingLeft={1} paddingRight={1}>
          <scrollbox stickyScroll stickyStart="bottom">
            <For each={state().entries}>{(entry) => <EntryView entry={entry} />}</For>
          </scrollbox>
        </box>

        {/* 没有 API key 时先说清楚，否则用户发一句话才看到报错 */}
        <Show when={state().ready && !state().ready!.hasApiKey}>
          <box paddingLeft={1}>
            <text fg={dark.warning}>没检测到 API key，发出去的请求会失败。</text>
          </box>
        </Show>

        <PromptInput
          placeholder={
            state().busy ? "正在跑…（Ctrl-C 中断）" : "交代一件事，比如「把 README 里的启动命令补全」"
          }
          busy={state().busy}
          onSubmit={(value) => {
            if (state().busy) return
            // /plan 开头走规划模式，但后端没宣告 plan 能力时退回普通对话
            const planGoal = value.startsWith("/plan ") ? value.slice(6).trim() : null
            if (planGoal && state().ready?.features.includes("plan")) session.plan(planGoal)
            else session.prompt(value)
          }}
        />

        <StatusLine
          model={state().ready?.model ?? "未连接"}
          workspace={workspace()}
          branch={props.branch}
          columns={dimensions().width}
          busy={state().busy}
          tokens={state().tokens}
          contextLimit={state().contextLimit}
        />
      </box>
    </ThemeProvider>
  )
}
