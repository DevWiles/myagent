/**
 * 文本选区状态机 + 取字。
 *
 * ## 为什么要自己做
 *
 * 一旦开启鼠标追踪（为了「点击折叠行展开」），终端就把鼠标事件全交给程序，
 * 不再自己处理拖拽选中。要让用户还能选字，只能自己接管：记选区、画高亮、
 * 松手写剪贴板。那份泄露的 Claude Code 源码走的就是这条路（自研 selection.ts
 * + useCopyOnSelect + OSC 52/pbcopy 三条剪贴板通路），这里是同一思路的精简版。
 *
 * ## 和 Claude Code 的差别（都是社区 ink 的限制导致的）
 *
 * 它的渲染器自带格子级屏幕缓冲，能 `setCellStyleId` 逐格换背景色，所以能做到
 * 「选中半行」的精确高亮。社区 ink 没有这个缓冲，我们只能重画整行文本 ——
 * 于是这里的高亮按**行内列区间**来算（首行从 startCol 起、末行到 endCol 止、
 * 中间整行），用 inverse 反色渲染。视觉效果和终端原生选中基本一致。
 *
 * 选区坐标一律用「内容坐标」（第几行内容、第几列），不是屏幕坐标 ——
 * 滚动之后屏幕行会变，内容行不会，这样拖到屏幕边缘自动滚动时选区不会错位。
 */

export type Point = { row: number; col: number }

export type SelectionState = {
  /** 按下的位置。null 表示当前没有选区。 */
  anchor: Point | null
  /** 拖到的位置。 */
  focus: Point | null
  /** 是否正在拖拽（按下未松手）。 */
  dragging: boolean
}

export const EMPTY_SELECTION: SelectionState = {
  anchor: null,
  focus: null,
  dragging: false,
}

export function startSelection(at: Point): SelectionState {
  return { anchor: at, focus: at, dragging: true }
}

export function extendSelection(s: SelectionState, to: Point): SelectionState {
  if (!s.dragging || !s.anchor) return s
  return { ...s, focus: to }
}

export function endSelection(s: SelectionState): SelectionState {
  return { ...s, dragging: false }
}

export function clearSelection(): SelectionState {
  return EMPTY_SELECTION
}

/** 选区是否至少覆盖一个格子。单击（anchor === focus）不算选区。 */
export function hasSelection(s: SelectionState): boolean {
  if (!s.anchor || !s.focus) return false
  return s.anchor.row !== s.focus.row || s.anchor.col !== s.focus.col
}

/**
 * 把 anchor/focus 归一成 start <= end，方便渲染和取字。
 * 用户可以从下往上、从右往左拖，归一之后下游就不用再操心方向。
 */
export function normalized(s: SelectionState): { start: Point; end: Point } | null {
  if (!s.anchor || !s.focus) return null
  const { anchor, focus } = s
  const anchorFirst =
    anchor.row < focus.row || (anchor.row === focus.row && anchor.col <= focus.col)
  return anchorFirst ? { start: anchor, end: focus } : { start: focus, end: anchor }
}

/**
 * 某一行里被选中的列区间，返回 [起, 止)（止为 null 表示到行尾）。
 * 该行完全没被选中时返回 null。
 *
 * 这是「行级高亮」的核心：渲染时按这个区间把该行切成三段，中段反色。
 */
export function rowRange(
  s: SelectionState,
  row: number,
): { from: number; to: number | null } | null {
  const norm = normalized(s)
  if (!norm) return null
  const { start, end } = norm
  if (row < start.row || row > end.row) return null

  // 单行选区：就是 start.col 到 end.col
  if (start.row === end.row) return { from: start.col, to: end.col }
  // 首行：从 start.col 到行尾
  if (row === start.row) return { from: start.col, to: null }
  // 末行：从行首到 end.col
  if (row === end.row) return { from: 0, to: end.col }
  // 中间行：整行
  return { from: 0, to: null }
}

/**
 * 按选区从「每行纯文本」里取出选中的字。
 *
 * lines 必须是**已去掉 ANSI 的纯文本**，索引即内容行号。
 * 行尾空白会被裁掉：终端里每行都被空格铺满到屏宽（我们自己铺的），
 * 不裁的话复制出来是一堆尾随空格。
 */
export function selectedText(s: SelectionState, lines: readonly string[]): string {
  const norm = normalized(s)
  if (!norm || !hasSelection(s)) return ''
  const out: string[] = []
  for (let row = norm.start.row; row <= norm.end.row; row++) {
    const line = lines[row]
    if (line === undefined) continue
    const range = rowRange(s, row)
    if (!range) continue
    const chars = [...line]
    const slice = chars.slice(range.from, range.to === null ? undefined : range.to)
    out.push(slice.join('').replace(/\s+$/, ''))
  }
  return out.join('\n')
}

/**
 * 把一条消息摊平成纯文本行，供选区取字用。
 *
 * 不追求和屏幕上的渲染逐字一致（那需要跑一遍排版引擎，而在 Ink 里二次渲染会
 * 和运行中的实例抢同一个 Yoga WASM 实例并崩掉）。这里给的是**语义等价的原文**：
 * 用户复制走的是干净的文字，而不是带 `•`、`✻` 这些装饰符的屏幕拓本。
 */
export function entryToLines(entry: {
  kind: string
  text?: string
  calls?: ReadonlyArray<{ tool: string; args: string; output?: string }>
}): string[] {
  if (entry.kind === 'tools') {
    const out: string[] = []
    for (const call of entry.calls ?? []) {
      out.push(`${call.tool} ${call.args}`)
      if (call.output) out.push(...call.output.split('\n'))
    }
    return out
  }
  return (entry.text ?? '').split('\n')
}
