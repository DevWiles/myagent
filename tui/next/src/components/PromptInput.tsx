/**
 * 底部输入框。
 *
 * 和 Ink 版最大的差别：**光标不用自己画**。
 * Ink 里没有原生输入控件，得自己维护光标位置、自己在字符串里插一个反色字符冒充光标；
 * OpenTUI 的 `<input>` 是原生 Renderable，光标、选区、滚动都在 Zig 那层做好了。
 *
 * 这一步只把壳搭起来：回车触发 onSubmit 并清空。真正把内容发给后端是下一步的事。
 */

import type { InputRenderable } from "@opentui/core"
import { onMount } from "solid-js"
import { useTheme } from "../theme.ts"

export type PromptInputProps = {
  placeholder: string
  /** 正在等后端时为 true：边框换色，提示「它还活着」 */
  busy?: boolean
  onSubmit: (value: string) => void
}

export function PromptInput(props: PromptInputProps) {
  const theme = useTheme()
  let input: InputRenderable | undefined

  // 启动就聚焦：TUI 里输入框不聚焦等于界面是死的，没有「点一下」这个动作
  onMount(() => input?.focus())

  return (
    <box flexDirection="row" flexShrink={0} paddingLeft={1} paddingRight={1}>
      <text fg={props.busy ? theme.promptBorderBusy : theme.brand}>{"› "}</text>
      <box flexGrow={1} minWidth={0}>
        <input
          ref={(r: InputRenderable) => (input = r)}
          placeholder={props.placeholder}
          cursorColor={theme.brand}
          focusedTextColor={theme.text}
          // 这个 prop 的类型是 `((e: SubmitEvent) => void) & ((v: string) => void)` ——
          // 交叉类型要求处理函数**两种签名都能接**。参数标 unknown 是唯一同时满足两者的写法：
          // 写成 (value: string) 只满足一半；写成 string | SubmitEvent 也不行，
          // 因为这里的 SubmitEvent 是 OpenTUI 自己的类型，和 lib.dom 里那个同名的不是一回事。
          onSubmit={(value: unknown) => {
            if (typeof value !== "string") return
            const trimmed = value.trim()
            if (!trimmed) return
            props.onSubmit(trimmed)
            // 提交后立刻清空，否则下一句会接在上一句后面
            if (input) input.value = ""
          }}
        />
      </box>
    </box>
  )
}
