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

/**
 * 名字那一列至少留出的空隙。
 *
 * <p>列宽<b>按实际内容算</b>，不写死一个数。写死过一次，值正好等于最长的那条
 * （`/resume-plan` 是 12 个字符，NAME_WIDTH 也是 12），于是 `padEnd(12)` 一个空格
 * 都补不出来，屏幕上是「/resume-plan接着跑上次…」。加一条命令就可能再犯一次，
 * 所以改成现算，并且强制留 GUTTER 个空格。
 */
const GUTTER = 2

export function CommandMenu(props: CommandMenuProps) {
  const theme = useTheme()

  /** 名字列有多宽：最长的那条 + 空隙。 */
  const nameWidth = () =>
    Math.max(
      ...props.entries.map((entry) => (entry.name + (entry.args ? " " + entry.args : "")).length),
      0,
    ) + GUTTER

  return (
    <Show when={props.entries.length > 0}>
      <box flexDirection="column" flexShrink={0} paddingLeft={1} paddingRight={1}>
        <Index each={props.entries}>
          {(entry, index) => {
            const active = () => index === props.selected
            const head = () => {
              const name = entry().name + (entry().args ? " " + entry().args : "")
              return name.padEnd(nameWidth(), " ")
            }
            // 说明挤不下就截断：宽度不够时宁可少看几个字，也不能让这一条换行——
            // 菜单一换行，下面的输入框位置就跟着跳，打字时眼睛得重新找
            // 用不了的时候只说**原因**，不再把说明和原因套成两层括号
            // （原来是「接着跑上次没跑完的计划（做成的步骤不重来）（上次没有留下没跑完的计划）」，
            // 而说明本身常常也带括号，叠起来根本读不出哪句是哪句）。
            // 此刻用户要的答案是「为什么它是灰的」，不是「它本来能干什么」
            const tail = () =>
              truncate(
                entry().available
                  ? entry().description
                  : `⊘ ${entry().reason ?? "现在用不了"}`,
                Math.max(10, props.columns - nameWidth() - 4),
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
