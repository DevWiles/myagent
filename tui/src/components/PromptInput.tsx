/**
 * 底部输入框。
 *
 * 和 Ink 版最大的差别：**光标不用自己画**。
 * Ink 里没有原生输入控件，得自己维护光标位置、自己在字符串里插一个反色字符冒充光标；
 * OpenTUI 的 `<input>` 是原生 Renderable，光标、选区、滚动都在 Zig 那层做好了。
 */

import type { InputRenderable } from "@opentui/core"
import { useTheme } from "../theme.ts"

export type PromptInputProps = {
  placeholder: string
  /** 正在等后端时为 true：提示符换色，提示「它还活着」 */
  busy?: boolean
  /**
   * 有确认框挂着时为 true：必须**交出焦点**。
   *
   * 否则用户按 y/n 想回答确认框，字符会被输入框一并吃掉打在里面
   * （确认框照样会响应——按键是全局监听的——但输入框里会留下一个莫名其妙的 y）。
   */
  suspended?: boolean
  /**
   * 输入框内容变了。命令菜单靠它边打边收窄。
   *
   * 程序改内容（提交后清空、Tab 补全）时也会叫一次——OpenTUI 的 input 事件
   * 只在用户按键时发，不叫的话菜单会挂在屏幕上不走。
   */
  onValueChange?: (value: string) => void
  /**
   * 当前内容对上了一条真实存在的命令。
   *
   * 整行换色。这是「这条命令确实存在」最便宜的反馈——按回车之前就看得见，
   * 而不是等一轮往返之后被告知「没这个命令」。
   */
  matched?: boolean
  /** 挂载时把「改内容」的手交出去，Tab 补全要用。 */
  controls?: (api: { setValue: (value: string) => void }) => void
  onSubmit: (value: string) => void
}

export function PromptInput(props: PromptInputProps) {
  const theme = useTheme()
  let input: InputRenderable | undefined

  // 上下两条横线把输入区从对话流和状态栏里切出来。
  // 正在跑的时候换成高亮色——这是除了状态栏那个点之外，第二个「它还活着」的信号，
  // 而且是全屏最宽的一处，眼睛不用去找。
  const rule = () => (props.busy ? theme.promptBorderBusy : theme.promptBorder)

  return (
    <box
      flexDirection="row"
      flexShrink={0}
      // 只要上下两条边：左右再封起来就成了一个方框，和下面的状态栏挤在一起显得很闷
      border={["top", "bottom"]}
      borderColor={rule()}
      paddingLeft={1}
      paddingRight={1}
    >
      <text fg={props.busy ? theme.promptBorderBusy : theme.brand}>{"› "}</text>
      <box flexGrow={1} minWidth={0}>
        <input
          ref={(r: InputRenderable) => {
            input = r
            props.controls?.({
              setValue: (value: string) => {
                r.value = value
                props.onValueChange?.(value)
              },
            })
          }}
          // 用声明式的 focused 而不是 onMount 里调 input.focus()：
          // 焦点要跟着 suspended 来回切，写成命令式就得自己管两个方向的时序，
          // 而且初次挂载时 ref 还没赋值，focus() 会静默落空
          focused={!props.suspended}
          placeholder={props.placeholder}
          cursorColor={theme.brand}
          // 对上命令就整行换成品牌色，见 matched 那段注释
          focusedTextColor={props.matched ? theme.brand : theme.text}
          onInput={(value: unknown) => {
            if (typeof value === "string") props.onValueChange?.(value)
          }}
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
            props.onValueChange?.("")   // 清空是程序干的，input 事件不会叫，菜单得自己收
          }}
        />
      </box>
    </box>
  )
}
