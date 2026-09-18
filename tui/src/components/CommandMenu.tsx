import { Index, Show } from "solid-js"
import { TextAttributes } from "@opentui/core"
import { useTheme } from "../theme.ts"
import { truncate } from "../format.ts"
import type { CommandEntry } from "../commands.ts"

/**
 * 输入框上方的命令候选条，随打字实时收窄。
 *
 * <h3>为什么要有它</h3>
 * 斜杠命令是「不说就没人知道」的那类功能：`/help` 里写了也没用——
 * 用户得先知道有 `/help`。菜单把命令表摊在他正在打字的地方，
 * 顺带解决了另一半问题：**命令是不是真的存在，敲的时候就看得见**，
 * 而不是按下回车、等一轮往返之后才被告知「没这个命令」。
 *
 * <h3>用不了的也列，但灰着</h3>
 * 「没有存档所以 /continue 用不了」和「根本没有 /continue 这条命令」，
 * 在用户眼里长得一模一样——如果我们把前者藏起来的话。所以照列，
 * 右边写明原因。看得见但用不了，比凭空消失好懂。
 */
export type CommandMenuProps = {
  entries: CommandEntry[]
  /** 选中第几条（↑↓ 移动，Tab 补全）。 */
  selected: number
  columns: number
}

/** 最长的命令是 /resume-plan，按它对齐，说明文字才不会一行一个起点。 */
const NAME_WIDTH = 12

export function CommandMenu(props: CommandMenuProps) {
  const theme = useTheme()

  return (
    <Show when={props.entries.length > 0}>
      <box flexDirection="column" flexShrink={0} paddingLeft={1} paddingRight={1}>
        <Index each={props.entries}>
          {(entry, index) => {
            const active = () => index === props.selected
            const head = () => {
              const name = entry().name + (entry().args ? " " + entry().args : "")
              return name.padEnd(NAME_WIDTH, " ")
            }
            // 说明挤不下就截断：宽度不够时宁可少看几个字，也不能让这一条换行——
            // 菜单一换行，下面的输入框位置就跟着跳，打字时眼睛得重新找
            const tail = () =>
              truncate(
                entry().available
                  ? entry().description
                  : `${entry().description}（${entry().reason ?? "现在用不了"}）`,
                Math.max(10, props.columns - NAME_WIDTH - 6),
              )
            return (
              <box flexDirection="row">
                <text fg={active() ? theme.brand : theme.inactive}>{active() ? "❯ " : "  "}</text>
                <text
                  fg={entry().available ? (active() ? theme.brand : theme.user) : theme.inactive}
                  attributes={active() ? TextAttributes.BOLD : undefined}
                >
                  {head()}
                </text>
                <text fg={entry().available ? theme.subtle : theme.inactive}>{tail()}</text>
              </box>
            )
          }}
        </Index>
        <text fg={theme.inactive}>
          {"  ↑↓ 选择 · Tab 补全 · Enter 执行"}
        </text>
      </box>
    </Show>
  )
}
