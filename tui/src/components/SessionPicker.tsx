import { Index, Show } from "solid-js"
import { TextAttributes } from "@opentui/core"
import { useTheme } from "../theme.ts"
import { formatBytes, formatRelativeTime } from "../format.ts"
import { truncateWidth } from "../path.ts"
import { describeFilter, windowAround, type PickerFilter, type SessionRow } from "../session-picker.ts"

/**
 * 会话选择器：从这个工作区的历史里挑一段接着聊。
 *
 * <h3>每一行为什么要这三样元信息</h3>
 * 挑错会话的代价是<b>拿着过时的上下文继续往下做</b>——模型会拿三天前的结论
 * 去改今天的代码，语气还一样笃定。所以副行上那三样各自回答一个问题：
 * <b>时间</b>答「还新鲜吗」，<b>分支</b>答「那段是在哪条线上聊的」（往往一眼
 * 就能排除掉一半），<b>大小</b>是「这段聊得有多沉」的便宜代理——
 * 几十 KB 是随手问两句，几百 KB 是啃了半天。
 *
 * <h3>为什么没有自己的搜索框</h3>
 * 搜索用的就是下面那个<b>主输入框</b>：面板开着的时候，输入框里打的字就是搜索词。
 * 自己再放一个 input 就要处理焦点在两个控件之间来回交接，
 * 而那正是这套界面上最容易出「按键打到别处去」的地方（见 App 里 consume 那段注释）。
 */
export type SessionPickerProps = {
  /** 已经筛过的行。 */
  rows: readonly SessionRow[]
  /** 筛之前一共几段。筛掉了多少必须说出来。 */
  total: number
  selected: number
  /** 此刻正待在哪一段里。标出来，否则用户会挑中自己现在就在的那段，然后发现什么都没变。 */
  currentId: string
  filter: PickerFilter
  columns: number
  /** 最多显示几段（一段占两行）。 */
  maxRows: number
  /** 「现在」是什么时候，用来算相对时间。传进来才能测。 */
  now: number
}

export function SessionPicker(props: SessionPickerProps) {
  const theme = useTheme()

  /**
   * 真正能写字的宽度：外面给的宽度减掉自己左右各 1 的 padding。
   *
   * <p>不扣的话最后一行会**溢出 2 列**，终端把它折成两行，
   * 面板高度凭空多一行——而面板是钉在输入框上方的，多一行就把输入框往下顶。
   * 截断按这个算，不按 props.columns。
   */
  const inner = () => Math.max(8, props.columns - 2)

  const view = () => windowAround(props.rows.length, props.selected, props.maxRows)
  const visible = () => props.rows.slice(view().start, view().end)
  const hidden = () => props.rows.length - visible().length

  const footer = () => {
    const branch = props.filter.currentBranch
    const toggle = branch
      ? props.filter.branchOnly
        ? `Ctrl-B 看全部分支`
        : `Ctrl-B 只看 ${branch}`
      : null
    return ["↑↓ 选择", "Enter 接着聊", toggle, "输入即搜索", "Esc 取消"]
      .filter(Boolean)
      .join(" · ")
  }

  return (
    <box
      flexDirection="column"
      flexShrink={0}
      border={["top", "bottom"]}
      borderColor={theme.border}
      paddingLeft={1}
      paddingRight={1}
    >
      <box flexDirection="row">
        <text flexShrink={0} fg={theme.brand} attributes={TextAttributes.BOLD}>
          {"接着聊哪一段"}
        </text>
        <text flexShrink={0} fg={theme.subtle}>
          {"  " + describeFilter(props.total, props.rows.length, props.filter)}
        </text>
      </box>

      <Show when={props.total === 0}>
        <text fg={theme.subtle}>{"这个工作区还没有存下来的会话。"}</text>
      </Show>
      <Show when={props.total > 0 && props.rows.length === 0}>
        <text fg={theme.subtle}>{"没有匹配的会话。改几个字，或者 Esc 取消。"}</text>
      </Show>

      <Index each={visible()}>
        {(row, index) => {
          const active = () => view().start + index === props.selected
          const title = () =>
            truncateWidth(
              row().title.trim() || "(没有内容)",
              Math.max(8, inner() - (row().id === props.currentId ? 10 : 2)),
            )
          // 时间 · 分支 · 大小 · 轮数。分支可能是 null（不在 git 仓库里，或是老存档），
          // 那就整段省掉——写个「null」或者「无分支」只是噪音
          const meta = () =>
            [
              formatRelativeTime(row().savedAt, props.now),
              row().branch ?? null,
              formatBytes(row().bytes),
              `${row().turns} 轮`,
            ]
              .filter(Boolean)
              .join(" · ")
          return (
            <box flexDirection="column">
              <box flexDirection="row">
                <text flexShrink={0} fg={active() ? theme.brand : theme.inactive}>
                  {active() ? "❯ " : "  "}
                </text>
                <text
                  flexShrink={0}
                  fg={active() ? theme.brand : theme.text}
                  attributes={active() ? TextAttributes.BOLD : undefined}
                >
                  {title()}
                </text>
                <Show when={row().id === props.currentId}>
                  <text flexShrink={0} fg={theme.subtle}>
                    {"  ← 当前"}
                  </text>
                </Show>
              </box>
                    <text fg={theme.subtle}>{"    " + truncateWidth(meta(), inner() - 4)}</text>
            </box>
          )
        }}
      </Index>

      <Show when={hidden() > 0}>
        <text fg={theme.inactive}>{`    …还有 ${hidden()} 段（↑↓ 翻）`}</text>
      </Show>

      <text fg={theme.inactive}>{truncateWidth(footer(), inner())}</text>
    </box>
  )
}
