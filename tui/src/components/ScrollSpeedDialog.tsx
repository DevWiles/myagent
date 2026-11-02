/**
 * `/scroll-speed` 弹窗。照 Claude Code：开着的时候滚一下就是新手感，
 * ←→ 调、r 恢复默认、Enter 存、Esc 撤回到打开前。
 *
 * 和别的框同一套纪律：<b>按键不在这里处理</b>，在 App 里。
 */

import { TextAttributes } from "@opentui/core"
import { Show } from "solid-js"
import { DEFAULT_SCROLL_SPEED, formatSpeed, SPEED_STEPS } from "../scroll.ts"
import { useTheme } from "../theme.ts"

export function ScrollSpeedDialog(props: { speed: number; envOverride: boolean }) {
  const theme = useTheme()
  const atMin = () => props.speed <= SPEED_STEPS[0]
  const atMax = () => props.speed >= SPEED_STEPS[SPEED_STEPS.length - 1]

  return (
    <box
      flexDirection="column"
      flexShrink={0}
      border
      borderColor={theme.border}
      paddingLeft={1}
      paddingRight={1}
    >
      <text fg={theme.brand} attributes={TextAttributes.BOLD}>
        滚轮速度
      </text>

      <box flexDirection="row" paddingTop={1}>
        <text fg={atMin() ? theme.inactive : theme.text}>{"◀ "}</text>
        <text fg={theme.text} attributes={TextAttributes.BOLD}>
          {formatSpeed(props.speed)}
        </text>
        <text fg={atMax() ? theme.inactive : theme.text}>{" ▶"}</text>
        <text fg={theme.subtle}>{`   每格 ${formatSpeed(props.speed)} 行，快搓会加速（默认 ${DEFAULT_SCROLL_SPEED}）`}</text>
      </box>

      <Show when={props.envOverride}>
        <box paddingTop={1}>
          <text fg={theme.warning}>MYAGENT_SCROLL_SPEED 设着，下次启动它仍会压过这里存的值</text>
        </box>
      </Show>

      <box paddingTop={1}>
        <text fg={theme.subtle}>滚一下试试 · ←→ 调 · r 恢复默认 · Enter 保存 · Esc 取消</text>
      </box>
    </box>
  )
}
