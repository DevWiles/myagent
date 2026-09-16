import { describe, expect, test } from 'bun:test'
import { extractMouseEvents } from './mouse.ts'

describe('SGR 鼠标解析', () => {
  test('左键按下 → down（选区的锚点）', () => {
    expect(extractMouseEvents('\x1b[<0;10;5M').events).toEqual([
      { type: 'down', col: 10, row: 5 },
    ])
  })

  test('左键按着移动 → drag（扩选）', () => {
    // btn 32 = motion 位 + 左键
    expect(extractMouseEvents('\x1b[<32;12;7M').events).toEqual([
      { type: 'drag', col: 12, row: 7 },
    ])
  })

  test('左键松开 → up（结束选区/触发点击）', () => {
    expect(extractMouseEvents('\x1b[<0;10;5m').events).toEqual([
      { type: 'up', col: 10, row: 5 },
    ])
  })

  test('滚轮上/下', () => {
    expect(extractMouseEvents('\x1b[<64;1;1M').events).toEqual([{ type: 'wheel', direction: 'up' }])
    expect(extractMouseEvents('\x1b[<65;1;1M').events).toEqual([{ type: 'wheel', direction: 'down' }])
  })

  test('中键/右键忽略，留给终端', () => {
    expect(extractMouseEvents('\x1b[<1;5;5M').events).toHaveLength(0)
    expect(extractMouseEvents('\x1b[<2;5;5M').events).toHaveLength(0)
  })

  test('一次拖拽的完整序列', () => {
    const { events } = extractMouseEvents(
      '\x1b[<0;3;2M' + '\x1b[<32;6;2M' + '\x1b[<32;9;3M' + '\x1b[<0;9;3m',
    )
    expect(events.map(e => e.type)).toEqual(['down', 'drag', 'drag', 'up'])
  })

  test('鼠标序列被剥离，普通按键从 rest 原样交回 ink', () => {
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
