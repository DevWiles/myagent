/**
 * 「执行这份计划?」确认框。
 *
 * 计划本身在对话流里，这个框只负责问一句，所以比审批框还简单，不用复述内容。
 * 和 ApprovalDialog 同一套纪律：**按键不在这里处理**，在 App 里——
 * 后端此刻卡在 confirm() 上等回答，按键必须无条件活着；
 * 光标停在哪一项也存在 store 里，理由一样。
 *
 * 刻意**没有「本会话都同意」**：每份计划都不一样，一次性授权掉后面所有计划
 * 是危险的。审批框有这一项、这里没有，是有意的差别（见 docs/protocol.md）。
 *
 * 选项和审批框长一个样，这不是凑数——两个框在同一个位置弹出、问的是同一类问题，
 * 一个要敲字母另一个要挪光标的话，手比脑子快的那一下必然按错。
 */

import { TextAttributes } from "@opentui/core"
import { For } from "solid-js"
import { PLAN_CHOICES } from "../approval.ts"
import { useTheme } from "../theme.ts"

export function PlanConfirmDialog(props: { choice: number }) {
  const theme = useTheme()

  return (
    <box
      flexDirection="column"
      flexShrink={0}
      border
      borderColor={theme.approvalBorder}
      paddingLeft={1}
      paddingRight={1}
    >
      <text fg={theme.approvalBorder} attributes={TextAttributes.BOLD}>
        执行这份计划？
      </text>

      <box paddingTop={1}>
        <text fg={theme.subtle}>计划就在下面的面板里，步骤多的话往上滚有完整版</text>
      </box>

      <box flexDirection="column" paddingTop={1}>
        <For each={PLAN_CHOICES}>
          {(label, i) => {
            const active = () => i() === props.choice
            return (
              <box flexDirection="row">
                <text fg={active() ? theme.approvalBorder : theme.inactive}>
                  {active() ? "❯ " : "  "}
                </text>
                <text fg={theme.inactive}>{`${i() + 1}. `}</text>
                <text
                  fg={active() ? (i() === 0 ? theme.success : theme.error) : theme.subtle}
                  attributes={active() ? TextAttributes.BOLD : undefined}
                >
                  {label}
                </text>
              </box>
            )
          }}
        </For>
        <text fg={theme.subtle}>{"  ↑↓ 选   Enter 确认   Esc 放弃"}</text>
      </box>
    </box>
  )
}
