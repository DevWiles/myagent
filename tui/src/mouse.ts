/**
 * SGR 鼠标事件解析。
 *
 * 社区版 ink 只给键盘（useInput），完全没有鼠标。所以我们自己开鼠标追踪
 * （见 main.tsx 的 ENABLE_MOUSE），从 raw stdin 里把鼠标序列抠出来。
 *
 * SGR 编码（DECSET 1006）：`ESC [ < b ; col ; row M`（按下/拖动/滚轮）或
 * 结尾 `m`（释放）。col/row 是 1-based。低 2 位是按钮号，高位是 bit flag：
 *   bit 6 (64)  滚轮 → button 0 = 上滚，1 = 下滚
 *   bit 5 (32)  motion（移动/拖动）
 *
 * 为了自研文本选中，这里要报全 down / drag / up 三种事件——只有释放事件是
 * 不够的：选区需要「按下记锚点、拖动扩选、松手复制」。
 */

export type MouseEvent =
  | { type: 'down'; col: number; row: number }
  | { type: 'drag'; col: number; row: number }
  | { type: 'up'; col: number; row: number }
  | { type: 'wheel'; direction: 'up' | 'down' }

// ESC [ < btn ; col ; row (M|m)
const SGR_MOUSE = /\x1b\[<(\d+);(\d+);(\d+)([Mm])/g

/**
 * 从一段 raw 输入里提取所有鼠标事件。
 *
 * 一次 stdin data 里可能夹着多个序列（快速拖动），也可能混着普通按键。
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

    const isLeft = (btn & 0x3) === 0
    if (!isLeft) continue // 只管左键，中/右键留给终端

    if (isRelease) {
      events.push({ type: 'up', col, row })
    } else if (btn & 0x20) {
      // motion 且左键按着 = 拖动
      events.push({ type: 'drag', col, row })
    } else {
      events.push({ type: 'down', col, row })
    }
  }
  rest += input.slice(lastIndex)
  return { events, rest }
}
