/**
 * 一段 diff：左边行号，右边整行底色。
 *
 * <h3>为什么抽成共用组件</h3>
 * diff 出现在两个地方——**确认框**（改动还没发生，你在决定批不批）和
 * **转录**（改动已经发生，你在回看改了什么）。两处各画一份的话，
 * 同一段 diff 在审批时和事后会长得不一样，而用户正是拿这两屏做对照的。
 *
 * <h3>为什么要自己补空格</h3>
 * `<text bg=...>` 只给**文字本身**铺底色，`width="100%"` 也不管用
 * （实测过：行尾那段空白的 bg 是透明的）。要让底色铺满一整行，
 * 只能把这一行按显示宽度补足空格。
 *
 * <p>补的时候必须用 {@link width} 而不是 `.length`：中文一个字占两列，
 * 按字符数补的话，含中文的行会短一截，底色右边缘参差不齐。
 */

import { For, Show } from "solid-js"
import { gutterWidth, lineNoOf, parseUnified, visibleRows, type DiffRow } from "../diff-view.ts"
import { truncateEnd, width } from "../path.ts"
import { useTheme, type Theme } from "../theme.ts"

export type DiffViewProps = {
  /** 后端来的统一 diff 原文 */
  diff: string
  /** 可用宽度（列）。底色要铺满它 */
  columns: number
  /** 最多画几行 */
  budget: number
}

export function DiffView(props: DiffViewProps) {
  const theme = useTheme()
  const rows = () => parseUnified(props.diff)
  const shown = () => visibleRows(rows(), props.budget)
  const gutter = () => gutterWidth(rows())

  return (
    <box flexDirection="column">
      <For each={shown().rows}>
        {(row) => <DiffRowView row={row} gutter={gutter()} columns={props.columns} theme={theme} />}
      </For>
      <Show when={shown().hidden > 0}>
        <text fg={theme.subtle}>{`… 还有 ${shown().hidden} 行没显示`}</text>
      </Show>
    </box>
  )
}

function DiffRowView(props: {
  row: DiffRow
  gutter: number
  columns: number
  theme: Theme
}) {
  const bg = (): string | undefined => {
    if (props.row.kind === "add") return props.theme.diffAddBg
    if (props.row.kind === "del") return props.theme.diffDelBg
    return undefined
  }
  const fg = (): string => {
    if (props.row.kind === "add") return props.theme.diffAdd
    if (props.row.kind === "del") return props.theme.diffDel
    if (props.row.kind === "hunk") return props.theme.subtle
    return props.theme.text
  }

  // hunk 抬头不带行号也不铺底色：它是分段标记，不是一行代码
  const body = (): string =>
    props.row.kind === "hunk"
      ? props.row.text
      : `${lineNoOf(props.row).padStart(props.gutter)} ${props.row.sign} ${props.row.text}`

  return (
    // wrapMode="none"：宁裁不折。columns 是上层**估**出来的（要逐层扣 padding、
    // 滚动条），估多一列的话，补出的空格就会折行——每行 diff 变成两行，
    // 第二行全空，看着像开了一倍行距。裁掉的只是行尾补的空格，无伤；
    // 正文本身在这之前已经按 columns 截断并标了省略号
    <text wrapMode="none" fg={fg()} bg={bg()}>
      {pad(truncateEnd(body(), props.columns), props.columns)}
    </text>
  )
}

/**
 * 按**显示宽度**补到指定列数，好让底色铺满整行。
 *
 * <p>hunk 抬头那种不铺底色的行也一起补——不补的话它比别的行短，
 * 在一片带底色的行之间会显出一个缺口。
 */
function pad(text: string, columns: number): string {
  const gap = columns - width(text)
  return gap > 0 ? text + " ".repeat(gap) : text
}
