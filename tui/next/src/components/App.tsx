/**
 * 顶层界面。
 *
 * 这一步是**纯壳**：不连后端、不跑 agent，欢迎框里的信息是写死的占位值。
 * 目的只有一个——确认 OpenTUI + Solid 这套渲染层在本项目里能跑通、布局正确。
 * 接 WebSocket 是下一步。
 *
 * 布局自上而下：欢迎框 → 对话区（先留空，靠 flexGrow 把下面的东西压到底）→ 输入框 → 状态栏。
 * 和 Ink 版一样把伸缩交给 Yoga 算，不自己数行数——
 * 自己算视口高度是 Ink 版早期踩过的坑，终端一缩放就错位。
 */

import { useTerminalDimensions } from "@opentui/solid"
import { createSignal, For } from "solid-js"
import { dark, ThemeProvider } from "../theme.ts"
import { PromptInput } from "./PromptInput.tsx"
import { StatusLine } from "./StatusLine.tsx"
import { Welcome, type WelcomeInfo } from "./Welcome.tsx"

export type AppProps = {
  info: WelcomeInfo
  branch?: string
}

export function App(props: AppProps) {
  const dimensions = useTerminalDimensions()
  // 先用一个最土的列表顶着，证明「输入 → 状态变化 → 重新渲染」这条链路是通的
  const [lines, setLines] = createSignal<string[]>([])

  return (
    <ThemeProvider value={dark}>
      <box flexDirection="column" width="100%" height="100%" paddingTop={1}>
        <Welcome info={props.info} columns={dimensions().width} />

        {/* 对话区：现在是空的，flexGrow 让它吃掉所有剩余高度，把输入框压在底部 */}
        <box flexDirection="column" flexGrow={1} minHeight={0} paddingLeft={1} paddingTop={1}>
          <For each={lines()}>{(line) => <text fg={dark.text}>{line}</text>}</For>
        </box>

        <PromptInput
          placeholder="交代一件事，比如「把 README 里的启动命令补全」"
          onSubmit={(value) => setLines((prev) => [...prev, `› ${value}`, "  （还没接后端，这一步只验证渲染层）"])}
        />
        <StatusLine
          model={props.info.model}
          workspace={props.info.workspace}
          branch={props.branch}
          columns={dimensions().width}
        />
      </box>
    </ThemeProvider>
  )
}
