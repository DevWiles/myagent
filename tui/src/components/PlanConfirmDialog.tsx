/**
 * plan 模式的确认框，两种问法（照 Claude Code）：
 *
 * - `enter`：模型想先进 plan 模式调研、出计划再动手——是非题
 * - `exit`：模型交了计划——批准 / 不批准并说说要改什么 / 直接不批准
 *
 * 和 ApprovalDialog 同一套纪律：**按键不在这里处理**，在 App 里——
 * 后端此刻卡在 review() 上等回答，按键必须无条件活着；
 * 光标停在哪一项、补话补到哪个字也存在 store 里，理由一样。
 *
 * 计划正文框里只放前面一截：长计划整段摊在框里会把对话区挤没。
 * 全文同时进了转录（store 的 plan entry），往上滚就能读完。
 *
 * 刻意**没有「以后都同意」**：每份计划都不一样，一次性授权掉后面所有计划
 * 是危险的。审批框有这一项、这里没有，是有意的差别（见 docs/protocol.md）。
 */

import { TextAttributes } from "@opentui/core"
import { For, Show } from "solid-js"
import { planChoices } from "../approval.ts"
import { truncateEnd } from "../path.ts"
import type { PlanConfirm } from "../store.ts"
import { useTheme } from "../theme.ts"
import { EntryView } from "./EntryView.tsx"

/** 框里最多露几行计划。屏幕的三成，夹在 4 到 20 之间。 */
export function planPreviewLines(rows: number): number {
  return Math.max(4, Math.min(20, Math.floor(rows * 0.3)))
}

/** 截出框里那一截。截了的话返回剩下几行，给提示语用。 */
export function planPreview(plan: string, maxLines: number): { text: string; hidden: number } {
  const lines = plan.trim().split("\n")
  if (lines.length <= maxLines) return { text: lines.join("\n"), hidden: 0 }
  return { text: lines.slice(0, maxLines).join("\n"), hidden: lines.length - maxLines }
}

export function PlanConfirmDialog(props: { confirm: PlanConfirm; columns: number; rows: number }) {
  const theme = useTheme()
  const choices = () => planChoices(props.confirm.ask)
  const preview = () => planPreview(props.confirm.plan, planPreviewLines(props.rows))

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
        {props.confirm.ask === "enter" ? "先进 plan 模式？" : "批准这份计划？"}
      </text>

      <Show when={props.confirm.ask === "enter"}>
        <box paddingTop={1}>
          <text fg={theme.subtle}>模型想先进 plan 模式调研、出计划再动手——计划批了之前不改任何东西</text>
        </box>
      </Show>

      <Show when={props.confirm.ask === "exit" && preview().text}>
        <EntryView entry={{ kind: "assistant", key: "plan-preview", text: preview().text }} columns={props.columns - 2} />
        <Show when={preview().hidden > 0}>
          <text fg={theme.subtle}>{`…还有 ${preview().hidden} 行，往上滚看完整计划`}</text>
        </Show>
      </Show>

      <Show
        when={props.confirm.feedback !== null}
        fallback={
          <box flexDirection="column" paddingTop={1}>
            <For each={choices()}>
              {(choice, i) => {
                const active = () => i() === props.confirm.choice
                return (
                  <box flexDirection="row">
                    <text fg={active() ? theme.approvalBorder : theme.inactive}>
                      {active() ? "❯ " : "  "}
                    </text>
                    <text fg={theme.inactive}>{`${i() + 1}. `}</text>
                    <text
                      fg={active() ? (choice.key === "approve" ? theme.success : theme.error) : theme.subtle}
                      attributes={active() ? TextAttributes.BOLD : undefined}
                    >
                      {choice.label}
                    </text>
                  </box>
                )
              }}
            </For>
            <text fg={theme.subtle}>{"  ↑↓ 选   Enter 确认   Esc 不批准"}</text>
          </box>
        }
      >
        {/* 补一句话：和审批框的拒绝理由一个样子 */}
        <box flexDirection="column" paddingTop={1}>
          <box flexDirection="row">
            <text fg={theme.error} attributes={TextAttributes.BOLD}>
              要改的是:{" "}
            </text>
            <text fg={theme.text}>
              {truncateEnd(props.confirm.feedback ?? "", Math.max(6, props.columns - 16))}
            </text>
            <text fg={theme.subtle}>▏</text>
          </box>
          <text fg={theme.subtle}>这句话会原样交给模型    [enter] 发出    [esc] 不带话直接不批准</text>
        </box>
      </Show>
    </box>
  )
}
