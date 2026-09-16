/**
 * SGR 鼠标事件解析。
 *
 * 社区版 ink 只给键盘（useInput），完全没有鼠标。所以我们自己开鼠标追踪
 * （见 main.tsx 的 ENABLE_MOUSE），从 raw stdin 里把鼠标序列抠出来。
 *
 * SGR 编码（DECSET 1006）：`ESC [ < b ; col ; row M`（按下/滚轮）或
 * 结尾 `m`（释放）。col/row 是 1-based。低 2 位是按钮号，高位是修饰键和
 * 事件类型的 bit flag：
 *   bit 6 (64)  滚轮/扩展按钮 → button 0 = 上滚，1 = 下滚
 *   bit 5 (32)  motion（拖拽/移动），我们不关心，直接丢
 *
 * 只解析左键单击和滚轮——这是「点击展开 + 滚动」需要的全部，不碰拖拽/hover，
 * 少一分误触。终端里的拖拽选中已经因为开了鼠标追踪交给用户按 option 走原生。
 */

export type MouseEvent =
  | { type: 'click'; col: number; row: number }
  | { type: 'wheel'; direction: 'up' | 'down' }

// ESC [ < btn ; col ; row (M|m)
const SGR_MOUSE = /\x1b\[<(\d+);(\d+);(\d+)([Mm])/g

/**
 * 从一段 raw 输入里提取所有鼠标事件。
 *
 * 一次 stdin data 里可能夹着多个序列（快速滚动），也可能只有鼠标没有别的。
 * 返回鼠标事件列表 + 去掉鼠标序列后剩下的文本（交回给 ink 当普通按键）。
 */
export function extractMouseEvents(input: string): {
  events: MouseEvent[]
  rest: string
} {
  const events: MouseEvent[] = []
  let rest = ''
  let lastIndex = 0

  SGR_MOUSE.lastIndex = 0
  let match: RegExpExecArray | null
  while ((match = SGR_MOUSE.exec(input)) !== null) {
    rest += input.slice(lastIndex, match.index)
    lastIndex = match.index + match[0].length

    const btn = Number(match[1])
    const col = Number(match[2])
    const row = Number(match[3])
    const isRelease = match[4] === 'm'

    // 滚轮：bit 6 置位，button 0/1 = 上/下
    if (btn & 0x40) {
      events.push({ type: 'wheel', direction: (btn & 0x1) === 0 ? 'up' : 'down' })
      continue
    }
    // motion（拖拽/移动）：bit 5 置位，丢弃
    if (btn & 0x20) continue
    // 只认左键（低 2 位 = 0）的**释放**——用释放而不是按下来判定「点击」，
    // 避免和拖拽选中的按下抢；释放时鼠标还在原格就是一次干净的点击。
    if ((btn & 0x3) === 0 && isRelease) {
      events.push({ type: 'click', col, row })
    }
  }
  rest += input.slice(lastIndex)
  return { events, rest }
}
