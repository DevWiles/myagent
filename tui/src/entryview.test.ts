import { describe, expect, test } from 'bun:test'
import { EXPANDED_OUTPUT_BUDGET, outputPreview, perCallBudget } from './components/EntryView.tsx'

describe('展开后的输出行数预算', () => {
  test('单条调用可以用掉整份预算', () => {
    expect(perCallBudget(1)).toBe(EXPANDED_OUTPUT_BUDGET)
  })

  test('多条调用均摊，总量不超预算——这是刷屏 bug 的回归点', () => {
    for (const n of [2, 3, 4, 8]) {
      expect(perCallBudget(n) * n).toBeLessThanOrEqual(EXPANDED_OUTPUT_BUDGET + n * 3)
    }
    // 4 条读文件时，单条不该再拿 20 行（旧行为），而是均摊到 6 行
    expect(perCallBudget(4)).toBe(6)
  })

  test('条数很多时保底每条 3 行，不至于什么都看不到', () => {
    expect(perCallBudget(100)).toBe(3)
  })

  test('条数为 0 或负数不会除零/爆掉', () => {
    expect(perCallBudget(0)).toBeGreaterThan(0)
    expect(perCallBudget(-1)).toBeGreaterThan(0)
  })
})

describe('输出截断', () => {
  test('不超限时原样返回，不加省略提示', () => {
    expect(outputPreview('a\nb', 5)).toEqual(['a', 'b'])
  })

  test('超限时截断并注明还剩多少行', () => {
    const out = outputPreview('1\n2\n3\n4\n5', 2)
    expect(out).toEqual(['1', '2', '… 还有 3 行'])
  })

  test('折叠态 limit=1 只露一行', () => {
    expect(outputPreview('1\n2\n3', 1)).toEqual(['1', '… 还有 2 行'])
  })

  test('空输出不崩', () => {
    expect(() => outputPreview('', 5)).not.toThrow()
  })
})
