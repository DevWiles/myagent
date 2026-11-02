/**
 * 「信任这个目录吗？」——在一个目录里第一次起 agent 之前问一句（见 `trust.ts`）。
 *
 * 这时后端还没起，App 也还没挂载，所以按键就在这里处理，不像审批框那样交给 App。
 * Ctrl-C 不管：那一刻归 main.tsx 的兜底，直接退出。
 *
 * 选项和审批框长一个样（↑↓ + Enter，或直接按数字），理由同 PlanConfirmDialog：
 * 同一类问题换一种按法，手比脑子快的那一下必然按错。
 */

import { TextAttributes, type KeyEvent } from "@opentui/core"
import { useKeyboard } from "@opentui/solid"
import { createSignal, For, Show } from "solid-js"
import { tildify } from "../path.ts"
import { useTheme } from "../theme.ts"

const CHOICES = ["信任，继续", "不信任，退出"] as const

export function TrustDialog(props: {
  workspace: string
  /** 在这里起 agent 的特别风险（家目录、系统目录……），没有就是 null。 */
  risk: string | null
  /** 同意之后会不会记住。不记住时要说一声，免得用户以为下次不用再答。 */
  remembered: boolean
  onAccept: () => void
  onDecline: () => void
}) {
  const theme = useTheme()
  const [choice, setChoice] = createSignal(0)

  const answer = (index: number) => (index === 0 ? props.onAccept() : props.onDecline())

  useKeyboard((key: KeyEvent) => {
    if (key.ctrl) return
    switch (key.name) {
      case "up":
      case "down":
        setChoice((c) => 1 - c)
        return
      case "return":
        answer(choice())
        return
      case "escape":
        props.onDecline()
        return
      case "1":
      case "2":
        answer(Number(key.name) - 1)
        return
    }
  })

  return (
    <box flexDirection="column" padding={1}>
      <box
        flexDirection="column"
        flexShrink={0}
        border
        borderColor={theme.approvalBorder}
        paddingLeft={1}
        paddingRight={1}
      >
        <text fg={theme.approvalBorder} attributes={TextAttributes.BOLD}>
          信任这个目录吗？
        </text>
        <text fg={theme.heading} attributes={TextAttributes.BOLD}>
          {tildify(props.workspace)}
        </text>

        <box flexDirection="column" paddingTop={1}>
          <text fg={theme.subtle}>
            myagent 会读这个目录里的文件（包括 .myagent/skills、.claude/skills 里的技能，它们会直接进提示词），
            并在你同意后在这里执行命令、修改文件。
          </text>
          <text fg={theme.subtle}>只在你信任的目录里用：来路不明的仓库可能藏着引导 agent 干坏事的文字。</text>
        </box>

        <Show when={props.risk}>
          {(risk) => (
            <box paddingTop={1}>
              <text fg={theme.warning}>⚠ {risk()}</text>
            </box>
          )}
        </Show>

        <box flexDirection="column" paddingTop={1}>
          <For each={CHOICES}>
            {(label, i) => {
              const active = () => i() === choice()
              return (
                <box flexDirection="row">
                  <text fg={active() ? theme.approvalBorder : theme.inactive}>{active() ? "❯ " : "  "}</text>
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
          <text fg={theme.subtle}>
            {props.remembered
              ? "  ↑↓ 选   Enter 确认   Esc 退出   （信任后这个目录和它的子目录不再问）"
              : "  ↑↓ 选   Enter 确认   Esc 退出   （这里只信这一次，下次启动还会问）"}
          </text>
        </box>
      </box>
    </box>
  )
}
