import { describe, expect, test } from 'bun:test'
import { extractMouseEvents } from './mouse.ts'

describe('SGR 鼠标解析', () => {
  test('左键释放 = 一次点击，带出 col/row', () => {
    // 按下 ESC[<0;10;5M，释放 ESC[<0;10;5m —— 用释放判定点击
    const { events } = extractMouseEvents('\x1b[<0;10;5m')
    expect(events).toEqual([{ type: 'click', col: 10, row: 5 }])
  })

  test('按下不算点击，只认释放（避免和拖拽选中抢）', () => {
    const { events } = extractMouseEvents('\x1b[<0;10;5M')
    expect(events).toHaveLength(0)
  })

  test('滚轮上/下', () => {
    expect(extractMouseEvents('\x1b[<64;1;1M').events).toEqual([{ type: 'wheel', direction: 'up' }])
    expect(extractMouseEvents('\x1b[<65;1;1M').events).toEqual([{ type: 'wheel', direction: 'down' }])
  })

  test('拖拽/移动（bit5）丢弃，不误当点击', () => {
    // btn 32 = motion
    expect(extractMouseEvents('\x1b[<32;10;5M').events).toHaveLength(0)
  })

  test('一段里多个事件都抓出来', () => {
    const { events } = extractMouseEvents('\x1b[<64;1;1M\x1b[<64;1;1M\x1b[<0;3;7m')
    expect(events).toEqual([
      { type: 'wheel', direction: 'up' },
      { type: 'wheel', direction: 'up' },
      { type: 'click', col: 3, row: 7 },
    ])
  })

  test('鼠标序列被剥离，普通按键从 rest 原样交回给 ink', () => {
    const { events, rest } = extractMouseEvents('a\x1b[<0;1;1mb')
    expect(events).toHaveLength(1)
    expect(rest).toBe('ab')
  })

  test('没有鼠标序列时原样返回', () => {
    const { events, rest } = extractMouseEvents('hello')
    expect(events).toHaveLength(0)
    expect(rest).toBe('hello')
  })
})
