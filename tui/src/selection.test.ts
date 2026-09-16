import { describe, expect, test } from 'bun:test'
import {
  EMPTY_SELECTION, clearSelection, endSelection, extendSelection,
  hasSelection, normalized, rowRange, selectedText, startSelection,
} from './selection.ts'

const LINES = ['第一行内容', 'abcdefghij', 'third line here', '末行']

describe('选区状态机', () => {
  test('按下即建立选区，但单点不算「有选中」', () => {
    const s = startSelection({ row: 1, col: 3 })
    expect(s.dragging).toBe(true)
    expect(hasSelection(s)).toBe(false)   // anchor === focus
  })

  test('拖动后才算有选中', () => {
    const s = extendSelection(startSelection({ row: 1, col: 3 }), { row: 1, col: 7 })
    expect(hasSelection(s)).toBe(true)
  })

  test('没按下时拖动事件不改选区（防止误扩选）', () => {
    const s = extendSelection(EMPTY_SELECTION, { row: 2, col: 2 })
    expect(s).toEqual(EMPTY_SELECTION)
  })

  test('松手保留选区、只停掉拖拽', () => {
    const s = endSelection(extendSelection(startSelection({ row: 0, col: 0 }), { row: 1, col: 4 }))
    expect(s.dragging).toBe(false)
    expect(hasSelection(s)).toBe(true)
  })

  test('clearSelection 清空', () => {
    expect(clearSelection()).toEqual(EMPTY_SELECTION)
  })
})

describe('方向归一', () => {
  test('从右往左拖，归一后 start 在前', () => {
    const s = extendSelection(startSelection({ row: 1, col: 8 }), { row: 1, col: 2 })
    expect(normalized(s)).toEqual({ start: { row: 1, col: 2 }, end: { row: 1, col: 8 } })
  })

  test('从下往上拖，归一后 start 在上', () => {
    const s = extendSelection(startSelection({ row: 3, col: 1 }), { row: 1, col: 5 })
    expect(normalized(s)).toEqual({ start: { row: 1, col: 5 }, end: { row: 3, col: 1 } })
  })
})

describe('行内列区间（高亮用）', () => {
  test('单行选区就是 start..end', () => {
    const s = extendSelection(startSelection({ row: 1, col: 2 }), { row: 1, col: 6 })
    expect(rowRange(s, 1)).toEqual({ from: 2, to: 6 })
  })

  test('跨行：首行到行尾、中间整行、末行到 endCol', () => {
    const s = extendSelection(startSelection({ row: 1, col: 4 }), { row: 3, col: 1 })
    expect(rowRange(s, 1)).toEqual({ from: 4, to: null })  // 首行
    expect(rowRange(s, 2)).toEqual({ from: 0, to: null })  // 中间
    expect(rowRange(s, 3)).toEqual({ from: 0, to: 1 })     // 末行
  })

  test('选区外的行返回 null', () => {
    const s = extendSelection(startSelection({ row: 1, col: 0 }), { row: 2, col: 3 })
    expect(rowRange(s, 0)).toBeNull()
    expect(rowRange(s, 3)).toBeNull()
  })
})

describe('取字', () => {
  test('单行取子串', () => {
    const s = extendSelection(startSelection({ row: 1, col: 2 }), { row: 1, col: 5 })
    expect(selectedText(s, LINES)).toBe('cde')
  })

  test('跨行拼接，中间行取整行', () => {
    const s = extendSelection(startSelection({ row: 1, col: 8 }), { row: 3, col: 1 })
    expect(selectedText(s, LINES)).toBe('ij\nthird line here\n末')
  })

  test('裁掉行尾空白——我们把每行铺满了空格，不裁会复制出一堆尾随空格', () => {
    const padded = ['hello     ', 'world     ']
    const s = extendSelection(startSelection({ row: 0, col: 0 }), { row: 1, col: 10 })
    expect(selectedText(s, padded)).toBe('hello\nworld')
  })

  test('中文按字符取，不会切坏多字节', () => {
    const s = extendSelection(startSelection({ row: 0, col: 1 }), { row: 0, col: 3 })
    expect(selectedText(s, LINES)).toBe('一行')
  })

  test('没有选中时返回空串', () => {
    expect(selectedText(startSelection({ row: 0, col: 0 }), LINES)).toBe('')
    expect(selectedText(EMPTY_SELECTION, LINES)).toBe('')
  })

  test('行号越界不崩', () => {
    const s = extendSelection(startSelection({ row: 0, col: 0 }), { row: 99, col: 5 })
    expect(() => selectedText(s, LINES)).not.toThrow()
  })
})
